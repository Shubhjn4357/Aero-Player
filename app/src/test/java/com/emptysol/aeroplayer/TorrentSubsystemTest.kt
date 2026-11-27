package com.emptysol.aeroplayer

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.emptysol.aeroplayer.data.database.PreferenceEntity
import com.emptysol.aeroplayer.data.database.TorrentTaskEntity
import com.emptysol.aeroplayer.torrent.*
import com.emptysol.aeroplayer.ui.screens.TorrentManagerScreen
import com.emptysol.aeroplayer.ui.theme.MyApplicationTheme
import com.emptysol.aeroplayer.ui.viewmodel.MainViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TorrentSubsystemTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun bencodeAndMetainfoParser_extractsValidSingleAndMultiFileInfoHash() {
        val dummyPieces = ByteArray(40) { (it + 1).toByte() }
        val infoMap = linkedMapOf<String, Any>(
            "name" to "AeroSampleMovie".toByteArray(StandardCharsets.UTF_8),
            "piece length" to 262144L,
            "pieces" to dummyPieces,
            "files" to listOf(
                linkedMapOf("length" to 1048576L, "path" to listOf("video.mp4".toByteArray(StandardCharsets.UTF_8))),
                linkedMapOf("length" to 4096L, "path" to listOf("subs.srt".toByteArray(StandardCharsets.UTF_8)))
            )
        )
        val rootMap = linkedMapOf<String, Any>(
            "announce" to "https://tracker.example.org/announce".toByteArray(StandardCharsets.UTF_8),
            "announce-list" to listOf(
                listOf("udp://tracker.opentrackr.org:1337/announce".toByteArray(StandardCharsets.UTF_8))
            ),
            "info" to infoMap
        )

        val encodedTorrent = BencodeCodec.encode(rootMap)
        val meta = TorrentMetainfoParser.parse(encodedTorrent)

        assertEquals("AeroSampleMovie", meta.name)
        assertEquals(1048576L + 4096L, meta.totalLength)
        assertEquals(2, meta.pieceHashes.size)
        assertEquals(2, meta.files.size)
        assertEquals("video.mp4", meta.files[0].path)
        assertEquals("subs.srt", meta.files[1].path)
        assertTrue(meta.trackers.contains("https://tracker.example.org/announce"))
        assertTrue(meta.trackers.contains("udp://tracker.opentrackr.org:1337/announce"))

        val expectedHash = BencodeCodec.toHex(BencodeCodec.sha1(BencodeCodec.encode(infoMap)))
        assertEquals(expectedHash, meta.infoHashHex)
    }

    @Test
    fun magnetParser_parsesHexAndBase32InfoHashesAndTrackers() {
        val hexHash = "0123456789abcdef0123456789abcdef01234567"
        val magnetHex = "magnet:?xt=urn:btih:$hexHash&dn=Big+Buck+Bunny&tr=udp%3A%2F%2Ftracker.example.org%3A6969"
        val parsedHex = MagnetParser.parse(magnetHex)
        assertEquals(hexHash, parsedHex.infoHashHex)
        assertEquals("Big Buck Bunny", parsedHex.displayName)
        assertEquals(listOf("udp://tracker.example.org:6969"), parsedHex.trackers)

        // 32-char Base32 hash representing 20 bytes (160 bits)
        val base32Magnet = "magnet:?xt=urn:btih:AEBAGBAFAYDQQCIKBMGA2DQPCAIEAQCA&dn=Base32Release"
        val parsedB32 = MagnetParser.parse(base32Magnet)
        assertEquals(40, parsedB32.infoHashHex.length)
        assertEquals("Base32Release", parsedB32.displayName)
    }

    @Test
    fun ipFilterManager_blocksCidrPeerGuardianAndSingleIps() {
        val rules = """
            # Sample blocklist
            192.168.10.0/24
            BadRange:45.10.20.0-45.10.20.255
            8.8.4.4
        """.trimIndent()
        val filter = IpFilterManager(rules)
        assertEquals(3, filter.ruleCount())
        assertTrue(filter.isBlocked("192.168.10.42"))
        assertFalse(filter.isBlocked("192.168.11.42"))
        assertTrue(filter.isBlocked("45.10.20.128"))
        assertTrue(filter.isBlocked("8.8.4.4"))
        assertFalse(filter.isBlocked("1.1.1.1"))
    }

    @Test
    fun fileSafetyScanner_detectsSpoofedMediaAndEmbeddedApk() {
        val dir = kotlin.io.path.createTempDirectory("aero_scan_test").toFile()
        try {
            // 1. Spoofed Windows MZ executable pretending to be .mp4
            val spoofedVideo = File(dir, "movie_trailer.mp4").apply {
                writeBytes(byteArrayOf('M'.code.toByte(), 'Z'.code.toByte(), 0x90.toByte(), 0x00))
            }
            // 2. Clean MP4 ftyp file
            val cleanVideo = File(dir, "clean_clip.mp4").apply {
                writeBytes(byteArrayOf(0x00, 0x00, 0x00, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()))
            }
            // 3. Disguised APK archive containing AndroidManifest.xml
            val apkZip = File(dir, "update_installer.zip")
            ZipOutputStream(FileOutputStream(apkZip)).use { zos ->
                zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zos.write("<manifest/>".toByteArray())
                zos.closeEntry()
            }

            val r1 = FileSafetyScanner.scanSingleFile(spoofedVideo)
            assertEquals("BLOCKED", r1.status)
            assertTrue(r1.reason.contains("spoofing", ignoreCase = true))

            val r2 = FileSafetyScanner.scanSingleFile(cleanVideo)
            assertEquals("CLEAN", r2.status)
            assertEquals(64, r2.sha256.length)

            val r3 = FileSafetyScanner.scanSingleFile(apkZip)
            assertEquals("BLOCKED", r3.status)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rssAutoDownloader_parsesFeedXmlAndAppliesFilters() {
        val rssXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:torrent="http://xmlns.ezrss.it/0.1/">
              <channel>
                <title>Test Releases</title>
                <item>
                  <title>Cosmos Documentary 1080p x265</title>
                  <link>https://example.org/cosmos.torrent</link>
                  <torrent:magnetURI>magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567</torrent:magnetURI>
                </item>
                <item>
                  <title>Cosmos Documentary CAMRip</title>
                  <enclosure url="https://example.org/cam.torrent" type="application/x-bittorrent" />
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val downloader = RssAutoDownloader()
        val parsed = downloader.parseFeedXml(
            xmlContent = rssXml,
            includeFilter = "1080p",
            excludeFilter = "CAMRip"
        )
        assertEquals(2, parsed.size)
        assertTrue(parsed[0].matchesFilter)
        assertTrue(parsed[0].downloadUri.startsWith("magnet:"))
        assertFalse(parsed[1].matchesFilter)
        assertEquals("https://example.org/cam.torrent", parsed[1].downloadUri)
    }

    @Test
    fun torrentScheduler_evaluatesNormalAndWrapAroundWindows() {
        val cal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, Calendar.WEDNESDAY) // ISO day 3
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 30)
        }
        // Window 22:00 (1320) -> 06:00 (360) wraps across midnight
        val directive = TorrentScheduler.evaluate(
            schedulerEnabled = true,
            daysJson = "[3]",
            startMinuteOfDay = 1320,
            endMinuteOfDay = 360,
            activeAction = "SEED_ONLY",
            calendar = cal
        )
        assertEquals(ScheduleDirective.SEED_ONLY, directive)

        val outside = TorrentScheduler.evaluate(
            schedulerEnabled = true,
            daysJson = "[1,2]", // Mon, Tue only
            startMinuteOfDay = 0,
            endMinuteOfDay = 1439,
            activeAction = "DOWNLOAD_AND_SEED",
            calendar = cal
        )
        assertEquals(ScheduleDirective.PAUSE_ALL, outside)
    }

    @Test
    fun remoteAccessServer_servesWebUiStatusAndEnforcesPin() = runBlocking {
        val freePort = ServerSocket(0).use { it.localPort }
        val sampleTask = TorrentTaskEntity(
            infoHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            title = "Remote Test Video",
            sourceUri = "magnet:?xt=urn:btih:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            saveLocation = "/tmp",
            status = "DOWNLOADING",
            totalBytes = 1000L,
            downloadedBytes = 500L
        )
        var addedUri = ""
        val server = RemoteAccessServer(
            getTasksSnapshot = { listOf(sampleTask) },
            onAddMagnetOrUrl = { uri -> addedUri = uri },
            onPauseTorrent = {},
            onResumeTorrent = {},
            onRemoveTorrent = {},
            onStateChanged = {}
        )
        server.start(freePort, "1234")
        kotlinx.coroutines.delay(250)

        try {
            // 1. Root HTML UI loads cleanly
            val uiConn = URL("http://127.0.0.1:$freePort/").openConnection() as HttpURLConnection
            assertEquals(200, uiConn.responseCode)
            val html = uiConn.inputStream.bufferedReader().readText()
            assertTrue(html.contains("Aero Player"))

            // 2. Missing PIN on /api/status returns 401
            val unauthConn = URL("http://127.0.0.1:$freePort/api/status").openConnection() as HttpURLConnection
            assertEquals(401, unauthConn.responseCode)

            // 3. Valid PIN returns task JSON
            val authConn = (URL("http://127.0.0.1:$freePort/api/status").openConnection() as HttpURLConnection).apply {
                setRequestProperty("X-Aero-Pin", "1234")
            }
            assertEquals(200, authConn.responseCode)
            val json = authConn.inputStream.bufferedReader().readText()
            assertTrue(json.contains("Remote Test Video"))

            // 4. POST /api/add forwards magnet URI
            val addConn = (URL("http://127.0.0.1:$freePort/api/add").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("X-Aero-Pin", "1234")
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write("""{"uri":"magnet:?xt=urn:btih:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}""".toByteArray()) }
            }
            assertEquals(200, addConn.responseCode)
            assertTrue(addedUri.contains("bbbbbbbb"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun torrentManagerScreen_rendersCleanTabsTurboBoostAndOpensMagnetDialog() {
        val app = RuntimeEnvironment.getApplication()
        val vm = MainViewModel(app)
        var vpnOpened = false
        var downloadsOpened = false

        composeRule.setContent {
            MyApplicationTheme(themeMode = "Dark", useDynamicColor = false) {
                TorrentManagerScreen(
                    viewModel = vm,
                    onBack = {},
                    onPlayMedia = {},
                    onOpenVpnManager = { vpnOpened = true },
                    onOpenDownloadManager = { downloadsOpened = true }
                )
            }
        }

        composeRule.onNodeWithText("P2P Torrent Manager").assertIsDisplayed()
        composeRule.onNodeWithTag("torrent_speed_boost_card").assertIsDisplayed()
        composeRule.onNodeWithTag("torrent_tab_rss").performClick()
        composeRule.onNodeWithText("RSS Auto-Downloader").assertIsDisplayed()
        composeRule.onNodeWithTag("torrent_tab_downloads").performScrollTo().performClick()
        composeRule.onNodeWithTag("fab_add_torrent").performClick()
        composeRule.onNodeWithTag("magnet_input_field").assertIsDisplayed()
        assertFalse(vpnOpened)
        assertFalse(downloadsOpened)
    }

    @Test
    fun torrentSpeedBoost_persistsSettingAndUpdatesPipelineDepth() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val db = com.emptysol.aeroplayer.data.database.AppDatabase.getDatabase(app)
        val engine = TorrentEngineManager(
            context = app,
            database = db,
            mediaRepository = com.emptysol.aeroplayer.data.repository.MediaRepository(db.mediaDao()),
            preferenceRepository = com.emptysol.aeroplayer.data.repository.PreferenceRepository(db.preferenceDao()),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
        )

        engine.setSpeedBoostEnabled(true)
        assertTrue(engine.isSpeedBoostEnabled())
        assertTrue(engine.engineStatus.value.speedBoostEnabled)
        assertEquals(64, engine.engineStatus.value.pipelineDepth)

        engine.setSpeedBoostEnabled(false)
        assertFalse(engine.isSpeedBoostEnabled())
        assertFalse(engine.engineStatus.value.speedBoostEnabled)
        assertEquals(32, engine.engineStatus.value.pipelineDepth)

        // Restore default Turbo ON and verify expanded peer discovery lists
        engine.setSpeedBoostEnabled(true)
        val trackerClient = TrackerAndDhtClient()
        assertTrue(trackerClient.publicFallbackTrackers.size >= 6)
        assertTrue(trackerClient.publicBootstrapDhtNodes.size >= 5)
    }

    @Test
    fun torrentEngine_removeTorrentFile_supportsKeepDataAndDeleteData() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val db = com.emptysol.aeroplayer.data.database.AppDatabase.getDatabase(app)
        val engine = TorrentEngineManager(
            context = app,
            database = db,
            mediaRepository = com.emptysol.aeroplayer.data.repository.MediaRepository(db.mediaDao()),
            preferenceRepository = com.emptysol.aeroplayer.data.repository.PreferenceRepository(db.preferenceDao()),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
        )

        val tempRoot = kotlin.io.path.createTempDirectory("aero_remove_file_test").toFile()
        try {
            val staging = File(tempRoot, "cccccccccccccccccccccccccccccccccccccccc").apply { mkdirs() }
            val file0 = File(staging, "movie.mp4").apply { writeText("video-bytes") }
            val file1 = File(staging, "sample.mkv").apply { writeText("sample-bytes") }
            val filesJson = TorrentEngineManager.serializeFilesJson(
                listOf(
                    TorrentFileEntry(0, "movie.mp4", file0.length(), "NORMAL", file0.length()),
                    TorrentFileEntry(1, "sample.mkv", file1.length(), "NORMAL", file1.length())
                )
            )
            val hash = "cccccccccccccccccccccccccccccccccccccccc"
            db.torrentDao().upsertTorrent(
                TorrentTaskEntity(
                    infoHash = hash,
                    title = "MyMoviePack",
                    sourceUri = "magnet:?xt=urn:btih:$hash",
                    saveLocation = tempRoot.absolutePath,
                    status = "PAUSED",
                    filesJson = filesJson,
                    totalBytes = file0.length() + file1.length(),
                    primaryMediaPath = file0.absolutePath
                )
            )

            // 1. Delete file name only -> entry removed from filesJson, file remains on disk
            engine.removeTorrentFile(hash, fileIndex = 1, deleteFromDisk = false)
            val afterKeep = db.torrentDao().getTorrentByHash(hash)!!
            val parsedAfterKeep = TorrentEngineManager.parseFilesJson(afterKeep.filesJson)
            assertEquals(1, parsedAfterKeep.size)
            assertEquals("movie.mp4", parsedAfterKeep.first().path)
            assertTrue("Expected sample.mkv to remain on disk when deleteFromDisk=false", file1.exists())

            // 2. Delete file + data -> entry removed AND file deleted from disk
            engine.removeTorrentFile(hash, fileIndex = 0, deleteFromDisk = true)
            val afterDelete = db.torrentDao().getTorrentByHash(hash)!!
            assertTrue(TorrentEngineManager.parseFilesJson(afterDelete.filesJson).isEmpty())
            assertFalse("Expected movie.mp4 to be deleted from disk when deleteFromDisk=true", file0.exists())
            assertNull(afterDelete.primaryMediaPath)

            // 3. Remove torrent with deleteFiles=true deletes staging dir
            engine.removeTorrent(hash, deleteFiles = true)
            assertNull(db.torrentDao().getTorrentByHash(hash))
            assertFalse(staging.exists())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun torrentCard_longPressOpensBottomSheetWithSingleDeleteAndDataSwitch() {
        val app = RuntimeEnvironment.getApplication()
        val vm = MainViewModel(app)
        val db = com.emptysol.aeroplayer.data.database.AppDatabase.getDatabase(app)
        val hash = "dddddddddddddddddddddddddddddddddddddddd"
        runBlocking {
            db.torrentDao().upsertTorrent(
                TorrentTaskEntity(
                    infoHash = hash,
                    title = "LongPress Sample",
                    sourceUri = "magnet:?xt=urn:btih:$hash",
                    saveLocation = "/tmp/AeroTestFolder",
                    status = "PAUSED",
                    totalBytes = 10_000_000L,
                    downloadedBytes = 2_500_000L
                )
            )
        }

        composeRule.setContent {
            MyApplicationTheme(themeMode = "Dark", useDynamicColor = false) {
                TorrentManagerScreen(
                    viewModel = vm,
                    onBack = {},
                    onPlayMedia = {}
                )
            }
        }

        composeRule.onNodeWithTag("torrent_card_$hash").performScrollTo().performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("torrent_long_press_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("sheet_view_folder_btn").assertIsDisplayed()
        composeRule.onNodeWithTag("sheet_delete_data_switch").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("sheet_confirm_delete_btn").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("sheet_confirm_delete_btn").performClick()
        composeRule.waitForIdle()
        runBlocking {
            assertNull(db.torrentDao().getTorrentByHash(hash))
        }
    }

    @Test
    fun peerWireCoordinator_verifiesLargePieceFromDiskWithoutRamBuffer() {
        val app = RuntimeEnvironment.getApplication()
        val tempDir = kotlin.io.path.createTempDirectory("aero_large_piece_test").toFile()
        try {
            // 1 MB piece (> 512 KB disk-streaming threshold)
            val pieceLen = 1024 * 1024
            val pieceData = ByteArray(pieceLen) { (it * 31 and 0xFF).toByte() }
            val expectedHash = BencodeCodec.sha1(pieceData)
            val infoHashBytes = ByteArray(20) { 0x11 }
            val meta = TorrentMetainfo(
                infoHashHex = BencodeCodec.toHex(infoHashBytes),
                infoHashBytes = infoHashBytes,
                name = "LargePieceMovie",
                pieceLength = pieceLen.toLong(),
                pieceHashes = listOf(expectedHash),
                totalLength = pieceLen.toLong(),
                files = listOf(TorrentFileEntry(0, "movie.mkv", pieceLen.toLong(), "NORMAL", 0L)),
                trackers = emptyList()
            )

            val coordinator = TorrentDownloadCoordinator(
                context = app,
                infoHashHex = meta.infoHashHex,
                infoHashBytes = infoHashBytes,
                peerId = ByteArray(20) { 0x22 },
                initialMetainfo = meta,
                initialTrackers = emptyList(),
                stagingDir = tempDir,
                safTreeUriString = "",
                filePriorities = emptyMap(),
                isSequential = true,
                encryptionMode = "PREFER",
                dhtEnabled = false,
                pexEnabled = false,
                seedOnlyMode = false,
                globalDlThrottler = BandwidthThrottler(0),
                globalUlThrottler = BandwidthThrottler(0),
                taskDlThrottler = BandwidthThrottler(0),
                taskUlThrottler = BandwidthThrottler(0),
                ipFilterManager = IpFilterManager(),
                trackerClient = TrackerAndDhtClient(),
                speedBoostEnabled = true,
                onProgressUpdate = { _, _, _, _, _, _, _, _, _, _ -> }
            )

            // Stream 16 KB blocks directly to disk and verify SHA-1 from disk
            var offset = 0
            while (offset < pieceLen) {
                val chunk = minOf(16384, pieceLen - offset)
                coordinator.writeBytesRangeToDiskPooled(meta, offset.toLong(), pieceData, offset, chunk)
                offset += chunk
            }

            assertTrue(coordinator.verifyPieceHashFromDisk(meta, pieceIdx = 0, pieceSize = pieceLen))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun torrentDownloadService_handlesPauseResumeStopActionsCleanly() {
        val controller = org.robolectric.Robolectric.buildService(com.emptysol.aeroplayer.service.TorrentDownloadService::class.java).create()
        val service = controller.get()
        assertNotNull(service)
        service.onStartCommand(android.content.Intent(RuntimeEnvironment.getApplication(), com.emptysol.aeroplayer.service.TorrentDownloadService::class.java).setAction(com.emptysol.aeroplayer.service.TorrentDownloadService.ACTION_PAUSE_ALL), 0, 1)
        service.onStartCommand(android.content.Intent(RuntimeEnvironment.getApplication(), com.emptysol.aeroplayer.service.TorrentDownloadService::class.java).setAction(com.emptysol.aeroplayer.service.TorrentDownloadService.ACTION_RESUME_ALL), 0, 2)
        service.onStartCommand(android.content.Intent(RuntimeEnvironment.getApplication(), com.emptysol.aeroplayer.service.TorrentDownloadService::class.java).setAction(com.emptysol.aeroplayer.service.TorrentDownloadService.ACTION_STOP), 0, 3)
        controller.destroy()
    }
}
