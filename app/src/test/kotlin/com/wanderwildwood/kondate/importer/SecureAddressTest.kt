package com.wanderwildwood.kondate.importer

import com.wanderwildwood.kondate.sync.LoginFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureAddressTest {

    @Test fun httpIsUpgradedToHttps() {
        assertEquals("https://b.example/x?y=1", Reader.secure("http://b.example/x?y=1"))
        assertEquals("https://b.example/x", Reader.secure(" HTTP://b.example/x "))
    }

    @Test fun httpsIsLeftAlone() {
        assertEquals("https://b.example/x", Reader.secure("https://b.example/x"))
    }

    @Test fun plainIsRecognised() {
        assertTrue(Reader.isPlain("http://b.example"))
        assertFalse(Reader.isPlain("https://b.example"))
    }

    @Test fun serverTypedWithHttpIsPlain() {
        assertTrue(LoginFlow.isPlain("http://cloud.example"))
        assertTrue(LoginFlow.isPlain("  HTTP://192.168.0.5"))
        assertFalse(LoginFlow.isPlain("cloud.example"))
        assertFalse(LoginFlow.isPlain("https://cloud.example"))
    }

    @Test fun bareNameGetsHttps() {
        assertEquals("https://cloud.example", LoginFlow.normalise("cloud.example/"))
        assertEquals("https://cloud.example", LoginFlow.normalise("https://cloud.example"))
    }
}
