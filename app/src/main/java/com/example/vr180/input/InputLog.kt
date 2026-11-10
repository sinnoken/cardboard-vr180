package com.example.vr180.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.mutableStateListOf
import kotlin.math.abs

/**
 * 按鍵測試頁的紀錄：只在測試頁開啟時（active = true）由 MainActivity 送進來。
 * 每種控制器、每種模式送出的訊號都不同，這頁讓使用者直接看到收到什麼。
 */
object InputLog {
    @Volatile var active = false
    val lines = mutableStateListOf<String>()

    private val lastBucket = HashMap<Int, Int>()
    private var lastMouseMs = 0L

    fun clear() { lines.clear(); lastBucket.clear() }

    private fun add(s: String) {
        lines.add(0, s)
        if (lines.size > 60) lines.removeAt(lines.size - 1)
    }

    fun onKey(e: KeyEvent) {
        if (e.action != KeyEvent.ACTION_DOWN || e.repeatCount > 0) return
        val name = KeyEvent.keyCodeToString(e.keyCode).removePrefix("KEYCODE_")
        val mapped = ControllerInput.map(e.keyCode)?.let { "→ " + ControllerInput.label(it) }
            ?: if (e.keyCode == KeyEvent.KEYCODE_VOLUME_UP || e.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
                "→ 交給系統調音量" else "→ App 不認得"
        add("按鍵 $name（${e.keyCode}）$mapped · ${e.device?.name ?: "?"}")
    }

    fun onMotion(e: MotionEvent) {
        val src = e.source
        fun has(s: Int) = (src and s) == s
        if (has(InputDevice.SOURCE_MOUSE) || has(InputDevice.SOURCE_TOUCHPAD)) {
            val now = e.eventTime
            if (now - lastMouseMs > 800) {
                lastMouseMs = now
                add("滑鼠／游標移動 → App 不支援，請切到遊戲模式 · ${e.device?.name ?: "?"}")
            }
            return
        }
        if (e.action != MotionEvent.ACTION_MOVE) return
        val dev = e.device ?: return
        // 只在某個軸「推到底 / 放開」時記一筆，避免洗版
        for (r in dev.motionRanges) {
            val axis = r.axis
            val v = e.getAxisValue(axis)
            val bucket = when { v > 0.6f -> 1; v < -0.6f -> -1; abs(v) < 0.3f -> 0; else -> lastBucket[axis] ?: 0 }
            if (bucket != (lastBucket[axis] ?: 0)) {
                lastBucket[axis] = bucket
                if (bucket != 0) {
                    val name = MotionEvent.axisToString(axis).removePrefix("AXIS_")
                    add("搖桿 $name ${if (bucket > 0) "+" else "−"} · ${dev.name}")
                }
            }
        }
    }
}
