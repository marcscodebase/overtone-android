package com.musicplayer.player.transcoder

import com.musicplayer.core.model.DownloadQuality
import com.musicplayer.core.model.DownloadQualityMode
import com.musicplayer.core.model.DownloadStatus
import com.musicplayer.core.model.Mp3Bitrate
import com.musicplayer.core.model.TranscodeSource
import com.musicplayer.data.local.AudioTagWriter
import com.musicplayer.data.local.EmbeddedLyricsReader
import com.musicplayer.data.local.FlacTagWriter
import com.musicplayer.data.local.AudioTagMetadata
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

class HybridAudioDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `pcm mp3 encoder produces valid mp3 frames from 16-bit stereo sine wave`() {
        val sampleRate = 44100
        val channels = 2
        val totalFrames = sampleRate / 2 // 0.5 seconds
        val pcmBytes = ByteArray(totalFrames * channels * 2)
        var offset = 0
        for (i in 0 until totalFrames) {
            val angle = 2.0 * PI * 440.0 * i / sampleRate
            val sample = (sin(angle) * 16000.0).toInt().toShort()
            // Left
            pcmBytes[offset++] = (sample.toInt() and 0xFF).toByte()
            pcmBytes[offset++] = ((sample.toInt() shr 8) and 0xFF).toByte()
            // Right
            pcmBytes[offset++] = (sample.toInt() and 0xFF).toByte()
            pcmBytes[offset++] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }

        val outFile = File(tempFolder.root, "encoded_sine.mp3")
        PcmMp3Encoder(sampleRateHz = sampleRate, channelCount = channels, bitrateKbps = 192).use { encoder ->
            outFile.outputStream().use { out ->
                encoder.encodePcm16Le(pcmBytes, output = out)
                encoder.flush(out)
            }
        }

