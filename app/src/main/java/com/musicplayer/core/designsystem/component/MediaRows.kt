package com.musicplayer.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueuePlayNext
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musicplayer.R
import com.musicplayer.core.common.Formatters
import com.musicplayer.core.model.Album
import com.musicplayer.core.model.Artist
import com.musicplayer.core.model.Playlist
import com.musicplayer.core.model.Track

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackRow(
    track: Track,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    enableSwipeToQueue: Boolean = true,
    showArtwork: Boolean = true,
    trackNumberOverride: Int? = null,
    onPlayNext: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    onToggleFavorite: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onToggleDownload: (() -> Unit)? = null,
    onGoToAlbum: (() -> Unit)? = null,
    onGoToArtist: (() -> Unit)? = null,
    extraMenuActionLabel: String? = null,
    onExtraMenuAction: (() -> Unit)? = null,
) {
    if (enabled && enableSwipeToQueue && onAddToQueue != null) {
        val haptic = LocalHapticFeedback.current
        val dismissState = rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
                if (value == SwipeToDismissBoxValue.StartToEnd) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onAddToQueue()
                }
                // Always snap the row back to its resting position like Spotify
                false
            },
        )

        SwipeToDismissBox(
            state = dismissState,
            enableDismissFromStartToEnd = true,
            enableDismissFromEndToStart = false,
            modifier = modifier.fillMaxWidth(),
            backgroundContent = {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.PlaylistPlay,
                        contentDescription = stringResource(R.string.queue_add_to_queue),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = stringResource(R.string.queue_add_to_queue),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            },
        ) {
            TrackRowContent(
                track = track,
                isPlaying = isPlaying,
                onClick = onClick,
                enabled = enabled,
                showArtwork = showArtwork,
                trackNumberOverride = trackNumberOverride,
                onPlayNext = onPlayNext,
                onAddToQueue = onAddToQueue,
                onToggleFavorite = onToggleFavorite,
                onAddToPlaylist = onAddToPlaylist,
                onToggleDownload = onToggleDownload,
                onGoToAlbum = onGoToAlbum,
                onGoToArtist = onGoToArtist,
                extraMenuActionLabel = extraMenuActionLabel,
                onExtraMenuAction = onExtraMenuAction,
            )
        }
    } else {
        TrackRowContent(
            track = track,
            isPlaying = isPlaying,
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            showArtwork = showArtwork,
            trackNumberOverride = trackNumberOverride,
            onPlayNext = if (enabled) onPlayNext else null,
            onAddToQueue = if (enabled) onAddToQueue else null,
            onToggleFavorite = onToggleFavorite,
            onAddToPlaylist = onAddToPlaylist,
            onToggleDownload = onToggleDownload,
            onGoToAlbum = onGoToAlbum,
            onGoToArtist = onGoToArtist,
            extraMenuActionLabel = extraMenuActionLabel,
            onExtraMenuAction = onExtraMenuAction,
        )
    }
}

