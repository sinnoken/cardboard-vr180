package com.example.vr180.model

import android.content.Context
import com.example.vr180.R

/**
 * 設定檔顯示在畫面上的名稱。預設檔依 id 取翻譯好的名稱（跟著系統語言），
 * 使用者自己的設定檔用存下來的 name。
 */
fun LensProfile.displayName(context: Context): String {
    if (!isPreset) return name
    val res = when (id) {
        LensProfile.CARDBOARD_DEFAULT_ID -> R.string.ui_preset_cardboard_default
        LensProfile.CARDBOARD_V2_ID -> R.string.ui_preset_cardboard_v2
        LensProfile.GENERIC_CENTER_ID -> R.string.ui_preset_generic_center
        else -> return name           // 未來新增的預設檔還沒翻譯時，退回 name
    }
    return context.getString(res)
}
