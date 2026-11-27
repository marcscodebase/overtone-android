package com.musicplayer.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.musicplayer.core.model.CellularBitrateCap
import com.musicplayer.core.model.DownloadQuality
import com.musicplayer.core.model.DownloadQualityMode
import com.musicplayer.core.model.Mp3Bitrate
import com.musicplayer.core.model.ReplayGainMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class UserPreferencesDataStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun emitsExpectedDefaultsWhenEmpty() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
            produceFile = { tempFolder.newFile("defaults.preferences_pb") },
        )
        val store = UserPreferencesDataStore(dataStore)

        val prefs = store.userPreferences.first()
        assertFalse(prefs.offlineModeEnabled)
        assertEquals(CellularBitrateCap.ORIGINAL, prefs.cellularBitrateCap)
        assertFalse(prefs.bitPerfectUsbAudio)
        assertEquals(DownloadQualityMode.LOSSLESS, prefs.defaultDownloadQuality.mode)
        assertEquals(Mp3Bitrate.KBPS_320, prefs.defaultDownloadQuality.mp3Bitrate)
        assertTrue(prefs.wifiOnlyDownloads)
        assertFalse(prefs.askDownloadQualityEachTime)
        assertEquals(ReplayGainMode.OFF, prefs.replayGainMode)
        assertEquals(0f, prefs.replayGainPreampDb, 0.001f)
        assertTrue(prefs.selectedMusicLibraryIds.isEmpty())
    }

    @Test
    fun persistsUpdatedPreferencesAndClampsPreamp() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
            produceFile = { tempFolder.newFile("updates.preferences_pb") },
        )
        val store = UserPreferencesDataStore(dataStore)

        store.setOfflineModeEnabled(true)
        store.setCellularBitrateCap(CellularBitrateCap.KBPS_256)
        store.setBitPerfectUsbAudio(true)
        store.setDefaultDownloadQuality(DownloadQuality(DownloadQualityMode.MP3, Mp3Bitrate.KBPS_192))
        store.setWifiOnlyDownloads(false)
        store.setAskDownloadQualityEachTime(true)
        store.setReplayGainMode(ReplayGainMode.ALBUM)
        store.setReplayGainPreampDb(25f)
        store.setSelectedMusicLibraryIds(setOf("lib-1", "lib-2"))

        val prefs = store.userPreferences.first()
        assertTrue(prefs.offlineModeEnabled)
        assertEquals(CellularBitrateCap.KBPS_256, prefs.cellularBitrateCap)
        assertTrue(prefs.bitPerfectUsbAudio)
        assertEquals(DownloadQualityMode.MP3, prefs.defaultDownloadQuality.mode)
        assertEquals(Mp3Bitrate.KBPS_192, prefs.defaultDownloadQuality.mp3Bitrate)
        assertFalse(prefs.wifiOnlyDownloads)
        assertTrue(prefs.askDownloadQualityEachTime)
        assertEquals(ReplayGainMode.ALBUM, prefs.replayGainMode)
        assertEquals(12f, prefs.replayGainPreampDb, 0.001f)
        assertEquals(setOf("lib-1", "lib-2"), prefs.selectedMusicLibraryIds)

        store.bumpDaylistRefresh("2026-11-27_MORNING")
        store.bumpDaylistRefresh("2026-11-27_MORNING")
        val afterTwoBumps = store.userPreferences.first()
        assertEquals("2026-11-27_MORNING", afterTwoBumps.daylistRefreshSlotKey)
        assertEquals(2L, afterTwoBumps.daylistRefreshNonce)

        store.bumpDaylistRefresh("2026-11-27_AFTERNOON")
        val afterNextSlot = store.userPreferences.first()
        assertEquals("2026-11-27_AFTERNOON", afterNextSlot.daylistRefreshSlotKey)
        assertEquals(1L, afterNextSlot.daylistRefreshNonce)

        store.toggleLibraryPin("playlist:p1")
        assertTrue("playlist:p1" in store.userPreferences.first().pinnedLibraryKeys)
        store.toggleLibraryPin("playlist:p1")
        assertFalse("playlist:p1" in store.userPreferences.first().pinnedLibraryKeys)
    }
}
