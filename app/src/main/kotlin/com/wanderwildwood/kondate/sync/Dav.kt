package com.wanderwildwood.kondate.sync

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.w3c.dom.Element
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import javax.xml.parsers.DocumentBuilderFactory

/**
 * One recipe in the cloud: the folder Cookbook keeps it in, named by its id on the server
 * ([fileId], which stays the same when the folder is renamed, and is Cookbook's id for the
 * recipe), and the JSON file inside. The etags change whenever what they name does; a folder's
 * whenever anything in it does.
 */
data class RemoteRecipe(
    val fileId: String,
    val folder: String,
    val folderEtag: String,
    val file: String,
    val fileEtag: String,
)

/** The recipes in the folder, by [RemoteRecipe.fileId], and the name of every folder in it. */
data class Listing(val recipes: Map<String, RemoteRecipe>, val folders: Set<String>)

/** The file was not what this phone last saw: changed, or gone, since. */
class Changed(message: String) : IOException(message)

/** A folder of that name is already there. */
class Taken(message: String) : IOException(message)

/** The far end of a sync: a folder of recipe folders. [NextcloudFolder] is the real one. */
interface Remote {
    /**
     * Every recipe folder and the recipe in it. [seen] is what the last sync found, by file id:
     * a folder whose etag has not moved since holds what it held, so it is not opened again.
     */
    fun list(seen: Map<String, RemoteRecipe>): Listing

    /** The recipe file's text, and its etag as read. [Changed] if it has gone. */
    fun read(r: RemoteRecipe): Pair<String, String>

    /** A new folder with the recipe in it. [Taken] if the folder is already there. */
    fun create(folder: String, text: String): RemoteRecipe

    /** The recipe written over [r]'s file, only if it is still as [r] saw it ([Changed] if not). Its new etag. */
    fun write(r: RemoteRecipe, text: String): String

    /** [r]'s folder renamed to [to]; false, and nothing done, if [to] is taken. */
    fun rename(r: RemoteRecipe, to: String): Boolean

    /**
     * [r]'s recipe file deleted, only if it is still as [r] saw it ([Changed] if not), and its
     * folder after it when nothing else is left in it. A picture Cookbook keeps beside the
     * recipe is never deleted: a folder holding one is left as it is, without the recipe.
     */
    fun delete(r: RemoteRecipe)
}

/**
 * The recipe folder in a Nextcloud, over WebDAV: `remote.php/dav/files/<user>/<folder>/`, one
 * folder per recipe inside, as Cookbook keeps them. Nextcloud keeps an etag for every file and
 * folder, says the new one after a write, honours If-Match and If-None-Match, and gives every
 * file and folder an id that a rename keeps, which is everything this leans on.
 */
