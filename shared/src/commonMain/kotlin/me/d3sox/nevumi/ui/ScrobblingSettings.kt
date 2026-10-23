package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.cancel
import me.d3sox.nevumi.resources.connect_last_fm
import me.d3sox.nevumi.resources.connect_listenbrainz
import me.d3sox.nevumi.resources.music_server
import me.d3sox.nevumi.resources.off
import me.d3sox.nevumi.resources.on
import me.d3sox.nevumi.resources.retry
import me.d3sox.nevumi.resources.sources_application_api_key
import me.d3sox.nevumi.resources.sources_application_shared_secret
import me.d3sox.nevumi.resources.sources_cancel_sign_in
import me.d3sox.nevumi.resources.sources_complete_lastfm_sign_in_failed
import me.d3sox.nevumi.resources.sources_complete_sign_in
import me.d3sox.nevumi.resources.sources_connect
import me.d3sox.nevumi.resources.sources_connect_listenbrainz_failed
import me.d3sox.nevumi.resources.sources_connected_as
import me.d3sox.nevumi.resources.sources_discard_listen
import me.d3sox.nevumi.resources.sources_disconnect
import me.d3sox.nevumi.resources.sources_disconnect_scrobbling_description
import me.d3sox.nevumi.resources.sources_disconnect_service
import me.d3sox.nevumi.resources.sources_disconnect_service_question
import me.d3sox.nevumi.resources.sources_get_api_credentials
import me.d3sox.nevumi.resources.sources_lastfm_authorize_hint
import me.d3sox.nevumi.resources.sources_lastfm_callback_hint
import me.d3sox.nevumi.resources.sources_lastfm_credentials_intro
import me.d3sox.nevumi.resources.sources_listenbrainz_token_intro
import me.d3sox.nevumi.resources.sources_no_pending_listens
import me.d3sox.nevumi.resources.sources_open_authorization_failed
import me.d3sox.nevumi.resources.sources_open_lastfm_authorization
import me.d3sox.nevumi.resources.sources_pending_listens
import me.d3sox.nevumi.resources.sources_retry_listen
import me.d3sox.nevumi.resources.sources_retry_unconfirmed_listen_description
import me.d3sox.nevumi.resources.sources_retry_unconfirmed_listen_question
import me.d3sox.nevumi.resources.sources_scrobble_after
import me.d3sox.nevumi.resources.sources_scrobble_or_after
import me.d3sox.nevumi.resources.sources_scrobble_server_forward_hint
import me.d3sox.nevumi.resources.sources_scrobble_state_authorization
import me.d3sox.nevumi.resources.sources_scrobble_state_in_flight
import me.d3sox.nevumi.resources.sources_scrobble_state_rejected
import me.d3sox.nevumi.resources.sources_scrobble_state_uncertain
import me.d3sox.nevumi.resources.sources_scrobble_state_waiting
import me.d3sox.nevumi.resources.sources_scrobble_threshold_hint
import me.d3sox.nevumi.resources.sources_scrobbling_intro
import me.d3sox.nevumi.resources.sources_send_listens_to
import me.d3sox.nevumi.resources.sources_service_address
import me.d3sox.nevumi.resources.sources_start_lastfm_sign_in_failed
import me.d3sox.nevumi.resources.sources_start_sign_in
import me.d3sox.nevumi.resources.sources_user_token
import org.jetbrains.compose.resources.stringResource

internal fun ScrobblingService.label() = when (this) {
    ScrobblingService.LISTENBRAINZ -> "ListenBrainz"
    ScrobblingService.LASTFM -> "Last.fm"
    ScrobblingService.NAVIDROME -> "Music server"
}

private fun ScrobblingService.logo() = when (this) {
    ScrobblingService.LISTENBRAINZ -> ServiceBrandIcons.listenBrainz
    ScrobblingService.LASTFM -> ServiceBrandIcons.lastFm
    ScrobblingService.NAVIDROME -> null
}

/** Display name of [ScrobblingService]; [label] stays the untranslated navigation key. */
@Composable
internal fun ScrobblingService.displayName() =
    if (this == ScrobblingService.NAVIDROME) stringResource(Res.string.music_server) else label()

