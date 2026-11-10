package com.example.vr180.ui

import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.vr180.PlayerActivity
import com.example.vr180.data.ProfileStore
import com.example.vr180.data.ProfileStore.BrowseOptions
import com.example.vr180.data.ProfileStore.BrowseSort
import com.example.vr180.source.FileSource
import com.example.vr180.source.FsEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

private val VIDEO_EXT = setOf("mp4", "mkv", "mov", "m4v", "ts", "webm")

/** 檔名結尾的解析度標記，例如 trip_8K.mp4、trip_5.7K.mp4 會被歸成同一支影片的兩個版本。 */
private val TAG = Regex("""^(.*?)[_\-. ](8k|6k|5\.7k|4k)$""", RegexOption.IGNORE_CASE)
private val RANK = mapOf("8k" to 4, "6k" to 3, "5.7k" to 2, "4k" to 1)

/** 「包含子資料夾」往下最多幾層：NAS 每層都要一次網路請求，太深會很慢。 */
private const val MAX_DEPTH = 3

/** 「隱藏小檔」的門檻：VR180 正片幾乎都大於這個大小，小檔多半是預覽或片段。 */
private const val SMALL_BYTES = 100L * 1024 * 1024

/** 掃到的一個影片檔，以及它所在的資料夾。dirPath 是相對於目前位置的路徑，空字串代表就在這一層。 */
private class Found(val entry: FsEntry, val dirId: String, val dirPath: String)

private sealed class ListRow {
    data class Dir(val entry: FsEntry) : ListRow()

    /** variants：(標籤, 檔案)，解析度高→低。 */
    data class Video(
        val base: String,
        val dirId: String,
        val dirPath: String,
        val variants: List<Pair<String, FsEntry>>,
    ) : ListRow() {
        val modifiedMs = variants.maxOf { it.second.modifiedMs }
        val size = variants.maxOf { it.second.size }
    }
}

private class Listing(val rows: List<ListRow>, val skippedDirs: Int)

private fun isVideo(e: FsEntry) = !e.isDir && e.name.substringAfterLast('.', "").lowercase() in VIDEO_EXT

/** 列出目前資料夾；包含子資料夾時往下掃 MAX_DEPTH 層，讀不到的子資料夾略過並計數。 */
private fun CoroutineScope.scan(
    source: FileSource, dirId: String, opt: BrowseOptions, onProgress: (Int) -> Unit,
): Listing {
    val found = ArrayList<Found>()
    val dirs = ArrayList<FsEntry>()
    var skipped = 0
    var scanned = 0
    val queue = ArrayDeque<Triple<String, String, Int>>()   // (資料夾 id, 相對路徑, 深度)
    queue.add(Triple(dirId, "", 0))
    while (queue.isNotEmpty()) {
        ensureActive()
        val (id, path, depth) = queue.removeFirst()
        // 目前這一層讀不到要報錯；子資料夾讀不到（權限、斷線）就略過，不讓整份清單失敗
        val entries = if (depth == 0) source.list(id) else runCatching { source.list(id) }.getOrNull()
        if (entries == null) { skipped++; continue }
        onProgress(++scanned)
        for (e in entries) {
            when {
                e.isDir && !opt.includeSubfolders -> dirs += e
                e.isDir -> if (depth < MAX_DEPTH) {
                    queue.add(Triple(e.id, if (path.isEmpty()) e.name else "$path/${e.name}", depth + 1))
                }
                isVideo(e) && !(opt.hideSmall && e.size in 0 until SMALL_BYTES) -> found += Found(e, id, path)
            }
        }
    }
    val dirRows = dirs.sortedBy { it.name.lowercase() }.map { ListRow.Dir(it) }
    return Listing(dirRows + group(found, opt.sort), skipped)
}

private fun group(found: List<Found>, sort: BrowseSort): List<ListRow.Video> {
    val videos = found
        .map { f ->
            val stem = f.entry.name.substringBeforeLast('.')
            val m = TAG.matchEntire(stem)
            Triple(m?.groupValues?.get(1) ?: stem, m?.groupValues?.get(2)?.uppercase() ?: "原檔", f)
        }
        // 不同資料夾的同名影片是不同影片
        .groupBy { (base, _, f) -> f.dirId to base }
        .map { (key, list) ->
            val f = list.first().third
            ListRow.Video(
                base = key.second, dirId = f.dirId, dirPath = f.dirPath,
                variants = list.sortedByDescending { RANK[it.second.lowercase()] ?: 0 }.map { it.second to it.third.entry },
            )
        }
    return when (sort) {
        BrowseSort.NAME -> videos.sortedWith(compareBy({ it.base.lowercase() }, { it.dirPath.lowercase() }))
        BrowseSort.DATE -> videos.sortedByDescending { it.modifiedMs }
        BrowseSort.SIZE -> videos.sortedByDescending { it.size }
    }
}

