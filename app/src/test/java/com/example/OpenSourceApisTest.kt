package com.example

import com.example.data.LyricsRepository
import com.example.model.Song
import com.example.network.InnerTubeClient
import com.example.network.InnerTuneSearchFilter
import com.example.network.MusicSourcesManager
import com.example.network.SearchSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSourceApisTest {

    @Test
    fun `test MusicSourcesManager search returns playable tracks for YouTube and Piped`() = runBlocking {
        val results = MusicSourcesManager.searchAllSources("Kesariya", SearchSource.ALL)
        assertTrue(results.isNotEmpty())
        val firstTrack = results.first()
        assertNotNull(firstTrack.title)
        assertNotNull(firstTrack.artist)
        assertTrue(firstTrack.streamUrl.startsWith("http"))
    }

    @Test
    fun `test MusicSourcesManager search with InnerTune category filter`() = runBlocking {
        val songsFilter = MusicSourcesManager.searchAllSources("Coldplay", SearchSource.ALL, InnerTuneSearchFilter.SONGS)
        assertTrue(songsFilter.isNotEmpty())

        val albumsFilter = MusicSourcesManager.searchAllSources("Coldplay", SearchSource.ALL, InnerTuneSearchFilter.ALBUMS)
        assertTrue(albumsFilter.isNotEmpty())
    }

    @Test
    fun `test LyricsRepository parses LRC synchronized text correctly`() {
        val repo = LyricsRepository()
        val lrcSample = """
            [00:12.34] Yeah, I feel it in my bones
            [00:18.50] Enough to make my systems grow
            [00:24.10] Welcome to the new age
        """.trimIndent()

        val lines = repo.parseLrcLyrics(lrcSample)
        assertEquals(3, lines.size)
        assertEquals(12340L, lines[0].timeMs)
        assertEquals("Yeah, I feel it in my bones", lines[0].text)
        assertEquals(18500L, lines[1].timeMs)
    }

    @Test
    fun `test LyricsRepository online fetch falls back smoothly when offline`() = runBlocking {
        val repo = LyricsRepository()
        val song = Song(
            id = "piped_kJQP7kiw5Fk",
            title = "Believer",
            artist = "Imagine Dragons",
            albumArtUrl = "https://img.youtube.com/vi/kJQP7kiw5Fk/0.jpg",
            streamUrl = "https://www.youtube.com/watch?v=kJQP7kiw5Fk"
        )

        val lyricsResult = repo.fetchLyricsOnline(song)
        assertNotNull(lyricsResult)
        assertTrue(lyricsResult.lines.isNotEmpty())
    }
}
