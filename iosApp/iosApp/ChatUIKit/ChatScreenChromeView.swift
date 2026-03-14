import SwiftUI
import UIKit
import ComposeApp

@MainActor
struct ChatToolbarGlassView: View {
    let state: ChatUiState
    let isRefreshing: Bool
    let onRetry: () -> Void
    let onOpenSessions: () -> Void
    let onOpenSettings: () -> Void
    let onDismissError: () -> Void
    let onRevert: () -> Void

    private var showTypingIndicator: Bool {
        TypingIndicatorKt.shouldShowTypingIndicator(state: state)
    }

    private var isReconnecting: Bool {
        state.connectionState.name.uppercased() == "RECONNECTING"
    }

    private var showProcessingBar: Bool {
        state.sessionStatus.name.uppercased() == "PROCESSING" && !showTypingIndicator
    }

    private var subtitle: String {
        guard let sessionId = state.currentSessionId, !sessionId.isEmpty else {
            return "Pocket chat"
        }
        return "Session \(sessionId.prefix(8))"
    }

    private var shouldShowRevert: Bool {
        guard state.lastGoodMessageId != nil, let error = state.error else { return false }
        return error is ChatError.SessionCorrupted || error is ChatError.SendFailed
    }

    var body: some View {
        chatGlassGrouping(spacing: 16) {
            VStack(spacing: 10) {
                VStack(spacing: 0) {
                    HStack(alignment: .center, spacing: 12) {
                        VStack(alignment: .leading, spacing: 3) {
                            Text("OpenCode")
                                .font(.system(.title3, design: .rounded).weight(.semibold))
                                .foregroundStyle(.primary)
                                .lineLimit(1)

                            Text(subtitle)
                                .font(.system(.caption, design: .monospaced))
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }

                        Spacer(minLength: 0)

                        HStack(spacing: 8) {
                            ChatToolbarIconButton(action: onRetry) {
                                if isRefreshing {
                                    ProgressView()
                                        .controlSize(.small)
                                } else {
                                    Image(systemName: "arrow.clockwise")
                                }
                            }
                            .disabled(isRefreshing)

                            ChatToolbarIconButton(action: onOpenSessions) {
                                Image(systemName: "rectangle.stack")
                            }

                            ChatToolbarIconButton(action: onOpenSettings) {
                                Image(systemName: "gearshape")
                            }
                        }
                    }
                    .padding(.horizontal, 14)
                    .padding(.top, 9)
                    .padding(.bottom, showProcessingBar ? 7 : 9)

                    if showProcessingBar {
                        ChatToolbarProcessingBar()
                            .padding(.horizontal, 14)
                            .padding(.bottom, 9)
                    }
                }

                if isReconnecting {
                    ChatStatusBanner(title: "Reconnecting", message: "Trying to restore the stream.")
                }

                if let error = state.error {
                    ChatErrorBanner(
                        message: error.message ?? "An error occurred.",
                        showRevert: shouldShowRevert,
                        onDismiss: onDismissError,
                        onRetry: onRetry,
                        onRevert: onRevert
                    )
                }
            }
        }
    }
}

@MainActor
struct ChatComposerCardView: View {
    let state: ChatUiState
    let onPickPhotos: () -> Void
    let onPickFiles: () -> Void
    let onAddFromClipboard: () -> Void
    let onRemoveAttachment: (Attachment) -> Void
    let onSelectMentionSuggestion: (VaultEntry) -> Void
    let onSelectSlashCommandSuggestion: (CommandInfo) -> Void
    let onTextAndCursorChange: (String, Int) -> Void
    let onSend: () -> Void
    let onAbort: () -> Void
    let onSelectThinkingVariant: (String?) -> Void
    let onPasteImageData: ([Data]) -> Void

    @State private var inputHeight: CGFloat = 24
    @State private var isInputFocused = false

    private var maxFilesPerMessage: Int {
        Int(AttachmentLimits.shared.MAX_FILES_PER_MESSAGE)
    }

    private var remainingAttachmentSlots: Int {
        max(0, maxFilesPerMessage - state.pendingAttachments.count)
    }

    private var canAddAttachments: Bool {
        remainingAttachmentSlots > 0
    }

    private var showStopButton: Bool {
        state.isSending ||
        state.isAborting ||
        state.sessionStatus.name.uppercased() == "PROCESSING" ||
        state.streamingMessageId != nil
    }

