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
 *
 * The account can instead be the phone's own, the one Home holds (HomeAccount.kt). Then only
 * the choice is this app's (who, and the stamp last taken); the sealed account here is a mirror
 * of Home's, written from Home on each start and change and let go as soon as Home signs out,
 * is gone, or holds another account ([followHome]).
 */
class Cloud private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val store = Store.get(context)
    private val prefs = context.getSharedPreferences("nextcloud", Context.MODE_PRIVATE)
    private val linksFile = File(context.filesDir, "sync/links.json")
    private val phoneSuffix = context.getString(R.string.phone_copy_suffix)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var pending: Job? = null
    private var lastTry = 0L

    /**
     * The signed-in account, opened from its sealed form. One saved in plain text by an earlier
     * version is read once and replaced by the sealed form on the spot.
     */
    private fun loadAccount(): Account? {
        val read = readStoredAccount(prefs.getString(ACCOUNT, null), Secrets::seal, Secrets::open)
        read.migrated?.let { prefs.edit().putString(ACCOUNT, it).apply() }
        return read.account
    }

    enum class State { OFF, IDLE, SYNCING, UNREACHABLE, REFUSED, FAILED }

    private val _account = MutableStateFlow(loadAccount())
    val account: StateFlow<Account?> = _account

    private val _folder = MutableStateFlow(prefs.getString(FOLDER, null) ?: DEFAULT_FOLDER)
    val folder: StateFlow<String> = _folder

    private val _state = MutableStateFlow(if (_account.value == null) State.OFF else State.IDLE)
    val state: StateFlow<State> = _state

    private val _homeWho = MutableStateFlow(prefs.getString(HOME_WHO, null))
    /**
     * The account chosen from Home ([HomeAccount.Held.who]), or null when the app signs in on its
     * own. Set with [account] null while Home is signed out: the app waits for the same account.
     */
    val homeWho: StateFlow<String?> = _homeWho

    private val _last = MutableStateFlow(prefs.getLong(LAST, 0L))
    /** When the last sync finished, in milliseconds; 0 for never. */
    val lastSync: StateFlow<Long> = _last

    init {
        store.onChanged = { syncSoon() }
    }

    /** On opening the app: Home asked about its account, then a sync, unless one was tried in the last minute. */
    fun onOpen() {
        scope.launch {
            val took = lock.withLock { applyHome() }
            if (took || System.currentTimeMillis() - lastTry > 60_000) syncNow()
        }
    }

    /**
     * Where the account came from Home: asks Home again (on a change notice from it) and does as
     * [HomeAccount.follow] says, off the main thread. A sync follows when a new password was taken.
     */
    fun followHome() {
        if (_homeWho.value == null) return
        scope.launch {
            if (lock.withLock { applyHome() }) syncNow()
        }
    }

    /**
     * Asks Home and does as [HomeAccount.follow] says; true when an account was taken. A new
     * password for the same account takes the old one's place, with the folder and what was synced
     * kept. When Home has signed out, is gone, or holds another account, the account is let go and
     * this reads as signed out, quietly; the recipes here, the folder and what was synced stay, so
     * the same account coming back picks up where it left off. Called holding [lock].
     */
    private fun applyHome(): Boolean {
        val who = _homeWho.value ?: return false
        // With nothing held (signed out there, or no keystore to keep the mirror) the stamp is empty.
        val stamp = if (_account.value == null) "" else prefs.getString(HOME_STAMP, null).orEmpty()
        val answer = HomeAccount.ask(appContext)
        // Stopped using it (signOut, which does not wait for the lock) while Home was asked.
        if (_homeWho.value != who) return false
        return when (val step = HomeAccount.follow(who, stamp, answer)) {
            is HomeAccount.Step.Keep -> {
                // The same account and password, but Home has since learnt the user id (an
                // account Home kept before it asked for one): the paths take it from now on.
                val held = (answer as? HomeAccount.Answer.Has)?.held
                val now = _account.value
                if (held != null && now != null && now.uid != held.uid && now.user == held.user) {
                    val a = now.copy(uid = held.uid)
                    Secrets.seal(a.toJson().toString())?.let { prefs.edit().putString(ACCOUNT, it).apply() }
                    _account.value = a
                }
                false
            }
            is HomeAccount.Step.Take -> {
                val a = Account(step.held.server, step.held.user, step.held.password, step.held.uid)
                val sealed = Secrets.seal(a.toJson().toString())
                prefs.edit().apply { if (sealed != null) putString(ACCOUNT, sealed) else remove(ACCOUNT) }
                    .putString(HOME_STAMP, step.held.stamp).apply()
                _account.value = a
                _state.value = State.IDLE
                true
            }
            is HomeAccount.Step.Drop -> {
                pending?.cancel()
                _account.value = null
                _state.value = State.OFF
                prefs.edit().remove(ACCOUNT).putString(HOME_STAMP, "")
                    .apply { if (!step.following) remove(HOME_WHO) }.apply()
                if (!step.following) _homeWho.value = null
                false
            }
        }
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
        // An account taken from Home is never used past a change there.
        applyHome()
        val a = _account.value ?: return@withLock
        val folder = _folder.value
        lastTry = System.currentTimeMillis()
        _state.value = State.SYNCING
        val sync = Sync(NextcloudFolder(a, folder), StoreSide(store), loadLinks(), phoneSuffix)
        var outcome = try {
            sync.run()
            State.IDLE
        } catch (_: Refused) {
            State.REFUSED
        } catch (_: Unreachable) {
            State.UNREACHABLE
        } catch (_: IOException) {
            State.FAILED
        } catch (_: Exception) {
            // A server answer this could not read (bad XML, a bad address in it, anything else).
            // Said as a failed sync rather than a crash.
            State.FAILED
        }
        // Signed out, or moved to another folder, while it ran: what it found is not kept.
        if (_account.value != a || _folder.value != folder) return@withLock
        // The phone's storage would not take the links: a failed sync, not a crash; the next
        // one finds the same recipes by name and stamp.
        try {
            saveLinks(sync.links.values)
        } catch (_: IOException) {
            outcome = State.FAILED
        }
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
     * together. Throws [Refused] or [Unreachable] when the account will not do. With [home], the
     * account is Home's, followed from now on ([followHome]); without, any choice of Home's is let go.
     */
    suspend fun signIn(given: Account, home: HomeAccount.Held? = null) {
        val (a, folder) = withContext(Dispatchers.IO) {
            // The app's own sign-in has only the login name: the server says the user id the
            // file paths take. Home's account comes with its user id.
            val checked = if (home == null) userId(given)?.let { given.copy(uid = it) } ?: given else given
            NextcloudFolder(checked, DEFAULT_FOLDER).probe()
            checked to (CookbookApp.folder(checked) ?: DEFAULT_FOLDER)
        }
        lock.withLock {
            // Only the sealed form is ever written; with no keystore the account lasts this run only.
            val sealed = Secrets.seal(a.toJson().toString())
            prefs.edit().apply { if (sealed != null) putString(ACCOUNT, sealed) else remove(ACCOUNT) }
                .putString(FOLDER, folder).remove(LAST)
                .apply {
                    if (home != null) putString(HOME_WHO, home.who).putString(HOME_STAMP, home.stamp)
                    else remove(HOME_WHO).remove(HOME_STAMP)
                }.apply()
            linksFile.delete()
            _homeWho.value = home?.who
            _folder.value = folder
            _last.value = 0L
            _account.value = a
            _state.value = State.IDLE
        }
        _signInProblem.value = null
        syncNow()
    }

    private var browserSignIn: Job? = null
    private val _awaitingBrowser = MutableStateFlow(false)
    /** Whether a sign-in begun in the browser is still being waited for. */
    val awaitingBrowser: StateFlow<Boolean> = _awaitingBrowser
    private val _signInProblem = MutableStateFlow<Int?>(null)
    /**
     * What went wrong with the last browser sign-in, as the id of a string to show; null for
     * nothing. Cleared by any sign-in that works, by signing out, and when the wait is given up.
     */
    val signInProblem: StateFlow<Int?> = _signInProblem

    /**
     * Waits for the reader to finish signing in in the browser, then keeps the account the server
     * hands over. It runs here rather than on the screen, so the screen going (Back, or the system
     * making it again) while the browser is in front does not lose a sign-in finished there.
     */
    fun awaitBrowser(started: LoginFlow.Started) {
        browserSignIn?.cancel()
        _signInProblem.value = null
        _awaitingBrowser.value = true
        browserSignIn = scope.launch {
            try {
                // The server gives twenty minutes to finish signing in.
                repeat(600) {
                    delay(2000)
                    val got = runCatching { LoginFlow.poll(started) }.getOrNull()
                    if (got != null) {
                        _awaitingBrowser.value = false
                        _signInProblem.value = try {
                            signIn(got)
                            null
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (e is Refused) R.string.cloud_refused else R.string.cloud_unreachable
                        }
                        return@launch
                    }
                }
            } finally {
                _awaitingBrowser.value = false
            }
        }
    }

    /** Stops waiting for the browser, when an app password is to be typed in instead. */
    fun stopAwaitingBrowser() {
        browserSignIn?.cancel()
        _signInProblem.value = null
    }

    fun clearSignInProblem() {
        _signInProblem.value = null
    }

    /**
     * The phone's Nextcloud account, taken from Home at the reader's press: checked and kept as
     * one typed in would be, in place of the app's own sign-in, and followed from now on.
     */
    suspend fun signInFromHome(held: HomeAccount.Held) {
        signIn(Account(held.server, held.user, held.password, held.uid), held)
    }

    /**
     * Forgets the account and what was synced, and any choice of Home's account (its Stop using
     * it); the recipes here stay as they are.
     */
    fun signOut() {
        pending?.cancel()
        _signInProblem.value = null
        _account.value = null
        _state.value = State.OFF
        _last.value = 0L
        prefs.edit().remove(ACCOUNT).remove(LAST).remove(FOLDER).remove(HOME_WHO).remove(HOME_STAMP).apply()
        _homeWho.value = null
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
            if (!tmp.renameTo(linksFile)) throw IOException("could not keep ${linksFile.name}")
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
        private const val HOME_WHO = "home_who"
        private const val HOME_STAMP = "home_stamp"

        @Volatile
        private var cloud: Cloud? = null

        fun get(context: Context): Cloud =
            cloud ?: synchronized(this) { cloud ?: Cloud(context.applicationContext).also { cloud = it } }
    }
}
