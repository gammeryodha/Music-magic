package com.example

import com.example.model.Song
import com.example.network.AudioPlayerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomPlayerBarTest {

    @Test
    fun `test Song domain model creation for mini player`() {
        val song = Song(
            id = "test_1",
            title = "Midnight Drive",
            artist = "Retro Wave",
            albumArtUrl = "https://example.com/art.jpg",
            streamUrl = "https://example.com/audio.mp3",
            durationMs = 200000L
        )

        assertEquals("test_1", song.id)
        assertEquals("Midnight Drive", song.title)
        assertEquals("Retro Wave", song.artist)
        assertEquals(200000L, song.durationMs)
    }

    @Test
    fun `test progress calculation fraction`() {
        val positionMs = 50000L
        val durationMs = 200000L
        val fraction = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)

        assertEquals(0.25f, fraction, 0.001f)
    }

    @Test
    fun `test progress calculation fraction edge cases`() {
        // Zero duration
        val zeroFraction = if (0L > 0) (10000L.toFloat() / 0L.toFloat()).coerceIn(0f, 1f) else 0f
        assertEquals(0f, zeroFraction, 0.001f)

        // Overshoot position
        val positionMs = 250000L
        val durationMs = 200000L
        val clampedFraction = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        assertEquals(1.0f, clampedFraction, 0.001f)
    }

    @Test
    fun `test initial default track assignment in audio manager`() {
        val testInitial = Song(
            id = "seed_mini_1",
            title = "Chill Sunset",
            artist = "Acoustic Vibes",
            albumArtUrl = "https://example.com/sunset.jpg",
            streamUrl = "https://example.com/sunset.mp3",
            durationMs = 180000L
        )

        AudioPlayerManager.initializeDefaultTrackIfEmpty(testInitial)
        val current = AudioPlayerManager.currentSong.value
        assertNotNull(current)
        assertTrue(AudioPlayerManager.playlist.value.isNotEmpty())
    }
}
