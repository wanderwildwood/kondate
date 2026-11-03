package com.wanderwildwood.kondate.data

import com.wanderwildwood.kondate.importer.JsonLd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The ticked ingredients ride along in the file of every recipe, and come back from it. */
class TicksTest {

    private val soup = Recipe(id = "soup-1", name = "Lentil Soup", ingredients = listOf("1 onion", "2 carrots", "200 g lentils"))
    private val bread = Recipe(id = "bread-2", name = "Soda Bread", ingredients = listOf("500 g flour", "400 ml buttermilk"))

    @Test
    fun ticksGoOutAndComeBack() {
        val text = recipesDocument(listOf(soup, bread)) { if (it == "soup-1") setOf(0, 2) else emptySet() }.toString(1)
        val back = JsonLd.recipesAndTicksInJson(text)
        assertEquals(listOf("soup-1", "bread-2"), back.map { it.first.id })
        assertEquals(setOf(0, 2), back[0].second)
        assertNull(back[1].second)
    }

    @Test
    fun aRecipeSavedOnItsOwnCarriesNoTicks() {
        assertFalse(recipesDocument(listOf(soup)).toString().contains(Ticks.KEY))
    }

    @Test
    fun anOldFileStillReadsWithNoTicks() {
        val old = """{"@context":"https://schema.org","@graph":[{"@type":"Recipe","identifier":"soup-1","name":"Lentil Soup","recipeIngredient":["1 onion"]}]}"""
        val back = JsonLd.recipesAndTicksInJson(old).single()
        assertEquals("Lentil Soup", back.first.name)
        assertNull(back.second)
    }

    @Test
    fun brokenTicksAreLeftOut() {
        val text = """{"@graph":[{"@type":"Recipe","identifier":"soup-1","name":"Lentil Soup","recipeIngredient":["a","b"],"kondateTicked":[1,"x",-3,1]}]}"""
        assertEquals(setOf(1), JsonLd.recipesAndTicksInJson(text).single().second)
        val notAList = """{"@graph":[{"@type":"Recipe","identifier":"soup-1","name":"Lentil Soup","kondateTicked":"0"}]}"""
        assertNull(JsonLd.recipesAndTicksInJson(notAList).single().second)
    }

    @Test
    fun theFileTicksWinWhenItHasThem() {
        assertEquals(setOf(1), Ticks.afterBringIn(soup, setOf(0), soup, setOf(1)))
        // A tick past the end of the ingredients points at nothing.
        assertEquals(setOf(2), Ticks.afterBringIn(null, emptySet(), soup, setOf(2, 3, 9)))
    }

    @Test
    fun withoutFileTicksTheOnesHereStayUnlessTheIngredientsChanged() {
        assertEquals(setOf(0), Ticks.afterBringIn(soup, setOf(0), soup.copy(name = "Red Lentil Soup"), null))
        assertEquals(emptySet<Int>(), Ticks.afterBringIn(soup, setOf(0), soup.copy(ingredients = listOf("1 leek")), null))
        assertEquals(emptySet<Int>(), Ticks.afterBringIn(null, emptySet(), bread, null))
    }
}
