import SwiftUI
import ComposeApp

@MainActor
struct SwiftUIMarkdownFileViewerView: View {
    let viewModel: MarkdownFileViewerViewModel
    let onOpenFile: (String) -> Void

    var body: some View {
        Observing(viewModel.uiState) {
            SamFullScreenLoadingView(title: "Opening file…")
        } content: { uiState in
            content(uiState: uiState)
                .navigationTitle(uiState.title)
                .navigationBarTitleDisplayMode(.inline)
        }
    }

    @ViewBuilder
    private func content(uiState: MarkdownFileViewerUiState) -> some View {
        if uiState.isLoading {
            SamFullScreenLoadingView(title: "Opening file…", subtitle: "Rendering Markdown.")
        } else if let error = uiState.error, !error.isEmpty {
            VStack(spacing: 12) {
                Text(error)
                    .foregroundStyle(.red)
                    .multilineTextAlignment(.center)
                Button("Retry") {
                    viewModel.reload()
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .padding(16)
        } else if uiState.content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            Text("File is empty.")
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .padding(16)
        } else if uiState.isMarkdown {
            GeometryReader { proxy in
                ScrollView {
                    let contentWidth = max(0, proxy.size.width - 32)
                    OCMobileMarkdownView(text: uiState.content, isSecondary: false, onOpenFile: onOpenFile)
                        .frame(width: contentWidth, alignment: .leading)
                        .padding(16)
                }
            }
        } else {
            ScrollView([.vertical, .horizontal]) {
                Text(uiState.content)
                    .font(.system(.footnote, design: .monospaced))
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .topLeading)
                    .padding(16)
            }
        }
    }
}
