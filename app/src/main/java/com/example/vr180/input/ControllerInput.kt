package com.example.vr180.input

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

/** 播放器層級的動作，與實際按鍵無關。 */
enum class VrAction {
    UP, DOWN, LEFT, RIGHT, SELECT, BACK, MENU, RECENTER, PLAY_PAUSE, SEEK_BACK, SEEK_FWD,
}

/**
 * 藍芽控制器輸入：手把、D-pad 遙控器、多媒體遙控器、鍵盤都會被 Android 當成 HID 輸入裝置，
 * 事件直接送進 Activity，不需要藍芽權限。
 *
 * 對應表（同時列在 README）：
 *   D-pad / 左搖桿 / 方向鍵         → 上下左右（按住會連續觸發）
 *   A / 確認 / Enter / D-pad 中鍵     → 選取
 *   B / 返回 / Esc                  → 返回
 *   Start / Y / Menu                → 開關選單
 *   X / Select / 左搖桿按下          → 重新置中
 *   L1 / R1 / 上一首 / 下一首 / 快退 / 快轉 → 倒退 / 快轉 10 秒
 *   播放暫停鍵 / 空白鍵 / R2         → 播放 / 暫停
 *   音量鍵                          → 交給系統
 */
class ControllerInput(
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

    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val a = map(keyCode) ?: return false
        if (event.repeatCount > 0) return true          // 自己處理連發，忽略系統的
        onAction(a, 0L)
        if (a in REPEATABLE) startRepeat(a)
        return true
    }

    fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val a = map(keyCode) ?: return false
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

    fun cancel() = stopRepeat()

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
        private val REPEATABLE = setOf(VrAction.UP, VrAction.DOWN, VrAction.LEFT, VrAction.RIGHT)

        /** 按鍵測試頁用的中文名稱。 */
        fun label(a: VrAction): String = when (a) {
            VrAction.UP -> "上"; VrAction.DOWN -> "下"; VrAction.LEFT -> "左"; VrAction.RIGHT -> "右"
            VrAction.SELECT -> "選取"; VrAction.BACK -> "返回"; VrAction.MENU -> "開關選單"
            VrAction.RECENTER -> "重新置中"; VrAction.PLAY_PAUSE -> "播放/暫停"
            VrAction.SEEK_BACK -> "倒退 10 秒"; VrAction.SEEK_FWD -> "快轉 10 秒"
        }

        fun map(keyCode: Int): VrAction? = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> VrAction.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> VrAction.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> VrAction.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> VrAction.RIGHT
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> VrAction.SELECT
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> VrAction.BACK
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_MENU -> VrAction.MENU
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_BUTTON_THUMBL -> VrAction.RECENTER
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> VrAction.SEEK_BACK
            KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_NEXT -> VrAction.SEEK_FWD
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_BUTTON_R2 -> VrAction.PLAY_PAUSE
            else -> null
        }
    }
}
