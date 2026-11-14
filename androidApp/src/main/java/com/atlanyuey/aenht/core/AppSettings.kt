package com.atlanyuey.aenht.core

import com.atlanyuey.aenht.shared.site.Site

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.builtins.serializer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

enum class ThemeMode(val raw: String, val title: String) {
    System("system", "System"), Light("light", "Light"), Dark("dark", "Dark");

    companion object {
        fun of(raw: String?) = entries.firstOrNull { it.raw == raw } ?: System
    }
}

enum class AccentChoice(val raw: String) {
    Blue("blue"), Indigo("indigo"), Purple("purple"), Pink("pink"), Red("red"), Orange("orange"),
    Yellow("yellow"), Green("green"), Mint("mint"), Teal("teal"), Cyan("cyan"), Brown("brown");

    companion object {
        fun of(raw: String?) = entries.firstOrNull { it.raw == raw } ?: Blue
    }
}

enum class GalleryListStyle(val raw: String, val title: String, val preferredCardWidth: Float) {
    Waterfall("waterfall", "Waterfall", 190f),
    Compact("compact", "Waterfall (Compact)", 190f),
    Grid("grid", "Grid", 150f),
    List("list", "List", 600f);

    companion object {
        fun of(raw: String?) = entries.firstOrNull { it.raw == raw } ?: Waterfall
    }
}

/** Where the tab bar sits on large screens. */
/** Where the tablet tab bar sits when it doesn't follow the hand. */
enum class TabBarPosition(val raw: String, val title: String) {
    Leading("leading", "Left"),
    Center("center", "Center"),
    Trailing("trailing", "Right");

    companion object {
        fun of(raw: String?) = entries.firstOrNull { it.raw == raw } ?: Center
    }
}

/**
 * How History and Downloads show their galleries: cards like Favorites (in
 * the list style), or a list with reading / download progress.
 */
enum class LibraryStyle(val raw: String, val title: String) {
    Cards("cards", "Cards"),
    List("list", "List");

    companion object {
        fun of(raw: String?) = entries.firstOrNull { it.raw == raw } ?: Cards
    }
}

/** How quickly the tablet tab bar follows the hand. */
enum class FollowSpeed(val raw: String, val title: String, val touches: Int, val durationMillis: Int) {
    Fast("fast", "Fast", 1, 300),
    Medium("medium", "Medium", 2, 450),
    Slow("slow", "Slow", 4, 650);

    companion object {
        fun of(raw: String?) = entries.firstOrNull { it.raw == raw } ?: Fast
    }
}

/**
 * Follows the hand in use: the side of the screen touched last ([record]'s
 * `touches` in a row on the other side move it). Touches near the middle
 * keep the side.
 */
object HandTracker {
    enum class Side { Leading, Center, Trailing }

    var side by androidx.compose.runtime.mutableStateOf(Side.Trailing)
        private set
    private var pending: Side? = null
    private var streak = 0

    /** A touch at [fraction] of the screen width (0 = left edge). */
    fun record(fraction: Float, touches: Int) {
        val next = when {
            fraction > 0.6f -> Side.Trailing
            fraction < 0.4f -> Side.Leading
            else -> return
        }
        if (next == side) {
            pending = null
            streak = 0
            return
        }
        if (pending == next) streak++ else {
            pending = next
            streak = 1
        }
        if (streak < touches) return
        pending = null
        streak = 0
        side = next
    }
}

/** Two pages side by side in the left-to-right / right-to-left modes. */
enum class DoublePage(val raw: String, val title: String) {
    Off("off", "Off"),
    Landscape("landscape", "In Landscape"),
    Always("always", "Always");

    companion object {
        fun of(raw: String?) = entries.firstOrNull { it.raw == raw } ?: Landscape
    }
}

enum class ReadMode(val raw: String, val title: String) {
    LeftToRight("leftToRight", "Left to Right"),
    RightToLeft("rightToLeft", "Right to Left"),
    VerticalPaged("verticalPaged", "Top to Bottom"),
    Vertical("vertical", "Continuous (Webtoon)"),
    VerticalGap("verticalGap", "Continuous with Gaps");

    /** Whether pages are turned one at a time. */
    val isPaged: Boolean get() = this != Vertical && this != VerticalGap

    companion object {
        fun of(raw: String?) = entries.firstOrNull { it.raw == raw } ?: LeftToRight
    }
}

