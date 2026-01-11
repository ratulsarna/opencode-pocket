import Foundation
import ComposeApp

enum KmpDateFormat {
    static func mediumDateTime(_ instant: KotlinInstant) -> String {
        let epochMs = instant.toEpochMilliseconds()
        let date = Date(timeIntervalSince1970: TimeInterval(epochMs) / 1000.0)
        return mediumDateTimeFormatter.string(from: date)
    }

    private static let mediumDateTimeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter
    }()
}

