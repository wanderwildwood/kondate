package com.wanderwildwood.kondate.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OwnJsonTest {

    private val recipe = Recipe(
        id = "3f2a",
        name = "Soup <for two>",
        recipeYield = "2 bowls",
        prepTime = "PT10M",
        cookTime = "overnight",
        ingredients = listOf("1. a <b>big</b> onion", "salt & pepper"),
        steps = listOf(Step("1. Chop the onion."), Step("Stir <gently>.", "For the pot"), Step("2 minutes more.", "For the pot")),
        notes = "Less salt next time.\nOr none.",
        url = "https://example.org/soup",
        author = "Ada Whitlock",
        keywords = "soup, winter",
        created = "2026-11-01T10:00:00-04:00",
        modified = "2026-11-02T10:00:00-05:00",
    )

    @Test fun theAppsOwnRecipeComesBackWordForWord() {
        assertEquals(recipe, Recipe.fromOwnJson(recipe.toJson().toString(1)))
    }

    @Test fun aPageRecipeIsLeftForThePageReader() {
        assertNull(Recipe.fromOwnJson("""{"@type":"Recipe","name":"No id"}"""))
        assertNull(Recipe.fromOwnJson("""{"@type":"Recipe","identifier":"x","name":"n","recipeInstructions":["Mix."]}"""))
        assertNull(Recipe.fromOwnJson("not json"))
    }
}
