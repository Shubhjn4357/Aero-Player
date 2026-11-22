package com.emptysol.aeroplayer

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.emptysol.aeroplayer.data.database.MediaEntity
import com.emptysol.aeroplayer.player.subtitle.SubtitleParser
import com.emptysol.aeroplayer.ui.screens.buildMediaItemWithSubtitles
import com.emptysol.aeroplayer.ui.screens.invalidateSubtitleCache
import com.emptysol.aeroplayer.ui.viewmodel.MainViewModel
import com.emptysol.aeroplayer.util.CategoryMetadataManager
import com.emptysol.aeroplayer.util.ContentResolverUtils
import com.emptysol.aeroplayer.util.MediaFormatCategory
import com.emptysol.aeroplayer.util.StreamPlaylistParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Pipeline verification across dual-engine format routing (ExoPlayer vs LibVLC),
 * MediaItem subtitle filtering (no crashes on legacy sub/sbv/smi/lrc),
 * MIME inference, equalizer state/presets, queue transitions, and heavy library search.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EngineSwitchingAndFormatPipelineTest {

    private fun dummyEntity(
        uri: String,
        path: String = uri,
        isVideo: Boolean = true,
        mime: String? = null
    ) = MediaEntity(
        uriString = uri,
        title = uri.substringAfterLast('/'),
        artist = "Test Artist",
        album = "Test Album",
        duration = 120_000L,
        size = 50_000_000L,
        dateAdded = 1700000000L,
        isVideo = isVideo,
        path = path,
        mimeType = mime
    )

    @Test
    fun `smart engine metadata routes legacy formats to LibVLC and modern formats to ExoPlayer`() {
        val vlcFormats = listOf(".avi", ".flv", ".wmv", ".vob", ".ogv", ".divx", ".rmvb", ".rm", ".ts", ".m2ts", ".asf", ".mpg", ".mpeg")
        vlcFormats.forEach { ext ->
            val meta = CategoryMetadataManager.extractCategoryMetadata(dummyEntity("/movies/video$ext"))
            assertTrue("Expected LibVLC recommendation for $ext but got ${meta.recommendedEngine}",
                meta.recommendedEngine.contains("VLC", ignoreCase = true))
        }

        val exoFormats = listOf(".mp4", ".mkv", ".webm", ".mov", ".m4v", ".mp3", ".flac", ".aac", ".opus")
        exoFormats.forEach { ext ->
            val meta = CategoryMetadataManager.extractCategoryMetadata(dummyEntity("/movies/media$ext", isVideo = ext in listOf(".mp4", ".mkv", ".webm", ".mov", ".m4v")))
            assertTrue("Expected ExoPlayer recommendation for $ext but got ${meta.recommendedEngine}",
                meta.recommendedEngine.contains("ExoPlayer", ignoreCase = true))
        }
    }

    @Test
    fun `ContentResolverUtils inferMimeType resolves all modern and legacy containers cleanly`() {
        val expected = mapOf(
            "/v/a.mp4" to "video/mp4",
            "/v/a.mkv" to "video/x-matroska",
            "/v/a.flv" to "video/x-flv",
            "/v/a.avi" to "video/x-msvideo",
            "/v/a.wmv" to "video/x-ms-wmv",
            "/v/a.ts" to "video/mp2t",
            "/v/a.m2ts" to "video/mp2t",
            "/v/a.vob" to "video/mpeg",
            "/v/a.ogv" to "video/ogg",
            "/a/b.flac" to "audio/flac",
            "/a/b.opus" to "audio/ogg",
            "/a/b.ac3" to "audio/ac3",
            "/a/b.eac3" to "audio/eac3",
            "/s/sub.vtt" to "text/vtt",
            "/s/sub.ass" to "text/x-ssa",
            "/s/sub.smi" to "application/smil+xml",
            "https://example.com/live/master.m3u8?token=abc" to "application/x-mpegURL"
        )
        expected.forEach { (path, mime) ->
            assertEquals("Failed MIME inference for $path", mime, ContentResolverUtils.inferMimeType(path, path, null))
        }
    }

    @Test
    fun `buildMediaItemWithSubtitles only attaches ExoPlayer-native subtitles and excludes legacy formats`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val subtitleDir = File(context.filesDir, "subtitles").apply { mkdirs() }
        val videoUri = "file:///storage/emulated/0/Movies/feature.mkv"
        val videoHash = videoUri.hashCode().toString()

        // Create both native (.vtt, .ass) and legacy (.sub, .smi, .sbv) cached subtitles
        File(subtitleDir, "sub_${videoHash}_en.vtt").writeText("WEBVTT\n\n00:01.000 --> 00:02.000\nHi")
        File(subtitleDir, "sub_${videoHash}_jp.ass").writeText("[Events]\nDialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Yo")
        File(subtitleDir, "sub_${videoHash}_old.sub").writeText("{1}{50}Legacy")
        File(subtitleDir, "sub_${videoHash}_smi.smi").writeText("<SAMI><BODY><SYNC Start=100>Hi</BODY></SAMI>")
        invalidateSubtitleCache()

        try {
            // Native external .srt passes through to ExoPlayer
            val exoSrtItem = buildMediaItemWithSubtitles(
                uriString = videoUri,
                context = context,
                externalSubtitleUri = "file:///subs/external.srt",
                title = "Feature"
            )
            val configs = exoSrtItem.localConfiguration?.subtitleConfigurations.orEmpty()
            // 1 external .srt + 2 native cached (.vtt, .ass) = 3; legacy .sub/.smi excluded
            assertEquals(3, configs.size)
            assertTrue(configs.any { it.mimeType == "application/x-subrip" })
            assertTrue(configs.any { it.mimeType == "text/vtt" })
            assertTrue(configs.any { it.mimeType == "text/x-ssa" })

            // Legacy external .sbv / .sub / .lrc / .smi / .mpl MUST NOT be added as Exo SubtitleConfiguration
            listOf("file:///subs/a.sub", "file:///subs/b.sbv", "file:///subs/c.smi", "file:///subs/d.lrc", "file:///subs/e.mpl").forEach { leg ->
                assertFalse("Should not be native Exo sub: $leg", SubtitleParser.isNativelySupportedByExo(leg))
                val item = buildMediaItemWithSubtitles(
                    uriString = "file:///storage/emulated/0/Movies/clean.mp4",
                    context = context,
                    externalSubtitleUri = leg
                )
                assertTrue(
                    "Legacy subtitle $leg must be handled by UnifiedSubtitleOverlay, not injected into Exo MediaItem",
                    item.localConfiguration?.subtitleConfigurations.isNullOrEmpty()
                )
            }
        } finally {
            subtitleDir.deleteRecursively()
            invalidateSubtitleCache()
        }
    }

    @Test
    fun `MainViewModel equalizer state presets queue switching and sample URLs integrity`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = MainViewModel(app)

        // Ensure equalizer initializes cleanly & applies presets accurately
        vm.ensureDefaultBands()
        assertEquals(5, vm.equalizerBands.value.size)

        vm.setEqualizerEnabled(true)
        assertTrue(vm.equalizerEnabled.value)

        vm.applyPreset("Bass Boost")
        val bassBands = vm.equalizerBands.value
        assertEquals(600.toShort(), bassBands[0].currentLevelMilliBel)
        assertEquals(350.toShort(), bassBands[1].currentLevelMilliBel)

        vm.setEqualizerBandLevel(2, (-350).toShort())
        assertEquals((-350).toShort(), vm.equalizerBands.value[2].currentLevelMilliBel)

        // Queue mixing MP4 (Exo), FLV (VLC), and AVI (VLC)
        val q0 = dummyEntity("file:///v/clip0.mp4")
        val q1 = dummyEntity("file:///v/clip1.flv")
        val q2 = dummyEntity("file:///v/clip2.avi")
        vm.setPlayingItemWithQueue(q0, listOf(q0, q1, q2), 0)
        assertEquals(q0, vm.currentPlayingItem.value)

        val next = vm.playNext()
        assertEquals(q1, next)
        assertEquals(q1, vm.currentPlayingItem.value)

        val next2 = vm.playNext()
        assertEquals(q2, next2)

        val prev = vm.playPrevious()
        assertEquals(q1, prev)

        // Verify SoundHelix URL fix in MainViewModel seed data
        vm.loadDemoMediaPack()
        var wait = 0
        while (vm.mediaList.value.isEmpty() && wait < 50) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            delay(30)
            wait++
        }
        val samples = vm.mediaList.value
        assertTrue(
            "Expected SoundHelix URL in mediaList (size=${samples.size}): ${samples.map { it.uriString }}",
            samples.any { it.uriString.contains("soundhelix.com/examples/mp3/") }
        )
        samples.forEach { entity ->
            assertFalse("Broken URL found: ${entity.uriString}", entity.uriString.contains(".emptysol.aeroplayers"))
        }
    }

    @Test
    fun `CategoryMetadataManager exposes full subtitle catalog and stream playlist detection works`() {
        val all = CategoryMetadataManager.getAllSupportedCategories()
        val subs = all[MediaFormatCategory.SUBTITLE_FORMAT].orEmpty()
        assertNotNull(subs.find { it.extension.contains(".srt") })
        assertNotNull(subs.find { it.extension.contains(".vtt") })
        assertNotNull(subs.find { it.extension.contains(".ass") })
        assertNotNull(subs.find { it.extension.contains(".sub") })
        assertNotNull(subs.find { it.extension.contains(".sbv") })
        assertNotNull(subs.find { it.extension.contains(".lrc") })
        assertNotNull(subs.find { it.extension.contains(".smi") })
        assertNotNull(subs.find { it.extension.contains(".mpl") })
        assertNotNull(subs.find { it.extension.contains(".txt") })

        assertTrue(StreamPlaylistParser.isPlaylistUrl("https://tv.example.com/playlist.m3u8?auth=1"))
        assertTrue(StreamPlaylistParser.isPlaylistUrl("https://tv.example.com/channels.m3u"))
        assertFalse(StreamPlaylistParser.isPlaylistUrl("https://tv.example.com/movie.mp4"))
    }
}
