package com.atlanyuey.aenht.shared

import com.atlanyuey.aenht.shared.db.HistoryRepository
import com.atlanyuey.aenht.shared.db.openInMemoryDatabase
import com.atlanyuey.aenht.shared.site.GalleryKey
import com.atlanyuey.aenht.shared.site.Site
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DatabaseTest {
    @Test
    fun historyMixesSitesNewestFirstAndKeepsKnownFields() = runTest {
        var clock = 1_000L
        val repo = HistoryRepository(openInMemoryDatabase()) { clock++ }
        val nh = GalleryKey.nh(177013)
        val eh = GalleryKey(Site.EHENTAI, 2345678, "abcdef1234")
        repo.record(nh, "NH title", mediaId = "987", thumbnail = "t.jpg", numPages = 20, tagIds = listOf(6346))
        repo.record(eh, "EH title", numPages = 30)
        repo.record(nh, "", page = 7) // reading progress only

        val all = repo.all()
        assertEquals(listOf(nh, eh), all.map { it.galleryKey })
        val first = all[0]
        assertEquals("NH title", first.title)
        assertEquals("987", first.mediaId)
        assertEquals(20, first.numPages)
        assertEquals(7, first.lastPage)
        assertEquals(listOf(6346), first.tagIdList)
        assertEquals("eh", all[1].site)

        repo.delete(nh)
        assertEquals(listOf(eh), repo.all().map { it.galleryKey })
    }
}
