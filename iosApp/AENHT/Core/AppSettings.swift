import Observation
import Shared
import SwiftUI
import UIKit

enum ThemeMode: String, CaseIterable, Identifiable {
    case system, light, dark

    var id: String { rawValue }

    var title: String {
        switch self {
        case .system: String(localized: "System")
        case .light: String(localized: "Light")
        case .dark: String(localized: "Dark")
        }
    }

    var colorScheme: ColorScheme? {
        switch self {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }
}

enum AccentChoice: String, CaseIterable, Identifiable {
    case blue, indigo, purple, pink, red, orange, yellow, green, mint, teal, cyan, brown

    var id: String { rawValue }

    var color: Color {
        switch self {
        case .blue: .blue
        case .indigo: .indigo
        case .purple: .purple
        case .pink: .pink
        case .red: .red
        case .orange: .orange
        case .yellow: .yellow
        case .green: .green
        case .mint: .mint
        case .teal: .teal
        case .cyan: .cyan
        case .brown: .brown
        }
    }
}

enum GalleryListStyle: String, CaseIterable, Identifiable {
    case waterfall, compact, grid, list

    var id: String { rawValue }

    var title: String {
        switch self {
        case .waterfall: String(localized: "Waterfall")
        case .grid: String(localized: "Grid")
        case .compact: String(localized: "Waterfall (Compact)")
        case .list: String(localized: "List")
        }
    }

    var systemImage: String {
        switch self {
        case .waterfall: "rectangle.3.group"
        case .grid: "square.grid.2x2"
        case .compact: "photo.on.rectangle"
        case .list: "list.bullet"
        }
    }
}

/// Where the iPad tab bar sits at the bottom of the screen.
/// Where the iPad tab bar sits when it doesn't follow the hand.
enum TabBarPosition: String, CaseIterable, Identifiable {
    case leading, center, trailing

    var id: String { rawValue }

    var title: String {
        switch self {
        case .leading: String(localized: "Left")
        case .center: String(localized: "Center")
        case .trailing: String(localized: "Right")
        }
    }
}

/// How History and Downloads show their galleries: cards like Favorites
/// (in the list style), or a list with reading / download progress.
enum LibraryStyle: String, CaseIterable, Identifiable {
    case cards, list

    var id: String { rawValue }

    var title: String {
        switch self {
        case .cards: String(localized: "Cards")
        case .list: String(localized: "List")
        }
    }
}

/// How quickly the iPad tab bar follows the hand.
enum FollowSpeed: String, CaseIterable, Identifiable {
    case fast, medium, slow

    var id: String { rawValue }

    var title: String {
        switch self {
        case .fast: String(localized: "Fast")
        case .medium: String(localized: "Medium")
        case .slow: String(localized: "Slow")
        }
    }

    /// Touches in a row on the other side before the bar moves there.
    var touches: Int {
        switch self {
        case .fast: 1
        case .medium: 2
        case .slow: 4
        }
    }

    /// How long the bar takes to slide across.
    var duration: Double {
        switch self {
        case .fast: 0.3
        case .medium: 0.45
        case .slow: 0.65
        }
    }
}

/// How long the reader keeps the screen on after the last touch (so a
/// reader who falls asleep doesn't leave it on all night).
enum ScreenOn: String, CaseIterable, Identifiable {
    case off, fiveMinutes, tenMinutes, always

    var id: String { rawValue }
    var title: String {
        switch self {
        case .off: String(localized: "Off")
        case .fiveMinutes: String(localized: "5 Minutes")
        case .tenMinutes: String(localized: "10 Minutes")
        case .always: String(localized: "Always")
        }
    }

    /// nil: no time limit.
    var minutes: Int? {
        switch self {
        case .fiveMinutes: 5
        case .tenMinutes: 10
        default: nil
        }
    }
}

/// Where taps turn pages in the reader.
enum TapZones: String, CaseIterable, Identifiable {
    case leftRight, topBottom

    var id: String { rawValue }
    var title: String {
        switch self {
        case .leftRight: String(localized: "Left and Right")
        case .topBottom: String(localized: "Top and Bottom")
        }
    }
}

/// What a swipe does in the reader (one way the pages don't turn).
enum ReaderGesture: String, CaseIterable, Identifiable {
    case none, exit, controls, goToPage, autoRead

    var id: String { rawValue }
    var title: String {
        switch self {
        case .none: String(localized: "Nothing")
        case .exit: String(localized: "Exit Reader")
        case .controls: String(localized: "Show or Hide Controls")
        case .goToPage: String(localized: "Go to Page…")
        case .autoRead: String(localized: "Start or Stop Auto Read")
        }
    }
}

/// What the app shows when it is in the background (the app switcher's picture).
enum BackgroundPrivacy: String, CaseIterable, Identifiable {
    case off, blurApp, cover, blurContent, blurImages

    var id: String { rawValue }

