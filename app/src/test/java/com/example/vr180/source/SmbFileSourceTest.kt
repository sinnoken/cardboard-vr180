package com.example.vr180.source

import com.example.vr180.model.SmbProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class SmbFileSourceTest {
    @Test fun normalSourceStartsAtShareRoot() {
        assertEquals(listOf("" to ""), SmbFileSource(SmbProfile("nas")).initialStack())
    }

    @Test fun bookmarkStartsAtExactPathAndKeepsParentsForUpNavigation() {
        val path = "Trips 2026/Day #1/100%"
        val stack = SmbFileSource(SmbProfile("nas"), path).initialStack()
        assertEquals(
            listOf("" to "", "Trips 2026" to "Trips 2026", "Day #1" to "Trips 2026/Day #1", "100%" to path),
            stack,
        )
    }
}