class NextcloudFolder(
    private val account: Account,
    folder: String,
    private val client: OkHttpClient = http,
) : Remote {

    private val home = account.server.trimEnd('/') + "/remote.php/dav/files/" + encodePath(account.user) + "/"
    private val folder = folder.trim('/', ' ')
    private val root = home + encodePath(this.folder) + "/"

    private fun request(url: String) = Request.Builder().url(url)
        .header("Authorization", Credentials.basic(account.user, account.password, Charsets.UTF_8))
        .header("User-Agent", AGENT)

    private fun dir(name: String) = root + encodePath(name) + "/"
    private fun file(r: RemoteRecipe) = dir(r.folder) + encodePath(r.file)

    override fun list(seen: Map<String, RemoteRecipe>): Listing {
        val top = try {
            propfind(root, 1)
        } catch (_: Missing) {
            // The first sync to a folder not made yet.
            makeFolders()
            return Listing(emptyMap(), emptySet())
        }
        val rootPath = decodeHref(root)
        val recipes = mutableMapOf<String, RemoteRecipe>()
        val folders = mutableSetOf<String>()
        for (entry in top) {
            if (!entry.isFolder) continue
            val name = entry.path.removePrefix(rootPath).trim('/')
            if (name.isEmpty() || name.contains('/') || !entry.path.startsWith(rootPath)) continue
            folders += name
            val id = entry.fileId ?: continue
            val etag = entry.etag ?: continue
            val before = seen[id]
            if (before != null && before.folderEtag == etag) {
                recipes[id] = before.copy(folder = name)
                continue
            }
            val inside = try {
                propfind(dir(name), 1)
            } catch (_: Missing) {
                continue
            }
            // Cookbook's own is recipe.json; it reads any .json file in the folder, and so does this.
            val files = inside.filter { !it.isFolder && it.etag != null && it.path.lowercase().endsWith(".json") }
            val json = files.firstOrNull { it.path.endsWith("/" + Cookbook.FILE) } ?: files.firstOrNull() ?: continue
            recipes[id] = RemoteRecipe(id, name, etag, json.path.substringAfterLast('/'), json.etag!!)
        }
        return Listing(recipes, folders)
    }

    override fun read(r: RemoteRecipe): Pair<String, String> =
        call(request(file(r)).get().build()) { response ->
            if (response.code == 404) throw Changed("${r.folder} is gone")
            check(response)
            val etag = response.header("ETag")?.let(::canonicalEtag) ?: r.fileEtag
            response.body!!.string() to etag
        }

    override fun create(folder: String, text: String): RemoteRecipe {
        val made = call(request(dir(folder)).method("MKCOL", null).build()) { response ->
            if (response.code == 405) return@call false
            if (response.code == 409) {
                // The recipe folder itself is gone from under it.
                makeFolders()
                return@call null
            }
            check(response)
            true
        } ?: call(request(dir(folder)).method("MKCOL", null).build()) { response -> check(response); true }
        if (!made) throw Taken(folder)
        val fileEtag = put(dir(folder) + encodePath(Cookbook.FILE), text, ifMatch = null)
        val self = propfind(dir(folder), 0).firstOrNull() ?: throw IOException("$folder is not there after making it")
        return RemoteRecipe(
            fileId = self.fileId ?: throw IOException("no id for $folder"),
            folder = folder,
            folderEtag = self.etag.orEmpty(),
            file = Cookbook.FILE,
            fileEtag = fileEtag,
        )
    }

    override fun write(r: RemoteRecipe, text: String): String = put(file(r), text, ifMatch = r.fileEtag)

    private fun put(url: String, text: String, ifMatch: String?): String {
        val builder = request(url).put(text.toRequestBody(JSON))
        if (ifMatch != null) builder.header("If-Match", ifMatch) else builder.header("If-None-Match", "*")
        return call(builder.build()) { response ->
            if (response.code == 412) throw Changed(url)
            check(response)
            (response.header("OC-ETag") ?: response.header("ETag"))?.let(::canonicalEtag)
        } ?: propfind(url, 0).firstOrNull()?.etag ?: throw IOException("no etag for $url")
    }

    override fun rename(r: RemoteRecipe, to: String): Boolean {
        val request = request(dir(r.folder)).method("MOVE", null)
            .header("Destination", dir(to))
            .header("Overwrite", "F")
            .build()
        return call(request) { response ->
            if (response.code == 412) return@call false
            if (response.code == 404) throw Changed("${r.folder} is gone")
            check(response)
            true
        }
    }

    override fun delete(r: RemoteRecipe) {
        call(request(file(r)).delete().header("If-Match", r.fileEtag).build()) { response ->
            if (response.code == 412) throw Changed("${r.folder} changed")
            if (response.code == 404) return@call
            check(response)
        }
        val left = try {
            propfind(dir(r.folder), 1)
        } catch (_: Missing) {
            return
        }
        val self = decodeHref(dir(r.folder))
        if (left.any { it.path != self }) return
        call(request(dir(r.folder)).delete().build()) { }
    }

    /** The recipe folder and every folder above it, each made where it is missing. */
    private fun makeFolders() {
        var sofar = home
        for (part in folder.split('/').filter { it.isNotEmpty() }) {
            sofar += encodePath(part) + "/"
            call(request(sofar).method("MKCOL", null).build()) { response ->
                // 405 is "already there".
                if (response.code != 201 && response.code != 405) check(response)
            }
        }
    }

    private fun propfind(url: String, depth: Int): List<Entry> {
        val request = request(url).method("PROPFIND", PROPFIND_BODY.toRequestBody(XML)).header("Depth", depth.toString()).build()
        return call(request) { response ->
            if (response.code == 404) throw Missing()
            check(response)
            if (response.code != 207) throw IOException("${response.code} for PROPFIND")
            response.body!!.byteStream().use(::parseMultistatus)
        }
    }

    private fun <T> call(request: Request, read: (Response) -> T): T {
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw Unreachable(e.message ?: "no answer")
        }
        return response.use(read)
    }

    private fun check(response: Response) {
        when {
            response.isSuccessful -> Unit
            response.code == 401 || response.code == 403 -> throw Refused("${response.code}")
            else -> throw IOException("${response.code} for ${response.request.method}")
        }
    }

    /**
     * Ask the server once, before an account is kept: that these credentials open the user's
     * files. [Refused] or [Unreachable] when not.
     */
    fun probe() {
        try {
            propfind(home, 0)
        } catch (_: Missing) {
            throw Refused("no files for ${account.user}")
        }
    }

    /** Nothing at that path. Inside this file only. */
    private class Missing : IOException("not there")
}

