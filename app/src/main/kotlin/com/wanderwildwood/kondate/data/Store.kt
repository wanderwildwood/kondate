package com.wanderwildwood.kondate.data

import android.content.Context
import com.wanderwildwood.kondate.importer.JsonLd
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.Collator
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Where the recipes are kept: one small JSON file each, in the app's own storage, in the
 * schema.org shape [Recipe.toJson] writes. A file a person could read, and nothing to migrate.
 * A few hundred recipes are a few hundred kilobytes, so all of them are held in memory.
 *
 * Which ingredients are ticked is kept apart, in preferences: it is where the cook is up to
 * today, not part of the recipe, and does not go into an export.
 */
class Store private constructor(private val context: Context) {

    private val dir = File(context.filesDir, "recipes").apply { mkdirs() }
    private val ticks = context.getSharedPreferences("ticks", Context.MODE_PRIVATE)
    private val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }

    private val _all = MutableStateFlow(load())

    /**
     * Called after every change made here on the phone (a save, a deletion, recipes brought in),
     * not after a sync's own; the sync uses it to follow a change a few seconds later.
     */
    @Volatile
    var onChanged: (() -> Unit)? = null

    /** Every recipe, in order of name. */
    val all: StateFlow<List<Recipe>> = _all

    private fun load(): List<Recipe> =
        sorted(
            dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
                runCatching { JsonLd.recipesInJson(f.readText()).firstOrNull() }.getOrNull()
                    ?.let { if (it.id.isEmpty()) it.copy(id = f.name.removeSuffix(".json")) else it }
            },
        )

    private fun sorted(list: List<Recipe>) = list.sortedWith { a, b -> collator.compare(a.name, b.name) }

    fun get(id: String): Recipe? = _all.value.firstOrNull { it.id == id }

    /** The recipe already saved from [url], if one was. */
    fun byUrl(url: String): Recipe? =
        url.takeIf { it.isNotBlank() }?.let { u -> _all.value.firstOrNull { same(it.url, u) } }

    private fun same(a: String, b: String): Boolean {
        fun bare(s: String) = s.trim().substringBefore('#').removeSuffix("/")
            .replaceFirst(Regex("^https?://(www\\.)?", RegexOption.IGNORE_CASE), "").lowercase()
        return a.isNotBlank() && bare(a) == bare(b)
    }

    /**
     * Keeps [recipe], new or changed, and gives it back as kept: with an id if it had none,
     * stamped with when it was made and changed. The file is written whole and then moved over
     * the old one, so a phone that dies mid-save keeps the old recipe rather than half of each.
     */
    @Synchronized
    fun save(recipe: Recipe): Recipe {
        val now = now()
        val kept = recipe.copy(
            id = recipe.id.ifEmpty { newId() },
            created = recipe.created.ifEmpty { now },
            modified = now,
        )
        write(kept)
        _all.value = sorted(_all.value.filterNot { it.id == kept.id } + kept)
        onChanged?.invoke()
        return kept
    }

    /**
     * A recipe from a sync, kept exactly as it came, stamps and all, only if the recipe of its
     * id here is still the one the sync saw: at [expect], or not here at all when that is null.
     * Its ticks go when its ingredients changed, since they were ticked by their place.
     */
    @Synchronized
    fun putSynced(recipe: Recipe, expect: String?): Boolean {
        val now = get(recipe.id)
        if (now?.modified != expect) return false
        write(recipe)
        if (now != null && now.ingredients != recipe.ingredients) ticks.edit().remove(recipe.id).apply()
        _all.value = sorted(_all.value.filterNot { it.id == recipe.id } + recipe)
        return true
    }

    /** A recipe deleted by a sync, on the same condition. */
    @Synchronized
    fun removeSynced(id: String, expect: String): Boolean {
        val now = get(id) ?: return true
        if (now.modified != expect) return false
        remove(id)
        return true
    }

    private fun write(recipe: Recipe) {
        val file = File(dir, "${safe(recipe.id)}.json")
        val tmp = File(dir, "${safe(recipe.id)}.json.tmp")
        tmp.writeText(recipe.toJson().toString(1))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    @Synchronized
    fun delete(id: String) {
        remove(id)
        onChanged?.invoke()
    }

    private fun remove(id: String) {
        File(dir, "${safe(id)}.json").delete()
        ticks.edit().remove(id).apply()
        _all.value = _all.value.filterNot { it.id == id }
    }

    /**
     * Recipes brought in from a file. One with the id of a recipe already here replaces it,
     * which is what bringing back an export should do; one with no id, or an id not seen
     * before, is added. Returns how many came in.
     */
    @Synchronized
    fun bringIn(recipes: List<Recipe>): Int {
        if (recipes.isEmpty()) return 0
        val now = now()
        val byId = _all.value.associateBy { it.id }.toMutableMap()
        for (r in recipes) {
            val kept = r.copy(
                id = r.id.takeIf { it.isNotEmpty() && safe(it) == it } ?: newId(),
                created = r.created.ifEmpty { now },
                modified = r.modified.ifEmpty { now },
            )
            write(kept)
            byId[kept.id] = kept
        }
        _all.value = sorted(byId.values.toList())
        onChanged?.invoke()
        return recipes.size
    }

    /** Every recipe as one document, for a file. */
    fun export(): String = recipesDocument(_all.value).toString(1)

    /** The ingredients ticked off in a recipe, by their place in its list. */
    fun ticked(id: String): Set<Int> =
        ticks.getStringSet(id, emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }.toSet()

    fun setTicked(id: String, ticked: Set<Int>) {
        if (ticked.isEmpty()) ticks.edit().remove(id).apply()
        else ticks.edit().putStringSet(id, ticked.map { it.toString() }.toSet()).apply()
    }

    fun newId() = UUID.randomUUID().toString()

    /** An id as a file name: letters, digits and dashes, as every id this app makes is. */
    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)

    fun now(): String =
        OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    companion object {
        @Volatile
        private var store: Store? = null

        fun get(context: Context): Store =
            store ?: synchronized(this) { store ?: Store(context.applicationContext).also { store = it } }
    }
}
