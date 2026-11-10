package com.example.vr180.player

import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * 只允許硬體視訊解碼器。音訊解碼器本來就是軟體實作，必須放行，否則會沒有聲音。
 * 搭配 DefaultTrackSelector 的 exceedRendererCapabilitiesIfNecessary = false，
 * 超出硬解能力的版本會被判定為「不支援」，而不是硬撐著播放。
 */
object HardwareOnly {
    val codecSelector = MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
        val all = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecure, requiresTunneling)
        if (MimeTypes.isVideo(mimeType)) all.filter { it.hardwareAccelerated } else all
    }
}