/** One entry of a WebDAV listing. [path] is decoded; a folder's ends in "/". */
data class Entry(val path: String, val etag: String?, val isFolder: Boolean, val fileId: String?)

/** An etag as a header carries it, quoted; some servers list it bare. */
fun canonicalEtag(etag: String): String {
    val t = etag.trim()
    return if (t.startsWith("\"") || t.startsWith("W/\"")) t else "\"$t\""
}

/**
 * The answer to a PROPFIND: one `<d:response>` per file or folder, with the properties asked
 * for that the server has. Read with the platform's own XML parser, which works the same off
 * the phone, where the tests run.
 */
fun parseMultistatus(body: InputStream): List<Entry> {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        // A server's answer is not trusted to name outside files for the parser to fetch.
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        isExpandEntityReferences = false
    }
    val document = factory.newDocumentBuilder().parse(body)
    val responses = document.getElementsByTagNameNS(DAV, "response")
    val out = mutableListOf<Entry>()
    for (i in 0 until responses.length) {
        val response = responses.item(i) as Element
        val href = response.first(DAV, "href")?.textContent?.trim() ?: continue
        var etag: String? = null
        var folder = false
        var fileId: String? = null
        val propstats = response.getElementsByTagNameNS(DAV, "propstat")
        for (j in 0 until propstats.length) {
            val propstat = propstats.item(j) as Element
            if (propstat.first(DAV, "status")?.textContent?.contains(" 200 ") != true) continue
            propstat.first(DAV, "getetag")?.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let { etag = canonicalEtag(it) }
            propstat.first(OC, "fileid")?.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let { fileId = it }
            if (propstat.first(DAV, "resourcetype")?.let { it.getElementsByTagNameNS(DAV, "collection").length > 0 } == true) {
                folder = true
            }
        }
        var path = decodeHref(href)
        if (folder && !path.endsWith("/")) path += "/"
        out += Entry(path, etag, folder, fileId)
    }
    return out
}

/** The path an href names, decoded; a full URL or only a path both come out as the path. */
fun decodeHref(href: String): String {
    val raw = if (href.startsWith("http://") || href.startsWith("https://")) URI(href).rawPath else href
    return URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8").replace(Regex("/{2,}"), "/")
}

/** Each segment of a path percent-encoded for a URL, the slashes kept. */
fun encodePath(path: String): String =
    path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

private const val DAV = "DAV:"
private const val OC = "http://owncloud.org/ns"
private val JSON = "application/json; charset=utf-8".toMediaType()
private val XML = "application/xml; charset=utf-8".toMediaType()

private const val PROPFIND_BODY = """<?xml version="1.0"?>
<d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns"><d:prop><d:getetag/><d:resourcetype/><oc:fileid/></d:prop></d:propfind>"""

private fun Element.first(ns: String, name: String): Element? =
    getElementsByTagNameNS(ns, name).let { if (it.length > 0) it.item(0) as Element else null }
