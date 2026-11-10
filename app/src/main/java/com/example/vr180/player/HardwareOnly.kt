package com.example.vr180.player

import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * 只允許硬體視訊解碼器。音訊解碼器本來就是軟體實作，必須放行，否則會沒有聲音。
 * 不允許改用軟體解碼：超出硬解能力時由 PlayerActivity 降版，最後一個版本則照樣交給硬體解碼器嘗試。
 */
object HardwareOnly {
    val codecSelector = MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
        val all = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecure, requiresTunneling)
        if (MimeTypes.isVideo(mimeType)) all.filter { it.hardwareAccelerated } else all
    }
}
