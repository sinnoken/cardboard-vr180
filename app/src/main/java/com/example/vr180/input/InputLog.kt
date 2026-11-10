package com.example.vr180.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs

/**
 * 按鍵測試頁的紀錄：只在測試頁開啟時（active = true）由 MainActivity 送進來。
 * 每種控制器、每種模式送出的訊號都不同，這頁讓使用者直接看到收到什麼。
 */
object InputLog {
    @Volatile var active = false
    val lines = mutableStateListOf<String>()

    /** 測試頁目前選的配置，紀錄依它顯示 App 會做什麼。 */
    @Volatile var choice = ControllerChoice.AUTO

    /** 最後一個送出訊號的控制器名稱，讓測試頁顯示「自動」解析成哪一組。 */
    var lastDevice by mutableStateOf<String?>(null)

    private val lastBucket = HashMap<Int, Int>()
    private var lastMouseMs = 0L
    private var chordKey = 0
    private var chordAt = 0L
    private var chordLine: String? = null

    fun clear() { lines.clear(); lastBucket.clear(); chordLine = null }

    private fun add(s: String) {
        lines.add(0, s)
        if (lines.size > 60) lines.removeAt(lines.size - 1)
    }

    fun onKey(e: KeyEvent) {
        if (e.action != KeyEvent.ACTION_DOWN || e.repeatCount > 0) return
        e.device?.name?.let { lastDevice = it }
        val name = KeyEvent.keyCodeToString(e.keyCode).removePrefix("KEYCODE_")
        val mapped = choice.resolve(e.device?.name).map(e.keyCode)?.let { "→ " + ControllerInput.label(it) }
            ?: if (e.keyCode == KeyEvent.KEYCODE_VOLUME_UP || e.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
                "→ 交給系統調音量" else "→ App 不認得"
        val line = "按鍵 $name（${e.keyCode}）$mapped · ${e.device?.name ?: "?"}"
        // 與 ControllerInput 相同的規則：A 與 R2 在 CHORD_MS 內先後送出，合成一次扳機
        val t = e.eventTime
        if (e.keyCode in ControllerInput.CHORD_KEYS) {
            val prev = chordLine
            if (prev != null && chordKey != e.keyCode && t - chordAt < ControllerInput.CHORD_MS) {
                lines.indexOf(prev).takeIf { it >= 0 }?.let { lines[it] = "$prev（合併）" }
                add("$line（合併）")
                add("扳機（A＋R2 同時送出）→ ${ControllerInput.label(VrAction.TRIGGER)} · ${e.device?.name ?: "?"}")
                chordLine = null
                return
            }
            chordKey = e.keyCode; chordAt = t; chordLine = line
        }
        add(line)
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
                    val use = when (axis) {
                        MotionEvent.AXIS_X, MotionEvent.AXIS_HAT_X -> if (bucket > 0) "→ 右" else "→ 左"
                        MotionEvent.AXIS_Y, MotionEvent.AXIS_HAT_Y -> if (bucket > 0) "→ 下" else "→ 上"
                        MotionEvent.AXIS_GAS, MotionEvent.AXIS_RTRIGGER,
                        MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_LTRIGGER -> "→ 扳機按多深，App 只看按鍵，可忽略"
                        else -> "→ App 不使用"
                    }
                    add("搖桿 $name ${if (bucket > 0) "+" else "−"} $use · ${dev.name}")
                }
            }
        }
    }
}
