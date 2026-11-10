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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.vr180.R
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
            meter.moved -> context.getString(R.string.ui_gyro_moved)
            drift == null -> context.getString(R.string.ui_gyro_no_samples)
            else -> {
                device = device.copy(yawDriftDegPerSec = drift.toFloat())
                store.saveDevice(device)
                val perMin = drift * 60
                context.getString(R.string.ui_gyro_result, perMin) +
                    if (abs(perMin) > 3) "\n" + context.getString(R.string.ui_gyro_drift_high) else ""
            }
        }
        running = false
    }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.ui_gyro_still_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.ui_gyro_still_desc, TEST_SECONDS))
        if (running) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        Button(enabled = !running, onClick = { result = null; progress = 0f; running = true }) {
            Text(stringResource(if (running) R.string.ui_gyro_running else R.string.ui_gyro_start))
        }
        result?.let { Text(it) }

        Text(stringResource(R.string.ui_gyro_prediction_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.ui_gyro_prediction_desc))
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