    var title: String {
        switch self {
        case .off: String(localized: "Off")
        case .blurApp: String(localized: "Blur the Whole App")
        case .cover: String(localized: "Cover the App")
        case .blurContent: String(localized: "Blur Pictures and Text")
        case .blurImages: String(localized: "Blur Pictures")
        }
    }
}

/// A row of covers pinned above the Home feed (at most two per site).
enum HomeRow: String, CaseIterable, Identifiable {
    case nhPopular = "nh-popular", nhToday = "nh-today", nhWeek = "nh-week", nhMonth = "nh-month", nhAll = "nh-all"
    case ehPopular = "eh-popular", ehDay = "eh-day", ehMonth = "eh-month", ehYear = "eh-year", ehAll = "eh-all"

    static let maximum = 2

    var id: String { rawValue }
    var site: SiteKind { rawValue.hasPrefix("nh") ? .nhentai : .ehentai }

    /// The English title, also the localization key.
    var titleKey: String {
        switch self {
        case .nhPopular, .ehPopular: "Popular Now"
        case .nhToday: "Popular Today"
        case .nhWeek: "Popular This Week"
        case .nhMonth: "Popular This Month"
        case .nhAll: "All-Time Popular"
        case .ehDay: "Top Galleries Yesterday"
        case .ehMonth: "Top Galleries This Month"
        case .ehYear: "Top Galleries This Year"
        case .ehAll: "Top Galleries of All Time"
        }
    }

    var title: String { String(localized: String.LocalizationValue(titleKey)) }

    static func rows(for site: SiteKind) -> [HomeRow] { allCases.filter { $0.site == site } }
}

/// Two pages side by side in the left-to-right / right-to-left modes.
enum DoublePage: String, CaseIterable, Identifiable {
    case off, landscape, always

    var id: String { rawValue }

    var title: String {
        switch self {
        case .off: String(localized: "Off")
        case .landscape: String(localized: "In Landscape")
        case .always: String(localized: "Always")
        }
    }
}

enum ReadMode: String, CaseIterable, Identifiable {
    case leftToRight, rightToLeft, verticalPaged
    /// Continuous vertical scroll (webtoon style).
    case vertical
    /// Continuous vertical scroll with a black gap between pages.
    case verticalGap

    /// Whether pages are turned one at a time.
    var isPaged: Bool { self != .vertical && self != .verticalGap }

    var id: String { rawValue }

    var title: String {
        switch self {
        case .leftToRight: String(localized: "Left to Right")
        case .rightToLeft: String(localized: "Right to Left")
        case .verticalPaged: String(localized: "Top to Bottom")
        case .vertical: String(localized: "Continuous (Webtoon)")
        case .verticalGap: String(localized: "Continuous with Gaps")
        }
    }

    var systemImage: String {
        switch self {
        case .leftToRight: "arrow.right.square"
        case .rightToLeft: "arrow.left.square"
        case .verticalPaged: "arrow.down.square"
        case .vertical: "scroll"
        case .verticalGap: "rectangle.grid.1x2"
        }
    }
}

/// App settings, persisted in UserDefaults.
@Observable
final class AppSettings {
    static let shared = AppSettings()

    @ObservationIgnored private let defaults = UserDefaults.standard
    /// Set while values from iCloud are applied, so they are not sent back.
    @ObservationIgnored private var isApplyingSynced = false

    var theme: ThemeMode { didSet { save(theme.rawValue, "theme") } }
    var accent: AccentChoice { didSet { save(accent.rawValue, "accent") } }
    var listStyle: GalleryListStyle { didSet { save(listStyle.rawValue, "listStyle") } }
    /// Columns of cards in portrait and landscape, a pair for iPhones and
    /// one for iPads: each kind of device uses its own, also when synced.
    var phoneColumnsPortrait: Int { didSet { save(phoneColumnsPortrait, "phoneColumnsPortrait") } }
    var phoneColumnsLandscape: Int { didSet { save(phoneColumnsLandscape, "phoneColumnsLandscape") } }
    var padColumnsPortrait: Int { didSet { save(padColumnsPortrait, "padColumnsPortrait") } }
    var padColumnsLandscape: Int { didSet { save(padColumnsLandscape, "padColumnsLandscape") } }
    /// Whether the column counts sync over iCloud (stored on this device only).
    var syncColumns: Bool {
        didSet {
            defaults.set(syncColumns, forKey: "syncColumns")
            // Turned on here: this device's counts become the synced ones.
            if syncColumns, !oldValue { SettingsSync.shared.pushColumns() }
        }
    }
    var blurCovers: Bool { didSet { save(blurCovers, "blurCovers") } }
    var showTags: Bool { didSet { save(showTags, "showTags") } }
    var translateTags: Bool { didSet { save(translateTags, "translateTags") } }

    var homeLanguage: LanguageFilter { didSet { save(homeLanguage.rawValue, "homeLanguage") } }
    var homeSort: SearchSort { didSet { save(homeSort.rawValue, "homeSort") } }
    var searchLanguage: LanguageFilter { didSet { save(searchLanguage.rawValue, "searchLanguage") } }
    var searchSort: SearchSort { didSet { save(searchSort.rawValue, "searchSort") } }