    private var canSend: Bool {
        let hasText = !state.inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        return hasText || !state.pendingAttachments.isEmpty
    }

    private var thinkingTitle: String {
        guard let variant = state.thinkingVariant, !variant.isEmpty else {
            return "Auto"
        }
        return variant.capitalized
    }

    var body: some View {
        chatGlassGrouping(spacing: 18) {
            VStack(spacing: 8) {
            if case let .active(payload) = ComposeApp.onEnum(of: state.slashCommandState) {
                ChatSuggestionPanel(
                    title: payload.isLoading && payload.suggestions.isEmpty ? "Loading commands..." : nil,
                    emptyTitle: payload.error ?? "No commands",
                    rows: payload.suggestions.map {
                        ChatSuggestionRow(
                            id: $0.name,
                            icon: "command",
                            title: "/\($0.name)",
                            subtitle: ($0.description_ ?? "").isEmpty ? nil : $0.description_
                        )
                    },
                    onSelect: { rowId in
                        guard let selected = payload.suggestions.first(where: { $0.name == rowId }) else { return }
                        onSelectSlashCommandSuggestion(selected)
                        isInputFocused = true
                    }
                )
            }

            if case let .active(payload) = ComposeApp.onEnum(of: state.mentionState) {
                ChatSuggestionPanel(
                    title: payload.isLoading && payload.suggestions.isEmpty ? "Searching files..." : nil,
                    emptyTitle: payload.error ?? "No matches",
                    rows: payload.suggestions.map {
                        ChatSuggestionRow(
                            id: $0.path,
                            icon: $0.isDirectory ? "folder" : "doc",
                            title: displayName(path: $0.path),
                            subtitle: $0.path
                        )
                    },
                    onSelect: { rowId in
                        guard let entry = payload.suggestions.first(where: { $0.path == rowId }) else { return }
                        onSelectMentionSuggestion(entry)
                        isInputFocused = true
                    }
                )
            }

            VStack(spacing: 0) {
                if !state.pendingAttachments.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(state.pendingAttachments, id: \.id) { attachment in
                                ChatAttachmentChip(attachment: attachment) {
                                    onRemoveAttachment(attachment)
                                }
                            }
                        }
                        .padding(.horizontal, 16)
                        .padding(.top, 12)
                        .padding(.bottom, 8)
                    }
                }

                ZStack(alignment: .topLeading) {
                    if state.inputText.isEmpty {
                        Text("Ask anything...")
                            .font(.body)
                            .foregroundStyle(Color(.placeholderText))
                            .allowsHitTesting(false)
                    }

                    ChatComposerInputTextView(
                        text: state.inputText,
                        cursorPosition: Int(state.inputCursor),
                        isFocused: $isInputFocused,
                        isEditable: true,
                        dynamicHeight: $inputHeight,
                        onTextAndCursorChange: onTextAndCursorChange,
                        onPasteImageData: onPasteImageData
                    )
                    .frame(height: max(inputHeight, 24))
                }
                .padding(.horizontal, 16)
                .padding(.top, state.pendingAttachments.isEmpty ? 12 : 6)
                .padding(.bottom, 10)

                Divider()
                    .overlay(Color.primary.opacity(0.08))
                    .padding(.horizontal, 16)

                HStack(spacing: 10) {
                    Menu {
                        Button("Photos", systemImage: "photo", action: onPickPhotos)
                            .disabled(!canAddAttachments)
                        Button("Files", systemImage: "paperclip", action: onPickFiles)
                            .disabled(!canAddAttachments)
                        Button("Paste from Clipboard", systemImage: "doc.on.clipboard", action: onAddFromClipboard)
                            .disabled(!canAddAttachments || !state.hasClipboardImage)
                    } label: {
                        Image(systemName: "plus")
                            .font(.system(size: 14, weight: .medium))
                            .foregroundStyle(.secondary)
                            .frame(width: 30, height: 30)
                            .chatGlassCircle(tint: Color.primary.opacity(0.04))
                    }

                    if !state.thinkingVariants.isEmpty {
                        Menu {
                            Button {
                                onSelectThinkingVariant(nil)
                            } label: {
                                if state.thinkingVariant == nil {
                                    Label("Auto", systemImage: "checkmark")
                                } else {
                                    Text("Auto")
                                }
                            }

                            ForEach(state.thinkingVariants, id: \.self) { variant in
                                Button {
                                    onSelectThinkingVariant(variant)
                                } label: {
                                    if state.thinkingVariant == variant {
                                        Label(variant.capitalized, systemImage: "checkmark")
                                    } else {
                                        Text(variant.capitalized)
                                    }
                                }
                            }
                        } label: {
                            HStack(spacing: 6) {
                                Image(systemName: "brain")
                                    .font(.system(size: 12, weight: .medium))
                                Text(thinkingTitle)
                                    .font(.subheadline)
                                    .lineLimit(1)
                                Image(systemName: "chevron.down")
                                    .font(.system(size: 9, weight: .medium))
                            }
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 7)
                            .chatGlassCapsule(tint: Color.primary.opacity(0.04))
                        }
                    }

                    Spacer(minLength: 0)

                    Button(action: showStopButton ? onAbort : onSend) {
                        Image(systemName: showStopButton ? "stop.fill" : "arrow.up")
                            .font(.system(size: 13, weight: .bold))
                            .foregroundStyle(showStopButton || canSend ? Color.primary : Color.secondary)
                            .frame(width: 34, height: 34)
                            .chatGlassCircle(
                                tint: showStopButton || canSend ? Color.primary.opacity(0.14) : Color.primary.opacity(0.03)
                            )
                    }
                    .disabled(!showStopButton && !canSend)
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, 10)
            }
        }
    }
    }

    private func displayName(path: String) -> String {
        let name = path.split(separator: "/").last.map(String.init) ?? path
        return name.isEmpty ? path : name
    }
}

