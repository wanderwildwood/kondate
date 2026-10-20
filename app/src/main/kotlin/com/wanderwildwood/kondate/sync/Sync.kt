package com.wanderwildwood.kondate.sync

import com.wanderwildwood.kondate.data.Recipe
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the last sync left one recipe as: its id on this phone, where it is in the cloud and the
 * etag its file had then, the recipe's own `modified` stamp here then, and the file's text as
 * last read or written, which is what the next write goes over, so fields this app does not
 * know are kept.
 *
 * The links are also the record of deletions. A recipe with a link and no recipe here was
 * deleted on this phone; a link whose folder has gone from the cloud was deleted there. A link
 * is dropped only once the other side has followed.
 */
data class Link(val id: String, val remote: RemoteRecipe, val modified: String, val raw: String) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("fileId", remote.fileId)
        .put("folder", remote.folder)
        .put("folderEtag", remote.folderEtag)
        .put("file", remote.file)
        .put("fileEtag", remote.fileEtag)
        .put("modified", modified)
        .put("raw", raw)

    companion object {
        fun fromJson(o: JSONObject) = Link(
            id = o.getString("id"),
            remote = RemoteRecipe(
                fileId = o.getString("fileId"),
                folder = o.getString("folder"),
                folderEtag = o.getString("folderEtag"),
                file = o.getString("file"),
                fileEtag = o.getString("fileEtag"),
            ),
            modified = o.getString("modified"),
            raw = o.getString("raw"),
        )

        fun listToJson(links: Collection<Link>): String = JSONArray().also { a -> links.forEach { a.put(it.toJson()) } }.toString()

        fun listFromJson(text: String): Map<String, Link> {
            val a = runCatching { JSONArray(text) }.getOrNull() ?: return emptyMap()
            return (0 until a.length()).mapNotNull { runCatching { fromJson(a.getJSONObject(it)) }.getOrNull() }.associateBy { it.id }
        }
    }
}

/** The recipes on this phone, as a sync sees them. [com.wanderwildwood.kondate.data.Store] is the real one. */
interface Local {
    fun recipes(): List<Recipe>

    /**
     * [recipe] kept exactly as given, its stamps included, only if the recipe of its id here is
     * still at [expect] (its `modified`), or absent when [expect] is null. False, and nothing
     * done, when it was changed meanwhile: the next sync takes it from there.
     */
    fun put(recipe: Recipe, expect: String?): Boolean

    /** The recipe deleted, on the same condition. */
    fun remove(id: String, expect: String): Boolean

    fun newId(): String
    fun now(): String
}

/** One thing a sync does, as [plan] decides it. */
sealed interface Action {
    /** Changed here since the last sync, not there: sent. */
    data class Push(val link: Link, val remote: RemoteRecipe) : Action
    /** Changed there, not here (or deleted here, since a change beats a deletion): fetched. */
    data class Pull(val link: Link, val remote: RemoteRecipe) : Action
    /** Changed on both sides: both kept, the phone's under a new name, unless they now agree. */
    data class Conflict(val link: Link, val remote: RemoteRecipe) : Action
    /** Unchanged on both sides, but the folder was renamed or its etag moved: noted. */
    data class Relink(val link: Link, val remote: RemoteRecipe) : Action
    /** Deleted there, unchanged here: deleted here. */
    data class DeleteHere(val link: Link) : Action
    /** Deleted here, unchanged there: deleted there. */
    data class DeleteThere(val link: Link, val remote: RemoteRecipe) : Action
    /** Gone from both sides: the link is let go. */
    data class Forget(val link: Link) : Action
    /** New here, or changed here after it was deleted there: sent as a new recipe. */
    data class Send(val id: String) : Action
    /** New there: fetched as a new recipe. */
    data class Fetch(val remote: RemoteRecipe) : Action
    /** New on both sides under the same name: one recipe if they agree, both kept if not. */
    data class Match(val id: String, val remote: RemoteRecipe) : Action
}

/**
 * What a sync has to do, from the recipes here, the links the last sync left and what the cloud
 * holds now. A recipe has changed here when its `modified` stamp is not the one its link
 * recorded, and there when its file's etag is not.
 *
 * Nothing is lost on either side. A change beats a deletion, whichever side made which; changes
 * on both sides keep both. When every linked folder has gone at once (the folder renamed or
 * swapped on the server, say), nothing is deleted here: the links are let go, and the recipes
 * are matched by name or sent again.
 */