    var readMode: ReadMode { didSet { save(readMode.rawValue, "readMode") } }
    var autoReadInterval: Double { didSet { save(autoReadInterval, "autoReadInterval") } }
    var autoScrollSecondsPerScreen: Double { didSet { save(autoScrollSecondsPerScreen, "autoScrollSecondsPerScreen") } }
    var preloadPages: Int { didSet { save(preloadPages, "preloadPages") } }
    var tapToTurnPages: Bool { didSet { save(tapToTurnPages, "tapToTurnPages") } }
    var animatePageTurns: Bool { didSet { save(animatePageTurns, "animatePageTurns") } }
    /// Gap between pages in the "Continuous with Gaps" mode, in points.
    var pageGap: Double { didSet { save(pageGap, "pageGap") } }
    var screenOn: ScreenOn { didSet { save(screenOn.rawValue, "screenOn") } }
    var detectClipboard: Bool { didSet { save(detectClipboard, "detectClipboard") } }
    var tabBarPosition: TabBarPosition { didSet { save(tabBarPosition.rawValue, "tabBarPosition") } }
    /// iPad: the tab bar moves to the side of the hand in use.
    var followHand: Bool { didSet { save(followHand, "followHand") } }
    var followSpeed: FollowSpeed { didSet { save(followSpeed.rawValue, "followSpeed") } }
    var saveSearchHistory: Bool { didSet { save(saveSearchHistory, "saveSearchHistory") } }
    /// Scrolling a list down slides the tab bar away.
    var hideBarsOnScroll: Bool { didSet { save(hideBarsOnScroll, "hideBarsOnScroll") } }
    /// Seconds without a touch before the reader's controls hide (3 at least).
    var readerControlsDelay: Double { didSet { save(readerControlsDelay, "readerControlsDelay") } }
    var libraryStyle: LibraryStyle { didSet { save(libraryStyle.rawValue, "libraryStyle") } }
    /// Recent search terms, newest first; synced with the settings.
    var recentSearches: [String] { didSet { save(recentSearches, "recentSearches") } }

    // MARK: Sites

    var nhentaiEnabled: Bool { didSet { save(nhentaiEnabled, "nhentaiEnabled") } }
    var ehentaiEnabled: Bool { didSet { save(ehentaiEnabled, "ehentaiEnabled") } }
    /// The site the home page is on (this device only).
    var homeSite: SiteKind { didSet { defaults.set(homeSite.rawValue, forKey: "homeSite") } }
    /// Which site the home page opens on: the last one, or a fixed one.
    var homeSiteDefault: SiteChoice { didSet { save(homeSiteDefault.rawValue, "homeSiteDefault") } }
    /// E-Hentai requests go to exhentai.org.
    var useExHentai: Bool { didSet { save(useExHentai, "useExHentai") } }
    var ehHomeList: EhHomeList { didSet { defaults.set(ehHomeList.rawValue, forKey: "ehHomeList") } }
    var favoritesSite: SiteChoice { didSet { save(favoritesSite.rawValue, "favoritesSite") } }
    var historySite: SiteChoice { didSet { save(historySite.rawValue, "historySite") } }
    var downloadsSite: SiteChoice { didSet { save(downloadsSite.rawValue, "downloadsSite") } }
    var searchSite: SiteChoice { didSet { save(searchSite.rawValue, "searchSite") } }
    /// Search runs on both sites at once (twice the requests).
    var searchBothSites: Bool { didSet { save(searchBothSites, "searchBothSites") } }
    /// Reader: page brightness (0.2–1), separate from the screen's.
    var readerBrightness: Double { didSet { save(readerBrightness, "readerBrightness") } }
    var readerGrayscale: Bool { didSet { save(readerGrayscale, "readerGrayscale") } }
    var readerInvert: Bool { didSet { save(readerInvert, "readerInvert") } }
    var superResolution: SuperResolutionMode { didSet { save(superResolution.rawValue, "superResolution") } }
    var superResolutionModel: SuperResolutionModel { didSet { save(superResolutionModel.rawValue, "superResolutionModel") } }
    /// Translation: Apple's on-device translation is the last fallback (or the only one).
    var deviceTranslation: Bool { didSet { save(deviceTranslation, "deviceTranslation") } }
    /// Reader: two pages side by side (left-to-right / right-to-left modes).
    var doublePage: DoublePage { didSet { save(doublePage.rawValue, "doublePage") } }
    /// Reader, double page: the first page (the cover) stays alone.
    var doublePageCover: Bool { didSet { save(doublePageCover, "doublePageCover") } }
    /// Double page: a turn moves on by one page instead of two.
    var doublePageSingleStep: Bool { didSet { save(doublePageSingleStep, "doublePageSingleStep") } }
    /// A swipe turns the page with a fade sweeping the way it went; the pages don't move with the finger.
    var swipeFades: Bool { didSet { save(swipeFades, "swipeFades") } }
    /// Reader: plain white / black margins are cut off the pages (paged modes).
    var autoCrop: Bool { didSet { save(autoCrop, "autoCrop") } }
    /// Rows pinned above the Home feed, both sites ("nh-popular,eh-popular").
    var homeRowsRaw: String { didSet { save(homeRowsRaw, "homeRows") } }
    /// E-Hentai commenters whose comments are hidden (names, one per line).
    var blockedCommenters: String { didSet { save(blockedCommenters, "blockedCommenters") } }
    /// Reader: taps on the left / right edges, or the top / bottom ones, turn pages.
    var tapZones: TapZones { didSet { save(tapZones.rawValue, "tapZones") } }
    /// Reader swipes the pages don't use: up / down in the left-right modes, left / right in the top-to-bottom ones.
    var swipeUp: ReaderGesture { didSet { save(swipeUp.rawValue, "swipeUp") } }
    var swipeDown: ReaderGesture { didSet { save(swipeDown.rawValue, "swipeDown") } }
    var swipeLeft: ReaderGesture { didSet { save(swipeLeft.rawValue, "swipeLeft") } }
    var swipeRight: ReaderGesture { didSet { save(swipeRight.rawValue, "swipeRight") } }
    /// Downloads: galleries downloading at once (the rest wait their turn).
    var downloadGalleries: Int { didSet { save(downloadGalleries, "downloadGalleries") } }
    /// Downloads: pages of one gallery at once (E-Hentai uses at most 3).
    var downloadPages: Int { didSet { save(downloadPages, "downloadPages") } }
    /// Downloads: tries again this many times when a page fails.
    var downloadRetries: Int { didSet { save(downloadRetries, "downloadRetries") } }
    /// Downloads: seconds to wait before each page.
    var downloadInterval: Double { didSet { save(downloadInterval, "downloadInterval") } }
    /// E-Hentai downloads: the original images (cost GP when large).
    var ehOriginalImages: Bool { didSet { save(ehOriginalImages, "ehOriginalImages") } }
    /// What the app shows in the background / app switcher.
    var backgroundPrivacy: BackgroundPrivacy { didSet { save(backgroundPrivacy.rawValue, "backgroundPrivacy") } }

