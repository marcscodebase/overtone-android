package com.musicplayer.data.remote

import com.musicplayer.core.network.JellyfinClientProvider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class JellyfinArtworkUrlBuilder @Inject constructor(
    private val clientProvider: JellyfinClientProvider,
) {
    fun buildItemImageUrl(
        itemId: String?,
        imageTag: String? = null,
        maxWidth: Int = CARD_MAX_WIDTH,
    ): String? {
        if (itemId.isNullOrBlank()) return null
        val cleanBase = clientProvider.getClient().baseUrl?.trimEnd('/')
        if (cleanBase.isNullOrBlank()) return null
        return buildString(cleanBase.length + 72) {
            append(cleanBase)
            append("/Items/")
            append(itemId)
            append("/Images/Primary?maxWidth=")
            append(maxWidth)
            append("&quality=")
            append(IMAGE_QUALITY)
            if (!imageTag.isNullOrBlank()) {
                append("&tag=")
                append(imageTag)
            }
        }
    }

    fun buildTrackArtworkUrl(
        trackId: String,
        albumId: String?,
        imageTag: String?,
        maxWidth: Int = THUMBNAIL_MAX_WIDTH,
    ): String? {
        return when {
            !imageTag.isNullOrBlank() -> buildItemImageUrl(trackId, imageTag, maxWidth)
            !albumId.isNullOrBlank() -> buildItemImageUrl(albumId, null, maxWidth)
            else -> buildItemImageUrl(trackId, null, maxWidth)
        }
    }

    companion object {
        const val THUMBNAIL_MAX_WIDTH = 192
        const val CARD_MAX_WIDTH = 384
        const val DEFAULT_MAX_WIDTH = CARD_MAX_WIDTH
        const val LARGE_MAX_WIDTH = 900
        private const val IMAGE_QUALITY = 85
    }
}
