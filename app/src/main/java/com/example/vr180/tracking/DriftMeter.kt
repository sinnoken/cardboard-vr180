package com.example.vr180.tracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2

/**
 * 靜置檢測：手機平放桌上不動，量測 GAME_ROTATION_VECTOR 的水平方向漂移速率。
 * 以最小平方法擬合「方位角 vs 時間」的斜率；若期間手機被移動（傾角變化過大）則判定無效。
 */
class DriftMeter(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    private val times = ArrayList<Double>()
    private val headings = ArrayList<Double>()
    private var lastHeading = Double.NaN
    private var unwrap = 0.0
    private var t0 = 0L
    private var tiltRef = Double.NaN
    var moved = false
        private set

    val sampleCount get() = synchronized(this) { times.size }

    fun start() {
        synchronized(this) {
            times.clear(); headings.clear()
            lastHeading = Double.NaN; unwrap = 0.0; t0 = 0L; tiltRef = Double.NaN; moved = false
        }
        sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        val r = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(r, e.values)
        // 裝置 Y 軸在世界座標中的方向；平放時 Y 軸是水平的，可用來算方位角
        val h = atan2(r[1].toDouble(), r[4].toDouble())
        val tilt = r[8].toDouble()   // 螢幕法線與「上」的夾角餘弦
        synchronized(this) {
            if (t0 == 0L) t0 = e.timestamp
            if (tiltRef.isNaN()) tiltRef = tilt
            if (abs(tilt - tiltRef) > 0.02) moved = true
            if (!lastHeading.isNaN()) {
                var d = h - lastHeading
                if (d > PI) d -= 2 * PI
                if (d < -PI) d += 2 * PI
                unwrap += d
            }
            lastHeading = h
            times += (e.timestamp - t0) * 1e-9
            headings += unwrap
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** 回傳漂移速率（度/秒）；樣本不足時回傳 null。 */
    fun driftDegPerSec(): Double? = synchronized(this) {
        val n = times.size
        if (n < 50) return null
        val mt = times.average(); val mh = headings.average()
        var num = 0.0; var den = 0.0
        for (i in 0 until n) {
            num += (times[i] - mt) * (headings[i] - mh)
            den += (times[i] - mt) * (times[i] - mt)
        }
        if (den == 0.0) null else Math.toDegrees(num / den)
    }
}
