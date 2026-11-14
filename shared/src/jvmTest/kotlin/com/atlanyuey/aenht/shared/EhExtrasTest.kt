package com.atlanyuey.aenht.shared

import com.atlanyuey.aenht.shared.eh.EhParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EhExtrasTest {
    @Test
    fun torrents() {
        val html = """<html><body><div id="torrentinfo"><div>
            <form method="post" action="https://e-hentai.org/gallerytorrents.php?gid=1&t=a"><div><table style="width:99%">
            <tr><td style="width:190px"><span>Posted:</span> <span>2026-11-01 10:00</span></td><td><span>Size:</span> 120.5 MiB</td>
            <td><span>Seeds:</span> 3</td><td><span>Peers:</span> 1</td><td><span>Downloads:</span> 1,024</td></tr>
            <tr><td colspan="5"><span>Uploader:</span> someone</td></tr>
            <tr><td colspan="5"><a href="https://ehtracker.org/get/1/abc.torrent?p=xyz">[Circle] Title.zip</a></td></tr>
            </table></div></form></div></div></body></html>"""
        val t = EhParser.parseTorrents(html).single()
        assertEquals("[Circle] Title.zip", t.name)
        assertEquals("https://ehtracker.org/get/1/abc.torrent?p=xyz", t.url)
        assertEquals("2026-11-01 10:00", t.posted)
        assertEquals("120.5 MiB", t.size)
        assertEquals(3, t.seeds)
        assertEquals(1024, t.downloads)
        assertEquals("someone", t.uploader)
    }

    @Test
    fun imageLimitAndNote() {
        val home = """<div class="homebox"><p>You are currently at <strong>1,234</strong> towards a limit of <strong>5,000</strong>.</p>
            <p>Reset Cost: <strong>617</strong> GP</p></div>"""
        val limit = EhParser.parseImageLimit(home)!!
        assertEquals(1234, limit.used)
        assertEquals(5000, limit.limit)
        assertEquals(617, limit.resetCost)
        assertNull(EhParser.parseImageLimit("<p>nothing</p>"))
        assertEquals("read later", EhParser.parseFavoriteNote("""<form><textarea name="favnote">read later</textarea></form>"""))
    }
}

class EhArchiveTest {
    @Test
    fun archiveOptionsAndLink() {
        val page = """<div id="db"><div style="float:left">
            <form action="https://e-hentai.org/archiver.php?gid=1&token=a&or=k" method="post">
            <input type="hidden" name="dltype" value="org" /><input type="submit" name="dlcheck" value="Download Original Archive" /></form>
            <p>Download Cost: <strong>Free!</strong></p><p>Estimated Size: <strong>120.53 MiB</strong></p></div>
            <div style="float:right"><form action="#" method="post"><input type="hidden" name="dltype" value="res" />
            <input type="submit" name="dlcheck" value="Download Resample Archive" /></form>
            <p>Download Cost: <strong>1,234 GP</strong></p><p>Estimated Size: <strong>40.1 MiB</strong></p></div></div>"""
        val info = EhParser.parseArchiveInfo(page)
        assertEquals(listOf("org", "res"), info.options.map { it.type })
        assertEquals("Free!", info.options[0].cost)
        assertEquals("120.53 MiB", info.options[0].size)
        assertEquals("1,234 GP", info.options[1].cost)
        assertEquals("Download Resample Archive", info.options[1].label)
        val answer = """<script type="text/javascript">document.location = "https://abc.hath.network/archive/1/xyz/";</script>"""
        assertEquals("https://abc.hath.network/archive/1/xyz/", EhParser.parseArchiveLink(answer))
    }
}
