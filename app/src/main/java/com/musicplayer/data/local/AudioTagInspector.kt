package com.musicplayer.data.local

import com.musicplayer.core.database.entity.TrackEntity
import com.musicplayer.core.model.DownloadQuality
import com.musicplayer.core.model.DownloadQualityMode
import com.musicplayer.core.model.Mp3Bitrate
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs

data class InspectedAudioTags(
    val trackId: String? = null,
    val qualityTag: String? = null,
    val audioSha256: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
)

data class ScannedMediaStoreAudio(
    val id: Long,
    val contentUri: String,
    val relativePath: String,
    val displayName: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val title: String?,
    val artist: String?,
    val album: String?,
    val dateModifiedSeconds: Long,
    val inspectedTags: InspectedAudioTags = InspectedAudioTags(),
) {
    val extension: String
        get() = displayName.substringAfterLast('.', "").lowercase(Locale.US)

    val isDuplicateNumberedName: Boolean
        get() = DUPLICATE_SUFFIX_REGEX.containsMatchIn(displayName.substringBeforeLast('.', displayName))

    val baseDisplayName: String
        get() {
            val dotIndex = displayName.lastIndexOf('.')
            if (dotIndex <= 0) return displayName
            val stem = displayName.substring(0, dotIndex)
            val ext = displayName.substring(dotIndex)
            val strippedStem = stem.replace(DUPLICATE_SUFFIX_REGEX, "").trimEnd()
            return "$strippedStem$ext"
        }

    companion object {
        private val DUPLICATE_SUFFIX_REGEX = Regex("\\s+\\(\\d+\\)$")
    }
}

data class DetectedFileQuality(
    val mode: DownloadQualityMode,
    val bitrateKbps: Int?,
)

/**
 * Lightweight streaming tag inspector and SHA-256 checksum utility for FLAC and MP3 files.
 * Reads only header metadata blocks when inspecting tags (fast even for large lossless files).
 */
object AudioTagInspector {

    const val TAG_TRACK_ID = "MUSICPLAYER_TRACK_ID"
    const val TAG_QUALITY = "MUSICPLAYER_QUALITY"
    const val TAG_AUDIO_SHA256 = "MUSICPLAYER_AUDIO_SHA256"

    private const val MAX_HEADER_BYTES = 16 * 1024 * 1024 // 16 MB safety cap

    fun formatQualityTag(quality: DownloadQuality): String = when (quality.mode) {
        DownloadQualityMode.LOSSLESS -> "LOSSLESS"
        DownloadQualityMode.MP3 -> "MP3:${quality.mp3Bitrate.kbps}"
    }

    fun parseQualityTag(raw: String?): DetectedFileQuality? {
        val cleaned = raw?.trim()?.uppercase(Locale.US)?.takeIf { it.isNotEmpty() } ?: return null
        if (cleaned == "LOSSLESS") {
            return DetectedFileQuality(DownloadQualityMode.LOSSLESS, null)
        }
        if (cleaned.startsWith("MP3:")) {
            val kbps = cleaned.substringAfter("MP3:").toIntOrNull()
            return DetectedFileQuality(DownloadQualityMode.MP3, kbps ?: Mp3Bitrate.KBPS_320.kbps)
        }
        return null
    }

    fun inferQuality(
        inspectedTags: InspectedAudioTags,
        extension: String,
        sizeBytes: Long,
        durationMs: Long,
    ): DetectedFileQuality {
        parseQualityTag(inspectedTags.qualityTag)?.let { return it }
        return when (extension.lowercase(Locale.US)) {
            "mp3" -> {
                val estimatedKbps = if (durationMs > 1_000L && sizeBytes > 0L) {
                    val rawKbps = ((sizeBytes * 8L) / durationMs).toInt()
                    Mp3Bitrate.entries.minByOrNull { abs(it.kbps - rawKbps) }?.kbps ?: 320
                } else {
                    320
                }
                DetectedFileQuality(DownloadQualityMode.MP3, estimatedKbps)
            }

            else -> DetectedFileQuality(DownloadQualityMode.LOSSLESS, null)
        }
    }

    fun computeSha256(file: File): String? = runCatching {
        file.inputStream().buffered(64 * 1024).use(::computeSha256)
    }.getOrNull()

