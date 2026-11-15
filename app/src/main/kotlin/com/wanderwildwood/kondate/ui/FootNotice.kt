package com.wanderwildwood.kondate.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import java.lang.ref.WeakReference
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mudita.mmd.ThemeMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.text.TextMMD

/**
 * A short thing to say, along the foot of the screen, until the next press. It stands where a
 * toast stood: on this panel a toast could be gone before it had been read.
 *
 * The next press anywhere takes it down and goes on to do what it does; a press on the words
 * themselves only takes them down, so nothing under them is pressed unseen. Back takes it down
 * and then goes back as it would have. A new notice takes the place of the last one.
 */
object FootNotice {
    /** Says [text] on the screen [context] belongs to, or else on the screen in front. */
    fun say(context: Context, text: String) {
        val activity = context.activity()?.takeUnless { it.isFinishing }
            ?: front?.get()?.takeUnless { it.isFinishing }
        if (activity == null) {
            // Nowhere on screen to say it: said the old way rather than not at all.
            Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show()
            return
        }
        activity.runOnUiThread { show(activity, text) }
    }

    /** The screen in front, for a notice from somewhere with no screen of its own. */
    private var front: WeakReference<Activity>? = null

    fun resumed(activity: Activity) { front = WeakReference(activity) }

    fun paused(activity: Activity) { if (front?.get() === activity) front = null }

    private fun show(activity: Activity, text: String) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val last = content.findViewWithTag<Layer>(TAG)
        // The same words again (a sound that still cannot play) leave the line as it is.
        if (last != null && last.text == text) return
        last?.dismiss()
        content.addView(Layer(activity, text).apply { tag = TAG })
    }

    private const val TAG = "foot-notice"

    private tailrec fun Context.activity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }

    @SuppressLint("ViewConstructor")
    private class Layer(activity: Activity, val text: String) : FrameLayout(activity) {
        /** Where the band of words starts, down from the top of this layer. */
        private var wordsTop = Float.MAX_VALUE

        private val words = ComposeView(activity).apply {
            setContent {
                ThemeMMD(colorScheme = monochrome, typography = houseTypography) {
                    Box(Modifier.fillMaxSize().navigationBarsPadding().imePadding(), contentAlignment = Alignment.BottomCenter) {
                        Column(
                            Modifier.fillMaxWidth()
                                .onGloballyPositioned { wordsTop = it.positionInRoot().y }
                                .background(MaterialTheme.colorScheme.surface),
                        ) {
                            HorizontalDividerMMD(thickness = 1.dp)
                            TextMMD(
                                text = text,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                                    .semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                    }
                }
            }
        }

        private val back = (activity as? ComponentActivity)?.let { owner ->
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    dismiss()
                    owner.onBackPressedDispatcher.onBackPressed()
                }
            }.also { owner.onBackPressedDispatcher.addCallback(owner, it) }
        }

        init {
            addView(words, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                post { dismiss() }
                // A press on the words is theirs; any other goes on to what lies under it.
                return ev.y >= wordsTop
            }
            return true
        }

        fun dismiss() {
            back?.remove()
            (parent as? ViewGroup)?.removeView(this)
        }
    }
}
