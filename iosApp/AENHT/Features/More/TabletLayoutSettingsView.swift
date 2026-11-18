import SwiftUI

struct TabletLayoutSettingsView: View {
    @Environment(AppSettings.self) private var settings

    var body: some View {
        @Bindable var settings = settings
        Form {
            Section {
                Toggle("Tablet Layout on Home", isOn: $settings.tabletHomeLayout)
            } footer: {
                Text("Shows the two pinned rows side by side when the home screen has room.")
            }
            Section {
                Toggle("Tablet Layout for Gallery Details", isOn: $settings.tabletDetailLayout)
                if settings.tabletDetailLayout {
                    Picker("Right Pane", selection: $settings.tabletDetailContent) {
                        ForEach(TabletDetailContent.allCases) { content in
                            Text(content.title).tag(content)
                        }
                    }
                    if settings.tabletDetailContent == .pages {
                        Picker("Pane Width", selection: $settings.compactTabletPages) {
                            Text("Equal Panes").tag(false)
                            Text("Three Columns of Pages").tag(true)
                        }
                    }
                }
            } footer: {
                if settings.tabletDetailLayout, settings.tabletDetailContent == .pages {
                    Text("The three-column pane keeps the original thumbnail size.")
                }
            }
        }
        .navigationTitle("Tablet Layout")
        .navigationBarTitleDisplayMode(.inline)
    }
}
