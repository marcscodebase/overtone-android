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
}
