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
        deleteExistingIfPresent(target.relativePath, target.displayName)

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

    suspend fun exists(contentUri: String?): Boolean = withContext(ioDispatcher) {
        if (contentUri.isNullOrBlank()) return@withContext false
        runCatching {
            val uri = Uri.parse(contentUri)
            resolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        }.getOrDefault(false)
    }

    suspend fun delete(contentUri: String?): Boolean = withContext(ioDispatcher) {
        if (contentUri.isNullOrBlank()) return@withContext false
        runCatching {
            resolver.delete(Uri.parse(contentUri), null, null) > 0
        }.getOrDefault(false)
    }

    private fun deleteExistingIfPresent(relativePath: String, displayName: String) {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.Audio.Media._ID)
        val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} = ? AND ${MediaStore.Audio.Media.DISPLAY_NAME} = ?"
        val args = arrayOf(relativePath, displayName)

        runCatching {
            resolver.query(collection, projection, selection, args, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val existingUri = ContentUris.withAppendedId(collection, id)
                    resolver.delete(existingUri, null, null)
                }
            }
        }
    }
}