@Composable
private fun TrackRowContent(
    track: Track,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showArtwork: Boolean = true,
    trackNumberOverride: Int? = null,
    onPlayNext: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    onToggleFavorite: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onToggleDownload: (() -> Unit)? = null,
    onGoToAlbum: (() -> Unit)? = null,
    onGoToArtist: (() -> Unit)? = null,
    extraMenuActionLabel: String? = null,
    onExtraMenuAction: (() -> Unit)? = null,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val hasAlbumLink = onGoToAlbum != null && !track.albumId.isNullOrBlank()
    val hasArtistLink = onGoToArtist != null && track.resolvedArtists.isNotEmpty()
    val hasMenu = onPlayNext != null || onAddToQueue != null || onToggleFavorite != null ||
        onAddToPlaylist != null || onToggleDownload != null ||
        hasAlbumLink || hasArtistLink ||
        (extraMenuActionLabel != null && onExtraMenuAction != null)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.42f)
            .padding(start = 16.dp, end = if (hasMenu) 4.dp else 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showArtwork) {
            ArtworkCard(
                imageUrl = track.artworkUrl,
                contentDescription = track.albumName,
                shape = MaterialTheme.shapes.extraSmall,
                fallbackIconSize = 22.dp,
                modifier = Modifier.size(48.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
        } else {
            val numberText = (trackNumberOverride ?: track.trackNumber)?.toString() ?: "•"
            Text(
                text = numberText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isPlaying) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center,
                modifier = Modifier.width(32.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (isPlaying) {
                    Icon(
                        imageVector = Icons.Rounded.Equalizer,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isPlaying) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (track.isDownloaded) {
                    Icon(
                        imageVector = Icons.Rounded.DownloadDone,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Text(
                    text = track.artistName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = Formatters.formatDuration(track.durationMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (hasMenu) {
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = stringResource(R.string.track_more_options),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    if (onPlayNext != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.queue_play_next)) },
                            leadingIcon = {
                                Icon(Icons.Rounded.QueuePlayNext, contentDescription = null)
                            },
                            onClick = {
                                menuExpanded = false
                                onPlayNext()
                            },
                        )
                    }
                    if (onAddToQueue != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.queue_add_to_queue)) },
                            leadingIcon = {
                                Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, contentDescription = null)
                            },
                            onClick = {
                                menuExpanded = false
                                onAddToQueue()
                            },
                        )
                    }
                    if (onToggleFavorite != null) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (track.isFavorite) {
                                            R.string.favorite_remove
                                        } else {
                                            R.string.favorite_add
                                        },
                                    ),
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (track.isFavorite) {
                                        Icons.Rounded.Favorite
                                    } else {
                                        Icons.Rounded.FavoriteBorder
                                    },
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onToggleFavorite()
                            },
                        )
                    }
                    if (onAddToPlaylist != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.playlist_add_to)) },
                            leadingIcon = {
                                Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null)
                            },
                            onClick = {
                                menuExpanded = false
                                onAddToPlaylist()
                            },
                        )
                    }
                    if (onToggleDownload != null) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (track.isDownloaded) {
                                            R.string.download_remove
                                        } else {
                                            R.string.action_download
                                        },
                                    ),
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (track.isDownloaded) {
                                        Icons.Rounded.Delete
                                    } else {
                                        Icons.Rounded.Download
                                    },
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onToggleDownload()
                            },
                        )
                    }
                    if (hasAlbumLink) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.track_go_to_album)) },
                            leadingIcon = {
                                Icon(Icons.Rounded.Album, contentDescription = null)
                            },
                            onClick = {
                                menuExpanded = false
                                onGoToAlbum()
                            },
                        )
                    }
                    if (hasArtistLink) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.track_go_to_artist)) },
                            leadingIcon = {
                                Icon(Icons.Rounded.Person, contentDescription = null)
                            },
                            onClick = {
                                menuExpanded = false
                                onGoToArtist()
                            },
                        )
                    }
                    if (extraMenuActionLabel != null && onExtraMenuAction != null) {
                        DropdownMenuItem(
                            text = { Text(extraMenuActionLabel) },
                            onClick = {
                                menuExpanded = false
                                onExtraMenuAction()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AlbumCarouselCard(
    album: Album,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(148.dp)
            .clickable(onClick = onClick),
    ) {
        ArtworkCard(
            imageUrl = album.artworkUrl,
            contentDescription = album.title,
            fallbackIcon = Icons.Rounded.Album,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = album.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = album.artistName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun PlaylistCarouselCard(
    playlist: Playlist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(148.dp)
            .clickable(onClick = onClick),
    ) {
        ArtworkCard(
            imageUrl = playlist.artworkUrl,
            contentDescription = playlist.name,
            fallbackIcon = Icons.AutoMirrored.Rounded.QueueMusic,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = playlist.name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val subtitle = listOfNotNull(
            playlist.folderName,
            "${playlist.trackCount} songs",
        ).joinToString(" • ")
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun ArtistCircleCard(
    artist: Artist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(120.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ArtworkCard(
            imageUrl = artist.artworkUrl,
            contentDescription = artist.name,
            shape = CircleShape,
            fallbackIcon = Icons.Rounded.Person,
            modifier = Modifier.size(112.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = artist.name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun CollectionDetailHeader(
    title: String,
    subtitle: String,
    metadata: String?,
    artworkUrl: String?,
    isCircularArtwork: Boolean,
    canPlay: Boolean,
    onPlayClick: () -> Unit,
    onShuffleClick: () -> Unit,
    modifier: Modifier = Modifier,
    onSubtitleClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ArtworkCard(
            imageUrl = artworkUrl,
            contentDescription = title,
            shape = if (isCircularArtwork) CircleShape else MaterialTheme.shapes.medium,
            fallbackIcon = if (isCircularArtwork) Icons.Rounded.Person else Icons.Rounded.Album,
            fallbackIconSize = 64.dp,
            modifier = Modifier.size(220.dp),
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.titleMedium,
            color = if (onSubtitleClick != null) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(CircleShape)
                .let { mod ->
                    if (onSubtitleClick != null) {
                        mod
                            .clickable(onClick = onSubtitleClick)
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    } else {
                        mod
                    }
                },
        )
        if (!metadata.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = metadata,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onPlayClick,
                enabled = canPlay,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.action_play))
            }
            FilledTonalButton(
                onClick = onShuffleClick,
                enabled = canPlay,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
            ) {
                Icon(Icons.Rounded.Shuffle, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.action_shuffle))
            }
        }
    }
}
