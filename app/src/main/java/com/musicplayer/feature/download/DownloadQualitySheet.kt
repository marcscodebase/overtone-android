package com.musicplayer.feature.download

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.musicplayer.R
import com.musicplayer.core.model.DownloadQuality
import com.musicplayer.core.model.DownloadQualityMode
import com.musicplayer.core.model.Mp3Bitrate

val downloadQualityPresets: List<DownloadQuality> = listOf(
    DownloadQuality(mode = DownloadQualityMode.LOSSLESS, mp3Bitrate = Mp3Bitrate.KBPS_320),
    DownloadQuality(mode = DownloadQualityMode.MP3, mp3Bitrate = Mp3Bitrate.KBPS_320),
    DownloadQuality(mode = DownloadQualityMode.MP3, mp3Bitrate = Mp3Bitrate.KBPS_256),
    DownloadQuality(mode = DownloadQualityMode.MP3, mp3Bitrate = Mp3Bitrate.KBPS_192),
    DownloadQuality(mode = DownloadQualityMode.MP3, mp3Bitrate = Mp3Bitrate.KBPS_128),
)

@Composable
fun formatDownloadQualityLabel(quality: DownloadQuality): String = when (quality.mode) {
    DownloadQualityMode.LOSSLESS -> stringResource(R.string.download_quality_lossless)
    DownloadQualityMode.MP3 -> stringResource(R.string.download_quality_mp3_kbps, quality.mp3Bitrate.kbps)
}

@Composable
fun formatDownloadQualitySubtitle(quality: DownloadQuality): String = when (quality.mode) {
    DownloadQualityMode.LOSSLESS -> stringResource(R.string.download_quality_lossless_desc)
    DownloadQualityMode.MP3 -> stringResource(R.string.download_quality_mp3_desc)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadQualitySheet(
    title: String,
    defaultQuality: DownloadQuality,
    onDismiss: () -> Unit,
    onConfirm: (DownloadQuality) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selected by remember(defaultQuality) { mutableStateOf(defaultQuality) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.download_choose_quality),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))

            downloadQualityPresets.forEach { preset ->
                val isSelected = selected.mode == preset.mode &&
                    (preset.mode == DownloadQualityMode.LOSSLESS || selected.mp3Bitrate == preset.mp3Bitrate)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selected = preset }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = { selected = preset },
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = formatDownloadQualityLabel(preset),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = formatDownloadQualitySubtitle(preset),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = { onConfirm(selected) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text(stringResource(R.string.action_download))
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
