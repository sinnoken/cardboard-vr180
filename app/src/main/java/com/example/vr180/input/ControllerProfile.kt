package com.example.vr180.input

import android.content.Context
import android.view.KeyEvent
import androidx.annotation.StringRes
import com.example.vr180.R

/**
 * 按鍵配置。同一個 keycode 在不同控制器上的實體位置不同，所以依配置決定動作。
 * 對應表同時列在 README「操作」一節。
 */
enum class ControllerProfile(@StringRes val labelRes: Int) {
    /** 一般雙手手把、遙控器、鍵盤。 */
    STANDARD(R.string.ui_profile_standard),

    /**
     * 單手控制器（例如 MOCUTE-052）：第一列 B、SELECT，下方菱形 12 點 X、3 點 Y、6 點 R1、9 點 START，
     * 背面扳機同時送出 A＋R2。依實體位置排：左右一對是音量－／＋，下方與扳機是確認，上方開關選單。
     * 倒退／快轉與上一支／下一支交給搖桿（選單關著時）。
     */
    ONE_HAND(R.string.ui_profile_one_hand);

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

    /** 按鍵測試頁顯示的對應表：(實體按鍵, 動作)，依 context 的語言取字串。 */
    fun legend(context: Context): List<Pair<String, String>> {
        fun s(@StringRes id: Int) = context.getString(id)
        return when (this) {
            STANDARD -> listOf(
                s(R.string.ui_legend_dpad_stick) to s(R.string.ui_legend_move),
                "A" to s(R.string.ui_action_select),
                "B" to s(R.string.ui_action_back),
                s(R.string.ui_legend_start_y) to s(R.string.ui_action_menu),
                s(R.string.ui_legend_x_select) to s(R.string.ui_action_recenter),
                s(R.string.ui_legend_l1_r1) to s(R.string.ui_legend_seek),
                "R2" to s(R.string.ui_legend_play_pause),
            )
            ONE_HAND -> listOf(
                s(R.string.ui_legend_stick) to s(R.string.ui_legend_move),
                s(R.string.ui_legend_trigger_r1) to s(R.string.ui_legend_trigger_action),
                s(R.string.ui_legend_x_12) to s(R.string.ui_action_menu),
                s(R.string.ui_legend_start_y_one_hand) to s(R.string.ui_legend_volume),
                "B" to s(R.string.ui_action_back),
                "SELECT" to s(R.string.ui_action_recenter),
            )
        }
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
enum class ControllerChoice(@StringRes val labelRes: Int) {
    AUTO(R.string.ui_choice_auto),
    STANDARD(R.string.ui_profile_standard),
    ONE_HAND(R.string.ui_profile_one_hand);

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
