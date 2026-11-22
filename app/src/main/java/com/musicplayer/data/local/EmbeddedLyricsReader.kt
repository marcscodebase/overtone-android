package com.musicplayer.data.local

import android.content.Context
import android.net.Uri
import com.musicplayer.core.common.AppDispatcher
import com.musicplayer.core.common.Dispatcher
import com.musicplayer.core.model.LyricsParser
import com.musicplayer.core.model.LyricsSource
import com.musicplayer.core.model.TrackLyrics
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.charset.Charset
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads embedded lyrics directly from local MP3 (ID3v2 USLT) and FLAC (Vorbis Comment LYRICS /
 * UNSYNCEDLYRICS) files so lyrics work seamlessly offline.
 */
@Singleton
class EmbeddedLyricsReader @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:Dispatcher(AppDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun readFromContentUri(contentUri: String): TrackLyrics? = withContext(ioDispatcher) {
        runCatching {
            context.contentResolver.openInputStream(Uri.parse(contentUri))?.buffered()?.use { stream ->
                readFromStream(stream)
            }
        }.getOrNull()
    }

    companion object {
        fun readFromStream(input: InputStream): TrackLyrics? {
            val buffered = if (input.markSupported()) input else input.buffered()
            buffered.mark(16)
            val magic = ByteArray(4)
            val read = buffered.read(magic)
            buffered.reset()
            if (read < 4) return null

            val rawText = when {
                magic[0] == 'I'.code.toByte() &&
                    magic[1] == 'D'.code.toByte() &&
                    magic[2] == '3'.code.toByte() -> readId3v2Uslt(buffered)

                String(magic, Charsets.US_ASCII) == "fLaC" -> readFlacVorbisLyrics(buffered)
                else -> null
            }
            return rawText?.let { LyricsParser.parse(it, LyricsSource.EMBEDDED) }
        }

        internal fun readId3v2Uslt(input: InputStream): String? {
            val header = ByteArray(10)
            if (readExact(input, header) < 10) return null
            val majorVersion = header[3].toInt() and 0xFF
            if (majorVersion !in 3..4) return null
            val tagSize = Id3v24TagWriter.decodeSyncSafeInt(header, 6)
            if (tagSize <= 0 || tagSize > 16 * 1024 * 1024) return null

            val tagBytes = ByteArray(tagSize)
            val actual = readExact(input, tagBytes)
            var offset = 0
            while (offset + 10 <= actual) {
                val frameId = String(tagBytes, offset, 4, Charsets.US_ASCII)
                if (frameId[0] == '\u0000') break
                val frameSize = if (majorVersion == 4) {
                    Id3v24TagWriter.decodeSyncSafeInt(tagBytes, offset + 4)
                } else {
                    ((tagBytes[offset + 4].toInt() and 0xFF) shl 24) or
                        ((tagBytes[offset + 5].toInt() and 0xFF) shl 16) or
                        ((tagBytes[offset + 6].toInt() and 0xFF) shl 8) or
                        (tagBytes[offset + 7].toInt() and 0xFF)
                }
                offset += 10
                if (frameSize <= 0 || offset + frameSize > actual) break

                if (frameId == "USLT") {
                    return decodeUsltPayload(tagBytes, offset, frameSize)
                }
                offset += frameSize
            }
            return null
        }

        private fun decodeUsltPayload(bytes: ByteArray, offset: Int, length: Int): String? {
            if (length < 5) return null
            val encoding = bytes[offset].toInt() and 0xFF
            // Skip 1 byte encoding + 3 bytes language code
            val descStart = offset + 4
            val end = offset + length

            val (charset, isDoubleNull) = when (encoding) {
                0 -> Charsets.ISO_8859_1 to false
                1 -> Charsets.UTF_16 to true
                2 -> Charsets.UTF_16BE to true
                3 -> Charsets.UTF_8 to false
                else -> Charsets.UTF_8 to false
            }

            var textStart = descStart
            if (isDoubleNull) {
                while (textStart + 1 < end) {
                    if (bytes[textStart] == 0.toByte() && bytes[textStart + 1] == 0.toByte()) {
                        textStart += 2
                        break
                    }
                    textStart += 2
                }
            } else {
                while (textStart < end) {
                    if (bytes[textStart] == 0.toByte()) {
                        textStart += 1
                        break
                    }
                    textStart++
                }
            }

            if (textStart >= end) return null
            return decodeString(bytes, textStart, end - textStart, charset)
                .trimEnd('\u0000')
                .takeIf { it.isNotBlank() }
        }

        private fun decodeString(bytes: ByteArray, offset: Int, len: Int, charset: Charset): String {
            return String(bytes, offset, len, charset)
        }

        internal fun readFlacVorbisLyrics(input: InputStream): String? {
            val dis = DataInputStream(input)
            val magic = ByteArray(4)
            dis.readFully(magic)
            if (String(magic, Charsets.US_ASCII) != "fLaC") return null

            var isLast = false
            while (!isLast) {
                val b0 = try {
                    dis.readUnsignedByte()
                } catch (_: EOFException) {
                    return null
                }
                isLast = (b0 and 0x80) != 0
                val blockType = b0 and 0x7F
                val len = (dis.readUnsignedByte() shl 16) or
                    (dis.readUnsignedByte() shl 8) or
                    dis.readUnsignedByte()
                if (len < 0 || len > 16 * 1024 * 1024) return null

                if (blockType == 4) { // VORBIS_COMMENT
                    val block = ByteArray(len)
                    dis.readFully(block)
                    return parseVorbisCommentLyrics(block)
                } else {
                    skipFully(dis, len.toLong())
                }
            }
            return null
        }

        private fun parseVorbisCommentLyrics(block: ByteArray): String? {
            val stream = ByteArrayInputStream(block)
            val vendorLen = readLeInt(stream) ?: return null
            if (vendorLen < 0 || vendorLen > block.size) return null
            stream.skip(vendorLen.toLong())
            val commentCount = readLeInt(stream) ?: return null
            if (commentCount <= 0) return null

            var fallbackUnsynced: String? = null
            for (i in 0 until commentCount) {
                val cLen = readLeInt(stream) ?: break
                if (cLen <= 0 || cLen > block.size) break
                val raw = ByteArray(cLen)
                if (readExact(stream, raw) < cLen) break
                val entry = String(raw, Charsets.UTF_8)
                val eq = entry.indexOf('=')
                if (eq <= 0) continue
                val key = entry.substring(0, eq).uppercase(Locale.US)
                val value = entry.substring(eq + 1)
                if (key == "LYRICS" && value.isNotBlank()) {
                    return value
                }
                if ((key == "UNSYNCEDLYRICS" || key == "UNSYNCED LYRICS") && value.isNotBlank()) {
                    fallbackUnsynced = value
                }
            }
            return fallbackUnsynced
        }

        private fun readLeInt(stream: InputStream): Int? {
            val b0 = stream.read()
            val b1 = stream.read()
            val b2 = stream.read()
            val b3 = stream.read()
            if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) return null
            return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        }

        private fun readExact(input: InputStream, buffer: ByteArray): Int {
            var total = 0
            while (total < buffer.size) {
                val r = input.read(buffer, total, buffer.size - total)
                if (r <= 0) break
                total += r
            }
            return total
        }

        private fun skipFully(input: InputStream, bytes: Long) {
            var remaining = bytes
            while (remaining > 0L) {
                val skipped = input.skip(remaining)
                if (skipped <= 0L) {
                    if (input.read() < 0) break
                    remaining -= 1L
                } else {
                    remaining -= skipped
                }
            }
        }
    }
}
