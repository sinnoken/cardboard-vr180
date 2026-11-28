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

    @Test fun smbProfilesRoundTrip() {
        val list = listOf(
            SmbProfile("a", "客廳", SmbConnection("192.168.1.20", "video", "me", "pw"), listOf("旅遊/2026 #1", "VR")),
            SmbProfile("b", conn = SmbConnection("192.168.1.20", "kids")),
        )
        assertEquals(list, SmbProfile.listFromJson(JSONObject(SmbProfile.listToJson(list).toString())))
        assertEquals(emptyList<SmbProfile>(), SmbProfile.listFromJson(JSONObject()))
    }

    @Test fun smbProfileWithoutIdIsSkipped() {
        val o = JSONObject().put("profiles", org.json.JSONArray().put(JSONObject().put("host", "x")))
        assertEquals(emptyList<SmbProfile>(), SmbProfile.listFromJson(o))
    }

    @Test fun smbProfileBookmarksDefaultToEmptyAndIgnoreInvalidPaths() {
        val profile = JSONObject("""{"id":"a","host":"nas","share":"video"}""")
        assertEquals(emptyList<String>(), SmbProfile.fromJson(profile).bookmarks)
        profile.put("bookmarks", org.json.JSONArray("""["","/absolute","one/../two","one//two","one\\two",42,"one/two","one/two"]"""))
        assertEquals(listOf("one/two"), SmbProfile.fromJson(profile).bookmarks)
    }

    @Test fun smbBookmarksCanBeAddedAndRemovedIndependently() {
        val original = SmbProfile("a")
        val saved = original.withBookmark("Trips/2026", saved = true)
            .withBookmark("VR", saved = true)
            .withBookmark("Trips/2026", saved = true)
        assertEquals(listOf("Trips/2026", "VR"), saved.bookmarks)
        assertEquals(listOf("VR"), saved.withBookmark("Trips/2026", saved = false).bookmarks)
        assertEquals(emptyList<String>(), original.bookmarks)
    }

    @Test fun oldSingleConnectionMigrates() {
        val old = SmbConnection("nas", "video", "me", "pw")
        assertEquals(listOf(SmbProfile(SmbProfile.MIGRATED_ID, conn = old)), SmbProfile.migrate(old))
        assertEquals(emptyList<SmbProfile>(), SmbProfile.migrate(SmbConnection()))
        assertEquals(emptyList<SmbProfile>(), SmbProfile.migrate(null))
    }

    @Test fun smbProfileLabelFallsBackToPath() {
        assertEquals("\\\\nas\\video", SmbProfile("a", conn = SmbConnection("nas", "video")).label)
        assertEquals("客廳", SmbProfile("a", "客廳", SmbConnection("nas", "video")).label)
    }
}
