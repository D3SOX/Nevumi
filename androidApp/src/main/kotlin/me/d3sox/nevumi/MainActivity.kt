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
import me.d3sox.nevumi.ui.MusicPlayerApp

class MainActivity : ComponentActivity() {
    private var launchedWriteRequest: String? = null
    private var launchedNetworkRequest: String? = null
    private val audioPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
    /** A song another app opened while Nevumi asks for the audio permission it needs to find the song. */
    private var songAwaitingPermission: String? = null
    private lateinit var openSongPermission: ActivityResultLauncher<String>

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
        // A recreated activity, or one reopened from recents, already handled its song.
        if (savedInstanceState == null) openSong(intent)
        setContent {
            MusicPlayerApp(controller, onAddMusic = {
                if (checkSelfPermission(audioPermission) == PackageManager.PERMISSION_GRANTED) controller.importSource("device")
                else request.launch(audioPermission)
            })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openSong(intent)
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
        super.onSaveInstanceState(outState)
    }
}
