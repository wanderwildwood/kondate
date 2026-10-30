package com.wanderwildwood.kondate.importer

import com.wanderwildwood.kondate.data.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PastedRecipeTest {

    private val plantBased = """
        Creamy Tomato Pasta

        Serves 4
        Prep Time: 10 mins  Cook Time: 20 mins  Total Time: 30 mins

        Ingredients
        • 250 g (9 oz) penne
        • 1 tbsp olive oil
        • 2 cloves garlic, minced
        • 400 g canned tomatoes
        • 1/2 cup cashew cream
        • salt and pepper

        Instructions
        1. Boil the penne in salted water until tender.
        2. Fry the garlic in the oil for 1 minute.
        3. Add the tomatoes and simmer for 10 minutes.
        4. Stir in the cashew cream and the drained pasta.

        Notes
        Keeps 3 days in the fridge.

        Nutrition
        Calories: 420
    """.trimIndent()

    @Test
    fun aTypicalPageIsSortedIntoItsParts() {
        val r = PastedRecipe.parse(plantBased)
        assertEquals("Creamy Tomato Pasta", r.name)
        assertEquals("4 servings", r.recipeYield)
        assertEquals("PT10M", r.prepTime)
        assertEquals("PT20M", r.cookTime)
        assertEquals("PT30M", r.totalTime)
        assertEquals(
            listOf("250 g (9 oz) penne", "1 tbsp olive oil", "2 cloves garlic, minced", "400 g canned tomatoes", "1/2 cup cashew cream", "salt and pepper"),
            r.ingredients,
        )
        assertEquals(4, r.steps.size)
        assertEquals(Step("Boil the penne in salted water until tender."), r.steps[0])
        assertEquals(Step("Stir in the cashew cream and the drained pasta."), r.steps[3])
        assertEquals("Keeps 3 days in the fridge.\nNutrition\nCalories: 420", r.notes)
    }

    @Test
    fun methodHeadingAndMetricUnitsWithBullets() {
        val r = PastedRecipe.parse(
            """
            Victoria Sponge
            Yield: 8 slices
            INGREDIENTS:
            - 200g butter, softened
            - 200g caster sugar
            - 4 eggs
            - 200g self-raising flour
            METHOD:
            - Heat the oven to 180C.
            - Beat the butter and sugar until pale.
            - Fold in the flour and bake for 25 minutes.
            """.trimIndent(),
        )
        assertEquals("Victoria Sponge", r.name)
        assertEquals("8 slices", r.recipeYield)
        assertEquals(listOf("200g butter, softened", "200g caster sugar", "4 eggs", "200g self-raising flour"), r.ingredients)
        assertEquals(listOf("Heat the oven to 180C.", "Beat the butter and sugar until pale.", "Fold in the flour and bake for 25 minutes."), r.steps.map { it.text })
        assertEquals("", r.notes)
    }

    @Test
    fun directionsHeadingUsUnitsAndStepWords() {
        val r = PastedRecipe.parse(
            """
            ## Grandma's Chili
            Servings: 6
            Prep: 15 minutes
            Cook: 1 hour 30 minutes
            ### Ingredients
            2 lbs ground beef
            1 (15 oz) can kidney beans
            ½ tsp cumin
            **Directions**
            Step 1: Brown the beef in a large pot.
            Step 2: Add beans, cumin and 2 cups water.
            Step 3 - Simmer for 90 minutes.
            """.trimIndent(),
        )
        assertEquals("Grandma's Chili", r.name)
        assertEquals("6 servings", r.recipeYield)
        assertEquals("PT15M", r.prepTime)
        assertEquals("PT1H30M", r.cookTime)
        assertEquals(listOf("2 lbs ground beef", "1 (15 oz) can kidney beans", "½ tsp cumin"), r.ingredients)
        assertEquals(listOf("Brown the beef in a large pot.", "Add beans, cumin and 2 cups water.", "Simmer for 90 minutes."), r.steps.map { it.text })
    }

    @Test
    fun junkBeforeAndAfterGoesToNotes() {
        val r = PastedRecipe.parse(
            """
            Easy Lentil Soup
            Jump to Recipe
            By Sam Cook | 2,431 reviews
            Advertisement
            Ingredients
            1 cup red lentils
            1 onion, diced
            Instructions
            Simmer everything for 25 minutes.
            Blend if you like.
            Comments
            Great recipe! - Anna
            Related recipes
            Lentil stew
            """.trimIndent(),
        )
        assertEquals("Easy Lentil Soup", r.name)
        assertEquals(listOf("1 cup red lentils", "1 onion, diced"), r.ingredients)
        assertEquals(listOf("Simmer everything for 25 minutes.", "Blend if you like."), r.steps.map { it.text })
        assertEquals(
            "Jump to Recipe\nBy Sam Cook | 2,431 reviews\nAdvertisement\nComments\nGreat recipe! - Anna\nRelated recipes\nLentil stew",
            r.notes,
        )
    }

    @Test
    fun quantitiesFirstWithoutAnyHeading() {
        val r = PastedRecipe.parse(
            """
            Quick Vinaigrette
            3 tbsp olive oil
            1 tbsp lemon juice
            a pinch of salt
            1. Whisk everything together in a jar.
            2. Shake before serving.
            Lovely on bitter leaves.
            """.trimIndent(),
        )
        assertEquals(listOf("3 tbsp olive oil", "1 tbsp lemon juice", "a pinch of salt"), r.ingredients)
        assertEquals(listOf("Whisk everything together in a jar.", "Shake before serving."), r.steps.map { it.text })
        assertEquals("Lovely on bitter leaves.", r.notes)
    }

    @Test
    fun numberOnOwnLineThenStepOnNextAndCheckboxes() {
        val r = PastedRecipe.parse(
            "Porridge\ningredients\n[ ] 1 cup oats\n[x] 2 cups milk\ndirections:\n1\nHeat the milk.\n2\nStir in the oats.",
        )
        assertEquals(listOf("1 cup oats", "2 cups milk"), r.ingredients)
        assertEquals(listOf("Heat the milk.", "Stir in the oats."), r.steps.map { it.text })
    }

    @Test
    fun aStepStartingWithCookIsNotATimeLine() {
        val r = PastedRecipe.parse("Rice\nIngredients\n1 cup rice\nMethod\nCook 15 minutes until tender.\nServes 4 with a salad.")
        assertEquals("", r.cookTime)
        assertEquals("", r.recipeYield)
        assertEquals(listOf("Cook 15 minutes until tender.", "Serves 4 with a salad."), r.steps.map { it.text })
    }

    @Test
    fun windowsLineEndingsAndWordTimes() {
        val r = PastedRecipe.parse("Bread\r\nPrep Time: overnight\r\nIngredients\r\n500 g flour\r\nSteps\r\nKnead.")
        assertEquals("Bread", r.name)
        assertEquals("overnight", r.prepTime)
        assertEquals(listOf("500 g flour"), r.ingredients)
        assertEquals(listOf("Knead."), r.steps.map { it.text })
    }

    @Test
    fun nothingButOneLineIsJustAName() {
        val r = PastedRecipe.parse("  \n\n  Soup  \n")
        assertEquals("Soup", r.name)
        assertTrue(r.ingredients.isEmpty() && r.steps.isEmpty())
        assertEquals("", PastedRecipe.parse("").name)
    }

    @Test
    fun unrecognisedTextKeepsEveryWordInNotes() {
        val r = PastedRecipe.parse("Grandma's way\nMix it all and bake until it smells right.\nServe warm.")
        assertEquals("Mix it all and bake until it smells right.\nServe warm.", r.notes)
    }
}
