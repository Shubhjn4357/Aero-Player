package com.example

import android.Manifest
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.database.MediaEntity
import com.example.ui.screens.MainScreen
import com.example.ui.screens.PlayerScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.OnboardingScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.MainViewModel
import com.google.accompanist.permissions.*
import androidx.compose.animation.*
import androidx.compose.animation.core.tween

class MainActivity : ComponentActivity() {
    private lateinit var mainViewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            mainViewModel = viewModel()
            val prefs by mainViewModel.preferencesState.collectAsState()

            MyApplicationTheme(
                themeMode = prefs.themeMode,
                useDynamicColor = prefs.useDynamicColor
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PermissionAndNavigationContainer(viewModel = mainViewModel)
                }
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (::mainViewModel.isInitialized) {
            mainViewModel.isInPipMode.value = isInPictureInPictureMode
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionAndNavigationContainer(viewModel: MainViewModel) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity

    // Determine permissions required by Android version
    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS
        )
    } else {
        listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    val permissionState = rememberMultiplePermissionsState(permissions)
    val prefs by viewModel.preferencesState.collectAsState()

    // Trigger permission request in a safe, non-blocking way once onboarding is completed
    LaunchedEffect(prefs.onboardingCompleted) {
        if (prefs.onboardingCompleted) {
            try {
                // Safe delay to ensure Activity/Window is fully ready
                kotlinx.coroutines.delay(500)
                permissionState.launchMultiplePermissionRequest()
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Permission request error: ${e.message}")
            }
        }
    }

    // Automatically trigger scan once permissions are granted and onboarding is completed
    LaunchedEffect(permissionState.allPermissionsGranted, prefs.onboardingCompleted) {
        if (permissionState.allPermissionsGranted && prefs.onboardingCompleted) {
            viewModel.scanLocalMedia()
        }
    }

    // App Navigation Flow
    var currentScreen by remember { mutableStateOf("Main") } // "Main", "Player", "Settings"
    val currentPlayingItem by viewModel.currentPlayingItem.collectAsState()

    val defaultOrientation = prefs.defaultOrientation

    val getOrientationFromPreference = { pref: String ->
        when (pref) {
            "Portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "Landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            "Reverse Portrait" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            "Reverse Landscape" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // App Navigation Content - Main screen is index screen, onboarding is a secondary overlay
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = currentScreen,
            transitionSpec = {
                if (targetState == "Player" || (initialState == "Main" && targetState == "Settings")) {
                    (slideInHorizontally { width -> width } + fadeIn(animationSpec = tween(300)))
                        .togetherWith(slideOutHorizontally { width -> -width } + fadeOut(animationSpec = tween(300)))
                } else {
                    (slideInHorizontally { width -> -width } + fadeIn(animationSpec = tween(300)))
                        .togetherWith(slideOutHorizontally { width -> width } + fadeOut(animationSpec = tween(300)))
                }
            },
            label = "screen_transition",
            modifier = Modifier.fillMaxSize()
        ) { targetScreen ->
            when (targetScreen) {
                "Main" -> {
                    LaunchedEffect(Unit) {
                        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    }

                    MainScreen(
                        viewModel = viewModel,
                        onPlayItem = { item ->
                            viewModel.setPlayingItem(item)
                            currentScreen = "Player"
                        },
                        onNavigateToSettings = {
                            currentScreen = "Settings"
                        },
                        onOpenPlayer = {
                            currentScreen = "Player"
                        }
                    )
                }
                "Player" -> {
                    LaunchedEffect(prefs.rotationLock, defaultOrientation) {
                        if (prefs.rotationLock) {
                            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
                        } else {
                            activity?.requestedOrientation = getOrientationFromPreference(defaultOrientation)
                        }
                    }

                    currentPlayingItem?.let { media ->
                        PlayerScreen(
                            mediaItem = media,
                            viewModel = viewModel,
                            onBack = {
                                currentScreen = "Main"
                            }
                        )
                    } ?: run {
                        LaunchedEffect(Unit) {
                            currentScreen = "Main"
                        }
                    }
                }
                "Settings" -> {
                    LaunchedEffect(Unit) {
                        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    }

                    SettingsScreen(
                        viewModel = viewModel,
                        onBack = {
                            currentScreen = "Main"
                        }
                    )
                }
            }
        }

        if (!prefs.onboardingCompleted) {
            OnboardingScreen(
                onFinished = {
                    viewModel.completeOnboarding()
                }
            )
        }
    }
}
