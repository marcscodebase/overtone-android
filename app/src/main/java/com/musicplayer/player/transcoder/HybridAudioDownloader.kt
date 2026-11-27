package com.musicplayer.player.transcoder

import com.musicplayer.core.common.AppDispatcher
import com.musicplayer.core.common.Dispatcher
import com.musicplayer.core.model.DownloadQuality
import com.musicplayer.core.model.DownloadQualityMode
import com.musicplayer.core.model.DownloadStatus
import com.musicplayer.core.model.LyricsParser
import com.musicplayer.core.model.TranscodeSource
import com.musicplayer.data.local.AudioTagInspector
import com.musicplayer.data.local.AudioTagMetadata
import com.musicplayer.data.local.AudioTagWriter
import com.musicplayer.data.local.EmbeddedLyricsReader
import com.musicplayer.player.PlaybackOkHttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class HttpDownloadResult(
    val success: Boolean,
    val statusCode: Int,
    val contentType: String?,
    val bytesWritten: Long,
)

interface AudioHttpFetcher {
    suspend fun downloadToFile(
        url: String,
        destination: File,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): HttpDownloadResult

    suspend fun fetchBytes(url: String): ByteArray?
}

@Singleton
class OkHttpAudioFetcher @Inject constructor(
    @param:PlaybackOkHttpClient private val okHttpClient: OkHttpClient,
    @param:Dispatcher(AppDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : AudioHttpFetcher {

    override suspend fun downloadToFile(
        url: String,
        destination: File,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
    ): HttpDownloadResult = withContext(ioDispatcher) {
        val request = Request.Builder().url(url).get().build()
        val call = okHttpClient.newCall(request)
        val completionHandle = currentCoroutineContext()[kotlinx.coroutines.Job]?.invokeOnCompletion { cause ->
            if (cause != null) call.cancel()
        }
        try {
            call.execute().use { response ->
                val contentType = response.header("Content-Type")
                if (!response.isSuccessful) {
                    return@withContext HttpDownloadResult(
                        success = false,
                        statusCode = response.code,
                        contentType = contentType,
                        bytesWritten = 0L,
                    )
                }
                val body = response.body ?: return@withContext HttpDownloadResult(
                    success = false,
                    statusCode = response.code,
                    contentType = contentType,
                    bytesWritten = 0L,
                )
                val totalBytes = body.contentLength()
                var bytesWritten = 0L
                destination.parentFile?.mkdirs()
                destination.outputStream().buffered(64 * 1024).use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            bytesWritten += read
                            onProgress(bytesWritten, totalBytes)
                        }
                    }
                }
                HttpDownloadResult(
                    success = bytesWritten > 0L,
                    statusCode = response.code,
                    contentType = contentType,
                    bytesWritten = bytesWritten,
                )
            }
        } finally {
            completionHandle?.dispose()
        }
    }

    override suspend fun fetchBytes(url: String): ByteArray? = withContext(ioDispatcher) {
        runCatching {
            val request = Request.Builder().url(url).get().build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.bytes()?.takeIf { it.isNotEmpty() }
            }
        }.getOrNull()
    }
}

object AudioFormatSniffer {

    fun looksLikeMp3(file: File): Boolean {
        if (!file.exists() || file.length() < 128L) return false
        val probeLen = minOf(file.length(), 4096L).toInt()
        val head = ByteArray(probeLen)
        val read = runCatching {
            RandomAccessFile(file, "r").use { it.read(head) }
        }.getOrDefault(0)
        if (read < 4) return false

        // Reject known non-MP3 containers and HTML/JSON error payloads
        val ascii4 = String(head, 0, 4, Charsets.US_ASCII)
        if (ascii4 == "fLaC" || ascii4 == "RIFF" || ascii4 == "OggS") return false
        if (read >= 8 && String(head, 4, 4, Charsets.US_ASCII) == "ftyp") return false
        val trimmedHead = String(head, 0, minOf(read, 16), Charsets.US_ASCII).trimStart()
        if (trimmedHead.startsWith("<") || trimmedHead.startsWith("{") || trimmedHead.startsWith("[")) {
            return false
        }

        // Accept ID3v2 header
        if (head[0] == 'I'.code.toByte() &&
            head[1] == 'D'.code.toByte() &&
            head[2] == '3'.code.toByte()
        ) {
            return true
        }

        // Scan for a valid MPEG Layer III frame sync header
        for (i in 0 until read - 3) {
            if (isValidMp3FrameHeader(head, i)) return true
        }
        return false
    }

