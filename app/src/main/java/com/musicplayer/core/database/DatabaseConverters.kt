package com.musicplayer.core.database

import androidx.room.TypeConverter
import com.musicplayer.core.model.DownloadOwnerType
import com.musicplayer.core.model.DownloadQualityMode
import com.musicplayer.core.model.DownloadStatus
import com.musicplayer.core.model.SyncActionType
import com.musicplayer.core.model.TranscodeSource

class DatabaseConverters {

    @TypeConverter
    fun fromDownloadStatus(value: DownloadStatus): String = value.name

    @TypeConverter
    fun toDownloadStatus(value: String): DownloadStatus = DownloadStatus.valueOf(value)

    @TypeConverter
    fun fromDownloadQualityMode(value: DownloadQualityMode): String = value.name

    @TypeConverter
    fun toDownloadQualityMode(value: String): DownloadQualityMode = DownloadQualityMode.valueOf(value)

    @TypeConverter
    fun fromTranscodeSource(value: TranscodeSource): String = value.name

    @TypeConverter
    fun toTranscodeSource(value: String): TranscodeSource = TranscodeSource.valueOf(value)

    @TypeConverter
    fun fromDownloadOwnerType(value: DownloadOwnerType): String = value.name

    @TypeConverter
    fun toDownloadOwnerType(value: String): DownloadOwnerType = DownloadOwnerType.valueOf(value)

    @TypeConverter
    fun fromSyncActionType(value: SyncActionType): String = value.name

    @TypeConverter
    fun toSyncActionType(value: String): SyncActionType = SyncActionType.valueOf(value)
}
