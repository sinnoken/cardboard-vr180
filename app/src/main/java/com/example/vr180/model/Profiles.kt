package com.example.vr180.model

import org.json.JSONObject
import java.util.UUID

/** 鏡片中心的垂直定位方式（對應 Cardboard 規格的 vertical_alignment）。 */
enum class VerticalAlignment { BOTTOM, CENTER, TOP }

/**
 * 眼鏡（鏡片）設定檔：屬於「眼鏡」的屬性，全域共用。
 * 距離單位一律為毫米；畸變係數沿用 Cardboard 定義（以 tan 角度為半徑單位）。
 */
data class LensProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val interLensMm: Float,        // 兩鏡片中心距離（軟體上的「眼距」）
    val screenToLensMm: Float,     // 螢幕到鏡片距離，決定畫面比例
    val lensHeightMm: Float,       // 鏡片中心到螢幕下緣（BOTTOM）或上緣（TOP）的距離
    val alignment: VerticalAlignment,
    val k1: Float,
    val k2: Float,
    val halfFovDeg: Float,         // 單眼可視半角，超出部分塗黑
) {
    val isPreset: Boolean get() = id.startsWith("preset-")

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name)
        .put("interLensMm", interLensMm.toDouble())
        .put("screenToLensMm", screenToLensMm.toDouble())
        .put("lensHeightMm", lensHeightMm.toDouble())
        .put("alignment", alignment.name)
        .put("k1", k1.toDouble()).put("k2", k2.toDouble())
        .put("halfFovDeg", halfFovDeg.toDouble())

    companion object {
        fun fromJson(o: JSONObject) = LensProfile(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name", "未命名"),
            interLensMm = o.optDouble("interLensMm", 63.0).toFloat(),
            screenToLensMm = o.optDouble("screenToLensMm", 40.0).toFloat(),
            lensHeightMm = o.optDouble("lensHeightMm", 31.0).toFloat(),
            alignment = runCatching { VerticalAlignment.valueOf(o.optString("alignment")) }
                .getOrDefault(VerticalAlignment.BOTTOM),
            k1 = o.optDouble("k1", 0.3).toFloat(),
            k2 = o.optDouble("k2", 0.2).toFloat(),
            halfFovDeg = o.optDouble("halfFovDeg", 50.0).toFloat(),
        )

        const val CARDBOARD_DEFAULT_ID = "preset-cardboard-v1"

        /** Cardboard 官方預設值（與 Cardboard SDK 未設定眼鏡時使用的參數相同）。 */
        fun cardboardDefault(): LensProfile = presets().first()

        /**
         * 預設檔僅為起點，請戴上眼鏡後在 VR 校正模式微調。
         * 預設檔使用固定 id 且永遠不會被修改；校正後會另存成新的設定檔。
         */
        fun presets(): List<LensProfile> = listOf(
            // Cardboard v1 官方參數（tray 35mm，扣掉約 4mm 手機下邊框）
            LensProfile(id = CARDBOARD_DEFAULT_ID, name = "Cardboard 預設值", interLensMm = 60f, screenToLensMm = 42f,
                lensHeightMm = 31f, alignment = VerticalAlignment.BOTTOM,
                k1 = 0.441f, k2 = 0.156f, halfFovDeg = 40f),
            LensProfile(id = "preset-cardboard-v2", name = "Cardboard v2（近似）", interLensMm = 63.9f, screenToLensMm = 39.3f,
                lensHeightMm = 31f, alignment = VerticalAlignment.BOTTOM,
                k1 = 0.34f, k2 = 0.55f, halfFovDeg = 50f),
            LensProfile(id = "preset-generic-center", name = "通用（螢幕置中）", interLensMm = 63f, screenToLensMm = 40f,
                lensHeightMm = 0f, alignment = VerticalAlignment.CENTER,
                k1 = 0.30f, k2 = 0.20f, halfFovDeg = 50f),
        )
    }
}

/** 手機本身的校正：螢幕實際尺寸、陀螺儀漂移、延遲預測。 */
data class DeviceCalib(
    val pxPerMm: Float = 0f,            // 0 = 尚未校正，改用系統回報的 DPI
    val yawDriftDegPerSec: Float = 0f,  // 靜置量測到的水平漂移速率
    val predictionMs: Float = 25f,      // 頭部方向往前預測的時間
) {
    fun toJson(): JSONObject = JSONObject()
        .put("pxPerMm", pxPerMm.toDouble())
        .put("yawDriftDegPerSec", yawDriftDegPerSec.toDouble())
        .put("predictionMs", predictionMs.toDouble())

    companion object {
        fun fromJson(o: JSONObject) = DeviceCalib(
            pxPerMm = o.optDouble("pxPerMm", 0.0).toFloat(),
            yawDriftDegPerSec = o.optDouble("yawDriftDegPerSec", 0.0).toFloat(),
            predictionMs = o.optDouble("predictionMs", 25.0).toFloat(),
        )
    }
}

/** 內容場景校正：屬於「影片」的屬性，每支影片各自儲存（並以 sidecar 存回 NAS）。 */
data class SceneCalib(
    val yawDeg: Float = 0f,           // 影片正前方
    val pitchDeg: Float = 0f,         // 拍攝時攝影機仰俯
    val rollDeg: Float = 0f,          // 地平線傾斜
    val eyeVerticalDeg: Float = 0f,   // 左右眼垂直落差（雙鏡頭沒對齊）
    val convergenceDeg: Float = 0f,   // 左右眼水平位移（深度舒適度）
    val swapEyes: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("version", 1)
        .put("yawDeg", yawDeg.toDouble()).put("pitchDeg", pitchDeg.toDouble())
        .put("rollDeg", rollDeg.toDouble())
        .put("eyeVerticalDeg", eyeVerticalDeg.toDouble())
        .put("convergenceDeg", convergenceDeg.toDouble())
        .put("swapEyes", swapEyes)

    companion object {
        fun fromJson(o: JSONObject) = SceneCalib(
            yawDeg = o.optDouble("yawDeg", 0.0).toFloat(),
            pitchDeg = o.optDouble("pitchDeg", 0.0).toFloat(),
            rollDeg = o.optDouble("rollDeg", 0.0).toFloat(),
            eyeVerticalDeg = o.optDouble("eyeVerticalDeg", 0.0).toFloat(),
            convergenceDeg = o.optDouble("convergenceDeg", 0.0).toFloat(),
            swapEyes = o.optBoolean("swapEyes", false),
        )
    }
}

data class SmbConnection(
    val host: String = "",
    val share: String = "",
    val user: String = "",
    val password: String = "",
    val domain: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("host", host).put("share", share)
        .put("user", user).put("password", password).put("domain", domain)

    companion object {
        fun fromJson(o: JSONObject) = SmbConnection(
            o.optString("host"), o.optString("share"), o.optString("user"),
            o.optString("password"), o.optString("domain"),
        )
    }
}
