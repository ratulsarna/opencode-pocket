import Foundation
import ComposeApp
import UIKit

enum ChatAttachmentBuilderError: Error {
    case fileTooLarge(sizeBytes: Int64, maxBytes: Int64)
}

enum ChatAttachmentBuilder {
    static func makeAttachment(
        filename: String,
        mimeType: String,
        data: Data
    ) -> Attachment {
        let bytes = data.toKotlinByteArray()
        let isImage = mimeType.starts(with: "image/")
        let thumbnailBytes: KotlinByteArray?
        if isImage {
            let thumbData = ImageDownsampler.makeThumbnailJpegData(
                data: data,
                maxPixelSize: 256,
                compressionQuality: 0.7
            )
            thumbnailBytes = thumbData?.toKotlinByteArray()
        } else {
            thumbnailBytes = nil
        }
        return Attachment(
            id: UUID().uuidString,
            filename: filename,
            mimeType: mimeType,
            bytes: bytes,
            thumbnailBytes: thumbnailBytes
        )
    }

    static func makeAttachment(
        filename: String,
        data: Data
    ) -> Attachment {
        makeAttachment(
            filename: filename,
            mimeType: AttachmentValidator.mimeType(for: filename),
            data: data
        )
    }

    static func loadFileData(from url: URL) async throws -> Data {
        try await Task.detached(priority: .userInitiated) {
            let needsStop = url.startAccessingSecurityScopedResource()
            defer {
                if needsStop {
                    url.stopAccessingSecurityScopedResource()
                }
            }

            let maxBytes = AttachmentValidator.maxFileSizeBytes

            if let values = try? url.resourceValues(forKeys: [.fileSizeKey, .totalFileSizeKey]),
               let size = values.fileSize ?? values.totalFileSize,
               Int64(size) > maxBytes
            {
                throw ChatAttachmentBuilderError.fileTooLarge(sizeBytes: Int64(size), maxBytes: maxBytes)
            }

            // Read incrementally to cap memory usage even when URL metadata is missing.
            if url.isFileURL {
                let handle = try FileHandle(forReadingFrom: url)
                defer { try? handle.close() }

                var data = Data()
                data.reserveCapacity(Int(min(maxBytes, 512 * 1024)))

                while true {
                    let chunk = try handle.read(upToCount: 64 * 1024) ?? Data()
                    if chunk.isEmpty { break }

                    let newCount = Int64(data.count &+ chunk.count)
                    if newCount > maxBytes {
                        throw ChatAttachmentBuilderError.fileTooLarge(sizeBytes: newCount, maxBytes: maxBytes)
                    }
                    data.append(chunk)
                }

                return data
            }

            // Fallback for non-file URLs.
            let data = try Data(contentsOf: url)
            let sizeBytes = Int64(data.count)
            if sizeBytes > maxBytes {
                throw ChatAttachmentBuilderError.fileTooLarge(sizeBytes: sizeBytes, maxBytes: maxBytes)
            }
            return data
        }.value
    }
}
