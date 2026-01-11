import Foundation
import ImageIO
import UIKit

enum ImageDownsampler {
    static func makeThumbnailImage(data: Data, maxPixelSize: Int) -> UIImage? {
        guard maxPixelSize > 0 else { return nil }

        let sourceOptions: CFDictionary = [
            kCGImageSourceShouldCache: false,
        ] as CFDictionary

        guard let source = CGImageSourceCreateWithData(data as CFData, sourceOptions) else {
            return nil
        }

        let thumbnailOptions: CFDictionary = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixelSize,
        ] as CFDictionary

        guard let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, thumbnailOptions) else {
            return nil
        }

        return UIImage(cgImage: cgImage)
    }

    static func makeThumbnailJpegData(
        data: Data,
        maxPixelSize: Int,
        compressionQuality: CGFloat
    ) -> Data? {
        guard let image = makeThumbnailImage(data: data, maxPixelSize: maxPixelSize) else { return nil }
        return autoreleasepool {
            image.jpegData(compressionQuality: compressionQuality)
        }
    }
}

