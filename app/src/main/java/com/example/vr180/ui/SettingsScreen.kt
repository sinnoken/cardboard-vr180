package com.example.vr180.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.vr180.R
import com.example.vr180.Screen
import com.example.vr180.data.ProfileStore
import com.example.vr180.model.LensProfile
import com.example.vr180.model.displayName

/** 設定：都是選用的，不做也能直接看片。 */
@Composable
fun SettingsScreen(store: ProfileStore, navigate: (Screen) -> Unit) {
    val context = LocalContext.current
    val device = remember { store.loadDevice() }
    var lens by remember { mutableStateOf(store.loadLenses().active) }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.ui_home_setup_title), style = MaterialTheme.typography.bodyMedium)
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
