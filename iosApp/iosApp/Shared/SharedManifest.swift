import Foundation

/// Manifest for shared content from the Share Extension.
/// Stored in the App Group container and read by the main app.
struct SharedManifest: Codable {
    let files: [SharedFileRef]
    let text: String?
    let timestampEpochMs: Int64

    static let filename = "SharedManifest.json"
    static let tempFilename = "SharedManifest.json.tmp"
}

/// Reference to a file stored in the App Group container.
/// Does NOT contain the file bytes - just metadata and path.
struct SharedFileRef: Codable {
    let id: String
    let filename: String
    let mimeType: String
    let relativePath: String
    let sizeBytes: Int64
}