fun plan(locals: List<Recipe>, links: Map<String, Link>, listing: Listing): List<Action> {
    val here = locals.associateBy { it.id }
    val there = listing.recipes
    val usable = if (links.size >= 3 && links.values.none { it.remote.fileId in there }) emptyMap() else links

    val out = mutableListOf<Action>()
    val linkedIds = mutableSetOf<String>()
    val linkedFiles = mutableSetOf<String>()
    for (link in usable.values) {
        val l = here[link.id]
        val r = there[link.remote.fileId]
        linkedIds += link.id
        if (r != null) linkedFiles += r.fileId
        val changedHere = l != null && l.modified != link.modified
        val changedThere = r != null && r.fileEtag != link.remote.fileEtag
        out += when {
            l == null && r == null -> Action.Forget(link)
            r == null -> if (changedHere) Action.Send(link.id) else Action.DeleteHere(link)
            l == null -> if (changedThere) Action.Pull(link, r) else Action.DeleteThere(link, r)
            changedHere && changedThere -> Action.Conflict(link, r)
            changedHere -> Action.Push(link, r)
            changedThere -> Action.Pull(link, r)
            r != link.remote -> Action.Relink(link, r)
            else -> continue
        }
    }

    val newThere = there.values.filter { it.fileId !in linkedFiles }.toMutableList()
    for (l in locals) {
        if (l.id in linkedIds) continue
        val folder = Cookbook.folderName(l.name)
        val same = newThere.firstOrNull { it.folder.equals(folder, ignoreCase = true) }
        if (same != null) {
            newThere -= same
            out += Action.Match(l.id, same)
        } else {
            out += Action.Send(l.id)
        }
    }
    newThere.forEach { out += Action.Fetch(it) }

    // Whatever frees or takes a folder name there before anything new is sent.
    return out.sortedBy { if (it is Action.Send) 1 else 0 }
}

private val ID = Regex("[A-Za-z0-9_-]{1,80}")

/**
 * One sync between [local] and [remote], doing what [plan] decides. [phoneSuffix] goes after
 * the name of the phone's copy when both sides changed a recipe (" (phone)").
 *
 * A file that changed on the server between the listing and the write ([Changed]) is left for
 * the next sync, which sees it as changed and does the right thing then. Anything else that
 * fails ends the sync where it is; [links] holds what was done up to there, for the caller to
 * keep either way.
 */
