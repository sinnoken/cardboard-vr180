package com.example.vr180.ui

import android.content.Context
import com.example.vr180.R
import com.example.vr180.source.FolderUnreadableException
import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 把 NAS／檔案讀取的例外轉成看得懂的說明（依 context 的語言）。
 * smbj 的錯誤訊息會帶 NT 狀態碼名稱（例如 STATUS_LOGON_FAILURE），用字串比對即可，不依賴類別細節。
 */
internal fun friendlyError(context: Context, t: Throwable): String {
    val chain = generateSequence(t) { it.cause }.take(8).toList()
    val text = chain.joinToString(" ") { it.message.orEmpty() }
    fun has(s: String) = text.contains(s, ignoreCase = true)
    val res = when {
        chain.any { it is FolderUnreadableException } -> R.string.ui_error_folder_unreadable
        has("STATUS_LOGON_FAILURE") || has("STATUS_WRONG_PASSWORD") -> R.string.ui_error_logon_failure
        has("STATUS_ACCOUNT_DISABLED") || has("STATUS_ACCOUNT_LOCKED_OUT") -> R.string.ui_error_account_disabled
        has("STATUS_BAD_NETWORK_NAME") -> R.string.ui_error_bad_share
        has("STATUS_ACCESS_DENIED") -> R.string.ui_error_access_denied
        has("STATUS_OBJECT_NAME_NOT_FOUND") || has("STATUS_OBJECT_PATH_NOT_FOUND") -> R.string.ui_error_path_not_found
        chain.any { it is UnknownHostException } -> R.string.ui_error_unknown_host
        chain.any { it is SocketTimeoutException } -> R.string.ui_error_timeout
        chain.any { it is ConnectException || it is NoRouteToHostException } -> R.string.ui_error_connect
        chain.any { it is FileNotFoundException } -> R.string.ui_error_file_not_found
        chain.any { it is SecurityException } -> R.string.ui_error_security
        else -> return t.message ?: t.javaClass.simpleName
    }
    return context.getString(res)
}
