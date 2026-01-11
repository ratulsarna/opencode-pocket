import UIKit
import SwiftUI
import UniformTypeIdentifiers
import Combine
import ComposeApp

/// Kotlin (ComposeApp framework) expects this symbol to exist when linking because the shared
/// `PushNotificationManager` calls it after permission is granted.
///
/// The share extension never registers for APNs, so this is intentionally a no-op implementation
/// to satisfy the linker for the extension target.
@_cdecl("triggerApnsRegistrationFromSwift")
public func triggerApnsRegistrationFromSwift() {
    // Not supported/used in extensions.
}

class ShareViewController: UIViewController {

    private var hostingController: UIHostingController<ShareExtensionView>?
    private var viewModel = ShareExtensionViewModel()

    override func viewDidLoad() {
        super.viewDidLoad()

        let shareView = ShareExtensionView(
            viewModel: viewModel,
            onSend: { [weak self] in
                self?.processAndSend()
            },
            onSaveToOcMobile: { [weak self] in
                self?.queueToOcMobileAndDismiss()
            },
            onCancel: { [weak self] in
                self?.cancelRequest()
            }
        )

        let hostingController = UIHostingController(rootView: shareView)
        self.hostingController = hostingController

        addChild(hostingController)
        view.addSubview(hostingController.view)
        hostingController.view.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            hostingController.view.topAnchor.constraint(equalTo: view.topAnchor),
            hostingController.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            hostingController.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            hostingController.view.trailingAnchor.constraint(equalTo: view.trailingAnchor)
        ])
        hostingController.didMove(toParent: self)

        // Extract content on main thread
        extractContent()
    }

    // MARK: - Content Extraction

    private func extractContent() {
        guard let extensionItem = extensionContext?.inputItems.first as? NSExtensionItem,
              let attachments = extensionItem.attachments else {
            viewModel.state = .error("No content to share")
            return
        }

        viewModel.state = .loading

        let group = DispatchGroup()
        let extractionQueue = DispatchQueue(label: "com.ratulsarna.ocmobile.extraction")
        var extractedFiles: [ExtractedFile] = []
        var extractedText: String = ""

        for provider in attachments {
            if provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) {
                group.enter()
                provider.loadFileRepresentation(forTypeIdentifier: UTType.image.identifier) { [weak self] url, error in
                    defer { group.leave() }
                    guard let self = self, let url = url, error == nil else { return }
                    if let file = self.createExtractedFile(from: url) {
                        extractionQueue.sync { extractedFiles.append(file) }
                    }
                }
            } else if provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
                group.enter()
                provider.loadItem(forTypeIdentifier: UTType.plainText.identifier, options: nil) { item, error in
                    defer { group.leave() }
                    if let text = item as? String {
                        extractionQueue.sync { extractedText = text }
                    }
                }
            } else if provider.hasItemConformingToTypeIdentifier(UTType.url.identifier) {
                group.enter()
                provider.loadItem(forTypeIdentifier: UTType.url.identifier, options: nil) { item, error in
                    defer { group.leave() }
                    if let url = item as? URL {
                        extractionQueue.sync {
                            extractedText = extractedText.isEmpty ? url.absoluteString : (extractedText + "\n" + url.absoluteString)
                        }
                    }
                }
            }
        }

        group.notify(queue: .main) { [weak self] in
            guard let self = self else { return }
            self.viewModel.files = extractedFiles
            self.viewModel.text = extractedText
            self.viewModel.state = .ready
        }
    }

    private func createExtractedFile(from url: URL) -> ExtractedFile? {
        let filename = url.lastPathComponent
        let fileManager = FileManager.default

        guard let attributes = try? fileManager.attributesOfItem(atPath: url.path),
              let fileSize = attributes[.size] as? Int64,
              fileSize <= AttachmentValidator.maxFileSizeBytes,
              let data = fileManager.contents(atPath: url.path) else {
            return nil
        }

        let ext = (filename as NSString).pathExtension.lowercased()
        let mimeType: String
        switch ext {
        case "png": mimeType = "image/png"
        case "jpg", "jpeg": mimeType = "image/jpeg"
        case "gif": mimeType = "image/gif"
        case "heic": mimeType = "image/heic"
        default: mimeType = "application/octet-stream"
        }

        return ExtractedFile(
            id: UUID().uuidString,
            filename: filename,
            mimeType: mimeType,
            data: data,
            sizeBytes: fileSize
        )
    }

    // MARK: - Process and Send via Kotlin

    private func processAndSend() {
        viewModel.state = .sending

        guard let context = extensionContext else {
            viewModel.state = .error("No extension context")
            return
        }

        let files = viewModel.files
        let textToSend = viewModel.text.trimmingCharacters(in: .whitespacesAndNewlines)

        // Convert files to data URLs on background queue, then call Kotlin
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            var dataUrls: [String] = []
            var filenames: [String] = []
            var mimeTypes: [String] = []

            for file in files {
                let base64 = file.data.base64EncodedString()
                dataUrls.append("data:\(file.mimeType);base64,\(base64)")
                filenames.append(file.filename)
                mimeTypes.append(file.mimeType)
            }

            // Call Kotlin via ComposeApp framework
            let result = ShareExtensionSenderKt.sendFromShareExtension(
                text: textToSend.isEmpty ? nil : textToSend,
                fileDataUrls: dataUrls,
                filenames: filenames,
                mimeTypes: mimeTypes
            )

            DispatchQueue.main.async {
                if result.isSuccess {
                    context.completeRequest(returningItems: [], completionHandler: nil)
                } else {
                    self?.viewModel.state = .error("Failed: \(result.errorMessage ?? "Unknown error")")
                }
            }
        }
    }

    private func queueToOcMobileAndDismiss() {
        viewModel.state = .sending

        guard let context = extensionContext else {
            viewModel.state = .error("No extension context")
            return
        }

        let filesToWrite = viewModel.files
        let textToWrite = viewModel.text.trimmingCharacters(in: .whitespacesAndNewlines)

        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            do {
                let fileRefs = try AppGroupFileManager.writeFiles(filesToWrite)
                let manifest = SharedManifest(
                    files: fileRefs,
                    text: textToWrite.isEmpty ? nil : textToWrite,
                    timestampEpochMs: Int64(Date().timeIntervalSince1970 * 1000)
                )
                try AppGroupFileManager.writeManifest(manifest)
                DispatchQueue.main.async {
                    context.completeRequest(returningItems: [], completionHandler: nil)
                }
            } catch {
                DispatchQueue.main.async {
                    self?.viewModel.state = .error("Failed to save to OpenCode Pocket: \(error.localizedDescription)")
                }
            }
        }
    }

    private func cancelRequest() {
        extensionContext?.cancelRequest(withError: NSError(domain: "OCMobileShareExtension", code: 0))
    }
}

// MARK: - View Model

class ShareExtensionViewModel: ObservableObject {
    enum State: Equatable {
        case loading
        case ready
        case sending
        case error(String)
    }

    @Published var state: State = .loading
    @Published var files: [ExtractedFile] = []
    // Editable caption/message (pre-filled from shared text/URLs when available)
    @Published var text: String = ""
}
