package com.musicplayer

import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectSetupTest {
    @Test
    fun applicationIdMatchesSpecification() {
        assertEquals("io.saier.overtone", BuildConfig.APPLICATION_ID)
    }
}
