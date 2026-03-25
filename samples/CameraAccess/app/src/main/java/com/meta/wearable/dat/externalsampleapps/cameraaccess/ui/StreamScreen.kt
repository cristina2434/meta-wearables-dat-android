/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// StreamScreen - DAT Camera Streaming UI
//
// This composable demonstrates the main streaming UI for DAT camera functionality. It shows how to
// display live video from wearable devices and handle photo capture.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.meta.wearable.dat.externalsampleapps.cameraaccess.mockdevicekit.MockDeviceKitViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.retrofit.FileViewModel
import kotlinx.coroutines.launch
@Composable
// Cambio la declaracion para usar mi ViewModel
fun StreamScreen(
    wearablesViewModel: WearablesViewModel,
    modifier: Modifier = Modifier,
    fileViewModel: FileViewModel = viewModel(),
    streamViewModel: StreamViewModel =
        viewModel(
            factory =
                StreamViewModel.Factory(
                    application = (LocalActivity.current as ComponentActivity).application,
                    wearablesViewModel = wearablesViewModel,
                ),
        ),
) {
  val streamUiState by streamViewModel.uiState.collectAsStateWithLifecycle()

  // Permitir lanzar tareas en segundo plano (corrutinas) al pulsar un boton,
  // sin congelar la interfaz grafica.
  val coroutineScope = rememberCoroutineScope()
  // Obtiene el entorno actual de Android. Se necesita obligatoriamente para
  // informar a FileUtils donde esta la carpeta fisica de cache del telefono
  val context = LocalContext.current

  // Estado para mostrar el dialogo de confirmacion tras grabar
  var showSendDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
  // Variable para guardar temporalmente el archivo del video grabado para enviarlo
  var recordedVideoFile by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<java.io.File?>(null)}
  // Estado de carga para el envio
  var isUploading by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
  LaunchedEffect(Unit) {
      // Pasar la Uri del MockDevice al StreamViewModel
      MockDeviceKitViewModel.lastSelectedVideoUri?.let {
          uri -> streamViewModel.setSimulatedVideoUri(uri)
      }
      streamViewModel.startStream()
      //streamViewModel.startAudioRecording(context)
  }

  Box(modifier = modifier.fillMaxSize()) {
    streamUiState.videoFrame?.let { videoFrame ->
      Image(
          bitmap = videoFrame.asImageBitmap(),
          contentDescription = stringResource(R.string.live_stream),
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Crop,
      )
    }
    if (streamUiState.streamSessionState == StreamSessionState.STARTING) {
      CircularProgressIndicator(
          modifier = Modifier.align(Alignment.Center),
      )
    }

    Box(modifier = Modifier.fillMaxSize().padding(all = 24.dp)) {
      Row(
          modifier =
              Modifier.align(Alignment.BottomCenter)
                  .navigationBarsPadding()
                  .fillMaxWidth()
                  .height(56.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        SwitchButton(
            label = stringResource(R.string.stop_stream_button_title),
            onClick = {
                // Lanzar la corrutina para enviar el video y apagar
                coroutineScope.launch{
                    // println("[StreamScreen] Boton stop stream pulsado. Iniciando envio del video simulado")
                    println("[StreamScreen] Boton stop stream pulsado. Deteniendo y guardando grabacion.")
                    // Pedir a StreamViewModel el archivo fisico

                    //val audioFile = streamViewModel.sendSimulatedAudio(context)
//                    val audioFile = streamViewModel.stopAudioRecordingAndGetFile()
//                    // Enviar a Retrofit y guardar la respuesta del LLM
//                    val llmRespone: String? = if (audioFile != null) {
//                        fileViewModel.sendFile(
//                            physicalFile = audioFile,
//                            typeMime = "audio/mp4",
//                            nameBackend = "file"
//                        )
//                    }
//                    else {
//                        println("[StreamScreen] No hay archivo de audio para enviar")
//                        null
//                    }
//
//                    if (llmRespone != null) {
//                        println("[StreamScreen] Respuesta del LLM: $llmRespone")
//                    }

                    // Detener grabacion de video y obtener el archivo
                    val videoFile = streamViewModel.stopVideoRecordingAndGetFile()

                    // Detener stream inmediatamente, para cancelar videoJob y evitar que entren mas fotogramas
                    streamViewModel.stopStream()

                    // Guardar en galeria
                    if (videoFile != null) {
                        // Guardar el archivo en la variable
                        recordedVideoFile = videoFile
                        streamViewModel.saveVideoToGallery(videoFile)
                        println("[StreamScreen] Video guardado en galeria. Abriendo dialogo.")
                        showSendDialog = true
//                        val llmResponse = fileViewModel.sendFile(
//                            physicalFile = videoFile,
//                            typeMime = "video/mp4",
//                            nameBackend = "file"
//                        )
//                        if(llmResponse != null) {
//                            println("[StreamScreen] Respuesta del LLM: $llmResponse")
//                        }
                    } else{
                        println("[StreamScreen] No se pudo obtener el archivo de video. Saliendo.")
                        wearablesViewModel.navigateToDeviceSelection()
                    }
//                    // Enviar a Retrofit
//                    if(videoFile != null) {
//                        // Guardar en galeria
//                        streamViewModel.saveVideoToGallery(videoFile)
//                        val llmResponse = fileViewModel.sendFile(
//                            physicalFile = videoFile,
//                            typeMime = "video/mp4",
//                            nameBackend = "file"
//                        )
//                        if(llmResponse != null) {
//                            println("[StreamScreen] Respuesta del LLM: $llmResponse")
//                        }
//                    } else{
//                        println("[StreamScreen] No se pudo obtener el archivo de video")
//                    }
                    // wearablesViewModel.navigateToDeviceSelection()
                }
            },
            isDestructive = true,
            modifier = Modifier.weight(1f),
        )

        // Photo capture button
        CaptureButton(
            onClick = {
                //streamViewModel.capturePhoto()
                // Lanzar corrutina al pulsar el boton
                coroutineScope.launch {
                    println("[StreamScreen] Boton capturar foto pulsado")

                    // Guardar la imagen en la galeria
                    streamUiState.videoFrame?.let { fotograma ->
                        streamViewModel.saveToGallery(fotograma)
                        println("[StreamScreen] Foto guardada en galeria")
                    }
//                    // Interceptar y guardar la imagen
//                    val saveFile = streamViewModel.saveCurrentFrame(context)
//
//                    // Si se ha guardado bien, pedimos al gestor de red que envie el archivo fisico
//                    if (saveFile != null) {
//                        fileViewModel.sendFile(
//                            physicalFile = saveFile,
//                            typeMime = "image/jpeg",
//                            nameBackend = "file"
//                        )
//                    }
                }
            },
        )
      }
    }
  }

  // Dialogo para elegir que tipo de archivo enviar
  if(showSendDialog) {
      androidx.compose.material3.AlertDialog(
          onDismissRequest = {
              // Si el usuario pulsa fuera, se cancela la accion y se vuelve al menu anterior
              recordedVideoFile?.delete()
              showSendDialog = false
              wearablesViewModel.navigateToDeviceSelection()
          },
          title = { androidx.compose.material3.Text("Recording Finished")},
          text = { androidx.compose.material3.Text("What type of file do you want to upload to the server for analysis?")},
          confirmButton = {
              androidx.compose.material3.TextButton(
                  onClick = {
                      coroutineScope.launch {
                          showSendDialog = false
                          isUploading = true
                          recordedVideoFile?.let { file ->
                              println("[StreamScreen] Enviando video.")
                              val response = fileViewModel.sendFile(file, "video/mp4", "file")
                              handleUploadResponse(context, response)

                              file.delete()
                          }
                          isUploading = false
                          wearablesViewModel.navigateToDeviceSelection()
                      }
                  }
              ) {
                  androidx.compose.material3.Text("Send video")
              }
          },
          dismissButton = {
              androidx.compose.material3.TextButton(
                  onClick = {
                      coroutineScope.launch {
                          showSendDialog = false
                          isUploading = true
                          recordedVideoFile?.let { file ->
                              println("[StreamScreen] Enviando solo audio.")
                              val response = fileViewModel.sendFile(file, "audio/mp4", "file")
                              handleUploadResponse(context, response)

                              file.delete()
                          }
                          isUploading = false
                          wearablesViewModel.navigateToDeviceSelection()
                      }
                  }
              ) {
                  androidx.compose.material3.Text("Send audio only")
              }
          }
      )
  }

  // Pantalla de carga
  if(isUploading) {
      Box (
          modifier = Modifier
              .fillMaxSize()
              .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f))
              .clickable(enabled = false) {},
          contentAlignment = Alignment.Center
      ) {
          Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(16.dp)
          ) {
              CircularProgressIndicator(color = AppColor.DeepBlue)
              androidx.compose.material3.Text(
                  text = "Sending recorded file to server...",
                  color = androidx.compose.ui.graphics.Color.White,
                  style = androidx.compose.material3.MaterialTheme.typography.titleMedium
              )
          }
      }
  }
  streamUiState.capturedPhoto?.let { photo ->
    if (streamUiState.isShareDialogVisible) {
      SharePhotoDialog(
          photo = photo,
          onDismiss = { streamViewModel.hideShareDialog() },
          onShare = { bitmap ->
            streamViewModel.sharePhoto(bitmap)
            streamViewModel.hideShareDialog()
          },
      )
    }
  }
}

// Funcion auxiliar para mostrar el resultado en StreamScreen
private suspend fun handleUploadResponse(context: android.content.Context, response: String?) {
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
        if (response != null) {
            println("[StreamScreen] Exito. Respuesta del LLM: $response")
            android.widget.Toast.makeText(context, "¡Éxito, envío y análisis completados!", android.widget.Toast.LENGTH_LONG).show()
        }
        else {
            println("[StreamScreen] Error al enviar el archivo.")
            android.widget.Toast.makeText(context, "Error al enviar el archivo al servidor", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}