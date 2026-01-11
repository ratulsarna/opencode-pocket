import Foundation

/// Manages file operations in the App Group container shared between main app and Share Extension.
final class AppGroupFileManager {
    static let shared = AppGroupFileManager()

    // IMPORTANT: Must match App Group ID in:
    // - iosApp/iosApp/iosApp.entitlements
    // - iosApp/OCMobileShareExtension/OCMobileShareExtension.entitlements
    // - composeApp/src/iosMain/.../ShareExtensionBridge.kt
    private let appGroupIdentifier = "group.com.ratulsarna.ocmobile"
    private let sharesDirectoryName = "Shares"

    private init() {}

    /// Returns the App Group container URL, or nil if not configured.
    var containerURL: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroupIdentifier)
    }

    /// Returns the Shares directory URL within the container.
    var sharesDirectoryURL: URL? {
        containerURL?.appendingPathComponent(sharesDirectoryName)
    }

    /// Creates a new per-share folder and returns its URL.
    /// Structure: Shares/<uuid>/
    func createShareFolder() -> URL? {
        guard let sharesDir = sharesDirectoryURL else { return nil }

        let shareFolderURL = sharesDir.appendingPathComponent(UUID().uuidString)

        do {
            try FileManager.default.createDirectory(
                at: shareFolderURL,
                withIntermediateDirectories: true,
                attributes: nil
            )
            return shareFolderURL
        } catch {
            print("[AppGroupFileManager] Failed to create share folder: \(error)")
            return nil
        }
    }

    /// Copies a file to the share folder with a given filename.
    /// Returns the relative path from the container root.
    func copyFileToShareFolder(
        from sourceURL: URL,
        shareFolder: URL,
        filename: String
    ) -> String? {
        let destinationURL = shareFolder.appendingPathComponent(filename)

        do {
            // Remove existing file if present
            if FileManager.default.fileExists(atPath: destinationURL.path) {
                try FileManager.default.removeItem(at: destinationURL)
            }
            try FileManager.default.copyItem(at: sourceURL, to: destinationURL)

            // Return relative path from container root
            guard let containerPath = containerURL?.path else { return nil }
            let fullPath = destinationURL.path
            if fullPath.hasPrefix(containerPath) {
                return String(fullPath.dropFirst(containerPath.count + 1)) // +1 for "/"
            }
            return nil
        } catch {
            print("[AppGroupFileManager] Failed to copy file: \(error)")
            return nil
        }
    }

    /// Gets file size in bytes without loading the file into memory.
    func fileSize(at url: URL) -> Int64? {
        do {
            let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
            return attributes[.size] as? Int64
        } catch {
            return nil
        }
    }

    /// Writes the manifest atomically (temp file + rename).
    func writeManifest(_ manifest: SharedManifest) throws {
        guard let containerURL = containerURL else {
            throw AppGroupError.containerNotFound
        }

        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        let data = try encoder.encode(manifest)

        let tempURL = containerURL.appendingPathComponent(SharedManifest.tempFilename)
        let finalURL = containerURL.appendingPathComponent(SharedManifest.filename)

        // Write to temp file first
        try data.write(to: tempURL, options: .atomic)

        // Atomic rename/replace
        if FileManager.default.fileExists(atPath: finalURL.path) {
            try FileManager.default.removeItem(at: finalURL)
        }
        try FileManager.default.moveItem(at: tempURL, to: finalURL)
    }

    /// Reads the manifest if it exists.
    func readManifest() -> SharedManifest? {
        guard let containerURL = containerURL else { return nil }
        let manifestURL = containerURL.appendingPathComponent(SharedManifest.filename)

        guard FileManager.default.fileExists(atPath: manifestURL.path) else { return nil }

        do {
            let data = try Data(contentsOf: manifestURL)
            return try JSONDecoder().decode(SharedManifest.self, from: data)
        } catch {
            print("[AppGroupFileManager] Failed to read manifest: \(error)")
            return nil
        }
    }

    /// Checks if a manifest exists.
    func hasManifest() -> Bool {
        guard let containerURL = containerURL else { return false }
        let manifestURL = containerURL.appendingPathComponent(SharedManifest.filename)
        return FileManager.default.fileExists(atPath: manifestURL.path)
    }

    /// Deletes the manifest and optionally the associated share folder.
    func deleteManifest(andFolder folderRelativePath: String? = nil) {
        guard let containerURL = containerURL else { return }

        // Delete manifest
        let manifestURL = containerURL.appendingPathComponent(SharedManifest.filename)
        try? FileManager.default.removeItem(at: manifestURL)

        // Delete temp manifest if exists
        let tempURL = containerURL.appendingPathComponent(SharedManifest.tempFilename)
        try? FileManager.default.removeItem(at: tempURL)

        // Delete share folder if specified
        if let folderPath = folderRelativePath {
            // Extract just the folder part (e.g., "Shares/uuid" from "Shares/uuid/file.png")
            let components = folderPath.split(separator: "/")
            if components.count >= 2 {
                let folderOnly = components.prefix(2).joined(separator: "/")
                let folderURL = containerURL.appendingPathComponent(folderOnly)
                try? FileManager.default.removeItem(at: folderURL)
            }
        }
    }

    /// Deletes all content in the Shares directory.
    func deleteAllShares() {
        guard let sharesDir = sharesDirectoryURL else { return }
        try? FileManager.default.removeItem(at: sharesDir)
    }
}

enum AppGroupError: Error {
    case containerNotFound
    case manifestWriteFailed
    case shareFolderCreationFailed
    case fileWriteFailed
}

// MARK: - Static Convenience Methods (for Share Extension)

extension AppGroupFileManager {
    /// Writes files to App Group container and returns file references.
    /// Returns empty array immediately if no files to write (avoids orphaned folders).
    static func writeFiles(_ files: [ExtractedFile]) throws -> [SharedFileRef] {
        // Skip folder creation for text-only shares to avoid orphaned empty folders
        guard !files.isEmpty else { return [] }

        let manager = AppGroupFileManager.shared

        guard let shareFolder = manager.createShareFolder() else {
            throw AppGroupError.shareFolderCreationFailed
        }

        var fileRefs: [SharedFileRef] = []

        for file in files {
            let destinationURL = shareFolder.appendingPathComponent(file.filename)

            do {
                try file.data.write(to: destinationURL, options: .atomic)

                guard let containerPath = manager.containerURL?.path else {
                    throw AppGroupError.containerNotFound
                }
                let fullPath = destinationURL.path
                let relativePath: String
                if fullPath.hasPrefix(containerPath) {
                    relativePath = String(fullPath.dropFirst(containerPath.count + 1))
                } else {
                    relativePath = file.filename
                }

                fileRefs.append(SharedFileRef(
                    id: file.id,
                    filename: file.filename,
                    mimeType: file.mimeType,
                    relativePath: relativePath,
                    sizeBytes: file.sizeBytes
                ))
            } catch {
                throw AppGroupError.fileWriteFailed
            }
        }

        return fileRefs
    }

    /// Writes manifest atomically (static convenience).
    static func writeManifest(_ manifest: SharedManifest) throws {
        try AppGroupFileManager.shared.writeManifest(manifest)
    }
}
