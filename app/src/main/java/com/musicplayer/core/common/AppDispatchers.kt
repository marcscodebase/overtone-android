package com.musicplayer.core.common

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class Dispatcher(val dispatcher: AppDispatcher)

enum class AppDispatcher {
    IO,
    Default,
    Main,
}

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApplicationScope
