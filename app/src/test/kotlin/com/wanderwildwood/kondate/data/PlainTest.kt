package com.wanderwildwood.kondate.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PlainTest {

    @Test
    fun stepsFromTheEditor() {
        val text = "Boil the pasta.\n\n# For the sauce\nFry the garlic.\nAdd the tomatoes.\n#\nServe."
        val steps = Plain.stepsFrom(text)
        assertEquals(
            listOf(Step("Boil the pasta."), Step("Fry the garlic.", "For the sauce"), Step("Add the tomatoes.", "For the sauce"), Step("Serve.")),
            steps,
        )
        assertEquals(steps, Plain.stepsFrom(Plain.stepsText(steps)))
    }

    @Test
    fun shoppingListIsAChecklist() {
        assertEquals("- [ ] 2 onions\n- [ ] 1 lemon\n", Plain.shoppingList(listOf("2 onions", "1 lemon")))
    }

    @Test
    fun recipeAsTextAtAScale() {
        val r = Recipe(
            id = "x", name = "Rice", recipeYield = "2",
            ingredients = listOf("1 cup rice", "2 cups water"),
            steps = listOf(Step("Rinse."), Step("Simmer for 12 minutes.", "Cooking")),
            url = "https://rice.example/",
        )
        val text = Plain.recipe(r, 2.0, "Serves 4", "Ingredients", "Method", "Notes")
        assertEquals(
            "Rice\nServes 4\n\nIngredients\n• 2 cup rice\n• 4 cups water\n\nMethod\n1. Rinse.\nCooking\n2. Simmer for 12 minutes.\n\nhttps://rice.example/\n",
            text,
        )
    }
}
