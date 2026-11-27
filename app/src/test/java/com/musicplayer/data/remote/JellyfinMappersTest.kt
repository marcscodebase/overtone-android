package com.musicplayer.data.remote

import kotlinx.serialization.json.Json
import org.jellyfin.sdk.model.api.BaseItemDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class JellyfinMappersTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun mapsLosslessTrackDtoWithAudioStreamSpecs() {
        val trackId = UUID.randomUUID()
        val albumId = UUID.randomUUID()
        val artistId = UUID.randomUUID()

        val payload = """
            {
              "Id": "$trackId",
              "Type": "Audio",
              "Name": "Echoes",
              "Album": "Meddle",
              "AlbumId": "$albumId",
              "Artists": ["Pink Floyd"],
              "ArtistItems": [{"Id": "$artistId", "Name": "Pink Floyd"}],
              "IndexNumber": 6,
              "ParentIndexNumber": 1,
              "ProductionYear": 1971,
              "PremiereDate": "1971-11-05T00:00:00.0000000Z",
              "Genres": ["Progressive Rock", "Psychedelic Rock"],
              "RunTimeTicks": 14110000000,
              "NormalizationGain": -4.2,
              "HasLyrics": true,
              "ImageTags": {"Primary": "img-tag-1"},
              "UserData": {
                "ItemId": "$trackId",
                "IsFavorite": true,
                "PlayCount": 12,
                "Played": true,
                "PlaybackPositionTicks": 0,
                "Key": "k"
              },
              "MediaSources": [
                {
                  "Protocol": "File",
                  "Type": "Default",
                  "Container": "flac",
                  "Bitrate": 2450000,
                  "IsRemote": false,
                  "ReadAtNativeFramerate": false,
                  "IgnoreDts": false,
                  "IgnoreIndex": false,
                  "GenPtsInput": false,
                  "SupportsTranscoding": true,
                  "SupportsDirectStream": true,
                  "SupportsDirectPlay": true,
                  "IsInfiniteStream": false,
                  "UseMostCompatibleTranscodingProfile": false,
                  "RequiresOpening": false,
                  "RequiresClosing": false,
                  "RequiresLooping": false,
                  "SupportsProbing": true,
                  "TranscodingSubProtocol": "http",
                  "HasSegments": false,
                  "MediaStreams": [
                    {
                      "Type": "Audio",
                      "Codec": "flac",
                      "BitRate": 2450000,
                      "SampleRate": 96000,
                      "BitDepth": 24,
                      "Index": 0,
                      "IsInterlaced": false,
                      "IsDefault": true,
                      "IsForced": false,
                      "IsHearingImpaired": false,
                      "IsExternal": false,
                      "IsTextSubtitleStream": false,
                      "SupportsExternalStream": false
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val dto = json.decodeFromString<BaseItemDto>(payload)
        val entity = JellyfinMappers.toTrackEntity(dto)

        assertEquals(trackId.toString(), entity.id)
        assertEquals("Echoes", entity.title)
        assertEquals("Pink Floyd", entity.artistName)
        assertEquals(artistId.toString(), entity.artistId)
        assertEquals(albumId.toString(), entity.albumId)
        assertEquals("Meddle", entity.albumName)
        assertEquals(6, entity.trackNumber)
        assertEquals(1, entity.discNumber)
        assertEquals(1_411_000L, entity.durationMs)
        assertEquals("flac", entity.container)
        assertEquals("flac", entity.codec)
        assertEquals(96_000, entity.sampleRate)
        assertEquals(24, entity.bitDepth)
        assertEquals(-4.2f, entity.replayGainTrack ?: 0f, 0.001f)
        assertTrue(entity.isFavorite)
        assertEquals(12, entity.playCount)
        assertTrue(entity.hasLyrics)
        assertEquals("img-tag-1", entity.primaryImageTag)
        assertEquals(1971, entity.year)
        assertTrue((entity.premiereDate ?: 0L) != 0L)
        assertEquals(
            listOf("Progressive Rock", "Psychedelic Rock"),
            com.musicplayer.core.model.GenresCodec.decode(entity.genresJson),
        )
    }

    @Test
    fun mapsAlbumAndArtistDtosWithDefaults() {
        val albumId = UUID.randomUUID()
        val albumDto = json.decodeFromString<BaseItemDto>(
            """
            {
              "Id": "$albumId",
              "Type": "MusicAlbum",
              "Name": "Discovery",
              "AlbumArtist": "Daft Punk",
              "ProductionYear": 2001,
              "ChildCount": 14
            }
            """.trimIndent(),
        )
        val album = JellyfinMappers.toAlbumEntity(albumDto)
        assertEquals(albumId.toString(), album.id)
        assertEquals("Discovery", album.title)
        assertEquals("Daft Punk", album.artistName)
        assertEquals(2001, album.year)
        assertEquals(14, album.trackCount)

        val artistId = UUID.randomUUID()
        val artistDto = json.decodeFromString<BaseItemDto>(
            """
            {
              "Id": "$artistId",
              "Type": "MusicArtist",
              "Name": "Daft Punk",
              "AlbumCount": 4
            }
            """.trimIndent(),
        )
        val artist = JellyfinMappers.toArtistEntity(artistDto)
        assertEquals(artistId.toString(), artist.id)
        assertEquals("Daft Punk", artist.name)
        assertEquals(4, artist.albumCount)
    }
}
