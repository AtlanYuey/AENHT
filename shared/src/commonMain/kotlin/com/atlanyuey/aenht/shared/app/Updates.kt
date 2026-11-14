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

/** Checks the project's releases (Settings › About › Check for Updates). */
class UpdateChecker internal constructor(private val http: HttpClient) {
    constructor() : this(buildHttpClient())

    @Throws(Exception::class)
    suspend fun check(currentVersion: String): UpdateInfo = withContext(Dispatchers.IO) {
        val text = http.get("https://api.github.com/repos/AtlanYuey/AENHT/releases?per_page=15") {
            header("Accept", "application/vnd.github+json")
        }.bodyAsText()
        val list = (lenientJson.parseToJsonElement(text) as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
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
        /** "v1.2.0" is newer than "1.1.9". */
        fun isNewer(tag: String, current: String): Boolean {
            fun parts(v: String) = v.trim().removePrefix("v").split('.', '-').mapNotNull { it.toIntOrNull() }
            val a = parts(tag)
            val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
