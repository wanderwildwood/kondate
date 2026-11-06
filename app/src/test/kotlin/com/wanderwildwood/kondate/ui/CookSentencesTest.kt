package com.wanderwildwood.kondate.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CookSentencesTest {
    @Test
    fun aStepIsSplitIntoItsSentences() {
        assertEquals(
            listOf("Chop the onion.", "Fry it gently for 10 minutes!", "Is it soft?", "Add the stock"),
            sentencesOf("Chop the onion. Fry it gently for 10 minutes!  Is it soft? Add the stock"),
        )
    }

    @Test
    fun numbersAndShortTextStayWhole() {
        assertEquals(listOf("Bake at 180 degrees for 1.5 hours."), sentencesOf("Bake at 180 degrees for 1.5 hours."))
        assertEquals(listOf("Serve"), sentencesOf("  Serve  "))
    }
}
