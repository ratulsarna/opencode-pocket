import Foundation
import ComposeApp

enum ChatAttachmentErrorPresentation {
    static func message(_ error: AttachmentError) -> String {
        switch ComposeApp.onEnum(of: error) {
        case .fileTooLarge(let payload):
            let sizeMb = Double(payload.sizeBytes) / 1_048_576.0
            let maxMb = Double(payload.maxBytes) / 1_048_576.0
            return "\(payload.filename) is \(String(format: "%.1f", sizeMb))MB (max \(String(format: "%.0f", maxMb))MB)."
        case .tooManyFiles(let payload):
            return "Too many files (\(payload.count)/\(payload.max))."
        case .unsupportedType(let payload):
            return "Unsupported file type: \(payload.filename)"
        case .noClipboardImage:
            return "No image found on clipboard."
        }
    }

    static func key(_ error: AttachmentError) -> String {
        switch ComposeApp.onEnum(of: error) {
        case .fileTooLarge(let payload):
            return "fileTooLarge:\(payload.filename):\(payload.sizeBytes):\(payload.maxBytes)"
        case .tooManyFiles(let payload):
            return "tooMany:\(payload.count):\(payload.max)"
        case .unsupportedType(let payload):
            return "unsupported:\(payload.filename):\(payload.mimeType)"
        case .noClipboardImage:
            return "noClipboardImage"
        }
    }
}

