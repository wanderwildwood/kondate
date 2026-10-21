package com.wanderwildwood.kondate.sync

import android.content.Context
import com.wanderwildwood.kondate.R
import com.wanderwildwood.kondate.data.Recipe
import com.wanderwildwood.kondate.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * The recipes kept in step with a Nextcloud, when the reader has signed in to one; nothing at
 * all happens here until then, and the app works the same without it.
 *
 * A sync runs when the app is opened, a few seconds after a recipe is saved or deleted (so a
 * run of edits goes as one), and when the reader asks for one. One runs at a time.
 *
 * The account is kept in the app's private preferences, which no other app can read and which
 * are not backed up (the app allows no backup). The password in it is an app password made for
 * this phone, which the server can revoke on its own, without the real one changing.
 */
class Cloud private constructor(context: Context) {

    private val store = Store.get(context)
    private val prefs = context.getSharedPreferences("nextcloud", Context.MODE_PRIVATE)
    private val linksFile = File(context.filesDir, "sync/links.json")
    private val phoneSuffix = context.getString(R.string.phone_copy_suffix)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var pending: Job? = null
    private var lastTry = 0L

    enum class State { OFF, IDLE, SYNCING, UNREACHABLE, REFUSED, FAILED }

    private val _account = MutableStateFlow(
        prefs.getString(ACCOUNT, null)?.let { runCatching { Account.fromJson(JSONObject(it)) }.getOrNull() }
    )
    val account: StateFlow<Account?> = _account

    private val _folder = MutableStateFlow(prefs.getString(FOLDER, null) ?: DEFAULT_FOLDER)
    val folder: StateFlow<String> = _folder

    private val _state = MutableStateFlow(if (_account.value == null) State.OFF else State.IDLE)
    val state: StateFlow<State> = _state

    private val _last = MutableStateFlow(prefs.getLong(LAST, 0L))
    /** When the last sync finished, in milliseconds; 0 for never. */
    val lastSync: StateFlow<Long> = _last

    init {
        store.onChanged = { syncSoon() }
    }

    /** On opening the app: a sync, unless one was tried in the last minute. */
    fun onOpen() {
        if (System.currentTimeMillis() - lastTry > 60_000) syncNow()
    }

    fun syncNow() {
        if (_account.value == null) return
        pending?.cancel()
        scope.launch { sync() }
    }

    /**
     * A sync now, as the list asks for one when it is pulled down from its top, returning once it
     * is done with the state it left. Waits for one already running, then runs its own.
     */
    suspend fun syncAndWait(): State {
        if (_account.value == null) return State.OFF
        pending?.cancel()
        withContext(Dispatchers.IO) { sync() }
        return _state.value
    }

    private fun syncSoon() {
        if (_account.value == null) return
        pending?.cancel()
        pending = scope.launch {
            delay(5_000)
            sync()
        }
    }

    private suspend fun sync() = lock.withLock {
        val a = _account.value ?: return@withLock
        val folder = _folder.value
        lastTry = System.currentTimeMillis()
        _state.value = State.SYNCING
        val sync = Sync(NextcloudFolder(a, folder), StoreSide(store), loadLinks(), phoneSuffix)
        val outcome = try {
            sync.run()
            State.IDLE
        } catch (_: Refused) {
            State.REFUSED
        } catch (_: Unreachable) {
            State.UNREACHABLE
        } catch (_: IOException) {
            State.FAILED
        } catch (_: RuntimeException) {
            // A server answer this could not read. Said as a failed sync rather than a crash.
            State.FAILED
        }
        // Signed out, or moved to another folder, while it ran: what it found is not kept.
        if (_account.value != a || _folder.value != folder) return@withLock
        saveLinks(sync.links.values)
        if (outcome == State.IDLE) {
            _last.value = System.currentTimeMillis()
            prefs.edit().putLong(LAST, _last.value).apply()
        }
        _state.value = outcome
        if (sync.wroteThere) CookbookApp.reindex(a)
    }

    /**
     * Keeps [a] after making sure it opens the files on the server, and starts the first sync.
     * The folder is Cookbook's own, when there is a Cookbook to say which, so the two start out
     * together. Throws [Refused] or [Unreachable] when the account will not do.
     */
    suspend fun signIn(a: Account) {
        val folder = withContext(Dispatchers.IO) {
            NextcloudFolder(a, DEFAULT_FOLDER).probe()
            CookbookApp.folder(a) ?: DEFAULT_FOLDER
        }
        lock.withLock {
            prefs.edit().putString(ACCOUNT, a.toJson().toString()).putString(FOLDER, folder).remove(LAST).apply()
            linksFile.delete()
            _folder.value = folder
            _last.value = 0L
            _account.value = a
            _state.value = State.IDLE
        }
        syncNow()
    }

    /** Forgets the account and what was synced; the recipes here stay as they are. */
    fun signOut() {
        pending?.cancel()
        _account.value = null
        _state.value = State.OFF
        _last.value = 0L
        prefs.edit().remove(ACCOUNT).remove(LAST).remove(FOLDER).apply()
        _folder.value = DEFAULT_FOLDER
        scope.launch { lock.withLock { linksFile.delete() } }
    }

    /**
     * Another folder in the same Nextcloud. Nothing synced with the old one carries over: the
     * recipes here are matched by name with the ones in the new folder, and the rest sent.
     */
    fun setFolder(typed: String) {
        val clean = typed.trim().trim('/').ifEmpty { DEFAULT_FOLDER }
        if (clean == _folder.value) return
        scope.launch {
            lock.withLock {
                linksFile.delete()
                prefs.edit().putString(FOLDER, clean).remove(LAST).apply()
                _folder.value = clean
                _last.value = 0L
            }
            sync()
        }
    }

    private fun loadLinks(): Map<String, Link> =
        runCatching { Link.listFromJson(linksFile.readText()) }.getOrDefault(emptyMap())

    private fun saveLinks(links: Collection<Link>) {
        linksFile.parentFile?.mkdirs()
        val tmp = File(linksFile.parentFile, "links.json.tmp")
        tmp.writeText(Link.listToJson(links))
        if (!tmp.renameTo(linksFile)) {
            linksFile.delete()
            tmp.renameTo(linksFile)
        }
    }

    /** The store as a sync sees it. */
    private class StoreSide(private val store: Store) : Local {
        override fun recipes(): List<Recipe> = store.all.value
        override fun put(recipe: Recipe, expect: String?) = store.putSynced(recipe, expect)
        override fun remove(id: String, expect: String) = store.removeSynced(id, expect)
        override fun newId() = store.newId()
        override fun now() = store.now()
    }

    companion object {
        /** Cookbook's own default, in English. */
        const val DEFAULT_FOLDER = "Recipes"
        private const val ACCOUNT = "account"
        private const val FOLDER = "folder"
        private const val LAST = "last_sync"

        @Volatile
        private var cloud: Cloud? = null

        fun get(context: Context): Cloud =
            cloud ?: synchronized(this) { cloud ?: Cloud(context.applicationContext).also { cloud = it } }
    }
}
