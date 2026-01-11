import Foundation
import UniformTypeIdentifiers

/// Mirrors AttachmentLimits from KMP code.
/// Used to validate attachments before copying to the App Group container.
enum AttachmentValidator {
    static let maxFileSizeBytes: Int64 = 10 * 1024 * 1024  // 10MB
    static let maxFilesPerMessage = 5

    static let supportedImageTypes: Set<String> = [
        "image/png", "image/jpeg", "image/jpg", "image/gif", "image/webp", "image/heic"
    ]

    static let supportedDocumentTypes: Set<String> = [
        "application/pdf", "text/plain", "text/markdown"
    ]

    static var allSupportedTypes: Set<String> {
        supportedImageTypes.union(supportedDocumentTypes)
    }

    /// Validation result for an attachment.
    enum ValidationResult {
        case valid
        case fileTooLarge(filename: String, sizeBytes: Int64, maxBytes: Int64)
        case tooManyFiles(count: Int, max: Int)
        case unsupportedType(filename: String, mimeType: String)
    }

    /// Validate file size.
    static func validateSize(filename: String, sizeBytes: Int64) -> ValidationResult {
        if sizeBytes > maxFileSizeBytes {
            return .fileTooLarge(
                filename: filename,
                sizeBytes: sizeBytes,
                maxBytes: maxFileSizeBytes
            )
        }
        return .valid
    }

    /// Validate file count.
    static func validateCount(currentCount: Int) -> ValidationResult {
        if currentCount >= maxFilesPerMessage {
            return .tooManyFiles(count: currentCount, max: maxFilesPerMessage)
        }
        return .valid
    }

    /// Guess MIME type from filename extension.
    /// Best-effort; KMP can fall back if needed.
    static func mimeType(for filename: String) -> String {
        let ext = (filename as NSString).pathExtension.lowercased()
        if let type = UTType(filenameExtension: ext), let mime = type.preferredMIMEType {
            return mime
        }
        switch ext {
        case "png": return "image/png"
        case "jpg", "jpeg": return "image/jpeg"
        case "gif": return "image/gif"
        case "webp": return "image/webp"
        case "heic": return "image/heic"
        case "pdf": return "application/pdf"
        case "txt": return "text/plain"
        case "md", "markdown": return "text/markdown"
        default: return "application/octet-stream"
        }
    }

    /// Format file size for display.
    static func formatFileSize(_ bytes: Int64) -> String {
        let mb = Double(bytes) / (1024 * 1024)
        if mb >= 1 {
            return String(format: "%.1f MB", mb)
        } else {
            let kb = Double(bytes) / 1024
            return String(format: "%.0f KB", kb)
        }
    }

    /// Format error message for display.
    static func errorMessage(for result: ValidationResult) -> String? {
        switch result {
        case .valid:
            return nil
        case .fileTooLarge(let filename, let sizeBytes, let maxBytes):
            return "\(filename) is too large (\(formatFileSize(sizeBytes))). Maximum size is \(formatFileSize(maxBytes))."
        case .tooManyFiles(_, let max):
            return "Maximum \(max) files allowed per message."
        case .unsupportedType(let filename, _):
            return "\(filename) is not a supported file type."
        }
    }

    /// Validate a list of files. Returns error message if invalid, nil if valid.
    static func validate(files: [ExtractedFile]) -> String? {
        if files.count > maxFilesPerMessage {
            return "Maximum \(maxFilesPerMessage) files allowed per message."
        }
        for file in files {
            if file.sizeBytes > maxFileSizeBytes {
                return "\(file.filename) is too large (\(formatFileSize(file.sizeBytes))). Maximum size is \(formatFileSize(maxFileSizeBytes))."
            }
        }
        return nil
    }
}
