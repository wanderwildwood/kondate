package com.wanderwildwood.kondate.importer

import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fetching, against a small server on this machine: no real site is ever asked. */
class ReaderFetchTest {

    private class Request(val path: String, val headers: Map<String, String>)

    private val seen = CopyOnWriteArrayList<Request>()
    private val server = ServerSocket(0, 20, java.net.InetAddress.getLoopbackAddress())
    private val base = "http://127.0.0.1:${server.localPort}"
    @Volatile private var handler: (Request) -> String = { notFound() }

    init {
        Thread {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: Exception) { break }
                Thread { serve(s) }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    @After fun stop() = server.close()

    private fun serve(s: Socket) = s.use {
        val lines = it.getInputStream().bufferedReader().let { r ->
            generateSequence { r.readLine() }.takeWhile { l -> l.isNotEmpty() }.toList()
        }
        if (lines.isEmpty()) return
        val headers = lines.drop(1).associate { l -> l.substringBefore(':').lowercase() to l.substringAfter(':').trim() }
        val req = Request(lines[0].split(' ')[1], headers)
        seen.add(req)
        it.getOutputStream().write(handler(req).toByteArray())
    }

    private fun reply(status: String, type: String, body: String, extra: String = "") =
        "HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n$extra\r\n$body"

    private fun notFound() = reply("404 Not Found", "text/plain", "no")

    private val recipePage = """<html><head><title>Soup</title><script type="application/ld+json">
        {"@type":"Recipe","name":"Plain Soup","recipeIngredient":["1 onion","2 cups water"],"recipeInstructions":"Boil."}
        </script></head><body></body></html>"""

    private fun looksLikeBrowser(r: Request) = r.headers["user-agent"].orEmpty().startsWith("Mozilla/5.0")

    @Test fun firstAskNamesTheAppAndSendsBrowserStyleAcceptHeaders() {
        handler = { reply("200 OK", "text/html; charset=utf-8", recipePage) }
        val r = Reader.readFrom("$base/soup", upgradeToHttps = false)
        assertTrue(r is Reader.Result.Found)
        val h = seen.single().headers
        assertTrue(h["user-agent"]!!.startsWith("Recipes/"))
        assertTrue(h["accept"]!!.startsWith("text/html"))
        assertTrue(h["accept-language"]!!.startsWith("en"))
    }

    @Test fun aSiteThatWantsABrowserIsServedOnceItLooksLikeOne() {
        handler = { r -> if (looksLikeBrowser(r)) reply("200 OK", "text/html", recipePage) else reply("403 Forbidden", "text/plain", "bots no") }
        assertTrue(Reader.readFrom("$base/soup", false) is Reader.Result.Found)
        assertEquals(2, seen.size)
        assertTrue(seen[1].headers["user-agent"]!!.contains("Chrome"))
        assertTrue(seen[1].headers["user-agent"]!!.endsWith("Recipes/0.2.10"))
    }

    @Test fun a403IsAskedAgainAsChromeThenAsMobileSafari() {
        handler = { r -> if (r.headers["user-agent"].orEmpty().contains("iPhone")) reply("200 OK", "text/html", recipePage) else reply("403 Forbidden", "text/plain", "x") }
        assertTrue(Reader.readFrom("$base/soup", false) is Reader.Result.Found)
        assertEquals(3, seen.size)
        assertTrue(seen[1].headers["user-agent"]!!.contains("Android"))
        assertTrue(seen[2].headers["user-agent"]!!.contains("Safari"))
        assertTrue(seen[2].headers["user-agent"]!!.endsWith("Recipes/0.2.10"))
    }

    @Test fun aSiteThatKeepsSaying403GetsExactlyThreeAsksAndItsOwnAnswerIsReported() {
        handler = { reply("403 Forbidden", "text/plain", "no") }
        assertEquals(Reader.Result.Failed(403), Reader.readFrom("$base/soup", false))
        assertEquals(3, seen.size)
    }

    @Test fun otherAnswersAreNotRetriedAndAreReportedAsTheSiteGaveThem() {
        handler = { reply("404 Not Found", "text/plain", "no") }
        assertEquals(Reader.Result.Failed(404), Reader.readFrom("$base/x", false))
        assertEquals(1, seen.size)
        seen.clear()
        handler = { reply("429 Too Many Requests", "text/plain", "slow") }
        assertEquals(Reader.Result.Failed(429), Reader.readFrom("$base/x", false))
        assertEquals(1, seen.size)
    }

    @Test fun redirectsAreFollowed() {
        handler = { r ->
            when (r.path) {
                "/old" -> reply("301 Moved Permanently", "text/plain", "", "Location: /new\r\n")
                "/new" -> reply("200 OK", "text/html", recipePage)
                else -> notFound()
            }
        }
        val r = Reader.readFrom("$base/old", false)
        assertTrue(r is Reader.Result.Found)
        assertEquals(listOf("/old", "/new"), seen.map { it.path })
    }

    @Test fun aPdfLinkIsSaidToBeAPdfNotAnError() {
        handler = { reply("200 OK", "application/pdf", "%PDF-1.4 not really") }
        assertEquals(Reader.Result.Failed(Reader.IS_PDF), Reader.readFrom("$base/menu", false))
    }

    @Test fun aPdfBehindARedirectIsRecognisedToo() {
        handler = { r ->
            if (r.path == "/go") reply("302 Found", "text/plain", "", "Location: /file\r\n")
            else reply("200 OK", "Application/PDF; qualifier=x", "%PDF")
        }
        assertEquals(Reader.Result.Failed(Reader.IS_PDF), Reader.readFrom("$base/go", false))
    }

    @Test fun aPdfIsRecognisedByNameWhenTheTypeIsJustAFile() {
        assertTrue(Reader.isPdf("application/octet-stream", "/files/Soup.PDF"))
        assertTrue(Reader.isPdf(null, "/a.pdf"))
        assertTrue(Reader.isPdf("application/pdf", "/a"))
        assertFalse(Reader.isPdf("text/html", "/a.pdf"))
        assertFalse(Reader.isPdf("text/html", "/a"))
    }

    @Test fun plainHttpStillRefusedWhenTheHttpsRuleIsOn() {
        // Nothing is sent: the address is upgraded to https, which this machine does not serve.
        val r = Reader.readFrom("$base/soup", upgradeToHttps = true)
        assertEquals(Reader.Result.Failed(Reader.NEEDS_HTTPS), r)
        assertTrue(seen.isEmpty())
    }
}