enum class SearchSort(val raw: String, val title: String) {
    Recent("date", "Recent"),
    PopularToday("popular-today", "Popular Today"),
    PopularWeek("popular-week", "Popular This Week"),
    PopularMonth("popular-month", "Popular This Month"),
    Popular("popular", "All-Time Popular");

    companion object {
        fun of(raw: String?, default: SearchSort) = entries.firstOrNull { it.raw == raw } ?: default
    }
}

enum class LanguageFilter(val raw: String, val title: String) {
    All("", "All Languages"),
    Japanese("japanese", "Japanese"),
    Chinese("chinese", "Chinese"),
    English("english", "English"),
    Translated("translated", "Translated");

    /** Search syntax added to a query. */
    val queryTerm: String? get() = if (this == All) null else "language:$raw"

    companion object {
        fun of(raw: String?) = entries.firstOrNull { it.raw == raw } ?: All
    }
}

/** A setting kept in Compose state and saved to SharedPreferences. */
/** Which site a screen shows: a fixed one, both, or the one the home page is on. */
enum class SiteChoice(val raw: String, val title: String) {
    Follow("follow", "Same as Home"),
    Nhentai("nh", "nhentai"),
    Ehentai("eh", "E-Hentai"),
    All("all", "All");

    companion object {
        fun of(raw: String?, default: SiteChoice) = entries.firstOrNull { it.raw == raw } ?: default
    }
}

/** What the E-Hentai home page lists. */
enum class EhHomeList(val raw: String, val title: String) {
    Latest("latest", "Latest"),
    Popular("popular", "Popular Now"),
    Watched("watched", "Watched"),
    ToplistDay("top-day", "Top Galleries Yesterday"),
    ToplistMonth("top-month", "Top Galleries This Month"),
    ToplistYear("top-year", "Top Galleries This Year"),
    ToplistAll("top-all", "Top Galleries of All Time");

    companion object {
        fun of(raw: String?) = entries.firstOrNull { it.raw == raw } ?: Latest
    }
}

private class Pref<T>(initial: T, private val onSet: (T) -> Unit) : ReadWriteProperty<Any?, T> {
    private val state = mutableStateOf(initial)
    override fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        if (state.value == value) return
        state.value = value
        onSet(value)
    }
}

/** App settings; the keys and values match the iOS app (export / import). */
object AppSettings {
    private lateinit var prefs: SharedPreferences
    private var loading = false

