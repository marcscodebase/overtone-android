package com.musicplayer.player

import com.musicplayer.player.engine.JellyfinAuthHeaderInterceptor
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
}
