package com.meta.wearable.dat.externalsampleapps.cameraaccess.retrofit

import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
/**
 * Cliente HTTP (Singleton)
 * Este objeto configura y mantiene una unica conexion a internet
 * para toda la aplicacion. Aqui se define la URL del servidor y se
 * construye el "motor" de Retrofit que ejecutara las llamadas definidas en MetaFileApi.
 */
object RetrofitClient {
    // En un futuro aqui ira la ip o dominio del servidor
    private const val BASE_URL = "http://api-tfm-h6erc5bdeudegghv.francecentral-01.azurewebsites.net/"

    // Configurar tiempo de espera del cliente
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS) // Tiempo para establecer la conexion
        .readTimeout(120, TimeUnit.SECONDS)   // Tiempo esperando a que el LLM devuelva el texto
        .writeTimeout(120, TimeUnit.SECONDS)  // Tiempo para terminar de subir el archivo
        .build()

    val api: FileApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            // Cliente personalizado
            .client(okHttpClient)
            // Añadir conversor para poder leer respuestas en texto plano
            .addConverterFactory(ScalarsConverterFactory.create())
            .build()
            .create(FileApiService::class.java)
    }
}


/*
.baseUrl("http://10.8.64.179") // local
 */