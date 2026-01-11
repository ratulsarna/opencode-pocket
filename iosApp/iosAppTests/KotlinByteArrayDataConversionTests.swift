import Foundation
import XCTest
import ComposeApp
 import OpenCodePocket

final class KotlinByteArrayDataConversionTests: XCTestCase {
    func test_roundTripPreservesBytes() {
        let bytes: [UInt8] = [0, 1, 2, 3, 254, 255]
        let data = Data(bytes)

        let kotlin = data.toKotlinByteArray()
        XCTAssertEqual(Int(kotlin.size), bytes.count)

        let roundTrip = kotlin.toData()
        XCTAssertEqual(roundTrip, data)
    }

    func test_emptyDataRoundTrip() {
        let data = Data()
        let kotlin = data.toKotlinByteArray()
        XCTAssertEqual(Int(kotlin.size), 0)
        XCTAssertEqual(kotlin.toData(), data)
    }
}
