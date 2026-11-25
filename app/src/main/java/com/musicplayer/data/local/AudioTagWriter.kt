package com.musicplayer.data.local

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class AudioTagMetadata(
    val title: String,
    val artist: String,
    val albumArtist: String? = null,
    val album: String,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val replayGainTrackDb: Float? = null,
    val lyrics: String? = null,
    val coverArtBytes: ByteArray? = null,
    val coverArtMimeType: String = "image/jpeg",
    val trackId: String? = null,
    val qualityTag: String? = null,
    val audioSha256: String? = null,
)

@Singleton
class AudioTagWriter @Inject constructor() {

    fun writeTags(
        file: File,
        extension: String,
        metadata: AudioTagMetadata,
    ): Boolean {
        return when (extension.trim().lowercase(Locale.US)) {
            "mp3" -> Id3v24TagWriter.write(file, metadata)
            "flac" -> FlacTagWriter.write(file, metadata)
            else -> true
        }
    }
}

/**
 * Spec-compliant ID3v2.4.0 tag writer for MP3 files with UTF-8 text frames, ReplayGain,
 * unsynchronized lyrics, and embedded APIC album artwork.
 */
object Id3v24TagWriter {

    private const val ENCODING_UTF8: Byte = 0x03

    fun write(file: File, metadata: AudioTagMetadata): Boolean {
        if (!file.exists()) return false
        val audioOffset = findAudioStartOffset(file)
        val tagBytes = buildId3v24Header(metadata)

        val tempFile = File(file.parentFile, "${file.name}.tagtmp")
        return try {
            tempFile.outputStream().buffered().use { out ->
                out.write(tagBytes)
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(audioOffset)
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = raf.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                    }
                }
            }
            if (!tempFile.renameTo(file)) {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
            }
            true
        } catch (_: Throwable) {
            tempFile.delete()
            false
        }
    }

    internal fun findAudioStartOffset(file: File): Long {
        if (file.length() < 10) return 0L
        return RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(10)
            if (raf.read(header) < 10) return@use 0L
            if (header[0] == 'I'.code.toByte() &&
                header[1] == 'D'.code.toByte() &&
                header[2] == '3'.code.toByte()
            ) {
                val flags = header[5].toInt() and 0xFF
                val tagSize = decodeSyncSafeInt(header, 6)
                val footerSize = if ((flags and 0x10) != 0) 10 else 0
                (10L + tagSize + footerSize).coerceAtMost(file.length())
            } else {
                0L
            }
        }
    }

    internal fun buildId3v24Header(metadata: AudioTagMetadata): ByteArray {
        val frames = ByteArrayOutputStream()

        writeTextFrame(frames, "TIT2", metadata.title)
        writeTextFrame(frames, "TPE1", metadata.artist)
        writeTextFrame(frames, "TPE2", metadata.albumArtist ?: metadata.artist)
        writeTextFrame(frames, "TALB", metadata.album)
        metadata.trackNumber?.takeIf { it > 0 }?.let { writeTextFrame(frames, "TRCK", it.toString()) }
        metadata.discNumber?.takeIf { it > 0 }?.let { writeTextFrame(frames, "TPOS", it.toString()) }
        metadata.year?.takeIf { it > 0 }?.let { writeTextFrame(frames, "TDRC", it.toString()) }
        metadata.replayGainTrackDb?.let { gain ->
            writeTxxxFrame(
                out = frames,
                description = "REPLAYGAIN_TRACK_GAIN",
                value = String.format(Locale.US, "%.2f dB", gain),
            )
        }
        metadata.trackId?.takeIf { it.isNotBlank() }?.let { id ->
            writeTxxxFrame(frames, AudioTagInspector.TAG_TRACK_ID, id)
        }
        metadata.qualityTag?.takeIf { it.isNotBlank() }?.let { quality ->
            writeTxxxFrame(frames, AudioTagInspector.TAG_QUALITY, quality)
        }
        metadata.audioSha256?.takeIf { it.isNotBlank() }?.let { sha ->
            writeTxxxFrame(frames, AudioTagInspector.TAG_AUDIO_SHA256, sha)
        }
        metadata.lyrics?.takeIf { it.isNotBlank() }?.let { lyrics ->
            writeUsltFrame(frames, lyrics)
        }
        metadata.coverArtBytes?.takeIf { it.isNotEmpty() }?.let { art ->
            writeApicFrame(frames, art, metadata.coverArtMimeType)
        }

        val frameBytes = frames.toByteArray()
        val result = ByteArrayOutputStream(10 + frameBytes.size)
        result.write("ID3".toByteArray(Charsets.US_ASCII))
        result.write(4) // ID3v2.4
        result.write(0) // minor version
        result.write(0) // flags
        result.write(encodeSyncSafeInt(frameBytes.size))
        result.write(frameBytes)
        return result.toByteArray()
    }

    private fun writeTextFrame(out: ByteArrayOutputStream, frameId: String, text: String) {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val payload = ByteArray(1 + textBytes.size).apply {
            this[0] = ENCODING_UTF8
            System.arraycopy(textBytes, 0, this, 1, textBytes.size)
        }
        writeFrame(out, frameId, payload)
    }

    private fun writeTxxxFrame(out: ByteArrayOutputStream, description: String, value: String) {
        val descBytes = description.toByteArray(Charsets.UTF_8)
        val valBytes = value.toByteArray(Charsets.UTF_8)
        val payload = ByteArrayOutputStream(2 + descBytes.size + valBytes.size).apply {
            write(ENCODING_UTF8.toInt())
            write(descBytes)
            write(0)
            write(valBytes)
        }.toByteArray()
        writeFrame(out, "TXXX", payload)
    }

    private fun writeUsltFrame(out: ByteArrayOutputStream, lyrics: String) {
        val lyricsBytes = lyrics.toByteArray(Charsets.UTF_8)
        val payload = ByteArrayOutputStream(5 + lyricsBytes.size).apply {
            write(ENCODING_UTF8.toInt())
            write("eng".toByteArray(Charsets.US_ASCII))
            write(0) // empty content descriptor
            write(lyricsBytes)
        }.toByteArray()
        writeFrame(out, "USLT", payload)
    }

    private fun writeApicFrame(
        out: ByteArrayOutputStream,
        imageBytes: ByteArray,
        mimeType: String,
    ) {
        val mimeBytes = mimeType.toByteArray(Charsets.US_ASCII)
        val payload = ByteArrayOutputStream(4 + mimeBytes.size + imageBytes.size).apply {
            write(ENCODING_UTF8.toInt())
            write(mimeBytes)
            write(0) // MIME null terminator
            write(0x03) // Front cover
            write(0) // Empty description null terminator
            write(imageBytes)
        }.toByteArray()
        writeFrame(out, "APIC", payload)
    }

    private fun writeFrame(out: ByteArrayOutputStream, frameId: String, payload: ByteArray) {
        out.write(frameId.toByteArray(Charsets.US_ASCII))
        out.write(encodeSyncSafeInt(payload.size))
        out.write(0) // flags byte 1
        out.write(0) // flags byte 2
        out.write(payload)
    }

    internal fun encodeSyncSafeInt(value: Int): ByteArray = byteArrayOf(
        ((value shr 21) and 0x7F).toByte(),
        ((value shr 14) and 0x7F).toByte(),
        ((value shr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte(),
    )

    internal fun decodeSyncSafeInt(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)
    }
}

