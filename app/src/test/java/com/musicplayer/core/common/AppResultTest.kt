package com.musicplayer.core.common

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AppResultTest {

    @Test
    fun runCatchingWrapsSuccessValue() {
        val result = runCatchingAppResult { 42 }
        assertEquals(AppResult.Success(42), result)
    }

    @Test
    fun runCatchingMapsIoExceptionToNetworkError() {
        val result = runCatchingAppResult<Unit> { throw IOException("timeout") }
        assertTrue(result is AppResult.Error && result.error is AppError.Network)
        assertEquals("timeout", (result as AppResult.Error).error.message)
    }

    @Test(expected = CancellationException::class)
    fun runCatchingRethrowsCancellationException() {
        runCatchingAppResult<Unit> { throw CancellationException("cancelled") }
    }

    @Test
    fun mapTransformsSuccessAndPreservesError() {
        val ok = AppResult.Success(5).map { it * 2 }
        assertEquals(AppResult.Success(10), ok)

        val err: AppResult<Int> = AppResult.Error(AppError.NotFound("missing"))
        assertEquals(err, err.map { it * 2 })
    }
}
