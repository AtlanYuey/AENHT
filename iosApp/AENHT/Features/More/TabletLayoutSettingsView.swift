import SwiftUI

/// Me › Tablet Layout (an iPad held sideways): which screens use the room
/// for two halves side by side.
struct TabletLayoutSettingsView: View {
    @Environment(AppSettings.self) private var settings

    var body: some View {
        @Bindable var settings = settings
        SettingsForm {
            Section {
                Toggle("Tablet Layout on Home", isOn: $settings.tabletHome)
            } footer: {
                Text("The two pinned rows side by side.")
            }
            Section {
                Toggle("Tablet Layout for Gallery Pages", isOn: $settings.tabletDetail)
                if settings.tabletDetail {
                    Picker("Right Half", selection: $settings.tabletDetailContent) {
                        ForEach(TabletDetailContent.allCases) { Text($0.title).tag($0) }
                    }
                    // The other versions, similar galleries and comments need the room.
                    if settings.tabletDetailContent == .pages {
                        Picker("Pages Width", selection: $settings.tabletNarrowPages) {
                            Text("Half the Page").tag(false)
                            Text("Three Columns").tag(true)
                        }
                    }
                }
            } footer: {
                if settings.tabletDetail {
                    Text(settings.tabletDetailContent == .pages
                         ? "The details on the left, every page on the right. Three Columns keeps the thumbnails' size and leaves more room to the details."
                         : "The details and the pages on the left; the other versions, similar galleries and comments on the right.")
                } else {
                    Text("Gallery pages show as on a phone: one column.")
                }
            }
            Section {
                Toggle("Tablet Layout for Me", isOn: $settings.tabletMe)
            } footer: {
                Text("The list on the left, the chosen page on the right.")
            }
        }
        .navigationTitle("Tablet Layout")
        .navigationBarTitleDisplayMode(.inline)
    }
}
