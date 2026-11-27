package com.musicplayer.core.model

enum class CellularBitrateCap(val kbps: Int?) {
    ORIGINAL(null),
    KBPS_320(320),
    KBPS_256(256),
    KBPS_192(192),
    KBPS_128(128),
    ;

    companion object {
        fun fromNameOrDefault(name: String?): CellularBitrateCap =
            entries.firstOrNull { it.name == name } ?: ORIGINAL
    }
}

enum class ReplayGainMode {
    OFF,
    TRACK,
    ALBUM,
    ;

    companion object {
        fun fromNameOrDefault(name: String?): ReplayGainMode =
            entries.firstOrNull { it.name == name } ?: OFF
    }
}

enum class StreamQualitySelection(val explicitBitrateKbps: Int? = null) {
    AUTO,
    LOCAL,
    HIGH,
    MP3_320(320),
    MP3_256(256),
    MP3_192(192),
    MP3_128(128),
}

data class UserPreferences(
    val offlineModeEnabled: Boolean = false,
    val cellularBitrateCap: CellularBitrateCap = CellularBitrateCap.ORIGINAL,
    val lowQualityBitrate: Mp3Bitrate = Mp3Bitrate.KBPS_128,
    val autoMatchOutputQuality: Boolean = false,
    val bitPerfectUsbAudio: Boolean = false,
    val hiResBluetoothDevices: Set<String> = emptySet(),
    val defaultDownloadQuality: DownloadQuality = DownloadQuality(),
    val wifiOnlyDownloads: Boolean = true,
    val askDownloadQualityEachTime: Boolean = false,
    val replayGainMode: ReplayGainMode = ReplayGainMode.OFF,
    val replayGainPreampDb: Float = 0f,
    val selectedMusicLibraryIds: Set<String> = emptySet(),
    val wireGuardMode: WireGuardMode = WireGuardMode.OFF,
    val daylistRefreshSlotKey: String = "",
    val daylistRefreshNonce: Long = 0L,
)
