package com.musicplayer.core.network

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.musicplayer.BuildConfig
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Provides
    @Singleton
    fun provideJellyfin(
        @ApplicationContext context: Context,
    ): Jellyfin = createJellyfin {
        this.context = context
        clientInfo = ClientInfo(
            name = "music-player",
            version = BuildConfig.VERSION_NAME,
        )
    }

    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient,
    ): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient }))
        }
        .memoryCache {
            MemoryCache.Builder()
                .maxSizePercent(context, percent = 0.20)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(File(context.cacheDir, "artwork_cache").toOkioPath())
                .maxSizeBytes(150L * 1024L * 1024L)
                .build()
        }
        .crossfade(true)
        .build()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkBindingsModule {

    @Binds
    @Singleton
    abstract fun bindNetworkMonitor(
        impl: ConnectivityManagerNetworkMonitor,
    ): NetworkMonitor

    @Binds
    @Singleton
    abstract fun bindOfflineStateProvider(
        impl: DefaultOfflineStateProvider,
    ): OfflineStateProvider
}
