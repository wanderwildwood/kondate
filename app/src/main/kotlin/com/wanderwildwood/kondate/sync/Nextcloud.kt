package com.wanderwildwood.kondate.sync

import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A Nextcloud account as its login page hands it over: the server, the user's login name and an
 * app password made for this phone alone, which can be revoked from the server's security
 * settings without touching the real one.
 */
data class Account(val server: String, val user: String, val password: String) {
    fun toJson(): JSONObject = JSONObject().put("server", server).put("user", user).put("password", password)

    companion object {
        fun fromJson(o: JSONObject) = Account(o.getString("server"), o.getString("user"), o.getString("password"))
    }
}

/**
 * What to do with the stored account text: the sealed form is opened; the plain JSON an older
 * version wrote is read as it is and handed back sealed ([migrated]) so the caller can replace it
 * once. [seal] and [open] are the keystore's, passed in so this part can be tested on its own.
 */
data class StoredAccount(val account: Account?, val migrated: String?)

fun readStoredAccount(stored: String?, seal: (String) -> String?, open: (String) -> String?): StoredAccount {
    if (stored.isNullOrEmpty()) return StoredAccount(null, null)
    val json = open(stored) ?: return StoredAccount(null, null)
    val account = runCatching { Account.fromJson(JSONObject(json)) }.getOrNull() ?: return StoredAccount(null, null)
    val plainBefore = stored.trimStart().startsWith("{")
    return StoredAccount(account, if (plainBefore) seal(json) else null)
}

/** The server could not be reached, or its certificate could not be trusted: nothing was sent. */
class Unreachable(message: String) : IOException(message)

/** The server would not let this account in: signed out on the server, or a wrong password. */
class Refused(message: String) : IOException(message)

internal const val AGENT = "Recipes (kondate)"

/**
 * Stops a request that would go out in plain http to anywhere but a home or VPN address,
 * whatever a saved account or a redirect names. Asked twice: before a connection is opened,
 * and again for each address a redirect leads to.
 */
private val homeOnlyOverHttp = Interceptor { chain ->
    val url = chain.request().url
    if (!url.isHttps && !LoginFlow.plainHttpAllowed(url.toString())) throw Unreachable("plain http")
    chain.proceed(chain.request())
}

internal val http = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .addInterceptor(homeOnlyOverHttp)
    .addNetworkInterceptor(homeOnlyOverHttp)
    .build()

/**
 * Nextcloud's own sign-in, the one its apps use: the phone asks the server for a login page,
 * the reader signs in there in the browser, and the server hands this phone an app password.
 * Nobody types a password into this app that way, and two-factor works because it is the
 * server's own page. A phone with no browser takes an app password typed in instead.
 */
object LoginFlow {

    data class Started(val loginUrl: String, val pollUrl: String, val token: String)

    fun start(server: String): Started {
        if (!plainHttpAllowed(normalise(server))) throw Refused("needs https")
        val request = Request.Builder()
            .url("${normalise(server)}/index.php/login/v2")
            .post(FormBody.Builder().build())
            .header("User-Agent", AGENT)
            .build()
        val json = try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Refused("${response.code}")
                JSONObject(response.body!!.string())
            }
        } catch (e: IOException) {
            if (e is Refused) throw e
            throw Unreachable(e.message ?: "no answer")
        }
        val poll = json.getJSONObject("poll")
        return Started(json.getString("login"), poll.getString("endpoint"), poll.getString("token"))
    }

    /** The account, once the reader has finished in the browser, or null while they have not. */
    fun poll(started: Started): Account? {
        val request = Request.Builder()
            .url(started.pollUrl)
            .post(FormBody.Builder().add("token", started.token).build())
            .header("User-Agent", AGENT)
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                if (response.code == 404) return null
                if (!response.isSuccessful) throw Refused("${response.code}")
                val json = JSONObject(response.body!!.string())
                Account(
                    server = json.getString("server").trimEnd('/'),
                    user = json.getString("loginName"),
                    password = json.getString("appPassword"),
                )
            }
        } catch (e: IOException) {
            if (e is Refused) throw e
            null
        }?.also { if (!plainHttpAllowed(it.server)) throw Refused("needs https") }
    }

    /**
     * Whether [address] may be used: any https one, or an http one whose host is on a home or VPN
     * network (a private or Tailscale address, "localhost", a name with no dots, or one ending
     * .local, .lan, .home.arpa, .internal or .ts.net). Plain http to a public host would send the
     * login in the clear across the internet. Memo's and Cabinet's rule.
     */
    fun plainHttpAllowed(address: String): Boolean {
        val url = address.trim().toHttpUrlOrNull() ?: return false
        if (url.isHttps) return true
        val host = url.host.lowercase()
        if (host.contains(':') || host.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))) return isPrivateAddress(host)
        return !host.contains('.') || listOf(".local", ".lan", ".home.arpa", ".internal", ".ts.net").any { host.endsWith(it) }
    }

    /** A private, loopback, link-local or Tailscale (100.64/10) address literal; never a name. */
    internal fun isPrivateAddress(literal: String): Boolean {
        val addr = literal.substringBefore('%').lowercase().trim()
        val v4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(addr)
        val v6 = ':' in addr && Regex("^[0-9a-f.:]+$").matches(addr)
        if (!v4 && !v6) return false
        val bytes = runCatching { java.net.InetAddress.getByName(addr).address }.getOrNull() ?: return false
        val a = bytes[0].toInt() and 0xFF
        val b = bytes[1].toInt() and 0xFF
        if (bytes.size == 4) {
            return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
                (a == 169 && b == 254) || (a == 100 && b in 64..127)
        }
        val loopback = bytes.take(15).all { it.toInt() == 0 } && bytes[15].toInt() == 1
        return loopback || (a and 0xFE) == 0xFC || (a == 0xFE && (b and 0xC0) == 0x80)
    }

    /** What was typed, as an address: "cloud.example" becomes "https://cloud.example". */
    fun normalise(server: String): String {
        val trimmed = server.trim().trimEnd('/')
        return if (trimmed.startsWith("https://", ignoreCase = true) || isPlain(trimmed)) trimmed else "https://$trimmed"
    }

    /** True when what was typed names plain http, which is used only for a home or VPN address. */
    fun isPlain(server: String): Boolean = server.trim().startsWith("http://", ignoreCase = true)
}

/**
 * The two things asked of Cookbook itself rather than of the files, both only to be helpful and
 * neither needed: which folder it keeps its recipes in, so this app can start from the same one,
 * and a fresh look at that folder after a sync, so what was sent shows in Cookbook at once rather
 * than at its next look. A server without Cookbook answers neither, and nothing comes of it.
 */
object CookbookApp {

    private fun api(account: Account, path: String) =
        "${account.server.trimEnd('/')}/index.php/apps/cookbook/api/v1/$path"

    private fun request(account: Account, url: String) = Request.Builder().url(url)
        .header("Authorization", Credentials.basic(account.user, account.password, Charsets.UTF_8))
        .header("User-Agent", AGENT)
        .header("Accept", "application/json")

    /** Cookbook's folder, as "Recipes", or null when there is no Cookbook to say. */
    fun folder(account: Account): String? = runCatching {
        http.newCall(request(account, api(account, "config")).get().build()).execute().use { r ->
            if (!r.isSuccessful) return null
            JSONObject(r.body!!.string()).optString("folder").trim('/', ' ').takeIf { it.isNotEmpty() }
        }
    }.getOrNull()

    fun reindex(account: Account) {
        runCatching {
            http.newCall(request(account, api(account, "reindex")).post(FormBody.Builder().build()).build()).execute().close()
        }
    }
}
