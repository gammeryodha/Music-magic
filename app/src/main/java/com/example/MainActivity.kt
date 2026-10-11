package com.example

import android.app.Activity
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.AppDatabaseHelper
import com.example.data.AuthManager
import com.example.data.FirestoreManager
import com.example.data.MusicRepository
import com.example.model.Song
import com.example.network.AudioPlayerManager
import com.example.ui.components.BottomPlayerBar
import com.example.ui.screens.*
import com.example.ui.theme.MusicAppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppDatabaseHelper.init(applicationContext)
        AuthManager.init(applicationContext)
        FirestoreManager.init(applicationContext)

        // Seed initial default track so persistent mini-player overlay is immediately ready
        try {
            val sampleSongs = MusicRepository().getSampleSongs()
            if (sampleSongs.isNotEmpty()) {
                AudioPlayerManager.initializeDefaultTrackIfEmpty(sampleSongs.first())
            }
        } catch (e: Throwable) {
            Log.w("MainActivity", "Initial track seed: ${e.message}")
        }

        setContent {
            MusicAppTheme {
                val currentUser by AuthManager.currentUser.collectAsState()
                if (currentUser == null) {
                    LoginScreen(
                        onSignInSuccess = {
                            // User authenticated successfully
                        }
                    )
                } else {
                    MainAppContent(
                        onLogout = {
                            AuthManager.signOut()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun MainAppContent(
    onLogout: () -> Unit = {}
) {
    val context = LocalContext.current
    val repository = remember { MusicRepository() }

    var selectedTab by remember { mutableIntStateOf(0) }
    var showFullPlayerScreen by remember { mutableStateOf(false) }

    val currentSong by AudioPlayerManager.currentSong.collectAsState()
    val isPlaying by AudioPlayerManager.isPlaying.collectAsState()
    val isBuffering by AudioPlayerManager.isBuffering.collectAsState()
    val isMusicReady by AudioPlayerManager.isMusicReady.collectAsState()
    val loadingStatus by AudioPlayerManager.loadingStatus.collectAsState()
    val playbackPositionMs by AudioPlayerManager.playbackPositionMs.collectAsState()
    val durationMs by AudioPlayerManager.durationMs.collectAsState()

    val likedSongs = MusicRepository.likedSongs
    val isCurrentSongLiked = remember(currentSong?.id, likedSongs.size) {
        val activeSong = currentSong
        if (activeSong != null) {
            likedSongs.any { liked -> liked.id == activeSong.id }
        } else false
    }

    BackHandler(enabled = showFullPlayerScreen) {
        showFullPlayerScreen = false
    }

    // Playback callback when user selects any song while browsing across all screens
    val onSongSelected: (Song, List<Song>) -> Unit = { song, queue ->
        AudioPlayerManager.playSong(context, song, queue)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Persistent Mini-Player Overlay docked directly above the navigation bar
                    BottomPlayerBar(
                        currentSong = currentSong,
                        isPlaying = isPlaying,
                        playbackPositionMs = playbackPositionMs,
                        durationMs = durationMs,
                        onPlayPauseToggle = {
                            AudioPlayerManager.togglePlayPause(context)
                        },
                        onSkipNext = {
                            AudioPlayerManager.playNext(context)
                        },
                        onSkipPrevious = {
                            AudioPlayerManager.playPrevious(context)
                        },
                        onOpenFullPlayer = {
                            showFullPlayerScreen = true
                        },
                        isLiked = isCurrentSongLiked,
                        onLikeToggle = {
                            currentSong?.let { repository.toggleLikeSong(it) }
                        },
                        isBuffering = isBuffering,
                        isMusicReady = isMusicReady,
                        loadingStatus = loadingStatus,
                        visible = !showFullPlayerScreen && currentSong != null
                    )

                    // Bottom Navigation Bar
                    NavigationBar(
                        modifier = Modifier.testTag("bottom_navigation_bar"),
                        tonalElevation = 8.dp
                    ) {
                        NavigationBarItem(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                            label = { Text("Home") },
                            modifier = Modifier.testTag("nav_home")
                        )
                        NavigationBarItem(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            icon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                            label = { Text("Search") },
                            modifier = Modifier.testTag("nav_search")
                        )
                        NavigationBarItem(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            icon = { Icon(Icons.Default.LibraryMusic, contentDescription = "Library") },
                            label = { Text("Library") },
                            modifier = Modifier.testTag("nav_library")
                        )
                        NavigationBarItem(
                            selected = selectedTab == 3,
                            onClick = { selectedTab = 3 },
                            icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                            label = { Text("Settings") },
                            modifier = Modifier.testTag("nav_settings")
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(if (!showFullPlayerScreen) innerPadding else PaddingValues(0.dp))
            ) {
                when (selectedTab) {
                    0 -> HomeScreen(
                        onSongSelected = onSongSelected
                    )
                    1 -> SearchScreen(
                        onSongSelected = onSongSelected
                    )
                    2 -> LibraryScreen(
                        onSongSelected = onSongSelected
                    )
                    3 -> SettingsScreen(
                        onLogout = onLogout
                    )
                }
            }
        }

        // Full Screen Player Overlay (Edge-to-edge covering the viewport with smooth transition)
        AnimatedVisibility(
            visible = showFullPlayerScreen && currentSong != null,
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(350)) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(300)) + fadeOut()
        ) {
            currentSong?.let { activeSong ->
                PlayerScreen(
                    song = activeSong,
                    isPlaying = isPlaying,
                    playbackPositionMs = playbackPositionMs,
                    onPlayPauseToggle = { AudioPlayerManager.togglePlayPause(context) },
                    onSkipNext = { AudioPlayerManager.playNext(context) },
                    onSkipPrevious = { AudioPlayerManager.playPrevious(context) },
                    onPositionChange = { newPosition -> AudioPlayerManager.seekTo(newPosition) },
                    onClose = { showFullPlayerScreen = false },
                    isLiked = isCurrentSongLiked,
                    onLikeToggle = { repository.toggleLikeSong(activeSong) },
                    isBuffering = isBuffering,
                    isMusicReady = isMusicReady,
                    loadingStatus = loadingStatus
                )
            }
        }
    }
}
