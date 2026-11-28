package com.example.vr180.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.vr180.R
import com.example.vr180.data.ProfileStore
import com.example.vr180.model.SmbProfile
import com.example.vr180.smb.LocalNetworkPermission
import com.example.vr180.smb.SmbRepository
import com.example.vr180.source.FileSource
import com.example.vr180.source.LocalFileSource
import com.example.vr180.source.SmbFileSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

private data class NasTarget(val profile: SmbProfile, val path: String = "") {
    val key get() = profile.id to path
}

/** 首頁：影片來源清單（NAS 連線與本機資料夾），點一下就開。editNas(null) 是新增。 */
@Composable
fun HomeScreen(store: ProfileStore, editNas: (String?) -> Unit, openSource: (FileSource) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var profiles by remember { mutableStateOf(store.loadSmbProfiles()) }
    var folders by remember { mutableStateOf(store.loadFolders()) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<Pair<Pair<String, String>, String>?>(null) }
    var pending by remember { mutableStateOf<NasTarget?>(null) }

    fun connect(target: NasTarget) {
        val p = target.profile
        error = null
        val source = SmbFileSource(p, target.path)
        if (target.path.isNotEmpty()) {
            // Let the browser load the bookmarked folder directly and offer Retry/Up if it fails.
            openSource(source)
            return
        }
        busyId = p.id
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    SmbRepository.configure(p.conn)
                    SmbRepository.list(p.conn.host, p.conn.share, "")
                }
            }
            busyId = null
            r.onSuccess { openSource(source) }
                .onFailure { error = target.key to context.getString(R.string.ui_home_connect_failed, friendlyError(context, it)) }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val target = pending
        pending = null
        if (target == null) return@rememberLauncherForActivityResult
        if (ok) connect(target) else error = target.key to context.getString(R.string.ui_home_permission_needed)
    }

    fun openNas(target: NasTarget) {
        if (LocalNetworkPermission.granted(context)) connect(target)
        else { pending = target; permissionLauncher.launch(LocalNetworkPermission.NAME) }
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val cr = context.contentResolver
            // 盡量取得讀寫權限（寫入場景校正檔用）；唯讀的位置就只拿讀取權限
            runCatching {
                cr.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }.recoverCatching { cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val f = ProfileStore.LocalFolder(uri.toString(), LocalFileSource.displayName(context, uri))
            store.saveFolders(folders.filter { it.uri != f.uri } + f)
            folders = store.loadFolders()
        }
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.ui_home_sources_title), style = MaterialTheme.typography.titleMedium)
        if (profiles.isEmpty() && folders.isEmpty()) {
            Text(stringResource(R.string.ui_home_empty), style = MaterialTheme.typography.bodyMedium)
        }
        profiles.forEach { p ->
            val target = NasTarget(p)
            SourceRow(
                icon = "🖧",
                title = p.label,
                detail = when {
                    busyId == p.id -> stringResource(R.string.ui_home_connecting)
                    p.name.isBlank() -> p.conn.user.ifBlank { stringResource(R.string.ui_home_guest) }
                    else -> "\\\\${p.conn.host}\\${p.conn.share} · " +
                        p.conn.user.ifBlank { stringResource(R.string.ui_home_guest) }
                },
                error = error?.takeIf { it.first == target.key }?.second,
                enabled = busyId == null && pending == null,
                onClick = { openNas(target) },
                menu = listOf(
                    stringResource(R.string.ui_edit) to { editNas(p.id) },
                    stringResource(R.string.ui_delete) to {
                        store.saveSmbProfiles(profiles.filter { it.id != p.id })
                        profiles = store.loadSmbProfiles()
                    },
                ),
            )
        }
        folders.forEach { f ->
            SourceRow(
                icon = "📁",
                title = f.name,
                detail = stringResource(R.string.ui_home_local),
                error = null,
                enabled = busyId == null,
                onClick = { openSource(LocalFileSource(context, Uri.parse(f.uri), f.name)) },
                menu = listOf(stringResource(R.string.ui_remove) to {
                    runCatching {
                        context.contentResolver.releasePersistableUriPermission(Uri.parse(f.uri),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    }
                    store.saveFolders(folders.filter { it.uri != f.uri })
                    folders = store.loadFolders()
                }),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { editNas(null) }) { Text(stringResource(R.string.ui_home_add_nas)) }
            OutlinedButton(onClick = { pickFolder.launch(null) }) { Text(stringResource(R.string.ui_home_add_folder)) }
        }
        Text(stringResource(R.string.ui_home_local_hint), style = MaterialTheme.typography.bodySmall)
        if (profiles.any { it.bookmarks.isNotEmpty() }) {
            Text(stringResource(R.string.ui_home_bookmarks), style = MaterialTheme.typography.titleMedium)
            profiles.forEach { p ->
                p.bookmarks.forEach { path ->
                    val target = NasTarget(p, path)
                    key(target.key) {
                        SourceRow(
                            icon = "★",
                            title = path.substringAfterLast('/'),
                            detail = "${p.label} / $path",
                            error = error?.takeIf { it.first == target.key }?.second,
                            enabled = busyId == null && pending == null,
                            onClick = { openNas(target) },
                            menu = listOf(stringResource(R.string.ui_bookmark_remove) to {
                                try {
                                    store.setSmbBookmark(p.id, path, saved = false)
                                    profiles = store.loadSmbProfiles()
                                    error = null
                                } catch (e: IOException) {
                                    error = target.key to context.getString(
                                        R.string.ui_bookmark_update_failed, friendlyError(context, e),
                                    )
                                }
                            }),
                        )
                    }
                }
            }
        }
    }
}

/** 來源清單的一列：點一下開啟，長按叫出 menu。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SourceRow(
    icon: String,
    title: String,
    detail: String,
    error: String?,
    enabled: Boolean,
    onClick: () -> Unit,
    menu: List<Pair<String, () -> Unit>>,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Card(
            Modifier.fillMaxWidth().combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = { menuOpen = true },
            ),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("$icon $title", style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            menu.forEach { (label, action) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { menuOpen = false; action() })
            }
        }
    }
}
