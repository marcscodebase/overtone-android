package com.musicplayer.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.musicplayer.core.model.CellularBitrateCap
import com.musicplayer.core.model.DownloadQuality
import com.musicplayer.core.model.DownloadQualityMode
import com.musicplayer.core.model.Mp3Bitrate
import com.musicplayer.core.model.ReplayGainMode
import com.musicplayer.core.model.UserPreferences
import com.musicplayer.core.model.WireGuardMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserPreferencesDataStore @Inject constructor(
    @param:UserPrefsDataStore private val dataStore: DataStore<Preferences>,
) {
    val userPreferences: Flow<UserPreferences> = dataStore.data.map { prefs ->
        val qualityMode = prefs[KEY_DOWNLOAD_QUALITY_MODE]
            ?.let { raw -> runCatching { DownloadQualityMode.valueOf(raw) }.getOrNull() }
            ?: DownloadQualityMode.LOSSLESS
        val mp3Bitrate = Mp3Bitrate.fromKbps(prefs[KEY_DOWNLOAD_MP3_BITRATE])
        val lowQualityBitrate = prefs[KEY_LOW_QUALITY_BITRATE]?.let(Mp3Bitrate::fromKbps) ?: Mp3Bitrate.KBPS_128

        UserPreferences(
            offlineModeEnabled = prefs[KEY_OFFLINE_MODE] ?: false,
            cellularBitrateCap = CellularBitrateCap.fromNameOrDefault(prefs[KEY_CELLULAR_BITRATE_CAP]),
            lowQualityBitrate = lowQualityBitrate,
            autoMatchOutputQuality = prefs[KEY_AUTO_MATCH_OUTPUT_QUALITY] ?: false,
            bitPerfectUsbAudio = prefs[KEY_BIT_PERFECT_USB_AUDIO] ?: false,
            hiResBluetoothDevices = prefs[KEY_HIRES_BT_DEVICES] ?: emptySet(),
            defaultDownloadQuality = DownloadQuality(
                mode = qualityMode,
                mp3Bitrate = mp3Bitrate,
            ),
            wifiOnlyDownloads = prefs[KEY_WIFI_ONLY_DOWNLOADS] ?: true,
            askDownloadQualityEachTime = prefs[KEY_ASK_DOWNLOAD_QUALITY] ?: false,
            replayGainMode = ReplayGainMode.fromNameOrDefault(prefs[KEY_REPLAY_GAIN_MODE]),
            replayGainPreampDb = prefs[KEY_REPLAY_GAIN_PREAMP_DB] ?: 0f,
            selectedMusicLibraryIds = prefs[KEY_SELECTED_LIBRARIES] ?: emptySet(),
            wireGuardMode = WireGuardMode.fromNameOrDefault(prefs[KEY_WIREGUARD_MODE]),
            daylistRefreshSlotKey = prefs[KEY_DAYLIST_REFRESH_SLOT] ?: "",
            daylistRefreshNonce = prefs[KEY_DAYLIST_REFRESH_NONCE] ?: 0L,
            pinnedLibraryKeys = decodePinnedKeys(prefs[KEY_PINNED_LIBRARY_KEYS]),
            leftHandedMode = prefs[KEY_LEFT_HANDED_MODE] ?: false,
        )
    }

    suspend fun setOfflineModeEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_OFFLINE_MODE] = enabled }
    }

    suspend fun setCellularBitrateCap(cap: CellularBitrateCap) {
        dataStore.edit { it[KEY_CELLULAR_BITRATE_CAP] = cap.name }
    }

    suspend fun setLowQualityBitrate(bitrate: Mp3Bitrate) {
        dataStore.edit { it[KEY_LOW_QUALITY_BITRATE] = bitrate.kbps }
    }

    suspend fun setAutoMatchOutputQuality(enabled: Boolean) {
        dataStore.edit { it[KEY_AUTO_MATCH_OUTPUT_QUALITY] = enabled }
    }

    suspend fun setBitPerfectUsbAudio(enabled: Boolean) {
        dataStore.edit { it[KEY_BIT_PERFECT_USB_AUDIO] = enabled }
    }

    suspend fun setBluetoothDeviceHiRes(deviceKey: String, isHiRes: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_HIRES_BT_DEVICES] ?: emptySet()
            prefs[KEY_HIRES_BT_DEVICES] = if (isHiRes) current + deviceKey else current - deviceKey
        }
    }

    suspend fun setDefaultDownloadQuality(quality: DownloadQuality) {
        dataStore.edit {
            it[KEY_DOWNLOAD_QUALITY_MODE] = quality.mode.name
            it[KEY_DOWNLOAD_MP3_BITRATE] = quality.mp3Bitrate.kbps
        }
    }

    suspend fun setWifiOnlyDownloads(wifiOnly: Boolean) {
        dataStore.edit { it[KEY_WIFI_ONLY_DOWNLOADS] = wifiOnly }
    }

    suspend fun setAskDownloadQualityEachTime(ask: Boolean) {
        dataStore.edit { it[KEY_ASK_DOWNLOAD_QUALITY] = ask }
    }

    suspend fun setReplayGainMode(mode: ReplayGainMode) {
        dataStore.edit { it[KEY_REPLAY_GAIN_MODE] = mode.name }
    }

    suspend fun setReplayGainPreampDb(preampDb: Float) {
        dataStore.edit { it[KEY_REPLAY_GAIN_PREAMP_DB] = preampDb.coerceIn(-12f, 12f) }
    }

    suspend fun setSelectedMusicLibraryIds(libraryIds: Set<String>) {
        dataStore.edit { it[KEY_SELECTED_LIBRARIES] = libraryIds }
    }

    suspend fun setWireGuardMode(mode: WireGuardMode) {
        dataStore.edit { it[KEY_WIREGUARD_MODE] = mode.name }
    }

    suspend fun bumpDaylistRefresh(slotKey: String) {
        dataStore.edit { prefs ->
            val currentSlot = prefs[KEY_DAYLIST_REFRESH_SLOT] ?: ""
            val currentNonce = if (currentSlot == slotKey) (prefs[KEY_DAYLIST_REFRESH_NONCE] ?: 0L) else 0L
            prefs[KEY_DAYLIST_REFRESH_SLOT] = slotKey
            prefs[KEY_DAYLIST_REFRESH_NONCE] = currentNonce + 1L
        }
    }

    suspend fun toggleLibraryPin(pinKey: String) {
        if (pinKey.isBlank()) return
        dataStore.edit { prefs ->
            val current = decodePinnedKeys(prefs[KEY_PINNED_LIBRARY_KEYS])
            val updated = if (pinKey in current) {
                current.filterNot { it == pinKey }
            } else {
                current + pinKey
            }
            prefs[KEY_PINNED_LIBRARY_KEYS] = updated.joinToString("\n")
        }
    }

    suspend fun setLeftHandedMode(enabled: Boolean) {
        dataStore.edit { it[KEY_LEFT_HANDED_MODE] = enabled }
    }

    private fun decodePinnedKeys(raw: String?): List<String> {
        if (raw == null) return UserPreferences.DEFAULT_PINNED_LIBRARY_KEYS
        if (raw.isEmpty()) return emptyList()
        return raw.split('\n').filter { it.isNotBlank() }.distinct()
    }

    companion object {
        private val KEY_OFFLINE_MODE = booleanPreferencesKey("offline_mode_enabled")
        private val KEY_CELLULAR_BITRATE_CAP = stringPreferencesKey("cellular_bitrate_cap")
        private val KEY_LOW_QUALITY_BITRATE = intPreferencesKey("low_quality_bitrate_kbps")
        private val KEY_AUTO_MATCH_OUTPUT_QUALITY = booleanPreferencesKey("auto_match_output_quality")
        private val KEY_BIT_PERFECT_USB_AUDIO = booleanPreferencesKey("bit_perfect_usb_audio")
        private val KEY_HIRES_BT_DEVICES = stringSetPreferencesKey("hires_bluetooth_devices")
        private val KEY_DOWNLOAD_QUALITY_MODE = stringPreferencesKey("download_quality_mode")
        private val KEY_DOWNLOAD_MP3_BITRATE = intPreferencesKey("download_mp3_bitrate_kbps")
        private val KEY_WIFI_ONLY_DOWNLOADS = booleanPreferencesKey("wifi_only_downloads")
        private val KEY_ASK_DOWNLOAD_QUALITY = booleanPreferencesKey("ask_download_quality")
        private val KEY_REPLAY_GAIN_MODE = stringPreferencesKey("replay_gain_mode")
        private val KEY_REPLAY_GAIN_PREAMP_DB = floatPreferencesKey("replay_gain_preamp_db")
        private val KEY_SELECTED_LIBRARIES = stringSetPreferencesKey("selected_music_library_ids")
        private val KEY_WIREGUARD_MODE = stringPreferencesKey("wireguard_mode")
        private val KEY_DAYLIST_REFRESH_SLOT = stringPreferencesKey("daylist_refresh_slot")
        private val KEY_DAYLIST_REFRESH_NONCE = longPreferencesKey("daylist_refresh_nonce")
        private val KEY_PINNED_LIBRARY_KEYS = stringPreferencesKey("pinned_library_keys")
        private val KEY_LEFT_HANDED_MODE = booleanPreferencesKey("left_handed_mode")
    }
}
