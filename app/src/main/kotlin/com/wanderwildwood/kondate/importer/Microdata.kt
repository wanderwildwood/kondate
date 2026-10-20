package com.wanderwildwood.kondate.importer

import com.wanderwildwood.kondate.data.Durations
import com.wanderwildwood.kondate.data.Recipe
import com.wanderwildwood.kondate.data.Step

/**
 * The older way a page marks up a recipe: schema.org microdata, `itemprop` attributes on the
 * page's own tags. Read only when a page has no JSON-LD, and read simply — the first value of
 * each property after the Recipe's `itemtype` — because a page this old is usually a plain one.
 * A page whose markup this cannot follow gives nothing, and the recipe is typed in instead.
 */
object Microdata {

    private val RECIPE = Regex("""(?i)itemtype\s*=\s*["']https?://schema\.org/Recipe["']""")

    fun recipeInHtml(html: String, pageUrl: String = ""): Recipe? {
        val at = RECIPE.find(html) ?: return null
        val body = html.substring(at.range.first)
        val name = values(body, "name").firstOrNull()?.let(Html::text).orEmpty()
        val ingredients = (values(body, "recipeIngredient").ifEmpty { values(body, "ingredients") })
            .map(Html::text).filter { it.isNotEmpty() }
        val steps = values(body, "recipeInstructions").flatMap(Html::lines).map { Step(it) }
        if (ingredients.isEmpty() && steps.isEmpty()) return null
        fun one(prop: String) = values(body, prop).firstOrNull()?.let(Html::text).orEmpty()
        fun time(prop: String) = one(prop).let { if (Durations.isoSeconds(it) != null) it.uppercase() else "" }
        return Recipe(
            id = "",
            name = name.ifEmpty { Html.title(html) },
            description = one("description"),
            recipeYield = one("recipeYield"),
            prepTime = time("prepTime"),
            cookTime = time("cookTime"),
            totalTime = time("totalTime"),
            ingredients = ingredients,
            steps = steps,
            url = pageUrl,
        )
    }

    private fun opening(prop: String) =
        Regex("""(?is)<([a-z][a-z0-9]*)\b([^>]*\bitemprop\s*=\s*["'](?:[^"']*\s)?$prop(?:\s[^"']*)?["'][^>]*)>""")

    private val CONTENT = Regex("""(?is)\b(?:content|datetime)\s*=\s*("([^"]*)"|'([^']*)')""")

    private val VOID = setOf("meta", "link", "img", "br", "input", "hr", "source")

    /**
     * Each value of [prop], in page order: a tag's `content` or `datetime` where it has one
     * (as `<meta itemprop="prepTime" content="PT10M">` does), else the HTML inside the tag up
     * to its own closing tag, counting tags of the same name opened inside it.
     */
    internal fun values(html: String, prop: String): List<String> {
        val out = mutableListOf<String>()
        for (m in opening(prop).findAll(html)) {
            val tag = m.groupValues[1].lowercase()
            val attrs = m.groupValues[2]
            val c = CONTENT.find(attrs)
            if (c != null) {
                out += c.groupValues[2].ifEmpty { c.groupValues[3] }
                continue
            }
            if (tag in VOID || attrs.trimEnd().endsWith("/")) continue
            inner(html, tag, m.range.last + 1)?.let { out += it }
        }
        return out
    }

    /** The HTML from [from] to the tag that closes a [tag] opened just before it. */
    private fun inner(html: String, tag: String, from: Int): String? {
        val either = Regex("""(?i)<(/?)$tag\b[^>]*>""")
        var depth = 1
        var i = from
        while (true) {
            val m = either.find(html, i) ?: return null
            if (m.groupValues[1].isEmpty()) depth++ else depth--
            if (depth == 0) return html.substring(from, m.range.first)
            i = m.range.last + 1
        }
    }
}
