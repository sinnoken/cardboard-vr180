package com.example.vr180.input

import android.view.KeyEvent

/**
 * 按鍵配置。同一個 keycode 在不同控制器上的實體位置不同，所以依配置決定動作。
 * 對應表同時列在 README「操作」一節。
 */
enum class ControllerProfile(val label: String) {
    /** 一般雙手手把、遙控器、鍵盤。 */
    STANDARD("標準手把"),

    /**
     * 單手控制器（例如 MOCUTE-052）：第一列 B、SELECT，下方菱形 12 點 X、3 點 Y、6 點 R1、9 點 START，
     * 背面扳機同時送出 A＋R2。依實體位置排：左右一對是音量－／＋，下方與扳機是確認，上方開關選單。
     * 倒退／快轉與上一支／下一支交給搖桿（選單關著時）。
     */
    ONE_HAND("單手控制器");

    fun map(keyCode: Int): VrAction? = when (this) {
        STANDARD -> standard(keyCode)
        ONE_HAND -> when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_X -> VrAction.MENU
            KeyEvent.KEYCODE_BUTTON_Y -> VrAction.VOL_UP
            KeyEvent.KEYCODE_BUTTON_START -> VrAction.VOL_DOWN
            KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_R2 -> VrAction.TRIGGER
            else -> standard(keyCode)
        }
    }

    /** 按鍵測試頁顯示的對應表：(實體按鍵, 動作)。 */
    fun legend(): List<Pair<String, String>> = when (this) {
        STANDARD -> listOf(
            "十字鍵／左搖桿" to "選單開著＝移動焦點；關著時左右＝倒退／快轉（按住越跳越快）、上下＝上一支／下一支",
            "A" to "選取",
            "B" to "返回",
            "Start／Y" to "開關選單",
            "X／Select" to "重新置中",
            "L1／R1" to "倒退／快轉 10 秒",
            "R2" to "播放／暫停",
        )
        ONE_HAND -> listOf(
            "搖桿" to "選單開著＝移動焦點；關著時左右＝倒退／快轉（按住越跳越快）、上下＝上一支／下一支",
            "扳機、R1（菱形 6 點）" to "選單開著＝選取，關著＝播放／暫停",
            "X（菱形 12 點）" to "開關選單",
            "START（9 點）／Y（3 點）" to "音量－／音量＋",
            "B" to "返回",
            "SELECT" to "重新置中",
        )
    }

    private companion object {
        fun standard(keyCode: Int): VrAction? = when (keyCode) {
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

/** 使用者的選擇：自動依控制器名稱判斷，或固定用某一組。存在 controller.json。 */
enum class ControllerChoice(val label: String) {
    AUTO("自動"),
    STANDARD("標準手把"),
    ONE_HAND("單手控制器");

    fun resolve(deviceName: String?): ControllerProfile = when (this) {
        STANDARD -> ControllerProfile.STANDARD
        ONE_HAND -> ControllerProfile.ONE_HAND
        AUTO -> if (ONE_HAND_NAMES.any { deviceName.orEmpty().contains(it, ignoreCase = true) })
            ControllerProfile.ONE_HAND else ControllerProfile.STANDARD
    }

    companion object {
        /** 已知的單手控制器名稱片段。 */
        private val ONE_HAND_NAMES = listOf("MOCUTE")

        fun parse(s: String?): ControllerChoice = entries.firstOrNull { it.name == s } ?: AUTO
    }
}
