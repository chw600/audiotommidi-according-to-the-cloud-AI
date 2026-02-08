package com.example.audiotomidi

import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

interface ApiService {

    @Multipart
    @POST("/upload")
    suspend fun uploadAudio(
        @Header("X-API-Key") apiKey: String,
        @Part file: MultipartBody.Part
    ): TaskResponse

    @GET("/task/{taskId}")
    suspend fun getTaskStatus(
        @Header("X-API-Key") apiKey: String,
        @Path("taskId") taskId: String
    ): TaskStatus

    @GET("/download/{filename}")
    suspend fun downloadMidiFile(
        @Header("X-API-Key") apiKey: String,
        @Path("filename") filename: String
    ): Response<ResponseBody>

    companion object {
        private const val CONNECT_TIMEOUT = 60L // 60 seconds
        private const val READ_TIMEOUT = 600L   // 10 minutes
        private const val WRITE_TIMEOUT = 600L  // 10 minutes

        fun create(baseUrl: String, isDebugMode: Boolean = false): ApiService {
            return if (isDebugMode) {
                createForDevelopment(baseUrl)
            } else {
                createForProduction(baseUrl)
            }
        }

        private fun createForProduction(baseUrl: String): ApiService {
            val okHttpClient = OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
                .build()

            return buildRetrofit(baseUrl, okHttpClient)
        }

        private fun createForDevelopment(baseUrl: String): ApiService {
            val okHttpClient = OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
                .sslSocketFactory(getInsecureSslSocketFactory(), getInsecureTrustManager())
                .hostnameVerifier { _, _ -> true } // Skip hostname verification for development
                .addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BODY
                })
                .build()

            return buildRetrofit(baseUrl, okHttpClient)
        }

        private fun buildRetrofit(baseUrl: String, okHttpClient: OkHttpClient): ApiService {
            return Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(ApiService::class.java)
        }

        private fun getInsecureTrustManager(): X509TrustManager {
            return object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
            }
        }

        private fun getInsecureSslSocketFactory(): SSLSocketFactory {
            return try {
                val trustManager = getInsecureTrustManager()
                val sslContext = SSLContext.getInstance("TLS").apply {
                    init(null, arrayOf(trustManager), SecureRandom())
                }
                sslContext.socketFactory
            } catch (e: Exception) {
                throw RuntimeException("Failed to create insecure SSL socket factory", e)
            }
        }
    }
}
