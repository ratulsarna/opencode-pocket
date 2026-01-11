import Foundation
import XCTest

extension XCTestCase {
    @MainActor
    func waitUntil(
        _ condition: @escaping () -> Bool,
        timeoutSeconds: Double = 3.0,
        pollIntervalNanoseconds: UInt64 = 20_000_000
    ) async {
        let deadline = Date().addingTimeInterval(timeoutSeconds)
        while !condition() {
            if Date() >= deadline {
                XCTFail("Timed out waiting for condition")
                return
            }
            try? await Task.sleep(nanoseconds: pollIntervalNanoseconds)
        }
    }
}

