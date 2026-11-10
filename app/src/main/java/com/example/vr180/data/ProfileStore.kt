package com.example.vr180.data

import android.content.Context
import com.example.vr180.model.DeviceCalib
import com.example.vr180.model.LensProfile
import com.example.vr180.model.SceneCalib
import com.example.vr180.model.SmbConnection
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 所有設定都以 JSON 存在 App 私有目錄（filesDir/vr180）。
 * 格式刻意保持簡單，之後 PWA 版可以讀同一份 JSON。
 * 注意：SMB 密碼以明文存於 App 私有目錄（其他 App 讀不到，但已 root 的手機可以）。
 */
class ProfileStore(context: Context) {
    private val dir = File(context.filesDir, "vr180").apply { mkdirs() }
    private val sceneDir = File(dir, "scenes").apply { mkdirs() }

    @Synchronized fun loadDevice(): DeviceCalib =
        read("device.json")?.let(DeviceCalib::fromJson) ?: DeviceCalib()

    @Synchronized fun saveDevice(d: DeviceCalib) = write("device.json", d.toJson())

    @Synchronized fun loadConnection(): SmbConnection =
        read("connection.json")?.let(SmbConnection::fromJson) ?: SmbConnection()

    @Synchronized fun saveConnection(c: SmbConnection) = write("connection.json", c.toJson())

    /** 使用者授權過的本機資料夾（SAF tree URI）。 */
    data class LocalFolder(val uri: String, val name: String)

    @Synchronized fun loadFolders(): List<LocalFolder> =
        read("folders.json")?.optJSONArray("folders")?.let { arr ->
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                LocalFolder(o.optString("uri"), o.optString("name"))
            }
        }.orEmpty()

    @Synchronized fun saveFolders(list: List<LocalFolder>) {
        val arr = JSONArray().apply { list.forEach { put(JSONObject().put("uri", it.uri).put("name", it.name)) } }
        write("folders.json", JSONObject().put("folders", arr))
    }

    data class Lenses(val profiles: List<LensProfile>, val activeId: String) {
        val active: LensProfile get() = profiles.firstOrNull { it.id == activeId } ?: profiles.first()
    }

    @Synchronized fun loadLenses(): Lenses {
        val o = read("lenses.json")
        val saved = o?.optJSONArray("profiles")?.let { arr ->
            (0 until arr.length()).map { LensProfile.fromJson(arr.getJSONObject(it)) }
        }.orEmpty().filter { !it.isPreset }
        // 預設檔一律以程式內的數值為準，排在最前面
        val list = LensProfile.presets() + saved
        val active = o?.optString("activeId").orEmpty()
            .takeIf { id -> list.any { it.id == id } } ?: LensProfile.CARDBOARD_DEFAULT_ID
        return Lenses(list, active)
    }

    @Synchronized fun activate(id: String) = saveLenses(loadLenses().copy(activeId = id))

    @Synchronized fun saveLenses(l: Lenses) {
        val arr = JSONArray().apply { l.profiles.filter { !it.isPreset }.forEach { put(it.toJson()) } }
        write("lenses.json", JSONObject().put("activeId", l.activeId).put("profiles", arr))
    }

    /** 更新（或新增）一個設定檔並設為使用中。預設檔不可修改，呼叫端需先另存成新 id。 */
    @Synchronized fun upsertActive(p: LensProfile) {
        require(!p.isPreset) { "預設檔不可修改" }
        val cur = loadLenses()
        val list = if (cur.profiles.any { it.id == p.id })
            cur.profiles.map { if (it.id == p.id) p else it } else cur.profiles + p
        saveLenses(Lenses(list, p.id))
    }

    @Synchronized fun loadScene(key: String): SceneCalib? =
        readFile(File(sceneDir, hash(key) + ".json"))?.let(SceneCalib::fromJson)

    @Synchronized fun saveScene(key: String, s: SceneCalib) =
        File(sceneDir, hash(key) + ".json").writeText(s.toJson().toString(2))

    private fun read(name: String) = readFile(File(dir, name))
    private fun readFile(f: File): JSONObject? =
        if (f.exists()) runCatching { JSONObject(f.readText()) }.getOrNull() else null

    private fun write(name: String, o: JSONObject) {
        // 先寫暫存檔再改名，避免寫到一半斷電把設定弄壞
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(o.toString(2))
        tmp.renameTo(File(dir, name))
    }

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
