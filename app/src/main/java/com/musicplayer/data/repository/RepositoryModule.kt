package com.musicplayer.data.repository

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(
        impl: JellyfinAuthRepository,
    ): AuthRepository

    @Binds
    @Singleton
    abstract fun bindLibraryRepository(
        impl: JellyfinLibraryRepository,
    ): LibraryRepository
}
