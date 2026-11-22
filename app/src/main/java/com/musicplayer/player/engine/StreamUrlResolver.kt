package com.musicplayer.player.engine

import com.musicplayer.core.model.Track
import com.musicplayer.core.network.JellyfinClientProvider
import javax.inject.Inject
import javax.inject.Singleton

data class ResolvedAudioSource(
    val uri: String,
    val isLocal: Boolean,
)

@Singleton
class StreamUrlResolver @Inject constructor(
    private val clientProvider: JellyfinClientProvider,
) {
    fun resolve(track: Track): ResolvedAudioSource? {
        return resolveForBaseUrl(
            track = track,
            baseUrl = clientProvider.getClient().baseUrl,
        )
    }

    companion object {
        fun resolveForBaseUrl(
            track: Track,
            baseUrl: String?,
        ): ResolvedAudioSource? {
            if (track.isDownloaded && !track.localUri.isNullOrBlank()) {
                return ResolvedAudioSource(uri = track.localUri, isLocal = true)
            }

            val cleanBase = baseUrl?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
            val directPlayUrl = "$cleanBase/Audio/${track.id}/stream?static=true"
            return ResolvedAudioSource(uri = directPlayUrl, isLocal = false)
        }
    }
}