    private fun isValidMp3FrameHeader(bytes: ByteArray, offset: Int): Boolean {
        val b0 = bytes[offset].toInt() and 0xFF
        val b1 = bytes[offset + 1].toInt() and 0xFF
        val b2 = bytes[offset + 2].toInt() and 0xFF
        if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return false
        val versionBits = (b1 shr 3) and 0x03
        val layerBits = (b1 shr 1) and 0x03
        val bitrateIndex = (b2 shr 4) and 0x0F
        val sampleRateIndex = (b2 shr 2) and 0x03
        return versionBits != 0x01 && // reserved version
            layerBits == 0x01 &&      // Layer III (MP3)
            bitrateIndex != 0x00 &&   // free
            bitrateIndex != 0x0F &&   // invalid
            sampleRateIndex != 0x03   // reserved
    }

    fun detectImageMimeType(bytes: ByteArray): String {
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() &&
            bytes[3] == 'G'.code.toByte()
        ) {
            return "image/png"
        }
        if (bytes.size >= 12 &&
            String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
        ) {
            return "image/webp"
        }
        return "image/jpeg"
    }

    fun resolveExtension(containerHint: String?, contentType: String?): String {
        val fromHint = containerHint
            ?.substringBefore(',')
            ?.trim()
            ?.trimStart('.')
            ?.lowercase(Locale.US)
            ?.takeIf { it.matches(SAFE_EXTENSION_REGEX) }
        if (fromHint != null) return fromHint

        val mime = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.US)
        return when (mime) {
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/mp4", "audio/m4a", "audio/aac", "audio/x-m4a" -> "m4a"
            "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
            "audio/aiff", "audio/x-aiff" -> "aiff"
            "audio/opus" -> "opus"
            "audio/ogg" -> "ogg"
            else -> "flac"
        }
    }

    private val SAFE_EXTENSION_REGEX = Regex("^[a-z0-9]{1,5}$")
}

data class HybridDownloadRequest(
    val trackId: String,
    val baseUrl: String,
    val userId: String? = null,
    val deviceId: String? = null,
    val title: String,
    val artist: String,
    val albumArtist: String? = null,
    val album: String,
    val trackNumber: Int?,
    val discNumber: Int?,
    val year: Int?,
    val containerHint: String?,
    val sourceBitrateKbps: Int?,
    val replayGainTrackDb: Float?,
    val lyrics: String? = null,
    val artworkItemId: String?,
    val quality: DownloadQuality,
)

data class HybridDownloadResult(
    val file: File,
    val extension: String,
    val transcodeSource: TranscodeSource,
    val bitrateKbps: Int?,
    val fileSizeBytes: Long,
)

