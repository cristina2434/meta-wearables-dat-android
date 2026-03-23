/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// StreamViewModel - DAT Camera Streaming API Demo
//
// This ViewModel demonstrates the DAT Camera Streaming APIs for:
// - Creating and managing stream sessions with wearable devices
// - Receiving video frames from device cameras
// - Capturing photos during streaming sessions
// - Handling different video qualities and formats
// - Processing raw video data (I420 -> NV21 conversion)

/**
 * Viewmodel de streaming y hardware (gafas meta)
 * Se encarga de gestionar la conexion con las gafas (o el MockDeviceKit),
 * recibir el flujo de video en tiempo real y manejar la captura de fotogramas.
 * No sabe nada de red (Retrofit), su unico objetivo es interactuar con el dispositivo.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.StreamSession
import com.meta.wearable.dat.camera.startStreamSession
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.externalsampleapps.cameraaccess.retrofit.FileUtils
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import kotlinx.coroutines.Dispatchers
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.media.MediaPlayer
import android.content.ContentValues
import android.provider.MediaStore
import android.os.Build
import android.widget.Toast

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.compose.animation.core.animateDecay
import androidx.compose.material3.MediumTopAppBar
import com.facebook.quicklog.identifiers.Presence
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive


class StreamViewModel(
    application: Application,
    private val wearablesViewModel: WearablesViewModel,
) : AndroidViewModel(application) {

  companion object {
    private const val TAG = "StreamViewModel"
    private val INITIAL_STATE = StreamUiState()
  }

  private val deviceSelector: DeviceSelector = wearablesViewModel.deviceSelector
  private var streamSession: StreamSession? = null

  private val _uiState = MutableStateFlow(INITIAL_STATE)
  val uiState: StateFlow<StreamUiState> = _uiState.asStateFlow()

  private var videoJob: Job? = null
  private var stateJob: Job? = null

  // Guardar uri del video que se esta reproduciendo temporalmente
  private var currentVideoUri: android.net.Uri? = null

  // Reproductor de audio
  private var mediaPlayer: MediaPlayer? = null

  // Para grabar el audio
  private var audioRecorder: android.media.MediaRecorder? = null
  private var currentAudioFile: File? = null

  private var audioRecord: android.media.AudioRecord? = null
  private var audioTrackIndex = -1
  private var audioRecordingJob: Job? = null
  // Para grabar el video
  private var mediaCodec: MediaCodec? = null
  private var mediaMuxer: MediaMuxer? = null
  private var videoTrackIndex = -1
  private var muxerStarted = false
  private var currentVideoFile: File? = null
  private var frameWidth = 0
  private var frameHeight = 0
  private var recordingStartTimeUs = 0L

  private var encoderSurface: android.view.Surface? = null
  // MainActivity establece la Uri
  fun setSimulatedVideoUri(uri: android.net.Uri) {
    currentVideoUri = uri
  }
  fun startStream() {
    videoJob?.cancel()
    stateJob?.cancel()
    val streamSession =
        Wearables.startStreamSession(
                getApplication(),
                deviceSelector,
                StreamConfiguration(videoQuality = VideoQuality.MEDIUM, 24),
            )
            .also { streamSession = it }
    // Reproducir audio en paralelo
    currentVideoUri?.let { uri ->
      try {
        // Reproductor "invisible" con el archivo mp4
        mediaPlayer = MediaPlayer.create(getApplication(), uri)
        mediaPlayer?.isLooping = true
        mediaPlayer?.start()
        println("[StreamViewModel] Reproduciendo audio en segundo plano")
      } catch (e: Exception) {
        println("[StreamViewModel] Error al intentar reproducir el audio: ${e.message}")
      }
    }
    videoJob = viewModelScope.launch { streamSession.videoStream.collect { handleVideoFrame(it) } }
    stateJob =
        viewModelScope.launch {
          streamSession.state.collect { currentState ->
            val prevState = _uiState.value.streamSessionState
            _uiState.update { it.copy(streamSessionState = currentState) }

            // navigate back when state transitioned to STOPPED
            if (currentState != prevState && currentState == StreamSessionState.STOPPED) {
              stopStream()
              wearablesViewModel.navigateToDeviceSelection()
            }
          }
        }
  }

  fun stopStream() {
    videoJob?.cancel()
    videoJob = null
    stateJob?.cancel()
    stateJob = null
    streamSession?.close()
    streamSession = null

    // Apagar y destruir el audio
    mediaPlayer?.stop()
    mediaPlayer?.release()
    mediaPlayer = null

    // Limpiar grabacion audio real
    audioRecorder?.release()
    audioRecorder = null
    audioRecordingJob?.cancel()
    audioRecordingJob = null
    audioRecord?.stop()
    audioRecord?.release()
    audioRecord = null

    // Limpiar grabacion video real
    // mediaCodec?.release()
    // mediaMuxer?.release()
    try {mediaCodec?.stop(); mediaCodec?.release() } catch(_: Exception) {}
    try {if (muxerStarted) mediaMuxer?.stop(); mediaMuxer?.release() } catch(_: Exception) {}
    mediaCodec = null
    mediaMuxer = null
    muxerStarted = false
    encoderSurface?.release()
    encoderSurface = null

    _uiState.update { INITIAL_STATE }
  }

  // Devuelve un File? , puede ser nulo si falla
  suspend fun saveCurrentFrame(context: Context): File?{
    // Obtener el fotorgrama exacto que se esta mostrando en pantalla
    val currentBitmap = uiState.value.videoFrame

    return if(currentBitmap != null) {
      // Delegar el trabajo de compresion y guardado fisico a FileUtils
      FileUtils.saveBitmapCache(context, currentBitmap)
    } else {
      println("[StreamViewModel] No hay ningun fotograma de video disponible para guardar")
      null
    }
  }

  suspend fun sendSimulatedVideo(context: Context): File? {
    val uri = currentVideoUri
    if(uri == null) {
      println("[StreamViewModel] No hay Uri del video simulado.")
      return null
    }
    return withContext(Dispatchers.IO) {
      try{
        // Leer los bytes del video original desde la Uri
        val inputStream = context.contentResolver.openInputStream(uri)
        val bytes = inputStream?.readBytes()
        inputStream?.close()

        if(bytes != null) {
          FileUtils.saveVideoCache(context, bytes)
        }
        else {
          null
        }
      } catch (e: Exception) {
        println("[StreamViewModel] Error al preparar el video simulado: ${e.message}")
        null
      }
    }
  }

  suspend fun sendSimulatedAudio(context: Context): File? {
    return withContext(Dispatchers.IO) {
      try {
        println("[StreamViewModel] Preparando archivo de audio de prueba")

        // Abrir archivo desde los assests de la app
        val inputStream = context.assets.open("audio_hola.mp3")
        val bytes = inputStream.readBytes()
        inputStream.close()

        // Guardarlo temporalmente en cache
        val audioFile = File(context.cacheDir, "test_audio_${System.currentTimeMillis()}.mp3")
        audioFile.writeBytes(bytes)

        // LOG TEMPORAL: primeros bytes para identificar el formato real
        val header = bytes.take(4).map { it.toInt() and 0xFF }
        println("[StreamViewModel] Cabecera del audio simulado: $header")

        println("[StreamViewModel] Audio guardado temporalmente en: ${audioFile.absolutePath}")
        audioFile
      } catch (e: Exception) {
        println("[StreamViewModel] Error al preparar el audio simulado: ${e.message}")
        null
      }
    }
  }

  fun capturePhoto() {
    if (uiState.value.isCapturing) {
      Log.d(TAG, "Photo capture already in progress, ignoring request")
      return
    }

    if (uiState.value.streamSessionState == StreamSessionState.STREAMING) {
      Log.d(TAG, "Starting photo capture")
      _uiState.update { it.copy(isCapturing = true) }

      viewModelScope.launch {
        streamSession
            ?.capturePhoto()
            ?.onSuccess { photoData ->
              Log.d(TAG, "Photo capture successful")
              handlePhotoData(photoData)
              _uiState.update { it.copy(isCapturing = false) }
            }
            ?.onFailure {
              Log.e(TAG, "Photo capture failed")
              _uiState.update { it.copy(isCapturing = false) }
            }
      }
    } else {
      Log.w(
          TAG,
          "Cannot capture photo: stream not active (state=${uiState.value.streamSessionState})",
      )
    }
  }

  fun showShareDialog() {
    _uiState.update { it.copy(isShareDialogVisible = true) }
  }

  fun hideShareDialog() {
    _uiState.update { it.copy(isShareDialogVisible = false) }
  }

  fun sharePhoto(bitmap: Bitmap) {
    val context = getApplication<Application>()
    val imagesFolder = File(context.cacheDir, "images")
    try {
      imagesFolder.mkdirs()
      val file = File(imagesFolder, "shared_image.png")
      FileOutputStream(file).use { stream ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
      }

      val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
      val intent = Intent(Intent.ACTION_SEND)
      intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
      intent.putExtra(Intent.EXTRA_STREAM, uri)
      intent.type = "image/png"
      intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

      val chooser = Intent.createChooser(intent, "Share Image")
      chooser.flags = Intent.FLAG_ACTIVITY_NEW_TASK
      context.startActivity(chooser)
    } catch (e: IOException) {
      Log.e("StreamViewModel", "Failed to share photo", e)
    }
  }

  private fun handleVideoFrame(videoFrame: VideoFrame) {
    // VideoFrame contains raw I420 video data in a ByteBuffer
    val buffer = videoFrame.buffer
    val dataSize = buffer.remaining()
    val byteArray = ByteArray(dataSize)

    // Save current position
    val originalPosition = buffer.position()
    buffer.get(byteArray)
    // Restore position
    buffer.position(originalPosition)

    // Convert I420 to NV21 format which is supported by Android's YuvImage
    val nv21 = convertI420toNV21(byteArray, videoFrame.width, videoFrame.height)
    val image = YuvImage(nv21, ImageFormat.NV21, videoFrame.width, videoFrame.height, null)
    val out =
        ByteArrayOutputStream().use { stream ->
          image.compressToJpeg(Rect(0, 0, videoFrame.width, videoFrame.height), 50, stream)
          stream.toByteArray()
        }

    val bitmap = BitmapFactory.decodeByteArray(out, 0, out.size)
    _uiState.update { it.copy(videoFrame = bitmap) }

    // Si hay grabacion activa, codificar este fotograma
    if(currentVideoFile != null) {
      val timeUs = System.nanoTime() / 1000
      encodeVideoFrame(bitmap, timeUs)
    }
  }

  // Convert I420 (YYYYYYYY:UUVV) to NV21 (YYYYYYYY:VUVU)
  private fun convertI420toNV21(input: ByteArray, width: Int, height: Int): ByteArray {
    val output = ByteArray(input.size)
    val size = width * height
    val quarter = size / 4

    input.copyInto(output, 0, 0, size) // Y is the same

    for (n in 0 until quarter) {
      output[size + n * 2] = input[size + quarter + n] // V first
      output[size + n * 2 + 1] = input[size + n] // U second
    }
    return output
  }

  private fun handlePhotoData(photo: PhotoData) {
    val capturedPhoto =
        when (photo) {
          is PhotoData.Bitmap -> photo.bitmap
          is PhotoData.HEIC -> {
            val byteArray = ByteArray(photo.data.remaining())
            photo.data.get(byteArray)

            // Extract EXIF transformation matrix and apply to bitmap
            val exifInfo = getExifInfo(byteArray)
            val transform = getTransform(exifInfo)
            decodeHeic(byteArray, transform)
          }
        }
    _uiState.update { it.copy(capturedPhoto = capturedPhoto, isShareDialogVisible = true) }
  }

  // HEIC Decoding with EXIF transformation
  private fun decodeHeic(heicBytes: ByteArray, transform: Matrix): Bitmap {
    val bitmap = BitmapFactory.decodeByteArray(heicBytes, 0, heicBytes.size)
    return applyTransform(bitmap, transform)
  }

  private fun getExifInfo(heicBytes: ByteArray): ExifInterface? {
    return try {
      ByteArrayInputStream(heicBytes).use { inputStream -> ExifInterface(inputStream) }
    } catch (e: IOException) {
      Log.w(TAG, "Failed to read EXIF from HEIC", e)
      null
    }
  }

  private fun getTransform(exifInfo: ExifInterface?): Matrix {
    val matrix = Matrix()

    if (exifInfo == null) {
      return matrix // Identity matrix (no transformation)
    }

    when (
        exifInfo.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    ) {
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_180 -> {
        matrix.postRotate(180f)
      }
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
        matrix.postScale(1f, -1f)
      }
      ExifInterface.ORIENTATION_TRANSPOSE -> {
        matrix.postRotate(90f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_90 -> {
        matrix.postRotate(90f)
      }
      ExifInterface.ORIENTATION_TRANSVERSE -> {
        matrix.postRotate(270f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_270 -> {
        matrix.postRotate(270f)
      }
      ExifInterface.ORIENTATION_NORMAL,
      ExifInterface.ORIENTATION_UNDEFINED -> {
        // No transformation needed
      }
    }

    return matrix
  }

  private fun applyTransform(bitmap: Bitmap, matrix: Matrix): Bitmap {
    if (matrix.isIdentity) {
      return bitmap
    }

    return try {
      val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
      if (transformed != bitmap) {
        bitmap.recycle()
      }
      transformed
    } catch (e: OutOfMemoryError) {
      Log.e(TAG, "Failed to apply transformation due to memory", e)
      bitmap
    }
  }

  // Funcion para guardar las capturas de fotos que se hacen durante el streaming en la galeria del telefono
  fun saveToGallery(bitmap: Bitmap) {
    val context = getApplication<Application>()
    val filename = "Meta_Glasses_${System.currentTimeMillis()}.jpg"

    // Preparar la etiqueta para la galeria de Android
    val contentValues = ContentValues().apply {
      put(MediaStore.Images.Media.DISPLAY_NAME, filename)
      put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // Se pide que lo guarde en la carpeta "Pictures/MetaGlasses"
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MetaGlasses")
        put(MediaStore.Images.Media.IS_PENDING, 1)
      }
    }

    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

    // Guardar la imagen en la galeria
    try {
      if(uri != null) {
        resolver.openOutputStream(uri)?.use { outputStream ->
          bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          contentValues.clear()
          contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
          resolver.update(uri, contentValues, null, null)
        }

        // Informar con un mensaje en pantalla para confirmar
        viewModelScope.launch(Dispatchers.Main) {
          Toast.makeText(context, "¡Foto guardada en la galería!", Toast.LENGTH_SHORT).show()
        }
      }
    } catch (e: Exception) {
      Log.e("StreamViewModel", "Error al guardar en galería", e)
    }
  }

  // Graba el audio real en paralelo al video
  @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
  private fun startAudioRecordingModern(context: Context, audioFile: File) {

    audioRecorder = android.media.MediaRecorder(context).apply {
      setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
      setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
      setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
      setAudioSamplingRate(44100)
      setAudioEncodingBitRate(128000)
      setOutputFile(audioFile.absolutePath)
      prepare()
      start()
    }
//    // Comprobar que se tiene permiso antes de intentar grabar
//    val permissionGranted = androidx.core.content.ContextCompat.checkSelfPermission(
//      context,
//      android.Manifest.permission.RECORD_AUDIO
//    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
//
//    if (!permissionGranted) {
//      println("[StreamViewModel] Sin permiso de microfono, no se puede grabar audio real")
//      return
//    }
//    val audioFile = File(context.cacheDir, "glasses_audio_${System.currentTimeMillis()}.m4a")
//    currentAudioFile = audioFile

//    try {
//      mp3Recorder = MP3Recorder(audioFile).apply {
//        start()
//      }
//      println("[StreamViewModel] Grabacion MP3 real iniciada: ${audioFile.absolutePath}")
//    }
//    catch (e: Exception) {
//      println("[StreamViewModel] Error al iniciar grabacion MP3: ${e.message}")
//      mp3Recorder = null
//      currentAudioFile = null
//    }
//    try {
//      audioRecorder = android.media.MediaRecorder(context).apply {
//        setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
//        setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
//        setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
//        setAudioSamplingRate(44100)
//        setAudioEncodingBitRate(128000)
//        setOutputFile(audioFile.absolutePath)
//        prepare()
//        start()
//      }
//      println("[StreamViewModel] Grabacion de audio real iniciada: ${audioFile.absolutePath}")
//    } catch(e: Exception) {
//      println("[StreamViewModel] Error al iniciar grabacion de audio: ${e.message}")
//      audioRecorder = null
//      currentAudioFile = null
//    }
  }

  @Suppress("DEPRECATION")
  private fun startAudioRecordingLegacy(audioFile: File) {
    audioRecorder = android.media.MediaRecorder().apply {
      setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
      setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
      setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
      setAudioSamplingRate(44100)
      setAudioEncodingBitRate(128000)
      setOutputFile(audioFile.absolutePath)
      prepare()
      start()
    }
  }

  // Graba el audio real en paralelo al video
  fun startAudioRecording(context: Context) {
    // Comprobar que se tiene permiso antes de intentar grabar
    val permissionGranted = androidx.core.content.ContextCompat.checkSelfPermission(
      context,
      android.Manifest.permission.RECORD_AUDIO
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    if (!permissionGranted) {
      println("[StreamViewModel] Sin permiso de microfono, no se puede grabar audio real")
      return
    }

    val audioFile = File(context.cacheDir, "glasses_audio_${System.currentTimeMillis()}.m4a")
    currentAudioFile = audioFile

    try {
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        startAudioRecordingModern(context, audioFile)
      } else {
        startAudioRecordingLegacy(audioFile)
      }

      println("[StreamViewModel] Grabacion de audio real iniciada: ${audioFile.absolutePath}")
    } catch(e: Exception) {
      println("[StreamViewModel] Error al iniciar grabacion de audio: ${e.message}")
      audioRecorder = null
      currentAudioFile = null
    }
  }

  // Detiene la grabacion y devuelve el archivo para enviar
  fun stopAudioRecordingAndGetFile(): File? {
    return try {
      audioRecorder?.apply() {
        stop()
        release()
      }
      audioRecorder = null
      val file = currentAudioFile
      currentAudioFile = null

      // Verificar que el archivo tiene contenido real
    if (file != null && file.exists()) {
      val header = file.readBytes().take(4).map { it.toInt() and 0xFF }
      println("[StreamViewModel] Grabacion de audio real detenida. Archivo: ${file?.absolutePath}")
      println("Tamaño: ${file.length()} bytes. Cabecera: $header")
    }

      file
    } catch (e: Exception) {
      println("[StreamViewModel] Error al deneter la grabacion real: ${e.message}")
      audioRecorder = null
      null
    }
  }

  // Grabar video real cuando se utilizan las gafas
  fun startVideoRecording(context: Context) {
    // Se inicializa vacia, las dimensiones se configuran con el primer fotograma
    println("[StreamViewModel] Grabacion de video y audio preparada, esperando el primer fotograma")
    currentVideoFile = File(context.cacheDir, "glasses_video_${System.currentTimeMillis()}.mp4")

    // Reset por precaucion
    muxerStarted = false
    videoTrackIndex = -1
    audioTrackIndex = -1
    encoderSurface = null
  }

  // Funcion llamada intenamente desde handleVideoFrame cuando hay una grabacion activa
  private fun encodeVideoFrame(bitmap: Bitmap, presentationTimeUs: Long) {
    // Inicializar codec con las dimensiones reales del primer fotograma
    if(mediaCodec == null) {
      // Redondear dimensiones al multiplo de 16 mas cercano
      frameWidth = ((bitmap.width + 15) / 16) * 16
      frameHeight = ((bitmap.height + 15) / 16) * 16
      val file = currentVideoFile ?: return

      try {
        val format = MediaFormat.createVideoFormat(
          MediaFormat.MIMETYPE_VIDEO_AVC,
          frameWidth,
          frameHeight
        ).apply {
          setInteger(MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
          setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000) // 2 Mbps
          setInteger(MediaFormat.KEY_FRAME_RATE, 24)
          setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1)
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoderSurface = codec.createInputSurface()
        codec.start()
        mediaCodec = codec


        mediaMuxer = MediaMuxer(
          file.absolutePath,
          MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )

        recordingStartTimeUs = presentationTimeUs
        println("[StreamViewModel] Codec de video iniciado: ${frameWidth}x${frameHeight}")
      } catch (e: Exception) {
        println("[StreamViewModel] Error al iniciar codec de video: ${e.message}")
        mediaCodec = null
        mediaMuxer = null
        encoderSurface = null
        return
      }
    }

    val codec = mediaCodec ?: return
    val surface = encoderSurface ?: return

    try {
      // Dibujar el bitmap en la Surface del codec, Android gestiona la conversion de color
      val canvas = surface.lockCanvas(null)
      if(canvas != null) {
        // Escalar al tamaño exacto del codec (multiplo de 16)
        val srcRect = android.graphics.Rect(0,0, bitmap.width, bitmap.height)
        val dstRect = android.graphics.Rect(0,0, frameWidth, frameHeight)
        canvas.drawBitmap(bitmap, srcRect, dstRect, null)
        surface.unlockCanvasAndPost(canvas)
      }

      // Drenar frames codificados al muexer
      drainEncoder(codec, presentationTimeUs - recordingStartTimeUs, false)



//      val inputIndex = codec.dequeueInputBuffer(10_000)
//      if (inputIndex >= 0) {
//        val inputBuffer = codec.getInputBuffer(inputIndex) ?: return
//        inputBuffer.clear()
//
//        // Escalar el bitmap a las dimensiones exactas del codec antes de convertir
//        val scaledBitmap = if (bitmap.width != frameWidth || bitmap.height != frameHeight) {
//          android.graphics.Bitmap.createScaledBitmap(bitmap, frameWidth, frameHeight, false)
//        } else bitmap
//
//
//        val yuvBytes = bitmapToYuv420(scaledBitmap, frameWidth, frameHeight)
//        inputBuffer.put(yuvBytes)
//
//        codec.queueInputBuffer(
//          inputIndex, 0, yuvBytes.size,
//          presentationTimeUs - recordingStartTimeUs, 0
//        )
//      }
//
//      // Extraer fotogramas codificados y escribirlos en el archivo
//      val bufferInfo = MediaCodec.BufferInfo()
//      var outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
//
//      // Detectar formato de salida
//      if(outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
//        && !muxerStarted) {
//        addTracksAndStartMuxer()
//      }
//      while(outputIndex >= 0) {
//        val outputBuffer = codec.getOutputBuffer(outputIndex)
//
//        if(!muxerStarted && bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG !=0 ) {
//          addTracksAndStartMuxer()
////          // Primer buffer: cabecera del codec, se usa para iniciar el muxer
////          val muxer = mediaMuxer ?: return
////          val newFormat = codec.outputFormat
////          videoTrackIndex = muxer.addTrack(newFormat)
////          muxer.start()
////          muxerStarted = true
////          println("[StreamViewModel] Muxer iniciando, escribiendo video...")
//        } else if(muxerStarted && outputBuffer != null && bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
//          mediaMuxer?.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo)
//        }
//        codec.releaseOutputBuffer(outputIndex, false)
//        outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
      // }
    } catch(e: Exception) {
      println("[StreamViewModel] Error codificando fotograma: ${e.message}")
    }
  }

  // Extraer frames del codec y escribirlos al muxer
  private fun drainEncoder(codec: MediaCodec, presentationTimeUs: Long, endOfStream: Boolean) {
    val bufferInfo = MediaCodec.BufferInfo()

    if(endOfStream) {
      codec.signalEndOfInputStream()
    }

    var keepGoing = true
    while(keepGoing) {
      val outputIndex = codec.dequeueOutputBuffer(bufferInfo, if(endOfStream) 10_000L else 0L)
      when {
        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
          if(!endOfStream) keepGoing = false
        }
        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
          if(!muxerStarted) addTracksAndStartMuxer(codec)
        }
        outputIndex >= 0 -> {
          val outputBuffer = codec.getOutputBuffer(outputIndex)
          if(muxerStarted && outputBuffer != null && bufferInfo.size > 0 &&
            bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
            // Usar el timestamp real del frame
            bufferInfo.presentationTimeUs = presentationTimeUs
            mediaMuxer?.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo)
          }
          codec.releaseOutputBuffer(outputIndex, false)
          if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
            keepGoing = false
          }
        }
      }
    }
  }
  // Añadir pustas de video y audio al muxer e iniciarlo
  private fun addTracksAndStartMuxer(codec: MediaCodec) {
    if(muxerStarted) return
    val muxer = mediaMuxer ?: return
    //val codec = mediaCodec ?: return

    videoTrackIndex = muxer.addTrack(codec.outputFormat)

    // Configurar pista de audio AAC
    val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 1).apply {
      setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
      setInteger(MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
      setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
    }
    audioTrackIndex = muxer.addTrack(audioFormat)
    muxer.start()
    muxerStarted = true
    println("[StreamViewModel] Muxer iniciado con video y audio")

    // Iniciar grabacion de audio en corrutina paralela
    startAudioCapture()
  }

  // Captura audio con AudioRecord y lo escribe directamente al muxer
  private fun startAudioCapture() {
    val sampleRate = 44100
    val channelConfig = android.media.AudioFormat.CHANNEL_IN_MONO
    val audioFormat  = android.media.AudioFormat.ENCODING_PCM_16BIT
    val bufferSize = android.media.AudioRecord.getMinBufferSize(sampleRate,channelConfig, audioFormat)

    val permissionGranted = androidx.core.content.ContextCompat.checkSelfPermission(
      getApplication(), android.Manifest.permission.RECORD_AUDIO
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    if(!permissionGranted) {
      println("[StreamViewModel] Sin permiso de microfono para grabar audio en video")
      return
    }

    audioRecord = android.media.AudioRecord(
      android.media.MediaRecorder.AudioSource.MIC,
      sampleRate, channelConfig, audioFormat, bufferSize
    )

    // Codec AAC para comprimir el audio antes de escribirlo al muxer
    val audioCodecFormat = MediaFormat.createAudioFormat(
      MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1
    ).apply {
      setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
      setInteger(MediaFormat.KEY_AAC_PROFILE,
        android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
      setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize)
    }

    val audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
      configure(audioCodecFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
      start()
    }

    audioRecord?.startRecording()

    audioRecordingJob = viewModelScope.launch(Dispatchers.IO) {
      val pcmBuffer = ByteArray(bufferSize)
      var audioPresentationTimeUs = 0L
      val bytesPerSecond = sampleRate * 2

      try {
        while(isActive && muxerStarted) {
          val bytesRead = audioRecord?.read(pcmBuffer, 0, bufferSize) ?: break
          if(bytesRead <= 0) continue

          // Enviar PCM al codec AAC
          val inputIndex = audioCodec.dequeueInputBuffer(10_000)
          if(inputIndex >= 0) {
            val inputBuffer = audioCodec.getInputBuffer(inputIndex)!!
            inputBuffer.clear()
            inputBuffer.put(pcmBuffer,0,bytesRead)
            audioCodec.queueInputBuffer(inputIndex, 0, bytesRead, audioPresentationTimeUs, 0)
            audioPresentationTimeUs += bytesRead * 1_000_000L / bytesPerSecond
          }

          // Obtener frames AAC codificados y escribir al muxer
          val bufferInfo = MediaCodec.BufferInfo()
          var outputIndex = audioCodec.dequeueOutputBuffer(bufferInfo, 0)
          while(outputIndex >= 0) {
            val outputBuffer = audioCodec.getOutputBuffer(outputIndex)
            if(outputBuffer != null && bufferInfo.size > 0 &&
              bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
              mediaMuxer?.writeSampleData(audioTrackIndex, outputBuffer, bufferInfo)
            }
            audioCodec.releaseOutputBuffer(outputIndex, false)
            outputIndex = audioCodec.dequeueOutputBuffer(bufferInfo, 0)
          }
        }
      } finally {
        try { audioCodec.stop() } catch (e: Exception) {}
        try {  audioCodec.release() } catch (e: Exception) {}
        try { audioRecord?.stop() } catch (e: Exception) {}
        try { audioRecord?.release() } catch (e: Exception) {}
          audioRecord = null
          println("[StreamViewModel] Captura de audio finalizada")
      }
    }
  }
  suspend fun stopVideoRecordingAngGetFile(): File? {
    return try {
      // Parar flujo de fotogramas antes de tocar el codec
      videoJob?.cancel()
      videoJob?.join() // Esperar a que termine el ultimo frame
      videoJob = null

      // Drenar y cerrar el codec de video
      val codec = mediaCodec
      if(codec != null) {

        drainEncoder(codec, 0L, true)
        try{ codec.stop() } catch (e: Exception) {}
        try { codec.release() } catch (e: Exception) {}

        // Señal de fin de stream al codec
//        val inputIndex = codec.dequeueInputBuffer(10_000)
//        if(inputIndex >= 0) {
//          codec.queueInputBuffer(inputIndex, 0,0,0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
//        }


//        // Vaciar codec
//        val bufferInfo = MediaCodec.BufferInfo()
//        var outputIndex = codec.dequeueOutputBuffer(bufferInfo,10_000)
//        while(outputIndex >= 0 && bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0) {
//          val outputBuffer = codec.getOutputBuffer(outputIndex)
//          if(muxerStarted && outputBuffer != null && bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
//            mediaMuxer?.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo)
//          }
//          drainEncoder(codec, 0L, true)
//
//          codec.releaseOutputBuffer(outputIndex, false)
//          outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
//        }

      }

      // Esperar a que el hilo de audio termine antes de cerrar el muxer
      audioRecordingJob?.cancel()
      audioRecordingJob?.join()
      audioRecordingJob = null

      if(muxerStarted) {
        try {mediaMuxer?.stop()} catch (e: Exception) { println("[StreamViewModel] Muxer stop ignorado: ${e.message}") }
      }
      try { mediaMuxer?.release() } catch (e: Exception) { println("[StreamViewModel] Muxer release ignorado: ${e.message}") }

      encoderSurface?.release()
      encoderSurface = null
      mediaCodec = null
      mediaMuxer = null
      muxerStarted = false
      videoTrackIndex = -1

      val file = currentVideoFile
      currentVideoFile = null

      if(file != null && file.exists()) {
        println("[StreamViewModel] Video real grabado: ${file.absolutePath}" +
        "(${file.length() / 1024} KB)")
      }
      file
    } catch (e: Exception) {
      println("[StreamViewModel] Error al dentener grabacion de video: ${e.message}")

      try {mediaCodec?.release() } catch (ex: Exception) {}
      try { mediaMuxer?.release() } catch (ex: Exception) {}
      encoderSurface?.release()
      encoderSurface = null
      mediaCodec = null
      mediaMuxer = null
      null
    }
  }

  // Guardar el video grabado en la galeria
  fun saveVideoToGallery(videoFile: File) {
    val context = getApplication<Application>()
    val filename = "Meta_Glasses_${System.currentTimeMillis()}.mp4"

    val contentValues = ContentValues().apply {
      put(MediaStore.Video.Media.DISPLAY_NAME, filename)
      put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MetaGlasses")
        put(MediaStore.Video.Media.IS_PENDING, 1)
      }
    }

    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)

    // Guardar la imagen en la galeria
    try {
      if(uri != null) {
        resolver.openOutputStream(uri)?.use { outputStream ->
          videoFile.inputStream().copyTo(outputStream)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          contentValues.clear()
          contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
          resolver.update(uri, contentValues, null, null)
        }

        // Informar con un mensaje en pantalla para confirmar
        viewModelScope.launch(Dispatchers.Main) {
          Toast.makeText(context, "¡Video guardado en la galería!", Toast.LENGTH_SHORT).show()
        }
      }
    } catch (e: Exception) {
      Log.e("StreamViewModel", "Error al guardar en galería", e)
    }
  }

//  // Funcion que convierte Bitmap -> YUV420 para el MediaCodec
//  private fun bitmapToYuv420(bitmap: Bitmap, width: Int, height: Int): ByteArray {
//    val argb = IntArray(width * height)
//    bitmap.getPixels(argb, 0, width, 0,0,width,height)
//
//    val yuv = ByteArray(width * height * 3 / 2)
//    val size = width * height
//
//    for(i in argb.indices) {
//      val r = (argb[i] shr 16) and 0xFF
//      val g = (argb[i] shr 8) and 0xFF
//      val b = argb[i] and 0xFF
//
//      yuv[i] = ((66 * r + 129 * g + 25 * b + 128) shr 8).plus(16)
//        .coerceIn(0,255).toByte()
//    }
//    var uvIndex = size
//    var i = 0
//    while (i < height / 2) {
//      var j = 0
//      while(j < width / 2) {
//        val index = i * 2 * width + j * 2
//        val r = (argb[index] shr 16) and 0xFF
//        val g = (argb[index] shr 8) and 0xFF
//        val b = argb[index] and 0xFF
//
//        yuv[uvIndex++] = ((-38 * r - 74 * g + 112 * b + 128) shr 8).plus(128)
//          .coerceIn(0, 255).toByte() // U
//        yuv[uvIndex++] = ((112 * r - 94 * g - 18 * b + 128) shr 8).plus(128)
//          .coerceIn(0, 255).toByte() // V
//        j++
//      }
//      i++
//    }
//    return yuv
//  }
  override fun onCleared() {
    super.onCleared()
    stopStream()
    stateJob?.cancel()
  }

  class Factory(
      private val application: Application,
      private val wearablesViewModel: WearablesViewModel,
  ) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      if (modelClass.isAssignableFrom(StreamViewModel::class.java)) {
        @Suppress("UNCHECKED_CAST", "KotlinGenericsCast")
        return StreamViewModel(
            application = application,
            wearablesViewModel = wearablesViewModel,
        )
            as T
      }
      throw IllegalArgumentException("Unknown ViewModel class")
    }
  }
}