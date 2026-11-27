package com.musicplayer.data.local

import com.musicplayer.core.database.entity.TrackEntity
import com.musicplayer.core.model.DownloadQuality
import com.musicplayer.core.model.DownloadQualityMode
import com.musicplayer.core.model.Mp3Bitrate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class OfflineFileVerifierAndTagInspectorTest {

    private fun sampleTrack(
        id: String = "11111111-2222-3333-4444-555555555555",
        title: String = "Time",
        artistName: String = "Pink Floyd",
        albumName: String = "The Dark Side of the Moon",
        trackNumber: Int? = 4,
        durationMs: Long = 413_000L,
    ) = TrackEntity(
        id = id,
        title = title,
        artistId = "artist-1",
        artistName = artistName,
        albumId = "album-1",
        albumName = albumName,
        trackNumber = trackNumber,
        discNumber = 1,
        durationMs = durationMs,
        container = "flac",
        codec = "flac",
        bitrate = 1_000_000,
        sampleRate = 44_100,
        bitDepth = 16,
        replayGainTrack = null,
        replayGainAlbum = null,
    )

    @Test
    fun roundTripsTrackIdentityAndChecksumInId3v24AndFlac() {
        val tempDir = Files.createTempDirectory("tag-inspect-test").toFile()
        try {
            // 1. MP3 ID3v2.4 round-trip + audio payload SHA-256 stability across re-tagging
            val mp3Payload = ByteArray(256) { (it * 7).toByte() }
            val mp3File = File(tempDir, "test.mp3").apply { writeBytes(mp3Payload) }
            val expectedPayloadSha = AudioTagInspector.computeAudioPayloadSha256(mp3File, "mp3")
            assertNotNull(expectedPayloadSha)

            assertTrue(
                Id3v24TagWriter.write(
                    mp3File,
                    AudioTagMetadata(
                        title = "Time",
                        artist = "Pink Floyd",
                        album = "Dark Side",
                        trackId = "track-uuid-1",
                        qualityTag = "MP3:320",
                        audioSha256 = expectedPayloadSha,
                        coverArtBytes = byteArrayOf(1, 2, 3, 4),
                    ),
                ),
            )
            val inspectedMp3 = AudioTagInspector.inspect(mp3File, "mp3")
            assertEquals("track-uuid-1", inspectedMp3.trackId)
            assertEquals("MP3:320", inspectedMp3.qualityTag)
            assertEquals(expectedPayloadSha, inspectedMp3.audioSha256)
            assertEquals("Time", inspectedMp3.title)
            assertEquals("Pink Floyd", inspectedMp3.artist)
            assertEquals("Dark Side", inspectedMp3.album)
            // Payload SHA stays identical even after ID3v2.4 header + cover art was added
            assertEquals(expectedPayloadSha, AudioTagInspector.computeAudioPayloadSha256(mp3File, "mp3"))

            // 2. FLAC Vorbis comment round-trip + payload SHA-256 stability
            val flacAudioPayload = ByteArray(128) { (it * 13).toByte() }
            val flacFile = File(tempDir, "test.flac").apply {
                val out = ByteArrayOutputStream()
                out.write("fLaC".toByteArray(Charsets.US_ASCII))
                // Last metadata block: STREAMINFO (type 0, length 34)
                out.write(0x80)
                out.write(0)
                out.write(0)
                out.write(34)
                out.write(ByteArray(34))
                out.write(flacAudioPayload)
                writeBytes(out.toByteArray())
            }
            val expectedFlacSha = AudioTagInspector.computeAudioPayloadSha256(flacFile, "flac")
            assertNotNull(expectedFlacSha)

            assertTrue(
                FlacTagWriter.write(
                    flacFile,
                    AudioTagMetadata(
                        title = "Money",
                        artist = "Pink Floyd",
                        album = "Dark Side",
                        trackId = "track-uuid-2",
                        qualityTag = "LOSSLESS",
                        audioSha256 = expectedFlacSha,
                    ),
                ),
            )
            val inspectedFlac = AudioTagInspector.inspect(flacFile, "flac")
            assertEquals("track-uuid-2", inspectedFlac.trackId)
            assertEquals("LOSSLESS", inspectedFlac.qualityTag)
            assertEquals(expectedFlacSha, inspectedFlac.audioSha256)
            assertEquals("Money", inspectedFlac.title)
            assertEquals("Pink Floyd", inspectedFlac.artist)
            assertEquals(expectedFlacSha, AudioTagInspector.computeAudioPayloadSha256(flacFile, "flac"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun verifiesEmbeddedTrackIdAndRejectsMismatchedId() {
        val track = sampleTrack()
        val matching = ScannedMediaStoreAudio(
            id = 1L,
            contentUri = "content://media/external/audio/media/1",
            relativePath = "Music/Overtone/Pink Floyd/The Dark Side of the Moon/",
            displayName = "04 - Time.flac",
            sizeBytes = 25_000_000L,
            durationMs = 413_200L,
            title = "Time",
            artist = "Pink Floyd",
            album = "The Dark Side of the Moon",
            dateModifiedSeconds = 1_700_000_000L,
            inspectedTags = InspectedAudioTags(trackId = track.id, qualityTag = "LOSSLESS"),
        )
        assertTrue(OfflineFileVerifier.verifyFileMatchesTrack(matching, track))
        assertTrue(
            OfflineFileVerifier.matchesRequestedQuality(
                matching,
                DownloadQuality(DownloadQualityMode.LOSSLESS),
            ),
        )
        assertFalse(
            OfflineFileVerifier.matchesRequestedQuality(
                matching,
                DownloadQuality(DownloadQualityMode.MP3, Mp3Bitrate.KBPS_320),
            ),
        )

        val wrongId = matching.copy(inspectedTags = InspectedAudioTags(trackId = "different-id"))
        assertFalse(OfflineFileVerifier.verifyFileMatchesTrack(wrongId, track))
    }

    @Test
    fun verifiesLegacyFilesWithoutTrackIdUsingStrictPathDurationAndTags() {
        val track = sampleTrack()
        val legacyFile = ScannedMediaStoreAudio(
            id = 2L,
            contentUri = "content://media/external/audio/media/2",
            relativePath = "Music/Overtone/Pink Floyd/The Dark Side of the Moon/",
            displayName = "04 - Time.flac",
            sizeBytes = 24_000_000L,
            durationMs = 412_500L,
            title = "Time",
            artist = "Pink Floyd",
            album = "The Dark Side of the Moon",
            dateModifiedSeconds = 1_700_000_000L,
        )
        assertTrue(OfflineFileVerifier.verifyFileMatchesTrack(legacyFile, track))

        // Rejects mismatched duration (> 3s off)
        assertFalse(
            OfflineFileVerifier.verifyFileMatchesTrack(legacyFile.copy(durationMs = 200_000L), track),
        )
        // Rejects mismatched title
        assertFalse(
            OfflineFileVerifier.verifyFileMatchesTrack(legacyFile.copy(title = "Different Song"), track),
        )
        // Rejects tiny/corrupt file
        assertFalse(
            OfflineFileVerifier.verifyFileMatchesTrack(legacyFile.copy(sizeBytes = 512L), track),
        )
    }

    @Test
    fun stripsDuplicateNumberSuffixAndPrefersCanonicalFile() {
        val clean = ScannedMediaStoreAudio(
            id = 10L,
            contentUri = "content://media/external/audio/media/10",
            relativePath = "Music/Overtone/Pink Floyd/The Dark Side of the Moon/",
            displayName = "04 - Time.flac",
            sizeBytes = 24_000_000L,
            durationMs = 413_000L,
            title = "Time",
            artist = "Pink Floyd",
            album = "The Dark Side of the Moon",
            dateModifiedSeconds = 1_700_000_000L,
        )
        val duplicate = clean.copy(
            id = 11L,
            contentUri = "content://media/external/audio/media/11",
            displayName = "04 - Time (1).flac",
            dateModifiedSeconds = 1_700_000_500L,
        )

        assertFalse(clean.isDuplicateNumberedName)
        assertTrue(duplicate.isDuplicateNumberedName)
        assertEquals("04 - Time.flac", duplicate.baseDisplayName)
        assertEquals(clean, OfflineFileVerifier.chooseCanonicalFile(listOf(duplicate, clean)))
    }
}
