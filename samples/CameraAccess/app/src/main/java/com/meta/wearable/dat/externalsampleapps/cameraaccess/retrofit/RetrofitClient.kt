package com.meta.wearable.dat.externalsampleapps.cameraaccess.retrofit

import retrofit2.Retrofit

object RetrofitClient {
    // En un futuro aqui ira la ip o dominio del servidor
    private const val BASE_URL = "https://servidor.com"

    val api: FileApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            // Probablemente añadir un .addConverterFactory(GsonConverterFactory.create())
            // si la api devuelve JSON, por ahora esto sirve para enviar el archivo bruto
            .build()
            .create(FileApiService::class.java)
    }
}


/*
.baseUrl("http://10.8.64.179") // local
 */