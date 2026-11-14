package com.atlanyuey.aenht.shared

import com.atlanyuey.aenht.shared.eh.EhCategory
import com.atlanyuey.aenht.shared.site.GalleryLink
import com.atlanyuey.aenht.shared.site.QueryConverter
import com.atlanyuey.aenht.shared.site.SiteLinks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SiteLinksTest {
    @Test
    fun findsLinksOfBothSites() {
        assertEquals(GalleryLink.Nh(177013), SiteLinks.find("look: https://nhentai.net/g/177013/ !"))
        assertEquals(GalleryLink.Eh(2345678, "abcdef1234", false), SiteLinks.find("e-hentai.org/g/2345678/abcdef1234/"))
        assertEquals(GalleryLink.Eh(2345678, "abcdef1234", true), SiteLinks.find("https://exhentai.org/g/2345678/abcdef1234/?p=1"))
        assertEquals(GalleryLink.EhPage(4321, "e21491868a", 2, false), SiteLinks.find("https://e-hentai.org/s/e21491868a/4321-2"))
        // The first link in the text wins.
        assertEquals(GalleryLink.Nh(5), SiteLinks.find("nhentai.net/g/5 and e-hentai.org/g/1/abcdef1234"))
        assertNull(SiteLinks.find("nothing here"))
    }

    @Test
    fun searchTextNumbersAreNhentai() {
        assertEquals(GalleryLink.Nh(123456), SiteLinks.fromSearchText(" 123456 "))
        assertEquals(GalleryLink.Eh(1, "abcdef1234", false), SiteLinks.fromSearchText("https://e-hentai.org/g/1/abcdef1234/"))
        assertNull(SiteLinks.fromSearchText("big eyes"))
        assertNull(SiteLinks.fromSearchText("0"))
    }
}

class QueryConverterTest {
    @Test
    fun nhentaiToEhentai() {
        val ns = mapOf("glasses" to listOf("female", "male"), "big eyes" to listOf("female"))
        val r = QueryConverter.nhToEh(
            """tag:"big eyes" -tag:glasses artist:someone language:chinese category:manga school""",
        ) { ns[it].orEmpty() }
        assertEquals("""female:"big eyes$" -glasses$ artist:someone$ language:chinese$ school""", r.query)
        assertEquals(setOf(EhCategory.MANGA), r.categories)
        assertEquals(emptyList(), r.dropped)
        assertEquals(listOf("pages:>20"), QueryConverter.nhToEh("pages:>20").dropped)
    }

    @Test
    fun ehentaiToNhentai() {
        val r = QueryConverter.ehToNh("""female:"big eyes$" -m:glasses$ artist:someone$ cosplayer:x$ school""")
        assertEquals("""tag:"big eyes" -tag:glasses artist:someone school""", r.query)
        assertEquals(listOf("cosplayer:x"), r.dropped)
    }
}

class ContentFilterTest {
    private fun card(site: com.atlanyuey.aenht.shared.site.Site, title: String, uploader: String? = null, ids: List<Int> = emptyList()) =
        com.atlanyuey.aenht.shared.site.GallerySummary(
            key = com.atlanyuey.aenht.shared.site.GalleryKey(site, 1, null), title = title, thumbnail = null,
            thumbWidth = 0, thumbHeight = 0, pages = 1, languageBadge = null, uploader = uploader, nhTagIds = ids,
        )

    @Test
    fun oneRuleHidesATagOnBothSites() {
        val f = com.atlanyuey.aenht.shared.site.ContentFilter(
            listOf(
                com.atlanyuey.aenht.shared.site.FilterRule("1", "tag", "big eyes"),
                com.atlanyuey.aenht.shared.site.FilterRule("2", "title", "sample"),
                com.atlanyuey.aenht.shared.site.FilterRule("3", "uploader", "^bad.*", regex = true),
                com.atlanyuey.aenht.shared.site.FilterRule("4", "tag", "glasses", enabled = false),
            ),
            nhBlacklist = setOf(99),
        )
        val eh = com.atlanyuey.aenht.shared.site.Site.EHENTAI
        val nh = com.atlanyuey.aenht.shared.site.Site.NHENTAI
        kotlin.test.assertTrue(f.hides(card(eh, "A"), listOf("female:big eyes")))
        kotlin.test.assertTrue(f.hides(card(nh, "A"), listOf("big eyes")))
        kotlin.test.assertFalse(f.hides(card(eh, "A"), listOf("glasses")))
        kotlin.test.assertTrue(f.hides(card(eh, "A Sample Story"), emptyList()))
        kotlin.test.assertTrue(f.hides(card(eh, "B", uploader = "badguy"), emptyList()))
        kotlin.test.assertTrue(f.hides(card(nh, "B", ids = listOf(5, 99)), emptyList()))
        kotlin.test.assertFalse(f.hides(card(nh, "B", ids = listOf(5)), emptyList()))
    }
}

class CommentFilterTest {
    @Test
    fun commentRules() {
        val f = com.atlanyuey.aenht.shared.site.ContentFilter(listOf(
            com.atlanyuey.aenht.shared.site.FilterRule("1", "comment", "spoiler"),
            com.atlanyuey.aenht.shared.site.FilterRule("2", "commenter", "Troll"),
            com.atlanyuey.aenht.shared.site.FilterRule("3", "comment", "^first!?$", regex = true),
        ))
        kotlin.test.assertTrue(f.hidesComment("a", "Big SPOILER ahead"))
        kotlin.test.assertTrue(f.hidesComment("troll", "hi"))
        kotlin.test.assertTrue(f.hidesComment("b", "First"))
        kotlin.test.assertTrue(!f.hidesComment("trolley", "nice work"))
    }
}
