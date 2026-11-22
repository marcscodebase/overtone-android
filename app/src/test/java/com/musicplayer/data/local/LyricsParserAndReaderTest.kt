package com.musicplayer.data.local

import com.musicplayer.core.model.LyricsParser
import com.musicplayer.core.model.LyricsSource
import com.musicplayer.data.repository.JellyfinLyricLineDto
import com.musicplayer.data.repository.JellyfinLyricsMapper
import com.musicplayer.data.repository.JellyfinLyricsResponseDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class LyricsParserAndReaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `parses synced lrc timestamps including multi-timestamp lines`() {
        val raw = """
            [ti:Paranoid Android]
            [ar:Radiohead]
            [00:12.50]Please could you stop the noise
            [00:16.00][01:02.25]I'm trying to get some rest
        """.trimIndent()

        val parsed = LyricsParser.parse(raw, LyricsSource.EMBEDDED)
        assertNotNull(parsed)
        assertTrue(parsed!!.isSynced)
        assertEquals(3, parsed.lines.size)
        assertEquals(12_500L, parsed.lines[0].startTimeMs)
        assertEquals("Please could you stop the noise", parsed.lines[0].text)
        assertEquals(16_000L, parsed.lines[1].startTimeMs)
        assertEquals(62_250L, parsed.lines[2].startTimeMs)

        assertEquals(-1, LyricsParser.findActiveLineIndex(parsed.lines, 10_000L))
        assertEquals(0, LyricsParser.findActiveLineIndex(parsed.lines, 12_500L))
        assertEquals(1, LyricsParser.findActiveLineIndex(parsed.lines, 20_000L))
        assertEquals(2, LyricsParser.findActiveLineIndex(parsed.lines, 65_000L))
    }

    @Test
    fun `maps jellyfin lyrics dto ticks to milliseconds`() {
        val dto = JellyfinLyricsResponseDto(
            lyrics = listOf(
                JellyfinLyricLineDto(text = "Line one", startTicks = 15_000_000L), // 1.5s
                JellyfinLyricLineDto(text = "Line two", startTicks = 45_500_000L), // 4.55s
            ),
        )
        val mapped = JellyfinLyricsMapper.map(dto)
        assertNotNull(mapped)
        assertTrue(mapped!!.isSynced)
        assertEquals(LyricsSource.JELLYFIN, mapped.source)
        assertEquals(1_500L, mapped.lines[0].startTimeMs)
        assertEquals(4_550L, mapped.lines[1].startTimeMs)
    }

    @Test
    fun `reads embedded uslt lyrics written by Id3v24TagWriter`() {
        val mp3File = File(tempFolder.root, "lyrics.mp3").apply {
            writeBytes(ByteArray(128) { 0x44 })
        }
        val lrc = "[00:05.00]Hello from ID3v2.4\n[00:10.00]Second synced line"
        val written = Id3v24TagWriter.write(
            file = mp3File,
            metadata = AudioTagMetadata(
                title = "Test",
                artist = "Artist",
                album = "Album",
                lyrics = lrc,
            ),
        )
        assertTrue(written)

        val read = mp3File.inputStream().use { EmbeddedLyricsReader.readFromStream(it) }
        assertNotNull(read)
        assertTrue(read!!.isSynced)
        assertEquals(2, read.lines.size)
        assertEquals("Hello from ID3v2.4", read.lines[0].text)
        assertEquals(5_000L, read.lines[0].startTimeMs)
    }

    @Test
    fun `reads embedded vorbis comment lyrics written by FlacTagWriter`() {
        val flacFile = File(tempFolder.root, "lyrics.flac").apply {
            val out = ByteArrayOutputStream()
            out.write("fLaC".toByteArray(Charsets.US_ASCII))
            out.write(0x80)
            out.write(0x00)
            out.write(0x00)
            out.write(34)
            out.write(ByteArray(34) { 0x01 })
            out.write(ByteArray(64) { 0x22 })
            writeBytes(out.toByteArray())
        }
        val plain = "Plain first line\nPlain second line"
        val written = FlacTagWriter.write(
            file = flacFile,
            metadata = AudioTagMetadata(
                title = "Test",
                artist = "Artist",
                album = "Album",
                lyrics = plain,
            ),
        )
        assertTrue(written)

        val read = flacFile.inputStream().use { EmbeddedLyricsReader.readFromStream(it) }
        assertNotNull(read)
        assertFalse(read!!.isSynced)
        assertEquals(listOf("Plain first line", "Plain second line"), read.lines.map { it.text })
    }
}
