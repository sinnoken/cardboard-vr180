package com.example.vr180.ui

import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 把 NAS／檔案讀取的例外轉成看得懂的中文。
 * smbj 的錯誤訊息會帶 NT 狀態碼名稱（例如 STATUS_LOGON_FAILURE），用字串比對即可，不依賴類別細節。
 */
internal fun friendlyError(t: Throwable): String {
    val chain = generateSequence(t) { it.cause }.take(8).toList()
    val text = chain.joinToString(" ") { it.message.orEmpty() }
    fun has(s: String) = text.contains(s, ignoreCase = true)
    return when {
        has("STATUS_LOGON_FAILURE") || has("STATUS_WRONG_PASSWORD") -> "帳號或密碼錯誤"
        has("STATUS_ACCOUNT_DISABLED") || has("STATUS_ACCOUNT_LOCKED_OUT") -> "這個帳號被停用或鎖定"
        has("STATUS_BAD_NETWORK_NAME") -> "找不到這個共用資料夾，請確認名稱"
        has("STATUS_ACCESS_DENIED") -> "這個帳號沒有權限讀取"
        has("STATUS_OBJECT_NAME_NOT_FOUND") || has("STATUS_OBJECT_PATH_NOT_FOUND") -> "找不到這個資料夾，可能已被移動或刪除"
        chain.any { it is UnknownHostException } -> "找不到主機，請確認 NAS 位址"
        chain.any { it is SocketTimeoutException } -> "NAS 沒有回應：位址可能打錯、NAS 關機，或手機和 NAS 不在同一個網路"
        chain.any { it is ConnectException || it is NoRouteToHostException } ->
            "連不到 NAS：請確認位址、NAS 已開機，且手機和 NAS 在同一個網路"
        chain.any { it is FileNotFoundException } -> "找不到檔案或資料夾"
        chain.any { it is SecurityException } -> "沒有存取權限，請重新選擇這個資料夾"
        else -> t.message ?: t.javaClass.simpleName
    }
}
