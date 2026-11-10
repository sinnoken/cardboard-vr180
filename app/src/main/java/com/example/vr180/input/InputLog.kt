package com.example.vr180.input

import android.content.res.Resources
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.vr180.R
import kotlin.math.abs

/**
 * 按鍵測試頁的紀錄：只在測試頁開啟時（active = true）由 MainActivity 送進來。
 * 每種控制器、每種模式送出的訊號都不同，這頁讓使用者直接看到收到什麼。
 */
object InputLog {
    @Volatile var active = false
    val lines = mutableStateListOf<String>()

    /** 紀錄文字用的字串資源，由測試頁開啟時交進來；沒有時不記錄。 */
    @Volatile var resources: Resources? = null

    /** 測試頁目前選的配置，紀錄依它顯示 App 會做什麼。 */
    @Volatile var choice = ControllerChoice.AUTO

    /** 最後一個送出訊號的控制器名稱，讓測試頁顯示「自動」解析成哪一組。 */
    var lastDevice by mutableStateOf<String?>(null)

    private val lastBucket = HashMap<Int, Int>()
    private var lastMouseMs = 0L
    private var lastTriggerAt = -ControllerInput.TRIGGER_DEDUP_MS

    fun clear() { lines.clear(); lastBucket.clear() }

    private fun add(s: String) {
        lines.add(0, s)
        if (lines.size > 60) lines.removeAt(lines.size - 1)
    }

    fun onKey(e: KeyEvent) {
        if (e.action != KeyEvent.ACTION_DOWN || e.repeatCount > 0) return
        val res = resources ?: return
        e.device?.name?.let { lastDevice = it }
        val name = KeyEvent.keyCodeToString(e.keyCode).removePrefix("KEYCODE_")
        val action = choice.resolve(e.device?.name).map(e.keyCode)
        val mapped = "→ " + res.getString(
            action?.let { ControllerInput.label(it) }
                ?: if (e.keyCode == KeyEvent.KEYCODE_VOLUME_UP || e.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
                    R.string.ui_log_volume_system else R.string.ui_log_unknown_key,
        )
        val line = res.getString(R.string.ui_log_key, name, e.keyCode, mapped, e.device?.name ?: "?")
        // 與 ControllerInput 相同的規則：單手配置下，同一次扳機送出的 A＋R2 只算一次
        if (action == VrAction.TRIGGER && e.keyCode in ControllerInput.TRIGGER_KEYS) {
            if (e.eventTime - lastTriggerAt < ControllerInput.TRIGGER_DEDUP_MS) {
                add(res.getString(R.string.ui_log_trigger_dedup, line))
                return
            }
            lastTriggerAt = e.eventTime
        }
        add(line)
    }

    fun onMotion(e: MotionEvent) {
        val res = resources ?: return
        val src = e.source
        fun has(s: Int) = (src and s) == s
        if (has(InputDevice.SOURCE_MOUSE) || has(InputDevice.SOURCE_TOUCHPAD)) {
            val now = e.eventTime
            if (now - lastMouseMs > 800) {
                lastMouseMs = now
                add(res.getString(R.string.ui_log_mouse, e.device?.name ?: "?"))
            }
            return
        }
        if (e.action != MotionEvent.ACTION_MOVE) return
        val dev = e.device ?: return
        lastDevice = dev.name
        // 只在某個軸「推到底 / 放開」時記一筆，避免洗版
        for (r in dev.motionRanges) {
            val axis = r.axis
            val v = e.getAxisValue(axis)
            val bucket = when { v > 0.6f -> 1; v < -0.6f -> -1; abs(v) < 0.3f -> 0; else -> lastBucket[axis] ?: 0 }
            if (bucket != (lastBucket[axis] ?: 0)) {
                lastBucket[axis] = bucket
                if (bucket != 0) {
                    val name = MotionEvent.axisToString(axis).removePrefix("AXIS_")
                    // 播放器只讀左搖桿（X/Y）和十字鍵（HAT_X/HAT_Y）
                    val use = "→ " + res.getString(when (axis) {
                        MotionEvent.AXIS_X, MotionEvent.AXIS_HAT_X ->
                            if (bucket > 0) R.string.ui_action_right else R.string.ui_action_left
                        MotionEvent.AXIS_Y, MotionEvent.AXIS_HAT_Y ->
                            if (bucket > 0) R.string.ui_action_down else R.string.ui_action_up
                        MotionEvent.AXIS_GAS, MotionEvent.AXIS_RTRIGGER,
                        MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_LTRIGGER -> R.string.ui_log_axis_trigger
                        else -> R.string.ui_log_axis_unused
                    })
                    add(res.getString(R.string.ui_log_axis, name, if (bucket > 0) "+" else "−", use, dev.name))
                }
            }
        }
    }
}
