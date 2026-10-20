package com.wanderwildwood.kondate.ui

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.kondate.R
import com.wanderwildwood.kondate.data.Durations
import com.wanderwildwood.kondate.data.Store

/**
 * The method one step to a screen, in large type, for a phone propped up by the stove. A swipe
 * sideways, or a press near either edge, goes to the next step or back one; the buttons at the
 * foot do the same. Where a step names a length of time, a button starts a timer for it in the
 * clock app.
 *
 * The screen stays on while this is open, and only then: a cook with floury hands should not
 * have to wake the phone between steps, and nobody else needs the screen held on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CookScreen(store: Store, id: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val all by store.all.collectAsStateWithLifecycle()
    val recipe = all.firstOrNull { it.id == id }
    if (recipe == null || recipe.steps.isEmpty()) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val steps = recipe.steps
    var at by rememberSaveable(id) { mutableIntStateOf(0) }
    at = at.coerceIn(0, steps.lastIndex)
    val step = steps[at]
    val found = remember(step.text) { Durations.inText(step.text).take(3) }

    // On the activity's window rather than on the view: the view's flag was seen to outlive
    // the screen that set it.
    val window = (LocalActivity.current)?.window
    DisposableEffect(window) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val latest by rememberUpdatedState(steps.size)
    fun next() { if (at < latest - 1) at++ else onBack() }
    fun previous() { if (at > 0) at-- }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.step_of, at + 1, steps.size)) },
                navigationIcon = { BarButton(Icons.Close, stringResource(R.string.cd_close_cook), onBack) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        var turned = false
                        detectHorizontalDragGestures(
                            onDragStart = { turned = false },
                            onDragEnd = { turned = false },
                        ) { _, drag ->
                            if (turned || drag == 0f) return@detectHorizontalDragGestures
                            turned = true
                            if (drag < 0) next() else previous()
                        }
                    }
                    .pointerInput(Unit) {
                        // The outer fifth either side turns the page, as a book's edge would.
                        detectTapGestures { offset ->
                            val edge = size.width / 5f
                            when {
                                offset.x < edge -> previous()
                                offset.x > size.width - edge -> next()
                            }
                        }
                    },
            ) {
                // A long step is read a screen at a time, swiped up like any list here.
                val list = rememberLazyListState()
                LaunchedEffect(at) { list.scrollToItem(0) }
                PagedColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)) {
                    if (step.section.isNotEmpty()) {
                        item(key = "section") {
                            TextMMD(text = step.section, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                        }
                    }
                    item(key = "text$at") { TextMMD(text = step.text, style = MaterialTheme.typography.titleLarge) }
                    if (found.isNotEmpty()) {
                        item(key = "timers") {
                            Column(Modifier.padding(top = 16.dp)) {
                                found.forEach { f ->
                                    val length = Words.length(context, f.seconds)
                                    WideButton(stringResource(R.string.start_timer, length)) {
                                        Elsewhere.timer(context, f.seconds, context.getString(R.string.timer_label, recipe.name, at + 1))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                WideButton(stringResource(R.string.step_previous), modifier = Modifier.weight(1f)) { previous() }
                Spacer(Modifier.width(12.dp))
                WideButton(
                    stringResource(if (at == steps.lastIndex) R.string.step_done else R.string.step_next),
                    filled = true,
                    modifier = Modifier.weight(1f),
                ) { next() }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
