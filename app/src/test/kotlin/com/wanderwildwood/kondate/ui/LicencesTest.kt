package com.wanderwildwood.kondate.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LicencesTest {
    private fun raw(name: String) = File("src/main/res/raw/$name").readText()

    @Test fun `the list of what the app is built with is not empty and every entry has a link`() {
        assertTrue(LIBRARIES.isNotEmpty())
        assertTrue(LIBRARIES.all { it.link.startsWith("https://") && it.licence.isNotBlank() && it.name.isNotBlank() })
    }

    @Test fun `the source link is the apps public repository`() {
        assertEquals("https://github.com/wanderwildwood/kondate", SOURCE_URL)
    }

    @Test fun `the bundled licence texts are whole and page by paragraph`() {
        val gpl = licenceParagraphs(raw("gpl_3_0.txt"))
        assertTrue(gpl.size > 100)
        assertTrue(gpl.first().contains("GNU GENERAL PUBLIC LICENSE"))
        assertTrue(gpl.last().contains("GNU Lesser General Public License"))
        assertTrue(licenceParagraphs(raw("apache_2_0.txt")).any { it.contains("Apache License") })
        assertTrue(licenceParagraphs(raw("ofl_1_1.txt")).any { it.contains("SIL OPEN FONT LICENSE") })
    }

    @Test fun `paragraphs are joined without losing words`() {
        assertEquals(listOf("one two", "three"), licenceParagraphs("one\ntwo\n\nthree\n"))
    }
}
