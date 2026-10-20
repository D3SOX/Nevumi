package me.d3sox.nevumi

import android.content.Intent
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import org.junit.Assert.assertNotNull
import me.d3sox.nevumi.sftp.SftpProfile
import me.d3sox.nevumi.sftp.SftpAuthentication
import java.io.File
import java.util.Properties
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 37)
class AndroidLocalNetworkUiTest {
    private suspend fun withActivity(block: suspend () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        // API 37's emulator mapper crashes while saving task snapshots. The
        // permission dialog remains visible; only this test activity is private.
        instrumentation.runOnMainSync { activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        try { block() } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test
    fun connectingSftpRequestsNearbyAccessBeforeAuthenticating() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val properties = Properties().apply { File("/data/local/tmp/nevumi-sftp-fixture.properties").inputStream().use { load(it) } }
        val profile = SftpProfile("10.0.2.2", 14534, properties.getProperty("username"),
            properties.getProperty("editableMusicDirectory"), properties.getProperty("fingerprint"))
        val authentication = SftpAuthentication(privateKey = File("/data/local/tmp/nevumi-sftp-client").readText())
        withActivity {
            app.controller.connectServer("http://127.0.0.1:14536", "fixture", "fixture-password").join()
            assertNull(app.controller.state.value.error)
            val connected = async { app.controller.connectServerFiles(profile, 1, authentication) }
            try {
                withTimeout(5_000) { instrumentation.uiAutomation.clickSystemDialogButton { it.viewIdResourceName?.endsWith(":id/permission_allow_button") == true } }
                withTimeout(10_000) { connected.await() }
                assertNotNull(app.controller.state.value.sftp)
            } finally { connected.cancel() }
        }
        Unit
    }

    @Test
    fun creatingAPairingInvitationRequestsNearbyAccessBeforeOpeningItsListener() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val devices = requireNotNull(app.controller.pairedDevices)
        withActivity {
            withTimeout(5_000) { while (app.controller.state.value.loading) delay(50) }
            val invitation = async { devices.createInvitation("10.0.2.15") }
            try {
                withTimeout(5_000) {
                    instrumentation.uiAutomation.clickSystemDialogButton {
                        (it.viewIdResourceName?.endsWith(":id/permission_allow_button") == true)
                            .also { allow -> if (allow) assertNull(devices.state.value.listeningPort) }
                    }
                }
                assertNotNull(withTimeout(10_000) { invitation.await() })
                assertNotNull(devices.state.value.listeningPort)
            } finally {
                invitation.cancel()
                devices.cancelInvitation()
            }
        }
        Unit
    }

    @Test
    fun connectingToALocalNavidromeServerRequestsNearbyAccessBeforeLoadingMusic() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        withActivity {
            withTimeout(5_000) { while (app.controller.state.value.loading) delay(50) }
            app.controller.connectServer("http://10.0.2.2:14533", "fixture", "fixture-password")
            withTimeout(5_000) { instrumentation.uiAutomation.clickSystemDialogButton { it.viewIdResourceName?.endsWith(":id/permission_allow_button") == true } }
            withTimeout(10_000) { while (app.controller.state.value.tracks.size != 3) delay(50) }
            assertNull(app.controller.state.value.error)
        }
        Unit
    }
}
