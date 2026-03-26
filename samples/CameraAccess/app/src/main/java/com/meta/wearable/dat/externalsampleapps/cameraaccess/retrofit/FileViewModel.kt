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
    // Devuelve String? con la respuesta del LLM (o null si falla)
    suspend fun sendFile(physicalFile: File, typeMime: String, nameBackend: String): String? {
        return try {
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
                // Extraer el cuerpo de la respuesta que manda el servidor
                val llmResponse = response.body()

                println("[FileViewModel]¡Éxito! Archivo enviado correctamente.")
                // Opcional, borrar  el archivo local para no ocupar espacio
                // localFile.delete()
                llmResponse
            }
            else {
                println("[FileViewModel]Error del servidor: Codigo ${response.code()}")
                null
            }
        }catch (e: Exception) {
            println("[FileViewModel]Error de red o de proceso: ${e.localizedMessage}")
            null
        }
    }

    suspend fun sendSimulatedFile(physicalFile: File, typeMime: String, nameBackend: String): String? {
        return try {
            print("[FileViewModel] MODO MOCK ACTIVADO")
            println("[FileViewModel]Simulando empaquetando archivo: ${physicalFile.name} ($typeMime)")
            val requestBody = physicalFile.asRequestBody(typeMime.toMediaTypeOrNull())

            // Simular tiempo de espara del servidor
            kotlinx.coroutines.delay(4000)

            // Lista de respuestas falsas
            val mockResponses = listOf(
                "1) Palabras repetidas:\n   - Frecuencia baja: \"gafas\" (1 vez), \"aplicación\" (1 vez).\n   - Ejemplos textuales: \"probando estas gafas\" y \"y la aplicación\".\n\n2) Frases repetidas:\n   - No se detectan repeticiones innecesarias.\n\n3) Muletillas:\n   - Frecuencia baja: \"y\" (2 veces en una frase corta)",
                "No hay evidencia suficiente para realizar un análisis basado en la transcripción \"Hola, buenos días\", ya que es una expresión muy breve y no contiene elementos suficientes para evaluar los aspectos solicitados."
            )
            // Elegir respuesta al azar
            val llmResponse = mockResponses.random()

            println("[FileViewModel]¡Éxito! Respuesta simulada devuelta correctamente.")

            // Devolver respuesta falsa
            llmResponse
        }catch (e: Exception) {
            println("[FileViewModel]Error de red o de proceso: ${e.localizedMessage}")
            null
        }
    }

    // Wrapper no-suspend para llamadas "fire and forget" desde la UI si no necesitas el resultado
    fun sendFileAsync(physicalFile: File, typeMime: String, nameBackend: String) {
        viewModelScope.launch {
            sendFile(physicalFile, typeMime, nameBackend)
        }
    }
    // Funcion para probar la conexion
    fun testConnection() {
        viewModelScope.launch {
            try {
                println("[FielViewModel] Comprobando el estado del servidor (healthcheck)")
                val response = RetrofitClient.api.checkHealth()

                if(response.isSuccessful) {
                    val bodyText = response.body()
                    println("[FielViewModel] Servidor OK. Respuesta: $bodyText")
                }
                else {
                    println("[FileViewModel] El servidor respondio con error: ${response.code()}")
                }
            }
            catch (e: Exception) {
                println("[FileViewModel] Error de red al hacer ping: ${e.localizedMessage}")
            }
        }
    }
}