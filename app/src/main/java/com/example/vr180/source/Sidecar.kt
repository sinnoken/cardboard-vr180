package com.example.vr180.source

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.example.vr180.smb.SmbRepository
import org.json.JSONObject
import java.io.IOException

/** 場景校正檔（影片旁的 xxx.vr180.json）的位置。 */
sealed class SidecarRef {
    data class Smb(val uri: String) : SidecarRef()
    data class Local(val tree: String, val parentId: String, val name: String) : SidecarRef()

    /** 手機本機備份用的鍵值。 */
    val key: String
        get() = when (this) {
            is Smb -> uri
            is Local -> "local:$tree|$parentId|$name"
        }

    fun encode(): String = when (this) {
        is Smb -> JSONObject().put("kind", "smb").put("uri", uri)
        is Local -> JSONObject().put("kind", "local").put("tree", tree)
            .put("parentId", parentId).put("name", name)
    }.toString()

    companion object {
        fun decode(s: String?): SidecarRef? = s?.let {
            runCatching {
                val o = JSONObject(it)
                when (o.getString("kind")) {
                    "smb" -> Smb(o.getString("uri"))
                    "local" -> Local(o.getString("tree"), o.getString("parentId"), o.getString("name"))
                    else -> null
                }
            }.getOrNull()
        }
    }
}

/** 讀寫場景校正檔。會做 I/O，請在背景執行緒呼叫。 */
class SidecarIo(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    fun read(ref: SidecarRef): String? = when (ref) {
        is SidecarRef.Smb -> SmbRepository.readText(Uri.parse(ref.uri))
        is SidecarRef.Local -> findChild(ref)?.let { uri ->
            resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }
    }

    /** 唯讀位置（NAS 唯讀共用、無寫入權限的資料夾）會丟出例外。 */
    fun write(ref: SidecarRef, text: String) {
        when (ref) {
            is SidecarRef.Smb -> SmbRepository.writeText(Uri.parse(ref.uri), text)
            is SidecarRef.Local -> {
                val tree = Uri.parse(ref.tree)
                val target = findChild(ref) ?: DocumentsContract.createDocument(
                    resolver,
                    DocumentsContract.buildDocumentUriUsingTree(tree, ref.parentId),
                    "application/json",
                    ref.name,
                ) ?: throw IOException("Cannot create ${ref.name}")
                resolver.openOutputStream(target, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    ?: throw IOException("Cannot write ${ref.name}")
            }
        }
    }

    private fun findChild(ref: SidecarRef.Local): Uri? {
        val tree = Uri.parse(ref.tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, ref.parentId)
        resolver.query(
            children, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME), null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == ref.name) return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
            }
        }
        return null
    }
}