    var blockedCommenterSet: Set<String> {
        Set(blockedCommenters.split(separator: "\n").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty })
    }

    func blockCommenter(_ name: String) {
        blockedCommenters = (blockedCommenterSet.union([name])).sorted().joined(separator: "\n")
    }

    func unblockCommenter(_ name: String) {
        blockedCommenters = blockedCommenterSet.subtracting([name]).sorted().joined(separator: "\n")
    }

    func homeRows(for site: SiteKind) -> [HomeRow] {
        Array(homeRowsRaw.split(separator: ",").compactMap { HomeRow(rawValue: String($0)) }.filter { $0.site == site }.prefix(HomeRow.maximum))
    }

    func setHomeRows(_ rows: [HomeRow], for site: SiteKind) {
        var unique: [HomeRow] = []
        for row in rows where !unique.contains(row) { unique.append(row) }
        let others = homeRowsRaw.split(separator: ",").compactMap { HomeRow(rawValue: String($0)) }.filter { $0.site != site }
        homeRowsRaw = (others + unique.prefix(HomeRow.maximum)).map(\.rawValue).joined(separator: ",")
    }
    /// The user's language models for translation, in order (JSON; keys are in the Keychain).
    var llmServices: String { didSet { save(llmServices, "llmServices") } }
    /// Filter rules for both sites (JSON, see the shared ContentFilter).
    var filterRules: String { didSet { save(filterRules, "filterRules") } }
    /// Lists also leave out galleries with tags on the nhentai account's blacklist.
    var useNhBlacklist: Bool { didSet { save(useNhBlacklist, "useNhBlacklist") } }
    /// E-Hentai favorite folders Favorites › All shows (all ten by default).
    var favoritesAllFolders: [Int] { didSet { save(favoritesAllFolders, "favoritesAllFolders") } }

    /// The sites in use (at least one).
    var enabledSites: [SiteKind] {
        let sites = SiteKind.allCases.filter { $0 == .nhentai ? nhentaiEnabled : ehentaiEnabled }
        return sites.isEmpty ? [.nhentai] : sites
    }

    /// The home page's site, if it is still in use.
    var currentSite: SiteKind { enabledSites.contains(homeSite) ? homeSite : enabledSites[0] }

    /// The site a screen opens on for [choice]; nil means both.
    func site(_ choice: SiteChoice) -> SiteKind? {
        if enabledSites.count == 1 { return enabledSites[0] }
        switch choice {
        case .follow: return currentSite
        case .nh: return .nhentai
        case .eh: return .ehentai
        case .all: return nil
        }
    }

    /// The site a new search starts on.
    var searchStartSite: SiteKind { site(searchSite) ?? currentSite }

