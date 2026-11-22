package com.musicplayer.core.model

enum class LyricsSource {
    JELLYFIN,
    EMBEDDED,
}

data class LyricLine(
    val text: String,
    val startTimeMs: Long? = null,
)

data class TrackLyrics(
    val lines: List<LyricLine>,
    val isSynced: Boolean,
    val source: LyricsSource,
)

object LyricsParser {

    private val TIMESTAMP_REGEX = Regex("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    private val METADATA_TAG_REGEX = Regex("^\\[(ar|ti|al|by|offset|re|ve|length):.*]$", RegexOption.IGNORE_CASE)

    fun parse(rawText: String, source: LyricsSource): TrackLyrics? {
        val cleaned = rawText.trim()
        if (cleaned.isEmpty()) return null

        val syncedLines = mutableListOf<LyricLine>()
        val rawLines = cleaned.lines()

        for (rawLine in rawLines) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || METADATA_TAG_REGEX.matches(trimmed)) continue

            val matches = TIMESTAMP_REGEX.findAll(trimmed).toList()
            if (matches.isNotEmpty()) {
                val lyricText = TIMESTAMP_REGEX.replace(trimmed, "").trim()
                if (lyricText.isEmpty()) continue
                for (match in matches) {
                    val minutes = match.groupValues[1].toLongOrNull() ?: continue
                    val seconds = match.groupValues[2].toLongOrNull() ?: continue
                    val fracStr = match.groupValues[3]
                    val millis = when (fracStr.length) {
                        0 -> 0L
                        1 -> (fracStr.toLongOrNull() ?: 0L) * 100L
                        2 -> (fracStr.toLongOrNull() ?: 0L) * 10L
                        else -> fracStr.take(3).toLongOrNull() ?: 0L
                    }
                    val totalMs = minutes * 60_000L + seconds * 1_000L + millis
                    syncedLines += LyricLine(text = lyricText, startTimeMs = totalMs)
                }
            }
        }

        if (syncedLines.isNotEmpty()) {
            return TrackLyrics(
                lines = syncedLines.sortedBy { it.startTimeMs ?: Long.MAX_VALUE },
                isSynced = true,
                source = source,
            )
        }

        val plainLines = rawLines
            .map { it.trim() }
            .filter { it.isNotEmpty() && !METADATA_TAG_REGEX.matches(it) }
            .map { LyricLine(text = it, startTimeMs = null) }

        return if (plainLines.isNotEmpty()) {
            TrackLyrics(lines = plainLines, isSynced = false, source = source)
        } else {
            null
        }
    }

    fun findActiveLineIndex(lines: List<LyricLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var active = -1
        for (i in lines.indices) {
            val start = lines[i].startTimeMs ?: continue
            if (start <= positionMs) {
                active = i
            } else {
                break
            }
        }
        return active
    }
}
