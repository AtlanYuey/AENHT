package com.atlanyuey.aenht.shared

import com.atlanyuey.aenht.shared.eh.EhCategory
import com.atlanyuey.aenht.shared.eh.EhException
import com.atlanyuey.aenht.shared.eh.EhParser
import com.atlanyuey.aenht.shared.eh.EhTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fixtures follow the site's markup (Compact list, gallery, image page) with neutral content. */
class EhParserTest {
    private val list = """
<html><body>
<table class="itg gltc"><tr><th></th><th>Published</th><th>Title</th><th class="glhide">Uploader</th></tr>
<tr><td class="gl1c glcat"><div class="cn cta" onclick="">Western</div></td><td class="gl2c"><div class="glthumb" id="it100"><div><img style="height:188px;width:250px" alt="A" title="A" src="https://ehgt.org/w/01/a.webp" /></div><div><div><div class="cn cta">Western</div><div id="postedpop_100">2026-11-13 21:58</div></div><div><div class="ir" style="background-position:-32px -21px;opacity:1"></div><div>21 pages</div></div></div></div><div><div onclick="" id="posted_100" style="border-color:#f00;background-color:rgba(255,0,0,.1)" title="Folder 1">2026-11-13 21:58</div><div class="ir" style="background-position:-32px -21px;opacity:1"></div></div></td><td class="gl3c glname"><a href="https://e-hentai.org/g/100/abcdef0123/"><div class="glink">[Artist] Sample Title</div><div><div class="gt" title="language:english">english</div><div class="gt" title="artist:someone">someone</div><div class="gtl" title="female:glasses">f:glasses</div></div></a></td><td class="gl4c glhide"><div><a href="https://e-hentai.org/uploader/uploader1">uploader1</a></div><div>21 pages</div></td></tr>
<tr><td class="gl1c glcat"><div class="cn ct2">Doujinshi</div></td><td class="gl2c"><div class="glthumb"><div><img style="height:300px;width:212px" data-src="https://ehgt.org/w/02/b.webp" src="data:image/gif;base64,R0lGOD" /></div></div><div><div id="posted_99"><s>2026-11-12 10:00</s></div><div class="ir" style="background-position:0px -1px;opacity:1"></div></div></td><td class="gl3c glname"><a href="https://e-hentai.org/g/99/0123abcdef/"><div class="glink">Second</div></a></td><td class="gl4c glhide"><div>(Disowned)</div><div>1 page</div></td></tr>
</table>
<div class="searchnav"><div><span id="uprev">&lt; Prev</span></div><div><a id="unext" href="https://e-hentai.org/?next=99">Next &gt;</a></div></div>
</body></html>
"""

    @Test
    fun parsesACompactList() {
        assertFalse(EhParser.needsCompactMode(list))
        val page = EhParser.parseList(list)
        assertEquals(2, page.galleries.size)
        val g = page.galleries[0]
        assertEquals(100L, g.gid)
        assertEquals("abcdef0123", g.token)
        assertEquals("[Artist] Sample Title", g.title)
        assertEquals(EhCategory.WESTERN, g.category)
        assertEquals("https://ehgt.org/w/01/a.webp", g.thumbnail)
        assertEquals(250, g.thumbWidth)
        assertEquals(188, g.thumbHeight)
        assertEquals("2026-11-13 21:58", g.posted)
        assertEquals(21, g.pages)
        assertEquals("uploader1", g.uploader)
        assertEquals(2.5, g.rating)
        assertEquals(listOf("language:english", "artist:someone", "female:glasses"), g.tags.map { it.full })
        assertEquals("english", g.language)
        assertEquals(1, g.favoriteFolder)

        val second = page.galleries[1]
        assertEquals("https://ehgt.org/w/02/b.webp", second.thumbnail) // lazy rows use data-src
        assertEquals(5.0, second.rating)
        assertEquals(1, second.pages)
        assertTrue(second.expunged)
        assertNull(second.favoriteFolder)

        assertEquals("99", page.nextCursor)
        assertNull(page.prevCursor)
    }

    @Test
    fun detectsOtherDisplayModes() {
        assertTrue(EhParser.needsCompactMode("""<table class="itg glte"><tr><td></td></tr></table>"""))
        assertTrue(EhParser.needsCompactMode("""<div class="itg gld"></div>"""))
    }

