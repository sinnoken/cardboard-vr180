package com.example.vr180.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.vr180.data.ProfileStore
import com.example.vr180.model.LensProfile
import com.example.vr180.model.VerticalAlignment

@Composable
fun LensProfilesScreen(store: ProfileStore, onDone: () -> Unit) {
    var lenses by remember { mutableStateOf(store.loadLenses()) }

    fun reload() { lenses = store.loadLenses() }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "這裡只是選一個起點。不論從哪裡開始，戴上眼鏡後都要在播放選單的『鏡片校正』裡微調，" +
                "最後的準確度一樣。"
        )

        Button(modifier = Modifier.fillMaxWidth(), onClick = {
            store.activate(LensProfile.CARDBOARD_DEFAULT_ID)
            onDone()
        }) { Text("使用 Cardboard 預設值") }

        Text("設定檔", style = MaterialTheme.typography.titleSmall)
        lenses.profiles.forEach { p ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = p.id == lenses.activeId, onClick = {
                        store.activate(p.id); reload()
                    })
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (p.isPreset) "${p.name}（預設）" else p.name,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            "鏡片間距 %.1f · 螢幕到鏡片 %.1f · 中心 %s · k1 %.2f k2 %.2f".format(
                                p.interLensMm, p.screenToLensMm,
                                if (p.alignment == VerticalAlignment.CENTER) "置中" else "%.1f mm".format(p.lensHeightMm),
                                p.k1, p.k2,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (!p.isPreset) TextButton(
                        enabled = p.id != lenses.activeId,
                        onClick = {
                            store.saveLenses(lenses.copy(profiles = lenses.profiles.filter { it.id != p.id }))
                            reload()
                        },
                    ) { Text("刪除") }
                }
            }
        }
    }
}
