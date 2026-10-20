package com.wanderwildwood.kondate.importer

import com.wanderwildwood.kondate.data.Durations
import com.wanderwildwood.kondate.data.Recipe
import com.wanderwildwood.kondate.data.Step
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Recipes read from schema.org JSON-LD: what a recipe page publishes for search engines, and
 * what this app writes to a file. The same reader does both, since they are the same shape.
 *
 * Pages differ in everything schema.org leaves open, and this takes all the shapes seen in the
 * wild: a Recipe on its own, in an array, or inside an `@graph` with the page's other things; a
 * `@type` that is a list ("Recipe", "NewsArticle"); instructions as one string, a list of
 * strings, HowToSteps, or HowToSections of HowToSteps; a yield as a number, a string or a list
 * of both; an author or image as a string, an object or a list. Text has its HTML and its
 * entities taken out.
 */
object JsonLd {

    /** Every recipe in a page's JSON-LD blocks, in the order the page gives them. */
    fun recipesInHtml(html: String, pageUrl: String = ""): List<Recipe> =
        Html.ldJsonBlocks(html).flatMap { recipesInJson(it, pageUrl) }

    /**
     * Every recipe in one JSON text: an exported file, a single recipe shared as a file, or one
     * block of a page. Text that is not JSON gives none.
     */
    fun recipesInJson(text: String, pageUrl: String = ""): List<Recipe> {
        val root = parse(text) ?: return emptyList()
        val found = mutableListOf<JSONObject>()
        collect(root, found, 0)
        return found.mapNotNull { fromObject(it, pageUrl) }
    }

    /**
     * JSON as pages write it. Their blocks are often a little wrong: wrapped in an HTML comment
     * or CDATA for old browsers, or holding raw line breaks inside strings, which JSON forbids;
     * those are written as the escapes they should have been, so a method kept in one string
     * still comes apart into its steps.
     */
    internal fun parse(text: String): Any? {
        var s = text.trim()
        s = s.removePrefix("<!--").removeSuffix("-->").trim()
        s = s.removePrefix("//<![CDATA[").removeSuffix("//]]>").trim()
        s = s.removePrefix("<![CDATA[").removeSuffix("]]>").trim()
        s = escapeRawBreaks(s)
        return try {
            JSONTokener(s).nextValue().takeIf { it is JSONObject || it is JSONArray }
        } catch (_: Exception) {
            null
        }
    }

    /** Line breaks and tabs inside JSON strings, escaped; outside them they are only space. */
    private fun escapeRawBreaks(s: String): String {
        val out = StringBuilder(s.length + 16)
        var inString = false
        var escaped = false
        for (c in s) {
            if (inString) {
                when {
                    escaped -> { out.append(c); escaped = false }
                    c == '\\' -> { out.append(c); escaped = true }
                    c == '"' -> { out.append(c); inString = false }
                    c == '\n' -> out.append("\\n")
                    c == '\r' -> {}
                    c == '\t' -> out.append(' ')
                    else -> out.append(c)
                }
            } else {
                if (c == '"') inString = true
                out.append(c)
            }
        }
        return out.toString()
    }

