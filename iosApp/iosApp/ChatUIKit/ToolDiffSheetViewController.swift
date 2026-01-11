import UIKit

final class ToolDiffSheetViewController: UIViewController {
    private let diffText: String
    private let diffLines: [UnifiedDiffLine]
    private let layout: UnifiedDiffLayout
    private let tableView = UITableView(frame: .zero, style: .plain)

    init(title: String, diffText: String) {
        self.diffText = diffText
        let parsed = UnifiedDiffParser.parse(diffText)
        // GitHub-style: hide raw headers like "Index:" / "===" / "---" / "+++" from the main diff body.
        self.diffLines = parsed.filter { line in
            line.kind != .fileHeader && line.kind != .meta
        }
        self.layout = UnifiedDiffLayout.make(lines: self.diffLines)
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

        tableView.dataSource = self
        tableView.separatorStyle = .none
        tableView.backgroundColor = .clear
        tableView.rowHeight = UITableView.automaticDimension
        tableView.estimatedRowHeight = 22
        tableView.register(UnifiedDiffLineCell.self, forCellReuseIdentifier: UnifiedDiffLineCell.reuseIdentifier)
        tableView.contentInset = UIEdgeInsets(top: 12, left: 0, bottom: 12, right: 0)

        view.addSubview(tableView)
        tableView.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            tableView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            tableView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            tableView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            tableView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
    }

    @objc private func didTapDone() {
        dismiss(animated: true)
    }

    @objc private func didTapCopy() {
        UIPasteboard.general.string = diffText
        let generator = UINotificationFeedbackGenerator()
        generator.notificationOccurred(.success)
    }
}

extension ToolDiffSheetViewController: UITableViewDataSource {
    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        diffLines.count
    }

    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: UnifiedDiffLineCell.reuseIdentifier, for: indexPath)
        if let diffCell = cell as? UnifiedDiffLineCell {
            diffCell.configure(line: diffLines[indexPath.row], layout: layout)
        }
        return cell
    }
}
