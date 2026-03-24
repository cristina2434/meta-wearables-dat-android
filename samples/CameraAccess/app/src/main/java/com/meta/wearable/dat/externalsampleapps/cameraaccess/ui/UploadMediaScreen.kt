package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun UploadMediaScreen(
    modifier: Modifier = Modifier,
    onVideoSelected: (Uri) -> Unit,
    onImageSelected: (Uri) -> Unit,
    onAudioSelected: (Uri) -> Unit
) {
    // Lanzadores para abrir la galeria del telefono

    // Video
    val videoPickerLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) { uri: Uri?
            ->
            uri?.let { onVideoSelected(it) }
        }

    // Imagen
    val imagePickerLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) { uri: Uri?
            ->
            uri?.let { onImageSelected(it) }
        }

    // Audio, en realidad se selecciona un video pero al servidor se enviara como audio/mp4
    val audioPickerLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) { uri: Uri?
            ->
            uri?.let { onAudioSelected(it) }
        }

    // Interfaz

    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Send to server",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = AppColor.DeepBlue
        )

        Text(
            text = "Select a file from your gallery to send for analysis.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Boton para fotos
        SwitchButton(
            label = "Select and send photo",
            onClick = {imagePickerLauncher.launch("image/*")},
            modifier = Modifier.fillMaxWidth()
        )

        // Boton para videosPictures/MetaGlasses
        SwitchButton(
            label = "Select and send video",
            onClick = { videoPickerLauncher.launch("video/*")},
            modifier = Modifier.fillMaxWidth()
        )

        // Boton para audios
        SwitchButton(
            label = "Select video and send audio only",
            onClick = { audioPickerLauncher.launch("video/*")},
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}