/**
 * Lossless FLAC metadata writer preserving STREAMINFO and untouched audio frames while
 * embedding Vorbis Comments and METADATA_BLOCK_PICTURE album cover art.
 */
object FlacTagWriter {

    private const val BLOCK_VORBIS_COMMENT = 4
    private const val BLOCK_PICTURE = 6

    private data class FlacBlock(
        val type: Int,
        val data: ByteArray,
    )

    fun write(file: File, metadata: AudioTagMetadata): Boolean {
        if (!file.exists() || file.length() < 42) return false
        val tempFile = File(file.parentFile, "${file.name}.flactmp")
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val magic = ByteArray(4)
                if (raf.read(magic) < 4 || String(magic, Charsets.US_ASCII) != "fLaC") {
                    return false
                }

                val preservedBlocks = mutableListOf<FlacBlock>()
                var hasExistingPicture = false
                var isLast = false
                while (!isLast) {
                    val header = ByteArray(4)
                    if (raf.read(header) < 4) return false
                    val b0 = header[0].toInt() and 0xFF
                    isLast = (b0 and 0x80) != 0
                    val blockType = b0 and 0x7F
                    val length = ((header[1].toInt() and 0xFF) shl 16) or
                        ((header[2].toInt() and 0xFF) shl 8) or
                        (header[3].toInt() and 0xFF)
                    val data = ByteArray(length)
                    raf.readFully(data)
                    when (blockType) {
                        BLOCK_VORBIS_COMMENT -> Unit // replace with updated comments
                        BLOCK_PICTURE -> {
                            if (metadata.coverArtBytes == null || metadata.coverArtBytes.isEmpty()) {
                                preservedBlocks += FlacBlock(blockType, data)
                                hasExistingPicture = true
                            }
                        }

                        else -> preservedBlocks += FlacBlock(blockType, data)
                    }
                }
                val audioFramesOffset = raf.filePointer

                preservedBlocks += FlacBlock(BLOCK_VORBIS_COMMENT, buildVorbisCommentBlock(metadata))
                val art = metadata.coverArtBytes
                if (art != null && art.isNotEmpty()) {
                    preservedBlocks += FlacBlock(
                        BLOCK_PICTURE,
                        buildPictureBlock(art, metadata.coverArtMimeType),
                    )
                } else if (!hasExistingPicture) {
                    // No art provided and none existed
                }

                tempFile.outputStream().buffered().use { out ->
                    out.write("fLaC".toByteArray(Charsets.US_ASCII))
                    preservedBlocks.forEachIndexed { index, block ->
                        val lastFlag = if (index == preservedBlocks.lastIndex) 0x80 else 0x00
                        val size = block.data.size
                        out.write(lastFlag or (block.type and 0x7F))
                        out.write((size shr 16) and 0xFF)
                        out.write((size shr 8) and 0xFF)
                        out.write(size and 0xFF)
                        out.write(block.data)
                    }
                    raf.seek(audioFramesOffset)
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = raf.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                    }
                }
            }

            if (!tempFile.renameTo(file)) {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
            }
            true
        } catch (_: Throwable) {
            tempFile.delete()
            false
        }
    }

    private fun buildVorbisCommentBlock(metadata: AudioTagMetadata): ByteArray {
        val comments = mutableListOf<String>()
        comments += "TITLE=${metadata.title}"
        comments += "ARTIST=${metadata.artist}"
        comments += "ALBUMARTIST=${metadata.albumArtist ?: metadata.artist}"
        comments += "ALBUM=${metadata.album}"
        metadata.trackNumber?.takeIf { it > 0 }?.let { comments += "TRACKNUMBER=$it" }
        metadata.discNumber?.takeIf { it > 0 }?.let { comments += "DISCNUMBER=$it" }
        metadata.year?.takeIf { it > 0 }?.let { comments += "DATE=$it" }
        metadata.replayGainTrackDb?.let { gain ->
            comments += String.format(Locale.US, "REPLAYGAIN_TRACK_GAIN=%.2f dB", gain)
        }
        metadata.trackId?.takeIf { it.isNotBlank() }?.let { id ->
            comments += "${AudioTagInspector.TAG_TRACK_ID}=$id"
        }
        metadata.qualityTag?.takeIf { it.isNotBlank() }?.let { quality ->
            comments += "${AudioTagInspector.TAG_QUALITY}=$quality"
        }
        metadata.audioSha256?.takeIf { it.isNotBlank() }?.let { sha ->
            comments += "${AudioTagInspector.TAG_AUDIO_SHA256}=$sha"
        }
        metadata.lyrics?.takeIf { it.isNotBlank() }?.let { comments += "LYRICS=$it" }

        val out = ByteArrayOutputStream()
        val vendor = "music-player".toByteArray(Charsets.UTF_8)
        writeLeInt(out, vendor.size)
        out.write(vendor)
        writeLeInt(out, comments.size)
        comments.forEach { comment ->
            val bytes = comment.toByteArray(Charsets.UTF_8)
            writeLeInt(out, bytes.size)
            out.write(bytes)
        }
        return out.toByteArray()
    }

    private fun buildPictureBlock(imageBytes: ByteArray, mimeType: String): ByteArray {
        val mimeBytes = mimeType.toByteArray(Charsets.US_ASCII)
        val out = ByteArrayOutputStream(32 + mimeBytes.size + imageBytes.size)
        writeBeInt(out, 3) // Picture type: Cover (front)
        writeBeInt(out, mimeBytes.size)
        out.write(mimeBytes)
        writeBeInt(out, 0) // Description length
        writeBeInt(out, 0) // Width (0 = unknown)
        writeBeInt(out, 0) // Height
        writeBeInt(out, 0) // Color depth
        writeBeInt(out, 0) // Indexed colors
        writeBeInt(out, imageBytes.size)
        out.write(imageBytes)
        return out.toByteArray()
    }

    private fun writeLeInt(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
        out.write((value shr 16) and 0xFF)
        out.write((value shr 24) and 0xFF)
    }

    private fun writeBeInt(out: ByteArrayOutputStream, value: Int) {
        out.write((value shr 24) and 0xFF)
        out.write((value shr 16) and 0xFF)
        out.write((value shr 8) and 0xFF)
        out.write(value and 0xFF)
    }
}
