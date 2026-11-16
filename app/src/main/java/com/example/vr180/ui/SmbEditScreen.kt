package com.example.vr180.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.vr180.R
import com.example.vr180.data.ProfileStore
import com.example.vr180.model.SmbConnection
import com.example.vr180.model.SmbProfile
import com.example.vr180.smb.LocalNetworkPermission
import com.example.vr180.smb.SmbDiscovery
import kotlinx.coroutines.delay
import java.util.UUID

/** 搜尋區網最多等幾秒：mDNS 回應通常一兩秒內就到。 */
private const val SEARCH_MS = 8_000L

/** 新增或修改一組 NAS 連線。profileId 為 null 是新增。 */
@Composable
fun SmbEditScreen(store: ProfileStore, profileId: String?, onDone: () -> Unit) {
    val context = LocalContext.current
    val existing = remember(profileId) { store.loadSmbProfiles().firstOrNull { it.id == profileId } }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var host by remember { mutableStateOf(existing?.conn?.host.orEmpty()) }
    var share by remember { mutableStateOf(existing?.conn?.share.orEmpty()) }
    var user by remember { mutableStateOf(existing?.conn?.user.orEmpty()) }
    var pass by remember { mutableStateOf(existing?.conn?.password.orEmpty()) }

    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val found = remember { mutableStateListOf<SmbDiscovery.Found>() }

    // 搜尋只在這段期間進行：時間到、離開畫面都會停止
    if (searching) {
        DisposableEffect(Unit) {
            val d = SmbDiscovery(context) { found += it }
            d.start()
            onDispose { d.stop() }
        }
        LaunchedEffect(Unit) {
            delay(SEARCH_MS)
            searching = false
            searched = true
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { found.clear(); searching = true }
        else error = context.getString(R.string.ui_home_permission_needed)
    }

    fun save() {
        val list = store.loadSmbProfiles()
        val p = SmbProfile(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name.trim(),
            // 網域沒有輸入欄：保留手動寫進檔案的值
            conn = SmbConnection(host.trim(), share.trim(), user, pass, existing?.conn?.domain.orEmpty()),
        )
        store.saveSmbProfiles(if (existing == null) list + p else list.map { if (it.id == p.id) p else it })
        onDone()
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(
            enabled = !searching,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                error = null
                if (LocalNetworkPermission.granted(context)) { found.clear(); searching = true }
                else permissionLauncher.launch(LocalNetworkPermission.NAME)
            },
        ) { Text(stringResource(if (searching) R.string.ui_smb_searching else R.string.ui_smb_search)) }
        found.forEach { f ->
            TextButton(onClick = { host = f.ip }, modifier = Modifier.fillMaxWidth()) {
                Text("${f.name}  ${f.ip}", Modifier.fillMaxWidth())
            }
        }
        if (searched && !searching && found.isEmpty()) {
            Text(stringResource(R.string.ui_smb_found_none), style = MaterialTheme.typography.bodySmall)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        OutlinedTextField(host, { host = it.trim() }, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.ui_home_host)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(share, { share = it.trim() }, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.ui_home_share)) }, singleLine = true)
        OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.ui_home_user)) }, singleLine = true)
        OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.ui_home_password)) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.ui_smb_name)) }, singleLine = true)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = host.isNotBlank() && share.isNotBlank(), onClick = { save() }) {
                Text(stringResource(R.string.ui_save))
            }
            if (existing != null) {
                TextButton(onClick = {
                    store.saveSmbProfiles(store.loadSmbProfiles().filter { it.id != existing.id })
                    onDone()
                }) { Text(stringResource(R.string.ui_delete)) }
            }
        }
    }
}
