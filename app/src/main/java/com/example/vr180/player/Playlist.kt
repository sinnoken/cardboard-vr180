package com.example.vr180.player

/**
 * 清單畫面交給播放器的影片清單（照畫面上的排序與篩選），讓播放器切到上一支／下一支。
 * 只放在記憶體：清單可能有上百支，放進 Intent 有大小上限；程序被系統回收後清單就沒了，
 * 播放器仍能用 Intent 裡的資料播目前這支，只是不能切換。
 */
object Playlist {
    data class Item(
        val uris: List<String>,     // 由高到低排序的版本
        val labels: List<String>,
        val sidecar: String,        // SidecarRef.encode()
        val title: String,
    )

    @Volatile var items: List<Item> = emptyList()
}
