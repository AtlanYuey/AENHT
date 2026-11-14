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
}
