package com.example.network

import com.example.model.Song
import com.example.util.AppLogger
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class InnerTubeTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnail: String,
    val durationMs: Long = 180000L,
    val album: String = "Single"
)

object InnerTubeClient {
    private const val TAG = "InnerTubeClient"
    private const val API_KEY = "AIzaSyAO_vYgS7E0y-B0b-u_Jp21p8E50JQg"
    private const val BASE_URL = "https://www.youtube.com/youtubei/v1"

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Executes a high-fidelity search query on YouTube Music's InnerTube endpoint
     */
    suspend fun searchMusic(query: String): List<Song> = withContext(Dispatchers.IO) {
        try {
            val requestBodyJson = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20240101.01.00")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("query", query)
                // Filter specifically for songs/videos
                put("params", "Eg-KAQwIARAAGAAgACgAMgMQAQ==") 
            }

            val request = Request.Builder()
                .url("$BASE_URL/search?key=$API_KEY")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Referer", "https://music.youtube.com/")
                .post(requestBodyJson.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLogger.w(TAG, "InnerTube search failed with code: ${response.code}")
                    return@withContext emptyList()
                }

                val bodyString = response.body?.string() ?: return@withContext emptyList()
                return@withContext parseInnerTubeSearchResponse(bodyString)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error executing InnerTube search: ${e.message}")
            return@withContext emptyList()
        }
    }

    /**
     * Resolves up-next suggestions for a playing track to build infinite queues
     */
    suspend fun getSuggestions(videoId: String): List<Song> = withContext(Dispatchers.IO) {
        try {
            val requestBodyJson = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20240101.01.00")
                    })
                })
                put("videoId", videoId)
            }

            val request = Request.Builder()
                .url("$BASE_URL/next?key=$API_KEY")
                .post(requestBodyJson.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val bodyString = response.body?.string() ?: return@withContext emptyList()
                return@withContext parseInnerTubeSearchResponse(bodyString)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed fetching InnerTube suggestions: ${e.message}")
            return@withContext emptyList()
        }
    }

    private fun parseInnerTubeSearchResponse(jsonResponse: String): List<Song> {
        val songsList = mutableListOf<Song>()
        try {
            val root = JSONObject(jsonResponse)
            
            // Recursive scan for videoId segments
            extractVideoNodes(root, songsList)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Parsing InnerTube response failed: ${e.message}")
        }
        return songsList.distinctBy { it.id }
    }

    private fun extractVideoNodes(json: Any, out: MutableList<Song>) {
        when (json) {
            is JSONObject -> {
                if (json.has("videoId")) {
                    val videoId = json.optString("videoId")
                    if (videoId.isNotBlank()) {
                        val title = json.optJSONObject("title")?.let { extractText(it) } ?: "YouTube Music Track"
                        val artist = json.optJSONObject("author")?.let { extractText(it) } 
                            ?: json.optJSONObject("publisher")?.let { extractText(it) }
                            ?: "YouTube Artist"
                        val thumbnail = "https://img.youtube.com/vi/$videoId/0.jpg"

                        out.add(
                            Song(
                                id = "piped_$videoId",
                                title = title,
                                artist = artist,
                                albumArtUrl = thumbnail,
                                streamUrl = "https://www.youtube.com/watch?v=$videoId",
                                durationMs = 180000L,
                                genre = "YouTube Music",
                                album = "InnerTube Release"
                            )
                        )
                    }
                }
                
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = json.get(key)
                    extractVideoNodes(value, out)
                }
            }
            is JSONArray -> {
                for (i in 0 until json.length()) {
                    extractVideoNodes(json.get(i), out)
                }
            }
        }
    }

    private fun extractText(obj: JSONObject): String? {
        val simpleText = obj.optString("simpleText")
        if (simpleText.isNotBlank()) return simpleText
        
        val runs = obj.optJSONArray("runs")
        if (runs != null && runs.length() > 0) {
            val firstRun = runs.optJSONObject(0)
            return firstRun?.optString("text")
        }
        return null
    }
}
