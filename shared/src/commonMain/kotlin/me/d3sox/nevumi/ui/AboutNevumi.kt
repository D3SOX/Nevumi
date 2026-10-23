package me.d3sox.nevumi.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalUriHandler
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.about_nevumi
import me.d3sox.nevumi.resources.app_name
import me.d3sox.nevumi.resources.done
import me.d3sox.nevumi.resources.nevumi_logo
import me.d3sox.nevumi.resources.settings_about_tagline
import me.d3sox.nevumi.resources.settings_license
import me.d3sox.nevumi.resources.settings_nevumi_logo
import me.d3sox.nevumi.resources.settings_source_code
import me.d3sox.nevumi.resources.settings_version
import me.d3sox.nevumi.resources.settings_view_license
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal expect fun appVersion(): String

@Composable
internal fun AboutNevumi(dismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val source = "https://github.com/D3SOX/Nevumi"
    var showLicense by remember { mutableStateOf(false) }
    var license by remember { mutableStateOf("") }
    LaunchedEffect(showLicense) {
        if (showLicense) license = Res.readBytes("files/LICENSE").decodeToString()
    }
    if (showLicense) {
        SettingsScreen(onDismissRequest = { showLicense = false }, title = { Text(stringResource(Res.string.settings_license)) },
            text = { Text(license, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) })
        return
    }
    SettingsScreen(onDismissRequest = dismiss, title = { Text(stringResource(Res.string.about_nevumi)) }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Image(painterResource(Res.drawable.nevumi_logo), stringResource(Res.string.settings_nevumi_logo), Modifier.size(144.dp))
            Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.headlineLarge)
            Text(stringResource(Res.string.settings_version, appVersion()), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(Res.string.settings_about_tagline), textAlign = TextAlign.Center)
            Text("AGPL-3.0-only", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = { uriHandler.openUri(source) }, Modifier.widthIn(max = 360.dp).fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(AppIcon.CODE.vector, null); Spacer(Modifier.width(8.dp)); Text(stringResource(Res.string.settings_source_code))
            }
            OutlinedButton(onClick = { showLicense = true }, Modifier.widthIn(max = 360.dp).fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(AppIcon.LICENSE.vector, null); Spacer(Modifier.width(8.dp)); Text(stringResource(Res.string.settings_view_license))
            }
        }
    }, confirmButton = { PageAction(stringResource(Res.string.done), onClick = dismiss) })
}
