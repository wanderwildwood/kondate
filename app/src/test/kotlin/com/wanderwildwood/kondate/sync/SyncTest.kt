package com.wanderwildwood.kondate.sync

import com.wanderwildwood.kondate.data.Recipe
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two-way sync against a made-up Nextcloud held in memory: folders with ids that a rename keeps,
 * etags that move whenever a file does (and a folder's whenever anything in it does), If-Match
 * honoured. Each test is one phone and one server, synced, changed, and synced again.
 */
class SyncTest {

    /** A Nextcloud recipe folder, as far as a sync can tell. */
    class FakeCloud : Remote {
        class Folder(val id: String, var etag: String, val files: MutableMap<String, Pair<String, String>> = mutableMapOf())

        val folders = linkedMapOf<String, Folder>()
        private var next = 100
        private fun tag() = "\"e${next++}\""

        /** Some servers give every file in a moved folder a new etag. */
        var renameChangesEtags = false

        /** Changing the next write's file underneath, between a sync's listing and its write. */
        var meddle: (() -> Unit)? = null

        fun recipe(folder: String): JSONObject? = folders[folder]?.files?.get(Cookbook.FILE)?.first?.let(::JSONObject)

        /** As Cookbook's editor or the web would: a recipe written there. */
        fun putThere(folder: String, json: JSONObject, file: String = Cookbook.FILE) {
            val f = folders.getOrPut(folder) { Folder("${next++}", tag()) }
            f.files[file] = json.toString() to tag()
            f.etag = tag()
        }

        fun deleteThere(folder: String) {
            folders.remove(folder)
        }

        override fun list(seen: Map<String, RemoteRecipe>): Listing {
            val recipes = mutableMapOf<String, RemoteRecipe>()
            for ((name, f) in folders) {
                val json = f.files.keys.firstOrNull { it == Cookbook.FILE } ?: f.files.keys.firstOrNull { it.endsWith(".json") } ?: continue
                recipes[f.id] = RemoteRecipe(f.id, name, f.etag, json, f.files.getValue(json).second)
            }
            return Listing(recipes, folders.keys.toSet())
        }

        private fun folderOf(r: RemoteRecipe) = folders.entries.firstOrNull { it.value.id == r.fileId }

        override fun read(r: RemoteRecipe): Pair<String, String> {
            val f = folderOf(r)?.value?.files?.get(r.file) ?: throw Changed("gone")
            return f
        }

        override fun create(folder: String, text: String): RemoteRecipe {
            if (folders.keys.any { it.equals(folder, ignoreCase = true) }) throw Taken(folder)
            val f = Folder("${next++}", "")
            val etag = tag()
            f.files[Cookbook.FILE] = text to etag
            f.etag = tag()
            folders[folder] = f
            return RemoteRecipe(f.id, folder, f.etag, Cookbook.FILE, etag)
        }

        override fun write(r: RemoteRecipe, text: String): String {
            meddle?.invoke()
            meddle = null
            val (name, f) = folderOf(r) ?: throw Changed("gone")
            val now = f.files[r.file] ?: throw Changed("gone")
            if (now.second != r.fileEtag) throw Changed(name)
            val etag = tag()
            f.files[r.file] = text to etag
            f.etag = tag()
            return etag
        }

        override fun rename(r: RemoteRecipe, to: String): Boolean {
            if (folders.containsKey(to)) return false
            val (name, f) = folderOf(r) ?: throw Changed("gone")
            folders.remove(name)
            folders[to] = f
            if (renameChangesEtags) {
                f.files.replaceAll { _, v -> v.first to tag() }
                f.etag = tag()
            }
            return true
        }

        override fun delete(r: RemoteRecipe) {
            val (name, f) = folderOf(r) ?: return
            val now = f.files[r.file] ?: return
            if (now.second != r.fileEtag) throw Changed(name)
            f.files.remove(r.file)
            f.etag = tag()
            if (f.files.isEmpty()) folders.remove(name)
        }
    }

    /** The phone's recipes, with the store's rules: put and remove only from what was seen. */
    class FakePhone : Local {
        val recipes = linkedMapOf<String, Recipe>()
        private var clock = 0
        private var ids = 0

        override fun recipes() = recipes.values.toList()
        override fun put(recipe: Recipe, expect: String?): Boolean {
            if (recipes[recipe.id]?.modified != expect) return false
            recipes[recipe.id] = recipe
            return true
        }
        override fun remove(id: String, expect: String): Boolean {
            if (recipes[id]?.modified != expect) return false
            recipes.remove(id)
            return true
        }
        override fun newId() = "new-${ids++}"
        override fun now() = "2026-10-19T12:00:%02d-04:00".format(clock++)

        /** As the editor saves: stamped now. */
        fun save(r: Recipe) {
            recipes[r.id] = r.copy(modified = now(), created = r.created.ifEmpty { "2026-10-19T09:00:00-04:00" })
        }

        fun byName(name: String) = recipes.values.singleOrNull { it.name == name }
    }

    private val cloud = FakeCloud()
    private val phone = FakePhone()
    private var links = emptyMap<String, Link>()

    private fun sync(): Sync {
        val s = Sync(cloud, phone, links, " (phone)")
        s.run()
        links = s.links.toMap()
        return s
    }

    private fun recipe(id: String, name: String, vararg ingredients: String) =
        Recipe(id = id, name = name, ingredients = ingredients.toList())

    private fun cookbookRecipe(name: String, vararg ingredients: String): JSONObject =
        Cookbook.toJson(Recipe(id = "", name = name, ingredients = ingredients.toList(), modified = "2026-10-10T14:00:00+0000"))
            .apply { remove("identifier") }
            .put("id", "999")

    @Test
    fun newHereIsSentInCookbooksLayout() {
        phone.save(recipe("a", "Pancakes", "2 eggs"))
        phone.save(recipe("b", "Mac & Cheese", "pasta"))
        phone.save(recipe("c", "Soup", "water"))
        assertTrue(sync().wroteThere)
        assertEquals(setOf("Pancakes", "Mac _ Cheese", "Soup"), cloud.folders.keys)
        assertEquals("Pancakes", cloud.recipe("Pancakes")!!.getString("name"))
        assertEquals("a", cloud.recipe("Pancakes")!!.getString("identifier"))
        assertEquals(3, links.size)
        // Nothing to do the second time.
        assertFalse(sync().wroteThere)
    }

    @Test
    fun newThereIsFetched() {
        cloud.putThere("Oat Flapjacks", cookbookRecipe("Oat Flapjacks", "250g oats"))
        sync()
        val r = phone.byName("Oat Flapjacks")!!
        assertEquals(listOf("250g oats"), r.ingredients)
        assertEquals("2026-10-10T14:00:00+0000", r.modified)
        assertEquals(1, links.size)
        // Fetched is not changed here: the next sync sends nothing back.
        assertFalse(sync().wroteThere)
    }

    @Test
    fun anEditHereIsSentAndAnEditThereIsFetched() {
        phone.save(recipe("a", "Pancakes", "2 eggs"))
        sync()
        phone.save(phone.recipes.getValue("a").copy(ingredients = listOf("3 eggs")))
        sync()
        assertEquals("3 eggs", cloud.recipe("Pancakes")!!.getJSONArray("recipeIngredient").getString(0))

        cloud.putThere("Pancakes", cloud.recipe("Pancakes")!!.put("recipeIngredient", org.json.JSONArray(listOf("4 eggs"))))
        sync()
        assertEquals(listOf("4 eggs"), phone.recipes.getValue("a").ingredients)
        assertEquals(1, phone.recipes.size)
        assertEquals(1, cloud.folders.size)
    }

    @Test
    fun editsOnBothSidesKeepBoth() {
        phone.save(recipe("a", "Pancakes", "2 eggs"))
        sync()
        phone.save(phone.recipes.getValue("a").copy(ingredients = listOf("3 eggs")))
        cloud.putThere("Pancakes", cloud.recipe("Pancakes")!!.put("recipeIngredient", org.json.JSONArray(listOf("4 eggs"))))
        sync()
        // The server's keeps the name and the id; the phone's comes up beside it.
        assertEquals(listOf("4 eggs"), phone.recipes.getValue("a").ingredients)
        assertEquals(listOf("3 eggs"), phone.byName("Pancakes (phone)")!!.ingredients)
        assertEquals(setOf("Pancakes", "Pancakes (phone)"), cloud.folders.keys)
        assertEquals("3 eggs", cloud.recipe("Pancakes (phone)")!!.getJSONArray("recipeIngredient").getString(0))
        assertFalse(sync().wroteThere)
    }

    @Test
    fun theSameEditOnBothSidesIsNoConflict() {
        phone.save(recipe("a", "Pancakes", "2 eggs"))
        sync()
        phone.save(phone.recipes.getValue("a").copy(ingredients = listOf("3 eggs")))
        cloud.putThere("Pancakes", cloud.recipe("Pancakes")!!.put("recipeIngredient", org.json.JSONArray(listOf("3 eggs"))))
        sync()
        assertEquals(1, phone.recipes.size)
        assertEquals(1, cloud.folders.size)
    }

    @Test
    fun aDeletionHereDeletesThere() {
        phone.save(recipe("a", "Pancakes", "2 eggs"))
        phone.save(recipe("b", "Soup", "water"))
        sync()
        // A picture Cookbook downloaded sits beside Soup's recipe.
        cloud.folders.getValue("Soup").files["full.jpg"] = "jpeg" to "\"img\""
        phone.recipes.remove("a")
        phone.recipes.remove("b")
        sync()
        assertNull(cloud.folders["Pancakes"])
        // The picture is never deleted: the folder stays, without its recipe.
        assertEquals(setOf("full.jpg"), cloud.folders.getValue("Soup").files.keys)
        assertTrue(links.isEmpty())
    }

    @Test
    fun aDeletionThereDeletesHere() {
        phone.save(recipe("a", "Pancakes", "2 eggs"))
        sync()
        cloud.deleteThere("Pancakes")
        sync()
        assertTrue(phone.recipes.isEmpty())
        assertTrue(links.isEmpty())
    }

    @Test
    fun aChangeBeatsADeletion() {
        phone.save(recipe("a", "Pancakes", "2 eggs"))
        phone.save(recipe("b", "Soup", "water"))
        sync()
        // Deleted here, changed there: it comes back.
        phone.recipes.remove("a")
        cloud.putThere("Pancakes", cloud.recipe("Pancakes")!!.put("recipeIngredient", org.json.JSONArray(listOf("4 eggs"))))
        // Deleted there, changed here: it goes up again.
        cloud.deleteThere("Soup")
        phone.save(phone.recipes.getValue("b").copy(ingredients = listOf("stock")))
        sync()
        assertEquals(listOf("4 eggs"), phone.recipes.getValue("a").ingredients)
        assertEquals("stock", cloud.recipe("Soup")!!.getJSONArray("recipeIngredient").getString(0))
        assertEquals(2, links.size)
    }

    @Test
    fun aRemoteFileChangedMidSyncIsNotWrittenOver() {
        phone.save(recipe("a", "Pancakes", "2 eggs"))
        sync()
        phone.save(phone.recipes.getValue("a").copy(ingredients = listOf("3 eggs")))
        cloud.meddle = {
            cloud.putThere("Pancakes", cloud.recipe("Pancakes")!!.put("recipeIngredient", org.json.JSONArray(listOf("5 eggs"))))
        }
        sync()
        assertEquals("5 eggs", cloud.recipe("Pancakes")!!.getJSONArray("recipeIngredient").getString(0))
        // The next sync sees both changed, and keeps both.
        sync()
        assertEquals(listOf("5 eggs"), phone.recipes.getValue("a").ingredients)
        assertEquals(listOf("3 eggs"), phone.byName("Pancakes (phone)")!!.ingredients)
    }

    @Test
    fun aRenameHereRenamesTheFolder() {
        phone.save(recipe("a", "Pancakes", "2 eggs"))
        sync()
        val id = cloud.folders.getValue("Pancakes").id
        phone.save(phone.recipes.getValue("a").copy(name = "Buttermilk Pancakes"))
        sync()
        assertEquals(setOf("Buttermilk Pancakes"), cloud.folders.keys)
        assertEquals(id, cloud.folders.getValue("Buttermilk Pancakes").id)
        assertEquals("Buttermilk Pancakes", cloud.recipe("Buttermilk Pancakes")!!.getString("name"))
    }

    @Test
    fun aRenameIsNotTakenForAConflictWhereAMoveChangesEtags() {
        cloud.renameChangesEtags = true
        phone.save(recipe("a", "Pancakes", "2 eggs"))
        sync()
        phone.save(phone.recipes.getValue("a").copy(name = "Crêpes"))
        sync()
        assertEquals("Crêpes", cloud.recipe("Crêpes")!!.getString("name"))
        sync()
        sync()
        assertEquals(1, phone.recipes.size)
        assertEquals(setOf("Crêpes"), cloud.folders.keys)
        assertEquals("Crêpes", phone.recipes.getValue("a").name)
    }

    @Test
    fun aRenameThereIsFollowedNotDuplicated() {
        phone.save(recipe("a", "Pancakes", "2 eggs"))
        sync()
        val json = cloud.recipe("Pancakes")!!.put("name", "Crêpes")
        val f = cloud.folders.remove("Pancakes")!!
        cloud.folders["Crêpes"] = f
        cloud.putThere("Crêpes", json)
        sync()
        assertEquals(1, phone.recipes.size)
        assertEquals("Crêpes", phone.recipes.getValue("a").name)
    }

    @Test
    fun theSameRecipeOnBothSidesIsMatchedByName() {
        phone.save(recipe("a", "Oat Flapjacks", "250g oats"))
        cloud.putThere("Oat Flapjacks", cookbookRecipe("Oat Flapjacks", "250g oats"))
        sync()
        assertEquals(1, phone.recipes.size)
        assertEquals(1, cloud.folders.size)
        assertEquals(setOf("a"), links.keys)
    }

    @Test
    fun twoDifferentRecipesOfOneNameAreBothKept() {
        phone.save(recipe("a", "Flapjacks", "250g oats"))
        cloud.putThere("Flapjacks", cookbookRecipe("Flapjacks", "flour", "milk"))
        sync()
        assertEquals(listOf("250g oats"), phone.byName("Flapjacks (phone)")!!.ingredients)
        assertEquals(listOf("flour", "milk"), phone.byName("Flapjacks")!!.ingredients)
        assertEquals(setOf("Flapjacks", "Flapjacks (phone)"), cloud.folders.keys)
        assertFalse(sync().wroteThere)
    }

    @Test
    fun anotherRecipeWithTheNameGetsANumberedFolder() {
        phone.save(recipe("a", "Soup", "water"))
        sync()
        phone.save(recipe("b", "Soup", "stock"))
        sync()
        assertEquals(setOf("Soup", "Soup (2)"), cloud.folders.keys)
        assertEquals("stock", cloud.recipe("Soup (2)")!!.getJSONArray("recipeIngredient").getString(0))
    }

    @Test
    fun everythingGoneAtOnceDeletesNothingHere() {
        phone.save(recipe("a", "Pancakes", "2 eggs"))
        phone.save(recipe("b", "Soup", "water"))
        phone.save(recipe("c", "Stew", "barley"))
        sync()
        cloud.folders.clear()
        sync()
        assertEquals(3, phone.recipes.size)
        assertEquals(setOf("Pancakes", "Soup", "Stew"), cloud.folders.keys)
    }

    @Test
    fun linksSurviveBeingWrittenDown() {
        phone.save(recipe("a", "Pancakes", "2 eggs"))
        sync()
        val back = Link.listFromJson(Link.listToJson(links.values))
        assertEquals(links, back)
    }
}
