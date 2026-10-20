package com.wanderwildwood.kondate.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * One step of the method. [section] is the part of the recipe it belongs to ("For the sauce"),
 * when the recipe is written in parts; steps of one part follow each other.
 */
data class Step(val text: String, val section: String = "")

/**
 * A recipe, held in the shape schema.org gives one, so that what a recipe page publishes, what
 * this app keeps and what it writes to a file are the same thing. The field names follow
 * schema.org's Recipe where it has one; Nextcloud Cookbook keeps its recipes in that shape too,
 * which is what would let them be carried over later without a translation in between.
 *
 * The times are kept as ISO-8601 durations ("PT1H15M"), as schema.org writes them; [Durations]
 * reads them. [image] is kept so nothing a page gave is thrown away, and never shown.
 * [notes] is the one field of the app's own: schema.org has nowhere for "less salt next time".
 */
data class Recipe(
    val id: String,
    val name: String,
    val description: String = "",
    val recipeYield: String = "",
    val prepTime: String = "",
    val cookTime: String = "",
    val totalTime: String = "",
    val ingredients: List<String> = emptyList(),
    val steps: List<Step> = emptyList(),
    val notes: String = "",
    val url: String = "",
    val image: String = "",
    val author: String = "",
    val category: String = "",
    val cuisine: String = "",
    val keywords: String = "",
    val created: String = "",
    val modified: String = "",
) {
    /** How many it serves, as a number, when the yield says one: "4 servings", "4-6", "Serves 4". */
    val servings: Int? get() = servingsOf(recipeYield)

    /** The recipe as a schema.org Recipe object, the form it is kept and exported in. */
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("@context", "https://schema.org")
        o.put("@type", "Recipe")
        o.put("identifier", id)
        o.put("name", name)
        o.putIf("description", description)
        o.putIf("recipeYield", recipeYield)
        o.putIf("prepTime", prepTime)
        o.putIf("cookTime", cookTime)
        o.putIf("totalTime", totalTime)
        o.put("recipeIngredient", JSONArray(ingredients))
        o.put("recipeInstructions", instructionsJson())
        o.putIf("notes", notes)
        o.putIf("url", url)
        o.putIf("image", image)
        if (author.isNotEmpty()) o.put("author", JSONObject().put("@type", "Person").put("name", author))
        o.putIf("recipeCategory", category)
        o.putIf("recipeCuisine", cuisine)
        o.putIf("keywords", keywords)
        o.putIf("dateCreated", created)
        o.putIf("dateModified", modified)
        return o
    }

    /** The steps as HowToSteps, gathered under a HowToSection wherever they are in a part. */
    private fun instructionsJson(): JSONArray {
        val out = JSONArray()
        var i = 0
        while (i < steps.size) {
            val section = steps[i].section
            if (section.isEmpty()) {
                out.put(howToStep(steps[i].text))
                i++
                continue
            }
            val items = JSONArray()
            while (i < steps.size && steps[i].section == section) {
                items.put(howToStep(steps[i].text))
                i++
            }
            out.put(JSONObject().put("@type", "HowToSection").put("name", section).put("itemListElement", items))
        }
        return out
    }

    private fun howToStep(text: String) = JSONObject().put("@type", "HowToStep").put("text", text)

    private fun JSONObject.putIf(key: String, value: String) {
        if (value.isNotEmpty()) put(key, value)
    }
}

private val FIRST_NUMBER = Regex("""\d+""")

/** The first whole number in a yield, which is how many it serves when it says at all. */
fun servingsOf(text: String): Int? =
    FIRST_NUMBER.find(text)?.value?.toIntOrNull()?.takeIf { it in 1..999 }

/** A document holding every recipe, as written to a file: a schema.org graph of Recipes. */
fun recipesDocument(recipes: List<Recipe>): JSONObject =
    JSONObject()
        .put("@context", "https://schema.org")
        .put("@graph", JSONArray().also { a -> recipes.forEach { a.put(it.toJson().apply { remove("@context") }) } })