@Singleton
class HybridAudioDownloader @Inject constructor(
    private val httpFetcher: AudioHttpFetcher,
    private val onDeviceMp3Encoder: OnDeviceMp3Encoder,
    private val audioTagWriter: AudioTagWriter,
) {
    suspend fun downloadAndPrepare(
        request: HybridDownloadRequest,
        workDir: File,
        onStatusChange: suspend (DownloadStatus) -> Unit = {},
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): HybridDownloadResult {
        workDir.mkdirs()
        val cleanBase = request.baseUrl.trimEnd('/')

        var resolvedLyrics = request.lyrics?.takeIf { it.isNotBlank() }

        val (preparedFile, extension, transcodeSource, bitrateKbps) = when (request.quality.mode) {
            DownloadQualityMode.LOSSLESS -> {
                onStatusChange(DownloadStatus.DOWNLOADING)
                val (file, contentType) = downloadOriginal(
                    cleanBase = cleanBase,
                    trackId = request.trackId,
                    workDir = workDir,
                    onProgress = onProgress,
                )
                val ext = AudioFormatSniffer.resolveExtension(request.containerHint, contentType)
                PreparedAudio(
                    file = file,
                    extension = ext,
                    transcodeSource = TranscodeSource.ORIGINAL,
                    bitrateKbps = request.sourceBitrateKbps,
                )
            }

            DownloadQualityMode.MP3 -> {
                val targetKbps = request.quality.mp3Bitrate.kbps
                onStatusChange(DownloadStatus.DOWNLOADING)
                val safeTempId = sanitizeTempFileId(request.trackId)
                val serverFile = File(workDir, "${safeTempId}_server.mp3")
                val serverUrl = buildServerMp3Url(
                    cleanBase = cleanBase,
                    trackId = request.trackId,
                    bitrateKbps = targetKbps,
                    userId = request.userId,
                    deviceId = request.deviceId,
                )

                val serverResult = try {
                    httpFetcher.downloadToFile(serverUrl, serverFile, onProgress)
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    serverFile.delete()
                    throw ce
                } catch (_: Throwable) {
                    null
                }

                if (serverResult != null &&
                    serverResult.success &&
                    AudioFormatSniffer.looksLikeMp3(serverFile)
                ) {
                    PreparedAudio(
                        file = serverFile,
                        extension = "mp3",
                        transcodeSource = TranscodeSource.SERVER,
                        bitrateKbps = targetKbps,
                    )
                } else {
                    serverFile.delete()
                    // Fallback: download untouched original and encode to MP3 on-device
                    val (originalFile, _) = downloadOriginal(
                        cleanBase = cleanBase,
                        trackId = request.trackId,
                        workDir = workDir,
                        onProgress = onProgress,
                    )
                    try {
                        if (resolvedLyrics == null) {
                            resolvedLyrics = runCatching {
                                originalFile.inputStream().buffered().use { stream ->
                                    EmbeddedLyricsReader.readFromStream(stream)
                                }
                            }.getOrNull()?.let(LyricsParser::formatForEmbedding)
                        }
                        onStatusChange(DownloadStatus.TRANSCODING)
                        val encodedFile = File(workDir, "${safeTempId}_encoded.mp3")
                        val encoded = onDeviceMp3Encoder.encodeToMp3(
                            sourceFile = originalFile,
                            targetMp3File = encodedFile,
                            bitrateKbps = targetKbps,
                        )
                        if (!encoded || !AudioFormatSniffer.looksLikeMp3(encodedFile)) {
                            encodedFile.delete()
                            throw IOException("On-device MP3 fallback encoding failed")
                        }
                        PreparedAudio(
                            file = encodedFile,
                            extension = "mp3",
                            transcodeSource = TranscodeSource.ON_DEVICE,
                            bitrateKbps = targetKbps,
                        )
                    } finally {
                        originalFile.delete()
                    }
                }
            }
        }

        val coverArtBytes = request.artworkItemId?.takeIf { it.isNotBlank() }?.let { artId ->
            httpFetcher.fetchBytes("$cleanBase/Items/$artId/Images/Primary?maxWidth=800&quality=90")
        }
        val coverMime = coverArtBytes?.let { AudioFormatSniffer.detectImageMimeType(it) } ?: "image/jpeg"

        val audioPayloadSha256 = AudioTagInspector.computeAudioPayloadSha256(preparedFile, extension)
        audioTagWriter.writeTags(
            file = preparedFile,
            extension = extension,
            metadata = AudioTagMetadata(
                title = request.title,
                artist = request.artist,
                albumArtist = request.albumArtist,
                album = request.album,
                trackNumber = request.trackNumber,
                discNumber = request.discNumber,
                year = request.year,
                replayGainTrackDb = request.replayGainTrackDb,
                lyrics = resolvedLyrics,
                coverArtBytes = coverArtBytes,
                coverArtMimeType = coverMime,
                trackId = request.trackId,
                qualityTag = AudioTagInspector.formatQualityTag(request.quality),
                audioSha256 = audioPayloadSha256,
            ),
        )

        return HybridDownloadResult(
            file = preparedFile,
            extension = extension,
            transcodeSource = transcodeSource,
            bitrateKbps = bitrateKbps,
            fileSizeBytes = preparedFile.length(),
        )
    }

    private suspend fun downloadOriginal(
        cleanBase: String,
        trackId: String,
        workDir: File,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
    ): Pair<File, String?> {
        val originalFile = File(workDir, "${sanitizeTempFileId(trackId)}_original.tmp")
        val primaryUrl = "$cleanBase/Items/$trackId/Download"
        val primary = try {
            httpFetcher.downloadToFile(primaryUrl, originalFile, onProgress)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            originalFile.delete()
            throw ce
        } catch (_: Throwable) {
            null
        }

        if (primary != null && primary.success) {
            return originalFile to primary.contentType
        }
        originalFile.delete()

        val fallbackUrl = "$cleanBase/Audio/$trackId/stream?static=true"
        val fallback = httpFetcher.downloadToFile(fallbackUrl, originalFile, onProgress)
        if (!fallback.success) {
            originalFile.delete()
            throw IOException("Failed to download original audio (HTTP ${fallback.statusCode})")
        }
        return originalFile to fallback.contentType
    }

    private fun sanitizeTempFileId(rawId: String): String =
        rawId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64).ifEmpty { "track" }

    private fun buildServerMp3Url(
        cleanBase: String,
        trackId: String,
        bitrateKbps: Int,
        userId: String?,
        deviceId: String?,
    ): String {
        val maxBitrateBps = bitrateKbps * 1000
        val params = buildList {
            if (!userId.isNullOrBlank()) add("userId=$userId")
            if (!deviceId.isNullOrBlank()) add("deviceId=$deviceId")
            add("container=mp3")
            add("audioCodec=mp3")
            add("maxStreamingBitrate=$maxBitrateBps")
            add("transcodingContainer=mp3")
            add("transcodingProtocol=http")
        }.joinToString("&")
        return "$cleanBase/Audio/$trackId/universal?$params"
    }

    private data class PreparedAudio(
        val file: File,
        val extension: String,
        val transcodeSource: TranscodeSource,
        val bitrateKbps: Int?,
    )
}
