import Foundation

/// Represents a file extracted from NSItemProvider in the Share Extension.
/// This is used to pass file data from extraction to storage.
struct ExtractedFile {
    let id: String
    let filename: String
    let mimeType: String
    let data: Data
    let sizeBytes: Int64
}
