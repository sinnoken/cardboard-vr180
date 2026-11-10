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
import androidx.compose.ui.unit.dp
import com.example.vr180.data.ProfileStore
import com.example.vr180.input.ControllerChoice
import com.example.vr180.input.InputLog

/** 控制器按鍵測試：選按鍵配置，並顯示控制器實際送出的訊號與 App 會把它當成什麼動作。 */
@Composable
fun KeyTestScreen(store: ProfileStore) {
    var choice by remember { mutableStateOf(store.loadControllerChoice()) }
    DisposableEffect(Unit) {
        InputLog.clear()
        InputLog.choice = choice
        InputLog.active = true
        onDispose { InputLog.active = false }
    }
    val device = InputLog.lastDevice
    val profile = choice.resolve(device)

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("按鍵配置", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ControllerChoice.entries.forEach { c ->
                FilterChip(
                    selected = c == choice,
                    onClick = {
                        choice = c
                        InputLog.choice = c
                        store.saveControllerChoice(c)
                    },
                    label = { Text(c.label) },
                )
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "使用：${profile.label}" + when {
                        choice != ControllerChoice.AUTO -> "（手動指定）"
                        device == null -> "（自動：按任一鍵後依控制器名稱判斷）"
                        else -> "（自動：$device）"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                profile.legend().forEach { (key, action) ->
                    Text("$key → $action", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text(
            "把每個按鍵、搖桿四個方向都按一次，最新的一筆在最上面。" +
                "這一頁會攔下所有按鍵，要離開請按左上角的「返回」。",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = { InputLog.clear() }) { Text("清除紀錄") }
        if (InputLog.lines.isEmpty()) {
            Text("還沒有收到任何訊號。確認控制器已在系統設定配對並連線。",
                style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(InputLog.lines) { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
