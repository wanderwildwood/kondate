package com.wanderwildwood.kondate.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceLinkTest {
    @Test fun aWebPageIsOpened() {
        assertTrue(isWebAddress("https://example.org/soup"))
        assertTrue(isWebAddress("HTTP://example.org"))
    }

    @Test fun anyOtherLinkIsNot() {
        assertFalse(isWebAddress("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(isWebAddress("someapp://open?item=1"))
        assertFalse(isWebAddress("javascript:alert(1)"))
        assertFalse(isWebAddress("file:///sdcard/recipes.json"))
        assertFalse(isWebAddress(" https://example.org"))
        assertFalse(isWebAddress("https://"))
        assertFalse(isWebAddress(""))
    }
}