    private init() {
        let d = UserDefaults.standard
        theme = ThemeMode(rawValue: d.string(forKey: "theme") ?? "") ?? .system
        accent = AccentChoice(rawValue: d.string(forKey: "accent") ?? "") ?? .blue
        listStyle = GalleryListStyle(rawValue: d.string(forKey: "listStyle") ?? "") ?? .waterfall
        phoneColumnsPortrait = Self.columns(d.object(forKey: "phoneColumnsPortrait"), landscape: false) ?? 2
        phoneColumnsLandscape = Self.columns(d.object(forKey: "phoneColumnsLandscape"), landscape: true) ?? 4
        padColumnsPortrait = Self.columns(d.object(forKey: "padColumnsPortrait"), landscape: false) ?? 3
        padColumnsLandscape = Self.columns(d.object(forKey: "padColumnsLandscape"), landscape: true) ?? 5
        syncColumns = d.object(forKey: "syncColumns") as? Bool ?? true
        blurCovers = d.bool(forKey: "blurCovers")
        showTags = d.object(forKey: "showTags") as? Bool ?? true
        translateTags = d.object(forKey: "translateTags") as? Bool
            ?? (Locale.preferredLanguages.first?.hasPrefix("zh") ?? false)
        homeLanguage = LanguageFilter(rawValue: d.string(forKey: "homeLanguage") ?? "") ?? .all
        homeSort = SearchSort(rawValue: d.string(forKey: "homeSort") ?? "") ?? .recent
        searchLanguage = LanguageFilter(rawValue: d.string(forKey: "searchLanguage") ?? "") ?? .all
        searchSort = SearchSort(rawValue: d.string(forKey: "searchSort") ?? "") ?? .popular
        readMode = ReadMode(rawValue: d.string(forKey: "readMode") ?? "") ?? .leftToRight
        autoReadInterval = d.object(forKey: "autoReadInterval") as? Double ?? 3
        autoScrollSecondsPerScreen = ReaderAutoScroll.shared.secondsPerScreen(value: d.object(forKey: "autoScrollSecondsPerScreen") as? Double ?? 10)
        preloadPages = d.object(forKey: "preloadPages") as? Int ?? 4
        tapToTurnPages = d.object(forKey: "tapToTurnPages") as? Bool ?? true
        animatePageTurns = d.object(forKey: "animatePageTurns") as? Bool ?? true
        pageGap = d.object(forKey: "pageGap") as? Double ?? 6
        screenOn = ScreenOn(rawValue: d.string(forKey: "screenOn") ?? "") ?? .fiveMinutes
        // Reading the clipboard shows the system paste prompt: opt in.
        detectClipboard = d.object(forKey: "detectClipboard") as? Bool ?? true
        tabBarPosition = TabBarPosition(rawValue: d.string(forKey: "tabBarPosition") ?? "") ?? .center
        followHand = d.object(forKey: "followHand") as? Bool ?? false
        followSpeed = FollowSpeed(rawValue: d.string(forKey: "followSpeed") ?? "") ?? .fast
        saveSearchHistory = d.object(forKey: "saveSearchHistory") as? Bool ?? true
        hideBarsOnScroll = d.object(forKey: "hideBarsOnScroll") as? Bool ?? false
        readerControlsDelay = min(max(d.object(forKey: "readerControlsDelay") as? Double ?? 3, 3), 10)
        libraryStyle = LibraryStyle(rawValue: d.string(forKey: "libraryStyle") ?? "") ?? .cards
        recentSearches = d.stringArray(forKey: "recentSearches") ?? []
        nhentaiEnabled = d.object(forKey: "nhentaiEnabled") as? Bool ?? true
        ehentaiEnabled = d.object(forKey: "ehentaiEnabled") as? Bool ?? true
        let siteDefault = SiteChoice(rawValue: d.string(forKey: "homeSiteDefault") ?? "") ?? .follow
        homeSiteDefault = siteDefault
        let lastSite = SiteKind(rawValue: d.string(forKey: "homeSite") ?? "") ?? .nhentai
        homeSite = siteDefault == .nh ? .nhentai : siteDefault == .eh ? .ehentai : lastSite
        useExHentai = d.bool(forKey: "useExHentai")
        ehHomeList = EhHomeList(rawValue: d.string(forKey: "ehHomeList") ?? "") ?? .latest
        favoritesSite = SiteChoice(rawValue: d.string(forKey: "favoritesSite") ?? "") ?? .follow
        historySite = SiteChoice(rawValue: d.string(forKey: "historySite") ?? "") ?? .all
        downloadsSite = SiteChoice(rawValue: d.string(forKey: "downloadsSite") ?? "") ?? .all
        searchSite = SiteChoice(rawValue: d.string(forKey: "searchSite") ?? "") ?? .follow
        searchBothSites = d.bool(forKey: "searchBothSites")
        favoritesAllFolders = (d.array(forKey: "favoritesAllFolders") as? [Int]) ?? Array(0..<10)
        filterRules = d.string(forKey: "filterRules") ?? ""
        llmServices = d.string(forKey: "llmServices") ?? ""
        readerBrightness = min(max(d.object(forKey: "readerBrightness") as? Double ?? 1, 0.2), 1)
        readerGrayscale = d.bool(forKey: "readerGrayscale")
        readerInvert = d.bool(forKey: "readerInvert")
        superResolution = SuperResolutionMode(rawValue: d.string(forKey: "superResolution") ?? "") ?? .auto
        superResolutionModel = SuperResolutionModel(rawValue: d.string(forKey: "superResolutionModel") ?? "") ?? .standard
        deviceTranslation = d.object(forKey: "deviceTranslation") as? Bool ?? true
        doublePage = DoublePage(rawValue: d.string(forKey: "doublePage") ?? "") ?? .landscape
        doublePageCover = d.object(forKey: "doublePageCover") as? Bool ?? true
        doublePageSingleStep = d.bool(forKey: "doublePageSingleStep")
        swipeFades = d.bool(forKey: "swipeFades")
        autoCrop = d.bool(forKey: "autoCrop")
        homeRowsRaw = d.string(forKey: "homeRows") ?? "nh-popular,nh-week,eh-day,eh-popular"
        blockedCommenters = d.string(forKey: "blockedCommenters") ?? ""
        tapZones = TapZones(rawValue: d.string(forKey: "tapZones") ?? "") ?? .leftRight
        swipeUp = ReaderGesture(rawValue: d.string(forKey: "swipeUp") ?? "") ?? .none
        swipeDown = ReaderGesture(rawValue: d.string(forKey: "swipeDown") ?? "") ?? .exit
        swipeLeft = ReaderGesture(rawValue: d.string(forKey: "swipeLeft") ?? "") ?? .none
        swipeRight = ReaderGesture(rawValue: d.string(forKey: "swipeRight") ?? "") ?? .exit
        downloadGalleries = min(max(d.object(forKey: "downloadGalleries") as? Int ?? 2, 1), 5)
        downloadPages = min(max(d.object(forKey: "downloadPages") as? Int ?? 3, 1), 6)
        downloadRetries = min(max(d.object(forKey: "downloadRetries") as? Int ?? 2, 0), 5)
        downloadInterval = min(max(d.double(forKey: "downloadInterval"), 0), 5)
        ehOriginalImages = d.bool(forKey: "ehOriginalImages")
        backgroundPrivacy = BackgroundPrivacy(rawValue: d.string(forKey: "backgroundPrivacy") ?? "") ?? .blurImages
        useNhBlacklist = d.object(forKey: "useNhBlacklist") as? Bool ?? true
    }

