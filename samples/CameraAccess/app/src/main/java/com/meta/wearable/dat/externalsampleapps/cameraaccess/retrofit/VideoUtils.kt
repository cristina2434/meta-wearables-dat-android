package com.meta.wearable.dat.externalsampleapps.cameraaccess.retrofit
import android.content.Context
import java.io.File

// Dependiendo de como entregue el video el SDK de Meta, puede que de
// ByteArray, InputStream o Uri

// Con ByteArray la funcion asume que se tienen los bytes puros
fun saveVideoCache(context: Context, bytesVideo: ByteArray): File {
    // Crear un archivo temporal en la carpeta segura de la app
    val videoFile = File(context.cacheDir, "clip_${System.currentTimeMillis()}.mp4")

    // Escribir los datos del video en ese archivo
    videoFile.writeBytes(bytesVideo)

    return videoFile
}