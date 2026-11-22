package com.musicplayer.data.repository

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncPayloadTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun roundTripsPlaybackStopAndFavoritePayloads() {
        val stop = PlaybackStopPayload(trackId = "track-42", positionMs = 123_456L)
        val encodedStop = json.encodeToString(stop)
        assertEquals(stop, json.decodeFromString<PlaybackStopPayload>(encodedStop))

        val fav = FavoriteMutationPayload(itemId = "item-99")
        val encodedFav = json.encodeToString(fav)
        assertEquals(fav, json.decodeFromString<FavoriteMutationPayload>(encodedFav))
    }
}