    fun init(context: Context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        loading = true
        val p = prefs
        theme = ThemeMode.of(p.getString("theme", null))
        accent = AccentChoice.of(p.getString("accent", null))
        listStyle = GalleryListStyle.of(p.getString("listStyle", null))
        phoneColumnsPortrait = portraitColumns(p.intOrNull("phoneColumnsPortrait")) ?: 2
        phoneColumnsLandscape = landscapeColumns(p.intOrNull("phoneColumnsLandscape")) ?: 4
        padColumnsPortrait = portraitColumns(p.intOrNull("padColumnsPortrait")) ?: 3
        padColumnsLandscape = landscapeColumns(p.intOrNull("padColumnsLandscape")) ?: 5
        blurCovers = p.getBoolean("blurCovers", false)
        showTags = p.getBoolean("showTags", true)
        translateTags = if (p.contains("translateTags")) p.getBoolean("translateTags", false)
        else java.util.Locale.getDefault().language == "zh"
        homeLanguage = LanguageFilter.of(p.getString("homeLanguage", null))
        homeSort = SearchSort.of(p.getString("homeSort", null), SearchSort.Recent)
        searchLanguage = LanguageFilter.of(p.getString("searchLanguage", null))
        searchSort = SearchSort.of(p.getString("searchSort", null), SearchSort.Popular)
        readMode = ReadMode.of(p.getString("readMode", null))
        autoReadInterval = p.getFloat("autoReadInterval", 3f).toDouble()
        preloadPages = p.getInt("preloadPages", 4)
        tapToTurnPages = p.getBoolean("tapToTurnPages", true)
        animatePageTurns = p.getBoolean("animatePageTurns", true)
        pageGap = p.getFloat("pageGap", 6f).toDouble()
        keepScreenOn = p.getBoolean("keepScreenOn", true)
        detectClipboard = p.getBoolean("detectClipboard", true)
        tabBarPosition = TabBarPosition.of(p.getString("tabBarPosition", null))
        followHand = p.getBoolean("followHand", false)
        followSpeed = FollowSpeed.of(p.getString("followSpeed", null))
        saveSearchHistory = p.getBoolean("saveSearchHistory", true)
        hideBarsOnScroll = p.getBoolean("hideBarsOnScroll", false)
        readerControlsDelay = p.getFloat("readerControlsDelay", 3f).toDouble().coerceIn(3.0, 10.0)
        libraryStyle = LibraryStyle.of(p.getString("libraryStyle", null))
        nhentaiEnabled = p.getBoolean("nhentaiEnabled", true)
        ehentaiEnabled = p.getBoolean("ehentaiEnabled", true)
        homeSite = p.getString("homeSite", null)?.let { Site.fromKey(it) } ?: Site.NHENTAI
        homeSiteDefault = SiteChoice.of(p.getString("homeSiteDefault", null), SiteChoice.Follow)
        useExHentai = p.getBoolean("useExHentai", false)
        ehHomeList = EhHomeList.of(p.getString("ehHomeList", null))
        favoritesSite = SiteChoice.of(p.getString("favoritesSite", null), SiteChoice.Follow)
        historySite = SiteChoice.of(p.getString("historySite", null), SiteChoice.All)
        downloadsSite = SiteChoice.of(p.getString("downloadsSite", null), SiteChoice.All)
        searchSite = SiteChoice.of(p.getString("searchSite", null), SiteChoice.Follow)
        searchBothSites = p.getBoolean("searchBothSites", false)
        filterRules = p.getString("filterRules", "") ?: ""
        llmServices = p.getString("llmServices", "") ?: ""
        volumeKeyTurns = p.getBoolean("volumeKeyTurns", false)
        readerBrightness = p.getFloat("readerBrightness", 1f).toDouble().coerceIn(0.2, 1.0)
        readerGrayscale = p.getBoolean("readerGrayscale", false)
        readerInvert = p.getBoolean("readerInvert", false)
        deviceTranslation = p.getBoolean("deviceTranslation", true)
        doublePage = DoublePage.of(p.getString("doublePage", null))
        doublePageCover = p.getBoolean("doublePageCover", true)
        autoCrop = p.getBoolean("autoCrop", false)
        useNhBlacklist = p.getBoolean("useNhBlacklist", true)
        favoritesAllFolders = p.getString("favoritesAllFolders", null)?.split(",")?.mapNotNull { it.toIntOrNull() }?.toSet() ?: (0..9).toSet()
        when (homeSiteDefault) {
            SiteChoice.Nhentai -> homeSite = Site.NHENTAI
            SiteChoice.Ehentai -> homeSite = Site.EHENTAI
            else -> {}
        }
        recentSearches.clear()
        p.getString("recentSearches", null)?.let { text ->
            runCatching { recentSearches.addAll(json.decodeFromString(ListSerializer(String.serializer()), text)) }
        }
        loading = false
    }

    private fun SharedPreferences.intOrNull(key: String) = if (contains(key)) getInt(key, 0) else null

    /** Applying settings from WebDAV: their change time is kept, not reset to now. */
    private var applyingSync = false

    /** When the settings last changed on this device (or came from another one), for WebDAV sync. */
    val changedAt: Long get() = if (::prefs.isInitialized) prefs.getLong("settingsChangedAt", 0L) else 0L

    private fun save(key: String, value: Any) {
        if (!::prefs.isInitialized || loading) return
        if (!applyingSync) prefs.edit().putLong("settingsChangedAt", System.currentTimeMillis()).apply()
        prefs.edit().apply {
            when (value) {
                is String -> putString(key, value)
                is Int -> putInt(key, value)
                is Boolean -> putBoolean(key, value)
                is Double -> putFloat(key, value.toFloat())
                is Float -> putFloat(key, value)
            }
        }.apply()
    }

    var theme by Pref(ThemeMode.System) { save("theme", it.raw) }
    var accent by Pref(AccentChoice.Blue) { save("accent", it.raw) }
    var listStyle by Pref(GalleryListStyle.Waterfall) { save("listStyle", it.raw) }
    /**
     * Columns of cards in portrait and landscape, a pair for phones and one
     * for tablets (the same keys as the iOS app's iPhone / iPad settings).
     */
    var phoneColumnsPortrait by Pref(2) { save("phoneColumnsPortrait", it) }
    var phoneColumnsLandscape by Pref(4) { save("phoneColumnsLandscape", it) }
    var padColumnsPortrait by Pref(3) { save("padColumnsPortrait", it) }
    var padColumnsLandscape by Pref(5) { save("padColumnsLandscape", it) }

