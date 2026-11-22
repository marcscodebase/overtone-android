package com.musicplayer.core.model

enum class DownloadQualityMode {
    LOSSLESS,
    MP3,
}

enum class Mp3Bitrate(val kbps: Int) {
    KBPS_128(128),
    KBPS_192(192),
    KBPS_256(256),
    KBPS_320(320),
    ;

    companion object {
        fun fromKbps(kbps: Int?): Mp3Bitrate =
            entries.firstOrNull { it.kbps == kbps } ?: KBPS_320
    }
}

data class DownloadQuality(
    val mode: DownloadQualityMode = DownloadQualityMode.LOSSLESS,
    val mp3Bitrate: Mp3Bitrate = Mp3Bitrate.KBPS_320,
)

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    TRANSCODING,
    COMPLETED,
    FAILED,
}

enum class TranscodeSource {
    ORIGINAL,
    SERVER,
    ON_DEVICE,
}

enum class DownloadOwnerType {
    TRACK,
    ALBUM,
    PLAYLIST,
}