    /// When the settings last changed on this device (or came from WebDAV), in epoch milliseconds.
    var changedAt: Int64 {
        Int64(defaults.object(forKey: "settingsChangedAt") as? Double ?? 0)
    }

    func setChangedAt(_ value: Int64) { defaults.set(Double(value), forKey: "settingsChangedAt") }

    private func save(_ value: Any, _ key: String) {
        defaults.set(value, forKey: key)
        if !isApplyingSynced { setChangedAt(Int64(Date.now.timeIntervalSince1970 * 1000)) }
        if !isApplyingSynced, syncColumns || !Self.columnKeys.contains(key) {
            SettingsSync.shared.settingChanged(key, value)
        }
    }

    static let columnKeys: Set<String> = ["phoneColumnsPortrait", "phoneColumnsLandscape",
                                          "padColumnsPortrait", "padColumnsLandscape"]
    static let portraitColumns = 1...6
    static let landscapeColumns = 1...10

    private static func columns(_ value: Any?, landscape: Bool) -> Int? {
        guard let number = value as? Int else { return nil }
        let range = landscape ? landscapeColumns : portraitColumns
        return min(max(number, range.lowerBound), range.upperBound)
    }

    /// The settings iCloud sync shares: all of them, but the column counts
    /// only while Sync Columns is on.
    func syncedSnapshot() -> [String: Any] {
        snapshot().filter { syncColumns || !Self.columnKeys.contains($0.key) }
    }

    /// Every setting, keyed as in UserDefaults (used by iCloud sync and
    /// export).
    func snapshot() -> [String: Any] {
        var values: [String: Any] = [:]
        values["theme"] = theme.rawValue
        values["accent"] = accent.rawValue
        values["listStyle"] = listStyle.rawValue
        values["phoneColumnsPortrait"] = phoneColumnsPortrait
        values["phoneColumnsLandscape"] = phoneColumnsLandscape
        values["padColumnsPortrait"] = padColumnsPortrait
        values["padColumnsLandscape"] = padColumnsLandscape
        values["blurCovers"] = blurCovers
        values["showTags"] = showTags
        values["translateTags"] = translateTags
        values["homeLanguage"] = homeLanguage.rawValue
        values["homeSort"] = homeSort.rawValue
        values["searchLanguage"] = searchLanguage.rawValue
        values["searchSort"] = searchSort.rawValue
        values["readMode"] = readMode.rawValue
        values["autoReadInterval"] = autoReadInterval
        values["autoScrollSecondsPerScreen"] = autoScrollSecondsPerScreen
        values["preloadPages"] = preloadPages
        values["tapToTurnPages"] = tapToTurnPages
        values["animatePageTurns"] = animatePageTurns
        values["pageGap"] = pageGap
        values["screenOn"] = screenOn.rawValue
        values["detectClipboard"] = detectClipboard
        values["tabBarPosition"] = tabBarPosition.rawValue
        values["followHand"] = followHand
        values["followSpeed"] = followSpeed.rawValue
        values["saveSearchHistory"] = saveSearchHistory
        values["hideBarsOnScroll"] = hideBarsOnScroll
        values["readerControlsDelay"] = readerControlsDelay
        values["libraryStyle"] = libraryStyle.rawValue
        values["recentSearches"] = recentSearches
        values["nhentaiEnabled"] = nhentaiEnabled
        values["ehentaiEnabled"] = ehentaiEnabled
        values["homeSiteDefault"] = homeSiteDefault.rawValue
        values["useExHentai"] = useExHentai
        values["favoritesSite"] = favoritesSite.rawValue
        values["historySite"] = historySite.rawValue
        values["downloadsSite"] = downloadsSite.rawValue
        values["searchSite"] = searchSite.rawValue
        values["searchBothSites"] = searchBothSites
        values["filterRules"] = filterRules
        values["llmServices"] = llmServices
        values["readerBrightness"] = readerBrightness
        values["readerGrayscale"] = readerGrayscale
        values["readerInvert"] = readerInvert
        values["superResolution"] = superResolution.rawValue
        values["deviceTranslation"] = deviceTranslation
        values["doublePage"] = doublePage.rawValue
        values["doublePageCover"] = doublePageCover
        values["doublePageSingleStep"] = doublePageSingleStep
        values["swipeFades"] = swipeFades
        values["autoCrop"] = autoCrop
        values["homeRows"] = homeRowsRaw
        values["blockedCommenters"] = blockedCommenters
        values["tapZones"] = tapZones.rawValue
        values["swipeUp"] = swipeUp.rawValue
        values["swipeDown"] = swipeDown.rawValue
        values["swipeLeft"] = swipeLeft.rawValue
        values["swipeRight"] = swipeRight.rawValue
        values["downloadGalleries"] = downloadGalleries
        values["downloadPages"] = downloadPages
        values["downloadRetries"] = downloadRetries
        values["downloadInterval"] = downloadInterval
        values["ehOriginalImages"] = ehOriginalImages
        values["backgroundPrivacy"] = backgroundPrivacy.rawValue
        values["useNhBlacklist"] = useNhBlacklist
        values["favoritesAllFolders"] = favoritesAllFolders.sorted().map(String.init).joined(separator: ",")
        return values
    }

