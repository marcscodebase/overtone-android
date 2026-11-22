package com.musicplayer.player

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
import okhttp3.OkHttpClient
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
    ): OkHttpClient = baseOkHttpClient.newBuilder()
        .addInterceptor(authHeaderInterceptor)
        .build()
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