private struct ChatToolbarIconButton<Label: View>: View {
    let action: () -> Void
    @ViewBuilder let label: () -> Label

    var body: some View {
        Group {
            Button(action: action) {
                label()
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(.primary)
                    .frame(width: 34, height: 34)
                    .chatGlassCircle(tint: Color.white.opacity(0.01))
            }
            .buttonStyle(.plain)
        }
    }
}

private struct ChatToolbarProcessingBar: View {
    @State private var animate = false

    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Capsule()
                    .fill(Color.primary.opacity(0.08))

                Capsule()
                    .fill(
                        LinearGradient(
                            colors: [Color.accentColor.opacity(0.25), Color.accentColor, Color.orange.opacity(0.65)],
                            startPoint: .leading,
                            endPoint: .trailing
                        )
                    )
                    .frame(width: max(72, proxy.size.width * 0.34))
                    .offset(x: animate ? proxy.size.width - max(72, proxy.size.width * 0.34) : 0)
            }
        }
        .frame(height: 4)
        .onAppear {
            withAnimation(.easeInOut(duration: 1.2).repeatForever(autoreverses: true)) {
                animate = true
            }
        }
    }
}

private struct ChatStatusBanner: View {
    let title: String
    let message: String

    var body: some View {
        HStack(spacing: 10) {
            ProgressView()
                .controlSize(.small)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                Text(message)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .chatGlassCard(cornerRadius: 18)
    }
}

private struct ChatErrorBanner: View {
    let message: String
    let showRevert: Bool
    let onDismiss: () -> Void
    let onRetry: () -> Void
    let onRevert: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(message)
                .font(.subheadline)
                .foregroundStyle(.primary)

            HStack(spacing: 8) {
                Button("Dismiss", action: onDismiss)
                Button("Retry", action: onRetry)
                if showRevert {
                    Button("Revert", action: onRevert)
                }
                Spacer(minLength: 0)
            }
            .font(.caption.weight(.semibold))
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(Color.red.opacity(0.10))
                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .strokeBorder(Color.red.opacity(0.18), lineWidth: 1)
        )
        .shadow(color: .black.opacity(0.06), radius: 14, x: 0, y: 8)
    }
}

private struct ChatSuggestionRow: Identifiable {
    let id: String
    let icon: String
    let title: String
    let subtitle: String?
}

private struct ChatSuggestionPanel: View {
    let title: String?
    let emptyTitle: String
    let rows: [ChatSuggestionRow]
    let onSelect: (String) -> Void

