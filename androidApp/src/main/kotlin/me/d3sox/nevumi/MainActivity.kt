package me.d3sox.nevumi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import android.Manifest
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import me.d3sox.nevumi.app.resumeQueue
import me.d3sox.nevumi.app.shuffleLibrary
import me.d3sox.nevumi.ui.AppShortcuts
import me.d3sox.nevumi.ui.MusicPlayerApp

class MainActivity : ComponentActivity() {
    private var launchedWriteRequest: String? = null
    private var launchedNetworkRequest: String? = null
    private val audioPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
    /** A song another app opened while Nevumi asks for the audio permission it needs to find the song. */
    private var songAwaitingPermission: String? = null
    private lateinit var openSongPermission: ActivityResultLauncher<String>
    private lateinit var shortcuts: AppShortcuts
    /** A launcher shortcut still waiting for startup. */
    private var pendingShortcut: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as MusicApplication
        launchedNetworkRequest = savedInstanceState?.getString("launched-network-request")
        val localNetwork = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            launchedNetworkRequest = null
            app.localNetworkRequests.complete(granted)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.localNetworkRequests.pending.collect { request ->
                    if (Build.VERSION.SDK_INT >= 37 && request != null && launchedNetworkRequest != request.id) {
                        launchedNetworkRequest = request.id
                        localNetwork.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                    }
                }
            }
        }
        launchedWriteRequest = savedInstanceState?.getString("launched-write-request")
        val editPermission = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            launchedWriteRequest = null
            app.mediaWriteRequests.complete(result.resultCode == RESULT_OK)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.mediaWriteRequests.pending.collect { request ->
                    if (request != null && launchedWriteRequest != request.id) {
                        launchedWriteRequest = request.id
                        editPermission.launch(IntentSenderRequest.Builder(request.sender).build())
                    }
                }
            }
        }
        val controller = app.controller
        val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) app.retryUserDownloads()
        }
        val notificationPermission = getSharedPreferences("notification-permission", MODE_PRIVATE)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.downloadNotificationsRequested.collect {
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                        !notificationPermission.getBoolean("requested", false)) {
                        notificationPermission.edit().putBoolean("requested", true).apply()
                        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
        }
        // Turning on release notifications asks again, as it is a direct request for them.
        val releaseNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.releasesController.notificationRequests.collect {
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                        releaseNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
        val request = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) controller.importSource("device")
        }
        // A refusal still tries, so the app explains why it cannot open the song.
        songAwaitingPermission = savedInstanceState?.getString("song-awaiting-permission")
        openSongPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            songAwaitingPermission?.let(controller::openSong)
            songAwaitingPermission = null
        }
        shortcuts = AppShortcuts(controller)
        publishLauncherShortcuts()
        // A recreated activity, or one reopened from recents, already handled its song or shortcut.
        if (savedInstanceState == null) { openSong(intent); openShortcut(intent) }
        else savedInstanceState.getString("pending-shortcut")?.let(::runShortcut)
        setContent {
            MusicPlayerApp(controller, shortcuts = shortcuts, crashReports = app.crashLog, onAddMusic = {
                if (checkSelfPermission(audioPermission) == PackageManager.PERMISSION_GRANTED) controller.importSource("device")
                else request.launch(audioPermission)
            })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openSong(intent)
        openShortcut(intent)
    }

    /** Offers Shuffle, Search and Resume when the launcher icon is held. Published at runtime so each build's application ID works. */
    private fun publishLauncherShortcuts() {
        fun shortcut(id: String, label: Int, longLabel: Int, icon: Int) = ShortcutInfo.Builder(this, id)
            .setShortLabel(getString(label)).setLongLabel(getString(longLabel)).setIcon(Icon.createWithResource(this, icon))
            .setIntent(Intent(this, MainActivity::class.java).setAction(id)).build()
        getSystemService(ShortcutManager::class.java)?.dynamicShortcuts = listOf(
            shortcut(SHUFFLE_SHORTCUT, R.string.shortcut_shuffle, R.string.shortcut_shuffle_long, R.drawable.ic_shortcut_shuffle),
            shortcut(SEARCH_SHORTCUT, R.string.shortcut_search, R.string.shortcut_search_long, R.drawable.ic_shortcut_search),
            shortcut(RESUME_SHORTCUT, R.string.shortcut_resume, R.string.shortcut_resume_long, R.drawable.ic_shortcut_resume))
    }

    private fun openShortcut(intent: Intent) {
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val action = intent.action?.takeIf { it in setOf(SHUFFLE_SHORTCUT, SEARCH_SHORTCUT, RESUME_SHORTCUT) } ?: return
        getSystemService(ShortcutManager::class.java)?.reportShortcutUsed(action)
        runShortcut(action)
    }

    /** Runs [action] once startup allows it; until then it is kept, so a recreated activity, such as after rotating, still runs it. */
    private fun runShortcut(action: String) {
        pendingShortcut = action
        val controller = (application as MusicApplication).controller
        lifecycleScope.launch {
            when (action) {
                SHUFFLE_SHORTCUT -> controller.shuffleLibrary()
                SEARCH_SHORTCUT -> shortcuts.openSearch()
                RESUME_SHORTCUT -> controller.resumeQueue()
            }
            pendingShortcut = null
        }
    }

    /** Plays a song another app opened in Nevumi. */
    private fun openSong(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val uri = intent.data?.toString() ?: return
        if (checkSelfPermission(audioPermission) == PackageManager.PERMISSION_GRANTED) (application as MusicApplication).controller.openSong(uri)
        else {
            songAwaitingPermission = uri
            openSongPermission.launch(audioPermission)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("launched-write-request", launchedWriteRequest)
        outState.putString("launched-network-request", launchedNetworkRequest)
        outState.putString("song-awaiting-permission", songAwaitingPermission)
        outState.putString("pending-shortcut", pendingShortcut)
        super.onSaveInstanceState(outState)
    }

    private companion object {
        const val SHUFFLE_SHORTCUT = "me.d3sox.nevumi.shortcut.SHUFFLE"
        const val SEARCH_SHORTCUT = "me.d3sox.nevumi.shortcut.SEARCH"
        const val RESUME_SHORTCUT = "me.d3sox.nevumi.shortcut.RESUME"
    }
}
