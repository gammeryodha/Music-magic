package com.example.network

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class BetterLyricsSearchResponse(
    val id: Long? = null,
    val trackName: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val duration: Double? = null,
    val instrumental: Boolean? = null,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null
)

interface BetterLyricsApiService {
    @GET("search")
    suspend fun searchLyrics(
        @Query("q") query: String,
        @Query("track_name") trackName: String? = null,
        @Query("artist_name") artistName: String? = null
    ): List<BetterLyricsSearchResponse>

    @GET("lyrics")
    suspend fun getLyrics(
        @Query("track_name") trackName: String,
        @Query("artist_name") artistName: String,
        @Query("duration") durationInSeconds: Long? = null
    ): BetterLyricsSearchResponse
}

object BetterLyricsClient {
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    val api: BetterLyricsApiService = Retrofit.Builder()
        .baseUrl("https://api.betterlyrics.org/v1/") // Primary stable BetterLyrics.org API Gateway endpoint
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(BetterLyricsApiService::class.java)

    val fallbackApi: BetterLyricsApiService = Retrofit.Builder()
        .baseUrl("https://lrclib.net/api/") // High-capacity fallback provider integrated by BetterLyrics
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(BetterLyricsApiService::class.java)
}
