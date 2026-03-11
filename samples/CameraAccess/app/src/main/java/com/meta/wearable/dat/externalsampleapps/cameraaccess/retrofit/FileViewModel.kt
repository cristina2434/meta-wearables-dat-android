package com.meta.wearable.dat.externalsampleapps.cameraaccess.retrofit

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

/**
 * Conecta la interfaz con las herramientas. Gestor de envios.
 * Se encarga de empaquetar archivos fisicos en formato Multipart
 * y coordinar su envio al servidor a traves de Retrofit.
 * Es generico: puede enviar fotos, videos o cualquier otro tipo de archivo (MIME type).
 */
class FileViewModel : ViewModel() {

    // Funcion principal que sirve para cualquier tipo de archivo (foto, videos,audios)
    fun sendFile(physicalFile: File, typeMime: String, nameBackend: String) {
        viewModelScope.launch {
            try {
                // Empaquetar el archivo para Retrofit
                println("[FileViewModel]Empaquetando archivo: ${physicalFile.name} ($typeMime)")
                val requestBody = physicalFile.asRequestBody(typeMime.toMediaTypeOrNull())

                val multipartPackage = MultipartBody.Part.createFormData(
                    nameBackend,
                    physicalFile.name,
                    requestBody
                )

                // Enviar a la API
                println("[FileViewModel]Enviando al servidor a traves de Retrofit")
                val response = RetrofitClient.api.uploadFile(multipartPackage)

                if (response.isSuccessful) {
                    println("[FileViewModel]¡Éxito! Archivo enviado correctamente.")
                    // Opcional, borrar  el archivo local para no ocupar espacio
                    // localFile.delete()
                }
                else {
                    println("[FileViewModel]Error del servidor: Codigo ${response.code()}")
                }
            } catch (e: Exception) {
                println("[FileViewModel]Error de red o de proceso: ${e.localizedMessage}")
            }
        }
    }
    /*
    // Funcion principal que se llama desde la interfaz de usuario
    fun processSendVideo(context: Context, mockData: ByteArray) {
        viewModelScope.launch {
            try {
                // Guardar el video del MockDeviceKit en el movil
                println("Guardando video localmente")
                val localFile = saveVideoCache(context, mockData)

                // Empaquetar el archivo para Retrofit
                println("Empaquetando el video")
                val requestBody = localFile.asRequestBody("video/mp4".toMediaTypeOrNull())

                // "video_file" nombre que se espera el en backend
                val multipartPackage = MultipartBody.Part.createFormData(
                    "video_file",
                    localFile.name,
                    requestBody
                )

                // Enviar a la API
                println("Enviando al servidor")
                val response = RetrofitClient.api.uploadFile(multipartPackage)

                if (response.isSuccessful) {
                    println("¡Éxito! Video enviado correctamente.")
                    // Opcional, borrar  el archivo local para no ocupar espacio
                    // localFile.delete()
                }
                else {
                    println("Error del servidor: Codigo ${response.code()}")
                }
            } catch (e: Exception) {
                println("Error de red o de proceso: ${e.localizedMessage}")
            }
        }

    }*/
}