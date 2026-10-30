package com.wanderwildwood.kondate.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StoredAccountTest {
    private val account = Account("https://cloud.example.org", "ann", "app-pass")
    private val seal = { s: String -> "enc:v1:" + s.reversed() }
    private val open = { s: String -> if (s.startsWith("enc:v1:")) s.removePrefix("enc:v1:").reversed() else s }

    @Test fun `an older plain account is read and handed back sealed`() {
        val r = readStoredAccount(account.toJson().toString(), seal, open)
        assertEquals(account, r.account)
        assertNotNull(r.migrated)
        assertEquals(true, r.migrated!!.startsWith("enc:v1:"))
        assertEquals(false, r.migrated!!.contains("app-pass"))
    }

    @Test fun `a sealed account is read and left alone`() {
        val r = readStoredAccount(seal(account.toJson().toString()), seal, open)
        assertEquals(account, r.account)
        assertNull(r.migrated)
    }

    @Test fun `a sealed account whose key is gone reads as signed out`() {
        val r = readStoredAccount("enc:v1:xx", seal, { null })
        assertNull(r.account)
        assertNull(r.migrated)
    }

    @Test fun `nothing stored reads as signed out`() {
        assertNull(readStoredAccount(null, seal, open).account)
        assertNull(readStoredAccount("not json", seal, open).account)
    }
}
