package com.musicplayer.player.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.musicplayer.MainActivity
import com.musicplayer.R
import com.musicplayer.core.database.dao.TrackDao
import com.musicplayer.core.datastore.UserPreferencesDataStore
import com.musicplayer.core.model.AudioOutputType
import com.musicplayer.core.model.ReplayGainMode
import com.musicplayer.data.repository.SyncRepository
import com.musicplayer.player.PlaybackController
import com.musicplayer.player.PlaybackOkHttpClient
import com.musicplayer.player.engine.AudioOutputMonitor
import com.musicplayer.player.engine.ReplayGainAudioProcessor
import com.musicplayer.player.engine.ReplayGainMath
import com.musicplayer.player.engine.UsbBitPerfectController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import javax.inject.Inject

@OptIn(UnstableApi::class)
@AndroidEntryPoint
class MusicPlaybackService : MediaLibraryService() {

    @Inject
    @PlaybackOkHttpClient
    lateinit var playbackOkHttpClient: OkHttpClient

    @Inject
    lateinit var replayGainAudioProcessor: ReplayGainAudioProcessor

    @Inject
    lateinit var usbBitPerfectController: UsbBitPerfectController

    @Inject
    lateinit var audioOutputMonitor: AudioOutputMonitor

    @Inject
    lateinit var trackDao: TrackDao

    @Inject
    lateinit var preferencesDataStore: UserPreferencesDataStore

    @Inject
    lateinit var syncRepository: SyncRepository

    @Inject
    lateinit var playbackController: PlaybackController

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val currentMediaId = MutableStateFlow<String?>(null)
    private val currentTrackIsFavorite = MutableStateFlow(false)

    private var mediaLibrarySession: MediaLibrarySession? = null
    private var exoPlayer: ExoPlayer? = null

