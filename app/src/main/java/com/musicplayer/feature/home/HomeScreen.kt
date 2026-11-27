package com.musicplayer.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musicplayer.R
import com.musicplayer.core.designsystem.component.AlbumCardShimmer
import com.musicplayer.core.designsystem.component.AlbumCarouselCard
import com.musicplayer.core.designsystem.component.ArtworkCard
import com.musicplayer.core.designsystem.component.EmptyState
import com.musicplayer.core.designsystem.component.ErrorState
import com.musicplayer.core.designsystem.component.LocalLeftHandedMode
import com.musicplayer.core.designsystem.component.PlaylistCarouselCard
import com.musicplayer.core.designsystem.component.SectionHeader
import com.musicplayer.core.designsystem.component.TrackRow
import com.musicplayer.core.model.Album
import com.musicplayer.core.model.Track

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    state: HomeUiState,
    onRefresh: () -> Unit,
    onQuickAccessClick: (QuickAccessTile) -> Unit,
    onQuickAccessPlay: (QuickAccessTile) -> Unit,
    onSmartMixClick: (SmartMixPreview) -> Unit,
    onSmartMixPlay: (SmartMixPreview) -> Unit,
    onSpotlightOpen: (Album) -> Unit,
    onSpotlightPlay: (Album) -> Unit,
    onSpotlightNext: () -> Unit,
    onExploreTagClick: (ExploreTag) -> Unit,
    onShowAllRecentlyAdded: () -> Unit,
    onShowAllMoreFromArtists: () -> Unit,
    onShowAllRediscover: () -> Unit,
    onShowAllUnplayed: () -> Unit,
    onShowAllPlaylists: () -> Unit,
    onShowAllRecentlyPlayed: () -> Unit,
    onAlbumClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onTrackClick: (List<Track>, Int) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onToggleTrackDownload: (Track) -> Unit,
    onGoToArtist: (Track) -> Unit,
    modifier: Modifier = Modifier,
) {
    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        when {
            state.isEmpty && state.isRefreshing -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
                ) {
                    item(key = "shimmer_header") {
                        SectionHeader(title = stringResource(R.string.home_recently_added))
                    }
                    item(key = "shimmer_row") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(4) { AlbumCardShimmer() }
                        }
                    }
                }
            }

            state.isEmpty && state.errorMessage != null -> {
                ErrorState(
                    title = stringResource(R.string.home_sync_failed),
                    message = state.errorMessage,
                    retryLabel = stringResource(R.string.action_retry),
                    onRetry = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            state.isEmpty -> {
                EmptyState(
                    title = stringResource(R.string.home_empty_title),
                    subtitle = stringResource(R.string.home_empty_subtitle),
                    actionLabel = stringResource(R.string.action_refresh),
                    onAction = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp),
                ) {
                    // =========================================================================
                    // ZONE 1 — "Above the Fold": Fast everyday return (2x3 Quick Access Grid)
                    // =========================================================================
                    if (state.quickAccessTiles.isNotEmpty()) {
                        item(key = "zone1_quick_access_grid") {
                            QuickAccessGrid(
                                tiles = state.quickAccessTiles,
                                onTileClick = onQuickAccessClick,
                                onTilePlay = onQuickAccessPlay,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                    }

                    // =========================================================================
                    // ZONE 2 — "Made For You": Instant-play Smart Mixes (Jellyfin + Offline)
                    // =========================================================================
                    if (state.smartMixes.isNotEmpty()) {
                        item(key = "zone2_mixes_header") {
                            SectionHeader(title = stringResource(R.string.home_your_mixes))
                        }
                        item(key = "zone2_mixes_row") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                items(
                                    items = state.smartMixes,
                                    key = { "${it.type.routeKey}_${it.seedId.orEmpty()}" },
                                ) { mix ->
                                    val subtitleText = if (mix.type == SmartMixType.DAYLIST && state.daylist != null) {
                                        daylistPeriodLabel(state.daylist.slot.period)
                                    } else {
                                        mix.subtitle
                                    }
                                    SmartMixCard(
                                        mix = mix,
                                        subtitle = subtitleText,
                                        canPlay = !state.isOffline || mix.tracks.any { it.isDownloaded },
                                        onClick = { onSmartMixClick(mix) },
                                        onPlayClick = { onSmartMixPlay(mix) },
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }

                    // =========================================================================
                    // ZONE 3 — "Conscious Listening & Plattenregal": Spotlight, Genres & Shelves
                    // =========================================================================
                    state.spotlight?.let { spotlight ->
                        item(key = "zone3_spotlight_header") {
                            SectionHeader(title = stringResource(R.string.home_from_your_shelf))
                        }
                        item(key = "zone3_spotlight_card") {
                            AlbumSpotlightCard(
                                spotlight = spotlight,
                                showNextButton = state.hasMultipleSpotlights,
                                onOpenClick = { onSpotlightOpen(spotlight.album) },
                                onPlayClick = { onSpotlightPlay(spotlight.album) },
                                onNextClick = onSpotlightNext,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }

                    if (state.exploreTags.isNotEmpty()) {
                        item(key = "zone3_explore_tags_header") {
                            SectionHeader(title = stringResource(R.string.home_explore_by_mood))
                        }
                        item(key = "zone3_explore_tags_row") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                items(
                                    items = state.exploreTags,
                                    key = { it.id },
                                ) { tag ->
                                    AssistChip(
                                        onClick = { onExploreTagClick(tag) },
                                        label = { Text(tag.label) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = if (tag.kind == ExploreTagKind.GENRE) {
                                                    Icons.Rounded.GraphicEq
                                                } else {
                                                    Icons.Rounded.History
                                                },
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        },
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(14.dp))
                        }
                    }

                    if (state.moreFromArtistsYouLove.isNotEmpty()) {
                        item(key = "zone3_more_loved_header") {
                            SectionHeader(
                                title = stringResource(R.string.home_more_from_artists),
                                actionLabel = stringResource(R.string.action_show_all),
                                onActionClick = onShowAllMoreFromArtists,
                            )
                        }
                        item(key = "zone3_more_loved_row") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                items(
                                    items = state.moreFromArtistsYouLove,
                                    key = { "loved_${it.id}" },
                                ) { album ->
                                    AlbumCarouselCard(
                                        album = album,
                                        onClick = { onAlbumClick(album.id) },
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }

                    if (state.rediscoverAlbums.isNotEmpty()) {
                        item(key = "zone3_rediscover_header") {
                            SectionHeader(
                                title = stringResource(R.string.home_rediscover_albums),
                                actionLabel = stringResource(R.string.action_show_all),
                                onActionClick = onShowAllRediscover,
                            )
                        }
                        item(key = "zone3_rediscover_row") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                items(
                                    items = state.rediscoverAlbums,
                                    key = { "rediscover_${it.album.id}" },
                                ) { item ->
                                    val sub = listOfNotNull(
                                        item.album.artistName,
                                        formatRelativeDaysAgo(item.daysSinceLastPlayed),
                                    ).joinToString(" • ")
                                    AlbumCarouselCard(
                                        album = item.album,
                                        subtitleOverride = sub,
                                        onClick = { onAlbumClick(item.album.id) },
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }

                    if (state.unplayedGems.isNotEmpty()) {
                        item(key = "zone3_unplayed_header") {
                            SectionHeader(
                                title = stringResource(R.string.home_unplayed_gems),
                                actionLabel = stringResource(R.string.action_show_all),
                                onActionClick = onShowAllUnplayed,
                            )
                        }
                        item(key = "zone3_unplayed_row") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                items(
                                    items = state.unplayedGems,
                                    key = { "unplayed_${it.id}" },
                                ) { album ->
                                    AlbumCarouselCard(
                                        album = album,
                                        onClick = { onAlbumClick(album.id) },
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }

                    if (state.recentlyAddedAlbums.isNotEmpty()) {
                        item(key = "section_recent_albums") {
                            SectionHeader(
                                title = stringResource(R.string.home_recently_added),
                                actionLabel = stringResource(R.string.action_show_all),
                                onActionClick = onShowAllRecentlyAdded,
                            )
                        }
                        item(key = "row_recent_albums") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                items(
                                    items = state.recentlyAddedAlbums,
                                    key = { "added_${it.id}" },
                                ) { album ->
                                    AlbumCarouselCard(
                                        album = album,
                                        onClick = { onAlbumClick(album.id) },
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }

                    if (state.playlists.isNotEmpty()) {
                        item(key = "section_playlists") {
                            SectionHeader(
                                title = stringResource(R.string.home_playlists),
                                actionLabel = stringResource(R.string.action_show_all),
                                onActionClick = onShowAllPlaylists,
                            )
                        }
                        item(key = "row_playlists") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                items(
                                    items = state.playlists,
                                    key = { "pl_${it.id}" },
                                ) { playlist ->
                                    PlaylistCarouselCard(
                                        playlist = playlist,
                                        onClick = { onPlaylistClick(playlist.id) },
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }

                    if (state.recentlyPlayedTracks.isNotEmpty()) {
                        item(key = "section_recent_tracks") {
                            SectionHeader(title = stringResource(R.string.home_recently_played))
                        }
                        itemsIndexed(
                            items = state.recentlyPlayedTracks,
                            key = { _, track -> "recent_${track.id}" },
                        ) { index, track ->
                            TrackRow(
                                track = track,
                                isPlaying = track.id == state.activeTrackId,
                                enabled = !state.isOffline || track.isDownloaded,
                                onClick = { onTrackClick(state.recentlyPlayedTracks, index) },
                                onAddToQueue = { onAddToQueue(track) },
                                onAddToPlaylist = { onAddToPlaylist(track) },
                                onToggleDownload = { onToggleTrackDownload(track) },
                                onGoToAlbum = track.albumId?.let { id -> { onAlbumClick(id) } },
                                onGoToArtist = { onGoToArtist(track) },
                            )
                        }
                        item(key = "show_all_recently_played") {
                            ShowAllListFooterButton(onClick = onShowAllRecentlyPlayed)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickAccessGrid(
    tiles: List<QuickAccessTile>,
    onTileClick: (QuickAccessTile) -> Unit,
    onTilePlay: (QuickAccessTile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = tiles.chunked(2)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        rows.forEach { rowTiles ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowTiles.forEach { tile ->
                    QuickAccessTileCard(
                        tile = tile,
                        onClick = { onTileClick(tile) },
                        onPlayClick = { onTilePlay(tile) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowTiles.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun QuickAccessTileCard(
    tile: QuickAccessTile,
    onClick: () -> Unit,
    onPlayClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val resolvedTitle = when (tile.kind) {
        QuickAccessKind.FAVORITES -> stringResource(R.string.library_collection_favorite_songs)
        QuickAccessKind.DOWNLOADED -> stringResource(R.string.library_collection_downloaded_songs)
        else -> tile.title
    }

    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.height(56.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize(),
        ) {
            when (tile.kind) {
                QuickAccessKind.FAVORITES -> {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Favorite,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }

                QuickAccessKind.DOWNLOADED -> {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DownloadDone,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }

                else -> {
                    ArtworkCard(
                        imageUrl = tile.artworkUrl,
                        contentDescription = resolvedTitle,
                        shape = RectangleShape,
                        fallbackIcon = when (tile.kind) {
                            QuickAccessKind.PLAYLIST -> Icons.AutoMirrored.Rounded.QueueMusic
                            QuickAccessKind.ARTIST -> Icons.Rounded.Person
                            else -> Icons.Rounded.Album
                        },
                        fallbackIconSize = 22.dp,
                        modifier = Modifier.size(56.dp),
                    )
                }
            }

            Text(
                text = resolvedTitle,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp, end = 2.dp),
            )

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clickable(onClick = onPlayClick),
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(R.string.action_play),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun SmartMixCard(
    mix: SmartMixPreview,
    subtitle: String,
    canPlay: Boolean,
    onClick: () -> Unit,
    onPlayClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isLeftHanded = LocalLeftHandedMode.current
    val badgeIcon: ImageVector = when (mix.type) {
        SmartMixType.DAYLIST -> Icons.Rounded.AutoAwesome
        SmartMixType.ON_REPEAT -> Icons.Rounded.Repeat
        SmartMixType.JELLYFIN_INSTANT_MIX -> Icons.Rounded.GraphicEq
        SmartMixType.TIME_CAPSULE -> Icons.Rounded.History
        SmartMixType.DEEP_CUTS -> Icons.Rounded.Explore
        SmartMixType.GENRE_MIX -> Icons.Rounded.GraphicEq
        SmartMixType.DECADE_MIX -> Icons.Rounded.History
    }

    Column(
        modifier = modifier
            .width(156.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        ) {
            DaylistMosaicArtwork(
                artworkUrls = mix.artworkUrls,
                shape = MaterialTheme.shapes.medium,
                fallbackIconSize = 40.dp,
                modifier = Modifier.fillMaxSize(),
            )
            // Subtle top corner mix-type badge
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f),
                shape = CircleShape,
                modifier = Modifier
                    .align(if (isLeftHanded) Alignment.TopEnd else Alignment.TopStart)
                    .padding(8.dp)
                    .size(28.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = badgeIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            // Instant 1-tap Play button
            FilledIconButton(
                onClick = onPlayClick,
                enabled = canPlay,
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier
                    .align(if (isLeftHanded) Alignment.BottomStart else Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(38.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(R.string.action_play),
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = mix.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
private fun AlbumSpotlightCard(
    spotlight: AlbumSpotlight,
    showNextButton: Boolean,
    onOpenClick: () -> Unit,
    onPlayClick: () -> Unit,
    onNextClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val album = spotlight.album
    val reasonLabel = when (spotlight.reason) {
        SpotlightReason.FROM_ARTIST_YOU_LOVE ->
            stringResource(R.string.home_spotlight_reason_loved_artist)
        SpotlightReason.HAVENT_PLAYED_IN_A_WHILE -> {
            val rel = spotlight.daysSinceLastPlayed?.let(::formatRelativeDaysAgo)
            if (rel != null) {
                stringResource(R.string.home_spotlight_reason_rediscover_days, rel.removeSuffix(" ago"))
            } else {
                stringResource(R.string.home_spotlight_reason_rediscover)
            }
        }
        SpotlightReason.UNPLAYED_IN_CATALOG ->
            stringResource(R.string.home_spotlight_reason_unplayed)
        SpotlightReason.RECENTLY_ADDED_UNPLAYED ->
            stringResource(R.string.home_spotlight_reason_recent)
    }
    val metaLine = listOfNotNull(
        album.artistName.takeIf { it.isNotBlank() },
        album.year?.toString(),
        album.trackCount.takeIf { it > 0 }?.let { if (it == 1) "1 song" else "$it songs" },
    ).joinToString(" • ")

    Card(
        onClick = onOpenClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.62f),
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtworkCard(
                imageUrl = album.artworkUrl,
                contentDescription = album.title,
                shape = MaterialTheme.shapes.medium,
                fallbackIcon = Icons.Rounded.Album,
                fallbackIconSize = 36.dp,
                modifier = Modifier.size(104.dp),
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = CircleShape,
                ) {
                    Text(
                        text = reasonLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = album.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (metaLine.isNotEmpty()) {
                    Text(
                        text = metaLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.82f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = onPlayClick,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                        modifier = Modifier.height(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.action_play),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    if (showNextButton) {
                        FilledTonalIconButton(
                            onClick = onNextClick,
                            shape = CircleShape,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Casino,
                                contentDescription = stringResource(R.string.home_spotlight_next_pick),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShowAllListFooterButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        TextButton(onClick = onClick) {
            Text(
                text = stringResource(R.string.action_show_all),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(modifier = Modifier.width(2.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
