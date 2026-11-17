package com.atlanyuey.aenht.shared.app

import com.atlanyuey.aenht.shared.net.buildHttpClient
import com.atlanyuey.aenht.shared.net.lenientJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.cancellation.CancellationException

/** A release on GitHub: a version, or the rolling test build (dev-latest). */
data class ReleaseInfo(
    val tag: String,
    val name: String,
    val notes: String,
    val url: String,
    val prerelease: Boolean,
    /** When it was published, or its app file last replaced (test builds), ISO 8601. */
    val date: String,
)

/** What is newer than the app: the latest version and the latest test build, with the release notes. */
data class UpdateInfo(val latest: ReleaseInfo?, val testBuild: ReleaseInfo?, val releases: List<ReleaseInfo>, val updateAvailable: Boolean)

/** The releases couldn't be read (no network, or GitHub refused). */
class UpdateCheckException(message: String) : Exception(message)

/** Checks the project's releases (Settings › About › Check for Updates). */
class UpdateChecker internal constructor(private val http: HttpClient) {
    constructor() : this(buildHttpClient())

    @Throws(Exception::class)
    suspend fun check(currentVersion: String): UpdateInfo = withContext(Dispatchers.IO) {
        val response = http.get("https://api.github.com/repos/AtlanYuey/AENHT/releases?per_page=15") {
            header("Accept", "application/vnd.github+json")
        }
        // An error (GitHub limits unsigned requests per hour) is not "no releases".
        if (response.status.value !in 200..299) throw UpdateCheckException("GitHub: HTTP ${response.status.value}")
        val array = runCatching { lenientJson.parseToJsonElement(response.bodyAsText()) as? JsonArray }.getOrNull()
            ?: throw UpdateCheckException("GitHub: unexpected answer")
        val list = array.mapNotNull { it as? JsonObject }
        val releases = list.map { o ->
            fun s(key: String) = (o[key] as? JsonPrimitive)?.content.orEmpty()
            val assetDate = (o["assets"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.get("updated_at")?.let { d -> (d as? JsonPrimitive)?.content } }?.maxOrNull()
            ReleaseInfo(
                tag = s("tag_name"),
                name = s("name").ifBlank { s("tag_name") },
                notes = s("body"),
                url = s("html_url"),
                prerelease = (o["prerelease"] as? JsonPrimitive)?.content == "true",
                date = assetDate ?: s("published_at"),
            )
        }
        val latest = releases.firstOrNull { !it.prerelease }
        val test = releases.firstOrNull { it.tag == "dev-latest" }
        UpdateInfo(latest, test, releases, latest != null && isNewer(latest.tag, currentVersion))
    }

    companion object {
        /** Compare numeric versions and SemVer prereleases; build metadata has no precedence. */
        fun isNewer(tag: String, current: String): Boolean {
            data class Version(val core: List<Long>, val pre: List<String>?)
            fun parse(value: String): Version? {
                val clean = value.trim().removePrefix("v").substringBefore('+')
                val pieces = clean.split('-', limit = 2)
                val core = pieces[0].split('.')
                if (core.size !in 1..3 || core.any { it.isEmpty() || it.any { c -> c !in '0'..'9' } }) return null
                val numbers = core.map { it.toLongOrNull() ?: return null }
                val pre = pieces.getOrNull(1)?.split('.')
                if (pre != null && pre.any { it.isEmpty() || it.any { c -> c !in '0'..'9' && c !in 'A'..'Z' && c !in 'a'..'z' && c != '-' } }) return null
                return Version(numbers, pre)
            }
            val a = parse(tag) ?: return false
            val b = parse(current) ?: return false
            for (i in 0..2) {
                val comparison = a.core.getOrElse(i) { 0 }.compareTo(b.core.getOrElse(i) { 0 })
                if (comparison != 0) return comparison > 0
            }
            if (a.pre == null || b.pre == null) return a.pre == null && b.pre != null
            for (i in 0 until maxOf(a.pre.size, b.pre.size)) {
                val x = a.pre.getOrNull(i) ?: return false
                val y = b.pre.getOrNull(i) ?: return true
                if (x == y) continue
                val xn = x.all { it in '0'..'9' }
                val yn = y.all { it in '0'..'9' }
                val comparison = if (xn && yn) {
                    val xx = x.trimStart('0').ifEmpty { "0" }
                    val yy = y.trimStart('0').ifEmpty { "0" }
                    xx.length.compareTo(yy.length).takeIf { it != 0 } ?: xx.compareTo(yy)
                } else if (xn != yn) {
                    if (xn) -1 else 1
                } else x.compareTo(y)
                if (comparison != 0) return comparison > 0
            }
            return false
        }
    }
}
