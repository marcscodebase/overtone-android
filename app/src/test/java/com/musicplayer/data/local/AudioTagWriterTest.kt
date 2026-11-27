package com.musicplayer.data.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream

class AudioTagWriterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun writesId3v24TagsAndArtworkWhilePreservingMp3AudioFrames() {
        val mp3File = tempFolder.newFile("sample.mp3")
        val fakeAudioFrames = byteArrayOf(
            0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00, 0x11, 0x22, 0x33, 0x44,
        )
        mp3File.writeBytes(fakeAudioFrames)

        val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10)
        val ok = Id3v24TagWriter.write(
            file = mp3File,
            metadata = AudioTagMetadata(
                title = "Breathe",
                artist = "Pink Floyd",
                album = "Dark Side",
                trackNumber = 2,
                discNumber = 1,
                year = 1973,
                replayGainTrackDb = -5.4f,
                coverArtBytes = fakeJpeg,
            ),
        )

        assertTrue(ok)
        val resultBytes = mp3File.readBytes()
        assertEquals("ID3", String(resultBytes, 0, 3, Charsets.US_ASCII))
        assertEquals(4, resultBytes[3].toInt())
        val tagSize = Id3v24TagWriter.decodeSyncSafeInt(resultBytes, 6)
        val extractedAudio = resultBytes.copyOfRange(10 + tagSize, resultBytes.size)
        assertArrayEquals(fakeAudioFrames, extractedAudio)

        val tagString = String(resultBytes, 0, 10 + tagSize, Charsets.UTF_8)
        assertTrue(tagString.contains("TIT2"))
        assertTrue(tagString.contains("Breathe"))
        assertTrue(tagString.contains("APIC"))
        assertTrue(tagString.contains("REPLAYGAIN_TRACK_GAIN"))
    }

    @Test
    fun writesFlacVorbisCommentsAndPictureWhilePreservingAudioFrames() {
        val flacFile = tempFolder.newFile("sample.flac")
        val fakeStreamInfo = ByteArray(34) { 0x2A }
        val fakeAudioFrames = byteArrayOf(0xFF.toByte(), 0xF8.toByte(), 0x69, 0x02, 0x55, 0x66)

        val rawFlac = ByteArrayOutputStream().apply {
            write("fLaC".toByteArray(Charsets.US_ASCII))
            write(0x80) // last metadata block + type 0 (STREAMINFO)
            write(0x00)
            write(0x00)
            write(34)
            write(fakeStreamInfo)
            write(fakeAudioFrames)
        }.toByteArray()
        flacFile.writeBytes(rawFlac)

        val ok = FlacTagWriter.write(
            file = flacFile,
            metadata = AudioTagMetadata(
                title = "Echoes",
                artist = "Pink Floyd",
                album = "Meddle",
                trackNumber = 6,
                year = 1971,
                coverArtBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02),
            ),
        )

        assertTrue(ok)
        val bytes = flacFile.readBytes()
        assertEquals("fLaC", String(bytes, 0, 4, Charsets.US_ASCII))
        // Audio frames must remain byte-identical at end of file
        val tail = bytes.copyOfRange(bytes.size - fakeAudioFrames.size, bytes.size)
        assertArrayEquals(fakeAudioFrames, tail)
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.contains("TITLE=Echoes"))
        assertTrue(text.contains("ALBUM=Meddle"))
        assertTrue(text.contains("image/jpeg"))
    }

    @Test
    fun preservesExistingFlacVorbisCommentsAndVendorWhileMergingUpdatedTags() {
        val flacFile = tempFolder.newFile("tagged.flac")
        val fakeStreamInfo = ByteArray(34) { 0x2A }
        val fakeAudioFrames = byteArrayOf(0xFF.toByte(), 0xF8.toByte(), 0x69, 0x02, 0x77, 0x88.toByte())
        val existingVorbis = buildRawVorbisCommentBlock(
            vendor = "reference libFLAC 1.4.3",
            comments = listOf(
                "title=Old Title",
                "ARTIST=Old Artist",
                "ALBUMARTIST=Various Artists",
                "DATE=1971-10-31",
                "GENRE=Electronic",
                "GENRE=Art Rock",
                "REPLAYGAIN_ALBUM_GAIN=-6.20 dB",
                "MUSICBRAINZ_TRACKID=mb-track-123",
                "LYRICS=[00:05.00]Original synced line",
            ),
        )

        val rawFlac = ByteArrayOutputStream().apply {
            write("fLaC".toByteArray(Charsets.US_ASCII))
            // STREAMINFO (not last)
            write(0x00)
            write(0x00)
            write(0x00)
            write(34)
            write(fakeStreamInfo)
            // VORBIS_COMMENT (last, type 4)
            write(0x84)
            write((existingVorbis.size shr 16) and 0xFF)
            write((existingVorbis.size shr 8) and 0xFF)
            write(existingVorbis.size and 0xFF)
            write(existingVorbis)
            write(fakeAudioFrames)
        }.toByteArray()
        flacFile.writeBytes(rawFlac)

        val ok = FlacTagWriter.write(
            file = flacFile,
            metadata = AudioTagMetadata(
                title = "Echoes",
                artist = "Pink Floyd",
                albumArtist = null,
                album = "Meddle",
                trackNumber = 6,
                year = 1971,
                lyrics = "Plain unsynced lyrics should not downgrade synced lyrics",
                trackId = "track-42",
                qualityTag = "LOSSLESS",
            ),
        )
        assertTrue(ok)

        val bytes = flacFile.readBytes()
        val tail = bytes.copyOfRange(bytes.size - fakeAudioFrames.size, bytes.size)
        assertArrayEquals(fakeAudioFrames, tail)

        val parsed = extractVorbisComments(bytes)
        assertEquals("reference libFLAC 1.4.3", parsed.vendor)
        val comments = parsed.comments
        assertEquals(listOf("Echoes"), comments.filter { it.first.equals("TITLE", true) }.map { it.second })
        assertEquals(listOf("Pink Floyd"), comments.filter { it.first.equals("ARTIST", true) }.map { it.second })
        assertEquals(listOf("Various Artists"), comments.filter { it.first.equals("ALBUMARTIST", true) }.map { it.second })
        assertEquals(listOf("1971-10-31"), comments.filter { it.first.equals("DATE", true) }.map { it.second })
        assertEquals(listOf("Electronic", "Art Rock"), comments.filter { it.first.equals("GENRE", true) }.map { it.second })
        assertEquals(listOf("-6.20 dB"), comments.filter { it.first.equals("REPLAYGAIN_ALBUM_GAIN", true) }.map { it.second })
        assertEquals(listOf("mb-track-123"), comments.filter { it.first.equals("MUSICBRAINZ_TRACKID", true) }.map { it.second })
        assertEquals(listOf("[00:05.00]Original synced line"), comments.filter { it.first.equals("LYRICS", true) }.map { it.second })
        assertEquals(listOf("track-42"), comments.filter { it.first == AudioTagInspector.TAG_TRACK_ID }.map { it.second })

        // Re-tagging with newer synced lyrics upgrades LYRICS without duplicating tags
        val ok2 = FlacTagWriter.write(
            file = flacFile,
            metadata = AudioTagMetadata(
                title = "Echoes",
                artist = "Pink Floyd",
                albumArtist = "Pink Floyd",
                album = "Meddle",
                year = 1971,
                lyrics = "[00:05.50]Updated synced line",
                trackId = "track-42",
            ),
        )
        assertTrue(ok2)
        val reparsed = extractVorbisComments(flacFile.readBytes())
        assertEquals(listOf("[00:05.50]Updated synced line"), reparsed.comments.filter { it.first.equals("LYRICS", true) }.map { it.second })
        assertEquals(listOf("Pink Floyd"), reparsed.comments.filter { it.first.equals("ALBUMARTIST", true) }.map { it.second })
        assertEquals(1, reparsed.comments.count { it.first == AudioTagInspector.TAG_TRACK_ID })
        assertEquals(listOf("Electronic", "Art Rock"), reparsed.comments.filter { it.first.equals("GENRE", true) }.map { it.second })
    }

    private fun buildRawVorbisCommentBlock(vendor: String, comments: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        fun writeLeInt(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF)
            out.write((v shr 24) and 0xFF)
        }
        val vendorBytes = vendor.toByteArray(Charsets.UTF_8)
        writeLeInt(vendorBytes.size)
        out.write(vendorBytes)
        writeLeInt(comments.size)
        comments.forEach { comment ->
            val bytes = comment.toByteArray(Charsets.UTF_8)
            writeLeInt(bytes.size)
            out.write(bytes)
        }
        return out.toByteArray()
    }

    private fun extractVorbisComments(flacBytes: ByteArray): FlacTagWriter.ParsedVorbisComments {
        var offset = 4
        while (offset + 4 <= flacBytes.size) {
            val b0 = flacBytes[offset].toInt() and 0xFF
            val isLast = (b0 and 0x80) != 0
            val blockType = b0 and 0x7F
            val len = ((flacBytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((flacBytes[offset + 2].toInt() and 0xFF) shl 8) or
                (flacBytes[offset + 3].toInt() and 0xFF)
            offset += 4
            if (blockType == 4) {
                return FlacTagWriter.parseVorbisCommentBlock(flacBytes.copyOfRange(offset, offset + len))!!
            }
            offset += len
            if (isLast) break
        }
        throw AssertionError("VORBIS_COMMENT block not found")
    }
}
