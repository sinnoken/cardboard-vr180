package com.example.vr180.player

import com.example.vr180.model.LensProfile
import com.example.vr180.model.VerticalAlignment

// 播放器用到的純計算，放在 Activity 外面才能寫 JVM 單元測試。

/** 按住倒退／快轉的時間 → 每次跳多遠：每秒升一級，10 秒、30 秒、1 分、2 分、5 分。 */
fun seekStepMs(heldMs: Long): Long = when {
    heldMs < 1_000 -> 10_000L
    heldMs < 2_000 -> 30_000L
    heldMs < 3_000 -> 60_000L
    heldMs < 4_000 -> 120_000L
    else -> 300_000L
}

/** 按住數值項目左右的時間 → 每次連發要走幾格。 */
fun stepsFor(heldMs: Long): Int = when {
    heldMs < 1_000 -> 1
    heldMs < 2_000 -> 3
    heldMs < 3_000 -> 6
    else -> 12
}

/** 毫秒 → 「m:ss」，超過一小時「h:mm:ss」。 */
fun fmtTime(ms: Long): String {
    val t = ms / 1000
    val h = t / 3600; val m = (t % 3600) / 60; val sec = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** 畫面往上（up > 0）或往下移動 |up| mm，依基準換算成 lensHeightMm。 */
fun moveLensVertical(l: LensProfile, up: Float): LensProfile = when (l.alignment) {
    VerticalAlignment.BOTTOM -> l.copy(lensHeightMm = (l.lensHeightMm + up).coerceAtLeast(0f))
    VerticalAlignment.TOP -> l.copy(lensHeightMm = (l.lensHeightMm - up).coerceAtLeast(0f))
    VerticalAlignment.CENTER -> l.copy(lensHeightMm = (l.lensHeightMm + up).coerceIn(-30f, 30f))
}

/**
 * 換鏡片中心的上下基準，同時換算 lensHeightMm，讓鏡片中心在螢幕上的實際位置不變。
 * screenHeightMm 不知道（≤ 0）時只能直接換基準。
 */
fun changeLensAlignment(l: LensProfile, to: VerticalAlignment, screenHeightMm: Float): LensProfile {
    if (screenHeightMm <= 0f) return l.copy(alignment = to)
    val h = screenHeightMm
    val fromBottom = when (l.alignment) {
        VerticalAlignment.BOTTOM -> l.lensHeightMm
        VerticalAlignment.TOP -> h - l.lensHeightMm
        VerticalAlignment.CENTER -> h / 2f + l.lensHeightMm
    }
    val value = when (to) {
        VerticalAlignment.BOTTOM -> fromBottom
        VerticalAlignment.TOP -> h - fromBottom
        VerticalAlignment.CENTER -> fromBottom - h / 2f
    }
    return l.copy(alignment = to, lensHeightMm = value)
}

/** 續播：看不到 5 秒不記；看到最後 30 秒當作看完，下次從頭。durMs ≤ 0（片長不明）時只看前一條。 */
fun worthResuming(posMs: Long, durMs: Long): Boolean =
    posMs >= 5_000 && (durMs <= 0 || posMs < durMs - 30_000)

/** 角度換到 (-180, 180]。 */
fun wrap180(v: Float): Float {
    var x = v
    while (x > 180f) x -= 360f
    while (x < -180f) x += 360f
    return x
}
