package com.musicplayer.core.common

import kotlinx.coroutines.CancellationException
import java.io.IOException

sealed interface AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>
    data class Error(val error: AppError) : AppResult<Nothing>
}

sealed interface AppError {
    val message: String

    data class Network(override val message: String, val cause: Throwable? = null) : AppError
    data class Unauthorized(override val message: String) : AppError
    data class Server(val statusCode: Int?, override val message: String) : AppError
    data class Storage(override val message: String, val cause: Throwable? = null) : AppError
    data class Transcode(override val message: String, val cause: Throwable? = null) : AppError
    data class Validation(override val message: String) : AppError
    data class NotFound(override val message: String) : AppError
    data class Unknown(override val message: String, val cause: Throwable? = null) : AppError
}

inline fun <T> runCatchingAppResult(block: () -> T): AppResult<T> {
    return try {
        AppResult.Success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (io: IOException) {
        AppResult.Error(AppError.Network(io.message ?: "Network error", io))
    } catch (t: Throwable) {
        AppResult.Error(AppError.Unknown(t.message ?: "Unexpected error", t))
    }
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(data))
    is AppResult.Error -> this
}
