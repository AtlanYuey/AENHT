package com.atlanyuey.aenht.shared

import com.atlanyuey.aenht.shared.reader.Spreads
import kotlin.test.Test
import kotlin.test.assertEquals

class SpreadsTest {
    private fun Spreads.all() = (0 until count).map { pages(it) }

    @Test
    fun coverAloneThenPairs() {
        val s = Spreads(6, cover = true, widePages = emptySet())
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3, 4), listOf(5)), s.all())
        assertEquals(1, s.slotOf(2))
        assertEquals(3, s.step(1, 1))
        assertEquals(0, s.step(2, -1))
        assertEquals(-1, s.step(5, 1))
    }

    @Test
    fun widePagesStayAlone() {
        val s = Spreads(6, cover = false, widePages = setOf(2))
        assertEquals(listOf(listOf(0, 1), listOf(2), listOf(3, 4), listOf(5)), s.all())
        assertEquals(listOf(listOf<Int>()), Spreads(0, true, emptySet()).let { listOf(it.pages(0)) })
    }

    @Test
    fun oneStepTurnsOnePage() {
        val s = Spreads(6, cover = false, widePages = emptySet(), singleStep = true)
        assertEquals(listOf(listOf(0, 1), listOf(1, 2), listOf(2, 3), listOf(3, 4), listOf(4, 5)), s.all())
        // A page belongs to the spread it starts; the last one to the spread that ends with it.
        assertEquals(listOf(0, 1, 2, 3, 4, 4), (0..5).map { s.slotOf(it) })
        assertEquals(1, s.step(0, 1))
        assertEquals(3, s.step(4, -1))
        assertEquals(-1, s.step(5, 1))
        // The cover alone, then one page on each turn.
        val c = Spreads(5, cover = true, widePages = emptySet(), singleStep = true)
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(2, 3), listOf(3, 4)), c.all())
        // No page shows alone right after showing in a pair (before a wide page, at the end).
        val w = Spreads(7, cover = false, widePages = setOf(2), singleStep = true)
        assertEquals(listOf(listOf(0, 1), listOf(2), listOf(3, 4), listOf(4, 5), listOf(5, 6)), w.all())
        assertEquals(listOf(listOf(0, 1), listOf(1, 2)), Spreads(3, false, emptySet(), singleStep = true).all())
    }
}
