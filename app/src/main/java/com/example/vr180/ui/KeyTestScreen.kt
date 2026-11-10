package com.example.vr180.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.vr180.R
import com.example.vr180.data.ProfileStore
import com.example.vr180.input.ControllerChoice
import com.example.vr180.input.InputLog

/** 控制器按鍵測試：選按鍵配置，並顯示控制器實際送出的訊號與 App 會把它當成什麼動作。 */
@Composable
fun KeyTestScreen(store: ProfileStore) {
    var choice by remember { mutableStateOf(store.loadControllerChoice()) }
    val context = LocalContext.current
    DisposableEffect(Unit) {
        InputLog.resources = context.resources
        InputLog.clear()
        InputLog.choice = choice
        InputLog.active = true
        onDispose { InputLog.active = false; InputLog.resources = null }
    }
    val device = InputLog.lastDevice
    val profile = choice.resolve(device)

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.ui_keytest_layout), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ControllerChoice.entries.forEach { c ->
                FilterChip(
                    selected = c == choice,
                    onClick = {
                        choice = c
                        InputLog.choice = c
                        store.saveControllerChoice(c)
                    },
                    label = { Text(stringResource(c.labelRes)) },
                )
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val profileName = stringResource(profile.labelRes)
                Text(
                    when {
                        choice != ControllerChoice.AUTO -> stringResource(R.string.ui_keytest_using_manual, profileName)
                        device == null -> stringResource(R.string.ui_keytest_using_auto_pending, profileName)
                        else -> stringResource(R.string.ui_keytest_using_auto, profileName, device)
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                profile.legend(context).forEach { (key, action) ->
                    Text("$key → $action", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text(
            stringResource(R.string.ui_keytest_hint),
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = { InputLog.clear() }) { Text(stringResource(R.string.ui_keytest_clear)) }
        if (InputLog.lines.isEmpty()) {
            Text(stringResource(R.string.ui_keytest_empty),
                style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(InputLog.lines) { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