@Composable
internal fun ScrobblingSettingsContent(state: AppState, controller: MusicController, connect: (ScrobblingService) -> Unit) {
    var signingIn by remember { mutableStateOf(false) }
    var signInError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val browser = LocalUriHandler.current
    var retry by remember { mutableStateOf<ScrobbleSubmission?>(null) }
    var disconnect by remember { mutableStateOf<ScrobblingProfile?>(null) }

    val off = stringResource(Res.string.off)
    val openAuthorizationFailed = stringResource(Res.string.sources_open_authorization_failed)
    val completeSignInFailed = stringResource(Res.string.sources_complete_lastfm_sign_in_failed)
    val pending = state.scrobbling.submissions.filter { it.listen != null }
        .sortedByDescending { it.listen?.startedAt }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.sources_scrobbling_intro))
            SettingsSlider(stringResource(Res.string.sources_scrobble_after), AppIcon.SCROBBLING, state.preferences.scrobblePercent.toFloat(),
                1f..100f, 98, format = { "${it.roundToInt()}%" }) { percent ->
                controller.updatePreferences { it.copy(scrobblePercent = percent.roundToInt()) }
            }
            SettingsSlider(stringResource(Res.string.sources_scrobble_or_after), AppIcon.HISTORY, state.preferences.scrobbleMaxSeconds.toFloat(),
                0f..600f, 19, format = { if (it == 0f) off else playbackTime(it.roundToInt() * 1_000L) }) { seconds ->
                controller.updatePreferences { it.copy(scrobbleMaxSeconds = seconds.roundToInt()) }
            }
            Text(stringResource(Res.string.sources_scrobble_threshold_hint),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.scrobbling.profiles.any { it.service == ScrobblingService.NAVIDROME && it.enabled })
                Text(stringResource(Res.string.sources_scrobble_server_forward_hint),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.scrobbling.profiles.forEach { profile ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val logo = profile.service.logo()
                    if (logo != null) Icon(logo, null, Modifier.size(24.dp), tint = Color.Unspecified)
                    else Icon(AppIcon.SERVER.vector, null, Modifier.size(24.dp))
                    Text(profile.service.displayName(), style = MaterialTheme.typography.titleMedium)
                }
                Text(stringResource(Res.string.sources_connected_as, profile.username))
                SettingChoice(stringResource(Res.string.sources_send_listens_to, profile.service.displayName()), profile.enabled, listOf(true to stringResource(Res.string.on), false to off)) {
                    controller.setScrobblingEnabled(profile.id, it)
                }
                if (profile.service != ScrobblingService.NAVIDROME)
                    ActionButton(stringResource(Res.string.sources_disconnect_service, profile.service.displayName()), AppIcon.UNLINK, style = ActionStyle.DESTRUCTIVE) { disconnect = profile }
            }
            for (service in listOf(ScrobblingService.LISTENBRAINZ, ScrobblingService.LASTFM)) {
                FilledTonalButton(onClick = { connect(service) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(requireNotNull(service.logo()), null, Modifier.size(24.dp), tint = Color.Unspecified)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(if (service == ScrobblingService.LASTFM) Res.string.connect_last_fm else Res.string.connect_listenbrainz))
                }
            }
            state.lastFmAuthorization?.let { authorization ->
                Text(stringResource(Res.string.sources_lastfm_authorize_hint))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(stringResource(Res.string.sources_open_lastfm_authorization), AppIcon.OPEN_EXTERNAL, style = ActionStyle.OUTLINED) {
                        try { browser.openUri(authorization.url) } catch (_: Exception) { signInError = openAuthorizationFailed }
                    }
                    ActionButton(stringResource(Res.string.sources_complete_sign_in), AppIcon.CHECK, enabled = !signingIn) {
                        signingIn = true; signInError = null
                        scope.launch {
                            try { controller.completeLastFmSignIn() }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { signInError = failure.message ?: completeSignInFailed }
                            finally { signingIn = false }
                        }
                    }
                    ActionButton(stringResource(Res.string.sources_cancel_sign_in), AppIcon.CLOSE, enabled = !signingIn, style = ActionStyle.OUTLINED) {
                        scope.launch { controller.cancelLastFmSignIn(); signInError = null }
                    }
                }
            }
            signInError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (pending.isEmpty()) Text(stringResource(Res.string.sources_no_pending_listens))
            else {
                Text(stringResource(Res.string.sources_pending_listens), style = MaterialTheme.typography.titleMedium)
                pending.forEach { entry ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(requireNotNull(entry.listen).metadata.title, style = MaterialTheme.typography.titleSmall)
                        Text("${state.scrobbling.profiles.firstOrNull { it.id == entry.profileId }?.service?.displayName().orEmpty()} · ${formatListenTime(entry.listen.startedAt)}")
                        Text(when (entry.state) {
                            ScrobbleState.UNCERTAIN -> stringResource(Res.string.sources_scrobble_state_uncertain)
                            ScrobbleState.AUTHORIZATION -> stringResource(Res.string.sources_scrobble_state_authorization)
                            ScrobbleState.REJECTED -> stringResource(Res.string.sources_scrobble_state_rejected)
                            ScrobbleState.IN_FLIGHT -> stringResource(Res.string.sources_scrobble_state_in_flight)
                            else -> stringResource(Res.string.sources_scrobble_state_waiting)
                        })
                        entry.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (entry.state != ScrobbleState.IN_FLIGHT) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ActionButton(stringResource(Res.string.retry), AppIcon.REFRESH) {
                                if (entry.state == ScrobbleState.UNCERTAIN) retry = entry else controller.retryScrobbleSubmission(entry.id)
                            }
                            ActionButton(stringResource(Res.string.sources_discard_listen), AppIcon.REMOVE, style = ActionStyle.DESTRUCTIVE) { controller.discardScrobbleSubmission(entry.id) }
                        }
                    }
                }
            }
            state.scrobbling.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    disconnect?.let { profile -> AlertDialog(onDismissRequest = { disconnect = null },
        title = { Text(stringResource(Res.string.sources_disconnect_service_question, profile.service.displayName())) },
        text = { Text(stringResource(Res.string.sources_disconnect_scrobbling_description, profile.username)) },
        confirmButton = { TextButton(onClick = { controller.disconnectScrobbling(profile.id); disconnect = null }) { Text(stringResource(Res.string.sources_disconnect)) } },
        dismissButton = { TextButton(onClick = { disconnect = null }) { Text(stringResource(Res.string.cancel)) } }) }
    retry?.let { entry -> AlertDialog(onDismissRequest = { retry = null }, title = { Text(stringResource(Res.string.sources_retry_unconfirmed_listen_question)) },
        text = { Text(stringResource(Res.string.sources_retry_unconfirmed_listen_description)) },
        confirmButton = { TextButton(onClick = { controller.retryScrobbleSubmission(entry.id); retry = null }) { Text(stringResource(Res.string.sources_retry_listen)) } },
        dismissButton = { TextButton(onClick = { retry = null }) { Text(stringResource(Res.string.cancel)) } }) }
}

