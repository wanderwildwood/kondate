package com.wanderwildwood.kondate.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.checkbox.CheckboxMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.kondate.R
import com.wanderwildwood.kondate.data.Plain
import com.wanderwildwood.kondate.data.Quantities
import com.wanderwildwood.kondate.data.Recipe
import com.wanderwildwood.kondate.data.Store
import com.wanderwildwood.kondate.data.recipesDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The scales offered for a recipe that does not say how many it serves. */
private val FACTORS = listOf(0.25, 0.5, 1.0, 1.5, 2.0, 3.0, 4.0)

/**
 * One recipe, read top to bottom as it would be on a card: the name, its times, how many it
 * serves (and a way to make it serve more or fewer), the ingredients as a list to tick off, the
 * method, the notes and where it came from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeScreen(store: Store, id: String, onBack: () -> Unit, onEdit: () -> Unit, onCook: () -> Unit) {
    val context = LocalContext.current
    val all by store.all.collectAsStateWithLifecycle()
    val recipe = all.firstOrNull { it.id == id }
    if (recipe == null) {
        // Deleted, or never there: nothing to show.
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val base = recipe.servings
    var servings by rememberSaveable(id) { mutableStateOf(base ?: 0) }
    var factorAt by rememberSaveable(id) { mutableStateOf(FACTORS.indexOf(1.0)) }
    val factor = if (base != null && base > 0) servings.toDouble() / base else FACTORS[factorAt]
    var ticked by remember(id) { mutableStateOf(store.ticked(id)) }
    var more by remember { mutableStateOf(false) }

    fun tick(i: Int) {
        ticked = if (i in ticked) ticked - i else ticked + i
        store.setTicked(id, ticked)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = {},
                navigationIcon = { BarButton(Icons.Back, stringResource(R.string.cd_back), onBack) },
                actions = {
                    BarButton(Icons.Edit, stringResource(R.string.cd_edit), onEdit)
                    BarButton(Icons.More, stringResource(R.string.cd_more)) { more = true }
                },
            )
        },
    ) { padding ->
        PagedColumn(modifier = Modifier.padding(padding).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp)) {
            item(key = "name") {
                TextMMD(
                    text = recipe.name.ifBlank { stringResource(R.string.untitled) },
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )
            }
            if (recipe.description.isNotBlank()) {
                item(key = "description") {
                    TextMMD(text = recipe.description, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp))
                }
            }
            val times = Words.times(context, recipe)
            if (times.isNotEmpty()) {
                item(key = "times") { TextMMD(text = times, style = MaterialTheme.typography.labelSmall) }
            }
            item(key = "scale") {
                Scaler(
                    label = if (base != null) {
                        Words.yieldOf(context, recipe.recipeYield, servings)
                    } else {
                        Words.factor(context, factor)
                    },
                    onLess = {
                        if (base != null) servings = (servings - 1).coerceAtLeast(1) else factorAt = (factorAt - 1).coerceAtLeast(0)
                    },
                    onMore = {
                        if (base != null) servings = (servings + 1).coerceAtMost(99) else factorAt = (factorAt + 1).coerceAtMost(FACTORS.lastIndex)
                    },
                )
            }
            if (recipe.steps.isNotEmpty()) {
                item(key = "cook") { WideButton(stringResource(R.string.cook), filled = true, onClick = onCook) }
            }
            if (recipe.ingredients.isNotEmpty()) {
                item(key = "ingredients") { Heading(stringResource(R.string.ingredients)) }
                recipe.ingredients.forEachIndexed { i, line ->
                    item(key = "i$i") {
                        Ingredient(Quantities.scale(line, factor), i in ticked) { tick(i) }
                    }
                }
                item(key = "shopping") {
                    Column(Modifier.padding(top = 8.dp)) {
                        WideButton(stringResource(R.string.add_to_shopping)) {
                            val left = recipe.ingredients.indices.filter { it !in ticked }.map { Quantities.scale(recipe.ingredients[it], factor) }
                            if (left.isEmpty()) {
                                Elsewhere.say(context, R.string.shopping_nothing_left)
                            } else {
                                val title = context.getString(R.string.shopping_title, recipe.name)
                                Elsewhere.shoppingList(context, title, Plain.shoppingList(left), Plain.shoppingText(title, left))
                            }
                        }
                        if (ticked.isNotEmpty()) {
                            WideButton(stringResource(R.string.clear_ticks)) {
                                ticked = emptySet()
                                store.setTicked(id, ticked)
                            }
                        }
                    }
                }
            }
            if (recipe.steps.isNotEmpty()) {
                item(key = "method") { Heading(stringResource(R.string.method)) }
                var section = ""
                recipe.steps.forEachIndexed { i, step ->
                    if (step.section != section && step.section.isNotEmpty()) {
                        val name = step.section
                        item(key = "s$i") {
                            TextMMD(text = name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                        }
                    }
                    section = step.section
                    item(key = "m$i") { MethodStep(i + 1, step.text) }
                }
            }
            if (recipe.notes.isNotBlank()) {
                item(key = "notes-h") { Heading(stringResource(R.string.notes)) }
                item(key = "notes") { TextMMD(text = recipe.notes, style = MaterialTheme.typography.bodyMedium) }
            }
            if (recipe.url.isNotBlank()) {
                item(key = "source") {
                    val host = remember(recipe.url) { runCatching { java.net.URI(recipe.url).host?.removePrefix("www.") }.getOrNull() ?: recipe.url }
                    TextMMD(
                        text = stringResource(R.string.source, host),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { Elsewhere.page(context, recipe.url) }
                            .padding(top = 18.dp, bottom = 12.dp),
                    )
                }
            }
            item(key = "foot") { Spacer(Modifier.height(16.dp)) }
        }
    }

    if (more) MoreDialog(store, recipe, factor, onDismiss = { more = false }, onDeleted = {
        more = false
        onBack()
    })
}

/** How many it serves, with a − and a + either side. */
@Composable
private fun Scaler(label: String, onLess: () -> Unit, onMore: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
    ) {
        OutlinedButtonMMD(onClick = onLess, modifier = Modifier.size(48.dp)) {
            TextMMD(text = "−", style = MaterialTheme.typography.titleMedium)
        }
        TextMMD(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        OutlinedButtonMMD(onClick = onMore, modifier = Modifier.size(48.dp)) {
            TextMMD(text = "+", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** An ingredient to tick off, the whole row taking the press. */
@Composable
private fun Ingredient(text: String, ticked: Boolean, onTick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onTick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckboxMMD(checked = ticked, onCheckedChange = { onTick() })
        Spacer(Modifier.width(6.dp))
        TextMMD(text = text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/** A numbered step of the method. */
@Composable
private fun MethodStep(number: Int, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.Start) {
        TextMMD(text = "$number.", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, modifier = Modifier.widthIn(min = 30.dp))
        TextMMD(text = text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/**
 * What else can be done with a recipe: send it as words, keep it as a file, or delete it. The
 * delete asks in its own face, and gives up asking after four seconds.
 */
@Composable
private fun MoreDialog(store: Store, recipe: Recipe, factor: Double, onDismiss: () -> Unit, onDeleted: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var arming by remember { mutableStateOf(false) }
    LaunchedEffect(arming) {
        if (arming) {
            delay(4_000L)
            arming = false
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                        out.write(recipesDocument(listOf(recipe)).toString(1).toByteArray())
                    } != null
                }.getOrDefault(false)
            }
            Elsewhere.say(context, if (ok) R.string.saved_to_file else R.string.save_failed)
            onDismiss()
        }
    }
    EInkDialog(onDismiss = onDismiss) {
        TextMMD(text = recipe.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(12.dp))
        WideButton(stringResource(R.string.share_text)) {
            val yieldLine = recipe.servings?.let {
                val n = Math.round(it * factor).toInt().coerceAtLeast(1)
                Words.yieldOf(context, recipe.recipeYield, n)
            } ?: recipe.recipeYield
            Elsewhere.share(
                context,
                recipe.name,
                Plain.recipe(recipe, factor, yieldLine, context.getString(R.string.ingredients), context.getString(R.string.method), context.getString(R.string.notes)),
            )
            onDismiss()
        }
        WideButton(stringResource(R.string.save_file)) { save.launch(fileName(recipe.name) + ".json") }
        WideButton(stringResource(if (arming) R.string.delete_armed else R.string.delete)) {
            if (arming) {
                store.delete(recipe.id)
                onDeleted()
            } else {
                arming = true
            }
        }
    }
}

/** A recipe's name as a file name: letters, digits and dashes. */
fun fileName(name: String): String =
    name.lowercase().replace(Regex("[^\\p{L}\\p{Nd}]+"), "-").trim('-').take(60).ifEmpty { "recipe" }
