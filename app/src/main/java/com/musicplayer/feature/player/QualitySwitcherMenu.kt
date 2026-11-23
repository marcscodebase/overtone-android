package com.musicplayer.feature.player

import com.musicplayer.core.model.StreamQualitySelection

data class QualityMenuOption(
    val selection: StreamQualitySelection,
    val title: String,
    val subtitle: String,
)

data class QualityMenuModel(
    val active: QualityMenuOption,
    val alternatives: List<QualityMenuOption>,
)

object QualitySwitcherMenuBuilder {

    fun build(
        isLocalPlayback: Boolean,
        transcodedBitrateKbps: Int?,
        isTrackDownloaded: Boolean,
        isOffline: Boolean,
        highQualitySubtitle: String,
        lowQualityBitrateKbps: Int,
        localQualitySubtitle: String,
    ): QualityMenuModel {
        val highOption = QualityMenuOption(
            selection = StreamQualitySelection.HIGH,
            title = "High Quality",
            subtitle = highQualitySubtitle,
        )
        val lowOption = QualityMenuOption(
            selection = StreamQualitySelection.LOW,
            title = "Low Quality",
            subtitle = "MP3 • $lowQualityBitrateKbps kbps",
        )
        val localOption = QualityMenuOption(
            selection = StreamQualitySelection.LOCAL,
            title = "Downloaded",
            subtitle = localQualitySubtitle,
        )

        val active: QualityMenuOption
        val alternatives = mutableListOf<QualityMenuOption>()

        when {
            isLocalPlayback -> {
                active = localOption
                if (!isOffline) {
                    alternatives += highOption
                    alternatives += lowOption
                }
            }

            transcodedBitrateKbps != null -> {
                active = QualityMenuOption(
                    selection = StreamQualitySelection.LOW,
                    title = "Low Quality",
                    subtitle = "MP3 • $transcodedBitrateKbps kbps",
                )
                if (!isOffline) {
                    alternatives += highOption
                }
                if (isTrackDownloaded) {
                    alternatives += localOption
                }
            }

            else -> {
                active = highOption
                if (!isOffline) {
                    alternatives += lowOption
                }
                if (isTrackDownloaded) {
                    alternatives += localOption
                }
            }
        }

        return QualityMenuModel(
            active = active,
            alternatives = alternatives,
        )
    }
}