        assertTrue("Encoded MP3 should be non-empty", outFile.length() > 1000L)
        assertTrue("Encoded MP3 should pass MP3 format sniffer", AudioFormatSniffer.looksLikeMp3(outFile))
    }

    @Test
    fun `high res sample rates map cleanly to valid mpeg1 sample rates`() {
        assertEquals(48000, PcmMp3Encoder.resolveOutputSampleRate(96000))
        assertEquals(44100, PcmMp3Encoder.resolveOutputSampleRate(88200))
        assertEquals(44100, PcmMp3Encoder.resolveOutputSampleRate(44100))
        assertEquals(48000, PcmMp3Encoder.resolveOutputSampleRate(48000))
    }

    @Test
    fun `sniffer rejects flac and html payloads and accepts mp3`() {
        val flacFile = File(tempFolder.root, "sample.flac").apply {
            writeBytes("fLaC".toByteArray(Charsets.US_ASCII) + ByteArray(256))
        }
        val htmlFile = File(tempFolder.root, "error.html").apply {
            writeBytes("<html><body>500 Error</body></html>".toByteArray() + ByteArray(200))
        }
        val mp3File = File(tempFolder.root, "sample.mp3").apply {
            writeBytes(fakeMp3Bytes())
        }

        assertFalse(AudioFormatSniffer.looksLikeMp3(flacFile))
        assertFalse(AudioFormatSniffer.looksLikeMp3(htmlFile))
        assertTrue(AudioFormatSniffer.looksLikeMp3(mp3File))
    }

    @Test
    fun `lossless mode downloads original file and tags flac`() = runTest {
        val fakeFetcher = FakeAudioHttpFetcher(
            responses = mapOf(
                "https://jellyfin.local/Items/t1/Download" to FakeResponse(
                    bytes = minimalFlacBytes(),
                    contentType = "audio/flac",
                ),
            ),
            artworkBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()),
        )
        val fakeEncoder = FakeOnDeviceMp3Encoder()
        val downloader = HybridAudioDownloader(fakeFetcher, fakeEncoder, AudioTagWriter())

        val result = downloader.downloadAndPrepare(
            request = sampleRequest(DownloadQuality(DownloadQualityMode.LOSSLESS)),
            workDir = tempFolder.newFolder("lossless"),
        )

        assertEquals(TranscodeSource.ORIGINAL, result.transcodeSource)
        assertEquals("flac", result.extension)
        assertEquals(0, fakeEncoder.invocations)
        assertTrue(result.file.exists())
        assertTrue(String(result.file.readBytes(), Charsets.UTF_8).contains("TITLE=Everything In Its Right Place"))
    }

    @Test
    fun `mp3 mode uses server transcoding when server returns valid mp3`() = runTest {
        val fakeFetcher = FakeAudioHttpFetcher(
            responses = mapOf(
                "https://jellyfin.local/Audio/t1/universal?container=mp3&audioCodec=mp3&maxStreamingBitrate=320000&transcodingContainer=mp3&transcodingProtocol=http" to FakeResponse(
                    bytes = fakeMp3Bytes(),
                    contentType = "audio/mpeg",
                ),
            ),
        )
        val fakeEncoder = FakeOnDeviceMp3Encoder()
        val downloader = HybridAudioDownloader(fakeFetcher, fakeEncoder, AudioTagWriter())

        val result = downloader.downloadAndPrepare(
            request = sampleRequest(DownloadQuality(DownloadQualityMode.MP3, Mp3Bitrate.KBPS_320)),
            workDir = tempFolder.newFolder("server_mp3"),
        )

        assertEquals(TranscodeSource.SERVER, result.transcodeSource)
        assertEquals("mp3", result.extension)
        assertEquals(320, result.bitrateKbps)
        assertEquals(0, fakeEncoder.invocations)
        assertTrue(String(result.file.readBytes(), 0, 3, Charsets.US_ASCII) == "ID3")
    }

    @Test
    fun `mp3 mode falls back to on-device encoder when server returns non-mp3 or fails`() = runTest {
        val fakeFetcher = FakeAudioHttpFetcher(
            responses = mapOf(
                // Server ignores transcode params and returns raw FLAC
                "https://jellyfin.local/Audio/t1/universal?container=mp3&audioCodec=mp3&maxStreamingBitrate=192000&transcodingContainer=mp3&transcodingProtocol=http" to FakeResponse(
                    bytes = minimalFlacBytes(),
                    contentType = "audio/flac",
                ),
                "https://jellyfin.local/Items/t1/Download" to FakeResponse(
                    bytes = minimalFlacBytes(),
                    contentType = "audio/flac",
                ),
            ),
        )
        val fakeEncoder = FakeOnDeviceMp3Encoder()
        val statuses = mutableListOf<DownloadStatus>()
        val downloader = HybridAudioDownloader(fakeFetcher, fakeEncoder, AudioTagWriter())

        val result = downloader.downloadAndPrepare(
            request = sampleRequest(DownloadQuality(DownloadQualityMode.MP3, Mp3Bitrate.KBPS_192)),
            workDir = tempFolder.newFolder("fallback_mp3"),
            onStatusChange = { statuses += it },
        )

        assertEquals(TranscodeSource.ON_DEVICE, result.transcodeSource)
        assertEquals("mp3", result.extension)
        assertEquals(192, result.bitrateKbps)
        assertEquals(1, fakeEncoder.invocations)
        assertEquals(192, fakeEncoder.lastBitrateKbps)
        assertEquals(listOf(DownloadStatus.DOWNLOADING, DownloadStatus.TRANSCODING), statuses)
    }

    @Test
    fun `embeds request lyrics into downloaded mp3 and flac files`() = runTest {
        val syncedLrc = "[00:05.00]Yesterday I woke up sucking a lemon\n[00:10.00]Everything in its right place"
        val fakeFetcher = FakeAudioHttpFetcher(
            responses = mapOf(
                "https://jellyfin.local/Audio/t1/universal?container=mp3&audioCodec=mp3&maxStreamingBitrate=320000&transcodingContainer=mp3&transcodingProtocol=http" to FakeResponse(
                    bytes = fakeMp3Bytes(),
                    contentType = "audio/mpeg",
                ),
            ),
        )
        val downloader = HybridAudioDownloader(fakeFetcher, FakeOnDeviceMp3Encoder(), AudioTagWriter())

        val result = downloader.downloadAndPrepare(
            request = sampleRequest(DownloadQuality(DownloadQualityMode.MP3, Mp3Bitrate.KBPS_320))
                .copy(lyrics = syncedLrc),
            workDir = tempFolder.newFolder("mp3_with_lyrics"),
        )

        val embedded = result.file.inputStream().use { EmbeddedLyricsReader.readFromStream(it) }
        assertNotNull(embedded)
        assertTrue(embedded!!.isSynced)
        assertEquals(2, embedded.lines.size)
        assertEquals("Yesterday I woke up sucking a lemon", embedded.lines[0].text)
        assertEquals(5_000L, embedded.lines[0].startTimeMs)
    }

    @Test
    fun `on-device mp3 fallback carries over embedded lyrics from original flac when api lyrics absent`() = runTest {
        val sourceFlac = tempFolder.newFile("source_with_lyrics.flac").apply {
            writeBytes(minimalFlacBytes())
            FlacTagWriter.write(
                file = this,
                metadata = AudioTagMetadata(
                    title = "Everything In Its Right Place",
                    artist = "Radiohead",
                    album = "Kid A",
                    lyrics = "[00:04.50]Embedded in original FLAC",
                ),
            )
        }
        val fakeFetcher = FakeAudioHttpFetcher(
            responses = mapOf(
                "https://jellyfin.local/Items/t1/Download" to FakeResponse(
                    bytes = sourceFlac.readBytes(),
                    contentType = "audio/flac",
                ),
            ),
        )
        val downloader = HybridAudioDownloader(fakeFetcher, FakeOnDeviceMp3Encoder(), AudioTagWriter())

        val result = downloader.downloadAndPrepare(
            request = sampleRequest(DownloadQuality(DownloadQualityMode.MP3, Mp3Bitrate.KBPS_256)),
            workDir = tempFolder.newFolder("fallback_lyrics_carryover"),
        )

        assertEquals(TranscodeSource.ON_DEVICE, result.transcodeSource)
        val embedded = result.file.inputStream().use { EmbeddedLyricsReader.readFromStream(it) }
        assertNotNull(embedded)
        assertTrue(embedded!!.isSynced)
        assertEquals("Embedded in original FLAC", embedded.lines.single().text)
        assertEquals(4_500L, embedded.lines.single().startTimeMs)
    }

    private fun sampleRequest(quality: DownloadQuality) = HybridDownloadRequest(
        trackId = "t1",
        baseUrl = "https://jellyfin.local/",
        title = "Everything In Its Right Place",
        artist = "Radiohead",
        album = "Kid A",
        trackNumber = 1,
        discNumber = 1,
        year = 2000,
        containerHint = "flac",
        sourceBitrateKbps = 950,
        replayGainTrackDb = -5.4f,
        artworkItemId = "album1",
        quality = quality,
    )

    private fun fakeMp3Bytes(): ByteArray {
        val out = ByteArrayOutputStream()
        // Valid MPEG-1 Layer III 128kbps 44.1kHz frame sync header: FF FB 90 00
        out.write(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00))
        out.write(ByteArray(256) { 0x22 })
        return out.toByteArray()
    }

    private fun minimalFlacBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.US_ASCII))
        // Last metadata block: STREAMINFO (type 0), length 34
        out.write(0x80)
        out.write(0x00)
        out.write(0x00)
        out.write(34)
        out.write(ByteArray(34) { 0x01 })
        out.write(ByteArray(128) { 0x55 })
        return out.toByteArray()
    }

    private data class FakeResponse(
        val bytes: ByteArray,
        val contentType: String,
    )

    private class FakeAudioHttpFetcher(
        private val responses: Map<String, FakeResponse>,
        private val artworkBytes: ByteArray? = null,
    ) : AudioHttpFetcher {
        override suspend fun downloadToFile(
            url: String,
            destination: File,
            onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
        ): HttpDownloadResult {
            val response = responses[url] ?: return HttpDownloadResult(
                success = false,
                statusCode = 404,
                contentType = null,
                bytesWritten = 0L,
            )
            destination.parentFile?.mkdirs()
            destination.writeBytes(response.bytes)
            onProgress(response.bytes.size.toLong(), response.bytes.size.toLong())
            return HttpDownloadResult(
                success = true,
                statusCode = 200,
                contentType = response.contentType,
                bytesWritten = response.bytes.size.toLong(),
            )
        }

        override suspend fun fetchBytes(url: String): ByteArray? = artworkBytes
    }

    private class FakeOnDeviceMp3Encoder : OnDeviceMp3Encoder {
        var invocations = 0
        var lastBitrateKbps: Int? = null

        override suspend fun encodeToMp3(
            sourceFile: File,
            targetMp3File: File,
            bitrateKbps: Int,
        ): Boolean {
            invocations++
            lastBitrateKbps = bitrateKbps
            val bytes = ByteArrayOutputStream().apply {
                write(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00))
                write(ByteArray(256) { 0x33 })
            }.toByteArray()
            targetMp3File.writeBytes(bytes)
            return true
        }
    }
}
