package com.emptysol.aeroplayer

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.emptysol.aeroplayer.torrent.RemoteAccessServer
import com.emptysol.aeroplayer.torrent.VpnAndCyberGhostHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

@RunWith(RobolectricTestRunner::class)
class MainActivityStartupTest {

    @Test
    @Config(sdk = [26])
    fun mainActivity_coldLaunchesCleanlyOnApi26() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val crashPrefs = context.getSharedPreferences("aero_crash_prefs", Context.MODE_PRIVATE)
        crashPrefs.edit().clear().commit()

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertNotNull(activity)
        assertFalse(activity.isFinishing)
        assertNull(crashPrefs.getString("last_crash_log", null))

        val vpn = VpnAndCyberGhostHelper.inspectStatus(context)
        assertNotNull(vpn.transportSummary)
        controller.pause().stop().destroy()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
    }

    @Test
    @Config(sdk = [31])
    fun mainActivity_coldLaunchesMainScreenWhenOnboardedOnApi31() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val crashPrefs = context.getSharedPreferences("aero_crash_prefs", Context.MODE_PRIVATE)
        crashPrefs.edit().clear().commit()
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("onboarding_completed", true).commit()

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertNotNull(activity)
        assertFalse(activity.isFinishing)
        assertNull(crashPrefs.getString("last_crash_log", null))
        controller.pause().stop().destroy()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
    }

    @Test
    @Config(sdk = [36])
    fun mainActivity_handlesMagnetIntentAndColdLaunchOnApi36() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val crashPrefs = context.getSharedPreferences("aero_crash_prefs", Context.MODE_PRIVATE)
        crashPrefs.edit().clear().commit()
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("onboarding_completed", true).commit()

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Sample"))
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        val activity = controller.get()
        assertNotNull(activity)
        assertFalse(activity.isFinishing)
        assertNull(crashPrefs.getString("last_crash_log", null))

        val vpn = VpnAndCyberGhostHelper.inspectStatus(context)
        assertNotNull(vpn.transportSummary)
        MainActivity.requestedScreenState.value = null
        MainActivity.requestedTorrentTabState.value = "DOWNLOADS"
        controller.pause().stop().destroy()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
    }

    @Test
    @Config(sdk = [33])
    fun remoteAccessServer_survivesIdleClientSocketTimeoutWithoutCrashing() = runBlocking {
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        var uncaught: Throwable? = null
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            uncaught = throwable
        }

        val freePort = ServerSocket(0).use { it.localPort }
        val server = RemoteAccessServer(
            getTasksSnapshot = { emptyList() },
            onAddMagnetOrUrl = {},
            onPauseTorrent = {},
            onResumeTorrent = {},
            onRemoveTorrent = {},
            onStateChanged = {}
        )

        try {
            server.start(freePort, "")
            delay(200)

            // Open raw TCP connection and send nothing for >5s to trigger s.soTimeout (5000ms)
            val silentSocket = Socket("127.0.0.1", freePort)
            delay(5600)
            silentSocket.close()

            // Verify server is still healthy and no uncaught SocketTimeoutException escaped
            val conn = URL("http://127.0.0.1:$freePort/api/status").openConnection() as HttpURLConnection
            assertEquals(200, conn.responseCode)
            val body = conn.inputStream.bufferedReader().readText()
            assertTrue(body.contains("torrents"))
            assertNull("Uncaught exception leaked: $uncaught", uncaught)
        } finally {
            server.stop()
            Thread.setDefaultUncaughtExceptionHandler(prevHandler)
        }
    }
}
