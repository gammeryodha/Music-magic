package com.example.data

import android.content.Context
import com.example.model.Playlist
import com.example.model.Song
import com.example.model.User
import com.example.util.AppLogger

object FirestoreManager {
    private const val TAG = "FirestoreManager"

    fun init(context: Context) {
        AppLogger.i(TAG, "Local Storage persistence fully initialized.")
    }

    fun saveUserProfile(user: User) {
        // Safe local storage mock - user profile is persisted locally in AuthManager's SharedPreferences
        AppLogger.i(TAG, "User profile saved locally for ${user.email}")
    }

    fun syncLikedSong(userId: String, song: Song, isLiked: Boolean) {
        // Safe local storage mock - liked songs are persisted locally in Room database via MusicRepository
    }

    fun syncPlaylist(userId: String, playlist: Playlist) {
        // Safe local storage mock - custom playlists are persisted locally in MusicRepository
    }

    fun recordRecentlyPlayed(userId: String, song: Song) {
        // Safe local storage mock - recently played items are recorded locally in Room database
    }
}