    private val gallery = """
<html><head><script>var base_url = "https://e-hentai.org/";
var gid = 4321;
var token = "1a2b3c4d5e";
var apiuid = -1;
var apikey = "0123456789abcdef";
var average_rating = 4.21;
var display_rating = 4.21;</script></head><body>
<div class="gm"><div id="gleft"><div id="gd1"><div style="width:225px; height:375px; background:transparent url(https://ehgt.org/w/02/cover.webp) 0 0 no-repeat"></div></div></div>
<div id="gd2"><h1 id="gn">[Circle] English Title</h1><h1 id="gj">日本語タイトル</h1></div>
<div id="gmid"><div id="gd3"><div id="gdc"><div class="cs ct3">Manga</div></div>
<div id="gdn"><a href="https://e-hentai.org/uploader/someone">someone</a></div>
<div id="gdd"><table><tr><td class="gdt1">Posted:</td><td class="gdt2">2026-11-13 21:56</td></tr><tr><td class="gdt1">Parent:</td><td class="gdt2">None</td></tr><tr><td class="gdt1">Visible:</td><td class="gdt2">Yes</td></tr><tr><td class="gdt1">Language:</td><td class="gdt2">Japanese &nbsp;</td></tr><tr><td class="gdt1">File Size:</td><td class="gdt2">144.9 MiB</td></tr><tr><td class="gdt1">Length:</td><td class="gdt2">100 pages</td></tr><tr><td class="gdt1">Favorited:</td><td class="gdt2" id="favcount">1,234 times</td></tr></table></div>
<div id="gdr"><table><tr><td id="grt1">Rating:</td><td id="grt2"><div id="rating_image" class="ir irb" style="background-position:-16px -1px"></div></td><td id="grt3"><span id="rating_count">56</span></td></tr><tr><td id="rating_label" colspan="3">Average: 4.21</td></tr></table></div>
<div id="gdf"><div style="float:left; cursor:pointer" id="fav"><div class="i" style="background-image:url(https://ehgt.org/g/fav.png); background-position:0px -40px; margin-left:10px" title="Favorites 2"></div></div></div></div>
<div id="gd4"><div id="taglist"><table><tr><td class="tc">parody:</td><td><div id="td_parody:original" class="gt"><a>original</a></div></td></tr><tr><td class="tc">female:</td><td><div id="td_female:big_eyes" class="gt"><a>big eyes</a></div><div id="td_female:glasses" class="gtl"><a>glasses</a></div></td></tr></table></div></div>
<div id="gd5"><p class="g2"><a href="#" onclick="return popUp('https://e-hentai.org/gallerytorrents.php?gid=4321&amp;t=1a2b3c4d5e',610,590)">Torrent Download (2)</a></p></div></div></div>
<div class="gtb"><table class="ptt"><tr><td class="ptds"><a href="https://e-hentai.org/g/4321/1a2b3c4d5e/">1</a></td><td><a href="https://e-hentai.org/g/4321/1a2b3c4d5e/?p=1">2</a></td><td><a href="https://e-hentai.org/g/4321/1a2b3c4d5e/?p=4">5</a></td><td><a href="https://e-hentai.org/g/4321/1a2b3c4d5e/?p=1">&gt;</a></td></tr></table></div>
<div id="gdt" class="gt200"><a href="https://e-hentai.org/s/9010a7dc78/4321-1"><div title="Page 1: 001.png" style="width:180px;height:300px;background:transparent url(https://thumbs.example/c2/sprite.webp) -0px 0 no-repeat"></div></a><a href="https://e-hentai.org/s/e21491868a/4321-2"><div title="Page 2: 002.png" style="width:180px;height:300px;background:transparent url(https://thumbs.example/c2/sprite.webp) -200px 0 no-repeat"></div></a></div>
<div id="cdiv" class="gm"><a name="c0"></a><div class="c1"><div class="c2"><div class="c3">Posted on 13 November 2026, 22:10 by: &nbsp; <a href="https://forums.e-hentai.org/index.php?showuser=1">someone</a>&nbsp; &nbsp; </div><div class="c4 nosel">Uploader Comment</div></div><div class="c6" id="comment_0">Thanks for reading.<br>Second line</div></div>
<a name="c1"></a><div class="c1"><div class="c2"><div class="c3">Posted on 14 November 2026, 01:02 by: &nbsp; <a href="https://forums.e-hentai.org/index.php?showuser=2">reader</a>&nbsp; &nbsp; </div><div class="c5 nosel">Score <span id="comment_score_555">+12</span></div></div><div class="c6" id="comment_555">Nice art</div></div></div>
</body></html>
"""