@Composable
internal fun ConnectLastFm(controller: MusicController, dismiss: () -> Unit) {
    var apiKey by remember { mutableStateOf("") }
    var sharedSecret by remember { mutableStateOf("") }
    var connecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val browser = LocalUriHandler.current
    val startSignInFailed = stringResource(Res.string.sources_start_lastfm_sign_in_failed)
    SettingsScreen(onDismissRequest = { if (!connecting) dismiss() }, title = { Text(stringResource(Res.string.connect_last_fm)) }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.sources_lastfm_credentials_intro))
            Text(stringResource(Res.string.sources_lastfm_callback_hint),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ActionButton(stringResource(Res.string.sources_get_api_credentials), AppIcon.OPEN_EXTERNAL, style = ActionStyle.OUTLINED) { browser.openUri("https://www.last.fm/api/account/create") }
            OutlinedTextField(apiKey, { apiKey = it }, modifier = Modifier.fillMaxWidth(), enabled = !connecting, label = { Text(stringResource(Res.string.sources_application_api_key)) }, leadingIcon = { Icon(AppIcon.KEY.vector, null) }, singleLine = true)
            OutlinedTextField(sharedSecret, { sharedSecret = it }, modifier = Modifier.fillMaxWidth(), enabled = !connecting, label = { Text(stringResource(Res.string.sources_application_shared_secret)) }, leadingIcon = { Icon(AppIcon.LOCK.vector, null) },
                visualTransformation = PasswordVisualTransformation(), singleLine = true)
            if (connecting) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { PageAction(stringResource(Res.string.sources_start_sign_in), AppIcon.KEY, enabled = apiKey.isNotBlank() && sharedSecret.isNotBlank() && !connecting, onClick = {
        connecting = true; error = null
        scope.launch {
            try {
                val authorization = controller.startLastFmSignIn(apiKey, sharedSecret)
                try { browser.openUri(authorization.url) } catch (_: Exception) { /* The pending authorization can be opened from Settings. */ }
                dismiss()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: startSignInFailed }
            finally { connecting = false }
        }
    }) }, dismissButton = { PageAction(stringResource(Res.string.cancel), AppIcon.CLOSE, enabled = !connecting, secondary = true, onClick = dismiss) })
}

@Composable
internal fun ConnectListenBrainz(controller: MusicController, dismiss: () -> Unit) {
    var token by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("https://api.listenbrainz.org") }
    var connecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val connectFailed = stringResource(Res.string.sources_connect_listenbrainz_failed)
    SettingsScreen(onDismissRequest = { if (!connecting) dismiss() }, title = { Text(stringResource(Res.string.connect_listenbrainz)) }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.sources_listenbrainz_token_intro))
            OutlinedTextField(token, { token = it }, modifier = Modifier.fillMaxWidth(), enabled = !connecting, label = { Text(stringResource(Res.string.sources_user_token)) }, leadingIcon = { Icon(AppIcon.KEY.vector, null) },
                visualTransformation = PasswordVisualTransformation(), singleLine = true)
            OutlinedTextField(address, { address = it }, modifier = Modifier.fillMaxWidth(), enabled = !connecting, label = { Text(stringResource(Res.string.sources_service_address)) }, leadingIcon = { Icon(AppIcon.CLOUD.vector, null) }, singleLine = true)
            if (connecting) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { PageAction(stringResource(Res.string.sources_connect), AppIcon.LINK, enabled = token.isNotBlank() && !connecting, onClick = {
        connecting = true; error = null
        scope.launch {
            try { controller.connectListenBrainz(token, address); dismiss() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: connectFailed }
            finally { connecting = false }
        }
    }) }, dismissButton = { PageAction(stringResource(Res.string.cancel), AppIcon.CLOSE, enabled = !connecting, secondary = true, onClick = dismiss) })
}