    fun computeSha256(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun computeAudioPayloadSha256(file: File, extension: String): String? = runCatching {
        val skipOffset = when (extension.lowercase(Locale.US)) {
            "mp3" -> Id3v24TagWriter.findAudioStartOffset(file)
            "flac" -> findFlacAudioStartOffset(file)
            else -> 0L
        }
        file.inputStream().buffered(64 * 1024).use { input ->
            skipFully(input, skipOffset)
            computeSha256(input)
        }
    }.getOrNull()

    fun computeAudioPayloadSha256(stream: InputStream, extension: String): String? = runCatching {
        val buffered = BufferedInputStream(stream, 64 * 1024)
        buffered.mark(16)
        val probe = ByteArray(10)
        val probeRead = readFully(buffered, probe, 0, probe.size)
        buffered.reset()

        if (probeRead >= 4 && extension.equals("flac", ignoreCase = true) &&
            String(probe, 0, 4, Charsets.US_ASCII) == "fLaC"
        ) {
            skipFlacHeaders(buffered)
            return@runCatching computeSha256(buffered)
        }
        if (probeRead >= 10 && extension.equals("mp3", ignoreCase = true) &&
            probe[0] == 'I'.code.toByte() && probe[1] == 'D'.code.toByte() && probe[2] == '3'.code.toByte()
        ) {
            val flags = probe[5].toInt() and 0xFF
            val tagSize = Id3v24TagWriter.decodeSyncSafeInt(probe, 6)
            val footerSize = if ((flags and 0x10) != 0) 10 else 0
            skipFully(buffered, 10L + tagSize + footerSize)
            return@runCatching computeSha256(buffered)
        }
        computeSha256(buffered)
    }.getOrNull()

    fun inspect(file: File, extension: String): InspectedAudioTags = runCatching {
        file.inputStream().buffered(32 * 1024).use { inspect(it, extension) }
    }.getOrDefault(InspectedAudioTags())

    fun inspect(stream: InputStream, extension: String): InspectedAudioTags = runCatching {
        val buffered = BufferedInputStream(stream, 32 * 1024)
        buffered.mark(16)
        val head = ByteArray(10)
        val read = readFully(buffered, head, 0, head.size)
        buffered.reset()
        if (read < 4) return@runCatching InspectedAudioTags()

        val magic4 = String(head, 0, 4, Charsets.US_ASCII)
        when {
            magic4 == "fLaC" -> parseFlacComments(buffered)
            head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte() ->
                parseId3v2Tags(buffered)
            extension.equals("flac", ignoreCase = true) -> parseFlacComments(buffered)
            extension.equals("mp3", ignoreCase = true) -> parseId3v2Tags(buffered)
            else -> InspectedAudioTags()
        }
    }.getOrDefault(InspectedAudioTags())

    private fun parseFlacComments(stream: InputStream): InspectedAudioTags {
        val magic = ByteArray(4)
        if (readFully(stream, magic, 0, 4) < 4 || String(magic, Charsets.US_ASCII) != "fLaC") {
            return InspectedAudioTags()
        }

        var trackId: String? = null
        var qualityTag: String? = null
        var audioSha256: String? = null
        var title: String? = null
        var artist: String? = null
        var album: String? = null

        var isLast = false
        while (!isLast) {
            val header = ByteArray(4)
            if (readFully(stream, header, 0, 4) < 4) break
            val b0 = header[0].toInt() and 0xFF
            isLast = (b0 and 0x80) != 0
            val blockType = b0 and 0x7F
            val length = ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or
                (header[3].toInt() and 0xFF)

            if (blockType == 4 && length in 8..MAX_HEADER_BYTES) {
                val data = ByteArray(length)
                if (readFully(stream, data, 0, length) < length) break
                var offset = 0
                val vendorLen = readLeInt(data, offset)
                offset += 4
                if (vendorLen < 0 || offset + vendorLen + 4 > data.size) break
                offset += vendorLen
                val count = readLeInt(data, offset)
                offset += 4
                for (i in 0 until count) {
                    if (offset + 4 > data.size) break
                    val commentLen = readLeInt(data, offset)
                    offset += 4
                    if (commentLen < 0 || offset + commentLen > data.size) break
                    val comment = String(data, offset, commentLen, Charsets.UTF_8)
                    offset += commentLen
                    val eqIdx = comment.indexOf('=')
                    if (eqIdx > 0) {
                        val key = comment.substring(0, eqIdx).trim().uppercase(Locale.US)
                        val value = comment.substring(eqIdx + 1).trim()
                        when (key) {
                            TAG_TRACK_ID -> trackId = value
                            TAG_QUALITY -> qualityTag = value
                            TAG_AUDIO_SHA256 -> audioSha256 = value
                            "TITLE" -> title = value
                            "ARTIST" -> artist = value
                            "ALBUM" -> album = value
                        }
                    }
                }
                break
            } else {
                skipFully(stream, length.toLong())
            }
        }

        return InspectedAudioTags(
            trackId = trackId,
            qualityTag = qualityTag,
            audioSha256 = audioSha256,
            title = title,
            artist = artist,
            album = album,
        )
    }

    private fun parseId3v2Tags(stream: InputStream): InspectedAudioTags {
        val header = ByteArray(10)
        if (readFully(stream, header, 0, 10) < 10) return InspectedAudioTags()
        if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) {
            return InspectedAudioTags()
        }
        val majorVersion = header[3].toInt() and 0xFF
        val tagSize = Id3v24TagWriter.decodeSyncSafeInt(header, 6)
        if (tagSize <= 0 || tagSize > MAX_HEADER_BYTES) return InspectedAudioTags()

        val tagBytes = ByteArray(tagSize)
        val actual = readFully(stream, tagBytes, 0, tagSize)
        if (actual < 10) return InspectedAudioTags()

        var trackId: String? = null
        var qualityTag: String? = null
        var audioSha256: String? = null
        var title: String? = null
        var artist: String? = null
        var album: String? = null

        var pos = 0
        while (pos + 10 <= actual) {
            if (tagBytes[pos] == 0.toByte()) break // padding
            val frameId = String(tagBytes, pos, 4, Charsets.US_ASCII)
            val frameSize = if (majorVersion >= 4) {
                Id3v24TagWriter.decodeSyncSafeInt(tagBytes, pos + 4)
            } else {
                ((tagBytes[pos + 4].toInt() and 0xFF) shl 24) or
                    ((tagBytes[pos + 5].toInt() and 0xFF) shl 16) or
                    ((tagBytes[pos + 6].toInt() and 0xFF) shl 8) or
                    (tagBytes[pos + 7].toInt() and 0xFF)
            }
            pos += 10
            if (frameSize <= 0 || pos + frameSize > actual) break

            when (frameId) {
                "TIT2" -> title = decodeId3Text(tagBytes, pos, frameSize)
                "TPE1" -> artist = decodeId3Text(tagBytes, pos, frameSize)
                "TALB" -> album = decodeId3Text(tagBytes, pos, frameSize)
                "TXXX" -> {
                    val (desc, value) = decodeId3Txxx(tagBytes, pos, frameSize)
                    when (desc.uppercase(Locale.US)) {
                        TAG_TRACK_ID -> trackId = value
                        TAG_QUALITY -> qualityTag = value
                        TAG_AUDIO_SHA256 -> audioSha256 = value
                    }
                }
            }
            pos += frameSize
        }

        return InspectedAudioTags(
            trackId = trackId,
            qualityTag = qualityTag,
            audioSha256 = audioSha256,
            title = title,
            artist = artist,
            album = album,
        )
    }

