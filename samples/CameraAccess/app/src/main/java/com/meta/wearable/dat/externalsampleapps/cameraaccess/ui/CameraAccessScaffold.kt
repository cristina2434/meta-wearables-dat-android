/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// CameraAccessScaffold - DAT Application Navigation Orchestrator
//
// This scaffold demonstrates a typical DAT application navigation pattern based on device
// registration and streaming states from the DAT API.
//
// DAT State-Based Navigation:
// - HomeScreen: When NOT registered (uiState.isRegistered = false) Shows initial registration UI
//   calling Wearables.startRegistration()
// - NonStreamScreen: When registered (uiState.isRegistered = true) but not streaming Shows device
//   selection, permission checking, and pre-streaming setup
// - StreamScreen: When actively streaming (uiState.isStreaming = true) Shows live video from
//   StreamSession.videoStream and photo capture UI
//
// The scaffold also provides a debug menu (in DEBUG builds) that gives access to
// MockDeviceKitScreen for testing DAT functionality without physical devices.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import androidx.compose.runtime.setValue
import androidx.compose.ui.composed
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.retrofit.FileViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.graphics.Color
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import java.util.Locale
import androidx.compose.runtime.DisposableEffect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraAccessScaffold(
    viewModel: WearablesViewModel,
    onRequestWearablesPermission: suspend (Permission) -> PermissionStatus,
    modifier: Modifier = Modifier,
    fileViewModel: FileViewModel = viewModel()
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val snackbarHostState = remember { SnackbarHostState() }
  val bottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  // Estado para controlar si el menu de subir archivos esta visible
  var isUploadMenuVisible by remember { androidx.compose.runtime.mutableStateOf(false) }

  // Variables para el envio de archivos
  val context = androidx.compose.ui.platform.LocalContext.current
  val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
  var isUploading by remember { androidx.compose.runtime.mutableStateOf(false) } // Estado de carga
  // Estado para mostrar la respuesta del LLM
 // var llmResponseText by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
  //var showResponseDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    // Lista del historial global
    val responseHistory = remember { androidx.compose.runtime.mutableStateListOf<String>() }
  //Text to speach
  var tts by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<android.speech.tts.TextToSpeech?>(null) }
    // Inicializar el motor y configurarlo para que se destruya al salir
    androidx.compose.runtime.DisposableEffect(context) {
        var ttsEngine: TextToSpeech? = null
        ttsEngine = TextToSpeech(context) { status ->
            if(status == TextToSpeech.SUCCESS) {
                ttsEngine?.language = Locale("es", "ES")
                println("[StreamScreen] Motor TTS iniacializado correctamente")
            } else {
                println("[StreamScreen] Error al inicializar el motor TTS")
            }
        }

        tts = ttsEngine

        onDispose {
            ttsEngine.stop()
            ttsEngine.shutdown()
            println("[StreamScreen] Motor TTS apagado y memoria liberada")
        }
    }

  // Observe camera permission errors and show snackbar
  LaunchedEffect(uiState.recentError) {
    uiState.recentError?.let { errorMessage ->
      snackbarHostState.showSnackbar(errorMessage)
      viewModel.clearCameraPermissionError()
    }
  }

  Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Box(modifier = Modifier.fillMaxSize()) {
      when {
        uiState.isStreaming ->
            StreamScreen(
                wearablesViewModel = viewModel,
            )
        uiState.isRegistered ->
            NonStreamScreen(
                viewModel = viewModel,
                onRequestWearablesPermission = onRequestWearablesPermission,
            )
        else ->
            HomeScreen(
                viewModel = viewModel,
            )
      }

      SnackbarHost(
          hostState = snackbarHostState,
          modifier =
              Modifier
                  .align(Alignment.BottomCenter)
                  .navigationBarsPadding()
                  .padding(horizontal = 16.dp, vertical = 32.dp),
          snackbar = { data ->
            Snackbar(
                shape = RoundedCornerShape(24.dp),
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = "Camera Access error",
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(data.visuals.message)
              }
            }
          },
      )


      // Boton flotante para abrir el menu de enviar archivos
      // Solo se muestra si no se esta en el estado de streaming
      if (!uiState.isStreaming) {
          FloatingActionButton(
              onClick = { isUploadMenuVisible = true},
              modifier = Modifier.align(Alignment.CenterStart),
              containerColor = AppColor.DeepBlue,
              contentColor = androidx.compose.ui.graphics.Color.White
          ) {
              Icon(Icons.Default.CloudUpload, contentDescription = "Upload file")
          }
      }

      // BottomSheet que muestra UploadMediaScreen
      if(isUploadMenuVisible) {
          ModalBottomSheet(
              onDismissRequest = {isUploadMenuVisible = false},
              sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
          ) {
              UploadMediaScreen(
                  onVideoSelected = { uri ->
                      println("[Upload] Video seleccionado: $uri")
                      isUploadMenuVisible = false
                      uploadUriToServer(uri, "video/mp4", context, coroutineScope, fileViewModel, { isUploading = it })  { text ->
//                          llmResponseText = text
//                          showResponseDialog = true
                          responseHistory.add(text)
                          tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
                      }
                  },
                  onImageSelected = { uri ->
                      println("[Upload] Imagen seleccionado: $uri")
                      isUploadMenuVisible = false
                      uploadUriToServer(uri, "image/jpeg", context, coroutineScope, fileViewModel, { isUploading = it }) { text ->
//                          llmResponseText = text
//                          showResponseDialog = true
                          responseHistory.add(text)
                          tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
                      }
                  },
                  onAudioSelected = { uri ->
                      println("[Upload] Audio seleccionado (desde video): $uri")
                      isUploadMenuVisible = false
                      uploadUriToServer(uri, "audio/mp4", context, coroutineScope, fileViewModel, { isUploading = it }) { text ->
//                          llmResponseText = text
//                          showResponseDialog = true
                          responseHistory.add(text)
                          tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
                      }
                  }
              )
          }
      }
        if (isUploading) {
            Box (
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable(enabled = false) {}, // para evitar que el usuario toque otras cosas
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ){
                    CircularProgressIndicator(
                        color = AppColor.Green
                    )
                    Text(
                        text = "Sending to server...",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
//      // Dialogo para mostrar la respuesta del LLM
//      if (showResponseDialog && llmResponseText != null) {
//          androidx.compose.material3.AlertDialog(
//              onDismissRequest = {
//                  showResponseDialog = false
//              },
//              title = { androidx.compose.material3.Text("LLM Response")},
//              text = {
//                  androidx.compose.foundation.layout.Column(
//                      modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())
//                  ) {
//                      androidx.compose.material3.Text(llmResponseText!!)
//                  }
//              },
//              confirmButton = {
//                  androidx.compose.material3.TextButton(
//                      onClick = {
//                          showResponseDialog = false
//                      }
//                  ) {
//                      androidx.compose.material3.Text("Close")
//                  }
//              }
//          )
//      }
        // Historico de respuestas en el menu principal
        if(responseHistory.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.6f)
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("History of analysis results", color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Text(
                            text = "Clean",
                            color = Color(0xFF64B5F6),
                            modifier = Modifier.clickable{
                                tts?.stop()
                                responseHistory.clear()
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(responseHistory.size) { index ->
                            Text(
                                text = "IA: ${responseHistory[index]}",
                                color = Color.LightGray,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            androidx.compose.material3.Divider(
                                color = Color.DarkGray,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
            }
        }
      if (BuildConfig.DEBUG) {
        FloatingActionButton(
            onClick = { viewModel.showDebugMenu() },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
          Icon(Icons.Default.BugReport, contentDescription = "Debug Menu")
        }

        if (uiState.isDebugMenuVisible) {
          ModalBottomSheet(
              onDismissRequest = { viewModel.hideDebugMenu() },
              sheetState = bottomSheetState,
              modifier = Modifier.fillMaxSize(),
          ) {
            MockDeviceKitScreen(modifier = Modifier.fillMaxSize())
          }
        }
      }
    }
  }
}

// Funcion que convierte la Uri a archivo y enviar por retrofit
private fun uploadUriToServer(
    uri: android.net.Uri,
    mimeType: String,
    context: android.content.Context,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    fileViewModel: FileViewModel,
    setLoadingState: (Boolean) -> Unit,
    onSuccess: (String) -> Unit
) {
    coroutineScope.launch {
        setLoadingState(true) // mostrar spinner de cargando

        val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // Copiar la Uri de la galeria a un archivo temporal en nuestra cache
                val inputStream = context.contentResolver.openInputStream(uri)
                // Determinar la extension en base al tipo de archivo que se va a enviar
                val extension = if(mimeType.contains("image")) ".jpg" else ".mp4"
                val tempFile = java.io.File(context.cacheDir, "upload_temp_${System.currentTimeMillis()}$extension")
                inputStream?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                tempFile
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

        if (file != null && file.exists()) {
            // Enviar el archivo por retrofit
            val response = fileViewModel.sendFile(
                physicalFile = file,
                typeMime = mimeType,
                nameBackend = "file"
            )

            // Mostrar feedback al usuario
            if(response != null) {
                println("[Upload] Respuesta del LLM: $response")
                onSuccess(response) // Llamar al callback
                //android.widget.Toast.makeText(context, "¡Éxito, envío completado!", android.widget.Toast.LENGTH_LONG).show()
            } else {
                println("[Upload] Error al enviar el archivo")
                android.widget.Toast.makeText(context, "Error al enviar al servidor.", android.widget.Toast.LENGTH_LONG).show()
            }

            // Borrar el archivo temporal
            file.delete()
        } else {
            android.widget.Toast.makeText(context, "Error al leer el archivo de la galería.", android.widget.Toast.LENGTH_LONG).show()
        }

        setLoadingState(false) // ocultar spinner
    }
}