    override fun onCreate() {
        super.onCreate()

        val httpDataSourceFactory = OkHttpDataSource.Factory(playbackOkHttpClient)
        val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)
        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    // Keep integer 16/24/32-bit PCM intact so Android 14+ Bit-Perfect USB DAC
                    // mixer matching works on 24-bit/32-bit Hi-Res streams without float conversion.
                    .setEnableFloatOutput(false)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(replayGainAudioProcessor))
                    .setAudioTrackProvider { config, audioAttributes, audioSessionId ->
                        // Configure Android 14+ MIXER_BEHAVIOR_BIT_PERFECT synchronously right
                        // before AudioTrack creation so the very first sample uses the direct path.
                        usbBitPerfectController.configureForAudioTrack(
                            sampleRateHz = config.sampleRate,
                            channelConfig = config.channelConfig,
                            encoding = config.encoding,
                        )
                        DefaultAudioSink.AudioTrackProvider.DEFAULT.getAudioTrack(
                            config,
                            audioAttributes,
                            audioSessionId,
                        )
                    }
                    .build()
            }
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        exoPlayer = player

        player.addListener(
            object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    currentMediaId.value = mediaItem?.mediaId
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    val id = player.currentMediaItem?.mediaId
                    if (id != null && currentMediaId.value != id) {
                        currentMediaId.value = id
                    }
                    if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
                        usbBitPerfectController.clear()
                    }
                }
            },
        )

        observeReplayGainAndBitPerfectUpdates()

        val sessionActivityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        mediaLibrarySession = MediaLibrarySession.Builder(
            this,
            player,
            object : MediaLibrarySession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): MediaSession.ConnectionResult {
                    val isSelf = controller.packageName == packageName
                    val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                        .buildUpon()
                        .add(COMMAND_TOGGLE_FAVORITE)
                        .build()
                    val playerCommands = if (isSelf) {
                        MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
                    } else {
                        // Restrict third-party controllers from injecting arbitrary media URIs
                        // into ExoPlayer (prevents cross-app SSRF / auth token abuse).
                        MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
                            .buildUpon()
                            .remove(Player.COMMAND_SET_MEDIA_ITEM)
                            .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                            .build()
                    }
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(sessionCommands)
                        .setAvailablePlayerCommands(playerCommands)
                        .setMediaButtonPreferences(
                            listOf(buildFavoriteCommandButton(currentTrackIsFavorite.value)),
                        )
                        .build()
                }

                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> {
                    if (controller.packageName != packageName) {
                        // Strip any externally supplied URIs so third-party apps cannot stream arbitrary URLs
                        return Futures.immediateFuture(mutableListOf())
                    }
                    return super.onAddMediaItems(mediaSession, controller, mediaItems)
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle,
                ): ListenableFuture<SessionResult> {
                    if (customCommand.customAction == ACTION_TOGGLE_FAVORITE) {
                        toggleCurrentTrackFavorite()
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    return super.onCustomCommand(session, controller, customCommand, args)
                }
            },
        )
            .setSessionActivity(sessionActivityIntent)
            .setMediaButtonPreferences(listOf(buildFavoriteCommandButton(isFavorite = false)))
            .build()

        observeCurrentTrackFavoriteState()
    }

    @kotlin.OptIn(ExperimentalCoroutinesApi::class)
    private fun observeCurrentTrackFavoriteState() {
        serviceScope.launch {
            currentMediaId
                .flatMapLatest { trackId ->
                    if (trackId.isNullOrBlank()) {
                        flowOf(false)
                    } else {
                        trackDao.observeIsFavorite(trackId).map { it == true }
                    }
                }
                .distinctUntilChanged()
                .collect { isFavorite ->
                    currentTrackIsFavorite.value = isFavorite
                    mediaLibrarySession?.setMediaButtonPreferences(
                        listOf(buildFavoriteCommandButton(isFavorite)),
                    )
                }
        }
    }

    private fun toggleCurrentTrackFavorite() {
        val trackId = exoPlayer?.currentMediaItem?.mediaId
            ?: currentMediaId.value
            ?: return
        val targetFavorite = !currentTrackIsFavorite.value
        currentTrackIsFavorite.value = targetFavorite
        mediaLibrarySession?.setMediaButtonPreferences(
            listOf(buildFavoriteCommandButton(targetFavorite)),
        )
        playbackController.updateTrackFavorite(trackId, targetFavorite)
        serviceScope.launch {
            syncRepository.setTrackFavorite(trackId, targetFavorite)
        }
    }

    private fun buildFavoriteCommandButton(isFavorite: Boolean): CommandButton {
        val icon = if (isFavorite) {
            CommandButton.ICON_CHECK_CIRCLE_FILLED
        } else {
            CommandButton.ICON_PLUS_CIRCLE_UNFILLED
        }
        val label = getString(
            if (isFavorite) R.string.favorite_remove else R.string.favorite_add,
        )
        return CommandButton.Builder(icon)
            .setDisplayName(label)
            .setSessionCommand(COMMAND_TOGGLE_FAVORITE)
            .setSlots(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW)
            .build()
    }

    private fun observeReplayGainAndBitPerfectUpdates() {
        serviceScope.launch {
            var lastEffectiveBitPerfect: Boolean? = null
            var lastUsbActive: Boolean? = null
            val audioPrefs = preferencesDataStore.userPreferences
                .map { Triple(it.replayGainMode, it.replayGainPreampDb, it.bitPerfectUsbAudio) }
                .distinctUntilChanged()
            val usbActiveFlow = audioOutputMonitor.outputState
                .map { it.activeDevice.type == AudioOutputType.USB_AUDIO }
                .distinctUntilChanged()

            combine(currentMediaId, audioPrefs, usbActiveFlow) { trackId, (mode, preampDb, bitPerfectUsb), usbActive ->
                AudioProcessingConfig(
                    trackId = trackId,
                    replayGainMode = mode,
                    preampDb = preampDb,
                    bitPerfectUsbAudio = bitPerfectUsb,
                    usbActive = usbActive,
                )
            }.collect { cfg ->
                val replayGainActive = cfg.replayGainMode != ReplayGainMode.OFF
                val effectiveBitPerfect = cfg.bitPerfectUsbAudio && !replayGainActive
                replayGainAudioProcessor.setBypass(effectiveBitPerfect)
                usbBitPerfectController.updateSettings(
                    bitPerfectEnabled = cfg.bitPerfectUsbAudio,
                    isReplayGainActive = replayGainActive,
                )

                val bitPerfectStateChanged = lastEffectiveBitPerfect != null &&
                    lastEffectiveBitPerfect != effectiveBitPerfect
                val usbPluggedInForBitPerfect = lastUsbActive != null &&
                    !lastUsbActive!! && cfg.usbActive && effectiveBitPerfect
                lastEffectiveBitPerfect = effectiveBitPerfect
                lastUsbActive = cfg.usbActive

                if ((bitPerfectStateChanged && cfg.usbActive) || usbPluggedInForBitPerfect) {
                    recreateAudioTrackForActiveItem()
                }

                if (!replayGainActive || cfg.trackId.isNullOrBlank()) {
                    replayGainAudioProcessor.setTargetGain(1f)
                } else {
                    val entity = withContext(Dispatchers.IO) { trackDao.getById(cfg.trackId) }
                    val gain = ReplayGainMath.computeLinearGain(
                        mode = cfg.replayGainMode,
                        trackGainDb = entity?.replayGainTrack,
                        albumGainDb = entity?.replayGainAlbum,
                        preampDb = cfg.preampDb,
                    )
                    replayGainAudioProcessor.setTargetGain(gain)
                }
            }
        }
    }

    private fun recreateAudioTrackForActiveItem() {
        val player = exoPlayer ?: return
        if (player.mediaItemCount == 0 || player.playbackState == Player.STATE_IDLE) return
        val index = player.currentMediaItemIndex
        val positionMs = player.currentPosition.coerceAtLeast(0L)
        val wasPlaying = player.playWhenReady
        player.stop()
        player.prepare()
        if (index in 0 until player.mediaItemCount) {
            player.seekTo(index, positionMs)
        }
        player.playWhenReady = wasPlaying
    }

    private data class AudioProcessingConfig(
        val trackId: String?,
        val replayGainMode: ReplayGainMode,
        val preampDb: Float,
        val bitPerfectUsbAudio: Boolean,
        val usbActive: Boolean,
    )

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = exoPlayer
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0 ||
            player.playbackState == Player.STATE_ENDED
        ) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        usbBitPerfectController.clear()
        serviceScope.cancel()
        mediaLibrarySession?.run {
            player.release()
            release()
        }
        mediaLibrarySession = null
        exoPlayer = null
        super.onDestroy()
    }

    companion object {
        private const val ACTION_TOGGLE_FAVORITE = "io.saier.overtone.action.TOGGLE_FAVORITE"
        private val COMMAND_TOGGLE_FAVORITE = SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY)
    }
}
