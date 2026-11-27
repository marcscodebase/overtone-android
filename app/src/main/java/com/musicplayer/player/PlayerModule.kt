package com.musicplayer.player

import com.musicplayer.core.network.wireguard.WireGuardTunnelManager
import com.musicplayer.player.engine.JellyfinAuthHeaderInterceptor
import com.musicplayer.player.transcoder.AndroidMediaCodecMp3Encoder
import com.musicplayer.player.transcoder.AudioHttpFetcher
import com.musicplayer.player.transcoder.OkHttpAudioFetcher
import com.musicplayer.player.transcoder.OnDeviceMp3Encoder
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PlaybackOkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object PlayerProvidersModule {

    @Provides
    @Singleton
    @PlaybackOkHttpClient
    fun providePlaybackOkHttpClient(
        baseOkHttpClient: OkHttpClient,
        authHeaderInterceptor: JellyfinAuthHeaderInterceptor,
        wireGuardTunnelManager: WireGuardTunnelManager,
    ): OkHttpClient {
        val client = baseOkHttpClient.newBuilder()
            .dispatcher(Dispatcher())
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addNetworkInterceptor(authHeaderInterceptor)
            .build()
        wireGuardTunnelManager.registerConnectionEvictor {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
        }
        return client
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PlayerBindingsModule {

    @Binds
    @Singleton
    abstract fun bindPlaybackController(
        impl: MediaControllerPlaybackController,
    ): PlaybackController

    @Binds
    @Singleton
    abstract fun bindOnDeviceMp3Encoder(
        impl: AndroidMediaCodecMp3Encoder,
    ): OnDeviceMp3Encoder

    @Binds
    @Singleton
    abstract fun bindAudioHttpFetcher(
        impl: OkHttpAudioFetcher,
    ): AudioHttpFetcher
}
