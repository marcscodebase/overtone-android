package com.musicplayer.core.database

import com.musicplayer.core.model.DownloadOwnerType
import com.musicplayer.core.model.DownloadQualityMode
import com.musicplayer.core.model.DownloadStatus
import com.musicplayer.core.model.SyncActionType
import com.musicplayer.core.model.TranscodeSource
import org.junit.Assert.assertEquals
import org.junit.Test

class DatabaseConvertersTest {

    private val converters = DatabaseConverters()

    @Test
    fun roundTripsAllDownloadStatuses() {
        DownloadStatus.entries.forEach { status ->
            assertEquals(status, converters.toDownloadStatus(converters.fromDownloadStatus(status)))
        }
    }

    @Test
    fun roundTripsAllDownloadQualityModes() {
        DownloadQualityMode.entries.forEach { mode ->
            assertEquals(mode, converters.toDownloadQualityMode(converters.fromDownloadQualityMode(mode)))
        }
    }

    @Test
    fun roundTripsAllTranscodeSources() {
        TranscodeSource.entries.forEach { source ->
            assertEquals(source, converters.toTranscodeSource(converters.fromTranscodeSource(source)))
        }
    }

    @Test
    fun roundTripsAllDownloadOwnerTypes() {
        DownloadOwnerType.entries.forEach { ownerType ->
            assertEquals(
                ownerType,
                converters.toDownloadOwnerType(converters.fromDownloadOwnerType(ownerType)),
            )
        }
    }

    @Test
    fun roundTripsAllSyncActionTypes() {
        SyncActionType.entries.forEach { actionType ->
            assertEquals(
                actionType,
                converters.toSyncActionType(converters.fromSyncActionType(actionType)),
            )
        }
    }
}
