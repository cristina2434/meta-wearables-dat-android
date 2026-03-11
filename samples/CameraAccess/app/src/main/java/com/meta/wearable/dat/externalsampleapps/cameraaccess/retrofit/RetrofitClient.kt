package com.meta.wearable.dat.externalsampleapps.cameraaccess.retrofit

import retrofit2.Retrofit

/**
 * Cliente HTTP (Singleton)
 * Este objeto configura y mantiene una unica conexion a internet
 * para toda la aplicacion. Aqui se define la URL del servidor y se
 * construye el "motor" de Retrofit que ejecutara las llamadas definidas en MetaFileApi.
 */
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