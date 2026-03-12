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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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

  LaunchedEffect(Unit) {
      // Pasar la Uri del MockDevice al StreamViewModel
      MockDeviceKitViewModel.lastSelectedVideoUri?.let {
          uri -> streamViewModel.setSimulatedVideoUri(uri)
      }
      streamViewModel.startStream()
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
                    println("[StreamScreen] Boton stop stream pulsado. Iniciando envio del video simulado")

                    // Pedir a StreamViewModel el archivo fisico
                    val videoFile = streamViewModel.sendSimulatedVideo(context)

                    // Enviar a Retrofit
                    if(videoFile != null) {
                        fileViewModel.sendFile(
                            physicalFile = videoFile,
                            typeMime = "video/mp4",
                            nameBackend = "video_file"
                        )
                    }
                    // Una vez enviado, apagar el stream
                    streamViewModel.stopStream()
                    wearablesViewModel.navigateToDeviceSelection()
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
                    println("[StreamScreen] Boton pulsado: iniciando flujo de captura y envio")

                    // Interceptar y guardar la imagen
                    val saveFile = streamViewModel.saveCurrentFrame(context)

                    // Si se ha guardado bien, pedimos al gestor de red que envie el archivo fisico
                    if (saveFile != null) {
                        fileViewModel.sendFile(
                            physicalFile = saveFile,
                            typeMime = "image/jpeg",
                            nameBackend = "archivo_imagen"
                        )
                    }
                }
            },
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
