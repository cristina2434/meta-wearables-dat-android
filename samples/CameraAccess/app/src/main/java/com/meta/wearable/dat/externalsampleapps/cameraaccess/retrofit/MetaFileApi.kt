package com.meta.wearable.dat.externalsampleapps.cameraaccess.retrofit

import okhttp3.MultipartBody
import retrofit2.Response
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

    // Para que Retrofit sepa que se va a enviar un archivo (Multipart)
    // Suspend significa que esta funcion se ejecuta en segundo plano
    @Multipart
    @POST("ruta") // "ruta pendiente de la api"
    suspend fun uploadFile(
        @Part file: MultipartBody.Part
    ): Response<Unit> // solo para saber si devuelve un codigo 200 (OK)
}