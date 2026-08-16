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
import com.example.ui.viewmodel.*
import com.google.accompanist.permissions.*
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable

class MainActivity : ComponentActivity() {
    private lateinit var mainViewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Capture crashes in background/main thread and save to preferences
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val prefs = getSharedPreferences("crash_prefs", MODE_PRIVATE)
                val stackTrace = android.util.Log.getStackTraceString(throwable)
                prefs.edit().putString("last_crash", stackTrace).commit()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }

        var initError: Throwable? = null
        try {
            mainViewModel = androidx.lifecycle.ViewModelProvider(this)[MainViewModel::class.java]
        } catch (t: Throwable) {
            initError = t
        }

        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val crashPrefs = remember { context.getSharedPreferences("crash_prefs", MODE_PRIVATE) }
            var crashLog by remember { mutableStateOf(crashPrefs.getString("last_crash", null)) }

            if (crashLog != null) {
                MyApplicationTheme(themeMode = "Dark", useDynamicColor = false) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp)
                                .systemBarsPadding(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "Aero Player Crashed",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                            Text(
                                text = "The application encountered an unexpected error. Traceback:",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                                    androidx.compose.foundation.text.selection.SelectionContainer {
                                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                                            item {
                                                Text(
                                                    text = crashLog ?: "",
                                                    fontSize = 11.sp,
                                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                    color = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Button(
                                    onClick = {
                                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(crashLog ?: ""))
                                        android.widget.Toast.makeText(context, "Copied traceback to clipboard!", android.widget.Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Text("Copy Full Traceback")
                                }
                                Button(
                                    onClick = {
                                        crashPrefs.edit().remove("last_crash").commit()
                                        crashLog = null
                                        finish()
                                        startActivity(intent)
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text("Clear & Restart")
                                }
                            }
                        }
                    }
                }
            } else {
                var composeError by remember { mutableStateOf<Throwable?>(initError) }
                
                if (composeError != null) {
                    MyApplicationTheme(themeMode = "Dark", useDynamicColor = false) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.background
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(24.dp)
                                    .systemBarsPadding(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "UI Render Error",
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(bottom = 16.dp)
                                )
                                Card(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                                        androidx.compose.foundation.text.selection.SelectionContainer {
                                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                                item {
                                                    Text(
                                                        text = android.util.Log.getStackTraceString(composeError),
                                                        fontSize = 11.sp,
                                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                        color = MaterialTheme.colorScheme.error
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                                val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(android.util.Log.getStackTraceString(composeError)))
                                            android.widget.Toast.makeText(context, "Copied traceback to clipboard!", android.widget.Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                    ) {
                                        Text("Copy Full Traceback")
                                    }
                                    Button(
                                        onClick = {
                                            composeError = null
                                            finish()
                                            startActivity(intent)
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Restart App")
                                    }
                                }
                            }
                        }
                    }
                } else {
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
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (::mainViewModel.isInitialized) {
            mainViewModel.isInPipMode.value = isInPictureInPictureMode
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (::mainViewModel.isInitialized) {
            val prefs = mainViewModel.preferencesState.value
            val currentItem = mainViewModel.currentPlayingItem.value
            val isPlaying = mainViewModel.isPlaying.value
            if (currentItem != null && currentItem.isVideo && isPlaying && prefs.backgroundMode == "LAUNCH_PIP_MODE") {
                enterPictureInPictureModeCompat()
            }
        }
    }

    private fun enterPictureInPictureModeCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val builder = PictureInPictureParams.Builder()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setAutoEnterEnabled(true)
            }
            try {
                enterPictureInPictureMode(builder.build())
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to enter Picture-in-Picture: ${e.message}")
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            @Suppress("DEPRECATION")
            try {
                enterPictureInPictureMode()
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to enter Picture-in-Picture: ${e.message}")
            }
        }
    }
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_HEADSETHOOK -> {
                    PlayerControlBridge.onHeadsetHookClick()
                    return true
                }
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    PlayerControlBridge.playPause()
                    return true
                }
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    PlayerControlBridge.play()
                    return true
                }
                android.view.KeyEvent.KEYCODE_MEDIA_PAUSE,
                android.view.KeyEvent.KEYCODE_MEDIA_STOP -> {
                    PlayerControlBridge.pause()
                    return true
                }
                android.view.KeyEvent.KEYCODE_MEDIA_NEXT,
                android.view.KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> {
                    PlayerControlBridge.next()
                    return true
                }
                android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                android.view.KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> {
                    PlayerControlBridge.prev()
                    return true
                }
                android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    PlayerControlBridge.seekBy(10000L)
                    return true
                }
                android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    PlayerControlBridge.seekBy(-10000L)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (::mainViewModel.isInitialized) {
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_VOLUME_UP,
                android.view.KeyEvent.KEYCODE_VOLUME_DOWN,
                android.view.KeyEvent.KEYCODE_VOLUME_MUTE -> {
                    if (mainViewModel.onVolumeKeyPressed(keyCode)) {
                        return true
                    }
                }
                android.view.KeyEvent.KEYCODE_HEADSETHOOK -> {
                    PlayerControlBridge.onHeadsetHookClick()
                    return true
                }
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    PlayerControlBridge.playPause()
                    return true
                }
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    PlayerControlBridge.play()
                    return true
                }
                android.view.KeyEvent.KEYCODE_MEDIA_PAUSE,
                android.view.KeyEvent.KEYCODE_MEDIA_STOP -> {
                    PlayerControlBridge.pause()
                    return true
                }
                android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    PlayerControlBridge.next()
                    return true
                }
                android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    PlayerControlBridge.prev()
                    return true
                }
                android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    PlayerControlBridge.seekBy(10000L)
                    return true
                }
                android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    PlayerControlBridge.seekBy(-10000L)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
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
        listOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }

    val permissionState = rememberMultiplePermissionsState(permissions)

    // Observe pending delete intent from ViewModel to request storage deletion permission (Android 10+)
    val pendingDeleteIntent by viewModel.pendingDeleteIntent.collectAsState()
    val deleteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            android.widget.Toast.makeText(context, "Storage file physically deleted", android.widget.Toast.LENGTH_SHORT).show()
            viewModel.scanLocalMedia()
        } else {
            android.widget.Toast.makeText(context, "File deletion permission denied", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(pendingDeleteIntent) {
        pendingDeleteIntent?.let { intentSender ->
            try {
                val intentSenderRequest = IntentSenderRequest.Builder(intentSender).build()
                deleteLauncher.launch(intentSenderRequest)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to launch delete intent sender", e)
            } finally {
                viewModel.clearPendingDeleteIntent()
            }
        }
    }
    val prefs by viewModel.preferencesState.collectAsState()
    val appSp = remember { context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE) }
    var isOnboardingCompleted by remember {
        mutableStateOf(appSp.getBoolean("onboarding_completed", false) || prefs.onboardingCompleted)
    }

    LaunchedEffect(prefs.onboardingCompleted) {
        if (prefs.onboardingCompleted) {
            isOnboardingCompleted = true
            if (!appSp.getBoolean("onboarding_completed", false)) {
                appSp.edit().putBoolean("onboarding_completed", true).apply()
            }
        }
    }

    var showAllFilesAccessDialog by remember { mutableStateOf(false) }

    LaunchedEffect(isOnboardingCompleted) {
        if (isOnboardingCompleted) {
            try {
                kotlinx.coroutines.delay(500)
                permissionState.launchMultiplePermissionRequest()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !android.os.Environment.isExternalStorageManager()) {
                    showAllFilesAccessDialog = true
                }
            } catch (e: Throwable) {
                android.util.Log.e("MainActivity", "Permission request error: ${e.message}")
            }
        }
    }

    if (showAllFilesAccessDialog && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !android.os.Environment.isExternalStorageManager()) {
        AlertDialog(
            onDismissRequest = { showAllFilesAccessDialog = false },
            title = { Text("All Files Access Required", fontWeight = FontWeight.Bold) },
            text = { Text("Aero Player requires All Files Access permission to browse, play, rename, and delete media files seamlessly across your storage.") },
            confirmButton = {
                Button(
                    onClick = {
                        showAllFilesAccessDialog = false
                        try {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = android.net.Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            context.startActivity(intent)
                        }
                    }
                ) {
                    Text("Grant Permission")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAllFilesAccessDialog = false }) {
                    Text("Later")
                }
            }
        )
    }

    // Automatically trigger scan once permissions are granted and onboarding is completed
    LaunchedEffect(permissionState.allPermissionsGranted, isOnboardingCompleted) {
        if (permissionState.allPermissionsGranted && isOnboardingCompleted) {
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
            "Landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
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

        if (!isOnboardingCompleted) {
            OnboardingScreen(
                onFinished = {
                    viewModel.completeOnboarding()
                    appSp.edit().putBoolean("onboarding_completed", true).apply()
                    isOnboardingCompleted = true
                }
            )
        }
    }
}
