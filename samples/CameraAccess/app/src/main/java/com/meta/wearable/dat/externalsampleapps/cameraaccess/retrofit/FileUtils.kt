package com.meta.wearable.dat.externalsampleapps.cameraaccess.retrofit
import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Utilidades de almacenamiento (I/O)
 * Centraliza toda la logica para guardar datos temporales en la memoria fisca del movil.
 * Utiliza hilos secundarios para asegurar que la compresion de imagenes o
 * la escritura de videos no congelan la pantalla principal de la aplicacion.
 */
object FileUtils{

    // Para guardar fotos
    // Devuelve un File? , puede ser nulo si falla
    suspend fun saveBitmapCache(context: Context, bitmap: Bitmap): File?{
        // withContext(Dispatchers.IO) mueve esta tarea a un hilo secundario
        // optimizado para leer y escribir archivos, sin tocar la interfaz grafica
        return withContext(Dispatchers.IO) {
            try {
                // Crear archivo temporal en la memoria cache del movil
                val photoFile = File(context.cacheDir, "test_frame_${System.currentTimeMillis()}.jpg")

                // Escribir (comprimir) la imagen dentro de este archivo fisico
                FileOutputStream(photoFile).use { out->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
                }
                println("[FileUtils] Foto guardada en: ${photoFile.absolutePath}")
                println("[FileUtils] Tamaño del archivo: ${photoFile.length() / 1024} KB")
                photoFile
            }
            catch (e: Exception) {
                println("[FileUtils] Error al guardar el fotograma: ${e.message}")
                null
            }
        }
    }

    // Para guardar videos
    // Con ByteArray la funcion asume que se tienen los bytes puros
    suspend fun saveVideoCache(context: Context, bytesVideo: ByteArray): File? {
        return withContext(Dispatchers.IO) {
            try{
                // Crear un archivo temporal en la carpeta segura de la app
                val videoFile = File(context.cacheDir, "test_clip_${System.currentTimeMillis()}.mp4")
                // Escribir los datos del video en ese archivo
                videoFile.writeBytes(bytesVideo)
                println("[FileUtils] Video guardado en: ${videoFile.absolutePath}")
                videoFile
            }catch (e: Exception) {
                println("[FileUtils] Error al guardar el video: ${e.message}")
                null
            }
        }
    }
}

