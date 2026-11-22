package com.musicplayer.feature.library

import app.cash.turbine.test
import com.musicplayer.core.common.AppResult
import com.musicplayer.core.model.Album
import com.musicplayer.core.model.Artist
import com.musicplayer.core.model.Playlist
import com.musicplayer.core.model.PlaylistTrackEntry
import com.musicplayer.core.model.SearchResults
import com.musicplayer.core.model.Track
import com.musicplayer.core.network.OfflineStateProvider
import com.musicplayer.core.network.OfflineStatus
import com.musicplayer.data.repository.LibraryRepository
import com.musicplayer.data.repository.PlaylistRepository
import com.musicplayer.data.repository.SyncRepository
import com.musicplayer.player.PlaybackController
import com.musicplayer.player.PlaybackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun togglesSortOrderAndFilterSelection() = runTest {
        val albums = listOf(
            Album("a1", "Zebra", null, "Artist A", 2020, 10, false, dateAdded = 200L, artworkUrl = null),
            Album("a2", "Alpha", null, "Artist B", 2021, 8, false, dateAdded = 100L, artworkUrl = null),
        )
        val viewModel = LibraryViewModel(
            libraryRepository = FakeLibraryRepository(albums),
            playlistRepository = FakePlaylistRepository(),
            syncRepository = FakeSyncRepository(),
            playbackController = FakePlaybackController(),
            offlineStateProvider = FakeOfflineStateProvider(),
        )

        viewModel.uiState.test {
            skipItems(1) // initial empty state
            val initial = awaitItem()
            assertEquals(LibraryFilter.ALBUMS, initial.selectedFilter)
            assertEquals(listOf("Alpha", "Zebra"), initial.albums.map { it.title })

            viewModel.onToggleSort()
            val byRecent = awaitItem()
            assertEquals(LibrarySort.RECENTLY_ADDED, byRecent.selectedSort)
            assertEquals(listOf("Zebra", "Alpha"), byRecent.albums.map { it.title })

            viewModel.onFilterSelected(LibraryFilter.DOWNLOADED)
            val downloaded = awaitItem()
            assertEquals(LibraryFilter.DOWNLOADED, downloaded.selectedFilter)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private class FakeLibraryRepository(
        private val albums: List<Album>,
    ) : LibraryRepository {
        override fun observeRecentlyAddedAlbums(limit: Int): Flow<List<Album>> = flowOf(albums)
        override fun observeRecentlyPlayedTracks(limit: Int): Flow<List<Track>> = flowOf(emptyList())
        override fun observeFavoriteTracks(): Flow<List<Track>> = flowOf(emptyList())
        override fun observeAllAlbums(): Flow<List<Album>> = flowOf(albums)
        override fun observeAllArtists(): Flow<List<Artist>> = flowOf(emptyList())
        override fun observeAllTracks(): Flow<List<Track>> = flowOf(emptyList())
        override fun observeAllPlaylists(): Flow<List<Playlist>> = flowOf(emptyList())
        override fun observeDownloadedTracks(): Flow<List<Track>> = flowOf(emptyList())
        override fun observeAlbum(albumId: String): Flow<Album?> = flowOf(null)
        override fun observeAlbumTracks(albumId: String): Flow<List<Track>> = flowOf(emptyList())
        override fun observeArtist(artistId: String): Flow<Artist?> = flowOf(null)
        override fun observeArtistAlbums(artistId: String): Flow<List<Album>> = flowOf(emptyList())
        override fun observeArtistTracks(artistId: String): Flow<List<Track>> = flowOf(emptyList())
        override fun observePlaylist(playlistId: String): Flow<Playlist?> = flowOf(null)
        override fun observePlaylistTracks(playlistId: String): Flow<List<Track>> = flowOf(emptyList())
        override suspend fun syncLibrary(): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun syncPlaylistTracks(playlistId: String): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun search(query: String): AppResult<SearchResults> = AppResult.Success(SearchResults())
    }

    private class FakePlaylistRepository : PlaylistRepository {
        override fun observePlaylistTrackEntries(playlistId: String): Flow<List<PlaylistTrackEntry>> = flowOf(emptyList())
        override suspend fun createPlaylist(name: String, initialTrackIds: List<String>): AppResult<Playlist> =
            AppResult.Success(Playlist("p1", name, initialTrackIds.size, false, null))
        override suspend fun renamePlaylist(playlistId: String, newName: String): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun deletePlaylist(playlistId: String): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun addTracksToPlaylist(playlistId: String, trackIds: List<String>): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun removeTrackFromPlaylist(playlistId: String, playlistItemId: String): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun moveTrackInPlaylist(playlistId: String, playlistItemId: String, fromIndex: Int, toIndex: Int): AppResult<Unit> = AppResult.Success(Unit)
    }

    private class FakeSyncRepository : SyncRepository {
        override fun observePendingSyncCount(): Flow<Int> = flowOf(0)
        override suspend fun reportPlaybackStart(trackId: String, positionMs: Long, isTranscoded: Boolean) = Unit
        override suspend fun reportPlaybackProgress(trackId: String, positionMs: Long, isPaused: Boolean, isTranscoded: Boolean) = Unit
        override suspend fun reportPlaybackStopped(trackId: String, positionMs: Long) = Unit
        override suspend fun setTrackFavorite(trackId: String, isFavorite: Boolean): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun setAlbumFavorite(albumId: String, isFavorite: Boolean): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun setArtistFavorite(artistId: String, isFavorite: Boolean): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun enqueuePlaylistMutation(payload: com.musicplayer.data.repository.PlaylistMutationPayload) = Unit
        override suspend fun flushPendingSyncActions(): Int = 0
    }

    private class FakePlaybackController : PlaybackController {
        override val playbackState: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState())
        override val feedbackMessages: SharedFlow<String> = MutableSharedFlow()
        override fun restorePersistedQueue() = Unit
        override fun playTracks(tracks: List<Track>, startIndex: Int, shuffle: Boolean) = Unit
        override fun playNext(track: Track) = Unit
        override fun addToQueue(track: Track) = Unit
        override fun addToQueue(tracks: List<Track>) = Unit
        override fun skipToQueueIndex(index: Int) = Unit
        override fun removeFromQueue(index: Int) = Unit
        override fun moveQueueItem(fromIndex: Int, toIndex: Int) = Unit
        override fun clearUpNext() = Unit
        override fun updateTrackFavorite(trackId: String, isFavorite: Boolean) = Unit
        override fun togglePlayPause() = Unit
        override fun skipToNext() = Unit
        override fun skipToPrevious() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun toggleShuffle() = Unit
        override fun cycleRepeatMode() = Unit
        override fun stopAndClear() = Unit
        override fun clearError() = Unit
    }

    private class FakeOfflineStateProvider : OfflineStateProvider {
        private val state = MutableStateFlow(OfflineStatus())
        override val offlineStatus: StateFlow<OfflineStatus> = state
        override suspend fun setManualOfflineMode(enabled: Boolean) {
            state.value = state.value.copy(manualOfflineMode = enabled)
        }
    }
}
