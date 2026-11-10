package com.example.vr180.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VariantNamesTest {
    @Test fun parsesResolutionTags() {
        assertEquals("trip" to "8K", parseVariant("trip_8K"))
        assertEquals("trip" to "5.7K", parseVariant("trip-5.7k"))
        assertEquals("trip" to "4K", parseVariant("trip.4K"))
        assertEquals("my trip" to "6K", parseVariant("my trip 6K"))
    }

    @Test fun untaggedNamesAreOriginals() {
        assertEquals("trip" to ORIGINAL_LABEL, parseVariant("trip"))
        assertEquals("trip_8K_cut" to ORIGINAL_LABEL, parseVariant("trip_8K_cut"))   // 標記要在結尾
        assertEquals("trip8K" to ORIGINAL_LABEL, parseVariant("trip8K"))             // 要有分隔符
    }

    @Test fun originalPlaysFirstThenHighToLow() {
        val order = listOf("4K", "8K", ORIGINAL_LABEL, "5.7K", "6K").sortedByDescending(::variantRank)
        assertEquals(listOf(ORIGINAL_LABEL, "8K", "6K", "5.7K", "4K"), order)
        assertTrue(variantRank("12K") < variantRank("4K"))
    }
}
