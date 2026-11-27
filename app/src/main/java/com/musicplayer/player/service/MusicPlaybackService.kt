package com.musicplayer.player.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.musicplayer.MainActivity
import com.musicplayer.core.database.dao.TrackDao
import com.musicplayer.core.datastore.UserPreferencesDataStore
import com.musicplayer.core.model.AudioOutputType
import com.musicplayer.core.model.ReplayGainMode
import com.musicplayer.player.PlaybackOkHttpClient
import com.musicplayer.player.engine.AudioOutputMonitor
import com.musicplayer.player.engine.ReplayGainAudioProcessor
import com.musicplayer.player.engine.ReplayGainMath
import com.musicplayer.player.engine.UsbBitPerfectController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val currentMediaId = MutableStateFlow<String?>(null)

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
                    // Preserve full 24-bit Hi-Res PCM precision instead of downsampling to 16-bit
                    .setEnableFloatOutput(true)
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
            object : MediaLibrarySession.Callback {},
        )
            .setSessionActivity(sessionActivityIntent)
            .build()
    }

    private fun observeReplayGainAndBitPerfectUpdates() {
        serviceScope.launch {
            var lastEffectiveBitPerfect: Boolean? = null
            val audioPrefs = preferencesDataStore.userPreferences
                .map { Triple(it.replayGainMode, it.replayGainPreampDb, it.bitPerfectUsbAudio) }
                .distinctUntilChanged()

            combine(currentMediaId, audioPrefs) { trackId, (mode, preampDb, bitPerfectUsb) ->
                AudioProcessingConfig(
                    trackId = trackId,
                    replayGainMode = mode,
                    preampDb = preampDb,
                    bitPerfectUsbAudio = bitPerfectUsb,
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
                lastEffectiveBitPerfect = effectiveBitPerfect

                if (bitPerfectStateChanged &&
                    audioOutputMonitor.outputState.value.activeDevice.type == AudioOutputType.USB_AUDIO
                ) {
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
}
