package me.d3sox.nevumi.queue

import me.d3sox.nevumi.resources.*
import org.jetbrains.compose.resources.StringResource

enum class QueueExportScope(val label: StringResource) {
    ALL(Res.string.queue_export_all), REMAINING(Res.string.queue_export_remaining), UP_NEXT(Res.string.queue_export_up_next);

    fun trackIds(snapshot: QueueSnapshot): List<String> = when (this) {
        ALL -> snapshot.entries
        REMAINING -> snapshot.entries.drop(snapshot.currentIndex.coerceAtLeast(0))
        UP_NEXT -> snapshot.upNext
    }.map { it.trackId }
}
