package com.wanderwildwood.kondate

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalCursorBlinkEnabled
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.IntentCompat
import com.mudita.mmd.ThemeMMD
import com.wanderwildwood.kondate.importer.Reader
import com.wanderwildwood.kondate.sync.Cloud
import com.wanderwildwood.kondate.ui.Incoming
import com.wanderwildwood.kondate.ui.RecipesApp
import com.wanderwildwood.kondate.ui.monochrome

class MainActivity : ComponentActivity() {

    /** What the latest intent brought, until the app has taken it. */
    private val incoming = mutableStateOf<Incoming?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) incoming.value = incomingFrom(intent)
        setContent {
            // A steady cursor: a blinking one is a repaint twice a second on an e-ink panel
            // for as long as a field has focus.
            CompositionLocalProvider(LocalCursorBlinkEnabled provides false) {
                ThemeMMD(colorScheme = monochrome) {
                    RecipesApp(incoming.value, onTaken = { incoming.value = null })
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Nothing happens here unless the reader has signed in to a Nextcloud.
        Cloud.get(this).onOpen()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingFrom(intent)?.let { incoming.value = it }
    }

    /**
     * A page shared from the browser (its address, perhaps with its title), words shared from
     * anywhere else, or a file of recipes opened from Files or shared from another app.
     */
    private fun incomingFrom(intent: Intent?): Incoming? {
        intent ?: return null
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { return Incoming.File(it) }
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
                Reader.addressIn(text)?.takeIf { Reader.isLinkShare(text) }?.let { Incoming.Link(it) }
                    ?: Incoming.Text(intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty(), text)
            }
            Intent.ACTION_VIEW -> intent.data?.let { uri ->
                if (uri.scheme == "http" || uri.scheme == "https") Incoming.Link(uri.toString()) else Incoming.File(uri)
            }
            else -> null
        }
    }
}
