package com.example.vr180.player

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.ContentDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.example.vr180.smb.SmbDataSource
import java.io.IOException

/**
 * 依位址類型選擇讀取方式：smb:// 走 SMB，content:// 走 Android 內建的本機檔案讀取。
 * 刻意不使用 DefaultDataSource，確保播放器只會讀這兩種來源。
 */
class RoutingDataSource(context: Context) : DataSource {
    private val smb = SmbDataSource()
    private val content = ContentDataSource(context)
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        smb.addTransferListener(transferListener)
        content.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val d = when (dataSpec.uri.scheme) {
            "smb" -> smb
            "content" -> content
            else -> throw IOException("Unsupported URI: ${dataSpec.uri}")
        }
        active = d
        return d.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active?.read(buffer, offset, length) ?: throw IOException("Data source not opened")

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            active?.close()
        } finally {
            active = null
        }
    }

    class Factory(context: Context) : DataSource.Factory {
        private val app = context.applicationContext
        override fun createDataSource(): DataSource = RoutingDataSource(app)
    }
}
