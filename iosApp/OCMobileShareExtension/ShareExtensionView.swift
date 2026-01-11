import SwiftUI

/// SwiftUI view for the Share Extension UI.
/// Shows a preview of shared content with Send/Cancel buttons.
struct ShareExtensionView: View {
    @ObservedObject var viewModel: ShareExtensionViewModel
    let onSend: () -> Void
    let onSaveToOcMobile: () -> Void
    let onCancel: () -> Void

    private var hasContent: Bool {
        !viewModel.files.isEmpty || !viewModel.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private var isProcessing: Bool {
        if case .loading = viewModel.state { return true }
        if case .sending = viewModel.state { return true }
        return false
    }

    private var errorMessage: String? {
        if case .error(let message) = viewModel.state {
            return message
        }
        return nil
    }

    var body: some View {
        NavigationView {
            VStack(spacing: 16) {
                if isProcessing {
                    Spacer()
                    ProgressView(viewModel.state == .sending ? "Sending..." : "Processing...")
                        .padding()
                    Spacer()
                } else if let error = errorMessage,
                          viewModel.files.isEmpty,
                          viewModel.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    // Only show error as main content if there's no other content
                    Spacer()
                    VStack(spacing: 12) {
                        Image(systemName: "exclamationmark.triangle")
                            .font(.system(size: 48))
                            .foregroundColor(.orange)
                        Text(error)
                            .multilineTextAlignment(.center)
                            .foregroundColor(.secondary)
                            .padding(.horizontal)
                    }
                    Spacer()
                } else {
                    // Content preview
                    ScrollView {
                        VStack(alignment: .leading, spacing: 16) {
                            // Error banner (if there's also content)
                            if let error = errorMessage {
                                HStack {
                                    Image(systemName: "exclamationmark.triangle.fill")
                                        .foregroundColor(.orange)
                                    Text(error)
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                }
                                .padding(12)
                                .background(Color.orange.opacity(0.1))
                                .cornerRadius(8)
                            }

                            // Files preview
                            if !viewModel.files.isEmpty {
                                VStack(alignment: .leading, spacing: 8) {
                                    Text("Attachments (\(viewModel.files.count))")
                                        .font(.headline)

                                    LazyVGrid(columns: [
                                        GridItem(.flexible()),
                                        GridItem(.flexible()),
                                        GridItem(.flexible())
                                    ], spacing: 8) {
                                        ForEach(viewModel.files, id: \.id) { file in
                                            FileThumbnailView(file: file)
                                        }
                                    }
                                }
                            }

                            // Caption/message field (WhatsApp-style)
                            VStack(alignment: .leading, spacing: 8) {
                                Text("Message")
                                    .font(.headline)

                                TextEditor(text: $viewModel.text)
                                    .frame(minHeight: 96)
                                    .padding(10)
                                    .background(Color.gray.opacity(0.12))
                                    .cornerRadius(10)
                                    .autocorrectionDisabled(false)
                                    .textInputAutocapitalization(.sentences)
                                    .overlay(
                                        Group {
                                            if viewModel.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                                                Text("Add a message…")
                                                    .foregroundColor(.secondary)
                                                    .padding(.horizontal, 16)
                                                    .padding(.vertical, 18)
                                                    .frame(maxWidth: .infinity, alignment: .leading)
                                                    .allowsHitTesting(false)
                                            }
                                        }
                                    )
                            }

                            // Failure actions (WhatsApp-style: keep user in share UI)
                            if errorMessage != nil {
                                HStack(spacing: 12) {
                                    Button("Save to OpenCode Pocket", action: onSaveToOcMobile)
                                        .buttonStyle(.bordered)

                                    Button("Retry Send", action: onSend)
                                        .buttonStyle(.borderedProminent)
                                }
                            }

                            // Empty state
                            if !hasContent {
                                VStack(spacing: 8) {
                                    Image(systemName: "doc.questionmark")
                                        .font(.largeTitle)
                                        .foregroundColor(.secondary)
                                    Text("Nothing to share")
                                        .foregroundColor(.secondary)
                                }
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 32)
                            }
                        }
                        .padding()
                    }
                }
            }
            .navigationTitle("Share to OpenCode Pocket")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: onCancel)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Send", action: onSend)
                        .disabled(isProcessing || !hasContent)
                        .fontWeight(.semibold)
                }
            }
        }
    }
}

/// Thumbnail view for an extracted file.
struct FileThumbnailView: View {
    let file: ExtractedFile

    private var isImage: Bool {
        file.mimeType.hasPrefix("image/")
    }

    var body: some View {
        VStack(spacing: 4) {
            ZStack {
                RoundedRectangle(cornerRadius: 8)
                    .fill(Color.gray.opacity(0.15))
                    .frame(width: 80, height: 80)

                if isImage, let uiImage = UIImage(data: file.data) {
                    // Show actual image thumbnail
                    Image(uiImage: uiImage)
                        .resizable()
                        .aspectRatio(contentMode: .fill)
                        .frame(width: 80, height: 80)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                } else if isImage {
                    // Fallback image icon
                    Image(systemName: "photo")
                        .font(.title)
                        .foregroundColor(.blue)
                } else {
                    // Show file type icon
                    Image(systemName: iconName(for: file.mimeType))
                        .font(.title)
                        .foregroundColor(.secondary)
                }
            }

            Text(file.filename)
                .font(.caption2)
                .lineLimit(1)
                .truncationMode(.middle)
                .frame(width: 80)

            Text(formatFileSize(file.sizeBytes))
                .font(.caption2)
                .foregroundColor(.secondary)
        }
    }

    private func iconName(for mimeType: String) -> String {
        if mimeType.hasPrefix("image/") {
            return "photo"
        }
        switch mimeType {
        case "application/pdf":
            return "doc.fill"
        case "text/plain", "text/markdown":
            return "doc.text.fill"
        default:
            return "doc.fill"
        }
    }

    private func formatFileSize(_ bytes: Int64) -> String {
        let formatter = ByteCountFormatter()
        formatter.allowedUnits = [.useKB, .useMB]
        formatter.countStyle = .file
        return formatter.string(fromByteCount: bytes)
    }
}
