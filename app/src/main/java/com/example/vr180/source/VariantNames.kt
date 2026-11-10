package com.example.vr180.source

/** 檔名結尾的解析度標記，例如 trip_8K.mp4、trip_5.7K.mp4 會被歸成同一支影片的兩個版本。 */
private val TAG = Regex("""^(.*?)[_\-. ](8k|6k|5\.7k|4k)$""", RegexOption.IGNORE_CASE)

/** 沒有標記的檔案的版本標籤。 */
const val ORIGINAL_LABEL = "原檔"

// 沒有標記的「原檔」多半是母片、畫質最高，排第一；解不了會自動降版
private val RANK = mapOf(ORIGINAL_LABEL to 5, "8k" to 4, "6k" to 3, "5.7k" to 2, "4k" to 1)

/** 檔名（不含副檔名）→ (基本名稱, 版本標籤)。標籤是大寫的解析度標記，沒有標記時是 [ORIGINAL_LABEL]。 */
fun parseVariant(stem: String): Pair<String, String> {
    val m = TAG.matchEntire(stem) ?: return stem to ORIGINAL_LABEL
    return m.groupValues[1] to m.groupValues[2].uppercase()
}

/** 版本標籤的播放優先順序，數字越大越先播。 */
fun variantRank(label: String): Int = RANK[label.lowercase()] ?: 0
