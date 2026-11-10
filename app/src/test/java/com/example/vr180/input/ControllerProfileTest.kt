package com.example.vr180.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ControllerProfileTest {
    @Test fun oneHandFollowsPhysicalLayout() {
        val p = ControllerProfile.ONE_HAND
        assertEquals(VrAction.MENU, p.map(KeyEvent.KEYCODE_BUTTON_X))          // 12 點
        assertEquals(VrAction.VOL_UP, p.map(KeyEvent.KEYCODE_BUTTON_Y))        // 3 點
        assertEquals(VrAction.TRIGGER, p.map(KeyEvent.KEYCODE_BUTTON_R1))      // 6 點
        assertEquals(VrAction.VOL_DOWN, p.map(KeyEvent.KEYCODE_BUTTON_START))  // 9 點
        assertEquals(VrAction.TRIGGER, p.map(KeyEvent.KEYCODE_BUTTON_A))       // 扳機
        assertEquals(VrAction.TRIGGER, p.map(KeyEvent.KEYCODE_BUTTON_R2))      // 扳機
        assertEquals(VrAction.BACK, p.map(KeyEvent.KEYCODE_BUTTON_B))
        assertEquals(VrAction.RECENTER, p.map(KeyEvent.KEYCODE_BUTTON_SELECT))
    }

    @Test fun standardKeepsGamepadMeanings() {
        val p = ControllerProfile.STANDARD
        assertEquals(VrAction.SELECT, p.map(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(VrAction.PLAY_PAUSE, p.map(KeyEvent.KEYCODE_BUTTON_R2))
        assertEquals(VrAction.SEEK_FWD, p.map(KeyEvent.KEYCODE_BUTTON_R1))
        assertEquals(VrAction.MENU, p.map(KeyEvent.KEYCODE_BUTTON_START))
        assertNull(p.map(KeyEvent.KEYCODE_VOLUME_UP))                          // 交給系統
    }

    @Test fun autoPicksOneHandByName() {
        assertEquals(ControllerProfile.ONE_HAND, ControllerChoice.AUTO.resolve("MOCUTE-052-DY Mouse"))
        assertEquals(ControllerProfile.STANDARD, ControllerChoice.AUTO.resolve("Xbox Wireless Controller"))
        assertEquals(ControllerProfile.STANDARD, ControllerChoice.AUTO.resolve(null))
        assertEquals(ControllerProfile.ONE_HAND, ControllerChoice.ONE_HAND.resolve("Xbox Wireless Controller"))
    }

    @Test fun unknownChoiceFallsBackToAuto() {
        assertEquals(ControllerChoice.AUTO, ControllerChoice.parse(null))
        assertEquals(ControllerChoice.AUTO, ControllerChoice.parse("SOMETHING_ELSE"))
        assertEquals(ControllerChoice.ONE_HAND, ControllerChoice.parse("ONE_HAND"))
    }
}
