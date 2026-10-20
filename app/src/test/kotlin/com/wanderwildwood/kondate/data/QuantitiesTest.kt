package com.wanderwildwood.kondate.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuantitiesTest {

    @Test
    fun reading() {
        assertEquals(2.0, Quantities.parse("2")!!, 1e-9)
        assertEquals(1.5, Quantities.parse("1.5")!!, 1e-9)
        assertEquals(1.5, Quantities.parse("1,5")!!, 1e-9)
        assertEquals(0.5, Quantities.parse("1/2")!!, 1e-9)
        assertEquals(1.5, Quantities.parse("1 1/2")!!, 1e-9)
        assertEquals(0.5, Quantities.parse("½")!!, 1e-9)
        assertEquals(1.75, Quantities.parse("1¾")!!, 1e-9)
        assertEquals(0.75, Quantities.parse("3⁄4")!!, 1e-9)
        assertNull(Quantities.parse("a pinch"))
        assertNull(Quantities.parse("1/0"))
    }

    @Test
    fun writing() {
        assertEquals("3", Quantities.write(3.0))
        assertEquals("1 1/2", Quantities.write(1.5))
        assertEquals("1/3", Quantities.write(1.0 / 3))
        assertEquals("2 2/3", Quantities.write(8.0 / 3))
        assertEquals("3/4", Quantities.write(0.75))
        assertEquals("1.5", Quantities.write(1.5, decimal = true))
        assertEquals("0.1", Quantities.write(0.1))
        assertEquals("1/8", Quantities.write(0.13))
        // Twenty and over, whole numbers.
        assertEquals("188", Quantities.write(187.5))
        assertEquals("3", Quantities.write(2.995))
    }

    @Test
    fun scalingLines() {
        assertEquals("4 cups flour", Quantities.scale("2 cups flour", 2.0))
        assertEquals("3 tsp salt", Quantities.scale("1 1/2 tsp salt", 2.0))
        assertEquals("3/4 tsp salt", Quantities.scale("1 1/2 tsp salt", 0.5))
        assertEquals("1 onion", Quantities.scale("½ onion", 2.0))
        assertEquals("800g tomatoes", Quantities.scale("400g tomatoes", 2.0))
        assertEquals("3 kg potatoes", Quantities.scale("1.5 kg potatoes", 2.0))
        assertEquals("2.25 kg potatoes", Quantities.scale("1.5 kg potatoes", 1.5))
        assertEquals("4-6 cloves garlic", Quantities.scale("2-3 cloves garlic", 2.0))
        assertEquals("4 to 6 cloves garlic", Quantities.scale("2 to 3 cloves garlic", 2.0))
        assertEquals("- 6 eggs", Quantities.scale("- 3 eggs", 2.0))
        assertEquals("1 1/2 eggs", Quantities.scale("3 eggs", 0.5))
        // Only the head: the tin's size is a size, not an amount.
        assertEquals("2 tin (400 g) tomatoes", Quantities.scale("1 tin (400 g) tomatoes", 2.0))
        // "1 tomato" is not a range to something.
        assertEquals("2 tomato", Quantities.scale("1 tomato", 2.0))
        // A unit touching a fraction gets a space once the fraction is written out.
        assertEquals("2 1/4 cups milk", Quantities.scale("1½ cups milk", 1.5))
    }

    @Test
    fun linesLeftAlone() {
        assertEquals("Salt to taste", Quantities.scale("Salt to taste", 2.0))
        assertEquals("a pinch of nutmeg", Quantities.scale("a pinch of nutmeg", 3.0))
        assertEquals("1,000 g flour", Quantities.scale("1,000 g flour", 2.0))
        assertEquals("2 cups flour", Quantities.scale("2 cups flour", 1.0))
    }

    @Test
    fun servingsFromYield() {
        assertEquals(4, servingsOf("4"))
        assertEquals(4, servingsOf("4 servings"))
        assertEquals(4, servingsOf("Serves 4-6"))
        assertEquals(12, servingsOf("12 rolls"))
        assertNull(servingsOf("a crowd"))
        assertNull(servingsOf(""))
    }
}