    /** Recipes found anywhere under [node]: in arrays, in `@graph`, in a page's mainEntity. */
    private fun collect(node: Any?, out: MutableList<JSONObject>, depth: Int) {
        if (depth > 12) return
        when (node) {
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), out, depth + 1)
            is JSONObject -> {
                if (isType(node, "Recipe")) {
                    out += node
                    return
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = node.opt(k)
                    if (v is JSONObject || v is JSONArray) collect(v, out, depth + 1)
                }
            }
        }
    }

    /** Whether [o]'s `@type` names [type], alone, in a list, or as a full schema.org address. */
    private fun isType(o: JSONObject, type: String): Boolean {
        fun named(v: Any?): Boolean = v is String && (v == type || v.endsWith("/$type") || v.endsWith(":$type"))
        return when (val t = o.opt("@type")) {
            is JSONArray -> (0 until t.length()).any { named(t.opt(it)) }
            else -> named(t)
        }
    }

    /** One Recipe object as this app's recipe, or null when it has no name and nothing in it. */
    fun fromObject(o: JSONObject, pageUrl: String = ""): Recipe? {
        val name = text(o.opt("name")).ifEmpty { text(o.opt("headline")) }
        val ingredients = lines(o.opt("recipeIngredient")).ifEmpty { lines(o.opt("ingredients")) }
        val steps = mutableListOf<Step>()
        instructions(o.opt("recipeInstructions"), "", steps, 0)
        if (name.isEmpty() && ingredients.isEmpty() && steps.isEmpty()) return null
        return Recipe(
            id = text(o.opt("identifier")),
            name = name,
            description = text(o.opt("description")),
            recipeYield = yieldOf(o.opt("recipeYield")).ifEmpty { yieldOf(o.opt("yield")) },
            prepTime = time(o.opt("prepTime")),
            cookTime = time(o.opt("cookTime")),
            totalTime = time(o.opt("totalTime")),
            ingredients = ingredients,
            steps = steps,
            notes = plain(o.opt("notes")),
            url = urlOf(o.opt("url")).ifEmpty { pageUrl },
            image = imageOf(o.opt("image")),
            author = names(o.opt("author")),
            category = joined(o.opt("recipeCategory")),
            cuisine = joined(o.opt("recipeCuisine")),
            keywords = joined(o.opt("keywords")),
            created = text(o.opt("dateCreated")).ifEmpty { text(o.opt("datePublished")) },
            modified = text(o.opt("dateModified")),
        )
    }

    /**
     * The method, in order, into [out]. A string may hold several steps, one to a line or
     * paragraph; a HowToSection names the part its steps belong to; a HowToStep's own name is
     * used only where it has no text, since sites that give both mostly repeat the text as the
     * name, cut short.
     */
    private fun instructions(node: Any?, section: String, out: MutableList<Step>, depth: Int) {
        if (depth > 8) return
        when (node) {
            null, JSONObject.NULL -> {}
            is String -> stepLines(node).forEach { out += Step(it, section) }
            is JSONArray -> for (i in 0 until node.length()) instructions(node.opt(i), section, out, depth + 1)
            is JSONObject -> when {
                isType(node, "HowToSection") || (node.has("itemListElement") && !node.has("text")) -> {
                    val name = text(node.opt("name"))
                    instructions(node.opt("itemListElement"), name.ifEmpty { section }, out, depth + 1)
                }
                else -> {
                    // HowToStep, HowToDirection, HowToTip, or an object that just has text.
                    val t = node.opt("text") ?: node.opt("name")
                    if (t is String) stepLines(t).forEach { out += Step(it, section) }
                    else instructions(node.opt("itemListElement"), section, out, depth + 1)
                }
            }
        }
    }

    private val NUMBERED = Regex("""^(?:step\s*)?\d{1,2}\s*[.):]\s+""", RegexOption.IGNORE_CASE)

    /** A block of method text as steps: one a line, with a leading "1." or "Step 1:" taken off. */
    private fun stepLines(text: String): List<String> =
        Html.lines(text).map { it.replace(NUMBERED, "").trim() }.filter { it.isNotEmpty() }

    /** A string or a number as one line of plain text; anything else as nothing. */
    private fun text(v: Any?): String = when (v) {
        is String -> Html.text(v)
        is Number -> numberText(v)
        else -> ""
    }

    /** Text that may run to several paragraphs, kept as lines. */
    private fun plain(v: Any?): String = if (v is String) Html.lines(v).joinToString("\n") else ""

    private fun numberText(n: Number): String {
        val d = n.toDouble()
        return if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString()
    }

    /** A list of lines: each string of an array, or one string's lines. */
    private fun lines(v: Any?): List<String> = when (v) {
        is JSONArray -> (0 until v.length()).flatMap { i ->
            when (val e = v.opt(i)) {
                is String -> listOf(Html.text(e))
                is JSONObject -> listOf(text(e.opt("text") ?: e.opt("name")))
                else -> emptyList()
            }
        }.filter { it.isNotEmpty() }
        is String -> Html.lines(v)
        else -> emptyList()
    }

    /**
     * The yield as one string. A list is mostly the same yield twice ("4", "4 servings"), so the
     * one with words in it is taken, which says what the number counts.
     */
    private fun yieldOf(v: Any?): String = when (v) {
        is JSONArray -> {
            val all = (0 until v.length()).map { text(v.opt(it)) }.filter { it.isNotEmpty() }
            all.firstOrNull { s -> s.any { it.isLetter() } } ?: all.firstOrNull().orEmpty()
        }
        is JSONObject -> text(v.opt("value")).let { n -> listOf(n, text(v.opt("unitText"))).filter { it.isNotEmpty() }.joinToString(" ") }
        else -> text(v)
    }

    /**
     * A time as an ISO-8601 duration when it is one. A page that writes "20 mins" instead is
     * kept as written, and shown as written; one that writes "PT0M" has no time to give.
     */
    private fun time(v: Any?): String {
        val s = text(v).trim()
        if (s.isEmpty()) return ""
        if (s.startsWith("P", ignoreCase = true)) return if (Durations.isoSeconds(s) != null) s.uppercase() else ""
        return s
    }

    private fun urlOf(v: Any?): String = when (v) {
        is String -> v.trim()
        is JSONArray -> urlOf(v.opt(0))
        is JSONObject -> urlOf(v.opt("url") ?: v.opt("@id"))
        else -> ""
    }

    private fun imageOf(v: Any?): String = when (v) {
        is String -> v.trim()
        is JSONArray -> if (v.length() > 0) imageOf(v.opt(0)) else ""
        is JSONObject -> imageOf(v.opt("url") ?: v.opt("contentUrl"))
        else -> ""
    }

    /** People as one line: a name, an object's name, or a list of either. */
    private fun names(v: Any?): String = when (v) {
        is String -> Html.text(v)
        is JSONObject -> text(v.opt("name"))
        is JSONArray -> (0 until v.length()).map { names(v.opt(it)) }.filter { it.isNotEmpty() }.distinct().joinToString(", ")
        else -> ""
    }

    /** A word or a list of words as one line, separated by commas. */
    private fun joined(v: Any?): String = when (v) {
        is JSONArray -> (0 until v.length()).map { text(v.opt(it)) }.filter { it.isNotEmpty() }.distinct().joinToString(", ")
        else -> text(v)
    }
}
