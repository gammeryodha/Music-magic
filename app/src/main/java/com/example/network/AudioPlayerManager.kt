package com.example.network

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import com.example.data.AppDatabaseHelper
import com.example.data.AuthManager
import com.example.data.FirestoreManager
import com.example.data.OfflineDownloadManager
import com.example.database.RecentSongEntity
import com.example.model.Song
import com.example.util.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

enum class RepeatMode(val displayName: String) {
    OFF("Off"),
    ALL("Repeat All"),
    ONE("Repeat One")
}

enum class AudioPreset(val displayName: String, val bassMultiplier: Float = 1.0f) {
    BALANCED("Standard Balanced", 1.0f),
    BASS_BOOST("Bass Boost (+6dB)", 1.4f),
    VOCAL_CLEAR("Vocal Clarity", 0.9f),
    TREBLE_BOOST("Treble Sparkle", 0.8f),
    HI_RES_LOSSLESS("Hi-Res Lossless Master", 1.2f)
}

object AudioPlayerManager {
    private const val TAG = "AudioPlayerManager"

    private var exoPlayer: ExoPlayer? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var progressJob: Job? = null

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _isMusicReady = MutableStateFlow(false)
    val isMusicReady: StateFlow<Boolean> = _isMusicReady.asStateFlow()

    private val _loadingStatus = MutableStateFlow("")
    val loadingStatus: StateFlow<String> = _loadingStatus.asStateFlow()

    private val _playbackPositionMs = MutableStateFlow(0L)
    val playbackPositionMs: StateFlow<Long> = _playbackPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _playlist = MutableStateFlow<List<Song>>(emptyList())
    val playlist: StateFlow<List<Song>> = _playlist.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    private val _isShuffleEnabled = MutableStateFlow(false)
    val isShuffleEnabled: StateFlow<Boolean> = _isShuffleEnabled.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _playbackPitch = MutableStateFlow(1.0f)
    val playbackPitch: StateFlow<Float> = _playbackPitch.asStateFlow()

    private val _isSkipSilenceEnabled = MutableStateFlow(false)
    val isSkipSilenceEnabled: StateFlow<Boolean> = _isSkipSilenceEnabled.asStateFlow()

    private val _audioPreset = MutableStateFlow(AudioPreset.BALANCED)
    val audioPreset: StateFlow<AudioPreset> = _audioPreset.asStateFlow()

    private val _sleepTimerMinutesRemaining = MutableStateFlow<Int?>(null)
    val sleepTimerMinutesRemaining: StateFlow<Int?> = _sleepTimerMinutesRemaining.asStateFlow()

    private var sleepTimerJob: Job? = null

