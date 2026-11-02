package com.wanderwildwood.kondate.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelsTest {

    private val soup = Recipe(id = "1", name = "Chickpea soup", keywords = "Soups, winter")
    private val tart = Recipe(id = "2", name = "Lemon tart", keywords = "desserts, Soups ,  Citrus  fruit")
    private val plain = Recipe(id = "3", name = "Toast")

    @Test
    fun parseTrimsCollapsesAndKeepsOrder() {
        assertEquals(listOf("Salads", "Italian", "Citrus fruit"), Labels.parse("  Salads ,Italian,\n Citrus   fruit "))
    }

    @Test
    fun parseDropsRepeatsIgnoringCaseAndAccents() {
        assertEquals(listOf("Crème brûlée", "Soups"), Labels.parse("Crème brûlée, creme brulee, Soups, SOUPS, ,"))
    }

    @Test
    fun parseOfNothingIsEmpty() {
        assertTrue(Labels.parse("").isEmpty())
        assertTrue(Labels.parse(" , ,\n").isEmpty())
    }

    @Test
    fun joinMakesTheKeywordsField() {
        assertEquals("Salads, italian", Labels.join(listOf(" Salads", "italian ", "salads", "Italian")))
    }

    @Test
    fun allListsEachLabelOnceAlphabetically() {
        assertEquals(listOf("Citrus fruit", "desserts", "Soups", "winter"), Labels.all(listOf(soup, tart, plain)))
    }

    @Test
    fun toggledAddsThenRemoves() {
        val added = Labels.toggled("Salads", "italian")
        assertEquals("Salads, italian", added)
        assertTrue(Labels.has(added, "Italian"))
        assertEquals("Salads", Labels.toggled(added, "ITALIAN"))
        assertFalse(Labels.has("Salads", "Italian"))
    }

    @Test
    fun filterShowsOnlyThatLabelsRecipes() {
        val all = listOf(soup, tart, plain)
        assertEquals(listOf(soup, tart), Labels.filter(all, "soups"))
        assertEquals(listOf(tart), Labels.filter(all, "Desserts"))
        assertTrue(Labels.filter(all, "Salads").isEmpty())
    }

    @Test
    fun filterWithNoLabelShowsEverything() {
        val all = listOf(soup, tart, plain)
        assertEquals(all, Labels.filter(all, null))
        assertEquals(all, Labels.filter(all, ""))
    }

    @Test
    fun filterDoesNotMatchPartOfALabel() {
        assertTrue(Labels.filter(listOf(tart), "fruit").isEmpty())
    }

    @Test
    fun sortedMergesKnownAndTyped() {
        assertEquals(listOf("Italian", "salads", "Soups"), Labels.sorted(listOf("Soups", "salads", "Italian", "Salads")))
    }
}
