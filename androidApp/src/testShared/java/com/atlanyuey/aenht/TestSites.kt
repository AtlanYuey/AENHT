package com.atlanyuey.aenht

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.math.abs

// Sample sites for the tests (Robolectric and on a device): no network, the same galleries every time.

/** Colorful sample covers / pages, different for every URL. */
object FakeImages : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val seed = abs(chain.request.data.toString().hashCode())
        val width = 360
        val height = 360 * (130 + seed % 50) / 100
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val hue = (seed % 360).toFloat()
        val a = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.95f))
        val b = android.graphics.Color.HSVToColor(floatArrayOf((hue + 60) % 360, 0.7f, 0.55f))
        val paint = Paint().apply { shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), a, b, Shader.TileMode.CLAMP) }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        val circle = Paint().apply { color = android.graphics.Color.argb(150, 255, 255, 255); isAntiAlias = true }
        canvas.drawCircle(width * 0.65f, height * 0.35f, width * 0.22f, circle)
        circle.color = android.graphics.Color.argb(120, 0, 0, 0)
        canvas.drawRect(width * 0.1f, height * 0.6f, width * 0.8f, height * 0.75f, circle)
        return SuccessResult(image = bitmap.asImage(), request = chain.request, dataSource = DataSource.MEMORY)
    }
}

/** Sample API responses. */
object FakeApi : okhttp3.Interceptor {
    private val titles = listOf(
        "[Sample Circle (Artist)] Summer Vacation Story [English]",
        "(C104) [Moonlight Club] Night Walk",
        "[Studio Aoi] Rainy Day Café",
        "Daily Life in the City (Original) [Chinese]",
        "[Hoshizora] The Library at Midnight",
        "[Circle Kaze] Festival",
        "Weekend Trip",
        "[Akane] Letters",
        "[Sora Works] Seaside Town Chronicle Vol. 2 [Digital]",
        "Snow Mountain",
        "[Yoru] After School",
        "[Kumo] Garden",
    )

    private fun item(i: Int): String {
        val lang = listOf(6346, 29963, 12227)[i % 3]
        val height = listOf(500, 470, 540, 495, 520, 480)[i % 6]
        return """{"id":${500000 + i},"media_id":"${100 + i}","english_title":"${titles[i % titles.size]}","japanese_title":"サンプル $i",""" +
            """"thumbnail":"galleries/${100 + i}/thumb.jpg","thumbnail_width":350,"thumbnail_height":$height,""" +
            """"num_pages":${20 + i},"num_favorites":${1000 + i * 37},"tag_ids":[$lang,17249],"blacklisted":false}"""
    }

    private fun list(count: Int, offset: Int = 0) = (0 until count).joinToString(",") { item(it + offset) }