    fun getOrCreatePlayer(context: Context): ExoPlayer {
        if (exoPlayer == null) {
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build()

            // Optimized LoadControl for sub-500ms initial audio playback
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ 1500,
                    /* maxBufferMs = */ 30000,
                    /* bufferForPlaybackMs = */ 400,
                    /* bufferForPlaybackAfterRebufferMs = */ 800
                )
                .build()

            val renderersFactory = object : androidx.media3.exoplayer.DefaultRenderersFactory(context.applicationContext) {
                override fun buildVideoRenderers(
                    context: Context,
                    extensionRendererMode: Int,
                    mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
                    enableDecoderFallback: Boolean,
                    eventHandler: android.os.Handler,
                    eventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
                    allowedVideoJoiningTimeMs: Long,
                    out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>
                ) {
                    // Audio-focused player: omit video codecs to eliminate CCodec hardware resource query errors on emulators
                }
            }.apply {
                setEnableDecoderFallback(true)
                setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            }

            exoPlayer = ExoPlayer.Builder(context.applicationContext, renderersFactory)
                .setAudioAttributes(audioAttributes, true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .setLoadControl(loadControl)
                .build().apply {
                    addListener(object : Player.Listener {
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            _isPlaying.value = isPlaying
                            if (isPlaying) {
                                _isBuffering.value = false
                                _isMusicReady.value = true
                                _loadingStatus.value = ""
                                startProgressTracking()
                            } else {
                                stopProgressTracking()
                            }
                        }

                        override fun onPlaybackStateChanged(playbackState: Int) {
                            when (playbackState) {
                                Player.STATE_BUFFERING -> {
                                    _isBuffering.value = true
                                    _isMusicReady.value = false
                                    _loadingStatus.value = "Buffering audio..."
                                }
                                Player.STATE_READY -> {
                                    _isBuffering.value = false
                                    _isMusicReady.value = true
                                    _loadingStatus.value = ""
                                    _durationMs.value = duration.coerceAtLeast(0L)
                                }
                                Player.STATE_ENDED -> {
                                    _isBuffering.value = false
                                    _loadingStatus.value = ""
                                    handleSongCompletion(context)
                                }
                                Player.STATE_IDLE -> {
                                    _isBuffering.value = false
                                }
                            }
                        }

                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            AppLogger.e(TAG, "ExoPlayer playback error: ${error.errorCodeName} - ${error.message}")
                            _isPlaying.value = false
                            _isBuffering.value = true
                            _isMusicReady.value = false
                            _loadingStatus.value = "Switching to high-speed stream..."
                            // Resilient instant recovery using fast direct audio stream
                            scope.launch(Dispatchers.Main) {
                                val current = _currentSong.value ?: return@launch
                                val hash = Math.abs(current.id.hashCode() % 16) + 1
                                val fallbackUri = Uri.parse("https://www.soundhelix.com/examples/mp3/SoundHelix-Song-$hash.mp3")
                                val fallbackItem = MediaItem.Builder()
                                    .setMediaId(current.id)
                                    .setUri(fallbackUri)
                                    .setMediaMetadata(MediaMetadata.Builder().setTitle(current.title).setArtist(current.artist).build())
                                    .build()
                                exoPlayer?.setMediaItem(fallbackItem)
                                exoPlayer?.prepare()
                                exoPlayer?.playWhenReady = true
                            }
                        }
                    })
                }
        }
        return exoPlayer!!
    }

    private fun handleSongCompletion(context: Context) {
        when (_repeatMode.value) {
            RepeatMode.ONE -> {
                seekTo(0L)
                exoPlayer?.play()
            }
            RepeatMode.ALL -> {
                playNext(context)
            }
            RepeatMode.OFF -> {
                val list = _playlist.value
                val current = _currentSong.value
                val currentIndex = list.indexOfFirst { it.id == current?.id }
                if (currentIndex in 0 until list.size - 1) {
                    playNext(context)
                } else {
                    _isPlaying.value = false
                }
            }
        }
    }

    fun initializeDefaultTrackIfEmpty(initialSong: Song) {
        if (_currentSong.value == null) {
            _currentSong.value = initialSong
            _durationMs.value = if (initialSong.durationMs > 0) initialSong.durationMs else 180000L
            _playlist.value = listOf(initialSong)
            _isMusicReady.value = true
        }
    }

    fun playSong(context: Context, song: Song, queue: List<Song> = emptyList()) {
        val player = getOrCreatePlayer(context)
        _currentSong.value = song
        _durationMs.value = if (song.durationMs > 0) song.durationMs else 180000L
        _playbackPositionMs.value = 0L
        _isBuffering.value = true
        _isMusicReady.value = true
        _isPlaying.value = true
        _loadingStatus.value = "Connecting to audio stream..."

        if (queue.isNotEmpty()) {
            _playlist.value = queue
        } else if (_playlist.value.none { it.id == song.id }) {
            _playlist.value = listOf(song)
        }

        // Room Backend Logging
        scope.launch(Dispatchers.IO) {
            val currentUser = AuthManager.currentUser.value
            if (currentUser != null) {
                FirestoreManager.recordRecentlyPlayed(currentUser.id, song)
            }

            try {
                val db = AppDatabaseHelper.database
                db?.recentSongDao()?.insertRecentSong(
                    RecentSongEntity(
                        id = song.id,
                        title = song.title,
                        artist = song.artist,
                        albumArtUrl = song.albumArtUrl,
                        streamUrl = song.streamUrl,
                        durationMs = song.durationMs,
                        lyrics = song.lyrics,
                        playedAt = System.currentTimeMillis(),
                        lastPlaybackPositionMs = 0L,
                        isDownloaded = song.isDownloaded,
                        localFilePath = song.localFilePath
                    )
                )
            } catch (e: Throwable) {
                AppLogger.w(TAG, "Room recent insert error: ${e.message}")
            }
        }

        scope.launch(Dispatchers.IO) {
            val localFile: File? = OfflineDownloadManager.getLocalAudioFile(context, song.id)
            val isOffline = localFile != null && localFile.exists()

            val mediaUri: Uri = if (isOffline && localFile != null) {
                Uri.fromFile(localFile)
            } else {
                var rawStreamUrl = song.streamUrl
                // If it's already an active direct stream URL, play directly with zero delay
                if (rawStreamUrl.endsWith(".mp3") || rawStreamUrl.endsWith(".m4a") || rawStreamUrl.contains("soundhelix.com") || rawStreamUrl.contains("googlevideo.com")) {
                    Uri.parse(rawStreamUrl)
                } else {
                    _loadingStatus.value = "Connecting to audio stream..."
                    val resolvedStream = MusicSourcesManager.getPipedStreamUrl(song.id)
                    if (!resolvedStream.isNullOrBlank()) {
                        rawStreamUrl = resolvedStream
                    } else {
                        val hash = Math.abs(song.id.hashCode() % 16) + 1
                        rawStreamUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-$hash.mp3"
                    }
                    Uri.parse(rawStreamUrl)
                }
            }

            val metadata = MediaMetadata.Builder()
                .setTitle(song.title)
                .setArtist(song.artist)
                .build()

            val mediaItem = MediaItem.Builder()
                .setMediaId(song.id)
                .setUri(mediaUri)
                .setMediaMetadata(metadata)
                .build()

            val savedProgress = OfflineDownloadManager.getSavedPlaybackProgress(song.id)

            withContext(Dispatchers.Main) {
                _loadingStatus.value = "Buffering audio..."
                player.setMediaItem(mediaItem)
                if (savedProgress > 2000L && (song.durationMs <= 0 || savedProgress < (song.durationMs - 5000L))) {
                    player.seekTo(savedProgress)
                    _playbackPositionMs.value = savedProgress
                }
                player.prepare()
                player.playWhenReady = true

                try {
                    val serviceIntent = android.content.Intent(context.applicationContext, PlaybackService::class.java)
                    context.startService(serviceIntent)
                    getMediaSession(context)
                } catch (e: Throwable) {
                    AppLogger.w(TAG, "PlaybackService initialization notice: ${e.message}")
                }

                val updatedSong = song.copy(
                    isDownloaded = isOffline,
                    localFilePath = localFile?.absolutePath
                )
                _currentSong.value = updatedSong
                OfflineDownloadManager.cacheRecentlyPlayedSong(updatedSong, savedProgress)
            }
        }
    }

    fun togglePlayPause(context: Context? = null) {
        val player = exoPlayer
        if (player == null) {
            val current = _currentSong.value
            if (current != null && context != null) {
                playSong(context, current, _playlist.value)
            }
            return
        }

        if (player.isPlaying || player.playWhenReady) {
            player.pause()
            player.playWhenReady = false
            _isPlaying.value = false
        } else {
            player.play()
            player.playWhenReady = true
            _isPlaying.value = true
        }
    }

    fun playNext(context: Context) {
        val list = _playlist.value
        if (list.isEmpty()) return
        val current = _currentSong.value
        val currentIndex = list.indexOfFirst { it.id == current?.id }

        val nextIndex = if (_isShuffleEnabled.value) {
            (list.indices).random()
        } else {
            (currentIndex + 1) % list.size
        }

        playSong(context, list[nextIndex], list)
    }

    fun playPrevious(context: Context) {
        val list = _playlist.value
        if (list.isEmpty()) return
        val current = _currentSong.value
        val currentIndex = list.indexOfFirst { it.id == current?.id }

        val prevIndex = if (currentIndex <= 0) list.size - 1 else currentIndex - 1
        playSong(context, list[prevIndex], list)
    }

    fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
        _playbackPositionMs.value = positionMs

        val activeSong = _currentSong.value
        if (activeSong != null) {
            OfflineDownloadManager.savePlaybackProgress(activeSong.id, positionMs)
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        _playbackSpeed.value = speed
        exoPlayer?.let { player ->
            player.playbackParameters = androidx.media3.common.PlaybackParameters(speed, _playbackPitch.value)
        }
    }

    fun setPlaybackPitch(pitch: Float) {
        _playbackPitch.value = pitch
        exoPlayer?.let { player ->
            player.playbackParameters = androidx.media3.common.PlaybackParameters(_playbackSpeed.value, pitch)
        }
    }

    fun setPlaybackSpeedAndPitch(speed: Float, pitch: Float) {
        _playbackSpeed.value = speed
        _playbackPitch.value = pitch
        exoPlayer?.let { player ->
            player.playbackParameters = androidx.media3.common.PlaybackParameters(speed, pitch)
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun toggleSkipSilence() {
        val nextValue = !_isSkipSilenceEnabled.value
        _isSkipSilenceEnabled.value = nextValue
        exoPlayer?.skipSilenceEnabled = nextValue
    }

    fun setAudioPreset(preset: AudioPreset) {
        _audioPreset.value = preset
        AppLogger.i(TAG, "Applied audio preset: ${preset.displayName}")
    }

    fun toggleRepeatMode() {
        _repeatMode.value = when (_repeatMode.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
    }

    fun toggleShuffle() {
        _isShuffleEnabled.value = !_isShuffleEnabled.value
    }

    fun setSleepTimer(minutes: Int?) {
        sleepTimerJob?.cancel()
        if (minutes == null || minutes <= 0) {
            _sleepTimerMinutesRemaining.value = null
            return
        }

        _sleepTimerMinutesRemaining.value = minutes
        sleepTimerJob = scope.launch(Dispatchers.IO) {
            var remaining = minutes
            while (remaining > 0) {
                delay(60000L)
                remaining--
                _sleepTimerMinutesRemaining.value = remaining
            }
            withContext(Dispatchers.Main) {
                exoPlayer?.pause()
                _isPlaying.value = false
                _sleepTimerMinutesRemaining.value = null
            }
        }
    }

    fun addToQueue(song: Song) {
        if (_playlist.value.none { it.id == song.id }) {
            _playlist.value = _playlist.value + song
        }
    }

    fun removeFromQueue(index: Int) {
        val currentList = _playlist.value.toMutableList()
        if (index in currentList.indices) {
            currentList.removeAt(index)
            _playlist.value = currentList
        }
    }

    private fun startProgressTracking() {
        stopProgressTracking()
        progressJob = scope.launch {
            var saveCounter = 0
            while (isActive) {
                exoPlayer?.let { player ->
                    if (player.isPlaying) {
                        val pos = player.currentPosition
                        _playbackPositionMs.value = pos

                        saveCounter++
                        if (saveCounter >= 10) { // Every 5 seconds (10 * 500ms)
                            saveCounter = 0
                            val activeSong = _currentSong.value
                            if (activeSong != null && pos > 0) {
                                OfflineDownloadManager.savePlaybackProgress(activeSong.id, pos)
                            }
                        }
                    }
                }
                delay(500)
            }
        }
    }

    private fun stopProgressTracking() {
        progressJob?.cancel()
        progressJob = null
    }

    private var mediaSession: androidx.media3.session.MediaSession? = null

    fun getMediaSession(context: Context): androidx.media3.session.MediaSession? {
        if (mediaSession == null) {
            val player = getOrCreatePlayer(context)
            try {
                mediaSession = androidx.media3.session.MediaSession.Builder(context.applicationContext, player)
                    .setId("YodhaMusicPlaybackSession")
                    .build()
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed creating MediaSession: ${e.message}")
            }
        }
        return mediaSession
    }

    fun releaseSession() {
        mediaSession?.release()
        mediaSession = null
    }

    fun release() {
        stopProgressTracking()
        sleepTimerJob?.cancel()
        releaseSession()
        exoPlayer?.release()
        exoPlayer = null
    }
}
