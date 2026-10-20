package com.wanderwildwood.kondate.sync

import com.wanderwildwood.kondate.data.Recipe
import com.wanderwildwood.kondate.data.Step
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The files under test/resources/cookbook are made up, in the shape Nextcloud Cookbook leaves a
 * recipe.json: one saved from Cookbook's editor after this app wrote it (Cookbook's folder id in
 * `id`, a whole-number yield, times as PT0H20M0S, the method flat with "## " parts, keywords
 * joined by commas, and its own tools and nutrition), one made in Cookbook and never touched
 * here, and one whose yield was changed in Cookbook after this app wrote words for it.
 */
class CookbookTest {

    private fun file(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("cookbook/$name")!!.use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun readsWhatCookbookWrites() {
        val r = Cookbook.fromJson(file("edited-in-cookbook.json"))!!
        assertEquals("3f1d2c9e-0b7a-4e7b-9d61-5a2f0c8e4b11", r.id)
        assertEquals("Barley Stew", r.name)
        assertEquals("6 bowls", r.recipeYield)
        assertEquals("PT20M", r.prepTime)
        assertEquals("PT1H30M", r.cookTime)
        assertEquals("", r.totalTime)
        assertEquals("barley, stew, winter", r.keywords)
        assertEquals("Main", r.category)
        assertEquals("Better the next day.", r.notes)
        assertEquals(4, r.ingredients.size)
        assertEquals(
            listOf(
                Step("Soften the onion and carrots in the pot.", "For the stew"),
                Step("Add the barley and stock and simmer for 90 minutes.", "For the stew"),
                Step("Season and ladle into bowls.", "To serve"),
            ),
            r.steps,
        )
        assertEquals("2026-10-02T09:15:00+0000", r.modified)
        assertEquals("2026-09-30T18:00:00+0000", r.created)
    }

    @Test
    fun aRecipeMadeInCookbookHasNoIdOfOurs() {
        val r = Cookbook.fromJson(file("made-in-cookbook.json"))!!
        assertEquals("", r.id)
        assertEquals("Oat Flapjacks", r.name)
        assertEquals("12", r.recipeYield)
        assertEquals(12, r.servings)
        assertEquals("PT35M", r.totalTime)
        assertEquals(listOf("", ""), r.steps.map { it.section })
    }

    @Test
    fun aYieldChangedInCookbookBeatsOldWords() {
        val r = Cookbook.fromJson(file("yield-changed-in-cookbook.json"))!!
        assertEquals("8", r.recipeYield)
        // HowToSteps, which another app might write, read as well.
        assertEquals(listOf(Step("Whisk and fry.")), r.steps)
    }

    @Test
    fun writesCookbooksShape() {
        val r = Recipe(
            id = "a1b2",
            name = "Lemon Rice",
            recipeYield = "4 servings",
            prepTime = "PT10M",
            cookTime = "PT20M",
            ingredients = listOf("300g rice", "1 lemon"),
            steps = listOf(Step("Rinse the rice."), Step("Cook it.", "For the rice"), Step("Zest the lemon over.", "To finish")),
            notes = "Less salt next time.",
            keywords = "rice, lemon",
            created = "2026-10-01T10:00:00-04:00",
            modified = "2026-10-03T11:30:00-04:00",
        )
        val o = Cookbook.toJson(r)
        assertEquals("http://schema.org", o.getString("@context"))
        assertEquals("Recipe", o.getString("@type"))
        assertEquals("a1b2", o.getString("identifier"))
        assertEquals(4, o.getInt("recipeYield"))
        assertEquals("4 servings", o.getString("recipeYieldText"))
        assertEquals("rice,lemon", o.getString("keywords"))
        assertTrue(o.isNull("totalTime"))
        assertEquals(
            listOf("Rinse the rice.", "## For the rice", "Cook it.", "## To finish", "Zest the lemon over."),
            o.getJSONArray("recipeInstructions").let { a -> (0 until a.length()).map { a.getString(it) } },
        )
        assertEquals("Less salt next time.", o.getString("notes"))
        assertEquals("2026-10-03T11:30:00-04:00", o.getString("dateModified"))
        assertEquals(0, o.getJSONArray("tool").length())
        // Back again, unchanged.
        assertEquals(r, Cookbook.fromJson(o.toString()))
    }

    @Test
    fun aPlainNumberYieldHasNoWordsBesideIt() {
        val o = Cookbook.toJson(Recipe(id = "x", name = "Toast", recipeYield = "2"))
        assertEquals(2, o.getInt("recipeYield"))
        assertFalse(o.has("recipeYieldText"))
        assertTrue(Cookbook.toJson(Recipe(id = "x", name = "Toast")).isNull("recipeYield"))
    }

    @Test
    fun keepsWhatCookbookAddedWhenWritingOverIt() {
        val raw = file("edited-in-cookbook.json")
        val r = Cookbook.fromJson(raw)!!.copy(name = "Barley and Leek Stew", notes = "")
        val o = Cookbook.toJson(r, JSONObject(raw))
        assertEquals("4211", o.getString("id"))
        assertEquals(JSONArray(listOf("Large pot")).toString(), o.getJSONArray("tool").toString())
        assertEquals("420 kcal", o.getJSONObject("nutrition").getString("calories"))
        assertTrue(o.has("datePublished"))
        assertEquals("Barley and Leek Stew", o.getString("name"))
        // A note cleared here is cleared there.
        assertFalse(o.has("notes"))
    }

    @Test
    fun sameIgnoresIdsDatesAndCookbooksSpelling() {
        val a = Cookbook.fromJson(file("edited-in-cookbook.json"))!!
        val b = a.copy(id = "other", modified = "2026-10-19T08:00:00-04:00", keywords = "barley,stew, winter", prepTime = "PT0H20M0S")
        assertTrue(Cookbook.same(a, b))
        assertFalse(Cookbook.same(a, a.copy(ingredients = a.ingredients + "salt")))
    }

    @Test
    fun notAJsonRecipe() {
        assertNull(Cookbook.fromJson("<html></html>"))
        assertNull(Cookbook.fromJson("{}"))
    }

    @Test
    fun folderNamesFollowCookbooksRule() {
        assertEquals("Pancakes", Cookbook.folderName("Pancakes"))
        assertEquals("Mac _ Cheese", Cookbook.folderName("Mac & Cheese"))
        assertEquals("What_ No eggs_", Cookbook.folderName("What? No eggs!"))
        assertEquals("1_2 Rolls_ Mum_s", Cookbook.folderName("1/2 Rolls: Mum's"))
        assertEquals("_ a_b_c_d_e_", Cookbook.folderName("# a\\b|c^d\"e#"))
        assertEquals("Crème brûlée", Cookbook.folderName("  Crème brûlée "))
        assertEquals("Recipe", Cookbook.folderName(""))
        assertEquals("_hidden", Cookbook.folderName(".hidden"))
        val long = "Ä".repeat(120)
        val cut = Cookbook.folderName(long)
        assertEquals("Ä".repeat(97) + "___", cut)
        // Four-byte characters count as one, as PHP's mb_substr counts them.
        assertEquals("🍲".repeat(97) + "___", Cookbook.folderName("🍲".repeat(101)))
    }

    @Test
    fun uniqueNames() {
        assertEquals("Pancakes", Cookbook.unique("Pancakes", setOf("Waffles")))
        assertEquals("Pancakes (2)", Cookbook.unique("Pancakes", setOf("pancakes")))
        assertEquals("Pancakes (3)", Cookbook.unique("Pancakes", setOf("Pancakes", "Pancakes (2)")))
    }
}