    private val detail = """{"id":500001,"media_id":"101","title":{"english":"[Sample Circle (Artist)] A Very Long Summer Vacation Story About a Gloomy Girl Who Became a Famous Idol with Over a Million Followers, Volume 2 [Digital] [English] [Sample Translations]",""" +
        """"japanese":"[サンプルサークル (アーティスト)] 夏休みの物語 [英訳]","pretty":"Summer Vacation Story"},""" +
        """"cover":{"path":"galleries/101/cover.jpg","width":350,"height":500},"thumbnail":{"path":"galleries/101/thumb.jpg","width":350,"height":500},""" +
        """"upload_date":1762300000,"num_pages":24,"num_favorites":1234,"is_favorited":false,"tags":[""" +
        """{"id":33173,"type":"category","name":"doujinshi","url":"/category/doujinshi/","count":523000},""" +
        """{"id":12227,"type":"language","name":"english","url":"/language/english/","count":150000},""" +
        """{"id":17249,"type":"language","name":"translated","url":"/language/translated/","count":200000},""" +
        """{"id":1,"type":"tag","name":"summer","url":"/tag/summer/","count":2400},""" +
        """{"id":2,"type":"tag","name":"beach","url":"/tag/beach/","count":5100},""" +
        """{"id":3,"type":"tag","name":"comedy","url":"/tag/comedy/","count":12000},""" +
        """{"id":4,"type":"tag","name":"school uniform","url":"/tag/school-uniform/","count":61000},""" +
        """{"id":5,"type":"tag","name":"glasses","url":"/tag/glasses/","count":48000},""" +
        """{"id":100,"type":"tag","name":"big breasts","url":"/tag/big-breasts/","count":240000},""" +
        """{"id":101,"type":"tag","name":"sole male","url":"/tag/sole-male/","count":190000},""" +
        """{"id":102,"type":"tag","name":"nakadashi","url":"/tag/nakadashi/","count":130000},""" +
        """{"id":103,"type":"tag","name":"schoolgirl uniform","url":"/tag/schoolgirl-uniform/","count":94000},""" +
        """{"id":104,"type":"tag","name":"ahegao","url":"/tag/ahegao/","count":65000},""" +
        """{"id":105,"type":"tag","name":"series","url":"/tag/series/","count":59000},""" +
        """{"id":106,"type":"tag","name":"x-ray","url":"/tag/x-ray/","count":56000},""" +
        """{"id":107,"type":"tag","name":"paizuri","url":"/tag/paizuri/","count":47000},""" +
        """{"id":108,"type":"tag","name":"netorare","url":"/tag/netorare/","count":44000},""" +
        """{"id":109,"type":"tag","name":"twintails","url":"/tag/twintails/","count":39000},""" +
        """{"id":110,"type":"tag","name":"ponytail","url":"/tag/ponytail/","count":36000},""" +
        """{"id":111,"type":"tag","name":"swimsuit","url":"/tag/swimsuit/","count":36000},""" +
        """{"id":112,"type":"tag","name":"kissing","url":"/tag/kissing/","count":31000},""" +
        """{"id":113,"type":"tag","name":"bikini","url":"/tag/bikini/","count":25000},""" +
        """{"id":6,"type":"parody","name":"original","url":"/parody/original/","count":300000},""" +
        """{"id":7,"type":"artist","name":"artist","url":"/artist/artist/","count":120},""" +
        """{"id":8,"type":"group","name":"sample circle","url":"/group/sample-circle/","count":80}],""" +
        """"pages":[${(1..24).joinToString(",") { """{"number":$it,"path":"galleries/101/$it.jpg","width":1280,"height":1810,"thumbnail":"galleries/101/${it}t.jpg"}""" }}],""" +
        """"related":[${list(6, 6)}]}"""

    private val comments = """{"result":[""" + (1..3).joinToString(",") {
        """{"id":$it,"poster":{"id":$it,"username":"reader$it","avatar_url":""},"post_date":1762300000,"body":"Great work! Comment number $it with some text to show how bubbles wrap across lines."}"""
    } + """],"num_pages":1}"""

    private fun ehRow(i: Int): String {
        val cats = listOf("Manga" to "ct3", "Doujinshi" to "ct2", "Artist CG" to "ct4", "Western" to "cta", "Non-H" to "ct9", "Cosplay" to "ct7")
        val (cat, cls) = cats[i % cats.size]
        val h = listOf(350, 330, 360, 300, 340)[i % 5]
        val stars = listOf("0px -1px", "-16px -21px", "-16px -1px", "-32px -1px")[i % 4]
        val lang = listOf("", "<div class=\"gt\" title=\"language:chinese\">chinese</div>", "<div class=\"gt\" title=\"language:english\">english</div>")[i % 3]
        return """<tr><td class="gl1c glcat"><div class="cn $cls">$cat</div></td><td class="gl2c"><div class="glthumb"><div><img style="height:${h}px;width:250px" src="https://ehgt.org/w/0$i/t.webp" /></div></div><div><div id="posted_${4300 + i}">2026-11-1${i % 10} 12:00</div><div class="ir" style="background-position:$stars;opacity:1"></div></div></td><td class="gl3c glname"><a href="https://e-hentai.org/g/${4300 + i}/1a2b3c4d5e/"><div class="glink">${titles[i % titles.size]}</div><div>$lang<div class="gt" title="female:glasses">f:glasses</div><div class="gt" title="other:full color">full color</div></div></a></td><td class="gl4c glhide"><div><a href="https://e-hentai.org/uploader/uploader$i">uploader$i</a></div><div>${20 + i} pages</div></td></tr>"""
    }

    private val ehList = """<html><body><table class="itg gltc"><tr><th></th></tr>${(0 until 12).joinToString("") { ehRow(it) }}</table><a id="unext" href="https://e-hentai.org/?next=4288">Next</a></body></html>"""

