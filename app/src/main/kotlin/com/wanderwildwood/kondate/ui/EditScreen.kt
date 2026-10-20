package com.wanderwildwood.kondate.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.kondate.R
import com.wanderwildwood.kondate.data.Durations
import com.wanderwildwood.kondate.data.Plain
import com.wanderwildwood.kondate.data.Recipe
import com.wanderwildwood.kondate.data.Store
import kotlinx.coroutines.delay

/**
 * A recipe typed in, or changed: a plain form, a box for each part. Ingredients and steps are
 * one to a line, as they would be written on a card. A time is in minutes; a time a page gave in
 * words of its own ("overnight") is kept as it was unless it is typed over.
 *
 * The cross leaves without saving. With changes made it asks first, in its own face.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(store: Store, id: String?, draft: Recipe?, note: Int, onDone: (String?) -> Unit) {
    val context = LocalContext.current
    val start = remember(id) { id?.let { store.get(it) } ?: draft ?: Recipe(id = "", name = "") }
    var name by rememberSaveable { mutableStateOf(start.name) }
    var serves by rememberSaveable { mutableStateOf(start.recipeYield) }
    var prep by rememberSaveable { mutableStateOf(minutesText(start.prepTime)) }
    var cook by rememberSaveable { mutableStateOf(minutesText(start.cookTime)) }
    var ingredients by rememberSaveable { mutableStateOf(start.ingredients.joinToString("\n")) }
    var steps by rememberSaveable { mutableStateOf(Plain.stepsText(start.steps)) }
    var notes by rememberSaveable { mutableStateOf(start.notes) }
    var url by rememberSaveable { mutableStateOf(start.url) }
    var leaving by remember { mutableStateOf(false) }
    var nameless by remember { mutableStateOf(false) }
    LaunchedEffect(leaving) {
        if (leaving) {
            delay(4_000L)
            leaving = false
        }
    }

    fun changed() = name != start.name || serves != start.recipeYield || prep != minutesText(start.prepTime) ||
        cook != minutesText(start.cookTime) || ingredients != start.ingredients.joinToString("\n") ||
        steps != Plain.stepsText(start.steps) || notes != start.notes || url != start.url

    fun save() {
        if (name.isBlank()) {
            nameless = true
            return
        }
        val lines = ingredients.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val kept = store.save(
            start.copy(
                name = name.trim(),
                recipeYield = serves.trim(),
                prepTime = timeFrom(prep, start.prepTime),
                cookTime = timeFrom(cook, start.cookTime),
                // A total the page gave stops being true once the parts are changed by hand.
                totalTime = if (prep == minutesText(start.prepTime) && cook == minutesText(start.cookTime)) start.totalTime else "",
                ingredients = lines,
                steps = Plain.stepsFrom(steps),
                notes = notes.trim(),
                url = url.trim(),
            ),
        )
        // The ticks count places in the list; a changed list makes them point at other lines.
        if (lines != start.ingredients) store.setTicked(kept.id, emptySet())
        onDone(kept.id)
    }

    fun leave() {
        if (!changed() || leaving) onDone(null) else leaving = true
    }

    androidx.activity.compose.BackHandler { leave() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = {
                    TextMMD(
                        text = when {
                            leaving -> stringResource(R.string.discard_armed)
                            id == null -> stringResource(R.string.new_recipe)
                            else -> stringResource(R.string.edit_recipe)
                        },
                    )
                },
                navigationIcon = { BarButton(Icons.Close, stringResource(R.string.cd_discard)) { leave() } },
                actions = { BarText(stringResource(R.string.save)) { save() } },
            )
        },
    ) { padding ->
        PagedColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp),
        ) {
            if (note != 0) {
                item(key = "note") {
                    TextMMD(text = stringResource(note), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
                }
            }
            item(key = "name") {
                Field(stringResource(R.string.field_name), name, {
                    name = it
                    nameless = false
                })
                if (nameless) TextMMD(text = stringResource(R.string.field_name_needed), style = MaterialTheme.typography.labelSmall)
            }
            item(key = "serves") { Field(stringResource(R.string.field_yield), serves, { serves = it }, hint = stringResource(R.string.field_yield_hint)) }
            item(key = "prep") { Field(stringResource(R.string.field_prep), prep, { prep = it }, number = !isWords(prep)) }
            item(key = "cook") { Field(stringResource(R.string.field_cook), cook, { cook = it }, number = !isWords(cook)) }
            item(key = "ingredients") {
                Field(
                    stringResource(R.string.field_ingredients), ingredients, { ingredients = it },
                    hint = stringResource(R.string.field_ingredients_hint), singleLine = false, lines = 5,
                )
            }
            item(key = "steps") {
                Field(
                    stringResource(R.string.field_steps), steps, { steps = it },
                    hint = stringResource(R.string.field_steps_hint), singleLine = false, lines = 5,
                )
                TextMMD(text = stringResource(R.string.field_steps_note), style = MaterialTheme.typography.labelSmall)
            }
            item(key = "notes") { Field(stringResource(R.string.field_notes), notes, { notes = it }, singleLine = false, lines = 3) }
            item(key = "url") { Field(stringResource(R.string.field_url), url, { url = it }, url = true) }
            item(key = "foot") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** A stored time for the minutes box: the minutes of an ISO duration, or the page's own words. */
private fun minutesText(value: String): String =
    if (value.isBlank()) "" else Durations.minutesOf(value)?.toString() ?: value

private fun isWords(text: String) = text.isNotBlank() && text.any { !it.isDigit() }

/** What the minutes box holds, as it is stored: minutes as an ISO duration, words as they are. */
private fun timeFrom(text: String, was: String): String {
    val t = text.trim()
    if (t.isEmpty()) return ""
    if (t == minutesText(was)) return was
    return t.toIntOrNull()?.let { Durations.isoOfMinutes(it) } ?: t
}
