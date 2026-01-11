import Foundation
import ComposeApp

extension Data {
    func toKotlinByteArray() -> KotlinByteArray {
        KotlinDataInteropKt.dataToKotlinByteArray(data: self)
    }
}

extension KotlinByteArray {
    func toData() -> Data {
        KotlinDataInteropKt.kotlinByteArrayToData(bytes: self)
    }
}
