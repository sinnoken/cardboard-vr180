package com.example.vr180.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.vr180.input.InputLog

/** 控制器按鍵測試：顯示控制器實際送出的訊號，以及 App 會把它當成什麼動作。 */
@Composable
fun KeyTestScreen() {
    DisposableEffect(Unit) {
        InputLog.clear()
        InputLog.active = true
        onDispose { InputLog.active = false }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "把控制器的每個按鍵、搖桿四個方向都按一次。遊戲模式和遙控模式各試一輪。" +
                "最新的一筆在最上面。這一頁會攔下所有按鍵，要離開請按左上角的「返回」。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { InputLog.clear() }) { Text("清除") }
        }
        if (InputLog.lines.isEmpty()) {
            Text("還沒有收到任何訊號。確認控制器已在系統設定配對並連線。",
                style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(InputLog.lines) { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