    @Test
    fun parsesAGallery() {
        val d = EhParser.parseGallery(gallery)
        assertEquals(4321L, d.gid)
        assertEquals("1a2b3c4d5e", d.token)
        assertEquals("[Circle] English Title", d.title)
        assertEquals("日本語タイトル", d.japaneseTitle)
        assertEquals(EhCategory.MANGA, d.category)
        assertEquals("someone", d.uploader)
        assertEquals("https://ehgt.org/w/02/cover.webp", d.cover)
        assertEquals("2026-11-13 21:56", d.posted)
        assertNull(d.parent)
        assertEquals("Japanese", d.language)
        assertEquals("144.9 MiB", d.fileSize)
        assertEquals(100, d.pages)
        assertEquals(1234, d.favoritedCount)
        assertEquals(4.21, d.rating)
        assertEquals(56, d.ratingCount)
        assertTrue(d.ratedByUser)
        assertEquals(listOf("parody", "female"), d.tags.keys.toList())
        assertEquals(listOf(EhTag("female", "big eyes"), EhTag("female", "glasses")), d.tags["female"])
        assertEquals(2, d.favoriteFolder)
        assertEquals("Favorites 2", d.favoriteName)
        assertEquals(2, d.torrentCount)
        assertEquals(5, d.thumbnailPageCount)
        assertEquals("0123456789abcdef", d.apiKey)

        assertEquals(2, d.thumbnails.size)
        val t = d.thumbnails[1]
        assertEquals(2, t.page)
        assertEquals("https://e-hentai.org/s/e21491868a/4321-2", t.pageUrl)
        assertEquals("https://thumbs.example/c2/sprite.webp", t.imageUrl)
        assertEquals(200, t.offsetX)
        assertEquals(180, t.width)
        assertEquals(300, t.height)

        assertEquals(2, d.comments.size)
        val uploader = d.comments[0]
        assertTrue(uploader.isUploader)
        assertEquals("someone", uploader.author)
        assertEquals("13 November 2026, 22:10", uploader.posted)
        assertNull(uploader.score)
        assertEquals("Thanks for reading.\nSecond line", uploader.text)
        assertEquals(12, d.comments[1].score)
        assertEquals("reader", d.comments[1].author)
    }

    private val imagePage = """
<html><body><div id="i1"><h1>Title</h1><div id="i2"><div class="sn"><a id="next" href="https://e-hentai.org/s/e21491868a/4321-2"></a><div><span>1</span> / <span>100</span></div></div><div>001.png :: 768 x 1280 :: 59.02 KiB</div></div>
<div id="i3"><a href="https://e-hentai.org/s/e21491868a/4321-2"><img id="img" src="https://abc.hath.network/h/key/001.webp" style="width:768px;height:1280px" onerror="this.onerror=null; nl('49776-498503')" /></a></div>
<div id="i6"><div><a href="#" id="loadfail" onclick="return nl('49776-498503')">Reload broken image</a></div><div><a href="https://e-hentai.org/fullimg/4321/1/xyz/001.png">Download original 768 x 1280 1.39 MiB</a></div></div></div></body></html>
"""

    @Test
    fun parsesAnImagePage() {
        val p = EhParser.parseImagePage(imagePage)
        assertEquals("https://abc.hath.network/h/key/001.webp", p.imageUrl)
        assertEquals(768, p.width)
        assertEquals(1280, p.height)
        assertEquals("49776-498503", p.reloadKey)
        assertEquals("https://e-hentai.org/fullimg/4321/1/xyz/001.png", p.originalUrl)
        assertFalse(p.limitReached)
        assertTrue(EhParser.parseImagePage(imagePage.replace("key/001.webp", "509.gif")).limitReached)
    }

    @Test
    fun recognizesProblemPages() {
        assertIs<EhException.NoAccess>(EhParser.problem(""))
        assertIs<EhException.Banned>(EhParser.problem("Your IP address has been temporarily banned for excessive pageloads. The ban expires in 59 minutes"))
        assertNull(EhParser.problem(list))
    }

    @Test
    fun tagQueriesAndCategories() {
        assertEquals("female:\"big eyes$\"", EhTag("female", "big eyes").query)
        assertEquals("artist:someone$", EhTag("artist", "someone").query)
        assertEquals(EhTag("female", "glasses"), EhTag.parse("f:glasses"))
        assertEquals(0, EhCategory.filterValue(emptySet()))
        // Only Doujinshi + Manga shown: every other bit set.
        assertEquals(1023 - 2 - 4, EhCategory.filterValue(setOf(EhCategory.DOUJINSHI, EhCategory.MANGA)))
        assertEquals(100L to "abcdef0123", EhParser.galleryKey("https://exhentai.org/g/100/abcdef0123/?p=2"))
        assertEquals(Triple("e21491868a", 4321L, 2), EhParser.imagePageKey("https://e-hentai.org/s/e21491868a/4321-2"))
    }
}
