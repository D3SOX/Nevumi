package me.d3sox.nevumi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/** Linux has no common share sheet, so the link goes to the clipboard. */
@Composable
internal actual fun rememberLinkSharer(): LinkSharer = remember { object : LinkSharer {
    override val copies = true
    override fun share(title: String, url: String) = Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(url), null)
} }
