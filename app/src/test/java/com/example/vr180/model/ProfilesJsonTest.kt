package com.example.vr180.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** 這些 JSON 是已經部署的資料格式（docs/data-formats.md）：來回轉換要一致，缺欄位要回到預設值。 */
class ProfilesJsonTest {
    @Test fun lensRoundTrips() {
        val l = LensProfile.cardboardDefault().copy(id = "mine", name = "我的眼鏡", k1 = 0.33f, tiltDeg = -1.2f)
        assertEquals(l, LensProfile.fromJson(JSONObject(l.toJson().toString())))
    }

    @Test fun sceneRoundTripsAndDefaults() {
        val s = SceneCalib(yawDeg = 12f, pitchDeg = -3.5f, rollDeg = 0.4f, swapEyes = true)
        assertEquals(s, SceneCalib.fromJson(JSONObject(s.toJson().toString())))
        assertEquals(SceneCalib(), SceneCalib.fromJson(JSONObject()))
    }

    @Test fun deviceDefaults() {
        assertEquals(DeviceCalib(), DeviceCalib.fromJson(JSONObject()))
        val d = DeviceCalib(pxPerMm = 17.3f, yawDriftDegPerSec = 0.01f, predictionMs = 30f)
        assertEquals(d, DeviceCalib.fromJson(JSONObject(d.toJson().toString())))
    }

    @Test fun connectionRoundTrips() {
        val c = SmbConnection("nas.local", "video", "me", "secret")
        assertEquals(c, SmbConnection.fromJson(JSONObject(c.toJson().toString())))
    }
}
