package com.example

import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

object RetrofitClient {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    // Base URL doesn't matter much as we'll pass @Url in the interface
    val api: LocationApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://example.com/") 
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(LocationApi::class.java)
    }
}
