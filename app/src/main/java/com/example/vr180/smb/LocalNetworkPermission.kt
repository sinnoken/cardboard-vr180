package com.example.vr180.smb

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Android 17（API 37）起，targetSdk ≥ 37 的 App 存取區域網路預設被封鎖，
 * 必須在執行時取得 ACCESS_LOCAL_NETWORK（屬於「附近裝置」權限群組）。
 * 較舊的 Android 沒有這個權限，視為已允許。
 */
object LocalNetworkPermission {
    const val NAME = "android.permission.ACCESS_LOCAL_NETWORK"

    val required: Boolean get() = Build.VERSION.SDK_INT >= 37

    fun granted(context: Context): Boolean =
        !required || ContextCompat.checkSelfPermission(context, NAME) == PackageManager.PERMISSION_GRANTED
}
