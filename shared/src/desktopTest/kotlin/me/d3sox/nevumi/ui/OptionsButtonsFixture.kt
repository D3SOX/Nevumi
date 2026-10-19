package me.d3sox.nevumi.ui

import kotlinx.coroutines.runBlocking
import me.d3sox.nevumi.settings.MusicPreferences

internal fun MusicPreferences.withOptionsButtons(): MusicPreferences = apply {
    runBlocking { update { it.copy(showMoreButtons = true) } }
}