    private fun decodeId3Text(bytes: ByteArray, offset: Int, length: Int): String? {
        if (length <= 1) return null
        val encoding = bytes[offset].toInt() and 0xFF
        val charset = id3Charset(encoding)
        return String(bytes, offset + 1, length - 1, charset)
            .trim('\u0000', ' ')
            .takeIf { it.isNotEmpty() }
    }

    private fun decodeId3Txxx(bytes: ByteArray, offset: Int, length: Int): Pair<String, String> {
        if (length <= 2) return "" to ""
        val encoding = bytes[offset].toInt() and 0xFF
        val charset = id3Charset(encoding)
        val isUtf16 = encoding == 1 || encoding == 2
        val start = offset + 1
        val end = offset + length

        var sepIndex = -1
        if (isUtf16) {
            var i = start
            while (i + 1 < end) {
                if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte()) {
                    sepIndex = i
                    break
                }
                i += 2
            }
        } else {
            for (i in start until end) {
                if (bytes[i] == 0.toByte()) {
                    sepIndex = i
                    break
                }
            }
        }
        if (sepIndex < 0) return "" to ""
        val sepLen = if (isUtf16) 2 else 1
        val desc = String(bytes, start, sepIndex - start, charset).trim('\u0000', ' ')
        val valStart = (sepIndex + sepLen).coerceAtMost(end)
        val value = String(bytes, valStart, end - valStart, charset).trim('\u0000', ' ')
        return desc to value
    }

    private fun id3Charset(encoding: Int) = when (encoding) {
        0 -> Charsets.ISO_8859_1
        1 -> Charsets.UTF_16
        2 -> Charsets.UTF_16BE
        else -> Charsets.UTF_8
    }

    private fun findFlacAudioStartOffset(file: File): Long = runCatching {
        file.inputStream().buffered(8192).use { input ->
            skipFlacHeaders(input)
        }
    }.getOrDefault(0L)

    private fun skipFlacHeaders(stream: InputStream): Long {
        val magic = ByteArray(4)
        if (readFully(stream, magic, 0, 4) < 4 || String(magic, Charsets.US_ASCII) != "fLaC") {
            return 0L
        }
        var total = 4L
        var isLast = false
        while (!isLast) {
            val header = ByteArray(4)
            if (readFully(stream, header, 0, 4) < 4) break
            total += 4
            val b0 = header[0].toInt() and 0xFF
            isLast = (b0 and 0x80) != 0
            val length = ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or
                (header[3].toInt() and 0xFF)
            skipFully(stream, length.toLong())
            total += length
        }
        return total
    }

    private fun readLeInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun readFully(stream: InputStream, buffer: ByteArray, offset: Int, length: Int): Int {
        var total = 0
        while (total < length) {
            val read = stream.read(buffer, offset + total, length - total)
            if (read <= 0) break
            total += read
        }
        return total
    }

    private fun skipFully(stream: InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                if (stream.read() < 0) break
                remaining--
            }
        }
    }
}

