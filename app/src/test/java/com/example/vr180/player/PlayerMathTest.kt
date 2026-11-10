package com.example.vr180.player

import com.example.vr180.model.LensProfile
import com.example.vr180.model.VerticalAlignment
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerMathTest {
    @Test fun seekStepGrowsWhileHeld() {
        assertEquals(10_000L, seekStepMs(0))
        assertEquals(10_000L, seekStepMs(999))
        assertEquals(30_000L, seekStepMs(1_000))
        assertEquals(60_000L, seekStepMs(2_000))
        assertEquals(120_000L, seekStepMs(3_000))
        assertEquals(300_000L, seekStepMs(4_000))
        assertEquals(300_000L, seekStepMs(60_000))
    }

    @Test fun resumesOnlyMeaningfulPositions() {
        assertEquals(false, worthResuming(4_999, 3_600_000))      // 才剛開始
        assertEquals(true, worthResuming(5_000, 3_600_000))
        assertEquals(true, worthResuming(3_569_999, 3_600_000))   // 離結束還有 30 秒以上
        assertEquals(false, worthResuming(3_570_000, 3_600_000))  // 最後 30 秒：當作看完
        assertEquals(true, worthResuming(600_000, -1))            // 片長不明
    }

    @Test fun stepperAccelerates() {
        assertEquals(listOf(1, 3, 6, 12), listOf(0L, 1_000L, 2_000L, 3_000L).map(::stepsFor))
    }

    @Test fun formatsTime() {
        assertEquals("0:00", fmtTime(0))
        assertEquals("0:05", fmtTime(5_999))
        assertEquals("44:17", fmtTime((44 * 60 + 17) * 1000L))
        assertEquals("1:02:03", fmtTime((3600 + 2 * 60 + 3) * 1000L))
    }

    @Test fun wrapsAngles() {
        assertEquals(-170f, wrap180(190f), 1e-4f)
        assertEquals(170f, wrap180(-190f), 1e-4f)
        assertEquals(180f, wrap180(180f), 1e-4f)
        assertEquals(0f, wrap180(720f), 1e-4f)
    }

    @Test fun changingAlignmentKeepsLensCenterInPlace() {
        // 螢幕高 66 mm；鏡片中心離下緣 35 mm = 離上緣 31 mm = 中心往上 2 mm
        val bottom = LensProfile.cardboardDefault().copy(alignment = VerticalAlignment.BOTTOM, lensHeightMm = 35f)
        val center = changeLensAlignment(bottom, VerticalAlignment.CENTER, 66f)
        assertEquals(VerticalAlignment.CENTER, center.alignment)
        assertEquals(2f, center.lensHeightMm, 1e-4f)
        val top = changeLensAlignment(center, VerticalAlignment.TOP, 66f)
        assertEquals(31f, top.lensHeightMm, 1e-4f)
        assertEquals(35f, changeLensAlignment(top, VerticalAlignment.BOTTOM, 66f).lensHeightMm, 1e-4f)
        // 螢幕高度還不知道：只換基準
        assertEquals(35f, changeLensAlignment(bottom, VerticalAlignment.CENTER, 0f).lensHeightMm, 1e-4f)
    }

    @Test fun movesLensUpRegardlessOfAlignment() {
        val base = LensProfile.cardboardDefault()
        // 「往上」在下緣基準是距離變大，在上緣基準是距離變小
        val bottom = base.copy(alignment = VerticalAlignment.BOTTOM, lensHeightMm = 35f)
        assertEquals(35.5f, moveLensVertical(bottom, 0.5f).lensHeightMm, 1e-4f)
        val top = base.copy(alignment = VerticalAlignment.TOP, lensHeightMm = 35f)
        assertEquals(34.5f, moveLensVertical(top, 0.5f).lensHeightMm, 1e-4f)
        val center = base.copy(alignment = VerticalAlignment.CENTER, lensHeightMm = 29.8f)
        assertEquals(30f, moveLensVertical(center, 0.5f).lensHeightMm, 1e-4f)   // 置中基準上限 ±30
        assertEquals(0f, moveLensVertical(bottom.copy(lensHeightMm = 0.2f), -0.5f).lensHeightMm, 1e-4f)
    }
}
