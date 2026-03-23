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
import androidx.annotation.ContentView

class StreamViewModel(
    application: Application,
    private val wearablesViewModel: WearablesViewModel
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

  // Variables para la grabacion de audio
  private var audioRecorder: android.media.MediaRecorder? = null
  private var currentAudioFile: File? = null

  // Variables para la grabacion de video
  private var mediaRecorder: android.media.MediaRecorder? = null
  private var recordingSurface: android.view.Surface? = null
  private var currentVideoFile: File? = null
  private var isRecording  = false
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

    // Se inicia la grabacion usando el formato del primer frame
    if(!isRecording) {
      startVideoRecording(getApplication(), videoFrame.width, videoFrame.height)
    }

    // Pintar el bitmap actual en el archivo de video
    recordingSurface?.let{ surface ->
      if(surface.isValid) {
        try {
          val canvas = surface.lockCanvas(null)
          if(canvas != null) {
            // Limpiar el lienzo por completo
            canvas.drawColor(android.graphics.Color.BLACK)

            // Calcular tamaño total del lienzo de grabacion
            val destRect = android.graphics.Rect(0,0,canvas.width, canvas.height)

            // Dibujar el bitmap forzando a que encaje en el destRect
            canvas.drawBitmap(bitmap, null, destRect, null)
            surface.unlockCanvasAndPost(canvas)
          }
        } catch (e: Exception) {
          println("[StreamViewModel] Error dibujando fotograma en MediaRecorder: ${e.message}")
        }
      }

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
    try {
      audioRecorder = if(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        android.media.MediaRecorder(context) // API 31+
      } else {
        @Suppress("DEPRECATION")
        android.media.MediaRecorder()         // API 29-30
      }.apply {
        setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
        setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
        setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
        setAudioSamplingRate(44100)
        setAudioEncodingBitRate(128000)
        setOutputFile(audioFile.absolutePath)
        prepare()
        start()
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

  private fun startVideoRecording(context: Context, width: Int, height: Int) {
    if(isRecording) return

    val permissionGranted = androidx.core.content.ContextCompat.checkSelfPermission(
      context,
      android.Manifest.permission.RECORD_AUDIO
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    if(!permissionGranted) {
      println("[StreamViewModel] Sin permiso de microfono, no se puede grabar el video con audio")
      return
    }

    val videoFile = File(context.cacheDir, "glasses_video_${System.currentTimeMillis()}.mp4")
    currentVideoFile = videoFile

    try {
      mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        android.media.MediaRecorder(context)
      } else {
        @Suppress("DEPRECATION")
        android.media.MediaRecorder()
      }.apply {
        setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
        setVideoSource(android.media.MediaRecorder.VideoSource.SURFACE)
        setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)

        setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
        setVideoEncoder(android.media.MediaRecorder.VideoEncoder.H264)

        // Resolucion: mantener la que manda las gafas para no deformar
        setVideoSize(width, height)
        setVideoFrameRate(24)
        setVideoEncodingBitRate(1200000)
        setAudioEncodingBitRate(64000)
        setAudioSamplingRate(44100)
        setOutputFile(videoFile.absolutePath)

        prepare()
      }

      recordingSurface = mediaRecorder?.surface
      mediaRecorder?.start()
      isRecording = true

      println("[StreamViewModel] Grabacion de video iniciada en: ${videoFile.absolutePath}")
    } catch (e: Exception) {
      println("[StreamViewModel] Error al  iniciar grabacion de video: ${e.message}")
      mediaRecorder = null
      currentVideoFile = null
      isRecording = false
    }
  }

  fun stopVideoRecordingAndGetFile(): File? {
    if (!isRecording) return null

    return try {
      mediaRecorder?.apply {
        stop()
        release()
      }
      recordingSurface?.release()

      val file = currentVideoFile

      mediaRecorder = null
      recordingSurface = null
      currentVideoFile = null
      isRecording = false

      // Verificar que el archivo tiene contenido real
      if (file != null && file.exists()) {
        println("[StreamViewModel] Grabacion de video real detenida. Tamaño: ${file.length() / 1024} KB")
      }
      file
    } catch (e: Exception) {
      println("[StreamViewModel] Error al deneter la grabacion de video real: ${e.message}")
      mediaRecorder = null
      recordingSurface = null
      isRecording = false
      null
    }
  }

  fun saveVideoToGallery(videoFile: File) {
    val context = getApplication<Application>()
    val filename = videoFile.name

    val contentValues = ContentValues().apply {
      put(MediaStore.Video.Media.DISPLAY_NAME, filename)
      put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
      if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // API 29+
        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MetaGlasses")
        put(MediaStore.Video.Media.IS_PENDING, 1)
      }
    }

    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)

    try{
      if (uri != null) {
        resolver.openOutputStream(uri)?.use{ outputStream ->
          videoFile.inputStream().use { inputStream ->
            inputStream.copyTo(outputStream)
          }
        }
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          contentValues.clear()
          contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
          resolver.update(uri, contentValues, null, null)
        }

        viewModelScope.launch(Dispatchers.Main) {
          Toast.makeText(context, "¡Vídeo guardado en la galería", Toast.LENGTH_SHORT).show()
        }
        println("[StreamViewModel] Video guardado en galeria")
      }
    } catch (e: Exception) {
      println("[StreamViewModel] Error al guardar video en galeria: ${e.message}")
    }
  }
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
/*
         // En un futuro llamar a RetrofitClient para enviar este archivo
         // Enviar el fotograma capturado por Retrofit
         println("Empaquetando la imagen para enviarla")
         val requestBody = photoFile.asRequestBody("image/jpeg".toMediaTypeOrNull())
         val multipartPackage = MultipartBody.Part.createFormData(
           "archivo_imagen", //
           photoFile.name,
           requestBody
         )

         println("Enviando al servidor a traves de Retrofit")
         // Llamar a la API
         val response = RetrofitClient.api.uploadFile(multipartPackage)
         if(response.isSuccessful) {
           println("¡Subida al servidor completada con exito!")
         }
         else {
           println("Error del servidor: Codigo ${response.code()}")
         }*/