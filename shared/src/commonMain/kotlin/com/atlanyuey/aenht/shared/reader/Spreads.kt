package com.atlanyuey.aenht.shared.reader

/**
 * Pages paired side by side for the double-page reader. With [cover] the
 * first page stays alone (books start on the right-hand page); a wide page
 * (already a spread) is always alone, and so is a page left without a
 * partner before it.
 */
class Spreads(pageCount: Int, val cover: Boolean, widePages: Set<Int>) {
    /** Never negative: a gallery without pages has no spreads. */
    val pageCount: Int = maxOf(0, pageCount)

    private val slots: List<IntArray>
    private val slotOfPage: IntArray

    init {
        val list = mutableListOf<IntArray>()
        var i = 0
        val pageCount = this.pageCount
        if (cover && pageCount > 0) { list += intArrayOf(0); i = 1 }
        while (i < pageCount) {
            val pairable = i + 1 < pageCount && i !in widePages && (i + 1) !in widePages
            if (pairable) { list += intArrayOf(i, i + 1); i += 2 } else { list += intArrayOf(i); i += 1 }
        }
        slots = list
        slotOfPage = IntArray(pageCount)
        list.forEachIndexed { slot, pages -> pages.forEach { slotOfPage[it] = slot } }
    }

    /** How many spreads there are (the pager's page count). */
    val count: Int get() = slots.size

    /** The spread showing [page] (0 when there are no pages). */
    fun slotOf(page: Int): Int = if (pageCount == 0) 0 else slotOfPage[page.coerceIn(0, pageCount - 1)]

    /** The first (lower) page of a spread. */
    fun firstPage(slot: Int): Int = slots.getOrNull(slot)?.first() ?: 0

    /** The pages of a spread in reading order (one or two). */
    fun pages(slot: Int): List<Int> = slots.getOrNull(slot)?.toList() ?: emptyList()

    /** The first page of the spread [delta] spreads away from [page]'s, or -1 past either end. */
    fun step(page: Int, delta: Int): Int {
        val slot = slotOf(page) + delta
        return if (slot in slots.indices) firstPage(slot) else -1
    }
}
