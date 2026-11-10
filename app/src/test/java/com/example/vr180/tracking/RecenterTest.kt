package com.example.vr180.tracking

import com.example.vr180.render.M3
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.atan2

class RecenterTest {
    private val eps = 1e-5f

    /** 仰躺：頭往上仰 80°（看天花板），再往左轉 30°。 */
    private val lyingHead = M3.mul(M3.rotY(0.52f), M3.rotX(1.4f))

    private fun forward(m: FloatArray) = M3.apply(m, 0f, 0f, -1f)
    private fun up(m: FloatArray) = M3.apply(m, 0f, 1f, 0f)

    @Test fun fullRecenterMakesCurrentHeadTheFront() {
        val r = recenteredHead(lyingHead, ref = lyingHead, yaw0 = 0f, drift = 0f, full = true)
        assertArrayEquals(M3.identity(), r, eps)
    }

    @Test fun fullRecenterTracksMovementRelativeToReference() {
        // 置中後頭再往右轉 10°：結果應該就是「往右轉 10°」，與躺的姿勢無關
        val turn = M3.rotY(-0.1745f)
        val r = recenteredHead(M3.mul(lyingHead, turn), ref = lyingHead, yaw0 = 0f, drift = 0f, full = true)
        assertArrayEquals(turn, r, eps)
    }

    @Test fun horizontalRecenterKeepsGravityLocked() {
        val yaw0 = atan2(-lyingHead[2], lyingHead[8])
        val r = recenteredHead(lyingHead, ref = M3.identity(), yaw0 = yaw0, drift = 0f, full = false)
        // 只抵銷水平方向：仰角還在，視線仍朝上，畫面的「上」不會跟著頭轉到水平
        assertEquals(forward(lyingHead)[1], forward(r)[1], eps)
        assertEquals(up(lyingHead)[1], up(r)[1], eps)
    }

    @Test fun driftRotatesAroundWorldVertical() {
        val g = M3.identity()
        val r = recenteredHead(g, ref = g, yaw0 = 0f, drift = 0.1f, full = true)
        assertArrayEquals(M3.rotY(0.1f), r, eps)
    }
}
