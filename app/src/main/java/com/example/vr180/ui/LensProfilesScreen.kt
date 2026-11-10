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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.vr180.R
import com.example.vr180.data.ProfileStore
import com.example.vr180.model.LensProfile
import com.example.vr180.model.VerticalAlignment
import com.example.vr180.model.displayName

@Composable
fun LensProfilesScreen(store: ProfileStore, onDone: () -> Unit) {
    var lenses by remember { mutableStateOf(store.loadLenses()) }
    val context = LocalContext.current

    fun reload() { lenses = store.loadLenses() }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.ui_lens_intro))

        Button(modifier = Modifier.fillMaxWidth(), onClick = {
            store.activate(LensProfile.CARDBOARD_DEFAULT_ID)
            onDone()
        }) { Text(stringResource(R.string.ui_lens_use_default)) }

        Text(stringResource(R.string.ui_lens_profiles), style = MaterialTheme.typography.titleSmall)
        lenses.profiles.forEach { p ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = p.id == lenses.activeId, onClick = {
                        store.activate(p.id); reload()
                    })
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (p.isPreset) stringResource(R.string.ui_lens_preset_name, p.displayName(context))
                            else p.displayName(context),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            stringResource(
                                R.string.ui_lens_detail,
                                p.interLensMm, p.screenToLensMm,
                                if (p.alignment == VerticalAlignment.CENTER) stringResource(R.string.ui_lens_center_centered)
                                else "%.1f mm".format(p.lensHeightMm),
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
                    ) { Text(stringResource(R.string.ui_delete)) }
                }
            }
        }
    }
}