    val PORTRAIT_COLUMNS = 1..6
    val LANDSCAPE_COLUMNS = 1..10

    private fun portraitColumns(value: Int?) = value?.coerceIn(PORTRAIT_COLUMNS)
    private fun landscapeColumns(value: Int?) = value?.coerceIn(LANDSCAPE_COLUMNS)

    /** Columns of cards on a phone or a [tablet] in the orientation. */
    fun columns(tablet: Boolean, landscape: Boolean) = when {
        tablet && landscape -> padColumnsLandscape
        tablet -> padColumnsPortrait
        landscape -> phoneColumnsLandscape
        else -> phoneColumnsPortrait
    }
    var blurCovers by Pref(false) { save("blurCovers", it) }
    var showTags by Pref(true) { save("showTags", it) }
    var translateTags by Pref(false) { save("translateTags", it) }

    var homeLanguage by Pref(LanguageFilter.All) { save("homeLanguage", it.raw) }
    var homeSort by Pref(SearchSort.Recent) { save("homeSort", it.raw) }
    var searchLanguage by Pref(LanguageFilter.All) { save("searchLanguage", it.raw) }
    var searchSort by Pref(SearchSort.Popular) { save("searchSort", it.raw) }

    var readMode by Pref(ReadMode.LeftToRight) { save("readMode", it.raw) }
    var autoReadInterval by Pref(3.0) { save("autoReadInterval", it) }
    var preloadPages by Pref(4) { save("preloadPages", it) }
    var tapToTurnPages by Pref(true) { save("tapToTurnPages", it) }
    var animatePageTurns by Pref(true) { save("animatePageTurns", it) }
    /** Gap between pages in the "Continuous with Gaps" mode, in dp. */
    var pageGap by Pref(6.0) { save("pageGap", it) }
    var keepScreenOn by Pref(true) { save("keepScreenOn", it) }
    var detectClipboard by Pref(true) { save("detectClipboard", it) }
    var tabBarPosition by Pref(TabBarPosition.Center) { save("tabBarPosition", it.raw) }
    /** Tablets: the tab bar moves to the side of the hand in use. */
    var followHand by Pref(false) { save("followHand", it) }
    var followSpeed by Pref(FollowSpeed.Fast) { save("followSpeed", it.raw) }
    var saveSearchHistory by Pref(true) { save("saveSearchHistory", it) }
    /** Scrolling a list down slides the tab bar away. */
    var hideBarsOnScroll by Pref(false) { save("hideBarsOnScroll", it) }
    /** Seconds without a touch before the reader's controls hide (3 at least). */
    var readerControlsDelay by Pref(3.0) { save("readerControlsDelay", it) }
    var libraryStyle by Pref(LibraryStyle.Cards) { save("libraryStyle", it.raw) }

    // MARK: Sites

    var nhentaiEnabled by Pref(true) { save("nhentaiEnabled", it) }
    var ehentaiEnabled by Pref(true) { save("ehentaiEnabled", it) }

    /** The sites in use (at least one). */
    val enabledSites: List<Site>
        get() = buildList {
            if (nhentaiEnabled) add(Site.NHENTAI)
            if (ehentaiEnabled) add(Site.EHENTAI)
        }.ifEmpty { listOf(Site.NHENTAI) }

    /** The site the home page is on now (remembered). */
    var homeSite by Pref(Site.NHENTAI) { save("homeSite", it.key) }
    /** Which site the home page opens on: the last one, or a fixed one. */
    var homeSiteDefault by Pref(SiteChoice.Follow) { save("homeSiteDefault", it.raw) }
    /** E-Hentai requests go to exhentai.org (applied by [EhClient] on each request). */
    var useExHentai by Pref(false) { save("useExHentai", it) }
    var ehHomeList by Pref(EhHomeList.Latest) { save("ehHomeList", it.raw) }

