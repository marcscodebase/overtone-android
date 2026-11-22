package com.musicplayer.data.local

import java.util.Locale

data class MediaStoreTarget(
    val relativePath: String,
    val displayName: String,
    val mimeType: String,
    val extension: String,
)

object MediaStorePathBuilder {

    const val ROOT_RELATIVE_PATH = "Music/music-player"

    fun build(
        artistName: String,
        albumName: String,
        title: String,
        trackNumber: Int?,
        discNumber: Int?,
        extension: String,
    ): MediaStoreTarget {
        val cleanExt = extension.trim().trimStart('.').lowercase(Locale.US).ifEmpty { "flac" }
        val safeArtist = sanitizeSegment(artistName, fallback = "Unknown Artist")
        val safeAlbum = sanitizeSegment(albumName, fallback = "Unknown Album")
        val safeTitle = sanitizeSegment(title, fallback = "Untitled")

        val trackPrefix = when {
            trackNumber != null && trackNumber > 0 && discNumber != null && discNumber > 1 ->
                String.format(Locale.US, "%d-%02d - ", discNumber, trackNumber)

            trackNumber != null && trackNumber > 0 ->
                String.format(Locale.US, "%02d - ", trackNumber)

            else -> ""
        }

        val relativePath = "$ROOT_RELATIVE_PATH/$safeArtist/$safeAlbum/"
        val displayName = "$trackPrefix$safeTitle.$cleanExt"

        return MediaStoreTarget(
            relativePath = relativePath,
            displayName = displayName,
            mimeType = mimeTypeForExtension(cleanExt),
            extension = cleanExt,
        )
    }

    fun mimeTypeForExtension(extension: String): String = when (extension.lowercase(Locale.US)) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "m4a", "alac", "aac", "mp4" -> "audio/mp4"
        "wav" -> "audio/wav"
        "aiff", "aif" -> "audio/aiff"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/opus"
        else -> "audio/*"
    }

    internal fun sanitizeSegment(raw: String, fallback: String): String {
        val cleaned = raw
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
            .take(MAX_SEGMENT_LENGTH)
            .trim()
        return cleaned.ifEmpty { fallback }
    }

    private const val MAX_SEGMENT_LENGTH = 80
}
