package com.wanderwildwood.kondate.importer

import com.wanderwildwood.kondate.data.Recipe
import com.wanderwildwood.kondate.data.Step
import com.wanderwildwood.kondate.data.recipesDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pages under test/resources/pages are made up, in the shapes real recipe sites publish:
 * a WordPress page whose Recipe sits in an @graph beside the page's other things, a plain array
 * with a two-type Recipe, a block with old-browser wrapping and a raw line break, microdata, and a
 * page with no recipe at all.
 */
class JsonLdTest {

    private fun page(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("pages/$name")!!.use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun wordpressGraph() {
        val r = JsonLd.recipesInHtml(page("wordpress-graph.html"), "https://kitchen.example/lentil-soup/").single()
        assertEquals("Lentil Soup With Lemon", r.name)
        // Entities decoded, including the &#39; that came through twice.
        assertEquals("A plain soup for a cold evening & the next day's lunch.", r.description)
        // The yield list says "4" and "4 servings"; the one with words wins.
        assertEquals("4 servings", r.recipeYield)
        assertEquals(4, r.servings)
        assertEquals("PT15M", r.prepTime)
        assertEquals("PT45M", r.cookTime)
        assertEquals("PT1H", r.totalTime)
        assertEquals(7, r.ingredients.size)
        assertEquals("½ lemon, juiced", r.ingredients[5])
        assertEquals(
            listOf(
                Step("Warm the oil in a large pot and soften the onion and carrots for 8 minutes.", "For the soup"),
                Step("Add the lentils and stock. Simmer for 25–30 minutes, until the lentils fall apart.", "For the soup"),
                Step("Stir in the lemon juice and season with salt.", "To finish"),
            ),
            r.steps,
        )
        assertEquals("https://kitchen.example/img/soup-1x1.jpg", r.image)
        assertEquals("Test Cook", r.author)
        assertEquals("Soup", r.category)
        assertEquals("lentil, soup, lemon", r.keywords)
        assertEquals("2024-01-02T10:00:00+00:00", r.created)
        // The page gives no url on the Recipe itself, so the page's address is kept.
        assertEquals("https://kitchen.example/lentil-soup/", r.url)
    }

    @Test
    fun plainArrayWithTwoTypes() {
        val r = JsonLd.recipesInHtml(page("plain-array.html"), "https://recipes.example/oat").single()
        assertEquals("Oat Biscuits", r.name)
        assertEquals("24", r.recipeYield)
        assertEquals("First Baker, Second Baker", r.author)
        assertEquals("https://recipes.example/oat.jpg", r.image)
        assertEquals("P0DT0H35M", r.totalTime)
        // "PT0M" is a page with no figure, not a recipe that takes no time.
        assertEquals("", r.cookTime)
        // One string of paragraphs, numbered: a step each, the numbers taken off.
        assertEquals(listOf("Heat the oven to 180C.", "Mix everything in a bowl.", "Bake for 12-15 minutes."), r.steps.map { it.text })
    }

    @Test
    fun messyBlock() {
        val r = Reader.fromHtml(page("messy.html"), "https://bread.example/flat")
        r as Reader.Result.Found
        assertEquals("Quick Flatbread", r.recipe.name)
        // A raw line break inside a string, which JSON forbids, did not lose the block.
        assertEquals("Soft, quick, and good with soup.", r.recipe.description)
        // The old "ingredients" name for the list.
        assertEquals(listOf("2 cups flour", "3/4 cup yoghurt", "1 tsp baking powder"), r.recipe.ingredients)
        assertEquals(3, r.recipe.steps.size)
        assertEquals(6, r.recipe.servings)
        // A time written in words is kept in words.
        assertEquals("10 minutes", r.recipe.prepTime)
    }

    @Test
    fun microdataWhenThereIsNoJsonLd() {
        val r = Reader.fromHtml(page("microdata.html"), "https://old.example/pudding")
        r as Reader.Result.Found
        assertEquals("Rice Pudding", r.recipe.name)
        assertEquals("PT5M", r.recipe.prepTime)
        assertEquals("PT2H", r.recipe.cookTime)
        assertEquals("Serves 4", r.recipe.recipeYield)
        assertEquals(listOf("100g pudding rice", "1 litre whole milk", "50g sugar"), r.recipe.ingredients)
        assertEquals(listOf("Heat the oven to 150C.", "Put everything in a dish and bake for 2 hours."), r.recipe.steps.map { it.text })
        assertEquals("https://old.example/pudding", r.recipe.url)
    }

    @Test
    fun noRecipeGivesTheTitle() {
        val r = Reader.fromHtml(page("no-recipe.html"), "https://blog.example/stew")
        assertEquals(Reader.Result.NoRecipe("My Grandmother's Stew & Dumplings"), r)
    }

    @Test
    fun notJsonGivesNothing() {
        assertTrue(JsonLd.recipesInJson("this is not json").isEmpty())
        assertTrue(JsonLd.recipesInJson("""{"@type":"Person","name":"Nobody"}""").isEmpty())
    }

    private val sample = Recipe(
        id = "6f0c3c2e-0000-4000-8000-000000000001",
        name = "Tomato & bean stew",
        description = "Quick.",
        recipeYield = "4 servings",
        prepTime = "PT10M",
        cookTime = "PT30M",
        ingredients = listOf("2 tbsp oil", "1 onion", "400g tin of beans"),
        steps = listOf(Step("Fry the onion."), Step("Add the beans.", "Stew"), Step("Simmer for 20 minutes.", "Stew"), Step("Serve.")),
        notes = "Less salt next time.\nGood cold.",
        url = "https://stew.example/",
        image = "https://stew.example/a.jpg",
        author = "A. Cook",
        category = "Dinner",
        created = "2026-10-19T12:00:00-04:00",
        modified = "2026-10-19T12:30:00-04:00",
    )

    @Test
    fun keptRecipeReadsBackTheSame() {
        val back = JsonLd.recipesInJson(sample.toJson().toString(1)).single()
        assertEquals(sample, back)
    }

    @Test
    fun exportReadsBackEveryRecipe() {
        val other = sample.copy(id = "6f0c3c2e-0000-4000-8000-000000000002", name = "Plain rice", steps = listOf(Step("Boil.")))
        val back = JsonLd.recipesInJson(recipesDocument(listOf(sample, other)).toString())
        assertEquals(listOf(sample, other), back)
    }

    @Test
    fun aRecipeWithNothingInItIsNone() {
        assertNull(JsonLd.fromObject(org.json.JSONObject("""{"@type":"Recipe"}""")))
    }

    @Test
    fun addressInSharedText() {
        assertEquals("https://a.example/soup", Reader.addressIn("Lentil soup — https://a.example/soup."))
        assertEquals("http://b.example/x?y=1", Reader.addressIn("http://b.example/x?y=1"))
        assertNull(Reader.addressIn("no address here"))
    }
}
