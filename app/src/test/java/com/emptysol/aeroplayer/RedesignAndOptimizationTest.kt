package com.emptysol.aeroplayer

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.emptysol.aeroplayer.ui.components.PlayerRightDrawerContent
import com.emptysol.aeroplayer.ui.screens.MainScreen
import com.emptysol.aeroplayer.ui.viewmodel.MainViewModel
import com.emptysol.aeroplayer.util.VideoPointDetector
import com.emptysol.aeroplayer.util.VideoPointType
import com.emptysol.aeroplayer.util.parseJumpTimeMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RedesignAndOptimizationTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `VideoPointDetector returns empty list when no native chapters exist`() {
        val shortResult = VideoPointDetector.detectPoints(durationMs = 45_000L)
        val movieResult = VideoPointDetector.detectPoints(durationMs = 7_200_000L)
        val customResult = VideoPointDetector.detectPoints(
            durationMs = 1_800_000L,
            customPrologueSec = 90,
            customPostCreditSec = 60,
            nativeChapters = emptyList()
        )

        assertTrue(shortResult.isEmpty())
        assertTrue(movieResult.isEmpty())
        assertTrue(customResult.isEmpty())
    }

    @Test
    fun `VideoPointDetector maps genuine embedded chapters accurately`() {
        val rawChapters = listOf(
            VideoPointDetector.NativeChapterRaw(name = "Opening Credits", startTimeMs = 0L, durationMs = 90_000L),
            VideoPointDetector.NativeChapterRaw(name = "Act I", startTimeMs = 90_000L, durationMs = 600_000L),
            VideoPointDetector.NativeChapterRaw(name = "Post-Credit Stinger", startTimeMs = 690_000L, durationMs = 45_000L)
        )

        val mapped = VideoPointDetector.detectPoints(
            durationMs = 735_000L,
            nativeChapters = rawChapters
        )

        assertEquals(3, mapped.size)
        assertEquals(VideoPointType.PROLOGUE, mapped[0].type)
        assertTrue(mapped[0].isSkippable)
        assertEquals(VideoPointType.CHAPTER, mapped[1].type)
        assertFalse(mapped[1].isSkippable)
        assertEquals(VideoPointType.POST_CREDIT, mapped[2].type)
        assertTrue(mapped.all { it.isNativeChapter })
    }

    @Test
    fun `parseJumpTimeMs parses human timestamps and rejects invalid inputs`() {
        assertEquals(90_000L, parseJumpTimeMs("90"))
        assertEquals(90_000L, parseJumpTimeMs("90s"))
        assertEquals(90_000L, parseJumpTimeMs("90sec"))
        assertEquals(90_000L, parseJumpTimeMs("1:30"))
        assertEquals(3_723_000L, parseJumpTimeMs("1:02:03"))
        assertNull(parseJumpTimeMs(""))
        assertNull(parseJumpTimeMs("   "))
        assertNull(parseJumpTimeMs("abc"))
        assertNull(parseJumpTimeMs("-5"))
        assertNull(parseJumpTimeMs("1:02:03:04"))
    }

    @Test
    fun `MainViewModel defers heavy engines at startup and preserves folder path across playback`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = MainViewModel(app)

        // Heavy players must not initialize eagerly during startup
        assertFalse(vm.isExoInitialized)
        assertFalse(vm.isVlcInitialized)

        // Open a nested folder and navigate via File Tree
        vm.selectPlaySubTab("Folder")
        vm.setActiveFolder("/storage/emulated/0/Movies/SciFi")
        vm.setActiveBrowsePath("/storage/emulated/0/Movies/SciFi")
        vm.setFolderPathSegments(listOf("Movies", "SciFi"))

        // Re-selecting the same sub-tab (e.g. returning from PlayerScreen) preserves folder context
        vm.selectPlaySubTab("Folder")
        assertEquals("/storage/emulated/0/Movies/SciFi", vm.activeFolder.value)
        assertEquals("/storage/emulated/0/Movies/SciFi", vm.activeBrowsePath.value)
        assertEquals(listOf("Movies", "SciFi"), vm.folderPathSegments.value)

        // Switching to a different sub-tab clears transient folder navigation cleanly
        vm.selectPlaySubTab("Audio")
        assertNull(vm.activeFolder.value)
        assertNull(vm.activeBrowsePath.value)
        assertTrue(vm.folderPathSegments.value.isEmpty())

        // Equalizer preset normalization keeps Main, Player, More & Settings aligned
        assertTrue(MainViewModel.EQUALIZER_PRESETS.contains("Flat"))
        vm.applyPreset("Normal")
        assertEquals("Flat", vm.currentEqualizerPreset.value)
        vm.applyPreset("Bass Booster")
        assertEquals("Bass Boost", vm.currentEqualizerPreset.value)
    }

    @Test
    fun `PlayerRightDrawerContent hides Chapters card when no chapters exist and shows it when present`() {
        val pointsState = androidx.compose.runtime.mutableStateOf(
            emptyList<com.emptysol.aeroplayer.util.VideoPoint>()
        )

        composeRule.setContent {
            MaterialTheme {
                PlayerRightDrawerContent(
                    isAudio = false,
                    effectiveEngine = "ExoPlayer",
                    onSelectEngine = {},
                    videoPoints = pointsState.value,
                    currentPosition = 12_000L,
                    duration = 300_000L,
                    onJumpToPoint = {},
                    onOpenAllPoints = {},
                    playbackSpeed = 1.0f,
                    onSpeedChange = {},
                    eqEnabled = false,
                    currentEqPreset = "Flat",
                    onToggleEq = {},
                    onOpenEqualizer = {},
                    playAsAudioOnly = false,
                    onToggleAudioOnly = {},
                    isPipActive = false,
                    onTogglePip = {},
                    sleepTimeLeftMinutes = 0,
                    onSetSleepTimer = {},
                    onCancelSleepTimer = {},
                    onJumpToTimeMs = {},
                    onSeekDeltaSeconds = {},
                    pointA = null,
                    pointB = null,
                    abRepeatEnabled = false,
                    onCycleAbPoint = {},
                    onResetAbLoop = {},
                    audioDelayMs = 0L,
                    onAudioDelayChange = {},
                    bookmarks = emptyList(),
                    onAddBookmark = {},
                    onRemoveBookmark = {},
                    playQueueSize = 1,
                    onSaveQueueAsPlaylist = {},
                    onOpenSubtitles = {},
                    onOpenMediaDetails = {},
                    onOpenUsageTips = {},
                    onCloseDrawer = {},
                    formatDuration = { "05:00" }
                )
            }
        }

        composeRule.onNodeWithText("Playback Controls").assertIsDisplayed()
        composeRule.onNodeWithTag("drawer_video_points_card").assertDoesNotExist()

        pointsState.value = VideoPointDetector.detectPoints(
            durationMs = 300_000L,
            nativeChapters = listOf(
                VideoPointDetector.NativeChapterRaw("Intro", 0L, 40_000L),
                VideoPointDetector.NativeChapterRaw("Episode", 40_000L, 260_000L)
            )
        )
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("drawer_video_points_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("View All Chapters (2)").assertIsDisplayed()
    }

    @Test
    fun `selectLibraryVideoHome resets from Folder or More directly to Video library`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = MainViewModel(app)

        vm.selectPlaySubTab("Folder")
        vm.setActiveFolder("/storage/emulated/0/Movies/Action")
        vm.setActiveBrowsePath("/storage/emulated/0/Movies/Action")
        vm.setFolderPathSegments(listOf("Movies", "Action"))
        vm.selectTab("More")

        assertEquals("More", vm.selectedTab.value)
        assertEquals("Folder", vm.playSubTab.value)

        vm.selectLibraryVideoHome()

        assertEquals("Play", vm.selectedTab.value)
        assertEquals("Video", vm.playSubTab.value)
        assertNull(vm.activeFolder.value)
        assertNull(vm.activeBrowsePath.value)
        assertTrue(vm.folderPathSegments.value.isEmpty())
    }

    @Test
    fun `PlayerRightDrawerContent adapts cleanly to narrow 30 percent portrait width`() {
        composeRule.setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .width(115.dp)
                        .height(700.dp)
                ) {
                    PlayerRightDrawerContent(
                        isAudio = false,
                        effectiveEngine = "ExoPlayer",
                        onSelectEngine = {},
                        videoPoints = emptyList(),
                        currentPosition = 0L,
                        duration = 300_000L,
                        onJumpToPoint = {},
                        onOpenAllPoints = {},
                        playbackSpeed = 1.0f,
                        onSpeedChange = {},
                        eqEnabled = false,
                        currentEqPreset = "Flat",
                        onToggleEq = {},
                        onOpenEqualizer = {},
                        playAsAudioOnly = false,
                        onToggleAudioOnly = {},
                        isPipActive = false,
                        onTogglePip = {},
                        sleepTimeLeftMinutes = 0,
                        onSetSleepTimer = {},
                        onCancelSleepTimer = {},
                        onJumpToTimeMs = {},
                        onSeekDeltaSeconds = {},
                        pointA = null,
                        pointB = null,
                        abRepeatEnabled = false,
                        onCycleAbPoint = {},
                        onResetAbLoop = {},
                        audioDelayMs = 0L,
                        onAudioDelayChange = {},
                        bookmarks = emptyList(),
                        onAddBookmark = {},
                        onRemoveBookmark = {},
                        playQueueSize = 1,
                        onSaveQueueAsPlaylist = {},
                        onOpenSubtitles = {},
                        onOpenMediaDetails = {},
                        onOpenUsageTips = {},
                        onCloseDrawer = {},
                        formatDuration = { "05:00" }
                    )
                }
            }
        }

        composeRule.onNodeWithTag("engine_switch_card").assertIsDisplayed()
        composeRule.onNodeWithTag("engine_pill_exoplayer").assertIsDisplayed()
        composeRule.onNodeWithTag("engine_pill_vlc").assertIsDisplayed()
    }

    @Test
    fun `MainScreen bottom bar shows Download pill for Torrent Manager without Folder Audio or Favorites circles`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = MainViewModel(app)
        var torrentsOpened = false

        composeRule.setContent {
            MaterialTheme {
                MainScreen(
                    viewModel = vm,
                    onPlayItem = {},
                    onNavigateToSettings = {},
                    onOpenPlayer = {},
                    onOpenTorrents = { torrentsOpened = true }
                )
            }
        }

        composeRule.onNodeWithTag("quick_nav_Folder").assertDoesNotExist()
        composeRule.onNodeWithTag("quick_nav_Audio").assertDoesNotExist()
        composeRule.onNodeWithTag("quick_nav_Favorites").assertDoesNotExist()
        composeRule.onNodeWithTag("nav_download_torrent_btn").assertIsDisplayed().performClick()
        assertTrue(torrentsOpened)
    }

    @Test
    fun `UnifiedSubtitleEngine deduplicates duplicate ASS and overlapping SRT cues and clears on bitmap mode`() = kotlinx.coroutines.runBlocking {
        val engine = com.emptysol.aeroplayer.player.subtitle.UnifiedSubtitleEngine(
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        )
        val duplicateSrt = """
            1
            00:00:01,000 --> 00:00:04,000
            Hello World

            2
            00:00:01,000 --> 00:00:04,000
            Hello World

            3
            00:00:02,000 --> 00:00:05,000
            Second Line
        """.trimIndent()

        engine.loadSubtitleText(duplicateSrt, formatHint = "srt", trackName = "Test SRT")
        repeat(100) {
            if (engine.hasParsedCues()) return@repeat
            kotlinx.coroutines.delay(20L)
        }
        assertTrue("Expected parsed SRT cues before querying position", engine.hasParsedCues())

        engine.updatePosition(2_500L)

        val activeTexts = engine.state.value.currentCues.map { it.text }
        assertEquals(listOf("Hello World", "Second Line"), activeTexts)

        // Switching to bitmap/native subtitle track immediately clears Compose text cues
        engine.setBitmapSubtitleActive(true)
        assertTrue(engine.state.value.currentCues.isEmpty())
    }

    @Test
    fun `MainViewModel setPlayingItemWithQueue resolves index cleanly and requestNextTrack debounces double-skips`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = MainViewModel(app)

        val ep1 = com.emptysol.aeroplayer.data.database.MediaEntity("file:///ep1.mkv", "Episode 1", null, null, 1000L, 100L, 1L, true, "/ep1.mkv", "video/x-matroska")
        val ep2 = com.emptysol.aeroplayer.data.database.MediaEntity("file:///ep2.mkv", "Episode 2", null, null, 1000L, 100L, 2L, true, "/ep2.mkv", "video/x-matroska")
        val ep3 = com.emptysol.aeroplayer.data.database.MediaEntity("file:///ep3.mkv", "Episode 3", null, null, 1000L, 100L, 3L, true, "/ep3.mkv", "video/x-matroska")

        // Omitting explicit index must resolve ep2's real index (1), not 0
        vm.setPlayingItemWithQueue(ep2, listOf(ep1, ep2, ep3))
        assertEquals(1, vm.currentQueueIndex.value)
        assertEquals(ep2, vm.currentPlayingItem.value)

        // Reset to ep1 and trigger immediate duplicate requestNextTrack() calls (simulating double-fire)
        vm.setPlayingItemWithQueue(ep1, listOf(ep1, ep2, ep3), 0)
        val firstStep = vm.requestNextTrack(minIntervalMs = 450L)
        val immediateDuplicateStep = vm.requestNextTrack(minIntervalMs = 450L)
        assertEquals(ep2, firstStep)
        assertEquals(ep2, immediateDuplicateStep)
        assertEquals(1, vm.currentQueueIndex.value)

        // After debounce window elapses, next advances strictly to ep3
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(500L))
        val afterWindowStep = vm.requestNextTrack(minIntervalMs = 450L)
        assertEquals(ep3, afterWindowStep)
        assertEquals(2, vm.currentQueueIndex.value)
    }

    @Test
    fun `compareNaturalStrings orders multi-digit episode titles numerically`() {
        val raw = listOf("Episode 10.mkv", "Episode 2.mkv", "Episode 1.mkv", "Episode 3.mkv")
        val sorted = raw.sortedWith { a, b ->
            com.emptysol.aeroplayer.ui.viewmodel.compareNaturalStrings(a, b)
        }
        assertEquals(
            listOf("Episode 1.mkv", "Episode 2.mkv", "Episode 3.mkv", "Episode 10.mkv"),
            sorted
        )
    }

    @Test
    fun `AeroVpnService DNS parsing, tracker blocking, IPv4 validation, and packet construction work cleanly`() {
        assertTrue(com.emptysol.aeroplayer.service.AeroVpnService.isValidIpv4("1.1.1.1"))
        assertTrue(com.emptysol.aeroplayer.service.AeroVpnService.isValidIpv4("94.140.14.14"))
        assertFalse(com.emptysol.aeroplayer.service.AeroVpnService.isValidIpv4("999.1.1.1"))
        assertFalse(com.emptysol.aeroplayer.service.AeroVpnService.isValidIpv4("not-an-ip"))

        // Build minimal DNS query header + QNAME for "pagead.doubleclick.net"
        val qname = byteArrayOf(
            6, 'p'.code.toByte(), 'a'.code.toByte(), 'g'.code.toByte(), 'e'.code.toByte(), 'a'.code.toByte(), 'd'.code.toByte(),
            11, 'd'.code.toByte(), 'o'.code.toByte(), 'u'.code.toByte(), 'b'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
            'c'.code.toByte(), 'l'.code.toByte(), 'i'.code.toByte(), 'c'.code.toByte(), 'k'.code.toByte(),
            3, 'n'.code.toByte(), 'e'.code.toByte(), 't'.code.toByte(),
            0, 0, 1, 0, 1
        )
        val dnsQuery = ByteArray(12) { 0 }.apply { this[0] = 0x12; this[1] = 0x34 } + qname
        val parsed = com.emptysol.aeroplayer.service.AeroVpnService.parseDnsQuestionDomain(dnsQuery, 0, dnsQuery.size)
        assertEquals("pagead.doubleclick.net", parsed)
        assertTrue(com.emptysol.aeroplayer.service.AeroVpnService.isBlockedTrackerDomain(parsed!!))
        assertFalse(com.emptysol.aeroplayer.service.AeroVpnService.isBlockedTrackerDomain("ubuntu.com"))

        val nx = com.emptysol.aeroplayer.service.AeroVpnService.buildNxDomainDnsResponse(dnsQuery)
        assertEquals(0x80, nx[2].toInt() and 0x80)
        assertEquals(3, nx[3].toInt() and 0x0F)

        val ipUdp = com.emptysol.aeroplayer.service.AeroVpnService.buildIpv4UdpResponsePacket(
            srcIp = byteArrayOf(1, 1, 1, 1),
            dstIp = byteArrayOf(10, 8, 0, 2),
            srcPort = 53,
            dstPort = 54321,
            payload = nx
        )
        assertEquals(20 + 8 + nx.size, ipUdp.size)
        assertEquals(0x45, ipUdp[0].toInt() and 0xFF)
        assertEquals(17, ipUdp[9].toInt() and 0xFF)
    }

    @Test
    fun `VpnAndCyberGhostHelper persists inbuilt profile and custom add-on VPN providers`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val customConfig = com.emptysol.aeroplayer.torrent.AeroVpnConfig(
            selectedMode = "INBUILT",
            inbuiltProfileId = "adguard",
            customPrimaryDns = "9.9.9.9",
            customSecondaryDns = "149.112.112.112",
            splitTunnelAppOnly = true,
            blockTrackers = true
        )
        com.emptysol.aeroplayer.torrent.VpnAndCyberGhostHelper.saveConfig(app, customConfig)
        val loaded = com.emptysol.aeroplayer.torrent.VpnAndCyberGhostHelper.loadConfig(app)
        assertEquals("INBUILT", loaded.selectedMode)
        assertEquals("adguard", loaded.inbuiltProfileId)
        assertTrue(loaded.splitTunnelAppOnly)
        assertTrue(loaded.blockTrackers)

        val withAddon = com.emptysol.aeroplayer.torrent.VpnAndCyberGhostHelper.addCustomAddonPackage(
            app,
            "com.tailscale.ipn"
        )
        assertEquals("ADDON", withAddon.selectedMode)
        assertTrue(withAddon.customAddonPackages.contains("com.tailscale.ipn"))
        val available = com.emptysol.aeroplayer.torrent.VpnAndCyberGhostHelper.getAvailableAddOnVpns(app)
        assertTrue(available.any { it.packageName == "com.tailscale.ipn" && it.isCustomAdded })

        val removed = com.emptysol.aeroplayer.torrent.VpnAndCyberGhostHelper.removeCustomAddonPackage(
            app,
            "com.tailscale.ipn"
        )
        assertFalse(removed.customAddonPackages.contains("com.tailscale.ipn"))
    }

    @Test
    fun `TorrentManagerScreen supports opening directly to Inbuilt and Add-On VPN tab`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = MainViewModel(app)
        vm.updateVpnConfig { it.copy(selectedMode = "INBUILT", inbuiltProfileId = "cloudflare") }

        composeRule.setContent {
            MaterialTheme {
                com.emptysol.aeroplayer.ui.screens.TorrentManagerScreen(
                    viewModel = vm,
                    onBack = {},
                    onPlayMedia = {},
                    initialTabKey = "REMOTE_VPN"
                )
            }
        }

        composeRule.onNodeWithTag("vpn_mode_inbuilt").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("btn_toggle_inbuilt_vpn").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("vpn_profile_adguard").performScrollTo().performClick()
        assertEquals("adguard", vm.vpnConfigFlow.value.inbuiltProfileId)

        composeRule.onNodeWithTag("vpn_mode_addon").performScrollTo().performClick()
        assertEquals("ADDON", vm.vpnConfigFlow.value.selectedMode)
        composeRule.onNodeWithTag("btn_add_custom_vpn_addon").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `MainScreen side drawer exposes dedicated Torrent & VPN Hub and Inbuilt VPN entries`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = MainViewModel(app)
        val requestedTabs = mutableListOf<String>()

        composeRule.setContent {
            MaterialTheme {
                MainScreen(
                    viewModel = vm,
                    onPlayItem = {},
                    onNavigateToSettings = {},
                    onOpenPlayer = {},
                    onOpenTorrents = {},
                    onOpenTorrentVpnHub = { tabKey -> requestedTabs.add(tabKey) }
                )
            }
        }

        composeRule.onNodeWithContentDescription("Open side drawer").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("drawer_torrent_vpn_item").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Open side drawer").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("drawer_inbuilt_vpn_item").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("DOWNLOADS", "REMOTE_VPN"), requestedTabs)
    }
}


