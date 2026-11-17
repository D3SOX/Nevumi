package me.d3sox.nevumi.ui

import androidx.compose.runtime.Composable

/** Hands a link to the platform: Android's share sheet, or the clipboard on Linux, which [copies] tells apart. */
internal interface LinkSharer {
    val copies: Boolean
    fun share(title: String, url: String)
}

@Composable
internal expect fun rememberLinkSharer(): LinkSharer
