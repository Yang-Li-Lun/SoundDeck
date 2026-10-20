package dev.sounddeck.protocol

/** IDs from the pinned ProtocolV2T1.hpp EqPresetId. Reserved values deliberately have no translation. */
object EqPresetNames {
    private val names = mapOf(
        0x00 to "關閉", 0x01 to "搖滾", 0x02 to "流行", 0x03 to "爵士", 0x04 to "舞曲",
        0x05 to "電子舞曲", 0x06 to "節奏藍調／嘻哈", 0x07 to "原音",
        0x10 to "明亮", 0x11 to "振奮", 0x12 to "柔和", 0x13 to "放鬆", 0x14 to "人聲",
        0x15 to "高音增強", 0x16 to "低音增強", 0x17 to "語音",
        0x20 to "遊戲", 0x21 to "FPS 1", 0x22 to "FPS 2", 0x23 to "FPS 3",
        0x30 to "厚重", 0x31 to "清晰", 0x32 to "強勁", 0x33 to "輕柔",
        0xa0 to "手動", 0xa1 to "自訂 1", 0xa2 to "自訂 2", 0xa3 to "自訂 3", 0xa4 to "自訂 4", 0xa5 to "自訂 5",
    )
    fun label(id: Int, reportedName: String = ""): String = names[id] ?: run {
        val code = "0x" + id.toString(16).uppercase().padStart(2, '0')
        val clean = reportedName.filterNot { it.isISOControl() }.trim()
        if (clean.isEmpty()) "未知預設 ($code)" else "$clean ($code)"
    }
}
