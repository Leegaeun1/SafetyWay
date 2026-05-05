package com.example.safetyway

import android.content.Context
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClient {

    fun createSearchApi(context: Context): NaverSearchApi {
        val interceptor = Interceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("X-Naver-Client-Id", context.getString(R.string.NAVER_SEARCH_CLIENT_ID))
                .addHeader(
                    "X-Naver-Client-Secret",
                    context.getString(R.string.NAVER_SEARCH_CLIENT_SECRET)
                )
                .build()
            chain.proceed(request)
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()

        return Retrofit.Builder()
            .baseUrl("https://openapi.naver.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(NaverSearchApi::class.java)
    }

    fun createMapApi(context: Context): NaverMapApi {
        val interceptor = Interceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("X-NCP-APIGW-API-KEY-ID", context.getString(R.string.NAVER_MAP_CLIENT_ID))
                .addHeader("X-NCP-APIGW-API-KEY", context.getString(R.string.NAVER_MAP_CLIENT_SECRET))
                .build()
            chain.proceed(request)
        }

        return Retrofit.Builder()
            .baseUrl("https://maps.apigw.ntruss.com/")
            .client(OkHttpClient.Builder().addInterceptor(interceptor).build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(NaverMapApi::class.java)
    }
}