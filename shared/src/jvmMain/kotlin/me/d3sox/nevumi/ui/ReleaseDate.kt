package me.d3sox.nevumi.ui

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val day = DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH)
private val month = DateTimeFormatter.ofPattern("MMM uuuu", Locale.ENGLISH)

internal actual fun formatReleaseDate(date: String): String = runCatching {
    when (date.length) {
        10 -> day.format(LocalDate.parse(date))
        7 -> month.format(YearMonth.parse(date))
        else -> date
    }
}.getOrDefault(date)
