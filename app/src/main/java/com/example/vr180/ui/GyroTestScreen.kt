package com.example.vr180.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.vr180.data.ProfileStore
import com.example.vr180.tracking.DriftMeter
import kotlinx.coroutines.delay
import kotlin.math.abs

private const val TEST_SECONDS = 15

@Composable
fun GyroTestScreen(store: ProfileStore) {
    val context = LocalContext.current
    val meter = remember { DriftMeter(context) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var result by remember { mutableStateOf<String?>(null) }
    var device by remember { mutableStateOf(store.loadDevice()) }
    var prediction by remember { mutableFloatStateOf(device.predictionMs) }

    DisposableEffect(Unit) { onDispose { meter.stop() } }

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        delay(1500)                                    // 給使用者把手離開手機的時間
        meter.start()
        val steps = TEST_SECONDS * 10
        for (i in 1..steps) { delay(100); progress = i / steps.toFloat() }
        meter.stop()
        val drift = meter.driftDegPerSec()
        result = when {
            meter.moved -> "檢測期間手機被移動了，請平放在桌上不要碰觸後重試。"
            drift == null -> "樣本不足，這支手機可能沒有提供 Game Rotation Vector 感測器。"
            else -> {
                device = device.copy(yawDriftDegPerSec = drift.toFloat())
                store.saveDevice(device)
                val perMin = drift * 60
                "水平漂移 %.2f°/分鐘，已儲存並會自動補償。".format(perMin) +
                    if (abs(perMin) > 3) "\n漂移偏大：建議遠離喇叭、磁鐵後重測，或使用時多按『重新置中』。" else ""
            }
        }
        running = false
    }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("靜置檢測", style = MaterialTheme.typography.titleMedium)
        Text(
            "系統的感測器融合會自動校正陀螺儀零點，上下與左右傾斜由重力鎖定不會漂；" +
                "只有水平方向會慢慢偏。把手機平放在桌上、按開始後不要碰觸，" +
                "App 會量測 $TEST_SECONDS 秒內的漂移速率，之後播放時自動抵銷。"
        )
        if (running) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        Button(enabled = !running, onClick = { result = null; progress = 0f; running = true }) {
            Text(if (running) "檢測中…請勿碰觸" else "開始檢測")
        }
        result?.let { Text(it) }

        Text("延遲預測", style = MaterialTheme.typography.titleMedium)
        Text("依轉頭速度把畫面往前推算，抵銷感測到顯示之間的延遲。轉頭時若畫面「拖」在後面就調高，" +
            "若轉頭停下時畫面會「衝過頭」再彈回就調低。")
        Text("%.0f ms".format(prediction))
        Slider(
            value = prediction, onValueChange = { prediction = it }, valueRange = 0f..60f,
            onValueChangeFinished = {
                device = store.loadDevice().copy(predictionMs = prediction)
                store.saveDevice(device)
            },
        )
    }
}
