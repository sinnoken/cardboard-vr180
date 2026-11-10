package com.example.vr180.render

import kotlin.math.cos
import kotlin.math.sin

/** 3x3 旋轉矩陣小工具，FloatArray(9)，列優先（row-major）。 */
object M3 {
    fun identity() = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(9)
        for (i in 0..2) for (j in 0..2) {
            r[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j]
        }
        return r
    }

    fun mul(vararg ms: FloatArray): FloatArray = ms.reduce { acc, m -> mul(acc, m) }

    fun transpose(a: FloatArray) = floatArrayOf(a[0], a[3], a[6], a[1], a[4], a[7], a[2], a[5], a[8])

    fun apply(a: FloatArray, x: Float, y: Float, z: Float) = floatArrayOf(
        a[0] * x + a[1] * y + a[2] * z,
        a[3] * x + a[4] * y + a[5] * z,
        a[6] * x + a[7] * y + a[8] * z,
    )

    /** 繞 y 軸（向上）。正角度讓前方方向往 -x 轉；對應影片時代表畫面往右移。 */
    fun rotY(rad: Float): FloatArray {
        val c = cos(rad); val s = sin(rad)
        return floatArrayOf(c, 0f, s, 0f, 1f, 0f, -s, 0f, c)
    }

    fun rotX(rad: Float): FloatArray {
        val c = cos(rad); val s = sin(rad)
        return floatArrayOf(1f, 0f, 0f, 0f, c, -s, 0f, s, c)
    }

    fun rotZ(rad: Float): FloatArray {
        val c = cos(rad); val s = sin(rad)
        return floatArrayOf(c, -s, 0f, s, c, 0f, 0f, 0f, 1f)
    }
}
