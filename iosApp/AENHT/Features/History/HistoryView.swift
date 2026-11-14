import SwiftData
import SwiftUI

struct HistoryView: View {
    @Query(sort: \HistoryEntry.lastRead, order: .reverse) private var entries: [HistoryEntry]
    @Environment(\.modelContext) private var modelContext
    @Environment(AppSettings.self) private var settings
    @State private var searchText = ""
    @State private var confirmClear = false
    @State private var site: SiteKind? = AppSettings.shared.site(AppSettings.shared.historySite)

    private var siteEntries: [HistoryEntry] {
        guard let site else { return entries }
        return entries.filter { $0.siteKind == site }
    }

    private var showsSites: Bool { site == nil && settings.enabledSites.count > 1 }

    private var filtered: [HistoryEntry] {
        let text = searchText.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else { return siteEntries }
        return siteEntries.filter {
            $0.title.localizedCaseInsensitiveContains(text) || "\($0.gid)" == text
        }
    }

    private struct DaySection: Identifiable {
        let day: Date
        let entries: [HistoryEntry]
        var id: Date { day }
    }

    private var sections: [DaySection] {
        let calendar = Calendar.current
        let grouped = Dictionary(grouping: filtered) { calendar.startOfDay(for: $0.lastRead) }
        return grouped.keys.sorted(by: >).map { DaySection(day: $0, entries: grouped[$0] ?? []) }
    }

    var body: some View {
        Group {
            if settings.libraryStyle == .cards {
                cards
            } else {
                list
            }
        }
        .hidesBarsOnScroll()
        .overlay {
            if entries.isEmpty {
                ContentUnavailableView("No History", systemImage: "clock",
                                       description: Text("Galleries you open appear here."))
            } else if filtered.isEmpty {
                ContentUnavailableView.search(text: searchText)
            }
        }
        .navigationTitle("History")
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $searchText, placement: .navigationBarDrawer(displayMode: .automatic), prompt: "Search History")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                SiteFilterMenu(site: $site)
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button("Clear", systemImage: "trash") { confirmClear = true }
                    .disabled(entries.isEmpty)
            }
        }
        .environment(\.showsSiteBadge, showsSites)
        .onAppear {
            if settings.historySite == .follow { site = settings.site(.follow) }
        }
        .confirmationDialog("Clear all history?", isPresented: $confirmClear, titleVisibility: .visible) {
            Button("Clear History", role: .destructive) {
                try? modelContext.delete(model: HistoryEntry.self)
                try? modelContext.save()
                Task { try? await WebDavService.shared.history.clear() }
            }
        }
    }

    /// Cards like Favorites (in the list style), by day.
    private var cards: some View {
        let byID = Dictionary(filtered.map { ($0.key, $0) }, uniquingKeysWith: { first, _ in first })
        return ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                ForEach(sections) { section in
                    SectionTitle(title: "\(title(for: section.day))")
                    GalleryCollection(items: section.entries.map(\.item))
                }
            }
            .padding(.bottom, 16)
        }
        // History is on the device: a double tap just goes to the top.
        .tabTapScrolling(.history) {}
        .environment(\.galleryCoverBadge) { item in
            AnyView(ReadingProgressBar(entry: byID[item.uid]))
        }
        .environment(\.galleryMenuItems) { item in
            AnyView(Button("Delete", systemImage: "trash", role: .destructive) {
                if let entry = byID[item.uid] { delete(entry) }
            })
        }
    }

    /// Rows with the page read, by day.
    private var list: some View {
        List {
            ForEach(sections) { section in
                Section {
                    ForEach(section.entries) { entry in
                        GalleryRow(item: entry.item, subtitle: subtitle(entry))
                    }
                    .onDelete { offsets in
                        for index in offsets {
                            modelContext.delete(section.entries[index])
                        }
                        try? modelContext.save()
                    }
                } header: {
                    Text(title(for: section.day))
                }
            }
        }
        .listStyle(.plain)
        .tabTapScrolling(.history) {}
    }

    private func delete(_ entry: HistoryEntry) {
        let key = entry.key
        Task { try? await WebDavService.shared.history.delete(storageKey: key) }
        modelContext.delete(entry)
        try? modelContext.save()
    }

    private func subtitle(_ entry: HistoryEntry) -> String {
        let time = entry.lastRead.formatted(date: .omitted, time: .shortened)
        guard entry.numPages > 0 else { return time }
        return String(localized: "Page \(entry.lastPage + 1) of \(entry.numPages) · \(time)")
    }

    private func title(for day: Date) -> String {
        let calendar = Calendar.current
        if calendar.isDateInToday(day) { return String(localized: "Today") }
        if calendar.isDateInYesterday(day) { return String(localized: "Yesterday") }
        return day.formatted(date: .complete, time: .omitted)
    }
}

/// How far a gallery has been read: a thin bar at the bottom of its cover.
private struct ReadingProgressBar: View {
    let entry: HistoryEntry?

    var body: some View {
        if let entry, entry.numPages > 1, entry.lastPage > 0 {
            let fraction = min(Double(entry.lastPage + 1) / Double(entry.numPages), 1)
            Capsule()
                .fill(.black.opacity(0.35))
                .overlay(alignment: .leading) {
                    GeometryReader { proxy in
                        Capsule()
                            .fill(.white)
                            .frame(width: max(proxy.size.width * fraction, 4))
                    }
                }
                .frame(height: 4)
                .padding(.horizontal, 8)
                .padding(.bottom, 6)
                .frame(maxHeight: .infinity, alignment: .bottom)
                .accessibilityHidden(true)
        }
    }
}
