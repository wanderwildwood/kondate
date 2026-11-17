package com.wanderwildwood.kondate.sync

import okhttp3.Request
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlainHttpTest {

    @Test
    fun httpsIsAlwaysFine() {
        assertTrue(LoginFlow.plainHttpAllowed("https://cloud.example.org"))
    }

    @Test
    fun httpOnAHomeOrVpnNetworkIsFine() {
        for (a in listOf(
            "http://192.168.1.5", "http://10.0.0.2:8080", "http://172.20.0.4/", "http://100.101.102.103:11000",
            "http://[fd12::5]/", "http://nextcloud", "http://cloud.local", "http://box.lan/",
            "http://pc.tail1234.ts.net", "http://localhost:8080",
        )) assertTrue(a, LoginFlow.plainHttpAllowed(a))
    }

    @Test
    fun httpToTheInternetIsRefused() {
        for (a in listOf(
            "http://cloud.example.org", "http://8.8.8.8/", "http://203.0.113.9:8080", "http://172.32.0.1",
            "http://evil.local.example.org", "http://[2001:db8::1]/", "not an address",
        )) assertFalse(a, LoginFlow.plainHttpAllowed(a))
    }

    @Test
    fun aSavedServerOnTheInternetIsNotAsked() {
        val e = runCatching {
            http.newCall(Request.Builder().url("http://cloud.example.org/remote.php/dav/").build()).execute()
        }.exceptionOrNull()
        assertTrue(e.toString(), e is Unreachable && e.message == "plain http")
    }

    @Test
    fun aServerAtHomeIsStillAsked() {
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { socket ->
            var heard = ""
            val answer = Thread {
                socket.accept().use { c ->
                    val reader = c.getInputStream().bufferedReader()
                    heard = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.joinToString("\n")
                    c.getOutputStream().write("HTTP/1.1 500 No\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                }
            }.apply { start() }
            runCatching { http.newCall(Request.Builder().url("http://127.0.0.1:${socket.localPort}/dav/").build()).execute().close() }
            answer.join(5000)
            assertTrue(heard, heard.startsWith("GET /dav/"))
        }
    }
}
