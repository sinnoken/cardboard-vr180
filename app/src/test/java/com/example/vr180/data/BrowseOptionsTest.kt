package com.example.vr180.data

import com.example.vr180.data.ProfileStore.BrowseOptions
import com.example.vr180.data.ProfileStore.BrowseSort
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** browse.json 是已部署的資料格式（docs/data-formats.md）。 */
class BrowseOptionsTest {
    @Test fun roundTrips() {
        val o = BrowseOptions(BrowseSort.DATE, descending = false, hideSmall = false, flatFolders = listOf("a", "b"))
        assertEquals(o, BrowseOptions.fromJson(JSONObject(o.toJson().toString())))
    }

    @Test fun missingFieldsUseDefaults() {
        assertEquals(BrowseOptions(), BrowseOptions.fromJson(JSONObject()))
        assertTrue(BrowseOptions.fromJson(JSONObject().put("sort", "SIZE")).descending)
    }

    @Test fun oldGlobalSubfolderFlagIsIgnored() {
        val o = BrowseOptions.fromJson(JSONObject().put("includeSubfolders", true))
        assertEquals(emptyList<String>(), o.flatFolders)
    }

    @Test fun subfoldersAreRememberedPerFolder() {
        val o = BrowseOptions().withFlat("smb://nas/v/A", true)
        assertTrue(o.isFlat("smb://nas/v/A"))
        assertFalse(o.isFlat("smb://nas/v/B"))
        assertFalse(o.withFlat("smb://nas/v/A", false).isFlat("smb://nas/v/A"))
    }

    @Test fun mostRecentFirstAndCapped() {
        var o = BrowseOptions()
        for (i in 0..BrowseOptions.MAX_FLAT_FOLDERS) o = o.withFlat("f$i", true)
        o = o.withFlat("f5", true)
        assertEquals("f5", o.flatFolders.first())
        assertEquals(BrowseOptions.MAX_FLAT_FOLDERS, o.flatFolders.size)
        assertEquals(1, o.flatFolders.count { it == "f5" })
    }
}