/**
 * Pure verification policy that matches existing files in `Music/music-player/` against Jellyfin
 * library tracks without false positives, supporting both embedded Track-ID tags and legacy files
 * from previous app installs.
 */
object OfflineFileVerifier {

    private const val MIN_VALID_AUDIO_BYTES = 4_096L
    private const val DURATION_TOLERANCE_MS = 3_000L

    fun verifyFileMatchesTrack(
        file: ScannedMediaStoreAudio,
        track: TrackEntity,
    ): Boolean {
        if (file.sizeBytes < MIN_VALID_AUDIO_BYTES) return false

        // 1. Duration sanity check when both durations are known
        if (file.durationMs > 0L && track.durationMs > 0L &&
            abs(file.durationMs - track.durationMs) > DURATION_TOLERANCE_MS
        ) {
            return false
        }

        // 2. Strongest verification: exact embedded MUSICPLAYER_TRACK_ID tag
        val embeddedTrackId = file.inspectedTags.trackId?.trim()
        if (!embeddedTrackId.isNullOrEmpty()) {
            return embeddedTrackId.equals(track.id, ignoreCase = true)
        }

        // 3. Fallback verification for legacy files (e.g. from an earlier install before trackId tag):
        // Require BOTH exact Music/music-player/<Artist>/<Album>/<Track> path AND metadata match.
        val expected = MediaStorePathBuilder.build(
            artistName = track.artistName,
            albumName = track.albumName,
            title = track.title,
            trackNumber = track.trackNumber,
            discNumber = track.discNumber,
            extension = file.extension.ifEmpty { "flac" },
        )
        if (!file.relativePath.equals(expected.relativePath, ignoreCase = true)) {
            return false
        }
        if (!file.baseDisplayName.equals(expected.displayName, ignoreCase = true)) {
            return false
        }

        val candidateTitle = file.inspectedTags.title ?: file.title
        if (!candidateTitle.isNullOrBlank() &&
            !candidateTitle.trim().equals(track.title.trim(), ignoreCase = true)
        ) {
            return false
        }

        val candidateArtist = file.inspectedTags.artist ?: file.artist
        if (!candidateArtist.isNullOrBlank() &&
            !candidateArtist.trim().equals(track.artistName.trim(), ignoreCase = true) &&
            !track.artistName.contains(candidateArtist.trim(), ignoreCase = true)
        ) {
            return false
        }

        return true
    }

    fun matchesRequestedQuality(
        file: ScannedMediaStoreAudio,
        requested: DownloadQuality,
    ): Boolean {
        val detected = AudioTagInspector.inferQuality(
            inspectedTags = file.inspectedTags,
            extension = file.extension,
            sizeBytes = file.sizeBytes,
            durationMs = file.durationMs,
        )
        return when (requested.mode) {
            DownloadQualityMode.LOSSLESS -> detected.mode == DownloadQualityMode.LOSSLESS
            DownloadQualityMode.MP3 -> detected.mode == DownloadQualityMode.MP3 &&
                (detected.bitrateKbps == null || detected.bitrateKbps == requested.mp3Bitrate.kbps)
        }
    }

    /**
     * Picks the best canonical file when multiple copies of the same track exist (e.g. `01 - Song.flac`
     * and `01 - Song (1).flac`), preferring non-numbered names, then embedded tags, then newest.
     */
    fun chooseCanonicalFile(candidates: List<ScannedMediaStoreAudio>): ScannedMediaStoreAudio? =
        candidates.minWithOrNull(
            compareBy<ScannedMediaStoreAudio> { it.isDuplicateNumberedName }
                .thenBy { it.inspectedTags.trackId.isNullOrBlank() }
                .thenByDescending { it.sizeBytes }
                .thenByDescending { it.dateModifiedSeconds },
        )
}
