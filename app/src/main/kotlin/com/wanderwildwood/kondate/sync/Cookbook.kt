package com.wanderwildwood.kondate.sync

import com.wanderwildwood.kondate.data.Durations
import com.wanderwildwood.kondate.data.Recipe
import com.wanderwildwood.kondate.data.Step
import com.wanderwildwood.kondate.data.servingsOf
import com.wanderwildwood.kondate.importer.JsonLd
import org.json.JSONArray
import org.json.JSONObject

/**
 * A recipe as Nextcloud Cookbook keeps it: `<folder>/<name>/recipe.json`, a schema.org Recipe in
 * the shape Cookbook's own filters leave it in, so that Cookbook reads what this app writes as it
 * reads its own, and its next save changes nothing but what was edited.
 *
 * Where the two differ:
 * - Cookbook keeps the method as a flat list of strings, a part of it as a line "## For the
 *   sauce" before its steps. That is written here too, and read back into parts.
 * - Cookbook keeps the yield as a whole number. The number is written, and the words this app
 *   keeps ("12 pancakes") go beside it as `recipeYieldText`; they are read back only while their
 *   number is still Cookbook's, so a yield changed in Cookbook is not undone by old words.
 * - `notes`, `identifier` (this app's id for the recipe) and `recipeYieldText` are not
 *   Cookbook's. It leaves fields it does not know where they are: its filters touch only their
 *   own, and its editor sends back the whole recipe it was given.
 *
 * Whatever else Cookbook or another app put in the file (tools, nutrition, the folder's id) is
 * kept, by writing over the file as it was last read rather than starting from nothing.
 */
object Cookbook {

    const val FILE = "recipe.json"

    /** The recipe as Cookbook's recipe.json, written over [was], the file as last read, if any. */
    fun toJson(r: Recipe, was: JSONObject? = null): JSONObject {
        val o = was?.let { JSONObject(it.toString()) } ?: JSONObject()
        o.put("@context", "http://schema.org")
        o.put("@type", "Recipe")
        o.put("identifier", r.id)
        o.put("name", r.name)
        o.put("description", r.description)
        o.put("url", r.url)
        o.put("image", r.image)
        putTime(o, "prepTime", r.prepTime)
        putTime(o, "cookTime", r.cookTime)
        putTime(o, "totalTime", r.totalTime)
        o.put("recipeCategory", r.category)
        o.put("keywords", r.keywords.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(","))
        val servings = servingsOf(r.recipeYield)
        o.put("recipeYield", servings ?: JSONObject.NULL)
        if (r.recipeYield.isNotBlank() && r.recipeYield.trim() != servings?.toString()) {
            o.put(YIELD_TEXT, r.recipeYield.trim())
        } else {
            o.remove(YIELD_TEXT)
        }
        o.put("recipeIngredient", JSONArray(r.ingredients))
        o.put("recipeInstructions", JSONArray(instructionLines(r.steps)))
        if (!o.has("tool")) o.put("tool", JSONArray())
        if (r.notes.isNotBlank()) o.put("notes", r.notes) else o.remove("notes")
        if (r.author.isNotBlank()) o.put("author", JSONObject().put("@type", "Person").put("name", r.author)) else o.remove("author")
        if (r.cuisine.isNotBlank()) o.put("recipeCuisine", r.cuisine) else o.remove("recipeCuisine")
        val modified = r.modified.ifEmpty { r.created }
        o.put("dateCreated", r.created.ifEmpty { modified }.ifEmpty { JSONObject.NULL })
        o.put("dateModified", modified.ifEmpty { JSONObject.NULL })
        return o
    }

    /** The method as Cookbook writes it: one string a step, a part's name as "## name" before it. */
    fun instructionLines(steps: List<Step>): List<String> {
        val out = mutableListOf<String>()
        var section = ""
        for (s in steps) {
            if (s.section != section) {
                section = s.section
                if (section.isNotEmpty()) out += "$PART$section"
            }
            out += s.text
        }
        return out
    }

    /**
     * A recipe.json as a recipe, or null when it is not one. [id] is empty unless the file names
     * this app's id for it; the caller gives it one.
     *
     * A file that names this app's id is one this app wrote, and its words are read as they
     * stand: blank lines in the notes, a "<" in a step and a step that begins "1." all come back
     * as they were typed. Only a file without one goes through the web page reader's cleaning.
     * The method has no mark for the end of a part, so steps that followed a part without one
     * of their own come back as the last steps of that part.
     */
    fun fromJson(text: String): Recipe? = read(text, asWritten = true)

