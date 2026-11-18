package com.wanderwildwood.kondate.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wanderwildwood.kondate.R
import com.wanderwildwood.kondate.data.Recipe
import com.wanderwildwood.kondate.data.Store
import com.wanderwildwood.kondate.importer.JsonLd
import com.wanderwildwood.kondate.importer.PastedRecipe
import com.wanderwildwood.kondate.importer.Reader
import com.wanderwildwood.kondate.sync.Cloud
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What another app handed this one: a page to read, or some text. */
sealed interface Incoming {
    data class Link(val url: String) : Incoming
    data class Text(val subject: String, val text: String) : Incoming
}

/** The places in the app. Each opens over the one it was reached from, and Back closes it. */
sealed interface Screen {
    data object List : Screen
    data class Recipe(val id: String) : Screen
    /** A recipe being typed in or changed: [id] null for a new one, [draft] what it starts from. */
    data class Edit(val id: String?, val draft: com.wanderwildwood.kondate.data.Recipe? = null, val note: Int = 0) : Screen
    data class Cook(val id: String) : Screen
    data class Reading(val url: String) : Screen
    /** Recipe text pasted in, to be sorted into a draft. */
    data object Paste : Screen
    data object Settings : Screen
    data object Nextcloud : Screen
}

@Composable
fun RecipesApp(incoming: Incoming?, onTaken: () -> Unit) {
    val context = LocalContext.current
    val store = remember { Store.get(context) }
    // Kept through the process being stopped, so a recipe half typed in is not lost.
    var stack by rememberSaveable(stateSaver = ScreenStack.saver) { mutableStateOf(listOf<Screen>(Screen.List)) }
    var about by remember { mutableStateOf(false) }

    fun go(s: Screen) { stack = stack + s }
    fun back() { if (stack.size > 1) stack = stack.dropLast(1) }
    fun replace(s: Screen) { stack = stack.dropLast(1) + s }

    LaunchedEffect(incoming) {
        val i = incoming ?: return@LaunchedEffect
        // Taken only once handled: clearing it first changes this effect's key, which would
        // cancel a file still being read in.
        when (i) {
            is Incoming.Link -> {
                val known = store.byUrl(i.url)
                stack = listOf(Screen.List) + if (known != null) {
                    FootNotice.say(context, context.getString(R.string.already_saved))
                    Screen.Recipe(known.id)
                } else {
                    Screen.Reading(i.url)
                }
            }
            // Shared words (not just a link): sorted into a draft, on the phone.
            is Incoming.Text -> stack = listOf(
                Screen.List,
                Screen.Edit(null, draftFromText(i.subject, i.text), R.string.paste_draft_note),
            )
        }
        onTaken()
    }

    BackHandler(enabled = stack.size > 1) { back() }
    val cloud = remember { Cloud.get(context) }
    val account by cloud.account.collectAsStateWithLifecycle()

    when (val s = stack.last()) {
        Screen.List -> ListScreen(
            store = store,
            onOpen = { go(Screen.Recipe(it)) },
            onNew = { go(Screen.Edit(null)) },
            onPaste = { go(Screen.Paste) },
            onLink = { url ->
                val known = store.byUrl(url)
                if (known != null) {
                    FootNotice.say(context, context.getString(R.string.already_saved))
                    go(Screen.Recipe(known.id))
                } else {
                    go(Screen.Reading(url))
                }
            },
            onSettings = { go(Screen.Settings) },
            onPull = if (account != null) ({ cloud.syncAndWait() }) else null,
        )
        is Screen.Recipe -> RecipeScreen(
            store = store,
            id = s.id,
            onBack = ::back,
            onEdit = { go(Screen.Edit(s.id)) },
            onCook = { go(Screen.Cook(s.id)) },
        )
        is Screen.Edit -> EditScreen(
            store = store,
            id = s.id,
            draft = s.draft,
            note = s.note,
            onDone = { saved -> if (saved != null && s.id == null) replace(Screen.Recipe(saved)) else back() },
        )
        is Screen.Cook -> CookScreen(store = store, id = s.id, onBack = ::back)
        is Screen.Reading -> ReadingScreen(
            store = store,
            url = s.url,
            onSaved = { replace(Screen.Recipe(it)) },
            onTypeIn = { draft, note -> replace(Screen.Edit(null, draft, note)) },
            onPaste = { replace(Screen.Paste) },
            onBack = ::back,
        )
        Screen.Paste -> PasteScreen(
            onDraft = { text -> replace(Screen.Edit(null, draftFromText("", text), R.string.paste_draft_note)) },
            onBack = ::back,
        )
        Screen.Settings -> SettingsScreen(
            store = store,
            onBack = ::back,
            onAbout = { about = true },
            onOpen = { stack = listOf(Screen.List, Screen.Recipe(it)) },
            onNextcloud = { go(Screen.Nextcloud) },
        )
        Screen.Nextcloud -> NextcloudScreen(cloud = Cloud.get(context), onBack = ::back)
    }
    if (about) AboutDialog(onDismiss = { about = false })
}

/**
 * Recipes from a file the reader picked in Settings: brought in, said so, and
 * the id of the one recipe when there was only one, to open it.
 */
suspend fun bringIn(context: Context, store: Store, uri: Uri): String? {
    val recipes = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()?.let { JsonLd.recipesAndTicksInJson(it) }.orEmpty()
    }
    if (recipes.isEmpty()) {
        FootNotice.say(context, context.getString(R.string.import_none))
        return null
    }
    val before = store.all.value.map { it.id }.toSet()
    val count = withContext(Dispatchers.IO) { store.bringIn(recipes) }
    FootNotice.say(context, context.resources.getQuantityString(R.plurals.imported, count, count))
    if (recipes.size != 1) return null
    return recipes[0].first.id.takeIf { it.isNotEmpty() && store.get(it) != null }
        ?: store.all.value.map { it.id }.firstOrNull { it !in before }
}

/** Reading a page shared here; it gives way to the recipe, or to the form to type it in. */
@Composable
private fun ReadingScreen(
    store: Store,
    url: String,
    onSaved: (String) -> Unit,
    onTypeIn: (Recipe, Int) -> Unit,
    onPaste: () -> Unit,
    onBack: () -> Unit,
) {
    var failed by remember(url) { mutableStateOf<Int?>(null) }
    var attempt by remember(url) { mutableStateOf(0) }
    LaunchedEffect(url, attempt) {
        failed = null
        when (val r = withContext(Dispatchers.IO) { Reader.read(url) }) {
            is Reader.Result.Found -> onSaved(withContext(Dispatchers.IO) { store.save(r.recipe.copy(id = "")) }.id)
            is Reader.Result.NoRecipe -> onTypeIn(Recipe(id = "", name = r.title, url = url), R.string.no_recipe_on_page)
            is Reader.Result.Failed -> failed = r.code
        }
    }
    ReadingPage(url = url, failed = failed, onRetry = { attempt++ }, onTypeIn = { onTypeIn(Recipe(id = "", name = "", url = url), 0) }, onPaste = onPaste, onBack = onBack)
}

/**
 * The draft for text that arrived by sharing or pasting. A subject the sharing app gave (a page
 * title, say) is the name only when the text itself has no first line to be one.
 */
fun draftFromText(subject: String, text: String): Recipe {
    val r = PastedRecipe.parse(text)
    return if (r.name.isBlank()) r.copy(name = subject.trim()) else r
}
