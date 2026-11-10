package com.example.vr180.tracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import com.example.vr180.render.M3
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 頭部追蹤。
 *
 * - 使用 GAME_ROTATION_VECTOR：系統感測器融合已自動估算陀螺儀零偏，pitch/roll 由重力鎖定；
 *   刻意不用磁力計（手機內部干擾大、會跳）。代價是水平方向（yaw）會緩慢漂移。
 * - 漂移補償：用靜置檢測量到的漂移速率做線性抵銷。
 * - 重新置中：把目前面向設為正前方，同時重設漂移計時。
 * - 延遲預測：依角速度把方向往前推算 predictionMs，減少轉頭時畫面拖曳感。
 *
 * 輸出座標系（GL 世界）：x = 東、y = 上、z = 南；眼睛看向 -z。
 */
class HeadTracker(context: Context, private val displayRotation: Int) : SensorEventListener {

    @Volatile var predictionMs = 25f
    @Volatile var yawDriftDegPerSec = 0f

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    val available get() = sensor != null

    private val lock = Any()
    private val q = floatArrayOf(0f, 0f, 0f, 1f)   // x, y, z, w（世界 ← 裝置）
    private val omega = FloatArray(3)             // 裝置座標系角速度 rad/s（平滑後）
    private var lastTs = 0L
    private var hasSample = false

    private var yaw0 = 0f
    private var tRef = 0L
    @Volatile private var recenterPending = true

    fun start() {
        sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
    }

    fun stop() = sm.unregisterListener(this)

    fun recenter() { recenterPending = true }

    override fun onSensorChanged(e: SensorEvent) {
        val w0 = if (e.values.size >= 4) e.values[3] else {
            val s = 1f - e.values[0] * e.values[0] - e.values[1] * e.values[1] - e.values[2] * e.values[2]
            if (s > 0f) sqrt(s) else 0f
        }
        val cur = floatArrayOf(e.values[0], e.values[1], e.values[2], w0)
        synchronized(lock) {
            if (hasSample) {
                val dt = (e.timestamp - lastTs) * 1e-9f
                if (dt in 1e-4f..0.1f) {
                    // dq = conj(q_prev) * q_cur  →  裝置座標系下的旋轉增量
                    val d = qMul(floatArrayOf(-q[0], -q[1], -q[2], q[3]), cur)
                    if (d[3] < 0f) for (i in 0..3) d[i] = -d[i]
                    val angle = 2f * acos(d[3].coerceIn(-1f, 1f))
                    val sn = sqrt(1f - d[3] * d[3])
                    for (i in 0..2) {
                        val wNew = if (sn > 1e-6f) d[i] / sn * angle / dt else 0f
                        omega[i] = omega[i] * 0.6f + wNew * 0.4f
                    }
                }
            }
            System.arraycopy(cur, 0, q, 0, 4)
            lastTs = e.timestamp
            hasSample = true
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * 取得「世界 ← 螢幕」旋轉矩陣（row-major），已套用預測、重新置中與漂移補償。
     * 在 GL 執行緒每幀呼叫。
     */
    fun headMatrix(nowNanos: Long): FloatArray {
        val qp: FloatArray
        synchronized(lock) {
            if (!hasSample) return M3.identity()
            val ahead = ((nowNanos - lastTs) * 1e-9f).coerceIn(0f, 0.05f) + predictionMs / 1000f
            qp = qMul(q, expMap(omega, ahead))
        }
        val r = quatToMatrix(qp)
        val remapped = FloatArray(9)
        when (displayRotation) {
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(
                r, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remapped)
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(
                r, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remapped)
            else -> System.arraycopy(r, 0, remapped, 0, 9)
        }
        // Android 世界（東、北、上）→ GL 世界（東、上、南）
        val m = remapped
        val g = floatArrayOf(
            m[0], m[1], m[2],
            m[6], m[7], m[8],
            -m[3], -m[4], -m[5],
        )
        if (recenterPending) {
            // 正前方 = G * (0,0,-1)
            yaw0 = atan2(-g[2], g[8])
            tRef = nowNanos
            recenterPending = false
        }
        val drift = Math.toRadians(yawDriftDegPerSec.toDouble()).toFloat() * (nowNanos - tRef) * 1e-9f
        return M3.mul(M3.rotY(yaw0 + drift), g)
    }

    private fun qMul(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
        a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
        a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
        a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2],
    )

    private fun expMap(w: FloatArray, dt: Float): FloatArray {
        val mag = sqrt(w[0] * w[0] + w[1] * w[1] + w[2] * w[2])
        val angle = mag * dt
        if (angle < 1e-6f) return floatArrayOf(0f, 0f, 0f, 1f)
        val s = sin(angle / 2f) / mag
        return floatArrayOf(w[0] * s, w[1] * s, w[2] * s, cos(angle / 2f))
    }

    private fun quatToMatrix(q: FloatArray): FloatArray {
        val (x, y, z, w) = q
        return floatArrayOf(
            1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w),
            2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w),
            2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y),
        )
    }

}