    /// Applies settings from iCloud (`fromSync`, not sent back) or from an
    /// imported file (sent on to iCloud like any other change). Unknown keys
    /// and invalid values are ignored.
    func apply(_ values: [String: Any], fromSync: Bool) {
        isApplyingSynced = fromSync
        defer { isApplyingSynced = false }
        for (key, value) in values {
            // Column counts from iCloud only while they are synced.
            if fromSync, !syncColumns, Self.columnKeys.contains(key) { continue }
            apply(value, forKey: key)
        }
    }

    private func apply(_ value: Any, forKey key: String) {
        func set<T: Equatable>(_ path: ReferenceWritableKeyPath<AppSettings, T>, _ new: T?) {
            if let new, self[keyPath: path] != new {
                self[keyPath: path] = new
            }
        }
        let string = value as? String
        let number = value as? NSNumber
        switch key {
        case "theme": set(\.theme, string.flatMap(ThemeMode.init(rawValue:)))
        case "accent": set(\.accent, string.flatMap(AccentChoice.init(rawValue:)))
        case "listStyle": set(\.listStyle, string.flatMap(GalleryListStyle.init(rawValue:)))
        case "phoneColumnsPortrait": set(\.phoneColumnsPortrait, Self.columns(number?.intValue, landscape: false))
        case "phoneColumnsLandscape": set(\.phoneColumnsLandscape, Self.columns(number?.intValue, landscape: true))
        case "padColumnsPortrait": set(\.padColumnsPortrait, Self.columns(number?.intValue, landscape: false))
        case "padColumnsLandscape": set(\.padColumnsLandscape, Self.columns(number?.intValue, landscape: true))
        case "blurCovers": set(\.blurCovers, number?.boolValue)
        case "showTags": set(\.showTags, number?.boolValue)
        case "translateTags": set(\.translateTags, number?.boolValue)
        case "homeLanguage": set(\.homeLanguage, string.flatMap(LanguageFilter.init(rawValue:)))
        case "homeSort": set(\.homeSort, string.flatMap(SearchSort.init(rawValue:)))
        case "searchLanguage": set(\.searchLanguage, string.flatMap(LanguageFilter.init(rawValue:)))
        case "searchSort": set(\.searchSort, string.flatMap(SearchSort.init(rawValue:)))
        case "readMode": set(\.readMode, string.flatMap(ReadMode.init(rawValue:)))
        case "autoReadInterval": set(\.autoReadInterval, number.map { min(max($0.doubleValue, 1), 10) })
        case "autoScrollSecondsPerScreen": set(\.autoScrollSecondsPerScreen, number.map { ReaderAutoScroll.shared.secondsPerScreen(value: $0.doubleValue) })
        case "preloadPages": set(\.preloadPages, number.map { min(max($0.intValue, 1), 10) })
        case "tapToTurnPages": set(\.tapToTurnPages, number?.boolValue)
        case "animatePageTurns": set(\.animatePageTurns, number?.boolValue)
        case "pageGap": set(\.pageGap, number.map { min(max($0.doubleValue, 2), 60) })
        case "screenOn": set(\.screenOn, string.flatMap(ScreenOn.init(rawValue:)))
        case "detectClipboard": set(\.detectClipboard, number?.boolValue)
        case "tabBarPosition": set(\.tabBarPosition, string.flatMap(TabBarPosition.init(rawValue:)))
        case "followHand": set(\.followHand, number?.boolValue)
        case "followSpeed": set(\.followSpeed, string.flatMap(FollowSpeed.init(rawValue:)))
        case "saveSearchHistory": set(\.saveSearchHistory, number?.boolValue)
        case "hideBarsOnScroll": set(\.hideBarsOnScroll, number?.boolValue)
        case "readerControlsDelay": set(\.readerControlsDelay, number.map { min(max($0.doubleValue, 3), 10) })
        case "libraryStyle": set(\.libraryStyle, string.flatMap(LibraryStyle.init(rawValue:)))
        case "nhentaiEnabled": set(\.nhentaiEnabled, number?.boolValue)
        case "ehentaiEnabled": set(\.ehentaiEnabled, number?.boolValue)
        case "homeSiteDefault": set(\.homeSiteDefault, string.flatMap(SiteChoice.init(rawValue:)))
        case "useExHentai": set(\.useExHentai, number?.boolValue)
        case "favoritesSite": set(\.favoritesSite, string.flatMap(SiteChoice.init(rawValue:)))
        case "historySite": set(\.historySite, string.flatMap(SiteChoice.init(rawValue:)))
        case "downloadsSite": set(\.downloadsSite, string.flatMap(SiteChoice.init(rawValue:)))
        case "searchSite": set(\.searchSite, string.flatMap(SiteChoice.init(rawValue:)))
        case "searchBothSites": set(\.searchBothSites, number?.boolValue)
        case "filterRules":
            // Imported or synced rules: loaded lists filter again.
            let before = filterRules
            set(\.filterRules, string)
            if filterRules != before { Task { @MainActor in Filters.shared.changed() } }
        case "llmServices": set(\.llmServices, string)
        case "readerBrightness": set(\.readerBrightness, number.map { min(max($0.doubleValue, 0.2), 1) })
        case "readerGrayscale": set(\.readerGrayscale, number?.boolValue)
        case "readerInvert": set(\.readerInvert, number?.boolValue)
        case "superResolution": set(\.superResolution, string.flatMap(SuperResolutionMode.init(rawValue:)))
        case "deviceTranslation": set(\.deviceTranslation, number?.boolValue)
        case "doublePage": set(\.doublePage, string.flatMap(DoublePage.init(rawValue:)))
        case "doublePageCover": set(\.doublePageCover, number?.boolValue)
        case "doublePageSingleStep": set(\.doublePageSingleStep, number?.boolValue)
        case "swipeFades": set(\.swipeFades, number?.boolValue)
        case "autoCrop": set(\.autoCrop, number?.boolValue)
        case "homeRows": set(\.homeRowsRaw, string)
        case "blockedCommenters": set(\.blockedCommenters, string)
        case "tapZones": set(\.tapZones, string.flatMap(TapZones.init(rawValue:)))
        case "swipeUp": set(\.swipeUp, string.flatMap(ReaderGesture.init(rawValue:)))
        case "swipeDown": set(\.swipeDown, string.flatMap(ReaderGesture.init(rawValue:)))
        case "swipeLeft": set(\.swipeLeft, string.flatMap(ReaderGesture.init(rawValue:)))
        case "swipeRight": set(\.swipeRight, string.flatMap(ReaderGesture.init(rawValue:)))
        case "downloadGalleries": set(\.downloadGalleries, number.map { min(max($0.intValue, 1), 5) })
        case "downloadPages": set(\.downloadPages, number.map { min(max($0.intValue, 1), 6) })
        case "downloadRetries": set(\.downloadRetries, number.map { min(max($0.intValue, 0), 5) })
        case "downloadInterval": set(\.downloadInterval, number.map { min(max($0.doubleValue, 0), 5) })
        case "ehOriginalImages": set(\.ehOriginalImages, number?.boolValue)
        case "backgroundPrivacy": set(\.backgroundPrivacy, string.flatMap(BackgroundPrivacy.init(rawValue:)))
        case "useNhBlacklist":
            let before = useNhBlacklist
            set(\.useNhBlacklist, number?.boolValue)
            if useNhBlacklist != before { Task { @MainActor in Filters.shared.changed() } }
        case "favoritesAllFolders": set(\.favoritesAllFolders, string.map { $0.split(separator: ",").compactMap { Int($0) } })
        case "recentSearches": set(\.recentSearches, (value as? [String]).map { Array($0.prefix(AppSettings.recentSearchLimit)) })
        default: break
        }
    }

    /// Columns of cards on this device in the orientation.
    func columns(isLandscape: Bool) -> Int {
        if UIDevice.current.userInterfaceIdiom == .pad {
            return isLandscape ? padColumnsLandscape : padColumnsPortrait
        }
        return isLandscape ? phoneColumnsLandscape : phoneColumnsPortrait
    }

    // MARK: Search history

    static let recentSearchLimit = 20


    func removeRecentSearch(_ term: String) {
        recentSearches = recentSearches.filter { $0 != term }
    }

    func removeRecentSearches(at offsets: IndexSet) {
        var items = recentSearches
        items.remove(atOffsets: offsets)
        recentSearches = items
    }

    func clearRecentSearches() {
        recentSearches = []
    }
}

