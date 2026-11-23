package com.musicplayer.core.model

import java.util.UUID

data class ParsedPlaylistInfo(
    val displayName: String,
    val folderName: String?,
)

data class PlaylistFolderGroup(
    val name: String,
    val playlists: List<Playlist>,
) {
    val totalTrackCount: Int get() = playlists.sumOf { it.trackCount }
}

/**
 * Combines two Jellyfin-compatible playlist folder representations:
 * 1. Naming convention (`<Folder> / <Playlist>`) — visible in Jellyfin Web & works for all users.
 * 2. Jellyfin item tags (`folder:<Folder>`) — explicit metadata synced across devices.
 *
 * Also normalizes Jellyfin item IDs (which some endpoints return as 32-char undashed hex strings
 * while item queries return 36-char hyphenated UUIDs) so Room never creates duplicate rows.
 */
object PlaylistFolderCodec {

    const val FOLDER_TAG_PREFIX = "folder:"
    const val NAME_SEPARATOR = " / "

    private val UNDASHED_HEX_UUID = Regex("^[0-9a-fA-F]{32}$")

    fun normalizeJellyfinId(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed
        val formatted = if (UNDASHED_HEX_UUID.matches(trimmed)) {
            buildString(36) {
                append(trimmed, 0, 8)
                append('-')
                append(trimmed, 8, 12)
                append('-')
                append(trimmed, 12, 16)
                append('-')
                append(trimmed, 16, 20)
                append('-')
                append(trimmed, 20, 32)
            }
        } else {
            trimmed
        }
        return runCatching { UUID.fromString(formatted).toString() }.getOrDefault(trimmed)
    }

    fun normalizeFolderName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw
            .trim()
            .trim('/')
            .replace(Regex("\\s+"), " ")
            .trim()
        return cleaned.takeIf { it.isNotEmpty() }
    }

    fun extractFolderFromTags(tags: List<String>?): String? {
        if (tags.isNullOrEmpty()) return null
        for (tag in tags) {
            val trimmed = tag.trim()
            if (trimmed.startsWith(FOLDER_TAG_PREFIX, ignoreCase = true)) {
                val candidate = normalizeFolderName(trimmed.substring(FOLDER_TAG_PREFIX.length))
                if (candidate != null) return candidate
            }
        }
        return null
    }

    fun parse(rawName: String?, tags: List<String>? = null): ParsedPlaylistInfo {
        val safeRaw = rawName?.trim().orEmpty()
        val tagFolder = extractFolderFromTags(tags)

        val sepIndex = safeRaw.indexOf(NAME_SEPARATOR)
        val nameFolder: String?
        val shortName: String
        if (sepIndex > 0) {
            val left = normalizeFolderName(safeRaw.substring(0, sepIndex))
            val right = safeRaw.substring(sepIndex + NAME_SEPARATOR.length).trim()
            if (left != null && right.isNotEmpty()) {
                nameFolder = left
                shortName = right
            } else {
                nameFolder = null
                shortName = safeRaw
            }
        } else {
            nameFolder = null
            shortName = safeRaw
        }

        val resolvedFolder = tagFolder ?: nameFolder
        val displayName = when {
            nameFolder != null && (tagFolder == null || nameFolder.equals(tagFolder, ignoreCase = true)) -> shortName
            else -> safeRaw
        }.ifBlank { "Untitled Playlist" }

        return ParsedPlaylistInfo(
            displayName = displayName,
            folderName = resolvedFolder,
        )
    }

    fun formatRemoteName(displayName: String, folderName: String?): String {
        val cleanName = displayName.trim()
        val cleanFolder = normalizeFolderName(folderName)
        return if (cleanFolder != null) {
            "$cleanFolder$NAME_SEPARATOR$cleanName"
        } else {
            cleanName
        }
    }

    fun buildUpdatedTags(existingTags: List<String>?, folderName: String?): List<String> {
        val preserved = existingTags
            .orEmpty()
            .filterNot { it.trim().startsWith(FOLDER_TAG_PREFIX, ignoreCase = true) }
        val cleanFolder = normalizeFolderName(folderName)
        return if (cleanFolder != null) {
            preserved + "$FOLDER_TAG_PREFIX$cleanFolder"
        } else {
            preserved
        }
    }

    fun groupPlaylists(playlists: List<Playlist>): Pair<List<PlaylistFolderGroup>, List<Playlist>> {
        val byFolder = linkedMapOf<String, MutableList<Playlist>>()
        val canonicalNameByKey = mutableMapOf<String, String>()
        val rootPlaylists = mutableListOf<Playlist>()

        for (playlist in playlists) {
            val folder = normalizeFolderName(playlist.folderName)
            if (folder == null) {
                rootPlaylists += playlist
            } else {
                val key = folder.lowercase()
                val canonical = canonicalNameByKey.getOrPut(key) { folder }
                byFolder.getOrPut(canonical) { mutableListOf() } += playlist
            }
        }

        val folders = byFolder.entries
            .sortedBy { it.key.lowercase() }
            .map { (name, items) ->
                PlaylistFolderGroup(
                    name = name,
                    playlists = items.sortedBy { it.name.lowercase() },
                )
            }

        return folders to rootPlaylists.sortedBy { it.name.lowercase() }
    }

    fun distinctFolderNames(playlists: List<Playlist>): List<String> =
        groupPlaylists(playlists).first.map { it.name }
}
