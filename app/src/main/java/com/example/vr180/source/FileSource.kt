package com.example.vr180.source

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.example.vr180.model.SmbConnection
import com.example.vr180.smb.SmbRepository
import java.io.IOException

/** 瀏覽清單中的一個項目。id 對 SMB 是相對路徑，對本機資料夾是 SAF 的 documentId。 */
/** size 為 -1、modifiedMs 為 0 代表來源沒有提供。 */
data class FsEntry(
    val name: String,
    val isDir: Boolean,
    val id: String,
    val size: Long = -1,
    val modifiedMs: Long = 0,
)

/**
 * 影片來源：NAS（SMB）或本機資料夾（Storage Access Framework）。
 * 瀏覽畫面、播放器、場景校正檔都只透過這個介面存取，不需要知道底層是哪一種。
 * list() 會做 I/O，請在背景執行緒呼叫。
 */
interface FileSource {
    val label: String
    val rootId: String
    fun list(dirId: String): List<FsEntry>
    /** 交給播放器的位址（smb:// 或 content://）。 */
    fun playUri(entry: FsEntry): String
    /** 同一資料夾內，這支影片的場景校正檔位置。 */
    fun sidecar(dirId: String, baseName: String): SidecarRef
}

class SmbFileSource(private val conn: SmbConnection) : FileSource {
    override val label = "\\\\${conn.host}\\${conn.share}"
    override val rootId = ""

    override fun list(dirId: String): List<FsEntry> {
        SmbRepository.configure(conn)
        return SmbRepository.list(conn.host, conn.share, dirId).map { FsEntry(it.name, it.isDir, join(dirId, it.name), it.size, it.modifiedMs) }
    }

    override fun playUri(entry: FsEntry) =
        SmbRepository.buildUri(conn.host, conn.share, entry.id).toString()

    override fun sidecar(dirId: String, baseName: String) =
        SidecarRef.Smb(SmbRepository.buildUri(conn.host, conn.share, join(dirId, "$baseName.vr180.json")).toString())

    private fun join(dir: String, name: String) = if (dir.isEmpty()) name else "$dir/$name"
}

/**
 * 使用者透過系統選擇器授權的本機資料夾（手機儲存空間、SD 卡、USB 隨身碟都可以）。
 * App 只能存取這個資料夾，不需要任何儲存權限。
 */
class LocalFileSource(
    context: Context,
    private val tree: Uri,
    override val label: String,
) : FileSource {
    private val resolver = context.applicationContext.contentResolver
    override val rootId: String = DocumentsContract.getTreeDocumentId(tree)

    override fun list(dirId: String): List<FsEntry> {
        val out = ArrayList<FsEntry>()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId)
        val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
        resolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                if (name.startsWith(".")) continue
                out += FsEntry(
                    name, c.getString(2) == Document.MIME_TYPE_DIR, id,
                    size = if (c.isNull(3)) -1 else c.getLong(3),
                    modifiedMs = if (c.isNull(4)) 0 else c.getLong(4),
                )
            }
        } ?: throw IOException("無法讀取資料夾，可能已失去存取權限，請移除後重新加入")
        return out.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    override fun playUri(entry: FsEntry) =
        DocumentsContract.buildDocumentUriUsingTree(tree, entry.id).toString()

    override fun sidecar(dirId: String, baseName: String) =
        SidecarRef.Local(tree.toString(), dirId, "$baseName.vr180.json")

    companion object {
        /** 讀取資料夾的顯示名稱。 */
        fun displayName(context: Context, tree: Uri): String {
            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            return runCatching {
                context.contentResolver.query(doc, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull() ?: tree.lastPathSegment ?: "本機資料夾"
        }
    }
}