    var body: some View {
        VStack(spacing: 0) {
            if rows.isEmpty {
                HStack(spacing: 10) {
                    if let title {
                        ProgressView()
                            .controlSize(.small)
                        Text(title)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    } else {
                        Text(emptyTitle)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
            } else {
                ForEach(Array(rows.prefix(6).enumerated()), id: \.element.id) { index, row in
                    Button {
                        onSelect(row.id)
                    } label: {
                        HStack(spacing: 10) {
                            Image(systemName: row.icon)
                                .font(.system(size: 13, weight: .medium))
                                .foregroundStyle(.secondary)
                                .frame(width: 22)

                            VStack(alignment: .leading, spacing: 2) {
                                Text(row.title)
                                    .font(.subheadline)
                                    .foregroundStyle(.primary)
                                    .lineLimit(1)
                                if let subtitle = row.subtitle, !subtitle.isEmpty {
                                    Text(subtitle)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                        .lineLimit(1)
                                }
                            }

                            Spacer(minLength: 0)
                        }
                        .padding(.horizontal, 14)
                        .padding(.vertical, 11)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)

                    if index < min(rows.count, 6) - 1 {
                        Divider()
                            .padding(.leading, 46)
                    }
                }
            }
        }
        .chatGlassCard(cornerRadius: 20)
        .padding(.horizontal, 12)
    }
}

private struct ChatAttachmentChip: View {
    let attachment: Attachment
    let onRemove: () -> Void

    private var thumbnailImage: UIImage? {
        if attachment.isImage {
            let bytes = attachment.thumbnailBytes ?? attachment.bytes
            return ImageDownsampler.makeThumbnailImage(data: bytes.toData(), maxPixelSize: 64)
        }
        return nil
    }

    var body: some View {
        HStack(spacing: 8) {
            Group {
                if let thumbnailImage {
                    Image(uiImage: thumbnailImage)
                        .resizable()
                        .scaledToFill()
                } else {
                    Image(systemName: attachment.isImage ? "photo" : "doc")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(.secondary)
                }
            }
            .frame(width: 24, height: 24)
            .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))

            Text(attachment.filename)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)

            Button(action: onRemove) {
                Image(systemName: "xmark.circle.fill")
                    .font(.system(size: 14))
                    .foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .chatGlassCapsule(tint: Color.primary.opacity(0.04))
    }
}

struct ChatAttachmentErrorView: View {
    let message: String
    let onDismiss: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Text(message)
                .font(.subheadline)
                .foregroundStyle(.primary)
                .multilineTextAlignment(.leading)
            Spacer(minLength: 0)
            Button(action: onDismiss) {
                Image(systemName: "xmark.circle.fill")
                    .foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .chatGlassCard(cornerRadius: 18)
        .padding(.horizontal, 12)
    }
}

extension View {
    @ViewBuilder
    func chatGlassGrouping<Content: View>(spacing: CGFloat, @ViewBuilder content: () -> Content) -> some View {
        if #available(iOS 26, *) {
            GlassEffectContainer(spacing: spacing) {
                content()
            }
        } else {
            content()
        }
    }

    @ViewBuilder
    func chatGlassCard(cornerRadius: CGFloat, tint: Color = Color.primary.opacity(0.02)) -> some View {
        if #available(iOS 26, *) {
            self
                .glassEffect(.regular.tint(tint), in: .rect(cornerRadius: cornerRadius))
                .overlay(
                    RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                        .strokeBorder(Color.white.opacity(0.22), lineWidth: 0.8)
                )
        } else {
            self
                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                        .strokeBorder(Color.primary.opacity(0.07), lineWidth: 1)
                )
                .shadow(color: .black.opacity(0.06), radius: 18, x: 0, y: 10)
        }
    }

    @ViewBuilder
    func chatGlassHostRegion(cornerRadius: CGFloat, tint: Color = .clear) -> some View {
        if #available(iOS 26, *) {
            self
                .glassEffect(.regular.tint(tint), in: .rect(cornerRadius: cornerRadius))
        } else {
            self
                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
        }
    }

    @ViewBuilder
    func chatGlassCapsule(tint: Color = Color.primary.opacity(0.04)) -> some View {
        if #available(iOS 26, *) {
            self
                .glassEffect(.regular.tint(tint), in: .capsule)
        } else {
            self
                .background(Color.primary.opacity(0.06), in: Capsule())
        }
    }

    @ViewBuilder
    func chatGlassCircle(tint: Color = Color.primary.opacity(0.04)) -> some View {
        if #available(iOS 26, *) {
            self
                .glassEffect(.regular.tint(tint), in: .circle)
        } else {
            self
                .background(Color.primary.opacity(0.06), in: Circle())
        }
    }
}
