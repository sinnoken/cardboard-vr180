package com.example.vr180.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.vr180.PlayerActivity
import com.example.vr180.source.FileSource
import com.example.vr180.source.FsEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val VIDEO_EXT = setOf("mp4", "mkv", "mov", "m4v", "ts", "webm")

/** 檔名結尾的解析度標記，例如 trip_8K.mp4、trip_5.7K.mp4 會被歸成同一支影片的兩個版本。 */
private val TAG = Regex("""^(.*?)[_\-. ](8k|6k|5\.7k|4k)$""", RegexOption.IGNORE_CASE)
private val RANK = mapOf("8k" to 4, "6k" to 3, "5.7k" to 2, "4k" to 1)

private sealed class Row {
    data class Dir(val entry: FsEntry) : Row()
    data class Video(val base: String, val variants: List<Pair<String, FsEntry>>) : Row() // (標籤, 檔案) 高→低
}

private fun group(entries: List<FsEntry>): List<Row> {
    val dirs = entries.filter { it.isDir }.map { Row.Dir(it) }
    val videos = entries.filter { !it.isDir && it.name.substringAfterLast('.', "").lowercase() in VIDEO_EXT }
        .map { e ->
            val stem = e.name.substringBeforeLast('.')
            val m = TAG.matchEntire(stem)
            Triple(m?.groupValues?.get(1) ?: stem, m?.groupValues?.get(2)?.uppercase() ?: "原檔", e)
        }
        .groupBy { it.first }
        .map { (base, list) ->
            Row.Video(base, list.sortedByDescending { RANK[it.second.lowercase()] ?: 0 }.map { it.second to it.third })
        }
        .sortedBy { it.base.lowercase() }
    return dirs + videos
}

/** NAS 與本機資料夾共用的瀏覽畫面。 */
@Composable
fun BrowserScreen(source: FileSource) {
    val context = LocalContext.current
    // 目前位置的堆疊：(顯示名稱, 資料夾 id)
    var stack by remember(source) { mutableStateOf(listOf("" to source.rootId)) }
    var rows by remember { mutableStateOf<List<Row>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val dirId = stack.last().second

    LaunchedEffect(source, dirId) {
        loading = true; error = null
        runCatching { withContext(Dispatchers.IO) { group(source.list(dirId)) } }
            .onSuccess { rows = it }
            .onFailure { error = it.message ?: it.javaClass.simpleName }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            (listOf(source.label) + stack.drop(1).map { it.first }).joinToString(" / "),
            Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall,
        )
        when {
            loading -> CircularProgressIndicator(Modifier.padding(24.dp))
            error != null -> Text("讀取失敗：$error", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            else -> LazyColumn(Modifier.fillMaxSize()) {
                if (stack.size > 1) item {
                    ListItem(headlineContent = { Text("‹ 上一層") },
                        modifier = Modifier.fillMaxWidth().clickable { stack = stack.dropLast(1) })
                    HorizontalDivider()
                }
                items(rows) { row ->
                    when (row) {
                        is Row.Dir -> ListItem(
                            headlineContent = { Text("📁 ${row.entry.name}") },
                            modifier = Modifier.clickable { stack = stack + (row.entry.name to row.entry.id) },
                        )
                        is Row.Video -> ListItem(
                            headlineContent = { Text(row.base) },
                            supportingContent = { Text(row.variants.joinToString(" · ") { it.first }) },
                            modifier = Modifier.clickable {
                                context.startActivity(Intent(context, PlayerActivity::class.java).apply {
                                    putStringArrayListExtra(PlayerActivity.EXTRA_URIS,
                                        ArrayList(row.variants.map { source.playUri(it.second) }))
                                    putStringArrayListExtra(PlayerActivity.EXTRA_LABELS,
                                        ArrayList(row.variants.map { it.first }))
                                    putExtra(PlayerActivity.EXTRA_SIDECAR, source.sidecar(dirId, row.base).encode())
                                    putExtra(PlayerActivity.EXTRA_TITLE, row.base)
                                })
                            },
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
