package com.example.vr180

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.vr180.data.ProfileStore
import com.example.vr180.source.FileSource
import com.example.vr180.ui.BrowserScreen
import com.example.vr180.ui.GyroTestScreen
import com.example.vr180.ui.HomeScreen
import com.example.vr180.ui.KeyTestScreen
import com.example.vr180.input.InputLog
import android.view.KeyEvent
import android.view.MotionEvent
import com.example.vr180.ui.LensProfilesScreen
import com.example.vr180.ui.ScreenCalibScreen
import com.example.vr180.ui.SettingsScreen
import com.example.vr180.ui.SmbEditScreen

enum class Screen(@StringRes val titleRes: Int) {
    HOME(R.string.app_name),
    BROWSER(R.string.ui_screen_browser),
    SMB_EDIT(R.string.ui_home_nas_title),
    SETTINGS(R.string.ui_screen_settings),
    SCREEN_CALIB(R.string.ui_screen_calib),
    GYRO(R.string.ui_screen_gyro),
    LENSES(R.string.ui_screen_lenses),
    KEY_TEST(R.string.ui_screen_key_test);

    /** 返回鍵回到哪一頁：設定裡的子頁回設定，其他回首頁。 */
    val parent: Screen get() = when (this) {
        SCREEN_CALIB, GYRO, LENSES, KEY_TEST -> SETTINGS
        else -> HOME
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = ProfileStore(this)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                App(store)
            }
        }
    }

    // 按鍵測試頁開啟時：記錄所有控制器訊號並攔下來（系統返回鍵除外，否則手勢返回會失效）
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!InputLog.active) return super.dispatchKeyEvent(event)
        InputLog.onKey(event)
        return if (event.keyCode == KeyEvent.KEYCODE_BACK) super.dispatchKeyEvent(event) else true
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (!InputLog.active) return super.dispatchGenericMotionEvent(ev)
        InputLog.onMotion(ev)
        return true
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(store: ProfileStore) {
    var screen by remember { mutableStateOf(Screen.HOME) }
    var source by remember { mutableStateOf<FileSource?>(null) }
    var editingNas by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = screen != Screen.HOME) { screen = screen.parent }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (screen == Screen.BROWSER) source?.label.orEmpty() else stringResource(screen.titleRes)) },
                navigationIcon = {
                    if (screen != Screen.HOME) TextButton(onClick = { screen = screen.parent }) { Text(stringResource(R.string.ui_nav_back)) }
                },
                actions = {
                    if (screen == Screen.HOME) TextButton(onClick = { screen = Screen.SETTINGS }) {
                        Text(stringResource(R.string.ui_home_settings))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (screen) {
                Screen.HOME -> HomeScreen(
                    store,
                    editNas = { editingNas = it; screen = Screen.SMB_EDIT },
                    openSource = { source = it; screen = Screen.BROWSER },
                )
                Screen.BROWSER -> source?.let { BrowserScreen(it, store) }
                Screen.SMB_EDIT -> SmbEditScreen(store, editingNas) { screen = Screen.HOME }
                Screen.SETTINGS -> SettingsScreen(store, navigate = { screen = it })
                Screen.SCREEN_CALIB -> ScreenCalibScreen(store)
                Screen.GYRO -> GyroTestScreen(store)
                Screen.LENSES -> LensProfilesScreen(store) { screen = Screen.SETTINGS }
                Screen.KEY_TEST -> KeyTestScreen(store)
            }
        }
    }
}
