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
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.musicplayer.MainActivity
import com.musicplayer.core.database.dao.TrackDao
import com.musicplayer.core.datastore.UserPreferencesDataStore
import com.musicplayer.core.model.ReplayGainMode
import com.musicplayer.player.PlaybackOkHttpClient
import com.musicplayer.player.engine.ReplayGainAudioProcessor
import com.musicplayer.player.engine.ReplayGainMath
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
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(replayGainAudioProcessor))
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
                }
            },
        )

        observeReplayGainUpdates()

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

    private fun observeReplayGainUpdates() {
        serviceScope.launch {
            val replayGainPrefs = preferencesDataStore.userPreferences
                .map { it.replayGainMode to it.replayGainPreampDb }
                .distinctUntilChanged()

            combine(currentMediaId, replayGainPrefs) { trackId, (mode, preampDb) ->
                Triple(trackId, mode, preampDb)
            }.collect { (trackId, mode, preampDb) ->
                if (mode == ReplayGainMode.OFF || trackId.isNullOrBlank()) {
                    replayGainAudioProcessor.setTargetGain(1f)
                } else {
                    val entity = withContext(Dispatchers.IO) { trackDao.getById(trackId) }
                    val gain = ReplayGainMath.computeLinearGain(
                        mode = mode,
                        trackGainDb = entity?.replayGainTrack,
                        albumGainDb = entity?.replayGainAlbum,
                        preampDb = preampDb,
                    )
                    replayGainAudioProcessor.setTargetGain(gain)
                }
            }
        }
    }

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
