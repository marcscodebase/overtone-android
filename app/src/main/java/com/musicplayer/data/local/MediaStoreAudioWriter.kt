package com.musicplayer.data.local

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.musicplayer.core.common.AppDispatcher
import com.musicplayer.core.common.Dispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class PublishedMediaFile(
    val contentUri: String,
    val relativePath: String,
    val fileSizeBytes: Long,
)

@Singleton
class MediaStoreAudioWriter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:Dispatcher(AppDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {
    private val resolver get() = context.contentResolver

    suspend fun publishAudioFile(
        sourceFile: File,
        target: MediaStoreTarget,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
    ): PublishedMediaFile = withContext(ioDispatcher) {
        // Check if an identical file already exists at the exact target path (e.g. from a previous install).
        // If we cannot overwrite/delete it under Scoped Storage, verify its SHA-256 / audio payload
        // checksum and reuse the existing file directly instead of creating a `(1)` duplicate!
        val existingMatches = queryFilesAtTarget(target.relativePath, target.displayName)
        for (existing in existingMatches) {
            val deleted = deleteByUri(existing.contentUri)
            if (!deleted && matchesFileChecksum(sourceFile, target.extension, existing)) {
                return@withContext PublishedMediaFile(
                    contentUri = existing.contentUri,
                    relativePath = existing.relativePath + existing.displayName,
                    fileSizeBytes = existing.sizeBytes.takeIf { it > 0L } ?: sourceFile.length(),
                )
            }
        }

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, target.displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, target.mimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, target.relativePath)
            put(MediaStore.Audio.Media.TITLE, title)
            put(MediaStore.Audio.Media.ARTIST, artist)
            put(MediaStore.Audio.Media.ALBUM, album)
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            if (durationMs > 0L) {
                put(MediaStore.Audio.Media.DURATION, durationMs)
            }
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val itemUri = resolver.insert(collection, values)
            ?: throw IOException("Failed to create MediaStore entry for ${target.displayName}")

        try {
            val outputStream = resolver.openOutputStream(itemUri)
                ?: throw IOException("Failed to open MediaStore output stream")
            outputStream.buffered().use { out ->
                sourceFile.inputStream().buffered().use { input ->
                    input.copyTo(out, bufferSize = 64 * 1024)
                }
            }

            val publishValues = ContentValues().apply {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
            }
            resolver.update(itemUri, publishValues, null, null)

            PublishedMediaFile(
                contentUri = itemUri.toString(),
                relativePath = target.relativePath + target.displayName,
                fileSizeBytes = sourceFile.length(),
            )
        } catch (t: Throwable) {
            runCatching { resolver.delete(itemUri, null, null) }
            throw t
        }
    }

    suspend fun scanOfflineFolder(): List<ScannedMediaStoreAudio> = withContext(ioDispatcher) {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Audio.Media.IS_PENDING} = 0"
        val args = arrayOf("${MediaStorePathBuilder.ROOT_RELATIVE_PATH}/%")
        queryAudioFiles(collection, selection, args, inspectTags = true)
    }

    suspend fun findExistingAtTarget(
        relativePath: String,
        displayName: String,
    ): List<ScannedMediaStoreAudio> = withContext(ioDispatcher) {
        queryFilesAtTarget(relativePath, displayName, inspectTags = true)
    }

    suspend fun computeContentSha256(contentUri: String): String? = withContext(ioDispatcher) {
        runCatching {
            resolver.openInputStream(Uri.parse(contentUri))?.buffered(64 * 1024)?.use { stream ->
                AudioTagInspector.computeSha256(stream)
            }
        }.getOrNull()
    }

    suspend fun exists(contentUri: String?): Boolean = withContext(ioDispatcher) {
        if (contentUri.isNullOrBlank()) return@withContext false
        runCatching {
            val uri = Uri.parse(contentUri)
            resolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        }.getOrDefault(false)
    }

    suspend fun delete(contentUri: String?): Boolean = withContext(ioDispatcher) {
        if (contentUri.isNullOrBlank()) return@withContext false
        deleteByUri(contentUri)
    }

    private fun deleteByUri(contentUri: String): Boolean = runCatching {
        resolver.delete(Uri.parse(contentUri), null, null) > 0
    }.getOrDefault(false)

    private fun queryFilesAtTarget(
        relativePath: String,
        displayName: String,
        inspectTags: Boolean = false,
    ): List<ScannedMediaStoreAudio> {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} = ? AND ${MediaStore.Audio.Media.DISPLAY_NAME} = ? AND ${MediaStore.Audio.Media.IS_PENDING} = 0"
        val args = arrayOf(relativePath, displayName)
        return queryAudioFiles(collection, selection, args, inspectTags = inspectTags)
    }

    private fun queryAudioFiles(
        collection: Uri,
        selection: String,
        args: Array<String>,
        inspectTags: Boolean,
    ): List<ScannedMediaStoreAudio> {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DATE_MODIFIED,
        )

        val results = mutableListOf<ScannedMediaStoreAudio>()
        runCatching {
            resolver.query(collection, projection, selection, args, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val modCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(collection, id)
                    val relPath = cursor.getString(pathCol).orEmpty()
                    val name = cursor.getString(nameCol).orEmpty()
                    val size = cursor.getLong(sizeCol)
                    val duration = cursor.getLong(durationCol)
                    val ext = name.substringAfterLast('.', "")
                    val tags = if (inspectTags && size > 0L) {
                        runCatching {
                            resolver.openInputStream(uri)?.use { stream ->
                                AudioTagInspector.inspect(stream, ext)
                            }
                        }.getOrNull() ?: InspectedAudioTags()
                    } else {
                        InspectedAudioTags()
                    }

                    results += ScannedMediaStoreAudio(
                        id = id,
                        contentUri = uri.toString(),
                        relativePath = relPath,
                        displayName = name,
                        sizeBytes = size,
                        durationMs = duration,
                        title = cursor.getString(titleCol),
                        artist = cursor.getString(artistCol),
                        album = cursor.getString(albumCol),
                        dateModifiedSeconds = cursor.getLong(modCol),
                        inspectedTags = tags,
                    )
                }
            }
        }
        return results
    }

    private fun matchesFileChecksum(
        sourceFile: File,
        extension: String,
        existing: ScannedMediaStoreAudio,
    ): Boolean {
        val existingUri = Uri.parse(existing.contentUri)
        // 1. Exact full-file SHA-256 match
        val sourceFullSha = AudioTagInspector.computeSha256(sourceFile)
        val existingFullSha = runCatching {
            resolver.openInputStream(existingUri)?.buffered(64 * 1024)?.use {
                AudioTagInspector.computeSha256(it)
            }
        }.getOrNull()
        if (sourceFullSha != null && sourceFullSha == existingFullSha) {
            return true
        }

        // 2. Audio-payload SHA-256 match (matches identical audio even if cover art / tag header changed!)
        val sourceAudioSha = AudioTagInspector.computeAudioPayloadSha256(sourceFile, extension)
        val existingAudioSha = runCatching {
            resolver.openInputStream(existingUri)?.use {
                AudioTagInspector.computeAudioPayloadSha256(it, existing.extension)
            }
        }.getOrNull()
        return sourceAudioSha != null && sourceAudioSha == existingAudioSha
    }
}
