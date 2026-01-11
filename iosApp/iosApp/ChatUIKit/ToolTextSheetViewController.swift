import UIKit
import SwiftUI

final class ToolTextSheetViewController: UIViewController {
    private let fullText: String
    private let filePath: String?
    private let renderAsMarkdown: Bool
    private let onOpenFile: ((String) -> Void)?
    private let textView = UITextView()

    init(
        title: String,
        text: String,
        filePath: String?,
        renderAsMarkdown: Bool,
        onOpenFile: ((String) -> Void)?
    ) {
        self.fullText = text
        self.filePath = filePath
        self.renderAsMarkdown = renderAsMarkdown
        self.onOpenFile = onOpenFile
        super.init(nibName: nil, bundle: nil)
        self.title = title
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()

        view.backgroundColor = .systemBackground
        navigationItem.largeTitleDisplayMode = .never

        navigationItem.rightBarButtonItem = UIBarButtonItem(
            image: UIImage(systemName: "xmark"),
            style: .plain,
            target: self,
            action: #selector(didTapDone)
        )
        navigationItem.leftBarButtonItem = UIBarButtonItem(
            image: UIImage(systemName: "doc.on.doc"),
            style: .plain,
            target: self,
            action: #selector(didTapCopy)
        )

        if renderAsMarkdown || shouldRenderMarkdown(filePath: filePath) {
            let root = ScrollView {
                OCMobileMarkdownView(
                    text: fullText,
                    isSecondary: false,
                    onOpenFile: { [weak self] file in self?.onOpenFile?(file) },
                    baseTextStyle: .body
                )
                .padding(16)
            }
            .background(Color(uiColor: .systemBackground))

            let host = UIHostingController(rootView: root)
            host.view.backgroundColor = .clear

            addChild(host)
            view.addSubview(host.view)
            host.view.translatesAutoresizingMaskIntoConstraints = false
            NSLayoutConstraint.activate([
                host.view.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
                host.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
                host.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
                host.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            ])
            host.didMove(toParent: self)
        } else {
            textView.isEditable = false
            textView.isSelectable = true
            textView.alwaysBounceVertical = true
            textView.backgroundColor = .clear
            textView.dataDetectorTypes = [.link]
            textView.font = .monospacedSystemFont(ofSize: UIFont.preferredFont(forTextStyle: .footnote).pointSize, weight: .regular)
            textView.textColor = .label
            textView.textContainerInset = UIEdgeInsets(top: 16, left: 16, bottom: 16, right: 16)
            textView.textContainer.lineFragmentPadding = 0
            textView.text = fullText

            view.addSubview(textView)
            textView.translatesAutoresizingMaskIntoConstraints = false
            NSLayoutConstraint.activate([
                textView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
                textView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
                textView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
                textView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            ])
        }
    }

    @objc private func didTapDone() {
        dismiss(animated: true)
    }

    @objc private func didTapCopy() {
        UIPasteboard.general.string = fullText
        let generator = UINotificationFeedbackGenerator()
        generator.notificationOccurred(.success)
    }

    private func shouldRenderMarkdown(filePath: String?) -> Bool {
        guard let filePath else { return false }
        let lower = filePath.lowercased()
        return lower.hasSuffix(".md") || lower.hasSuffix(".markdown") || lower.hasSuffix(".mdown")
    }
}
