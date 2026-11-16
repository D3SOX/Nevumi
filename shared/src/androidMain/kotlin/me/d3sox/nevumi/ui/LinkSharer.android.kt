package me.d3sox.nevumi.ui

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
internal actual fun rememberLinkSharer(): LinkSharer {
    val context = LocalContext.current
    return remember(context) { object : LinkSharer {
        override val copies = false
        override fun share(title: String, url: String) {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url).putExtra(Intent.EXTRA_TITLE, title)
            context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    } }
}
