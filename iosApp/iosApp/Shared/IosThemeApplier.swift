import UIKit

/// Applies the app's theme preference (System / Light / Dark) to UIKit via `overrideUserInterfaceStyle`.
///
/// Note: This is intentionally implemented in Swift (reading `UserDefaults`) so it can run early in app
/// startup before KMP view models are initialized.
@MainActor
enum IosThemeApplier {
    static let themeModeUserDefaultsKey = "theme_mode"

    private static var autoReapplyInstalled: Bool = false
    private static var lastAppliedStyle: UIUserInterfaceStyle?

    static func readStoredStyle() -> UIUserInterfaceStyle {
        let raw = UserDefaults.standard.string(forKey: themeModeUserDefaultsKey)
        return style(fromStoredString: raw)
    }

    static func style(fromStoredString raw: String?) -> UIUserInterfaceStyle {
        switch raw?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() {
        case "LIGHT":
            return .light
        case "DARK":
            return .dark
        default:
            return .unspecified
        }
    }

    /// Installs lightweight observers that re-apply the most recent style when scenes activate.
    /// This helps reduce "theme flash" during startup and when new windows appear.
    static func installAutoReapply() {
        guard !autoReapplyInstalled else { return }
        autoReapplyInstalled = true

        let handler: @MainActor () -> Void = {
            let style = lastAppliedStyle ?? readStoredStyle()
            _ = apply(style: style)
        }

        NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification,
            object: nil,
            queue: .main
        ) { _ in
            Task { @MainActor in handler() }
        }

        NotificationCenter.default.addObserver(
            forName: UIScene.didActivateNotification,
            object: nil,
            queue: .main
        ) { _ in
            Task { @MainActor in handler() }
        }
    }

    /// Reads `theme_mode` from `UserDefaults` and applies it to all current windows.
    @discardableResult
    static func applyStoredPreference() -> Int {
        apply(style: readStoredStyle())
    }

    /// Applies the given style to all current windows.
    /// Returns the number of windows updated.
    @discardableResult
    static func apply(style: UIUserInterfaceStyle) -> Int {
        lastAppliedStyle = style

        let windows = allWindows()
        for window in windows {
            // Setting this repeatedly is fine and keeps the operation idempotent.
            window.overrideUserInterfaceStyle = style
        }
        return windows.count
    }

    static func anyWindowMismatched(expected: UIUserInterfaceStyle) -> Bool {
        if expected == .unspecified { return false }
        return allWindows().contains { $0.traitCollection.userInterfaceStyle != expected }
    }

    private static func allWindows() -> [UIWindow] {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
    }
}

