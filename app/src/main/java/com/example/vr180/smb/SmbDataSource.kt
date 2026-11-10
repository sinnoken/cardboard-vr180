package com.example.vr180.smb

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import java.io.IOException
import com.hierynomus.smbj.share.File as SmbFile

/**
 * 讓 ExoPlayer 直接從 SMB 讀檔（原檔直送，不經 NAS 轉檔）。
 * ExoPlayer 每次只要幾十 KB，若每次都發一個 SMB 請求會被網路延遲拖垮，
 * 所以這裡一次讀 2 MB 進緩衝區再分批交出去，高位元率的 8K 影片才跑得動。
 * 不用更大：跳轉後要讀滿一整塊才交得出第一個位元組，4 MB 在 Wi-Fi 上會讓跳轉後的首幀多等約 0.1–0.3 秒。
 */
class SmbDataSource : BaseDataSource(/* isNetwork = */ true) {
    private var uri: Uri? = null
    private var file: SmbFile? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var opened = false

    private val chunk = ByteArray(CHUNK_SIZE)
    private var chunkStart = 0L
    private var chunkLen = 0

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        try {
            val f = SmbRepository.openRead(dataSpec.uri)
            file = f
            val size = f.fileInformation.standardInformation.endOfFile
            if (dataSpec.position > size) {
                throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            }
            position = dataSpec.position
            bytesRemaining =
                if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else size - position
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw DataSourceException(e, openErrorCode(e))
        }
        chunkLen = 0
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        if (position < chunkStart || position >= chunkStart + chunkLen) fillChunk()
        if (chunkLen == 0) return C.RESULT_END_OF_INPUT

        val idx = (position - chunkStart).toInt()
        val n = minOf(length.toLong(), (chunkLen - idx).toLong(), bytesRemaining).toInt()
        System.arraycopy(chunk, idx, buffer, offset, n)
        position += n
        bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    private fun fillChunk() {
        val f = file ?: throw IOException("檔案尚未開啟")
        chunkStart = position
        var filled = 0
        try {
            // smbj 每次最多讀到 NAS 協商的上限（通常 1–8 MB），不足就繼續讀
            while (filled < CHUNK_SIZE) {
                val r = f.read(chunk, position + filled, filled, CHUNK_SIZE - filled)
                if (r <= 0) break
                filled += r
            }
        } catch (e: Exception) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        chunkLen = filled
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            file?.close()
        } catch (_: Exception) {
        } finally {
            file = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    companion object {
        /** smbj 的錯誤訊息帶 NT 狀態碼名稱：找不到檔案、沒有權限要跟網路斷線分開，播放器才顯示得出真正原因。 */
        private fun openErrorCode(e: Exception): Int {
            val text = generateSequence<Throwable>(e) { it.cause }.take(8).joinToString(" ") { it.message.orEmpty() }
            return when {
                "STATUS_OBJECT_NAME_NOT_FOUND" in text || "STATUS_OBJECT_PATH_NOT_FOUND" in text ->
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
                "STATUS_ACCESS_DENIED" in text || "STATUS_LOGON_FAILURE" in text ->
                    PlaybackException.ERROR_CODE_IO_NO_PERMISSION
                else -> PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            }
        }

        const val CHUNK_SIZE = 2 * 1024 * 1024
    }
}
