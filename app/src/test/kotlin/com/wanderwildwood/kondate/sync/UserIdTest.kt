package com.wanderwildwood.kondate.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Sign in with the login name, build the file paths with the user id. */
class UserIdTest {
    private val byEmail = Account("https://cloud.example.org", "Ann@example.org", "app-pass", "ann")

    @Test fun `the file paths take the user id, not the login name`() {
        assertEquals("https://cloud.example.org/remote.php/dav/files/ann/", filesHome(byEmail))
    }

    @Test fun `without a user id the login name is the user id`() {
        val a = Account("https://cloud.example.org/", "ann", "app-pass")
        assertEquals("ann", a.uid)
        assertEquals("https://cloud.example.org/remote.php/dav/files/ann/", filesHome(a))
    }

    @Test fun `the user id is kept with the account`() {
        assertEquals(byEmail, Account.fromJson(JSONObject(byEmail.toJson().toString())))
    }

    @Test fun `an account kept before the user id reads with the login name`() {
        val old = JSONObject().put("server", "https://cloud.example.org").put("user", "ann").put("password", "p")
        assertEquals(Account("https://cloud.example.org", "ann", "p", "ann"), Account.fromJson(old))
    }

    @Test fun `the account in a log has no password`() {
        assertFalse(byEmail.toString().contains("app-pass"))
    }

    @Test fun `the server is shown without its scheme or a default port`() {
        assertEquals("cloud.example.org", shownServer("https://cloud.example.org/"))
        assertEquals("cloud.example.org", shownServer("https://Cloud.Example.org:443"))
        assertEquals("10.0.2.2:8081", shownServer("http://10.0.2.2:8081"))
        assertEquals("nas.lan", shownServer("http://nas.lan:80"))
        assertEquals("cloud.example.org:8443/nc", shownServer("https://cloud.example.org:8443/nc"))
    }
}
