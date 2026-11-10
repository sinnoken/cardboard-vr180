package com.example.vr180.tracking

import com.example.vr180.render.M3

/**
 * 重新置中後的頭部方向（世界 ← 螢幕）。
 *
 * - 只轉水平（full = false）：只抵銷置中當下的水平方向 yaw0，仰俯與傾斜仍由重力鎖定，地平線跟真實世界對齊。
 * - 全方向（full = true）：以置中當下的完整方向 ref 為基準，躺著、側躺按一下置中，影片就端正地出現在眼前；
 *   代價是地平線不再跟重力對齊，換姿勢要再置中。
 *
 * 兩種都先套用水平漂移補償 drift（繞世界垂直軸）。
 *
 * @param g 目前的頭部方向（GL 世界 ← 螢幕）
 * @param ref 置中當下的 g（只有全方向使用）
 * @param yaw0 置中當下的水平方向（只轉水平使用）
 * @param drift 置中後累積的漂移補償角（弧度）
 */
fun recenteredHead(g: FloatArray, ref: FloatArray, yaw0: Float, drift: Float, full: Boolean): FloatArray =
    if (full) M3.mul(M3.transpose(ref), M3.mul(M3.rotY(drift), g))
    else M3.mul(M3.rotY(yaw0 + drift), g)
