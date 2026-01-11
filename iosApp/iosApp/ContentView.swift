import UIKit
import SwiftUI
import ComposeApp

#if DEBUG
private enum IosRootMode: String {
    case skieSmoke = "skie_smoke"
    case swiftuiEventBridge = "swiftui_event_bridge"
    case swiftuiApp = "swiftui_app"

    fileprivate static let rootKey = "OC_POCKET_IOS_ROOT"

    static func resolve() -> (mode: IosRootMode, raw: String?, source: String) {
        if let raw = ProcessInfo.processInfo.environment[rootKey]?.trimmingCharacters(in: .whitespacesAndNewlines),
           let mode = IosRootMode(rawValue: raw.lowercased()) {
            return (mode, raw, "env")
        }

        let args = ProcessInfo.processInfo.arguments
        for arg in args {
            if let raw = arg.split(separator: "=", maxSplits: 1).last, arg.hasPrefix("--oc-pocket-ios-root="),
               let mode = IosRootMode(rawValue: raw.lowercased()) {
                return (mode, String(raw), "arg")
            }
            if let raw = arg.split(separator: "=", maxSplits: 1).last, arg.hasPrefix("\(rootKey)="),
               let mode = IosRootMode(rawValue: raw.lowercased()) {
                return (mode, String(raw), "arg")
            }
        }

        if let raw = UserDefaults.standard.string(forKey: rootKey)?.trimmingCharacters(in: .whitespacesAndNewlines),
           let mode = IosRootMode(rawValue: raw.lowercased()) {
            return (mode, raw, "defaults")
        }

        return (.swiftuiApp, nil, "default")
    }
}

private let _rootModeLogOnce: Void = {
    let env = ProcessInfo.processInfo.environment[IosRootMode.rootKey] ?? "nil"
    let args = ProcessInfo.processInfo.arguments.joined(separator: " ")
    print("[oc-pocket] iOS root selection: \(IosRootMode.rootKey)=\(env), args=\(args)")
}()
#endif

struct ContentView: View {
    init() {
#if DEBUG
        _ = _rootModeLogOnce
#endif
    }

    var body: some View {
#if DEBUG
        let resolution = IosRootMode.resolve()
        let isRunningTests = ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil
        let isUiTests = ProcessInfo.processInfo.environment["OC_POCKET_UI_TESTS"] == "1"
        Group {
            if isRunningTests && !isUiTests {
                Color.clear
            } else if resolution.mode == .skieSmoke {
                SkieSmokeTestRootView()
            } else if resolution.mode == .swiftuiEventBridge {
                NavigationStack {
                    SwiftUIEventBridgeHarnessView()
                }
            } else {
                SwiftUIAppResetContainerView()
            }
        }
#else
        SwiftUIAppResetContainerView()
#endif
    }
}

@MainActor
private struct SwiftUIAppResetContainerView: View {
    @State private var resetToken = UUID()

    var body: some View {
        SwiftUIAppRootView(onRequestAppReset: { resetToken = UUID() })
            .id(resetToken)
    }
}
