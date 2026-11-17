package com.atlanyuey.aenht.core

import android.webkit.CookieManager

/**
 * The login cookies the web views keep for a site. The login pages start
 * without them (else the site signs straight back in to the account there
 * was), and a logout removes them. Cloudflare's own cookies stay.
 */
object WebLogins {
    val NHENTAI = listOf("https://nhentai.net")
    val EHENTAI = listOf("https://forums.e-hentai.org", "https://e-hentai.org", "https://exhentai.org")

    fun forget(urls: List<String>) {
        runCatching {
            val cookies = CookieManager.getInstance()
            for (url in urls) {
                val host = android.net.Uri.parse(url).host ?: continue
                val names = cookies.getCookie(url)?.split(";").orEmpty()
                    .map { it.substringBefore("=").trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("cf_") && !it.startsWith("__cf") }
                for (name in names.distinct()) {
                    // A cookie is removed by setting it expired, for the host and the domain it was set for.
                    cookies.setCookie(url, "$name=; Max-Age=0; Path=/")
                    cookies.setCookie(url, "$name=; Max-Age=0; Path=/; Domain=$host")
                    cookies.setCookie(url, "$name=; Max-Age=0; Path=/; Domain=.${host.removePrefix("forums.")}")
                }
            }
            cookies.flush()
        }
    }
}
