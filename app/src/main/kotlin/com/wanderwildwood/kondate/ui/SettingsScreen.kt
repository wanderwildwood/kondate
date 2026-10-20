package com.wanderwildwood.kondate.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.kondate.R
import com.wanderwildwood.kondate.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * The few settings, and the recipes in and out of a file: the whole collection to keep
 * somewhere else, and a file of recipes from another phone or another app brought in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(store: Store, onBack: () -> Unit, onAbout: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val all by store.all.collectAsStateWithLifecycle()

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(store.export().toByteArray()) } != null
                }.getOrDefault(false)
            }
            Elsewhere.say(context, if (ok) R.string.saved_to_file else R.string.save_failed)
        }
    }
    val bring = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch { bringIn(context, store, uri)?.let(onOpen) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.settings_title)) },
                navigationIcon = { BarButton(Icons.Back, stringResource(R.string.cd_back), onBack) },
                actions = { BarButton(Icons.Info, stringResource(R.string.cd_about), onAbout) },
            )
        },
    ) { padding ->
        PagedColumn(modifier = Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp)) {
            item { Spacer(Modifier.height(8.dp)) }
            item(key = "jump") {
                val jumpShown by rememberListJumpShown()
                SwitchRow(stringResource(R.string.list_jump_setting), jumpShown) { ListJumpPrefs.setShown(context, it) }
            }
            item(key = "files") { Heading(stringResource(R.string.settings_files)) }
            item(key = "export") {
                SettingRow(
                    stringResource(R.string.export_all),
                    pluralStringResource(R.plurals.recipe_count, all.size, all.size),
                    onClick = if (all.isEmpty()) null else ({ export.launch("recipes-${LocalDate.now()}.json") }),
                )
            }
            item(key = "import") {
                SettingRow(stringResource(R.string.import_file), stringResource(R.string.import_file_note)) {
                    bring.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                }
            }
        }
    }
}
