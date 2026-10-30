package com.wanderwildwood.kondate.ui

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.kondate.R
import com.wanderwildwood.kondate.data.Recipe
import com.wanderwildwood.kondate.data.Store
import com.wanderwildwood.kondate.importer.Reader
import com.wanderwildwood.kondate.sync.Cloud
import com.wanderwildwood.kondate.sync.PULLED_LINE_MS
import com.wanderwildwood.kondate.sync.Pulled
import com.wanderwildwood.kondate.sync.pulledAfter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.Normalizer

/**
 * Every recipe, by name, and the way in to a new one. A search over names and ingredients
 * opens under the bar when the magnifier is pressed; the + offers typing one in or reading
 * one from a page's address.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    store: Store,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onPaste: () -> Unit,
    onLink: (String) -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    // A sync with the Nextcloud to run when the list is pulled down from its top, returning the
    // state it left; null while the recipes are kept only on this phone.
    onPull: (suspend () -> Cloud.State)? = null,
) {
    val context = LocalContext.current
    var pulled by remember { mutableStateOf<Pulled?>(null) }
    val pullScope = rememberCoroutineScope()
    var pulling by remember { mutableStateOf<Job?>(null) }
    val pull: () -> Unit = {
        val run = onPull
        if (run != null && pulling?.isActive != true) {
            pulling = pullScope.launch {
                Log.i("kondate", "pulled at the top of the list: syncing")
                pulled = Pulled.SYNCING
                pulled = pulledAfter(run())
                delay(PULLED_LINE_MS)
                pulled = null
            }
        }
    }
    val all by store.all.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var askLink by remember { mutableStateOf(false) }
    val shown = remember(all, query, searching) { if (searching) matching(all, query) else all }
    val state = rememberLazyListState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.app_name)) },
                actions = {
                    BarButton(Icons.Search, stringResource(R.string.cd_search)) {
                        searching = !searching
                        if (!searching) query = ""
                    }
                    BarButton(Icons.Add, stringResource(R.string.cd_add)) { adding = true }
                    BarButton(Icons.Settings, stringResource(R.string.cd_settings), onSettings)
                    BarButton(Icons.Info, stringResource(R.string.cd_about), onAbout)
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (searching) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                TextFieldMMD(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { TextMMD(text = stringResource(R.string.search_hint), style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).focusRequester(focus).textActions(),
                )
            }
            pulled?.let { p ->
                TextMMD(
                    text = stringResource(
                        when (p) {
                            Pulled.SYNCING -> R.string.pull_syncing
                            Pulled.UP_TO_DATE -> R.string.pull_up_to_date
                            Pulled.UNREACHABLE -> R.string.pull_unreachable
                            Pulled.REFUSED -> R.string.pull_refused
                            Pulled.FAILED -> R.string.pull_failed
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                )
            }
            when {
                all.isEmpty() -> TextMMD(
                    text = stringResource(R.string.list_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                )
                shown.isEmpty() -> TextMMD(
                    text = stringResource(R.string.search_none),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                )
                else -> {
                    PagedColumn(
                        state = state,
                        modifier = Modifier.fillMaxSize(),
                        onPullAtTop = if (onPull != null) pull else null,
                    ) {
                        items(shown, key = { it.id }) { r ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpen(r.id) }
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                            ) {
                                TextMMD(
                                    text = r.name.ifBlank { stringResource(R.string.untitled) },
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val summary = remember(r) { Words.summary(context, r) }
                                if (summary.isNotEmpty()) TextMMD(text = summary, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }

    if (adding) {
        EInkDialog(onDismiss = { adding = false }) {
            TextMMD(text = stringResource(R.string.new_recipe), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(12.dp))
            WideButton(stringResource(R.string.new_type_in)) {
                adding = false
                onNew()
            }
            WideButton(stringResource(R.string.new_from_link)) {
                adding = false
                askLink = true
            }
            WideButton(stringResource(R.string.new_paste)) {
                adding = false
                onPaste()
            }
            Spacer(Modifier.height(8.dp))
            TextMMD(text = stringResource(R.string.new_share_hint), style = MaterialTheme.typography.labelSmall)
        }
    }
    if (askLink) LinkDialog(onDismiss = { askLink = false }, onRead = {
        askLink = false
        onLink(it)
    })
}

/** The address of a recipe page, typed or pasted. */
@Composable
private fun LinkDialog(onDismiss: () -> Unit, onRead: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    EInkDialog(onDismiss = onDismiss) {
        TextMMD(text = stringResource(R.string.link_title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        TextFieldMMD(
            value = text,
            onValueChange = {
                text = it
                wrong = false
            },
            singleLine = true,
            placeholder = { TextMMD(text = "https://", style = MaterialTheme.typography.labelSmall) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).textActions(),
        )
        if (wrong) TextMMD(text = stringResource(R.string.link_wrong), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(12.dp))
        WideButton(stringResource(R.string.link_read), filled = true) {
            val t = text.trim()
            val url = Reader.addressIn(t) ?: if (t.contains('.') && !t.contains(' ')) "https://$t" else null
            if (url == null) wrong = true else onRead(url)
        }
        WideButton(stringResource(R.string.cancel), onClick = onDismiss)
    }
}

/** The page being read, or why it could not be. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingPage(url: String, failed: Int?, onRetry: () -> Unit, onTypeIn: () -> Unit, onPaste: () -> Unit, onBack: () -> Unit) {
    val host = remember(url) { runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull() ?: url }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.reading_title)) },
                navigationIcon = { BarButton(Icons.Back, stringResource(R.string.cd_back), onBack) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 20.dp, vertical = 16.dp)) {
            if (failed == null) {
                TextMMD(text = stringResource(R.string.reading, host), style = MaterialTheme.typography.bodyMedium)
            } else if (Reader.refused(failed)) {
                TextMMD(text = stringResource(R.string.read_refused), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                HouseButton(stringResource(R.string.paste_title), onPaste, Modifier.fillMaxWidth(), on = true)
                Spacer(Modifier.height(8.dp))
                HouseButton(stringResource(R.string.cancel), onBack, Modifier.fillMaxWidth())
            } else {
                TextMMD(
                    text = if (failed == Reader.NEEDS_HTTPS) stringResource(R.string.read_needs_https, host) else if (failed == 0) stringResource(R.string.read_failed_offline, host) else stringResource(R.string.read_failed_code, host, failed),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                WideButton(stringResource(R.string.try_again), filled = true, onClick = onRetry)
                WideButton(stringResource(R.string.type_it_in), onClick = onTypeIn)
            }
        }
    }
}

/** Recipes whose name or ingredients hold every word of [query], accents and case aside. */
private fun matching(all: List<Recipe>, query: String): List<Recipe> {
    val words = fold(query).split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return all
    return all.filter { r ->
        val hay = fold(r.name + " " + r.ingredients.joinToString(" ") + " " + r.keywords + " " + r.category)
        words.all { hay.contains(it) }
    }
}

private val MARKS = Regex("\\p{Mn}+")

private fun fold(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(MARKS, "")

/**
 * Recipe text pasted in: a big plain box, and a button that sorts it into a draft for the
 * recipe form. Nothing leaves the phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasteScreen(onDraft: (String) -> Unit, onBack: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var empty by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.paste_title)) },
                navigationIcon = { BarButton(Icons.Back, stringResource(R.string.cd_back), onBack) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp)) {
            TextMMD(text = stringResource(R.string.paste_help), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(8.dp))
            TextFieldMMD(
                value = text,
                onValueChange = {
                    text = it
                    empty = false
                },
                singleLine = false,
                textStyle = MaterialTheme.typography.bodyMedium,
                placeholder = { TextMMD(text = stringResource(R.string.paste_hint), style = MaterialTheme.typography.labelSmall) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().weight(1f).focusRequester(focus).textActions(),
            )
            if (empty) TextMMD(text = stringResource(R.string.paste_empty), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(8.dp))
            HouseButton(
                stringResource(R.string.paste_make),
                { if (text.isBlank()) empty = true else onDraft(text) },
                Modifier.fillMaxWidth(),
                on = true,
            )
        }
    }
}
