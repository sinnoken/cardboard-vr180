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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.vr180.Screen
import com.example.vr180.data.ProfileStore
import com.example.vr180.model.LensProfile
import com.example.vr180.model.SmbConnection
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
                .onFailure { error = "連線失敗：" + friendlyError(it) }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) connect()
        else error = "需要「附近裝置／區域網路」權限才能連到 NAS。可到系統設定 › 應用程式 › VR180 Player › 權限 開啟。"
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
        Text("NAS 連線（SMB）", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(host, { host = it.trim() }, Modifier.fillMaxWidth(),
            label = { Text("主機（IP 或名稱）") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(share, { share = it.trim() }, Modifier.fillMaxWidth(),
            label = { Text("共用資料夾") }, singleLine = true)
        OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(),
            label = { Text("帳號（空白 = 訪客）") }, singleLine = true)
        OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(),
            label = { Text("密碼") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        Button(
            enabled = !busy && host.isNotBlank() && share.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                if (LocalNetworkPermission.granted(context)) connect()
                else permissionLauncher.launch(LocalNetworkPermission.NAME)
            },
        ) { Text(if (busy) "連線中…" else "連線並瀏覽") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Spacer(Modifier.height(8.dp))
        Text("本機資料夾", style = MaterialTheme.typography.titleMedium)
        Text(
            "手機儲存空間、SD 卡、USB 隨身碟都可以。只授權選定的資料夾，不需要儲存權限，也不用網路。",
            style = MaterialTheme.typography.bodySmall,
        )
        folders.forEach { f ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("📁 ${f.name}", Modifier.weight(1f))
                    TextButton(onClick = {
                        openSource(LocalFileSource(context, Uri.parse(f.uri), f.name))
                    }) { Text("開啟") }
                    TextButton(onClick = {
                        runCatching {
                            context.contentResolver.releasePersistableUriPermission(Uri.parse(f.uri),
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        }
                        store.saveFolders(folders.filter { it.uri != f.uri })
                        folders = store.loadFolders()
                    }) { Text("移除") }
                }
            }
        }
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { pickFolder.launch(null) }) {
            Text("新增本機資料夾")
        }

        Spacer(Modifier.height(8.dp))
        Text("設定（都可以跳過，直接看片）", style = MaterialTheme.typography.titleMedium)
        SetupCard("1. 螢幕尺寸校正（選用）",
            if (device.pxPerMm > 0f) "已校正（%.2f 像素/mm）".format(device.pxPerMm)
            else "未校正：使用手機回報的螢幕密度，通常已經夠準。戴上後眼距或位置明顯不對再來做。",
        ) { navigate(Screen.SCREEN_CALIB) }
        // 漂移的預設值是 0；實際量測幾乎不可能剛好是 0.0，所以 0 就代表還沒檢測過
        val drift = if (device.yawDriftDegPerSec == 0f) "漂移尚未檢測"
            else "漂移 %.2f°/分".format(device.yawDriftDegPerSec * 60f)
        SetupCard("2. 陀螺儀檢測（選用）",
            "$drift · 預測 %.0f ms。看片時正前方會慢慢跑掉再做；平常長按畫面重新置中就好。"
                .format(device.predictionMs),
        ) { navigate(Screen.GYRO) }
        val usingDefault = lens.id == LensProfile.CARDBOARD_DEFAULT_ID
        SetupCard(
            "3. 眼鏡設定檔（可跳過）",
            if (usingDefault)
                "使用中：Cardboard 預設值，可直接跳過這步。" +
                    "戴上眼鏡後在播放選單的『鏡片校正』裡微調即可。"
            else "使用中：${lens.name}",
            buttonText = "選其他設定檔",
            secondaryText = if (usingDefault) null else "改用 Cardboard 預設值",
            onSecondary = {
                store.activate(LensProfile.CARDBOARD_DEFAULT_ID)
                lens = store.loadLenses().active
            },
        ) { navigate(Screen.LENSES) }
        SetupCard(
            "4. 控制器按鍵測試（選用）",
            "藍芽控制器按了沒反應時，用這頁看它送出什麼訊號。",
        ) { navigate(Screen.KEY_TEST) }
    }
}

@Composable
private fun SetupCard(
    title: String,
    detail: String,
    buttonText: String = "開啟",
    secondaryText: String? = null,
    onSecondary: () -> Unit = {},
    onClick: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onClick) { Text(buttonText) }
                secondaryText?.let { TextButton(onClick = onSecondary) { Text(it) } }
            }
        }
    }
}
