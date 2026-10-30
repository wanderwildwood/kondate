package com.wanderwildwood.kondate.importer

import com.wanderwildwood.kondate.data.Recipe
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/**
 * Reading a recipe from the address of its page: the one thing this app goes online for.
 *
 * The page is fetched once, saying plainly that it is this app, and nothing else is: no pictures, no
 * scripts, nothing sent but the request for the page. What comes back is searched for the
 * recipe the page publishes for search engines (JSON-LD), then for the older microdata; a page
 * with neither gives back its title, so the recipe can be typed in under it.
 */
object Reader {

    sealed interface Result {
        /** A recipe read from the page. */
        data class Found(val recipe: Recipe) : Result

        /** The page was read but holds no recipe a machine can read: its title, to type one in under. */
        data class NoRecipe(val title: String) : Result

        /** The page could not be fetched: no network, no such page, or the site turned the request away. */
        data class Failed(val code: Int) : Result
    }

    /** What a page's HTML gives, without fetching anything: the part the tests can reach. */
    fun fromHtml(html: String, url: String): Result {
        JsonLd.recipesInHtml(html, url).firstOrNull()?.let { return Result.Found(it.copy(url = it.url.ifEmpty { url })) }
        Microdata.recipeInHtml(html, url)?.let { return Result.Found(it) }
        val title = Html.title(html)
        if (isChallenge(title, html)) return Result.Failed(403)
        return Result.NoRecipe(title)
    }

    /** Fetches [address] and reads it. Blocking: call it off the main thread. */
    fun read(address: String): Result {
        val failed = if (isPlain(address)) Result.Failed(NEEDS_HTTPS) else null
        val page = try {
            fetch(address)
        } catch (_: Exception) {
            return failed ?: Result.Failed(0)
        }
        if (page.code !in 200..299) return failed ?: Result.Failed(page.code)
        return fromHtml(page.body, page.url)
    }

    /**
     * True when the web server's answer means the site does not let apps read its pages: forbidden,
     * not signed in, too many requests, or "not acceptable" to a client that is not a browser.
     * The app does not try to get round it; it says so and offers pasted text instead.
     */
    fun refused(code: Int): Boolean = code in REFUSED

    private val REFUSED = setOf(401, 403, 406, 429)

    private val CHALLENGE_TITLES = listOf(
        "just a moment", "attention required", "access denied", "are you a robot", "are you a human",
        "security check", "verify you are", "checking your browser", "pardon our interruption", "robot or human",
    )
    private val CHALLENGE_MARKERS = listOf("cf-browser-verification", "challenge-platform", "captcha-delivery.com", "px-captcha")

    /** True for the page a bot check answers with (status 200, but not the recipe): treated as a refusal. */
    fun isChallenge(title: String, html: String): Boolean {
        val t = title.lowercase()
        return CHALLENGE_TITLES.any { t.contains(it) } || CHALLENGE_MARKERS.any { html.contains(it, ignoreCase = true) }
    }

    /**
     * True when text shared from another app is just a link (perhaps with the page's title), to be read
     * by address; false for a recipe's words, which may well mention an address of their own.
     */
    fun isLinkShare(text: String): Boolean {
        val url = addressIn(text) ?: return false
        val rest = text.replace(url, "").trim()
        return rest.length <= 120 && rest.lines().count { it.isNotBlank() } <= 2
    }

    /** [Result.Failed] code for a page that only answers over plain http, which is never used. */
    const val NEEDS_HTTPS = -1

    /** True for an address that starts with plain http. */
    fun isPlain(address: String): Boolean = address.trim().startsWith("http://", ignoreCase = true)

    /** The same address over https: this app asks for nothing in plain text. */
    fun secure(address: String): String =
        if (isPlain(address)) "https://" + address.trim().substring(7) else address.trim()

    private class Page(val code: Int, val body: String, val url: String)

    /** No recipe page needs more than this; a larger answer is not a recipe page. */
    private const val LIMIT = 4 * 1024 * 1024

    /**
     * The page at [address], following up to five redirects, and always over https: an http address is
     * tried as https, and a redirect to http is followed as https. A site that refuses an app is not
     * got round: the caller offers pasted text instead.
     */
    private fun fetch(address: String): Page {
        var url = URL(secure(address))
        repeat(6) {
            val c = url.openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 15_000
                c.readTimeout = 20_000
                c.setRequestProperty("User-Agent", USER_AGENT)
                c.setRequestProperty("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
                c.setRequestProperty("Accept-Language", "en;q=0.9,*;q=0.5")
                val code = c.responseCode
                if (code in 300..399) {
                    val next = c.getHeaderField("Location") ?: return Page(code, "", url.toString())
                    url = URL(secure(URL(url, next).toString()))
                    return@repeat
                }
                val stream = if (code in 200..299) c.inputStream else return Page(code, "", url.toString())
                val bytes = ByteArrayOutputStream()
                stream.use { input ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        bytes.write(buf, 0, n)
                        if (bytes.size() > LIMIT) break
                    }
                }
                return Page(code, String(bytes.toByteArray(), charsetOf(c.contentType)), url.toString())
            } finally {
                c.disconnect()
            }
        }
        return Page(310, "", url.toString())
    }

    private fun charsetOf(contentType: String?): Charset {
        val name = contentType?.substringAfter("charset=", "")?.trim()?.trim('"')?.substringBefore(';')
        return runCatching { if (name.isNullOrEmpty()) Charsets.UTF_8 else Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
    }

    private const val USER_AGENT = "Recipes/0.2.10 (Android; reads one recipe page for its owner)"

    private val ADDRESS = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    /**
     * The first web address in text shared from another app: a browser shares the address
     * alone, some apps share "Title — https://…".
     */
    fun addressIn(text: String): String? =
        ADDRESS.find(text)?.value?.trimEnd('.', ',', ')', ']', ';', '!', '?')
}
