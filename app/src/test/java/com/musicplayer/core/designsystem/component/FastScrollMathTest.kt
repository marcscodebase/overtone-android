package com.musicplayer.core.designsystem.component

import org.junit.Assert.assertEquals
import org.junit.Test

class FastScrollMathTest {

    @Test
    fun `extracts normalized uppercase section letters and maps non-letters to hash`() {
        assertEquals("A", FastScrollMath.sectionLetter("Abbey Road"))
        assertEquals("A", FastScrollMath.sectionLetter("Ärzte"))
        assertEquals("E", FastScrollMath.sectionLetter("étoile"))
        assertEquals("Z", FastScrollMath.sectionLetter("  zebra"))
        assertEquals("#", FastScrollMath.sectionLetter("1989"))
        assertEquals("#", FastScrollMath.sectionLetter("(What's the Story)"))
        assertEquals("#", FastScrollMath.sectionLetter("   "))
        assertEquals("#", FastScrollMath.sectionLetter(null))
    }

    @Test
    fun `maps fractions to data indices safely across bounds`() {
        assertEquals(0, FastScrollMath.fractionToDataIndex(0f, 100))
        assertEquals(99, FastScrollMath.fractionToDataIndex(1f, 100))
        assertEquals(50, FastScrollMath.fractionToDataIndex(0.5f, 101))
        assertEquals(0, FastScrollMath.fractionToDataIndex(-0.5f, 100))
        assertEquals(99, FastScrollMath.fractionToDataIndex(1.5f, 100))
        assertEquals(0, FastScrollMath.fractionToDataIndex(0.7f, 0))
        assertEquals(0, FastScrollMath.fractionToDataIndex(0.7f, 1))
    }

    @Test
    fun `computes normalized scroll progress accounting for headers`() {
        assertEquals(0f, FastScrollMath.scrollProgressFraction(0, headerItemCount = 2, itemCount = 100, visibleItemCount = 10), 0.001f)
        assertEquals(0f, FastScrollMath.scrollProgressFraction(2, headerItemCount = 2, itemCount = 100, visibleItemCount = 10), 0.001f)
        assertEquals(0.5f, FastScrollMath.scrollProgressFraction(47, headerItemCount = 2, itemCount = 100, visibleItemCount = 10), 0.001f)
        assertEquals(1f, FastScrollMath.scrollProgressFraction(92, headerItemCount = 2, itemCount = 100, visibleItemCount = 10), 0.001f)
    }
}
