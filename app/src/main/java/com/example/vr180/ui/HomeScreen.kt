package com.example.vr180.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.vr180.R
import com.example.vr180.Screen
import com.example.vr180.data.ProfileStore
import com.example.vr180.model.LensProfile
import com.example.vr180.model.SmbConnection
import com.example.vr180.model.displayName
import com.example.vr180.smb.LocalNetworkPermission
import com.example.vr180.smb.SmbRepository
import com.example.vr180.source.FileSource
import com.example.vr180.source.LocalFileSource
import com.example.vr180.source.SmbFileSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(store: ProfileStore, navigate: (Screen) -> Unit, openSource: (FileSource) -> Unit) {
    val saved = remember { store.loadConnection() }
    var host by remember { mutableStateOf(saved.host) }
    var share by remember { mutableStateOf(saved.share) }
    var user by remember { mutableStateOf(saved.user) }
    var pass by remember { mutableStateOf(saved.password) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun connect() {
        busy = true; error = null
        val c = SmbConnection(host, share, user, pass)
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    store.saveConnection(c)          // 先存：連線失敗時不用重打帳號密碼
                    SmbRepository.configure(c)
                    SmbRepository.list(c.host, c.share, "")
                }
            }
            busy = false
            r.onSuccess { openSource(SmbFileSource(c)) }
                .onFailure { error = context.getString(R.string.ui_home_connect_failed, friendlyError(context, it)) }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) connect()
        else error = context.getString(R.string.ui_home_permission_needed)
    }

    var folders by remember { mutableStateOf(store.loadFolders()) }
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

    val device = remember { store.loadDevice() }
    var lens by remember { mutableStateOf(store.loadLenses().active) }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.ui_home_nas_title), style = MaterialTheme.typography.titleMedium)
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
        Button(
            enabled = !busy && host.isNotBlank() && share.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                if (LocalNetworkPermission.granted(context)) connect()
                else permissionLauncher.launch(LocalNetworkPermission.NAME)
            },
        ) { Text(stringResource(if (busy) R.string.ui_home_connecting else R.string.ui_home_connect)) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.ui_home_local_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.ui_home_local_hint),
            style = MaterialTheme.typography.bodySmall,
        )
        folders.forEach { f ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("📁 ${f.name}", Modifier.weight(1f))
                    TextButton(onClick = {
                        openSource(LocalFileSource(context, Uri.parse(f.uri), f.name))
                    }) { Text(stringResource(R.string.ui_open)) }
                    TextButton(onClick = {
                        runCatching {
                            context.contentResolver.releasePersistableUriPermission(Uri.parse(f.uri),
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        }
                        store.saveFolders(folders.filter { it.uri != f.uri })
                        folders = store.loadFolders()
                    }) { Text(stringResource(R.string.ui_remove)) }
                }
            }
        }
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { pickFolder.launch(null) }) {
            Text(stringResource(R.string.ui_home_add_folder))
        }

        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.ui_home_setup_title), style = MaterialTheme.typography.titleMedium)
        SetupCard(stringResource(R.string.ui_home_calib_title),
            if (device.pxPerMm > 0f) stringResource(R.string.ui_home_calib_done, device.pxPerMm)
            else stringResource(R.string.ui_home_calib_none),
        ) { navigate(Screen.SCREEN_CALIB) }
        // 漂移的預設值是 0；實際量測幾乎不可能剛好是 0.0，所以 0 就代表還沒檢測過
        val drift = if (device.yawDriftDegPerSec == 0f) stringResource(R.string.ui_home_drift_none)
            else stringResource(R.string.ui_home_drift, device.yawDriftDegPerSec * 60f)
        SetupCard(stringResource(R.string.ui_home_gyro_title),
            stringResource(R.string.ui_home_gyro_detail, drift, device.predictionMs),
        ) { navigate(Screen.GYRO) }
        val usingDefault = lens.id == LensProfile.CARDBOARD_DEFAULT_ID
        SetupCard(
            stringResource(R.string.ui_home_lens_title),
            if (usingDefault) stringResource(R.string.ui_home_lens_default)
            else stringResource(R.string.ui_home_lens_in_use, lens.displayName(context)),
            buttonText = stringResource(R.string.ui_home_lens_choose),
            secondaryText = if (usingDefault) null else stringResource(R.string.ui_home_lens_use_default),
            onSecondary = {
                store.activate(LensProfile.CARDBOARD_DEFAULT_ID)
                lens = store.loadLenses().active
            },
        ) { navigate(Screen.LENSES) }
        SetupCard(
            stringResource(R.string.ui_home_keytest_title),
            stringResource(R.string.ui_home_keytest_detail),
        ) { navigate(Screen.KEY_TEST) }
    }
}

@Composable
private fun SetupCard(
    title: String,
    detail: String,
    buttonText: String? = null,              // null = 「開啟」
    secondaryText: String? = null,
    onSecondary: () -> Unit = {},
    onClick: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onClick) { Text(buttonText ?: stringResource(R.string.ui_open)) }
                secondaryText?.let { TextButton(onClick = onSecondary) { Text(it) } }
            }
        }
    }
}
