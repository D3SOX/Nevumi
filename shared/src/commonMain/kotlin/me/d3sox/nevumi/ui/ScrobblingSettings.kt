package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.history.ScrobblingService
import me.d3sox.nevumi.history.ScrobbleState
import me.d3sox.nevumi.history.ScrobbleSubmission
import me.d3sox.nevumi.history.ScrobblingProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal fun ScrobblingService.label() = when (this) {
    ScrobblingService.LISTENBRAINZ -> "ListenBrainz"
    ScrobblingService.LASTFM -> "Last.fm"
    ScrobblingService.NAVIDROME -> "Music server"
}

@Composable
internal fun ScrobblingSettingsContent(state: AppState, controller: MusicController, connect: (ScrobblingService) -> Unit) {
    var signingIn by remember { mutableStateOf(false) }
    var signInError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val browser = LocalUriHandler.current
    var retry by remember { mutableStateOf<ScrobbleSubmission?>(null) }
    var disconnect by remember { mutableStateOf<ScrobblingProfile?>(null) }

    val pending = state.scrobbling.submissions.filter { it.listen != null }
        .sortedByDescending { it.listen?.startedAt }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Scrobbling sends recorded plays to your connected accounts. Existing history is not backfilled.")
            SettingsSlider("Scrobble after", AppIcon.SCROBBLING, state.preferences.scrobblePercent.toFloat(),
                1f..100f, 98, format = { "${it.roundToInt()}%" }) { percent ->
                controller.updatePreferences { it.copy(scrobblePercent = percent.roundToInt()) }
            }
            SettingsSlider("Or after", AppIcon.HISTORY, state.preferences.scrobbleMaxSeconds.toFloat(),
                0f..600f, 19, format = { if (it == 0f) "Off" else playbackTime(it.roundToInt() * 1_000L) }) { seconds ->
                controller.updatePreferences { it.copy(scrobbleMaxSeconds = seconds.roundToInt()) }
            }
            Text("Send a listen when either threshold is reached. Choose Off to use only the percentage. Applies to all connected services. Last.fm requires tracks longer than 30 seconds.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.scrobbling.profiles.any { it.service == ScrobblingService.NAVIDROME && it.enabled })
                Text("If your music server forwards plays to Last.fm or ListenBrainz, keep the matching direct connection off here.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.scrobbling.profiles.forEach { profile ->
                Text(profile.service.label(), style = MaterialTheme.typography.titleMedium)
                Text("Connected as ${profile.username}")
                SettingChoice("Send listens to ${profile.service.label()}", profile.enabled, listOf(true to "On", false to "Off")) {
                    controller.setScrobblingEnabled(profile.id, it)
                }
                if (profile.service != ScrobblingService.NAVIDROME)
                    TextButton(onClick = { disconnect = profile }) { Text("Disconnect ${profile.service.label()}") }
            }
            for (service in listOf(ScrobblingService.LISTENBRAINZ, ScrobblingService.LASTFM)) {
                FilledTonalButton(onClick = { connect(service) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(if (service == ScrobblingService.LISTENBRAINZ) ServiceBrandIcons.listenBrainz else ServiceBrandIcons.lastFm,
                        null, Modifier.size(24.dp), tint = Color.Unspecified)
                    Spacer(Modifier.width(12.dp))
                    Text("Connect ${service.label()}")
                }
            }
            state.lastFmAuthorization?.let { authorization ->
                Text("Authorize Nevumi in your browser, then return here to complete sign-in.")
                TextButton(onClick = { try { browser.openUri(authorization.url) } catch (_: Exception) { signInError = "Could not open the authorization page in your browser." } }) {
                    Text("Open Last.fm authorization")
                }
                TextButton(enabled = !signingIn, onClick = {
                    signingIn = true; signInError = null
                    scope.launch {
                        try { controller.completeLastFmSignIn() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { signInError = failure.message ?: "Could not complete Last.fm sign-in" }
                        finally { signingIn = false }
                    }
                }) { Text("Complete sign-in") }
                TextButton(enabled = !signingIn, onClick = { scope.launch { controller.cancelLastFmSignIn(); signInError = null } }) { Text("Cancel sign-in") }
            }
            signInError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (pending.isEmpty()) Text("No pending listens")
            else {
                Text("Pending listens", style = MaterialTheme.typography.titleMedium)
                pending.forEach { entry ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(requireNotNull(entry.listen).metadata.title, style = MaterialTheme.typography.titleSmall)
                        Text("${state.scrobbling.profiles.firstOrNull { it.id == entry.profileId }?.service?.label().orEmpty()} · ${formatListenTime(entry.listen.startedAt)}")
                        Text(when (entry.state) {
                            ScrobbleState.UNCERTAIN -> "Unconfirmed submission"
                            ScrobbleState.AUTHORIZATION -> "Sign-in required"
                            ScrobbleState.REJECTED -> "Service rejected this listen"
                            ScrobbleState.IN_FLIGHT -> "Sending listen"
                            else -> "Waiting to send"
                        })
                        entry.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (entry.state != ScrobbleState.IN_FLIGHT) Row {
                            TextButton(onClick = {
                                if (entry.state == ScrobbleState.UNCERTAIN) retry = entry else controller.retryScrobbleSubmission(entry.id)
                            }) { Text("Retry") }
                            TextButton(onClick = { controller.discardScrobbleSubmission(entry.id) }) { Text("Discard listen") }
                        }
                    }
                }
            }
            state.scrobbling.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    disconnect?.let { profile -> AlertDialog(onDismissRequest = { disconnect = null },
        title = { Text("Disconnect ${profile.service.label()}?") },
        text = { Text("This removes the saved credentials and discards pending deliveries for ${profile.username}.") },
        confirmButton = { TextButton(onClick = { controller.disconnectScrobbling(profile.id); disconnect = null }) { Text("Disconnect") } },
        dismissButton = { TextButton(onClick = { disconnect = null }) { Text("Cancel") } }) }
    retry?.let { entry -> AlertDialog(onDismissRequest = { retry = null }, title = { Text("Retry unconfirmed listen?") },
        text = { Text("The service may already have recorded this listen. Retrying can submit it a second time.") },
        confirmButton = { TextButton(onClick = { controller.retryScrobbleSubmission(entry.id); retry = null }) { Text("Retry listen") } },
        dismissButton = { TextButton(onClick = { retry = null }) { Text("Cancel") } }) }
}

@Composable
internal fun ConnectLastFm(controller: MusicController, dismiss: () -> Unit) {
    var apiKey by remember { mutableStateOf("") }
    var sharedSecret by remember { mutableStateOf("") }
    var connecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val browser = LocalUriHandler.current
    SettingsScreen(onDismissRequest = { if (!connecting) dismiss() }, title = { Text("Connect Last.fm") }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Enter the API credentials for your Last.fm application, then authorize Nevumi in your browser.")
            Text("Leave the Callback URL field blank when creating your Last.fm API application.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { browser.openUri("https://www.last.fm/api/account/create") }) { Text("Get API credentials") }
            OutlinedTextField(apiKey, { apiKey = it }, modifier = Modifier.fillMaxWidth(), enabled = !connecting, label = { Text("Application API key") }, leadingIcon = { Icon(AppIcon.KEY.vector, null) }, singleLine = true)
            OutlinedTextField(sharedSecret, { sharedSecret = it }, modifier = Modifier.fillMaxWidth(), enabled = !connecting, label = { Text("Application shared secret") }, leadingIcon = { Icon(AppIcon.LOCK.vector, null) },
                visualTransformation = PasswordVisualTransformation(), singleLine = true)
            if (connecting) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { PageAction("Start sign-in", AppIcon.KEY, enabled = apiKey.isNotBlank() && sharedSecret.isNotBlank() && !connecting, onClick = {
        connecting = true; error = null
        scope.launch {
            try {
                val authorization = controller.startLastFmSignIn(apiKey, sharedSecret)
                try { browser.openUri(authorization.url) } catch (_: Exception) { /* The pending authorization can be opened from Settings. */ }
                dismiss()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Could not start Last.fm sign-in" }
            finally { connecting = false }
        }
    }) }, dismissButton = { PageAction("Cancel", AppIcon.CLOSE, enabled = !connecting, secondary = true, onClick = dismiss) })
}

@Composable
internal fun ConnectListenBrainz(controller: MusicController, dismiss: () -> Unit) {
    var token by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("https://api.listenbrainz.org") }
    var connecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    SettingsScreen(onDismissRequest = { if (!connecting) dismiss() }, title = { Text("Connect ListenBrainz") }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Use the user token from your ListenBrainz account settings.")
            OutlinedTextField(token, { token = it }, modifier = Modifier.fillMaxWidth(), enabled = !connecting, label = { Text("User token") }, leadingIcon = { Icon(AppIcon.KEY.vector, null) },
                visualTransformation = PasswordVisualTransformation(), singleLine = true)
            OutlinedTextField(address, { address = it }, modifier = Modifier.fillMaxWidth(), enabled = !connecting, label = { Text("Service address") }, leadingIcon = { Icon(AppIcon.CLOUD.vector, null) }, singleLine = true)
            if (connecting) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { PageAction("Connect", AppIcon.LINK, enabled = token.isNotBlank() && !connecting, onClick = {
        connecting = true; error = null
        scope.launch {
            try { controller.connectListenBrainz(token, address); dismiss() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Could not connect to ListenBrainz" }
            finally { connecting = false }
        }
    }) }, dismissButton = { PageAction("Cancel", AppIcon.CLOSE, enabled = !connecting, secondary = true, onClick = dismiss) })
}
