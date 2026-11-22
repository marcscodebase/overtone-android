package com.musicplayer.data.remote

import com.musicplayer.core.network.JellyfinClientProvider
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.model.api.ImageType
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class JellyfinArtworkUrlBuilder @Inject constructor(
    private val clientProvider: JellyfinClientProvider,
) {
    fun buildItemImageUrl(
        itemId: String?,
        imageTag: String? = null,
        maxWidth: Int = DEFAULT_MAX_WIDTH,
    ): String? {
        if (itemId.isNullOrBlank()) return null
        val api = clientProvider.getClient()
        if (api.baseUrl.isNullOrBlank()) return null
        val uuid = runCatching { UUID.fromString(itemId) }.getOrNull() ?: return null
        return runCatching {
            api.imageApi.getItemImageUrl(
                itemId = uuid,
                imageType = ImageType.PRIMARY,
                maxWidth = maxWidth,
                tag = imageTag,
                quality = IMAGE_QUALITY,
            )
        }.getOrNull()
    }

    fun buildTrackArtworkUrl(
        trackId: String,
        albumId: String?,
        imageTag: String?,
        maxWidth: Int = DEFAULT_MAX_WIDTH,
    ): String? {
        return when {
            !imageTag.isNullOrBlank() -> buildItemImageUrl(trackId, imageTag, maxWidth)
            !albumId.isNullOrBlank() -> buildItemImageUrl(albumId, null, maxWidth)
            else -> buildItemImageUrl(trackId, null, maxWidth)
        }
    }

    companion object {
        const val DEFAULT_MAX_WIDTH = 512
        const val LARGE_MAX_WIDTH = 1024
        private const val IMAGE_QUALITY = 90
    }
}