    private fun read(text: String, asWritten: Boolean): Recipe? {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val cleaned = JsonLd.fromObject(o) ?: return null
        val id = (o.opt("identifier") as? String).orEmpty().trim()
        val read = if (asWritten && id.isNotEmpty()) ownWords(o, cleaned) else cleaned
        return read.copy(
            id = id,
            recipeYield = yieldOf(o),
            prepTime = timeOf(o, "prepTime", read.prepTime),
            cookTime = timeOf(o, "cookTime", read.cookTime),
            totalTime = timeOf(o, "totalTime", read.totalTime),
            steps = parts(read.steps),
            keywords = read.keywords.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", "),
        )
    }

    /**
     * The words of a file this app wrote, as they stand in it, wherever they are plain strings
     * as Cookbook keeps them; anything else is left as the page reader made it.
     */
    private fun ownWords(o: JSONObject, cleaned: Recipe): Recipe = cleaned.copy(
        name = (o.opt("name") as? String)?.takeIf { it.isNotBlank() } ?: cleaned.name,
        description = o.opt("description") as? String ?: cleaned.description,
        ingredients = strings(o.opt("recipeIngredient")) ?: cleaned.ingredients,
        steps = strings(o.opt("recipeInstructions"))?.map { Step(it) } ?: cleaned.steps,
        notes = o.opt("notes") as? String ?: cleaned.notes,
        author = (o.opt("author") as? JSONObject)?.opt("name") as? String ?: cleaned.author,
        category = o.opt("recipeCategory") as? String ?: cleaned.category,
        cuisine = o.opt("recipeCuisine") as? String ?: cleaned.cuisine,
    )

    /** A list that is all strings, the blank ones left out; null when it is not one. */
    private fun strings(v: Any?): List<String>? {
        if (v !is JSONArray) return null
        val out = ArrayList<String>(v.length())
        for (i in 0 until v.length()) {
            val e = v.opt(i) as? String ?: return null
            if (e.isNotBlank()) out += e
        }
        return out
    }

    /**
     * A time as Cookbook keeps it: a duration it can read, or null with the words ("overnight")
     * beside it in a field of this app's own, as the yield's words are, so a pull keeps them.
     */
    private fun putTime(o: JSONObject, key: String, value: String) {
        if (Durations.isoSeconds(value) != null) {
            o.put(key, value)
            o.remove(key + TIME_TEXT)
        } else {
            o.put(key, JSONObject.NULL)
            if (value.isNotBlank()) o.put(key + TIME_TEXT, value.trim()) else o.remove(key + TIME_TEXT)
        }
    }

    /** The time read, or its words while Cookbook's own value is still empty. */
    private fun timeOf(o: JSONObject, key: String, read: String): String =
        compact(read).ifEmpty { if (o.isNull(key)) (o.opt(key + TIME_TEXT) as? String).orEmpty().trim() else "" }

    /** The words of the yield while they still agree with Cookbook's number, else the number. */
    private fun yieldOf(o: JSONObject): String {
        val number = when (val y = o.opt("recipeYield")) {
            is Number -> y.toInt().takeIf { it > 0 }
            is String -> servingsOf(y)
            is JSONArray -> servingsOf((0 until y.length()).joinToString(" ") { y.opt(it).toString() })
            else -> null
        }
        val words = (o.opt(YIELD_TEXT) as? String).orEmpty().trim()
        if (words.isNotEmpty() && (number == null || servingsOf(words) == number)) return words
        val raw = o.opt("recipeYield")
        if (raw is String && raw.isNotBlank() && servingsOf(raw) == number) return raw.trim()
        return number?.toString().orEmpty()
    }

    /** Steps read from Cookbook's flat list, a "## name" line starting a part. */
    private fun parts(steps: List<Step>): List<Step> {
        val out = mutableListOf<Step>()
        var marked: String? = null
        for (s in steps) {
            val m = PART_LINE.matchEntire(s.text)
            if (m != null) {
                marked = m.groupValues[1].trim()
                continue
            }
            out += Step(s.text, marked ?: s.section)
        }
        return out
    }

    /** "PT0H25M0S", as Cookbook writes a time, as "PT25M", as this app does. */
    fun compact(iso: String): String {
        val seconds = Durations.isoSeconds(iso) ?: return if (iso.startsWith("P", true)) "" else iso
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return "PT" + (if (h > 0) "${h}H" else "") + (if (m > 0) "${m}M" else "") + (if (s > 0) "${s}S" else "")
    }

    /**
     * Whether two recipes say the same thing, as Cookbook would keep them: the same once both
     * have been through its shape, whatever their ids and dates. Both are compared as the page
     * reader cleans them, as before recipes were read word for word, so that a recipe kept here
     * from an earlier, cleaned read still matches the same recipe read word for word now, and a
     * sync after the update makes no copies of it.
     */
    fun same(a: Recipe, b: Recipe): Boolean {
        fun canon(r: Recipe) = read(toJson(r.copy(id = "", created = "", modified = "")).toString(), asWritten = false)
            ?.copy(id = "", created = "", modified = "")
        return canon(a) == canon(b)
    }

    /**
     * The folder Cookbook gives a recipe of this name: the characters it will not have in a
     * folder name made underscores, and anything over 100 characters cut to 97 and "___". The
     * same rule as Cookbook's own, so a recipe made in either is found by name in the other.
     */
    fun folderName(name: String): String {
        var n = name.trim().replace(FORBIDDEN, "_")
        val points = n.codePointCount(0, n.length)
        if (points > MAX_FOLDER) n = n.substring(0, n.offsetByCodePoints(0, MAX_FOLDER - 3)) + "___"
        // A name Nextcloud would hide or refuse.
        if (n.isEmpty() || n == "." || n == "..") n = "Recipe"
        if (n.startsWith(".")) n = "_" + n.substring(1)
        return n
    }

    /** [wanted], or "wanted (2)" and on, whichever is not in [taken] (compared ignoring case). */
    fun unique(wanted: String, taken: Collection<String>): String {
        val lower = taken.map { it.lowercase() }.toSet()
        if (wanted.lowercase() !in lower) return wanted
        var i = 2
        while ("$wanted ($i)".lowercase() in lower) i++
        return "$wanted ($i)"
    }

    private const val YIELD_TEXT = "recipeYieldText"
    private const val TIME_TEXT = "Text"
    private const val PART = "## "
    private const val MAX_FOLDER = 100
    private val PART_LINE = Regex("""^#{1,6}\s+(.+)$""")
    private val FORBIDDEN = Regex("""[/:?!"\\'|&^#\u0000-\u001f]""")
}