    /** Which site Favorites / History / Downloads / Search show when opened. */
    var favoritesSite by Pref(SiteChoice.Follow) { save("favoritesSite", it.raw) }
    var historySite by Pref(SiteChoice.All) { save("historySite", it.raw) }
    var downloadsSite by Pref(SiteChoice.All) { save("downloadsSite", it.raw) }
    var searchSite by Pref(SiteChoice.Follow) { save("searchSite", it.raw) }
    /** E-Hentai favorite folders that Favorites › All shows (all ten by default). */
    var favoritesAllFolders by Pref((0..9).toSet()) { save("favoritesAllFolders", it.sorted().joinToString(",")) }

    /** Reader: the volume keys turn pages. */
    var volumeKeyTurns by Pref(false) { save("volumeKeyTurns", it) }
    /** Reader: page brightness (0.2–1), separate from the screen's. */
    var readerBrightness by Pref(1.0) { save("readerBrightness", it) }
    var readerGrayscale by Pref(false) { save("readerGrayscale", it) }
    var readerInvert by Pref(false) { save("readerInvert", it) }
    /** Translation: the device's own translator is the last fallback (or the only one). */
    var deviceTranslation by Pref(true) { save("deviceTranslation", it) }
    /** Reader: two pages side by side (left-to-right / right-to-left modes). */
    var doublePage by Pref(DoublePage.Landscape) { save("doublePage", it.raw) }
    /** Reader, double page: the first page (the cover) stays alone. */
    var doublePageCover by Pref(true) { save("doublePageCover", it) }
    /** Reader: plain white / black margins are cut off the pages (paged modes). */
    var autoCrop by Pref(false) { save("autoCrop", it) }

    /** The user's language models for translation, in order (JSON; keys are in the Keystore). */
    var llmServices by Pref("") { save("llmServices", it) }

    /** Filter rules for both sites (JSON, see ContentFilter). */
    var filterRules by Pref("") { save("filterRules", it) }
    /** Lists also leave out galleries with tags on the nhentai account's blacklist. */
    var useNhBlacklist by Pref(true) { save("useNhBlacklist", it) }

    /** Search runs on both sites at once (twice the requests). */
    var searchBothSites by Pref(false) { save("searchBothSites", it) }

    /** The home page's site, if it is still enabled. */
    val currentSite: Site get() = homeSite.takeIf { it in enabledSites } ?: enabledSites.first()

    /** The site a new search starts on. */
    fun searchStartSite(): Site = site(searchSite) ?: currentSite

    /** The site a screen opens on for [choice]; null means both. */
    fun site(choice: SiteChoice): Site? {
        if (enabledSites.size == 1) return enabledSites.first()
        return when (choice) {
            SiteChoice.Follow -> currentSite
            SiteChoice.Nhentai -> Site.NHENTAI
            SiteChoice.Ehentai -> Site.EHENTAI
            SiteChoice.All -> null
        }
    }

    /** Recent search terms, newest first. */
    val recentSearches = mutableStateListOf<String>()
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /** Puts [term] first in the search history (when history is on). */
    fun addRecentSearch(term: String) {
        if (!saveSearchHistory) return
        recentSearches.remove(term)
        recentSearches.add(0, term)
        while (recentSearches.size > RECENT_SEARCH_LIMIT) recentSearches.removeAt(recentSearches.lastIndex)
        saveRecentSearches()
    }

    fun removeRecentSearch(term: String) {
        recentSearches.remove(term)
        saveRecentSearches()
    }

    fun clearRecentSearches() {
        recentSearches.clear()
        saveRecentSearches()
    }

    private fun setRecentSearches(items: List<String>) {
        recentSearches.clear()
        recentSearches.addAll(items.take(RECENT_SEARCH_LIMIT))
        saveRecentSearches()
    }

    private fun saveRecentSearches() {
        if (!::prefs.isInitialized || loading) return
        prefs.edit().putString("recentSearches", json.encodeToString(ListSerializer(String.serializer()), recentSearches.toList())).apply()
    }

    private const val RECENT_SEARCH_LIMIT = 20


