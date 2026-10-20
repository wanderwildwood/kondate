package com.wanderwildwood.kondate.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DurationsTest {

    @Test
    fun isoDurations() {
        assertEquals(15 * 60, Durations.isoSeconds("PT15M"))
        assertEquals(75 * 60, Durations.isoSeconds("PT1H15M"))
        assertEquals(35 * 60, Durations.isoSeconds("P0DT0H35M"))
        assertEquals(35 * 60, Durations.isoSeconds("P0Y0M0DT0H35M0.000S"))
        assertEquals(90 * 60, Durations.isoSeconds("PT90M"))
        assertEquals(90 * 60, Durations.isoSeconds("PT1.5H"))
        assertEquals(2 * 86400, Durations.isoSeconds("P2D"))
        assertEquals(30, Durations.isoSeconds("pt30s"))
        assertNull(Durations.isoSeconds("PT0M"))
        assertNull(Durations.isoSeconds("P"))
        assertNull(Durations.isoSeconds("PT"))
        assertNull(Durations.isoSeconds("20 minutes"))
        assertNull(Durations.isoSeconds(""))
    }

    @Test
    fun minutesBackAndForth() {
        assertEquals("PT1H15M", Durations.isoOfMinutes(75))
        assertEquals("PT2H", Durations.isoOfMinutes(120))
        assertEquals("PT5M", Durations.isoOfMinutes(5))
        assertEquals("", Durations.isoOfMinutes(0))
        assertEquals(75, Durations.minutesOf("PT1H15M"))
        assertNull(Durations.minutesOf("overnight"))
    }

    private fun found(text: String) = Durations.inText(text).map { it.seconds }

    @Test
    fun lengthsInSteps() {
        assertEquals(listOf(600), found("Simmer for 10 minutes."))
        assertEquals(listOf(75 * 60), found("Bake for 1 hour 15 minutes, until golden."))
        assertEquals(listOf(75 * 60), found("Roast for 1 hr and 15 min."))
        assertEquals(listOf(90 * 60), found("Leave to rise for 1 1/2 hours."))
        assertEquals(listOf(90 * 60), found("Leave to rise for 1½ hours."))
        assertEquals(listOf(30 * 60), found("Rest for half an hour."))
        assertEquals(listOf(90 * 60), found("Cook for an hour and a half."))
        assertEquals(listOf(3600), found("Chill for an hour."))
        assertEquals(listOf(30), found("Stir for 30 seconds."))
        assertEquals(listOf(5 * 60), found("Fry for five minutes."))
        assertEquals(listOf(10 * 60), found("Steam for 10m."))
    }

    @Test
    fun aRangeStartsAtItsFirst() {
        assertEquals(listOf(25 * 60), found("Simmer for 25-30 minutes."))
        assertEquals(listOf(25 * 60), found("Simmer for 25–30 minutes."))
        assertEquals(listOf(12 * 60), found("Bake for 12 to 15 mins."))
    }

    @Test
    fun severalInOneStep() {
        assertEquals(listOf(8 * 60, 2 * 60), found("Fry for 8 minutes, then add the garlic and fry for 2 minutes more."))
        // The same length twice is one button.
        assertEquals(listOf(5 * 60), found("Stir for 5 minutes, rest, then stir for 5 minutes again."))
    }

    @Test
    fun notLengths() {
        assertTrue(found("Heat the oven to 200C.").isEmpty())
        assertTrue(found("Cut into 5 m strips of pasta.").isEmpty())
        assertTrue(found("Add a minced onion.").isEmpty())
        assertTrue(found("Season, then serve.").isEmpty())
    }
}
