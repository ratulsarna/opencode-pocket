package com.ratulsarna.ocmobile.util

/**
 * Utility for deriving MIME types from file extensions.
 */
object MimeTypes {

    /**
     * Get MIME type from a file path or filename.
     * Extracts the extension and maps it to a known MIME type.
     *
     * @param path File path or filename
     * @return MIME type string, defaults to "application/octet-stream" for unknown extensions
     */
    fun fromPath(path: String): String {
        val extension = path.substringAfterLast('.', "").lowercase()
        return fromExtension(extension)
    }

    /**
     * Get MIME type from a file extension (without the dot).
     *
     * @param extension File extension (e.g., "md", "png", "kt")
     * @return MIME type string, defaults to "application/octet-stream" for unknown extensions
     */
    fun fromExtension(extension: String): String = when (extension.lowercase()) {
        // Documents
        "md", "markdown" -> "text/markdown"
        "txt" -> "text/plain"
        "pdf" -> "application/pdf"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "yaml", "yml" -> "text/yaml"

        // Images
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"

        // Web
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "js" -> "text/javascript"
        "ts" -> "text/typescript"

        // Source code
        "kt", "kts" -> "text/x-kotlin"
        "java" -> "text/x-java"
        "swift" -> "text/x-swift"
        "py" -> "text/x-python"
        "rb" -> "text/x-ruby"
        "sh" -> "text/x-shellscript"
        "c" -> "text/x-c"
        "cpp", "cc", "cxx" -> "text/x-c++src"
        "h", "hpp" -> "text/x-c++hdr"
        "go" -> "text/x-go"
        "rs" -> "text/x-rust"

        // Unknown
        else -> "application/octet-stream"
    }

    /** MIME type for directories */
    const val DIRECTORY = "inode/directory"
}
