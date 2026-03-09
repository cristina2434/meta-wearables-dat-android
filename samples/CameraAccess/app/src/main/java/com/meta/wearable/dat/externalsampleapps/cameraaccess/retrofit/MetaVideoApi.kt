package com.meta.wearable.dat.externalsampleapps.cameraaccess.retrofit

import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface VideoApiService {

    // Para que Retrofit sepa que se va a enviar un archivo (Multipart)
    // Suspend significa que esta funcion se ejecuta en segundo plano
    @Multipart
    @POST("ruta") // "ruta pendiente de la api"
    suspend fun uploadFile(
        @Part videoClip: MultipartBody.Part
    ): Response<Unit> // solo para saber si devuelve un codigo 200 (OK)
}