private fun formatDate(ms: Long): String? =
    if (ms <= 0) null else Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toString()

private fun formatSize(bytes: Long): String? = when {
    bytes < 0 -> null
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    else -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
}

/** NAS 與本機資料夾共用的瀏覽畫面。 */
@Composable
fun BrowserScreen(source: FileSource, store: ProfileStore) {
    val context = LocalContext.current
    // 目前位置的堆疊：(顯示名稱, 資料夾 id)
    var stack by remember(source) { mutableStateOf(listOf("" to source.rootId)) }
    var opt by remember { mutableStateOf(store.loadBrowseOptions()) }
    var listing by remember { mutableStateOf(Listing(emptyList(), 0)) }
    var loading by remember { mutableStateOf(true) }
    var scanned by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var lastOpen by remember { mutableLongStateOf(0L) }
    val dirId = stack.last().second

    fun update(o: BrowseOptions) { opt = o; store.saveBrowseOptions(o) }

    // 系統返回鍵／手勢：先退回上一層資料夾，到最上層才回首頁
    BackHandler(enabled = stack.size > 1) { stack = stack.dropLast(1) }

    LaunchedEffect(source, dirId, reload, opt) {
        loading = true; error = null; scanned = 0
        runCatching { withContext(Dispatchers.IO) { scan(source, dirId, opt) { scanned = it } } }
            .onSuccess { listing = it }
            .onFailure { error = friendlyError(it) }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            (listOf(source.label) + stack.drop(1).map { it.first }).joinToString(" / "),
            Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), style = MaterialTheme.typography.bodySmall,
        )
        Row(
            Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("排序", style = MaterialTheme.typography.bodyMedium)
            listOf(BrowseSort.NAME to "名稱", BrowseSort.DATE to "日期", BrowseSort.SIZE to "大小").forEach { (s, label) ->
                FilterChip(selected = opt.sort == s, onClick = { update(opt.copy(sort = s)) }, label = { Text(label) })
            }
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = opt.includeSubfolders,
                onClick = { update(opt.copy(includeSubfolders = !opt.includeSubfolders)) },
                label = { Text("包含子資料夾") },
            )
            FilterChip(
                selected = opt.hideSmall,
                onClick = { update(opt.copy(hideSmall = !opt.hideSmall)) },
                label = { Text("隱藏小於 100 MB") },
            )
        }
        when {
            loading -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                if (opt.includeSubfolders && scanned > 0) Text("已讀取 $scanned 個資料夾…")
            }
            error != null -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("讀取失敗：$error", color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { reload++ }) { Text("重試") }
                    if (stack.size > 1) OutlinedButton(onClick = { stack = stack.dropLast(1) }) { Text("上一層") }
                }
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                if (stack.size > 1) item {
                    ListItem(headlineContent = { Text("‹ 上一層") },
                        modifier = Modifier.fillMaxWidth().clickable { stack = stack.dropLast(1) })
                    HorizontalDivider()
                }
                if (listing.skippedDirs > 0) item {
                    Text("有 ${listing.skippedDirs} 個子資料夾讀不到，已略過。", Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (listing.rows.none { it is ListRow.Video }) item {
                    Text(
                        "這裡沒有影片" + if (opt.hideSmall) "（已隱藏小於 100 MB 的影片）。" else "。",
                        Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
                    )
                }
                items(listing.rows) { row ->
                    when (row) {
                        is ListRow.Dir -> ListItem(
                            headlineContent = { Text("📁 ${row.entry.name}") },
                            modifier = Modifier.clickable { stack = stack + (row.entry.name to row.entry.id) },
                        )
                        is ListRow.Video -> ListItem(
                            headlineContent = { Text(row.base) },
                            supportingContent = {
                                val info = listOfNotNull(
                                    row.variants.joinToString(" · ") { it.first },
                                    formatDate(row.modifiedMs),
                                    formatSize(row.size),
                                ).joinToString("　")
                                Text(if (row.dirPath.isEmpty()) info else "$info\n📁 ${row.dirPath}")
                            },
                            modifier = Modifier.clickable {
                                // 連點兩下只開一個播放器
                                val now = SystemClock.uptimeMillis()
                                if (now - lastOpen < 1500) return@clickable
                                lastOpen = now
                                context.startActivity(Intent(context, PlayerActivity::class.java).apply {
                                    putStringArrayListExtra(PlayerActivity.EXTRA_URIS,
                                        ArrayList(row.variants.map { source.playUri(it.second) }))
                                    putStringArrayListExtra(PlayerActivity.EXTRA_LABELS,
                                        ArrayList(row.variants.map { it.first }))
                                    putExtra(PlayerActivity.EXTRA_SIDECAR, source.sidecar(row.dirId, row.base).encode())
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
