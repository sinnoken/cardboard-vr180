package com.example.vr180.data

import android.content.Context
import com.example.vr180.input.ControllerChoice
import com.example.vr180.player.worthResuming
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

    @Synchronized fun loadControllerChoice(): ControllerChoice =
        ControllerChoice.parse(read("controller.json")?.optString("choice"))

    @Synchronized fun saveControllerChoice(c: ControllerChoice) =
        write("controller.json", JSONObject().put("choice", c.name))

    /** defaultDescending：切換到這種排序時的預設方向（名稱 A→Z；日期、大小新的／大的在前）。 */
    enum class BrowseSort(val defaultDescending: Boolean) { NAME(false), DATE(true), SIZE(true) }

    /**
     * 影片清單的顯示方式。排序與隱藏小檔由 NAS 與本機資料夾共用；
     * 「包含子資料夾」每個資料夾各自記住：flatFolders 是開啟的資料夾鍵（FileSource.folderKey），最近開的在前。
     */
    data class BrowseOptions(
        val sort: BrowseSort = BrowseSort.NAME,
        val descending: Boolean = sort.defaultDescending,
        val hideSmall: Boolean = true,
        val flatFolders: List<String> = emptyList(),
    ) {
        fun isFlat(folder: String) = folder in flatFolders

        fun withFlat(folder: String, on: Boolean): BrowseOptions {
            val rest = flatFolders.filter { it != folder }
            return copy(flatFolders = (if (on) listOf(folder) + rest else rest).take(MAX_FLAT_FOLDERS))
        }

        fun toJson(): JSONObject = JSONObject()
            .put("sort", sort.name).put("descending", descending).put("hideSmall", hideSmall)
            .put("flatFolders", JSONArray(flatFolders))

        companion object {
            /** 記住幾個「包含子資料夾」開啟的資料夾。 */
            const val MAX_FLAT_FOLDERS = 200

            /** 舊版的全域 includeSubfolders 欄位不再讀取：升級後每個資料夾都從關閉開始。 */
            fun fromJson(o: JSONObject): BrowseOptions {
                val d = BrowseOptions()
                val sort = BrowseSort.entries.firstOrNull { it.name == o.optString("sort") } ?: d.sort
                val flat = o.optJSONArray("flatFolders")
                return BrowseOptions(
                    sort = sort,
                    descending = o.optBoolean("descending", sort.defaultDescending),
                    hideSmall = o.optBoolean("hideSmall", d.hideSmall),
                    flatFolders = if (flat == null) emptyList()
                        else (0 until flat.length()).mapNotNull { flat.optString(it).ifEmpty { null } },
                )
            }
        }
    }

    @Synchronized fun loadBrowseOptions(): BrowseOptions =
        read("browse.json")?.let(BrowseOptions::fromJson) ?: BrowseOptions()

    @Synchronized fun saveBrowseOptions(b: BrowseOptions) = write("browse.json", b.toJson())

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
        require(!p.isPreset) { "Preset profiles are read-only" }
        val cur = loadLenses()
        val list = if (cur.profiles.any { it.id == p.id })
            cur.profiles.map { if (it.id == p.id) p else it } else cur.profiles + p
        saveLenses(Lenses(list, p.id))
    }

    /** 影片最後播放位置。key 跟場景校正相同（同一支影片的各版本共用），存的是 SHA-1。 */
    @Synchronized fun loadPosition(key: String): Long? {
        val h = hash(key)
        val arr = read("positions.json")?.optJSONArray("items") ?: return null
        return (0 until arr.length()).map { arr.optJSONObject(it) }
            .firstOrNull { it?.optString("key") == h }?.optLong("posMs")
    }

    /** 記住位置（最新的排最前面，只留 MAX_POSITIONS 筆）；不值得續播時刪掉這支的紀錄。 */
    @Synchronized fun savePosition(key: String, posMs: Long, durMs: Long) {
        val h = hash(key)
        val old = read("positions.json")?.optJSONArray("items")
        val others = (0 until (old?.length() ?: 0)).mapNotNull { old?.optJSONObject(it) }
            .filter { it.optString("key") != h }
        val keep = if (worthResuming(posMs, durMs)) {
            listOf(JSONObject().put("key", h).put("posMs", posMs).put("durMs", durMs)) + others
        } else others
        val arr = JSONArray().apply { keep.take(MAX_POSITIONS).forEach { put(it) } }
        write("positions.json", JSONObject().put("items", arr))
    }

    @Synchronized fun loadScene(key: String): SceneCalib? =
        readFile(File(sceneDir, hash(key) + ".json"))?.let(SceneCalib::fromJson)

    @Synchronized fun saveScene(key: String, s: SceneCalib) =
        writeFile(File(sceneDir, hash(key) + ".json"), s.toJson())

    private fun read(name: String) = readFile(File(dir, name))
    private fun readFile(f: File): JSONObject? =
        if (f.exists()) runCatching { JSONObject(f.readText()) }.getOrNull() else null

    private fun write(name: String, o: JSONObject) = writeFile(File(dir, name), o)

    private fun writeFile(f: File, o: JSONObject) {
        // 先寫暫存檔再改名，避免寫到一半斷電把設定弄壞
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(o.toString(2))
        tmp.renameTo(f)
    }

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_POSITIONS = 200
    }
}
