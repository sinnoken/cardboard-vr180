package com.example.vr180.input

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.annotation.StringRes
import com.example.vr180.R
import kotlin.math.abs

/** 播放器層級的動作，與實際按鍵無關。 */
enum class VrAction {
    UP, DOWN, LEFT, RIGHT, SELECT, BACK, MENU, RECENTER, PLAY_PAUSE, SEEK_BACK, SEEK_FWD,
    /** 選單開著＝選取，關著＝播放／暫停（單手控制器的扳機與 R1）。 */
    TRIGGER,
    VOL_UP, VOL_DOWN,
}

/**
 * 藍芽控制器輸入：手把、D-pad 遙控器、多媒體遙控器、鍵盤都會被 Android 當成 HID 輸入裝置，
 * 事件直接送進 Activity，不需要藍芽權限。按鍵對應依 [ControllerProfile]，音量鍵交給系統。
 */
class ControllerInput(
    /** 使用者選的配置；每次按鍵時依事件的控制器名稱解析。 */
    private val choice: () -> ControllerChoice,
    /** heldMs = 0 表示剛按下；連發時是已經按住的毫秒數，讓呼叫端決定要加速或節流。 */
    private val onAction: (action: VrAction, heldMs: Long) -> Unit,
) {

    private val handler = Handler(Looper.getMainLooper())
    private var repeating: VrAction? = null
    private var pressedAt = 0L
    private val repeatRunnable = object : Runnable {
        override fun run() {
            repeating?.let {
                onAction(it, SystemClock.uptimeMillis() - pressedAt)
                handler.postDelayed(this, REPEAT_MS)
            }
        }
    }

    // 類比輸入（HAT、搖桿）的目前方向，用來偵測「新按下」
    private var axisDirX = 0
    private var axisDirY = 0

    // 單手控制器的扳機會同時送出 A＋R2（分不開），單手配置下兩個都是 TRIGGER：同一次扳機只算一次。
    // 標準配置的 A 與 R2 是兩顆獨立的鍵，按下就送出，不等待。
    private var lastTriggerAt = -TRIGGER_DEDUP_MS

    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val profile = choice().resolve(event.device?.name)
        val a = profile.map(keyCode) ?: return false
        if (event.repeatCount > 0) return true          // 自己處理連發，忽略系統的
        if (a == VrAction.TRIGGER && keyCode in TRIGGER_KEYS) {
            if (event.eventTime - lastTriggerAt < TRIGGER_DEDUP_MS) return true
            lastTriggerAt = event.eventTime
        }
        onAction(a, 0L)
        if (a in REPEATABLE) startRepeat(a)
        return true
    }

    fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val a = choice().resolve(event.device?.name).map(keyCode) ?: return false
        if (repeating == a) stopRepeat()
        return true
    }

    fun onGenericMotion(e: MotionEvent): Boolean {
        val src = e.source
        fun has(s: Int) = (src and s) == s
        val isJoy = has(InputDevice.SOURCE_JOYSTICK) || has(InputDevice.SOURCE_GAMEPAD) ||
            has(InputDevice.SOURCE_DPAD)
        if (!isJoy || e.action != MotionEvent.ACTION_MOVE) return false

        val hx = e.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val sx = e.getAxisValue(MotionEvent.AXIS_X)
        val sy = e.getAxisValue(MotionEvent.AXIS_Y)
        val x = if (abs(hx) > abs(sx)) hx else sx
        val y = if (abs(hy) > abs(sy)) hy else sy

        val dx = dir(x); val dy = dir(y)
        if (dx != axisDirX) {
            axisDirX = dx
            if (dx != 0) press(if (dx < 0) VrAction.LEFT else VrAction.RIGHT) else release(VrAction.LEFT, VrAction.RIGHT)
        }
        if (dy != axisDirY) {
            axisDirY = dy
            if (dy != 0) press(if (dy < 0) VrAction.UP else VrAction.DOWN) else release(VrAction.UP, VrAction.DOWN)
        }
        return true
    }

    fun cancel() {
        stopRepeat()
    }

    private fun dir(v: Float) = when {
        v > 0.6f -> 1
        v < -0.6f -> -1
        else -> 0
    }

    private fun press(a: VrAction) { onAction(a, 0L); startRepeat(a) }
    private fun release(vararg a: VrAction) { repeating?.let { if (it in a) stopRepeat() } }

    private fun startRepeat(a: VrAction) {
        stopRepeat()
        repeating = a
        pressedAt = SystemClock.uptimeMillis()
        handler.postDelayed(repeatRunnable, REPEAT_DELAY_MS)
    }

    private fun stopRepeat() {
        repeating = null
        handler.removeCallbacks(repeatRunnable)
    }

    companion object {
        private const val REPEAT_DELAY_MS = 400L
        private const val REPEAT_MS = 90L
        /** 單手控制器同一次扳機送出 A 與 R2 的時間差上限。 */
        const val TRIGGER_DEDUP_MS = 80L
        val TRIGGER_KEYS = setOf(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_R2)
        private val REPEATABLE = setOf(VrAction.UP, VrAction.DOWN, VrAction.LEFT, VrAction.RIGHT)

        /** 按鍵測試頁顯示的動作名稱（字串資源 id，由畫面端依語言取字串）。 */
        @StringRes
        fun label(a: VrAction): Int = when (a) {
            VrAction.UP -> R.string.ui_action_up; VrAction.DOWN -> R.string.ui_action_down
            VrAction.LEFT -> R.string.ui_action_left; VrAction.RIGHT -> R.string.ui_action_right
            VrAction.SELECT -> R.string.ui_action_select; VrAction.BACK -> R.string.ui_action_back
            VrAction.MENU -> R.string.ui_action_menu
            VrAction.RECENTER -> R.string.ui_action_recenter; VrAction.PLAY_PAUSE -> R.string.ui_action_play_pause
            VrAction.SEEK_BACK -> R.string.ui_action_seek_back; VrAction.SEEK_FWD -> R.string.ui_action_seek_fwd
            VrAction.TRIGGER -> R.string.ui_action_trigger
            VrAction.VOL_UP -> R.string.ui_action_vol_up; VrAction.VOL_DOWN -> R.string.ui_action_vol_down
        }
    }
}
