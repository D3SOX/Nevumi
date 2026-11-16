package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.d3sox.nevumi.diagnostics.CrashReport
import me.d3sox.nevumi.diagnostics.CrashReports
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.crash_prompt_dismiss
import me.d3sox.nevumi.resources.crash_prompt_message
import me.d3sox.nevumi.resources.crash_prompt_title
import me.d3sox.nevumi.resources.crash_report_copied
import me.d3sox.nevumi.resources.crash_report_copy
import me.d3sox.nevumi.resources.crash_report_delete
import me.d3sox.nevumi.resources.crash_reports
import me.d3sox.nevumi.resources.crash_reports_delete_all
import me.d3sox.nevumi.resources.crash_reports_description
import me.d3sox.nevumi.resources.crash_reports_empty
import me.d3sox.nevumi.resources.done
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** The device's crash log, which the platform provides; tests and previews run without one. */
internal val LocalCrashReports = staticCompositionLocalOf<CrashReports?> { null }

/** A clipboard entry holding [text], which each platform wraps differently. */
internal expect fun plainTextClip(text: String): ClipEntry

/** After a crash, offers its report once on the next launch. */
@Composable
internal fun CrashReportPrompt() {
    val reports = LocalCrashReports.current ?: return
    val report = reports.unseen.collectAsState().value ?: return
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = reports::markSeen, icon = { Icon(AppIcon.BUG_REPORT.vector, null) },
        title = { Text(stringResource(Res.string.crash_prompt_title), textAlign = TextAlign.Center) },
        text = { Text(stringResource(Res.string.crash_prompt_message)) },
        confirmButton = { TextButton(onClick = {
            scope.launch { clipboard.setClipEntry(plainTextClip(report.text)) }
            reports.markSeen()
        }) { Text(stringResource(Res.string.crash_report_copy)) } },
        dismissButton = { TextButton(onClick = reports::markSeen) { Text(stringResource(Res.string.crash_prompt_dismiss)) } })
}

@Composable
internal fun CrashReportsPage(reports: CrashReports, dismiss: () -> Unit) {
    val shown by reports.reports.collectAsState()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf<String?>(null) }
    SettingsScreen(onDismissRequest = dismiss, title = { Text(stringResource(Res.string.crash_reports)) },
        actions = { if (shown.isNotEmpty()) TextButton(onClick = reports::deleteAll) { Text(stringResource(Res.string.crash_reports_delete_all)) } },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.crash_reports_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
                copied?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                if (shown.isEmpty()) Text(stringResource(Res.string.crash_reports_empty), Modifier.padding(vertical = 16.dp))
                else SettingsGroup { shown.forEach { report -> CrashReportRow(report, copy = {
                    scope.launch {
                        clipboard.setClipEntry(plainTextClip(report.text))
                        copied = getString(Res.string.crash_report_copied)
                    }
                }, delete = { reports.delete(report.id) }) } }
            }
        }, confirmButton = { PageAction(stringResource(Res.string.done), onClick = dismiss) })
}

@Composable
private fun CrashReportRow(report: CrashReport, copy: () -> Unit, delete: () -> Unit) {
    val time = formatListenTime(report.time)
    // The class name without its package keeps the line readable; the copied report keeps everything.
    val summary = report.summary.substringBefore(':').substringAfterLast('.') + report.summary.substringAfter(':', "").let { if (it.isEmpty()) "" else ":$it" }
    Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 12.dp, end = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(time, style = MaterialTheme.typography.bodyLarge)
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            AppIconButton(AppIcon.COPY, stringResource(Res.string.crash_report_copy), onClick = copy)
            AppIconButton(AppIcon.REMOVE, stringResource(Res.string.crash_report_delete, time), onClick = delete)
        }
    }
}
