package com.example.safetyway

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClient {
    fun createTmapApi(): TmapSearchApi {
        return Retrofit.Builder()
            .baseUrl("https://apis.openapi.sk.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TmapSearchApi::class.java)
    }

    fun createSearchApi(): NaverSearchApi {
        val interceptor = Interceptor { chain ->
            val request = chain.request().newBuilder()
                // BuildConfig를 사용하여 숨겨둔 키를 불러옵니다.
                .addHeader("X-Naver-Client-Id", BuildConfig.NAVER_SEARCH_CLIENT_ID)
                .addHeader("X-Naver-Client-Secret", BuildConfig.NAVER_SEARCH_CLIENT_SECRET)
                .build()
            chain.proceed(request)
        }

        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()

        return Retrofit.Builder()
            .baseUrl("https://openapi.naver.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(NaverSearchApi::class.java)
    }

    fun createMapApi(): NaverMapApi {
        val interceptor = Interceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("X-NCP-APIGW-API-KEY-ID", BuildConfig.NAVER_MAP_CLIENT_ID)
                .addHeader("X-NCP-APIGW-API-KEY", BuildConfig.NAVER_MAP_CLIENT_SECRET)
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