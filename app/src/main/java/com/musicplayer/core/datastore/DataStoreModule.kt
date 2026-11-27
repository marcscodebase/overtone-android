package com.musicplayer.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UserPrefsDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AuthDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class WireGuardDataStore

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    @Provides
    @Singleton
    @UserPrefsDataStore
    fun provideUserPreferencesDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        produceFile = { context.preferencesDataStoreFile("user_preferences") },
    )

    @Provides
    @Singleton
    @AuthDataStore
    fun provideAuthPreferencesDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        produceFile = { context.preferencesDataStoreFile("auth_session_secure") },
    )

    @Provides
    @Singleton
    @WireGuardDataStore
    fun provideWireGuardPreferencesDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        produceFile = { context.preferencesDataStoreFile("wireguard_config_secure") },
    )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DataStoreBindingsModule {

    @Binds
    @Singleton
    abstract fun bindCryptoCipher(
        impl: AndroidKeystoreCryptoCipher,
    ): CryptoCipher

    @Binds
    @Singleton
    abstract fun bindAuthSessionStore(
        impl: EncryptedAuthSessionStore,
    ): AuthSessionStore

    @Binds
    @Singleton
    abstract fun bindWireGuardConfigStore(
        impl: EncryptedWireGuardConfigStore,
    ): WireGuardConfigStore
}
