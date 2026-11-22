package com.emptysol.aeroplayer

import android.content.Context
import androidx.media3.common.text.Cue
import androidx.test.core.app.ApplicationProvider
import com.emptysol.aeroplayer.player.subtitle.SubtitleAlignment
import com.emptysol.aeroplayer.player.subtitle.SubtitleParser
import com.emptysol.aeroplayer.player.subtitle.UnifiedSubtitleEngine
import com.emptysol.aeroplayer.ui.viewmodel.MainViewModel
import com.emptysol.aeroplayer.ui.viewmodel.PlayerControlBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.lang.ref.WeakReference
import java.nio.charset.Charset
import kotlin.system.measureTimeMillis

/**
 * Exhaustive & high-load verification suite for the modern + legacy subtitle pipeline,
 * charset auto-detection, binary VobSub guard, O(log N) lookup latency, race-free switching,
 * and delay/toggle synchronization across PlayerControlBridge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SubtitlePipelineStressTest {

    @Test
    fun `parses SRT with br tags html markup dot commas and irregular spacing`() {
        val rawSrt = """
            1
            00:00:01,500 --> 00:00:04.250
            <i>First line</i><br/>Second line

            2
            00:00:05.000 --> 00:00:07,000
            Third<br>Fourth
        """.trimIndent()

        val cues = SubtitleParser.parseText(rawSrt, "movie.srt")
        assertEquals(2, cues.size)
        assertEquals(1500L, cues[0].startMs)
        assertEquals(4250L, cues[0].endMs)
        assertEquals("First line\nSecond line", cues[0].text)
        assertEquals("Third\nFourth", cues[1].text)
    }

    @Test
    fun `parses WebVTT skipping STYLE NOTE REGION blocks and keeping short timestamps`() {
        val rawVtt = """
            WEBVTT
            Kind: captions

            STYLE
            ::cue {
              color: lime;
            }

            NOTE This is a multi-line comment
            00:10.000 --> 00:20.000 ignored comment cue

            00:02.500 --> 00:05.750
            <v Alice>Hello <c.yellow>World</c></v>

            01:02:03.100 --> 01:02:06.200
            Long timestamp cue
        """.trimIndent()

        val cues = SubtitleParser.parseText(rawVtt, "sample.vtt")
        assertEquals(2, cues.size)
        assertEquals(2500L, cues[0].startMs)
        assertEquals(5750L, cues[0].endMs)
        assertEquals("Hello World", cues[0].text)
        assertEquals(3723100L, cues[1].startMs)
    }

    @Test
    fun `parses ASS and SSA with reordered Format columns alignment and suppresses vector drawings`() {
        val rawAss = """
            [Script Info]
            Title: Reordered ASS

            [Events]
            Format: Text, End, Start, Layer
            Dialogue: {\an8}Top Title\NSecond Line,0:00:05.50,0:00:01.20,0
            Dialogue: {\an1}Bottom Left,0:00:08.00,0:00:06.00,0
            Dialogue: {\an5}Center Note,0:00:10.00,0:00:09.00,0
            Dialogue: {\p1}m 0 0 l 100 0 100 100 0 100{\p0},0:00:12.00,0:00:11.00,0
            Dialogue: m 10 20 l 30 40 50 60 c,0:00:13.00,0:00:12.50,0
        """.trimIndent()

        val cues = SubtitleParser.parseText(rawAss, "anime.ass")
        assertEquals(3, cues.size)
        assertEquals(1200L, cues[0].startMs)
        assertEquals(5500L, cues[0].endMs)
        assertEquals(SubtitleAlignment.TOP_CENTER, cues[0].alignment)
        assertEquals("Top Title\nSecond Line", cues[0].text)
        assertEquals(SubtitleAlignment.BOTTOM_LEFT, cues[1].alignment)
        assertEquals(SubtitleAlignment.CENTER, cues[2].alignment)
    }

    @Test
    fun `parses legacy MicroDVD sub with FPS header and pipe newlines`() {
        val microDvd = """
            {1}{1}23.976
            {24}{72}Hello World|Line Two
            {100}{}Open-ended microdvd line
        """.trimIndent()

        val cues = SubtitleParser.parseText(microDvd, "classic.sub")
        assertEquals(2, cues.size)
        // Frame 24 @ 23.976fps ≈ 1001ms
        assertTrue(cues[0].startMs in 999L..1003L)
        assertTrue(cues[0].endMs in 3000L..3006L)
        assertEquals("Hello World\nLine Two", cues[0].text)
        // Open-ended fallback gives ~4 seconds (fps * 4 frames at 23.976fps ≈ 3963ms)
        val openDuration = cues[1].endMs - cues[1].startMs
        assertTrue("Expected ~4s fallback duration, got $openDuration", openDuration in 3900L..4100L)
    }

    @Test
    fun `parses SubViewer sbv and sub legacy formats`() {
        val sbv = """
            0:00:01.000,0:00:03.500
            First SubViewer line[br]Second line

            0:00:04.250,0:00:06.000
            Clean closing cue
        """.trimIndent()
        val sbvCues = SubtitleParser.parseText(sbv, "legacy.sbv")
        assertEquals(2, sbvCues.size)
        assertEquals(1000L, sbvCues[0].startMs)
        assertEquals(3500L, sbvCues[0].endMs)
        assertEquals("First SubViewer line\nSecond line", sbvCues[0].text)

        val subViewerSub = """
            [INFORMATION]
            [TITLE]Old Film
            00:00:10.00,00:00:12.50
            Classic SubViewer[br]Next Line
        """.trimIndent()
        val svCues = SubtitleParser.parseText(subViewerSub, "oldfilm.sub")
        assertEquals(1, svCues.size)
        assertEquals(10000L, svCues[0].startMs)
        assertEquals("Classic SubViewer\nNext Line", svCues[0].text)
    }

    @Test
    fun `parses legacy SAMI smi format computing end times from next SYNC tag`() {
        val smi = """
            <SAMI>
            <BODY>
            <SYNC Start=1000><P Class=ENCC>Welcome<br>Home</P>
            <SYNC Start=3500><P Class=ENCC>&nbsp;</P>
            <SYNC Start=5000><P Class=ENCC>Final line</P>
            </BODY>
            </SAMI>
        """.trimIndent()

        val cues = SubtitleParser.parseText(smi, "movie.smi")
        assertEquals(2, cues.size)
        assertEquals(1000L, cues[0].startMs)
        assertEquals(3500L, cues[0].endMs)
        assertEquals("Welcome\nHome", cues[0].text)
        assertEquals(5000L, cues[1].startMs)
        assertEquals(10000L, cues[1].endMs)
    }

    @Test
    fun `parses LRC synchronized lyrics with multi-timestamp lines and offset tag`() {
        val lrc = """
            [ar:Artist]
            [offset:+500]
            [00:10.00][00:30.50]Chorus repeats twice
            [00:20.250]Verse one
        """.trimIndent()

        val cues = SubtitleParser.parseText(lrc, "track.lrc")
        assertEquals(3, cues.size)
        // [offset:+500] shifts timestamps 500ms earlier -> 10000 - 500 = 9500
        assertEquals(9500L, cues[0].startMs)
        assertEquals("Chorus repeats twice", cues[0].text)
        assertEquals(19750L, cues[1].startMs)
        assertEquals(30000L, cues[2].startMs)
    }

    @Test
    fun `parses TMPlayer txt and MPL2 mpl legacy formats`() {
        val tmPlayer = """
            00:00:02:First TM line|Second TM line
            00:00:07=Equal delimiter line
        """.trimIndent()
        val tmCues = SubtitleParser.parseText(tmPlayer, "movie.txt")
        assertEquals(2, tmCues.size)
        assertEquals(2000L, tmCues[0].startMs)
        assertEquals(6999L, tmCues[0].endMs)
        assertEquals("First TM line\nSecond TM line", tmCues[0].text)

        val mpl2 = """
            [15][45]/Italic MPL line|Second MPL line
            [50][80]Plain MPL
        """.trimIndent()
        val mplCues = SubtitleParser.parseText(mpl2, "movie.mpl")
        assertEquals(2, mplCues.size)
        // MPL2 uses deciseconds (15 * 100 = 1500ms)
        assertEquals(1500L, mplCues[0].startMs)
        assertEquals(4500L, mplCues[0].endMs)
        assertEquals("Italic MPL line\nSecond MPL line", mplCues[0].text)
    }

    @Test
    fun `rejects binary VobSub sub streams safely without crashing or hanging`() {
        // Real MPEG-2 Program Stream packet header used by binary DVD VobSub .sub
        val vobSubHeader = byteArrayOf(0x00, 0x00, 0x01, 0xBA.toByte(), 0x44, 0x00, 0x04, 0x00)
        assertTrue(SubtitleParser.isBinarySubtitleBytes(vobSubHeader))
        val cues = SubtitleParser.parseFromInputStream(ByteArrayInputStream(vobSubHeader), "UTF-8", "dvd.sub")
        assertTrue(cues.isEmpty())
    }

    @Test
    fun `auto-detects UTF-16LE UTF-8 BOM and Windows-1252 legacy encodings`() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nCafé résumé — OK\n"

        // UTF-16LE with BOM
        val utf16LeBytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + srt.toByteArray(Charsets.UTF_16LE)
        val cues16 = SubtitleParser.parseFromInputStream(ByteArrayInputStream(utf16LeBytes), "Default (Windows-1252)", "utf16.srt")
        assertEquals(1, cues16.size)
        assertEquals("Café résumé — OK", cues16[0].text)

        // UTF-8 with BOM
        val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + srt.toByteArray(Charsets.UTF_8)
        val cues8 = SubtitleParser.parseFromInputStream(ByteArrayInputStream(utf8Bom), "Default (Windows-1252)", "utf8.srt")
        assertEquals(1, cues8.size)
        assertEquals("Café résumé — OK", cues8[0].text)

        // Windows-1252
        val winSrt = "1\n00:00:01,000 --> 00:00:02,000\nCinéma français\n"
        val winBytes = winSrt.toByteArray(Charset.forName("windows-1252"))
        val cuesWin = SubtitleParser.parseFromInputStream(ByteArrayInputStream(winBytes), "Default (Windows-1252)", "win.srt")
        assertEquals(1, cuesWin.size)
        assertEquals("Cinéma français", cuesWin[0].text)
    }

    @Test
    fun `heavy load 12000 cues binary search benchmark and accuracy on UnifiedSubtitleEngine`() = runBlocking {
        val sb = StringBuilder(1_000_000)
        val cueCount = 12_000
        for (i in 0 until cueCount) {
            val startSec = i * 3
            val endSec = startSec + 2
            val sh = startSec / 3600
            val sm = (startSec % 3600) / 60
            val ss = startSec % 60
            val eh = endSec / 3600
            val em = (endSec % 3600) / 60
            val es = endSec % 60
            sb.append(i + 1).append('\n')
            sb.append(String.format("%02d:%02d:%02d,000 --> %02d:%02d:%02d,500\n", sh, sm, ss, eh, em, es))
            sb.append("Heavy cue number $i\n\n")
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val engine = UnifiedSubtitleEngine(scope)
            engine.loadSubtitleText(sb.toString(), "heavy.srt", "Heavy 12k Track")

            // Wait briefly for CPU Default parse completion
            var attempts = 0
            while (!engine.hasParsedCues() && attempts < 100) {
                delay(20)
                attempts++
            }
            assertTrue("Expected 12,000 cues to finish loading cleanly", engine.hasParsedCues())

            // Verify pinpoint lookup across random/boundary positions + measure 5,000 lookups
            val queryElapsed = measureTimeMillis {
                for (q in 0 until 5_000) {
                    val idx = (q * 7) % cueCount
                    val posMs = idx * 3_000L + 500L
                    engine.updatePosition(posMs)
                    val active = engine.state.value.currentCues
                    assertEquals(1, active.size)
                    assertEquals("Heavy cue number $idx", active[0].text)
                }
            }
            assertTrue("5,000 O(log N) binary searches over 12k cues should complete fast (took ${queryElapsed}ms)", queryElapsed < 2000L)

            // Verify gap between cues returns empty
            engine.updatePosition(2_750L)
            assertTrue(engine.state.value.currentCues.isEmpty())

            // Verify delay shift aligns cue correctly
            engine.setSubtitleDelay(1_000L) // pos + 1000 -> 2750 + 1000 = 3750ms -> inside cue #1 (3000..5500)
            assertEquals("Heavy cue number 1", engine.state.value.currentCues.first().text)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `rapid external subtitle switching cancels stale jobs without race overwrite`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tmpDir = File(context.cacheDir, "sub_race_test").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val engine = UnifiedSubtitleEngine(scope)
            val files = (1..10).map { i ->
                File(tmpDir, "track_$i.srt").apply {
                    writeText("1\n00:00:01,000 --> 00:00:10,000\nTrack Winner $i\n")
                }
            }

            files.forEach { file ->
                engine.loadExternalSubtitle(context, file.toURI().toString(), "UTF-8", file.name)
            }

            delay(350)
            engine.updatePosition(2_000L)
            val active = engine.state.value.currentCues
            assertEquals(1, active.size)
            assertEquals("Track Winner 10", active[0].text)
        } finally {
            scope.cancel()
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `PlayerControlBridge adjustSubtitleDelay preserves milliseconds and clearExternalParsedCues hands back to Exo`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = MainViewModel(app)
        PlayerControlBridge.viewModelRef = WeakReference(vm)

        val scope = vm.subtitleEngine
        scope.loadSubtitleText("1\n00:00:01,000 --> 00:00:05,000\nExternal Cue\n", "test.srt")
        var wait = 0
        while (!scope.hasParsedCues() && wait < 50) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            delay(20)
            wait++
        }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertTrue(scope.hasParsedCues())

        // Regression check for fixed 1000x delay bug
        scope.setSubtitleDelay(0L)
        PlayerControlBridge.adjustSubtitleDelay(250L)
        assertEquals(250L, scope.state.value.subDelayMs)

        // Switching back to embedded clears external cues so Exo onCues renders
        scope.clearExternalParsedCues()
        assertFalse(scope.hasParsedCues())
        scope.onExoCues(listOf(Cue.Builder().setText("Embedded <i>CEA-608</i>").build()))
        assertEquals("Embedded CEA-608", scope.state.value.currentCues.first().text)

        PlayerControlBridge.viewModelRef = null
    }
}
