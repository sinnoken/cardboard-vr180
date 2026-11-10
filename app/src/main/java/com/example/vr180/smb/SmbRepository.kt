package com.example.vr180.smb

import android.net.Uri
import com.example.vr180.model.SmbConnection
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import java.util.EnumSet
import java.util.concurrent.TimeUnit

data class SmbEntry(val name: String, val isDir: Boolean, val size: Long, val modifiedMs: Long)

/**
 * 管理單一 NAS 的 SMB 連線（SMB2/3，透過 smbj）。所有方法都會做網路 I/O，請在背景執行緒呼叫。
 * 影片位址統一用 smb://host/share/path 表示，交給 SmbDataSource 播放。
 */
object SmbRepository {
    private val client = SMBClient(
        SmbConfig.builder()
            // Android 內建的 BouncyCastle 被裁減過，必須指定完整版
            .withSecurityProvider(BCSecurityProvider())
            .withMultiProtocolNegotiate(true)
            .withReadBufferSize(8 * 1024 * 1024)   // 實際上限取決於 NAS 協商結果
            .withTimeout(30, TimeUnit.SECONDS)
            .withSoTimeout(30, TimeUnit.SECONDS)
            .build()
    )

    @Volatile private var conn: SmbConnection? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private val shares = HashMap<String, DiskShare>()

    fun configure(c: SmbConnection) {
        // 播放器在主執行緒呼叫：設定沒變（幾乎都是這樣）就不碰鎖，免得 NAS 很慢時卡在另一條執行緒的連線上
        if (c == conn) return
        synchronized(this) {
            if (c != conn) {
                closeAll()
                conn = c
            }
        }
    }

    @Synchronized fun share(host: String, shareName: String): DiskShare {
        val c = conn ?: error("NAS connection not configured")
        check(host.equals(c.host, ignoreCase = true)) { "No credentials for $host" }
        shares[shareName]?.let { if (it.isConnected) return it }

        val cn = connection?.takeIf { it.isConnected } ?: run {
            // 先用 6 秒試連 445 埠：位址打錯時很快就回報，不用等 smbj 的 30 秒逾時
            java.net.Socket().use { it.connect(java.net.InetSocketAddress(c.host, 445), 6_000) }
            client.connect(c.host)
        }.also {
            connection = it; session = null; shares.clear()
        }
        val ss = session ?: cn.authenticate(
            if (c.user.isBlank()) AuthenticationContext.guest()
            else AuthenticationContext(c.user, c.password.toCharArray(), c.domain)
        ).also { session = it }
        return (ss.connectShare(shareName) as DiskShare).also { shares[shareName] = it }
    }

    fun list(host: String, shareName: String, dir: String): List<SmbEntry> =
        share(host, shareName).list(toSmbPath(dir))
            .filter { it.fileName != "." && it.fileName != ".." && !it.fileName.startsWith(".") }
            .map {
                SmbEntry(
                    it.fileName,
                    (it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L,
                    it.endOfFile,
                    it.lastWriteTime.toEpochMillis(),
                )
            }
            .sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))

    fun openRead(uri: Uri): SmbFile {
        val (host, shareName, path) = parse(uri)
        return share(host, shareName).openFile(
            toSmbPath(path), EnumSet.of(AccessMask.GENERIC_READ), null,
            SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
        )
    }

    /** 讀小檔（例如 sidecar 校正檔）。檔案不存在時回傳 null。 */
    fun readText(uri: Uri): String? {
        val (host, shareName, path) = parse(uri)
        val s = share(host, shareName)
        if (!s.fileExists(toSmbPath(path))) return null
        openRead(uri).use { f -> return f.getInputStream().use { it.readBytes() }.toString(Charsets.UTF_8) }
    }

    /** 寫小檔；NAS 共用資料夾唯讀時會丟出例外。 */
    fun writeText(uri: Uri, text: String) {
        val (host, shareName, path) = parse(uri)
        share(host, shareName).openFile(
            toSmbPath(path), EnumSet.of(AccessMask.GENERIC_WRITE), null,
            SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, null,
        ).use { it.write(text.toByteArray(Charsets.UTF_8), 0L) }
    }

    fun buildUri(host: String, shareName: String, path: String): Uri =
        Uri.Builder().scheme("smb").authority(host)
            .path("/" + shareName + "/" + path.trim('/')).build()

    private fun parse(uri: Uri): Triple<String, String, String> {
        val host = uri.host ?: error("Invalid SMB URI: $uri")
        val segs = uri.pathSegments
        require(segs.size >= 2) { "Invalid SMB URI: $uri" }
        return Triple(host, segs[0], segs.drop(1).joinToString("/"))
    }

    private fun toSmbPath(p: String) = p.trim('/').replace('/', '\\')

    @Synchronized fun closeAll() {
        runCatching { shares.values.forEach { it.close() } }
        runCatching { session?.close() }
        runCatching { connection?.close() }
        shares.clear(); session = null; connection = null
    }
}