    /** Every setting, keyed as on iOS (used by export). */
    fun snapshot(): Map<String, Any> = mapOf(
        "theme" to theme.raw,
        "accent" to accent.raw,
        "listStyle" to listStyle.raw,
        "phoneColumnsPortrait" to phoneColumnsPortrait,
        "phoneColumnsLandscape" to phoneColumnsLandscape,
        "padColumnsPortrait" to padColumnsPortrait,
        "padColumnsLandscape" to padColumnsLandscape,
        "blurCovers" to blurCovers,
        "showTags" to showTags,
        "translateTags" to translateTags,
        "homeLanguage" to homeLanguage.raw,
        "homeSort" to homeSort.raw,
        "searchLanguage" to searchLanguage.raw,
        "searchSort" to searchSort.raw,
        "readMode" to readMode.raw,
        "autoReadInterval" to autoReadInterval,
        "preloadPages" to preloadPages,
        "tapToTurnPages" to tapToTurnPages,
        "animatePageTurns" to animatePageTurns,
        "pageGap" to pageGap,
        "keepScreenOn" to keepScreenOn,
        "detectClipboard" to detectClipboard,
        "tabBarPosition" to tabBarPosition.raw,
        "followHand" to followHand,
        "followSpeed" to followSpeed.raw,
        "saveSearchHistory" to saveSearchHistory,
        "hideBarsOnScroll" to hideBarsOnScroll,
        "readerControlsDelay" to readerControlsDelay,
        "libraryStyle" to libraryStyle.raw,
        "nhentaiEnabled" to nhentaiEnabled,
        "ehentaiEnabled" to ehentaiEnabled,
        "homeSiteDefault" to homeSiteDefault.raw,
        "useExHentai" to useExHentai,
        "favoritesSite" to favoritesSite.raw,
        "historySite" to historySite.raw,
        "downloadsSite" to downloadsSite.raw,
        "searchSite" to searchSite.raw,
        "searchBothSites" to searchBothSites,
        "favoritesAllFolders" to favoritesAllFolders.sorted().joinToString(","),
        "filterRules" to filterRules,
        "llmServices" to llmServices,
        "volumeKeyTurns" to volumeKeyTurns,
        "readerBrightness" to readerBrightness,
        "readerGrayscale" to readerGrayscale,
        "readerInvert" to readerInvert,
        "deviceTranslation" to deviceTranslation,
        "doublePage" to doublePage.raw,
        "doublePageCover" to doublePageCover,
        "autoCrop" to autoCrop,
        "useNhBlacklist" to useNhBlacklist,
        "recentSearches" to recentSearches.toList(),
    )

