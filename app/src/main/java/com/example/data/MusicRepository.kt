package com.example.data

import androidx.compose.runtime.mutableStateListOf
import com.example.database.LocalSongEntity
import com.example.database.RecentSongEntity
import com.example.model.Playlist
import com.example.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MusicRepository {

    private val scope = CoroutineScope(Dispatchers.IO)

    fun getLikedSongsFlow(): Flow<List<Song>> {
        val db = AppDatabaseHelper.database ?: return flowOf(emptyList())
        return db.localSongDao().getLikedSongs().map { list ->
            list.map { entity ->
                Song(
                    id = entity.id,
                    title = entity.title,
                    artist = entity.artist,
                    albumArtUrl = entity.albumArtUrl,
                    streamUrl = entity.streamUrl,
                    durationMs = entity.durationMs,
                    lyrics = entity.lyrics ?: "",
                    isDownloaded = entity.isDownloaded,
                    localFilePath = entity.localFilePath,
                    isLiked = true,
                    genre = "Liked",
                    album = "Favorites"
                )
            }
        }
    }

    companion object {
        val likedSongs = mutableStateListOf<Song>()
        val customPlaylists = mutableStateListOf<Playlist>()

        fun getRecentlyPlayedSongs(): Flow<List<RecentSongEntity>> {
            val db = AppDatabaseHelper.database
            return db?.recentSongDao()?.getRecentSongsFlow() ?: flowOf(emptyList())
        }
    }

    fun getSampleSongs(): List<Song> {
        return listOf(
            Song(
                id = "piped_SoundHelix_1",
                title = "Despacito",
                artist = "Luis Fonsi ft. Daddy Yankee",
                albumArtUrl = "https://img.youtube.com/vi/kJQP7kiw5Fk/0.jpg",
                streamUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3",
                durationMs = 230000L,
                genre = "Latin Pop",
                album = "YouTube Hits"
            ),
            Song(
                id = "piped_SoundHelix_2",
                title = "Bohemian Rhapsody",
                artist = "Queen",
                albumArtUrl = "https://img.youtube.com/vi/fJ9rUzIMcZQ/0.jpg",
                streamUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3",
                durationMs = 359000L,
                genre = "Classic Rock",
                album = "A Night at the Opera"
            ),
            Song(
                id = "piped_SoundHelix_3",
                title = "Shape of You",
                artist = "Ed Sheeran",
                albumArtUrl = "https://img.youtube.com/vi/JGwWNGJdvx8/0.jpg",
                streamUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-3.mp3",
                durationMs = 233000L,
                genre = "Pop",
                album = "Divide"
            ),
            Song(
                id = "piped_SoundHelix_4",
                title = "Gangnam Style",
                artist = "PSY",
                albumArtUrl = "https://img.youtube.com/vi/9bZkp7q19f0/0.jpg",
                streamUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-4.mp3",
                durationMs = 252000L,
                genre = "K-Pop",
                album = "Psy 6 (Six Rules)"
            ),
            Song(
                id = "piped_SoundHelix_5",
                title = "Roar",
                artist = "Katy Perry",
                albumArtUrl = "https://img.youtube.com/vi/CevxZvSJLk8/0.jpg",
                streamUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-5.mp3",
                durationMs = 223000L,
                genre = "Pop",
                album = "Prism"
            ),
            Song(
                id = "piped_SoundHelix_6",
                title = "Sugar",
                artist = "Maroon 5",
                albumArtUrl = "https://img.youtube.com/vi/09R8_2nJtjg/0.jpg",
                streamUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-6.mp3",
                durationMs = 235000L,
                genre = "Pop",
                album = "V"
            ),
            Song(
                id = "piped_SoundHelix_7",
                title = "Hello",
                artist = "Adele",
                albumArtUrl = "https://img.youtube.com/vi/YQHsXMglC9A/0.jpg",
                streamUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-7.mp3",
                durationMs = 295000L,
                genre = "Soul / Pop",
                album = "25"
            ),
            Song(
                id = "piped_SoundHelix_8",
                title = "Uptown Funk",
                artist = "Mark Ronson ft. Bruno Mars",
                albumArtUrl = "https://img.youtube.com/vi/OPf0YbXqDm0/0.jpg",
                streamUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-8.mp3",
                durationMs = 270000L,
                genre = "Funk Pop",
                album = "Uptown Special"
            )
        )
    }

    fun getRecentSongs(): List<Song> = getSampleSongs().take(5)

    fun getFeaturedPlaylists(): List<Playlist> {
        val samples = getSampleSongs()
        return listOf(
            Playlist("p1", "Chill Vibes", "Relax, study and unwind with smooth tunes", "https://picsum.photos/seed/p1/400/400", listOf(samples[1], samples[3], samples[5])),
            Playlist("p2", "Cybernetic Drive", "High-energy synthwave and cyberpunk beats", "https://picsum.photos/seed/p2/400/400", listOf(samples[0], samples[2], samples[4], samples[6])),
            Playlist("p3", "Deep Focus", "Ambient sounds and soothing jazz for productivity", "https://picsum.photos/seed/p3/400/400", listOf(samples[5], samples[7], samples[3]))
        )
    }

    fun toggleLikeSong(song: Song) {
        val existing = likedSongs.find { it.id == song.id }
        if (existing != null) {
            likedSongs.remove(existing)
        } else {
            likedSongs.add(song.copy(isLiked = true))
        }

        scope.launch {
            try {
                val db = AppDatabaseHelper.database ?: return@launch
                val existingEntity = db.localSongDao().getSongById(song.id)
                if (existingEntity == null) {
                    db.localSongDao().insertSong(
                        LocalSongEntity(
                            id = song.id,
                            title = song.title,
                            artist = song.artist,
                            albumArtUrl = song.albumArtUrl,
                            streamUrl = song.streamUrl,
                            durationMs = song.durationMs,
                            lyrics = song.lyrics,
                            isLiked = (existing == null),
                            isDownloaded = false,
                            localFilePath = null,
                            downloadProgress = 0,
                            lastPlaybackPositionMs = 0L,
                            lastPlayedAt = System.currentTimeMillis()
                        )
                    )
                } else {
                    db.localSongDao().updateLikedStatus(song.id, existing == null)
                }
            } catch (e: Throwable) {
                com.example.util.AppLogger.w("MusicRepository", "Failed toggling like in database: ${e.message}")
            }
        }
    }

    fun addSongToPlaylist(playlistId: String, song: Song) {
        val index = customPlaylists.indexOfFirst { it.id == playlistId }
        if (index != -1) {
            val pl = customPlaylists[index]
            if (pl.songs.none { it.id == song.id }) {
                customPlaylists[index] = pl.copy(songs = pl.songs + song)
            }
        }
    }

    fun createPlaylist(name: String, description: String = "") {
        val newPl = Playlist(
            id = "custom_${System.currentTimeMillis()}",
            name = name,
            description = description,
            coverUrl = "https://picsum.photos/seed/$name/400/400",
            songs = emptyList()
        )
        customPlaylists.add(newPl)
    }
}
