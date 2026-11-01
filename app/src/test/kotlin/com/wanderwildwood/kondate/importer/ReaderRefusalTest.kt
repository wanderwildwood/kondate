package com.wanderwildwood.kondate.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderRefusalTest {

    @Test
    fun theseAnswersMeanTheSiteTurnedTheAppAway() {
        for (code in listOf(401, 402, 403, 406, 429)) assertTrue("$code", Reader.refused(code))
        for (code in listOf(0, -1, 404, 410, 500, 503)) assertFalse("$code", Reader.refused(code))
    }

    @Test
    fun aBotCheckPageCountsAsRefused() {
        val html = "<html><head><title>Just a moment...</title></head><body>Checking your browser</body></html>"
        assertEquals(Reader.Result.Failed(403), Reader.fromHtml(html, "https://a.example/x"))
        val dd = "<html><head><title>x</title></head><script src=\"https://ct.captcha-delivery.com/c.js\"></script></html>"
        assertEquals(Reader.Result.Failed(403), Reader.fromHtml(dd, "https://a.example/x"))
    }

    @Test
    fun anOrdinaryPageWithoutARecipeIsNotRefused() {
        val r = Reader.fromHtml("<html><head><title>My blog</title></head><body>hello</body></html>", "https://a.example/x")
        assertEquals(Reader.Result.NoRecipe("My blog"), r)
    }

    @Test
    fun aSharedLinkIsReadByAddressButRecipeWordsAreNot() {
        assertTrue(Reader.isLinkShare("https://a.example/pasta"))
        assertTrue(Reader.isLinkShare("Best Pasta Ever\nhttps://a.example/pasta"))
        assertFalse(Reader.isLinkShare("Pasta\nIngredients\n1 cup flour\nMethod\nMix.\nSource: https://a.example/pasta"))
        assertFalse(Reader.isLinkShare("no address here"))
    }
}