    /** Applies imported settings; unknown keys and invalid values are ignored. */
    /** The settings as JSON: the export file (the same as the iOS app's) and WebDAV sync. */
    fun toJson(pretty: Boolean = false): String {
        val o = kotlinx.serialization.json.buildJsonObject {
            snapshot().toSortedMap().forEach { (key, value) ->
                when (value) {
                    is Boolean -> put(key, kotlinx.serialization.json.JsonPrimitive(value))
                    is Number -> put(key, kotlinx.serialization.json.JsonPrimitive(value))
                    is List<*> -> put(key, kotlinx.serialization.json.JsonArray(value.map { kotlinx.serialization.json.JsonPrimitive(it.toString()) }))
                    else -> put(key, kotlinx.serialization.json.JsonPrimitive(value.toString()))
                }
            }
        }
        return kotlinx.serialization.json.Json { prettyPrint = pretty }.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), o)
    }

    /** Applies settings from a JSON file or from WebDAV ([changedAt]: the other device's change time). */
    fun applyJson(text: String, changedAt: Long? = null) {
        val o = Net.json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
        val values = o.mapValues { (_, v) ->
            when (v) {
                is kotlinx.serialization.json.JsonPrimitive ->
                    if (v.isString) v.content else v.booleanOrNull ?: v.longOrNull ?: v.doubleOrNull ?: v.content
                is kotlinx.serialization.json.JsonArray -> v.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                else -> null
            }
        }
        if (changedAt == null) { apply(values); return }
        applyingSync = true
        try { apply(values) } finally { applyingSync = false }
        prefs.edit().putLong("settingsChangedAt", changedAt).apply()
    }

    fun apply(values: Map<String, Any?>) {
        for ((key, value) in values) {
            val string = value as? String
            val number = value as? Number
            val bool = value as? Boolean
            when (key) {
                "theme" -> string?.let { theme = ThemeMode.of(it) }
                "accent" -> string?.let { accent = AccentChoice.of(it) }
                "listStyle" -> string?.let { listStyle = GalleryListStyle.of(it) }
                "phoneColumnsPortrait" -> number?.let { phoneColumnsPortrait = it.toInt().coerceIn(PORTRAIT_COLUMNS) }
                "phoneColumnsLandscape" -> number?.let { phoneColumnsLandscape = it.toInt().coerceIn(LANDSCAPE_COLUMNS) }
                "padColumnsPortrait" -> number?.let { padColumnsPortrait = it.toInt().coerceIn(PORTRAIT_COLUMNS) }
                "padColumnsLandscape" -> number?.let { padColumnsLandscape = it.toInt().coerceIn(LANDSCAPE_COLUMNS) }
                "blurCovers" -> bool?.let { blurCovers = it }
                "showTags" -> bool?.let { showTags = it }
                "translateTags" -> bool?.let { translateTags = it }
                "homeLanguage" -> string?.let { homeLanguage = LanguageFilter.of(it) }
                "homeSort" -> string?.let { homeSort = SearchSort.of(it, homeSort) }
                "searchLanguage" -> string?.let { searchLanguage = LanguageFilter.of(it) }
                "searchSort" -> string?.let { searchSort = SearchSort.of(it, searchSort) }
                "readMode" -> string?.let { readMode = ReadMode.of(it) }
                "autoReadInterval" -> number?.let { autoReadInterval = it.toDouble().coerceIn(1.0, 10.0) }
                "preloadPages" -> number?.let { preloadPages = it.toInt().coerceIn(1, 10) }
                "tapToTurnPages" -> bool?.let { tapToTurnPages = it }
                "animatePageTurns" -> bool?.let { animatePageTurns = it }
                "pageGap" -> number?.let { pageGap = it.toDouble().coerceIn(2.0, 60.0) }
                "keepScreenOn" -> bool?.let { keepScreenOn = it }
                "detectClipboard" -> bool?.let { detectClipboard = it }
                "tabBarPosition" -> string?.let { tabBarPosition = TabBarPosition.of(it) }
                "followHand" -> bool?.let { followHand = it }
                "followSpeed" -> string?.let { followSpeed = FollowSpeed.of(it) }
                "saveSearchHistory" -> bool?.let { saveSearchHistory = it }
                "hideBarsOnScroll" -> bool?.let { hideBarsOnScroll = it }
                "readerControlsDelay" -> number?.let { readerControlsDelay = it.toDouble().coerceIn(3.0, 10.0) }
                "libraryStyle" -> string?.let { libraryStyle = LibraryStyle.of(it) }
                "nhentaiEnabled" -> bool?.let { nhentaiEnabled = it }
                "ehentaiEnabled" -> bool?.let { ehentaiEnabled = it }
                "homeSiteDefault" -> string?.let { homeSiteDefault = SiteChoice.of(it, homeSiteDefault) }
                "useExHentai" -> bool?.let { useExHentai = it }
                "favoritesSite" -> string?.let { favoritesSite = SiteChoice.of(it, favoritesSite) }
                "historySite" -> string?.let { historySite = SiteChoice.of(it, historySite) }
                "downloadsSite" -> string?.let { downloadsSite = SiteChoice.of(it, downloadsSite) }
                "searchSite" -> string?.let { searchSite = SiteChoice.of(it, searchSite) }
                "searchBothSites" -> bool?.let { searchBothSites = it }
                "filterRules" -> string?.let { filterRules = it }
                "llmServices" -> string?.let { llmServices = it }
                "volumeKeyTurns" -> bool?.let { volumeKeyTurns = it }
                "readerBrightness" -> number?.let { readerBrightness = it.toDouble().coerceIn(0.2, 1.0) }
                "readerGrayscale" -> bool?.let { readerGrayscale = it }
                "readerInvert" -> bool?.let { readerInvert = it }
                "deviceTranslation" -> bool?.let { deviceTranslation = it }
                "doublePage" -> string?.let { doublePage = DoublePage.of(it) }
                "doublePageCover" -> bool?.let { doublePageCover = it }
                "autoCrop" -> bool?.let { autoCrop = it }
                "useNhBlacklist" -> bool?.let { useNhBlacklist = it }
                "favoritesAllFolders" -> string?.let { favoritesAllFolders = it.split(",").mapNotNull { v -> v.toIntOrNull() }.toSet() }
                "recentSearches" -> (value as? List<*>)?.let { list -> setRecentSearches(list.filterIsInstance<String>()) }
            }
        }
    }
}
