package com.musicplayer.feature.player

import com.musicplayer.core.model.StreamQualitySelection

data class QualityMenuOption(
    val selection: StreamQualitySelection,
    val title: String,
    val subtitle: String,
    val isSelected: Boolean,
)

data class QualityMenuModel(
    val autoOption: QualityMenuOption?,
    val options: List<QualityMenuOption>,
)

object QualitySwitcherMenuBuilder {

    fun build(
        currentSelection: StreamQualitySelection,
        currentActiveBadgeLabel: String,
        isTrackDownloaded: Boolean,
        isOffline: Boolean,
        highQualitySubtitle: String,
        localQualitySubtitle: String,
    ): QualityMenuModel {
        val autoOption = if (!isOffline) {
            QualityMenuOption(
                selection = StreamQualitySelection.AUTO,
                title = "Auto",
                subtitle = if (currentSelection == StreamQualitySelection.AUTO) {
                    "Active: $currentActiveBadgeLabel"
                } else {
                    "Follows Settings & output device"
                },
                isSelected = currentSelection == StreamQualitySelection.AUTO,
            )
        } else {
            null
        }

        val options = buildList {
            if (isTrackDownloaded) {
                add(
                    QualityMenuOption(
                        selection = StreamQualitySelection.LOCAL,
                        title = "Downloaded",
                        subtitle = localQualitySubtitle,
                        isSelected = isOffline || currentSelection == StreamQualitySelection.LOCAL,
                    ),
                )
            }
            if (!isOffline) {
                add(
                    QualityMenuOption(
                        selection = StreamQualitySelection.HIGH,
                        title = "Original (Lossless)",
                        subtitle = highQualitySubtitle,
                        isSelected = currentSelection == StreamQualitySelection.HIGH,
                    ),
                )
                add(
                    QualityMenuOption(
                        selection = StreamQualitySelection.MP3_320,
                        title = "320 kbps",
                        subtitle = "MP3",
                        isSelected = currentSelection == StreamQualitySelection.MP3_320,
                    ),
                )
                add(
                    QualityMenuOption(
                        selection = StreamQualitySelection.MP3_256,
                        title = "256 kbps",
                        subtitle = "MP3",
                        isSelected = currentSelection == StreamQualitySelection.MP3_256,
                    ),
                )
                add(
                    QualityMenuOption(
                        selection = StreamQualitySelection.MP3_192,
                        title = "192 kbps",
                        subtitle = "MP3",
                        isSelected = currentSelection == StreamQualitySelection.MP3_192,
                    ),
                )
                add(
                    QualityMenuOption(
                        selection = StreamQualitySelection.MP3_128,
                        title = "128 kbps",
                        subtitle = "MP3",
                        isSelected = currentSelection == StreamQualitySelection.MP3_128,
                    ),
                )
            }
        }

        return QualityMenuModel(
            autoOption = autoOption,
            options = options,
        )
    }
}
