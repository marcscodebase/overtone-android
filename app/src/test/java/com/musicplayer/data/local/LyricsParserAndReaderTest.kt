package com.musicplayer.data.local

import com.musicplayer.core.model.LyricLine
import com.musicplayer.core.model.LyricsParser
import com.musicplayer.core.model.LyricsSource
import com.musicplayer.core.model.TrackLyrics
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
    fun `leadTimeMs starts transition early so animation completes on lyric timestamp`() {
        val raw = """
            [00:10.00]First line
            [00:15.00]Second line
            [00:20.00]Third line
        """.trimIndent()

        val parsed = LyricsParser.parse(raw, LyricsSource.EMBEDDED)!!
        val leadMs = 220L

        // Just before lead window of line 2 (15_000 - 220 = 14_780) -> still line 0
        assertEquals(0, LyricsParser.findActiveLineIndex(parsed.lines, 14_779L, leadTimeMs = leadMs))
        // Exactly leadMs before line 2 -> switches to line 1 so 220ms animation ends at 15_000
        assertEquals(1, LyricsParser.findActiveLineIndex(parsed.lines, 14_780L, leadTimeMs = leadMs))
        // Exactly at timestamp -> still line 1
        assertEquals(1, LyricsParser.findActiveLineIndex(parsed.lines, 15_000L, leadTimeMs = leadMs))
        // Lead window of line 3 (20_000 - 220 = 19_780) -> switches to line 2
        assertEquals(2, LyricsParser.findActiveLineIndex(parsed.lines, 19_780L, leadTimeMs = leadMs))
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

    @Test
    fun `formats synced and plain lyrics cleanly for embedding and round trips`() {
        val synced = TrackLyrics(
            lines = listOf(
                LyricLine("First synced line", startTimeMs = 12_500L),
                LyricLine("Late track line", startTimeMs = 6_305_250L), // 105:05.25
            ),
            isSynced = true,
            source = LyricsSource.JELLYFIN,
        )
        val lrc = LyricsParser.formatForEmbedding(synced)
        assertEquals("[00:12.50]First synced line\n[105:05.25]Late track line", lrc)

        val reparsed = LyricsParser.parse(lrc!!, LyricsSource.EMBEDDED)
        assertNotNull(reparsed)
        assertTrue(reparsed!!.isSynced)
        assertEquals(listOf(12_500L, 6_305_250L), reparsed.lines.map { it.startTimeMs })
        assertEquals(listOf("First synced line", "Late track line"), reparsed.lines.map { it.text })

        val plain = TrackLyrics(
            lines = listOf(LyricLine("Line A"), LyricLine("Line B")),
            isSynced = false,
            source = LyricsSource.JELLYFIN,
        )
        assertEquals("Line A\nLine B", LyricsParser.formatForEmbedding(plain))
    }
}
