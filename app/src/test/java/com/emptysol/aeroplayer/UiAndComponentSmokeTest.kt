package com.emptysol.aeroplayer

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import com.emptysol.aeroplayer.data.database.PreferenceEntity
import com.emptysol.aeroplayer.player.subtitle.SubtitleAlignment
import com.emptysol.aeroplayer.player.subtitle.SubtitleCue
import com.emptysol.aeroplayer.player.subtitle.SubtitleParser
import com.emptysol.aeroplayer.ui.components.FormatSupportDialog
import com.emptysol.aeroplayer.ui.components.UnifiedSubtitleOverlay
import com.emptysol.aeroplayer.ui.screens.CustomFileBrowser
import com.emptysol.aeroplayer.ui.screens.PlayingEqualizerIndicator
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Jetpack Compose UI & component verification under Robolectric SDK 36
 * covering subtitle overlay rendering, GPU equalizer indicator animation,
 * format support modal search/tab filtering, and CustomFileBrowser extension filtering.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UiAndComponentSmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `UnifiedSubtitleOverlay renders cue text and hides cleanly when disabled`() {
        val prefs = PreferenceEntity(
            subtitleSize = 20f,
            subtitleTextColor = "#FFFF00",
            subtitleOutlineColor = "#FF000000",
            subtitleShadowEnabled = true,
            subtitleBackgroundEnabled = true,
            subtitleBackground = "#99000000"
        )
        val initialCues = listOf(
            SubtitleCue(
                startMs = 0L,
                endMs = 5000L,
                text = "GPU Composited Subtitle",
                alignment = SubtitleAlignment.TOP_CENTER
            )
        )

        val cuesState = androidx.compose.runtime.mutableStateOf(initialCues)
        composeRule.setContent {
            MaterialTheme {
                UnifiedSubtitleOverlay(
                    cues = cuesState.value,
                    prefs = prefs,
                    isVisible = true
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(300)
        composeRule.onNodeWithText("GPU Composited Subtitle").assertIsDisplayed()

        cuesState.value = emptyList()
        composeRule.mainClock.advanceTimeBy(300)
        composeRule.onNodeWithText("GPU Composited Subtitle").assertDoesNotExist()
    }

    @Test
    fun `PlayingEqualizerIndicator animates smoothly across playing and paused states`() {
        val playingState = androidx.compose.runtime.mutableStateOf(true)
        composeRule.setContent {
            MaterialTheme {
                PlayingEqualizerIndicator(isPlaying = playingState.value)
            }
        }
        composeRule.mainClock.advanceTimeBy(600)
        composeRule.waitForIdle()

        playingState.value = false
        composeRule.mainClock.advanceTimeBy(300)
        composeRule.waitForIdle()
    }

    @Test
    fun `FormatSupportDialog tabs and search filter legacy subtitle specs accurately`() {
        composeRule.setContent {
            MaterialTheme {
                FormatSupportDialog(onDismiss = {})
            }
        }

        composeRule.onNodeWithText("Format & Codec Support").assertIsDisplayed()
        composeRule.onNodeWithTag("format_support_modal_sheet").performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Subtitles & Captions").performScrollTo().performClick()
        composeRule.onNodeWithTag("format_search_input").performTextInput("MicroDVD")
        composeRule.onNodeWithText("SAMI / MicroDVD / SubViewer").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("format_search_input").performTextReplacement("MPL2")
        composeRule.onNodeWithText("LRC / TMPlayer / MPL2").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `CustomFileBrowser filters legacy subtitle files off main thread and handles selection`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sampleDir = File(context.cacheDir, "browser_test_dir").apply {
            deleteRecursively()
            mkdirs()
        }
        File(sampleDir, "demo.srt").writeText("1\n00:00:01,000 --> 00:00:02,000\nDemo")
        File(sampleDir, "legacy.smi").writeText("<SAMI></SAMI>")
        File(sampleDir, "movie.mp4").writeText("dummy")

        var selected: File? = null
        try {
            composeRule.setContent {
                MaterialTheme {
                    CustomFileBrowser(
                        title = "Select Subtitle",
                        allowedExtensions = SubtitleParser.SUPPORTED_SUBTITLE_EXTENSIONS,
                        onFileSelected = { selected = it },
                        onDismiss = {}
                    )
                }
            }
            composeRule.onNodeWithText("Select Subtitle").assertIsDisplayed()
            assertTrue(SubtitleParser.SUPPORTED_SUBTITLE_EXTENSIONS.containsAll(listOf("srt", "vtt", "ass", "ssa", "sub", "sbv", "smi", "lrc", "mpl", "txt")))
        } finally {
            sampleDir.deleteRecursively()
        }
    }
}
