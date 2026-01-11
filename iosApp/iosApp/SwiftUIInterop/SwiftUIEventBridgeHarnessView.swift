import SwiftUI
import ComposeApp

@MainActor
struct SwiftUIEventBridgeHarnessView: View {
    @State private var source = IosEventBridgeTestSource()
    @StateObject private var bridge = KmpUiEventBridge<String>()
    @State private var received: [String] = []
    @State private var emitCounter: Int = 0

    var body: some View {
        Observing(source.subscriptionCount) {
            SamFullScreenLoadingView(title: "Loading…")
        } content: { subscriptionCount in
            let count = subscriptionCount.intValue
            List {
                Section("Status") {
                    LabeledContent("subscriptionCount", value: "\(count)")
                    LabeledContent("received", value: "\(received.count)")
                }

                Section("Actions") {
                    Button("Start collector") {
                        bridge.start(flow: source.events) { value in
                            received.append(value)
                        }
                    }

                    Button("Stop collector") {
                        bridge.stop()
                    }

                    Button("Emit 10 events") {
                        for _ in 0..<10 {
                            emitCounter += 1
                            _ = source.tryEmit(value: "event-\(emitCounter)")
                        }
                    }

                    Button("Clear received") {
                        received.removeAll()
                    }
                }

                if !received.isEmpty {
                    Section("Received") {
                        ForEach(Array(received.enumerated()), id: \.offset) { _, item in
                            Text(item)
                                .font(.caption)
                                .textSelection(.enabled)
                        }
                    }
                }
            }
            .navigationTitle("Event Bridge")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}
