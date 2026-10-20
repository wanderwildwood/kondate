package com.wanderwildwood.kondate.importer

import com.wanderwildwood.kondate.data.Recipe
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/**
 * Reading a recipe from the address of its page: the one thing this app goes online for.
 *
 * The page is fetched once, as a browser would fetch it, and nothing else is: no pictures, no
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
        return Result.NoRecipe(Html.title(html))
    }

    /** Fetches [address] and reads it. Blocking: call it off the main thread. */
    fun read(address: String): Result {
        val page = try {
            fetch(address)
        } catch (_: Exception) {
            return Result.Failed(0)
        }
        if (page.code !in 200..299) return Result.Failed(page.code)
        return fromHtml(page.body, page.url)
    }

    private class Page(val code: Int, val body: String, val url: String)

    /** No recipe page needs more than this; a larger answer is not a recipe page. */
    private const val LIMIT = 4 * 1024 * 1024

    /**
     * The page at [address], following up to five redirects, including from http to https,
     * which HttpURLConnection will not do by itself. Sites that refuse anything but a browser
     * are asked as a phone's browser asks.
     */
    private fun fetch(address: String): Page {
        var url = URL(address)
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
                    url = URL(url, next)
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

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    private val ADDRESS = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    /**
     * The first web address in text shared from another app: a browser shares the address
     * alone, some apps share "Title — https://…".
     */
    fun addressIn(text: String): String? =
        ADDRESS.find(text)?.value?.trimEnd('.', ',', ')', ']', ';', '!', '?')
}
