package com.wanderwildwood.kondate

import com.wanderwildwood.kondate.ui.DarkSelection
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalCursorBlinkEnabled
import androidx.compose.runtime.mutableStateOf
import com.mudita.mmd.ThemeMMD
import com.wanderwildwood.kondate.importer.Reader
import com.wanderwildwood.kondate.sync.Cloud
import com.wanderwildwood.kondate.ui.Incoming
import com.wanderwildwood.kondate.ui.RecipesApp
import com.wanderwildwood.kondate.ui.houseTypography
import com.wanderwildwood.kondate.ui.monochrome
import com.wanderwildwood.kondate.ui.ArmedScope

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
                ThemeMMD(colorScheme = monochrome, typography = houseTypography) { DarkSelection {
                    ArmedScope { RecipesApp(incoming.value, onTaken = { incoming.value = null }) }
                } }
            }
        }
    }

    // A line that stays until the next press hears of it here; see NextPress.
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        com.wanderwildwood.kondate.ui.NextPress.seen(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        com.wanderwildwood.kondate.ui.NextPress.seen(event)
        return super.dispatchKeyEvent(event)
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
     * A page shared from the browser (its address, perhaps with its title), or words shared from
     * anywhere else. A file handed in by another app is not taken: recipes come in from a file
     * only through Settings, where the reader picks it.
     */
    private fun incomingFrom(intent: Intent?): Incoming? {
        intent ?: return null
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
                Reader.addressIn(text)?.takeIf { Reader.isLinkShare(text) }?.let { Incoming.Link(it) }
                    ?: Incoming.Text(intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty(), text)
            }
            Intent.ACTION_VIEW -> intent.data
                ?.takeIf { it.scheme == "http" || it.scheme == "https" }
                ?.let { Incoming.Link(it.toString()) }
            else -> null
        }
    }
}