class Sync(
    private val remote: Remote,
    private val local: Local,
    start: Map<String, Link>,
    private val phoneSuffix: String,
) {
    val links: MutableMap<String, Link> = start.toMutableMap()

    /** Whether anything was written or deleted on the server, so Cookbook can be told to look. */
    var wroteThere = false
        private set

    fun run() {
        val listing = remote.list(links.values.associate { it.remote.fileId to it.remote })
        val actions = plan(local.recipes(), links, listing)
        // Every link not dropped by the plan's own "all gone" rule is acted on by it; the rest go.
        val acted = actions.mapNotNull { linkOf(it)?.id }.toSet()
        val listed = listing.recipes.keys
        links.values.removeAll { it.id !in acted && it.remote.fileId !in listed }
        val taken = listing.folders.toMutableSet()
        for (a in actions) {
            try {
                act(a, taken)
            } catch (_: Changed) {
                // Left for the next sync.
            }
        }
    }

    private fun linkOf(a: Action): Link? = when (a) {
        is Action.Push -> a.link
        is Action.Pull -> a.link
        is Action.Conflict -> a.link
        is Action.Relink -> a.link
        is Action.DeleteHere -> a.link
        is Action.DeleteThere -> a.link
        is Action.Forget -> a.link
        else -> null
    }

    private fun act(a: Action, taken: MutableSet<String>) {
        val here = local.recipes().associateBy { it.id }
        when (a) {
            is Action.Push -> {
                val mine = here[a.link.id] ?: return
                val text = Cookbook.toJson(mine, parsed(a.link.raw)).toString()
                val etag = remote.write(a.remote.copy(fileEtag = a.link.remote.fileEtag), text)
                wroteThere = true
                var r = a.remote.copy(fileEtag = etag)
                links[mine.id] = Link(mine.id, r, mine.modified, text)
                // A new name moves the folder too, as Cookbook does, after the write: a server
                // that gives the file a new etag when its folder moves then only makes the next
                // sync fetch what this one wrote, where before the write it would refuse it.
                val wanted = Cookbook.folderName(mine.name)
                if (wanted != r.folder && taken.none { it.equals(wanted, ignoreCase = true) } && remote.rename(r, wanted)) {
                    taken -= r.folder
                    taken += wanted
                    r = r.copy(folder = wanted)
                    links[mine.id] = Link(mine.id, r, mine.modified, text)
                }
            }
            is Action.Pull -> {
                val (text, etag) = remote.read(a.remote)
                val theirs = Cookbook.fromJson(text) ?: return
                val kept = stamped(theirs.copy(id = a.link.id))
                if (local.put(kept, here[a.link.id]?.modified)) {
                    links[kept.id] = Link(kept.id, a.remote.copy(fileEtag = etag), kept.modified, text)
                }
            }
            is Action.Conflict -> {
                val mine = here[a.link.id] ?: return
                val (text, etag) = remote.read(a.remote)
                val theirs = Cookbook.fromJson(text) ?: return
                if (Cookbook.same(mine, theirs)) {
                    links[mine.id] = Link(mine.id, a.remote.copy(fileEtag = etag), mine.modified, text)
                    return
                }
                // The phone's copy under a new name and id, sent as a new recipe; the server's
                // takes the old id, and with it the place in the list and the link.
                val copy = mine.copy(id = local.newId(), name = mine.name + phoneSuffix, modified = local.now())
                if (!local.put(copy, null)) return
                if (local.put(stamped(theirs.copy(id = mine.id)), mine.modified)) {
                    val kept = local.recipes().first { it.id == mine.id }
                    links[mine.id] = Link(mine.id, a.remote.copy(fileEtag = etag), kept.modified, text)
                }
                send(copy, taken)
            }
            is Action.Relink -> links[a.link.id] = a.link.copy(remote = a.remote)
            is Action.DeleteHere -> {
                val l = here[a.link.id]
                if (l == null || local.remove(l.id, a.link.modified)) links.remove(a.link.id)
            }
            is Action.DeleteThere -> {
                remote.delete(a.remote.copy(fileEtag = a.link.remote.fileEtag))
                wroteThere = true
                taken.removeAll { it.equals(a.remote.folder, ignoreCase = true) }
                links.remove(a.link.id)
            }
            is Action.Forget -> links.remove(a.link.id)
            is Action.Send -> {
                links.remove(a.id)
                here[a.id]?.let { send(it, taken) }
            }
            is Action.Fetch -> {
                val (text, etag) = remote.read(a.remote)
                val theirs = Cookbook.fromJson(text) ?: return
                val id = theirs.id.takeIf { usable(it) && it !in here && it !in links } ?: local.newId()
                val kept = stamped(theirs.copy(id = id))
                if (local.put(kept, null)) links[id] = Link(id, a.remote.copy(fileEtag = etag), kept.modified, text)
            }
            is Action.Match -> {
                val mine = here[a.id] ?: return
                val (text, etag) = remote.read(a.remote)
                val theirs = Cookbook.fromJson(text) ?: return
                if (Cookbook.same(mine, theirs)) {
                    links[mine.id] = Link(mine.id, a.remote.copy(fileEtag = etag), mine.modified, text)
                    return
                }
                // Two different recipes of one name: the server's comes in as it is, and the
                // phone's goes up beside it under the phone's name.
                val newId = theirs.id.takeIf { usable(it) && it !in here && it !in links } ?: local.newId()
                val fetched = stamped(theirs.copy(id = newId))
                if (local.put(fetched, null)) links[newId] = Link(newId, a.remote.copy(fileEtag = etag), fetched.modified, text)
                val renamed = mine.copy(name = mine.name + phoneSuffix, modified = local.now())
                if (local.put(renamed, mine.modified)) send(renamed, taken)
            }
        }
    }

    /** [r] sent as a new recipe, in a folder of its name not already there. */
    private fun send(r: Recipe, taken: MutableSet<String>) {
        val text = Cookbook.toJson(r).toString()
        repeat(5) {
            val folder = Cookbook.unique(Cookbook.folderName(r.name), taken)
            taken += folder
            val made = try {
                remote.create(folder, text)
            } catch (_: Taken) {
                return@repeat
            }
            wroteThere = true
            links[r.id] = Link(r.id, made, r.modified, text)
            return
        }
    }

    /** A recipe from the server with both its dates, as this phone keeps every recipe. */
    private fun stamped(r: Recipe): Recipe {
        val modified = r.modified.ifEmpty { r.created }.ifEmpty { local.now() }
        return r.copy(modified = modified, created = r.created.ifEmpty { modified })
    }

    /** An id from a file kept as this phone's own only when it is the kind this app makes. */
    private fun usable(id: String) = ID.matches(id)

    private fun parsed(raw: String): JSONObject? = runCatching { JSONObject(raw) }.getOrNull()
}
