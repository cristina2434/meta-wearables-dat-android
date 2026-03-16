package com.meta.wearable.dat.externalsampleapps.cameraaccess.retrofit

import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

/**
 * Interfaz de red
 * Define las rutas y metodos HTTP que usa la aplicacion para hablar con el backend.
 * Retrofit leera las etiquetas (@Multipart, @POST) y construira las peticiones
 * automaticamente por debajo
 */
interface FileApiService {
    // Endpoint
    @GET("healthcheck")
    suspend fun checkHealth(): Response<String>

    // Para que Retrofit sepa que se va a enviar un archivo (Multipart)
    // Suspend significa que esta funcion se ejecuta en segundo plano
    @Multipart
    @POST("analyzer/audio") // "ruta pendiente de la api"
    suspend fun uploadFile(
        @Part file: MultipartBody.Part
    ): Response<String> // Devuelve un string con la respuesta del LLM
}