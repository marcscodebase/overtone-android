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

    @Test
    fun `builds contiguous ordered sections and resolves active section index`() {
        val titles = listOf("Alpha", "Apex", "Beta", "Charlie", "Cyber", "Zulu")
        val sections = FastScrollMath.buildSections(titles.size) { idx ->
            FastScrollMath.sectionLetter(titles[idx])
        }

        assertEquals(
            listOf(
                FastScrollSection("A", firstIndex = 0, lastIndex = 1),
                FastScrollSection("B", firstIndex = 2, lastIndex = 2),
                FastScrollSection("C", firstIndex = 3, lastIndex = 4),
                FastScrollSection("Z", firstIndex = 5, lastIndex = 5),
            ),
            sections,
        )
        assertEquals(0, FastScrollMath.activeSectionIndex(sections, 0))
        assertEquals(0, FastScrollMath.activeSectionIndex(sections, 1))
        assertEquals(1, FastScrollMath.activeSectionIndex(sections, 2))
        assertEquals(2, FastScrollMath.activeSectionIndex(sections, 4))
        assertEquals(3, FastScrollMath.activeSectionIndex(sections, 5))
    }

    @Test
    fun `downsamples large section counts evenly while keeping first and last`() {
        val years = (2025 downTo 1976).map { it.toString() } // 50 unique years
        val sections = FastScrollMath.buildSections(
            itemCount = years.size,
            maxSections = 10,
        ) { idx -> years[idx] }

        assertEquals(10, sections.size)
        assertEquals("2025", sections.first().label)
        assertEquals("1976", sections.last().label)
        assertEquals(0, FastScrollMath.activeSectionIndex(sections, 0))
        assertEquals(9, FastScrollMath.activeSectionIndex(sections, 49))
    }

    @Test
    fun `computes uniform section center fractions`() {
        assertEquals(0.5f, FastScrollMath.sectionCenterFraction(0, 1), 0.001f)
        assertEquals(0.125f, FastScrollMath.sectionCenterFraction(0, 4), 0.001f)
        assertEquals(0.875f, FastScrollMath.sectionCenterFraction(3, 4), 0.001f)
    }
}
