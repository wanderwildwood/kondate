package com.wanderwildwood.kondate.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.kondate.R
import com.wanderwildwood.kondate.sync.Account
import com.wanderwildwood.kondate.sync.Cloud
import com.wanderwildwood.kondate.sync.HomeAccount
import com.wanderwildwood.kondate.sync.LoginFlow
import com.wanderwildwood.kondate.sync.Refused
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Nextcloud: signing in, and once in, the folder, a sync on demand and signing out. Signing in
 * goes through the server's own page in the browser, as Nextcloud's apps do; a phone without a
 * browser (a Kompakt may have none) takes an app password typed in instead. Where Home holds
 * the phone's Nextcloud account, one row uses that one instead (sync/HomeAccount.kt).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NextcloudScreen(cloud: Cloud, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val account by cloud.account.collectAsStateWithLifecycle()
    val folder by cloud.folder.collectAsStateWithLifecycle()
    val state by cloud.state.collectAsStateWithLifecycle()
    val last by cloud.lastSync.collectAsStateWithLifecycle()

    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf(false) }
    // Asking the server for its sign-in page; the wait for the browser after it is the Cloud's.
    var starting by remember { mutableStateOf<Job?>(null) }
    val awaiting by cloud.awaitingBrowser.collectAsStateWithLifecycle()
    val browserProblem by cloud.signInProblem.collectAsStateWithLifecycle()
    var problem by remember { mutableStateOf<Int?>(null) }
    var newFolder by remember(folder) { mutableStateOf(folder) }
    val homeWho by cloud.homeWho.collectAsStateWithLifecycle()
    // Whether Home has an account for this app: asked off the main thread when the screen opens,
    // and again on each change notice from Home.
    var homeAvailable by remember { mutableStateOf(false) }
    var homeChanged by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val stop = HomeAccount.observe(context) { homeChanged++ }
        onDispose { stop() }
    }
    LaunchedEffect(homeChanged, account, homeWho) {
        homeAvailable = withContext(Dispatchers.IO) { HomeAccount.available(context) }
    }

    fun useHome() {
        starting?.cancel()
        cloud.stopAwaitingBrowser()
        scope.launch {
            val held = withContext(Dispatchers.IO) { HomeAccount.read(context) }
            if (held == null) {
                homeAvailable = false
                return@launch
            }
            if (!LoginFlow.plainHttpAllowed(held.server)) {
                problem = R.string.cloud_needs_https
                return@launch
            }
            problem = runCatching { cloud.signInFromHome(held); null }.getOrElse { e ->
                if (e is Refused) R.string.cloud_refused else R.string.cloud_unreachable
            }
        }
    }

    fun keep(a: Account) {
        scope.launch {
            problem = runCatching { cloud.signIn(a); null }.getOrElse { e ->
                if (e is Refused) R.string.cloud_refused else R.string.cloud_unreachable
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.cloud_title)) },
                navigationIcon = { BarButton(Icons.Back, stringResource(R.string.cd_back), onBack) },
            )
        },
    ) { padding ->
        PagedColumn(modifier = Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp), showCutRow = true) {
            item { Spacer(Modifier.height(8.dp)) }
            val a = account
            val fromHome = homeWho != null
            if (a == null && fromHome) {
                // Home signed out (or is gone): so is this app, quietly, until the same account is back there.
                item(key = "home_signed_out") {
                    Note(stringResource(R.string.home_account_signed_out, stringResource(R.string.home_account_what)))
                }
                item(key = "home_stop") { SettingRow(stringResource(R.string.home_account_stop), null) { cloud.signOut() } }
            } else if (a != null) {
                item(key = "account") {
                    val server = a.server.removePrefix("https://")
                    SettingRow(
                        stringResource(R.string.cloud_account),
                        if (fromHome) stringResource(R.string.home_account_in_use, a.user, server)
                        else stringResource(R.string.cloud_signed_in, a.user, server),
                        onClick = null,
                    )
                }
                item(key = "sync") {
                    val status = when (state) {
                        Cloud.State.SYNCING -> stringResource(R.string.cloud_syncing)
                        Cloud.State.UNREACHABLE -> stringResource(R.string.cloud_failed_unreachable)
                        Cloud.State.REFUSED -> stringResource(R.string.cloud_failed_refused)
                        Cloud.State.FAILED -> stringResource(R.string.cloud_failed)
                        else -> if (last == 0L) stringResource(R.string.cloud_never) else stringResource(
                            R.string.cloud_last,
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(last)),
                        )
                    }
                    SettingRow(stringResource(R.string.cloud_sync_now), status, onClick = { cloud.syncNow() })
                }
                item(key = "folder") {
                    Field(stringResource(R.string.cloud_folder), newFolder, { newFolder = it }, words = false)
                }
                if (newFolder.trim().trim('/') != folder) {
                    item(key = "use") { WideButton(stringResource(R.string.cloud_use_folder), filled = true) { cloud.setFolder(newFolder) } }
                }
                item(key = "folder_note") { Note(stringResource(R.string.cloud_folder_note)) }
                if (fromHome) {
                    item(key = "home_stop") { SettingRow(stringResource(R.string.home_account_stop), null) { cloud.signOut() } }
                } else {
                    if (homeAvailable) {
                        item(key = "home_use") { SettingRow(stringResource(R.string.home_account_use), null) { useHome() } }
                    }
                    problem?.let { p -> item(key = "problem") { Note(stringResource(p)) } }
                    item(key = "out") {
                        ArmedRow(stringResource(R.string.cloud_sign_out), stringResource(R.string.cloud_sign_out_armed)) { cloud.signOut() }
                    }
                }
            } else {
                item(key = "explain") { Note(stringResource(R.string.cloud_explain)) }
                if (homeAvailable) {
                    item(key = "home_use") { SettingRow(stringResource(R.string.home_account_use), null) { useHome() } }
                }
                item(key = "server") {
                    Field(stringResource(R.string.cloud_server), server, { server = it; problem = null; cloud.clearSignInProblem() }, hint = stringResource(R.string.cloud_server_hint), url = true)
                }
                if (typed) {
                    item(key = "user") { Field(stringResource(R.string.cloud_user), user, { user = it; problem = null }, words = false) }
                    item(key = "password") {
                        Field(stringResource(R.string.cloud_app_password), password, { password = it; problem = null }, secret = true)
                    }
                    item(key = "password_note") { Note(stringResource(R.string.cloud_app_password_note)) }
                }
                (problem ?: browserProblem)?.let { p -> item(key = "problem") { Note(stringResource(p)) } }
                if (starting != null || awaiting) item(key = "waiting") { Note(stringResource(R.string.cloud_waiting)) }
                item(key = "sign_in") {
                    WideButton(stringResource(R.string.cloud_sign_in), filled = true) {
                        if (server.isBlank()) return@WideButton
                        if (!LoginFlow.plainHttpAllowed(LoginFlow.normalise(server))) {
                            problem = R.string.cloud_needs_https
                            return@WideButton
                        }
                        if (typed) {
                            if (user.isBlank() || password.isBlank()) return@WideButton
                            keep(Account(LoginFlow.normalise(server), user.trim(), password.trim()))
                            return@WideButton
                        }
                        if (starting != null || awaiting) return@WideButton
                        cloud.clearSignInProblem()
                        starting = scope.launch {
                            try {
                                val started = runCatching { withContext(Dispatchers.IO) { LoginFlow.start(server) } }.getOrNull()
                                if (started == null) {
                                    problem = R.string.cloud_unreachable
                                    return@launch
                                }
                                val opened = isRealBrowserFor(context, started.loginUrl) &&
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(started.loginUrl)))
                                    }.isSuccess
                                if (!opened) {
                                    problem = R.string.cloud_no_browser
                                    typed = true
                                    return@launch
                                }
                                cloud.awaitBrowser(started)
                            } finally {
                                starting = null
                            }
                        }
                    }
                }
                if (!typed) {
                    item(key = "type") { WideButton(stringResource(R.string.cloud_type_password)) {
                        typed = true
                        starting?.cancel()
                        cloud.stopAwaitingBrowser()
                    } }
                }
            }
        }
    }
}

/** A line or two of explanation between the rows. */
@Composable
private fun Note(text: String) {
    TextMMD(text = text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 8.dp))
}