    private val ehGallery = """<html><head><script>var gid = 4321;
var token = "1a2b3c4d5e";
var apiuid = -1;
var apikey = "0123456789abcdef";
var average_rating = 4.37;</script></head><body>
<div id="gd1"><div style="width:250px; height:350px; background:transparent url(https://ehgt.org/w/02/cover.webp) 0 0 no-repeat"></div></div>
<div id="gd2"><h1 id="gn">[Sample Circle (Artist)] A Long Summer Vacation Story [English]</h1><h1 id="gj">[サンプルサークル] 夏休みの物語</h1></div>
<div id="gdc"><div class="cs ct3">Manga</div></div><div id="gdn"><a href="https://e-hentai.org/uploader/someone">someone</a></div>
<div id="gdd"><table><tr><td class="gdt1">Posted:</td><td class="gdt2">2026-11-13 21:56</td></tr><tr><td class="gdt1">Parent:</td><td class="gdt2">None</td></tr><tr><td class="gdt1">Language:</td><td class="gdt2">English &nbsp;</td></tr><tr><td class="gdt1">File Size:</td><td class="gdt2">84.2 MiB</td></tr><tr><td class="gdt1">Length:</td><td class="gdt2">30 pages</td></tr><tr><td class="gdt1">Favorited:</td><td class="gdt2" id="favcount">1,234 times</td></tr></table></div>
<div id="gdr"><div id="rating_image" class="ir" style="background-position:-16px -21px"></div><span id="rating_count">256</span></div>
<div id="taglist"><table><tr><td class="tc">language:</td><td><div id="td_language:english" class="gt"><a>english</a></div><div id="td_language:translated" class="gt"><a>translated</a></div></td></tr><tr><td class="tc">parody:</td><td><div id="td_parody:original" class="gt"><a>original</a></div></td></tr><tr><td class="tc">artist:</td><td><div id="td_artist:artist" class="gt"><a>artist</a></div></td></tr><tr><td class="tc">female:</td><td><div id="td_female:glasses" class="gt"><a>glasses</a></div><div id="td_female:school_uniform" class="gt"><a>school uniform</a></div><div id="td_female:twintails" class="gtl"><a>twintails</a></div></td></tr><tr><td class="tc">other:</td><td><div id="td_other:full_color" class="gt"><a>full color</a></div></td></tr></table></div>
<div id="gd5"><p class="g2"><a href="#">Torrent Download (2)</a></p></div>
<table class="ptt"><tr><td><a href="https://e-hentai.org/g/4321/1a2b3c4d5e/">1</a></td><td><a href="https://e-hentai.org/g/4321/1a2b3c4d5e/?p=1">2</a></td></tr></table>
<div id="gdt">${(1..20).joinToString("") { """<a href="https://e-hentai.org/s/0123456789/4321-$it"><div style="width:100px;height:142px;background:transparent url(https://ehgt.org/s/sprite${(it - 1) / 10}.webp) -${((it - 1) % 10) * 100}px 0 no-repeat"></div></a>""" }}</div>
<div id="cdiv"><div class="c1"><div class="c3">Posted on 13 November 2026, 22:10 by: &nbsp; <a href="#">someone</a></div><div class="c6" id="comment_0">Thanks for reading!</div></div><div class="c1"><div class="c3">Posted on 14 November 2026, 01:02 by: &nbsp; <a href="#">reader</a></div><div class="c5 nosel">Score <span>+12</span></div><div class="c6" id="comment_55">Great art and a fun story.</div></div></div>
</body></html>"""

    override fun intercept(chain: okhttp3.Interceptor.Chain): Response {
        val url = chain.request().url
        val path = url.encodedPath
        if (url.host == "e-hentai.org" || url.host == "exhentai.org") {
            val html = when {
                path.startsWith("/g/") -> ehGallery
                path.startsWith("/s/") -> "<html><body><img id=\"img\" src=\"https://ehgt.org/i/${path.hashCode()}.jpg\" style=\"width:1280px;height:1810px\" /></body></html>"
                else -> ehList
            }
            return Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(html.toResponseBody("text/html; charset=UTF-8".toMediaType())).build()
        }
        val body = when {
            path.endsWith("/galleries/popular") -> "[${list(8, 4)}]"
            path.contains("/comments") -> comments
            Regex("/galleries/\\d+$").containsMatchIn(path) -> detail
            // Each page its own galleries (the same ones again would be dropped as already shown, and more pages read).
            path.endsWith("/galleries") || path.endsWith("/search") -> {
                val page = url.queryParameter("page")?.toIntOrNull() ?: 1
                """{"result":[${list(12, (page - 1) * 12)}],"num_pages":5,"total":1234}"""
            }
            path.endsWith("/user") -> """{"id":1,"username":"tester","avatar_url":""}"""
            else -> "[]"
        }
        return Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }
}
