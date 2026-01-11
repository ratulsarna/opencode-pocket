import SwiftUI
import ComposeApp
import VisionKit
import UIKit

@MainActor
struct SwiftUIConnectToOpenCodeView: View {
    let viewModel: ConnectViewModel
    let onConnected: () -> Void
    let onDisconnected: () -> Void

    @State private var isShowingScanner = false
    @State private var isShowingPairingEditor = false

    var body: some View {
        Observing(viewModel.uiState) {
            SamFullScreenLoadingView(title: "Loading…")
        } content: { uiState in
            Form {
                if uiState.hasAuthTokenForActiveServer {
                    Section("Connected") {
                        if let name = uiState.activeServerName, !name.isEmpty {
                            LabeledContent("Server", value: name)
                        }
                        if let baseUrl = uiState.activeServerBaseUrl, !baseUrl.isEmpty {
                            LabeledContent("Base URL", value: baseUrl)
                        }

                        Button(role: .destructive) {
                            viewModel.disconnect()
                        } label: {
                            Text("Disconnect")
                        }
                    }
                }

                Section {
                    Text("Scan the QR code shown in Terminal after running `oc-pocket setup`, or paste the pairing string.")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }

                Section("Pairing") {
                    if QrScannerView.canScan {
                        Button {
                            isShowingScanner = true
                        } label: {
                            Label("Scan QR code", systemImage: "qrcode.viewfinder")
                        }
                    } else {
                        HStack {
                            Label("Scan QR code", systemImage: "qrcode.viewfinder")
                            Spacer()
                            Text("Device only")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }

                    Button {
                        if let text = UIPasteboard.general.string {
                            viewModel.setPairingString(raw: text)
                            isShowingPairingEditor = true
                        } else {
                            isShowingPairingEditor = true
                        }
                    } label: {
                        Label("Paste pairing string", systemImage: "doc.on.clipboard")
                    }

                    Button {
                        isShowingPairingEditor = true
                    } label: {
                        Label("Enter pairing string", systemImage: "pencil")
                    }

                    let hasPairing = !uiState.pairingString.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    if isShowingPairingEditor || hasPairing {
                        ZStack(alignment: .topLeading) {
                            TextEditor(
                                text: Binding(
                                    get: { uiState.pairingString },
                                    set: { viewModel.setPairingString(raw: $0) }
                                )
                            )
                            .font(.system(.body, design: .monospaced))
                            .frame(height: 140)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)

                            if !hasPairing {
                                Text("Paste or type the pairing string here…")
                                    .foregroundStyle(.secondary)
                                    .padding(.top, 8)
                                    .padding(.leading, 4)
                                    .allowsHitTesting(false)
                            }
                        }
                    } else {
                        Text("Scan a QR code or paste a pairing string to continue.")
                            .font(.callout)
                            .foregroundStyle(.secondary)
                    }
                }

                if let parsed = uiState.parsed {
                    Section("Preview") {
                        LabeledContent("Name", value: parsed.name ?? "OpenCode")
                        LabeledContent("Base URL", value: parsed.baseUrl)
                    }
                }

                if uiState.isAwaitingLocalNetworkPermission {
                    Section {
                        Text(uiState.error ?? "Waiting for Local Network permission. Tap Allow on the iOS prompt.")
                            .font(.callout)
                            .foregroundStyle(.secondary)

                        Button("Open iOS Settings") {
                            if let url = URL(string: UIApplication.openSettingsURLString) {
                                UIApplication.shared.open(url)
                            }
                        }
                        .font(.callout)
                    }
                } else if let error = uiState.error, !error.isEmpty {
                    Section {
                        Text(error)
                            .foregroundStyle(.red)
                    }
                }

                Section {
                    Button {
                        viewModel.connect()
                    } label: {
                        HStack {
                            Spacer()
                            if uiState.isConnecting {
                                ProgressView()
                                    .controlSize(.small)
                                    .padding(.trailing, 6)
                            }
                            Text(uiState.isConnecting ? "Connecting…" : "Connect")
                                .font(.headline)
                            Spacer()
                        }
                    }
                    .disabled(uiState.isConnecting || uiState.pairingString.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            .navigationTitle("Connect to OpenCode")
            .navigationBarTitleDisplayMode(.inline)
            .sheet(isPresented: $isShowingScanner) {
                QrScannerView(
                    onScanned: { value in
                        viewModel.setPairingString(raw: value)
                        viewModel.connect()
                        isShowingPairingEditor = true
                        isShowingScanner = false
                    },
                    onCancel: {
                        isShowingScanner = false
                    }
                )
            }
            .task(id: uiState.connectedServerId ?? "nil") {
                guard uiState.connectedServerId != nil else { return }
                onConnected()
            }
            .task(id: uiState.disconnectRevision) {
                guard uiState.disconnectRevision > 0 else { return }
                onDisconnected()
            }
        }
    }
}

@MainActor
private struct QrScannerView: UIViewControllerRepresentable {
    static var canScan: Bool {
        DataScannerViewController.isSupported && DataScannerViewController.isAvailable
    }

    let onScanned: (String) -> Void
    let onCancel: () -> Void

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let controller = DataScannerViewController(
            recognizedDataTypes: [.barcode(symbologies: [.qr])],
            qualityLevel: .balanced,
            recognizesMultipleItems: false,
            isHighFrameRateTrackingEnabled: false,
            isHighlightingEnabled: true
        )
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ uiViewController: DataScannerViewController, context: Context) {
        if uiViewController.isScanning { return }
        do {
            try uiViewController.startScanning()
        } catch {
            onCancel()
        }
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(onScanned: onScanned, onCancel: onCancel)
    }

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        private var didFinish = false
        private let onScanned: (String) -> Void
        private let onCancel: () -> Void

        init(onScanned: @escaping (String) -> Void, onCancel: @escaping () -> Void) {
            self.onScanned = onScanned
            self.onCancel = onCancel
        }

        func dataScanner(_ dataScanner: DataScannerViewController, didAdd addedItems: [RecognizedItem], allItems: [RecognizedItem]) {
            guard !didFinish else { return }
            for item in addedItems {
                if case let .barcode(barcode) = item, let value = barcode.payloadStringValue, !value.isEmpty {
                    didFinish = true
                    onScanned(value)
                    return
                }
            }
        }

        func dataScannerDidCancel(_ dataScanner: DataScannerViewController) {
            guard !didFinish else { return }
            didFinish = true
            onCancel()
        }
    }
}
