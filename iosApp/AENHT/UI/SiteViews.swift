import SwiftUI

/// The site filter of a screen that lists both sites (History, Downloads):
/// All / nhentai / E-Hentai. Hidden when only one site is in use.
struct SiteFilterMenu: View {
    @Binding var site: SiteKind?
    var allowAll = true

    @Environment(AppSettings.self) private var settings

    var body: some View {
        if settings.enabledSites.count > 1 {
            Menu {
                if allowAll {
                    Button { site = nil } label: {
                        if site == nil { Label("All", systemImage: "checkmark") } else { Text("All") }
                    }
                }
                ForEach(settings.enabledSites) { s in
                    Button { site = s } label: {
                        if site == s { Label(s.displayName, systemImage: "checkmark") } else { Text(s.displayName) }
                    }
                }
            } label: {
                HStack(spacing: 4) {
                    Text(site?.badge ?? String(localized: "All")).font(.subheadline.weight(.semibold))
                    Image(systemName: "chevron.down").font(.caption2.weight(.bold))
                }
            }
            .accessibilityLabel(Text("Site"))
        }
    }
}
