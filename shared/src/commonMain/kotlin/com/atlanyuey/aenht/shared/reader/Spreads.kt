package com.atlanyuey.aenht.shared.reader

/**
 * Pages paired side by side for the double-page reader. With [cover] the
 * first page stays alone (books start on the right-hand page); a wide page
 * (already a spread) is always alone, and so is a page left without a
 * partner before it.
 *
 * With [singleStep] a turn moves on by one page instead of two: every page
 * starts a spread with the page after it (pages 1–2, then 2–3, 3–4…), so the
 * page that was on the far side comes to the near side.
 */
class Spreads(pageCount: Int, val cover: Boolean, widePages: Set<Int>, val singleStep: Boolean = false) {
    /** Never negative: a gallery without pages has no spreads. */
    val pageCount: Int = maxOf(0, pageCount)

    private val slots: List<IntArray>
    private val slotOfPage: IntArray

    init {
        val list = mutableListOf<IntArray>()
        val pageCount = this.pageCount
        var i = 0
        if (cover && pageCount > 0) { list += intArrayOf(0); i = 1 }
        fun pairable(first: Int) = first + 1 < pageCount && first !in widePages && (first + 1) !in widePages
        while (i < pageCount) {
            if (pairable(i)) {
                list += intArrayOf(i, i + 1)
                // One page on: the second page starts the next spread, unless it
                // would show alone there (the last page, or before a wide one),
                // having just been shown.
                i += if (singleStep && pairable(i + 1)) 1 else 2
            } else {
                list += intArrayOf(i)
                i += 1
            }
        }
        slots = list
        slotOfPage = IntArray(pageCount)
        // A page shown in two spreads belongs to the one it starts.
        for (slot in list.indices.reversed()) list[slot].forEach { slotOfPage[it] = slot }
        list.forEachIndexed { slot, pages -> slotOfPage[pages[0]] = slot }
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
