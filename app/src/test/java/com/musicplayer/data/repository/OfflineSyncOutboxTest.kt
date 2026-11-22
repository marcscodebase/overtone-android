package com.musicplayer.data.repository

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineSyncOutboxTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `playlist mutation payloads round-trip cleanly through json`() {
        val rename = PlaylistMutationPayload(
            operation = PlaylistMutationPayload.OP_RENAME,
            playlistId = "pl-1",
            newName = "Late Night Lossless",
        )
        val add = PlaylistMutationPayload(
            operation = PlaylistMutationPayload.OP_ADD_TRACKS,
            playlistId = "pl-1",
            trackIds = listOf("t1", "t2"),
        )
        val remove = PlaylistMutationPayload(
            operation = PlaylistMutationPayload.OP_REMOVE_TRACK,
            playlistId = "pl-1",
            playlistItemId = "item-99",
        )
        val move = PlaylistMutationPayload(
            operation = PlaylistMutationPayload.OP_MOVE_TRACK,
            playlistId = "pl-1",
            playlistItemId = "item-99",
            toIndex = 3,
        )

        listOf(rename, add, remove, move).forEach { original ->
            val encoded = json.encodeToString(original)
            val decoded = json.decodeFromString<PlaylistMutationPayload>(encoded)
            assertEquals(original, decoded)
        }
    }

    @Test
    fun `outbox retry policy deletes on success increments until limit and drops at limit`() {
        assertEquals(
            OutboxActionDisposition.DELETE_SUCCEEDED,
            OutboxRetryPolicy.evaluate(succeeded = true, currentRetryCount = 0, maxRetries = 5),
        )
        assertEquals(
            OutboxActionDisposition.INCREMENT_RETRY,
            OutboxRetryPolicy.evaluate(succeeded = false, currentRetryCount = 0, maxRetries = 5),
        )
        assertEquals(
            OutboxActionDisposition.INCREMENT_RETRY,
            OutboxRetryPolicy.evaluate(succeeded = false, currentRetryCount = 3, maxRetries = 5),
        )
        assertEquals(
            OutboxActionDisposition.DROP_MAX_RETRIES,
            OutboxRetryPolicy.evaluate(succeeded = false, currentRetryCount = 4, maxRetries = 5),
        )
    }
}
