package com.example.vr180.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.vr180.data.ProfileStore

/** 信用卡／悠遊卡等 ID-1 卡片的國際標準尺寸（ISO/IEC 7810）。 */
private const val CARD_W_MM = 53.98f   // 直放：短邊在左右
private const val CARD_H_MM = 85.60f

/**
 * 螢幕實際尺寸校正。手機回報的 DPI 常常不準，而眼距、鏡片位置都以毫米換算成像素，
 * 所以用一張實體卡片貼在螢幕上比對，直接量出「每毫米幾個像素」。
 */
@Composable
fun ScreenCalibScreen(store: ProfileStore) {
    val dm = LocalContext.current.resources.displayMetrics
    val systemPxPerMm = (dm.xdpi + dm.ydpi) / 2f / 25.4f
    val device = remember { store.loadDevice() }
    var scale by remember {
        mutableFloatStateOf(if (device.pxPerMm > 0f) device.pxPerMm / systemPxPerMm else 1f)
    }
    var saved by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    val pxPerMm = systemPxPerMm * scale

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("把一張信用卡（或悠遊卡）直放貼在框上，調整到框線剛好貼齊卡片四邊。")
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = CARD_W_MM * pxPerMm
                val h = CARD_H_MM * pxPerMm
                val tl = Offset((size.width - w) / 2f, (size.height - h) / 2f)
                drawRoundRect(accent, tl, Size(w, h), CornerRadius(3.18f * pxPerMm), style = Stroke(2f))
                // 十字中線方便對準
                drawLine(accent.copy(alpha = 0.4f), Offset(size.width / 2, tl.y), Offset(size.width / 2, tl.y + h))
                drawLine(accent.copy(alpha = 0.4f), Offset(tl.x, size.height / 2), Offset(tl.x + w, size.height / 2))
            }
        }
        Text("%.3f 像素/mm（系統回報 %.3f）".format(pxPerMm, systemPxPerMm),
            style = MaterialTheme.typography.bodySmall)
        Slider(value = scale, onValueChange = { scale = it; saved = false }, valueRange = 0.85f..1.15f)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { scale -= 0.001f; saved = false }) { Text("縮小一點") }
            OutlinedButton(onClick = { scale += 0.001f; saved = false }) { Text("放大一點") }
            Button(onClick = {
                store.saveDevice(store.loadDevice().copy(pxPerMm = pxPerMm))
                saved = true
            }) { Text(if (saved) "已儲存" else "儲存") }
        }
    }
}
