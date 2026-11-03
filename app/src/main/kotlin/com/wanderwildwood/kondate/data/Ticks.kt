package com.wanderwildwood.kondate.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * The ingredients ticked off in a recipe, carried in the file Settings saves every recipe to,
 * so a restore puts the cook back where they were. Each recipe in that file's `@graph` holds its
 * ticks, by place in its ingredient list, under [KEY]: a field of the app's own, which other
 * schema.org readers pass over. A recipe saved on its own to share carries none, and a file
 * without them (one from before, or from another app) brings in no ticks.
 */
object Ticks {

    const val KEY = "kondateTicked"

    /** The ticks to put on a recipe's object in the file; nothing when it has none. */
    fun put(o: JSONObject, ticked: Set<Int>) {
        if (ticked.isNotEmpty()) o.put(KEY, JSONArray(ticked.sorted()))
    }

    /** The ticks on one recipe object of a file, or null when it carries none. */
    fun of(o: JSONObject): Set<Int>? {
        val a = o.optJSONArray(KEY) ?: return null
        return (0 until a.length()).mapNotNull { i -> (a.opt(i) as? Int)?.takeIf { it >= 0 } }.toSet()
    }

    /**
     * The ticks a recipe keeps after it is brought in from a file: the file's own when it has
     * them, those still pointing at an ingredient; otherwise the ones already here, unless the
     * ingredients changed under them, since they were ticked by their place.
     */
    fun afterBringIn(here: Recipe?, current: Set<Int>, incoming: Recipe, fromFile: Set<Int>?): Set<Int> = when {
        fromFile != null -> fromFile.filter { it in incoming.ingredients.indices }.toSet()
        here != null && here.ingredients == incoming.ingredients -> current
        else -> emptySet()
    }
}
