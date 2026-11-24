import SwiftUI

/// Settings › WebDAV Sync.
struct WebDavSettingsView: View {
    @State private var service = WebDavService.shared
    @State private var url = WebDavService.shared.url
    @State private var user = WebDavService.shared.user
    @State private var password = ""
    @State private var checking = false
    @State private var message: String?

    var body: some View {
        @Bindable var service = service
        SettingsForm {
            Section {
                TextField("Address", text: $url, prompt: Text(verbatim: "https://dav.example.com/remote.php/dav/files/me"))
                    .keyboardType(.URL)
                TextField("User Name", text: $user)
                SecureField("Password", text: $password)
                Button {
                    checking = true
                    Task {
                        let error = await service.test(url: url, user: user, password: password)
                        checking = false
                        if let error {
                            message = error
                        } else {
                            service.save(url: url, user: user, password: password)
                            password = ""
                            message = String(localized: "Connected. The app keeps its file in the AENHT folder.")
                            service.syncNow()
                        }
                    }
                } label: {
                    HStack {
                        Text(checking ? "Connecting…" : "Connect")
                        if checking { Spacer(); ProgressView() }
                    }
                }
                .disabled(checking || url.isEmpty || user.isEmpty || (password.isEmpty && !service.isConfigured))
            } header: {
                Text("Server")
            } footer: {
                Text("For example Nextcloud, a NAS, or Jianguoyun (use an app password). The password stays on this device and isn't exported with the settings.")
            }
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()

            if service.isConfigured {
                Section {
                    Toggle("Sync Automatically", isOn: $service.auto)
                    Button {
                        service.syncNow()
                    } label: {
                        HStack {
                            Text(service.syncing ? "Syncing…" : "Sync Now")
                            if service.syncing { Spacer(); ProgressView() }
                        }
                    }
                    .disabled(service.syncing)
                    Button("Disconnect", role: .destructive) {
                        service.disconnect()
                        url = ""; user = ""
                    }
                } footer: {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Syncs the settings, the reading history and the favorites copy with your other devices, Android or iPhone. Automatically: when the app opens and when it goes to the background.")
                        if let error = service.error { Text(error).foregroundStyle(.red) }
                        if let date = service.lastSync {
                            Text("Last synced: \(date.formatted(date: .abbreviated, time: .shortened))")
                        }
                    }
                }
            }
        }
        .navigationTitle("WebDAV Sync")
        .navigationBarTitleDisplayMode(.inline)
        .alert("WebDAV Sync", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(message ?? "")
        }
    }
}
