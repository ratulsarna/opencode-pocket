import SwiftUI
import ComposeApp
import UIKit

class AppDelegate: NSObject, UIApplicationDelegate {

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        // Apply stored theme before the UI is presented (best-effort).
        IosThemeApplier.installAutoReapply()
        IosThemeApplier.applyStoredPreference()
#if DEBUG
        let raw = ProcessInfo.processInfo.environment["OC_POCKET_MEM_LOG"]?.trimmingCharacters(in: .whitespacesAndNewlines)
        let enabled = raw == "1" || raw?.lowercased() == "true"
        if enabled {
            MemoryDiagnostics.shared.start()
        }
#endif
        return true
    }
}

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate
    @Environment(\.scenePhase) private var scenePhase

    init() {
        // Apply the stored theme preference as early as possible to minimize launch-time "theme flash".
        // Live updates are handled from SwiftUI once KMP viewmodels are active.
        IosThemeApplier.installAutoReapply()
        IosThemeApplier.applyStoredPreference()

        let raw = ProcessInfo.processInfo.environment["OC_POCKET_MOCK_MODE"]?.trimmingCharacters(in: .whitespacesAndNewlines)
        let enabled = raw == "1" || raw?.lowercased() == "true"
        if enabled {
            AppModule.shared.isMockMode = true
            print("[oc-pocket] MOCK_MODE=1 enabled via env")
        }
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .onOpenURL { url in
                    if url.scheme == "oc-pocket" {
                        if url.host == "share" {
                            ShareExtensionBridgeKt.consumePendingShare()
                        }
                    }
                }
                .onChange(of: scenePhase) { oldPhase, newPhase in
                    // Critical: sharing often happens while the app is already running.
                    // Re-check the App Group manifest whenever we become active so the user
                    // doesn't have to force-kill and relaunch to see the pending share.
                    if newPhase == .active {
                        ShareExtensionBridgeKt.consumePendingShare()
                    }
                }
        }
    }
}
