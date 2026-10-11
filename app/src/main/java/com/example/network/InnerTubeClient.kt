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

enum class InnerTuneSearchFilter(val displayName: String, val paramKey: String?) {
    ALL("All", null),
    SONGS("Songs", "Eg-KAQwIARAAGAAgACgAMgMQAQ=="),
    ALBUMS("Albums", "Eg-KAQwIAhABGAEgASgAMgMQAQ=="),
    ARTISTS("Artists", "Eg-KAQwIAxABGAEgASgAMgMQAQ=="),
    PLAYLISTS("Playlists", "Eg-KAQwIABAAGAAgACgAMgMQAQ==")
}

@JsonClass(generateAdapter = true)
data class InnerTuneTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnail: String,
    val durationMs: Long = 180000L,
    val album: String = "Single"
)

/**
 * High-performance YouTube Music client based on InnerTune (z-huang/innertune)
 * Interacts directly with YouTube Music's WEB_REMIX InnerTube endpoints.
 */
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
     * with support for InnerTune category filters (Songs, Albums, Artists, Playlists).
     */
    suspend fun searchMusic(
        query: String,
        filter: InnerTuneSearchFilter = InnerTuneSearchFilter.ALL
    ): List<Song> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        try {
            val requestBodyJson = JSONObject().apply {
                put("context", createInnerTubeContext())
                put("query", query)
                filter.paramKey?.let { put("params", it) }
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
     * Resolves up-next radio suggestions for a playing track to build infinite queues (InnerTune Radio)
     */
    suspend fun getSuggestions(videoId: String): List<Song> = withContext(Dispatchers.IO) {
        try {
            val cleanId = videoId.removePrefix("piped_")
            val requestBodyJson = JSONObject().apply {
                put("context", createInnerTubeContext())
                put("videoId", cleanId)
            }

            val request = Request.Builder()
                .url("$BASE_URL/next?key=$API_KEY")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", "https://music.youtube.com/")
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

    /**
     * Resolves YouTube Music explore charts and trending tracks (InnerTune Explore/Charts)
     */
    suspend fun fetchTrending(): List<Song> = withContext(Dispatchers.IO) {
        try {
            val requestBodyJson = JSONObject().apply {
                put("context", createInnerTubeContext())
                put("browseId", "FEmusic_charts")
            }

            val request = Request.Builder()
                .url("$BASE_URL/browse?key=$API_KEY")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", "https://music.youtube.com/")
                .post(requestBodyJson.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val bodyString = response.body?.string() ?: return@withContext emptyList()
                return@withContext parseInnerTubeSearchResponse(bodyString)
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed fetching charts, falling back to search: ${e.message}")
            return@withContext emptyList()
        }
    }

    /**
     * Resolves InnerTube synced or plain lyrics by inspecting next/browse metadata
     */
    suspend fun fetchInnerTubeLyrics(videoId: String): String? = withContext(Dispatchers.IO) {
        try {
            val cleanId = videoId.removePrefix("piped_")
            val nextJson = JSONObject().apply {
                put("context", createInnerTubeContext())
                put("videoId", cleanId)
            }

            val nextRequest = Request.Builder()
                .url("$BASE_URL/next?key=$API_KEY")
                .post(nextJson.toString().toRequestBody(jsonMediaType))
                .build()

            var lyricsBrowseId: String? = null
            client.newCall(nextRequest).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val root = JSONObject(body)
                lyricsBrowseId = findLyricsBrowseId(root)
            }

            val bId = lyricsBrowseId ?: return@withContext null

            val browseJson = JSONObject().apply {
                put("context", createInnerTubeContext())
                put("browseId", bId)
            }
            val browseReq = Request.Builder()
                .url("$BASE_URL/browse?key=$API_KEY")
                .post(browseJson.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(browseReq).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                return@withContext extractLyricsDescription(JSONObject(body))
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "InnerTube lyrics resolution exception: ${e.message}")
            return@withContext null
        }
    }

    private fun findLyricsBrowseId(json: Any): String? {
        when (json) {
            is JSONObject -> {
                if (json.has("browseEndpoint")) {
                    val endpoint = json.optJSONObject("browseEndpoint")
                    val bId = endpoint?.optString("browseId", "")
                    if (!bId.isNullOrBlank() && bId.startsWith("MPLYt")) {
                        return bId
                    }
                }
                val keys = json.keys()
                while (keys.hasNext()) {
                    val res = findLyricsBrowseId(json.get(keys.next()))
                    if (res != null) return res
                }
            }
            is JSONArray -> {
                for (i in 0 until json.length()) {
                    val res = findLyricsBrowseId(json.get(i))
                    if (res != null) return res
                }
            }
        }
        return null
    }

    private fun extractLyricsDescription(json: JSONObject): String? {
        val runs = json.optJSONObject("contents")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents")
            ?.optJSONObject(0)
            ?.optJSONObject("musicDescriptionShelfRenderer")
            ?.optJSONObject("description")
            ?.optJSONArray("runs")

        if (runs != null && runs.length() > 0) {
            val sb = java.lang.StringBuilder()
            for (i in 0 until runs.length()) {
                val t = runs.optJSONObject(i)?.optString("text", "")
                if (!t.isNullOrBlank()) sb.append(t)
            }
            return sb.toString().trim()
        }
        return null
    }

    private fun createInnerTubeContext(): JSONObject {
        return JSONObject().apply {
            put("client", JSONObject().apply {
                put("clientName", "WEB_REMIX")
                put("clientVersion", "1.20240101.01.00")
                put("hl", "en")
                put("gl", "US")
            })
        }
    }

    private fun parseInnerTubeSearchResponse(jsonResponse: String): List<Song> {
        val songsList = mutableListOf<Song>()
        try {
            val root = JSONObject(jsonResponse)
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
                                album = "InnerTune Release"
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
