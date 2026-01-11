import SwiftUI
import ComposeApp

@MainActor
struct SkieSmokeTestRootView: View {
    @StateObject private var kmp = KmpAppOwnerStore()

    @State private var echoStatus: String = "Idle"
    @State private var echoTask: Task<Void, Never>?

    @State private var apiResultStatus: String = "Idle"
    @State private var apiResultTask: Task<Void, Never>?

    var body: some View {
        let viewModel = kmp.owner.skieSmokeTestViewModel()

        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                GroupBox("Flow observation (SKIE)") {
                    Observing(viewModel.counter) {
                        ProgressView("Waiting for flows…")
                    } content: { counter in
                        VStack(alignment: .leading, spacing: 8) {
                            Text("counter: \(counter.intValue)")
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .padding(.vertical, 4)
                }

                GroupBox("ViewModel onCleared() signal") {
                    Observing(SkieSmokeTestSignals.shared.clearedCount, SkieSmokeTestSignals.shared.lastClearedAtEpochMs) {
                        ProgressView("Waiting for signals…")
                    } content: { clearedCount, lastClearedAtEpochMs in
                        VStack(alignment: .leading, spacing: 6) {
                            Text("clearedCount: \(clearedCount.intValue)")
                            Text("lastClearedAtEpochMs: \(lastClearedAtEpochMs.int64Value)")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                            Text("Switch roots or close the app to trigger owner.clear() → onCleared().")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .padding(.vertical, 4)
                }

                GroupBox("Suspend + cancellation (SKIE)") {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Status: \(echoStatus)")
                            .font(.footnote)
                            .foregroundStyle(.secondary)

                        HStack {
                            Button("Start (5s)") {
                                startEcho(delayMs: 5_000)
                            }
                            Button("Cancel") {
                                echoTask?.cancel()
                                echoTask = nil
                            }
                        }
                    }
                }

                GroupBox("ApiResult sealed bridging (SKIE)") {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Status: \(apiResultStatus)")
                            .font(.footnote)
                            .foregroundStyle(.secondary)

                        HStack {
                            Button("Probe success") {
                                startApiResultProbe(shouldFail: false)
                            }
                            Button("Probe failure") {
                                startApiResultProbe(shouldFail: true)
                            }
                            Button("Cancel") {
                                apiResultTask?.cancel()
                                apiResultTask = nil
                            }
                        }
                    }
                }

                Spacer()

                Text("Phase 0 only. Set env var `OC_POCKET_IOS_ROOT=skie_smoke` (DEBUG) to show this screen.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            .padding(16)
            .navigationTitle("SKIE Smoke Test")
        }
        .onDisappear {
            echoTask?.cancel()
            echoTask = nil
            apiResultTask?.cancel()
            apiResultTask = nil
        }
    }

    private func startEcho(delayMs: Int64) {
        echoTask?.cancel()
        echoStatus = "Starting…"

        echoTask = Task {
            do {
                echoStatus = "Awaiting Kotlin suspend…"
                let value = try await kmp.owner.skieSmokeTestViewModel().delayedEcho(message: "Echo OK", delayMs: delayMs)
                echoStatus = "Success: \(value)"
            } catch is CancellationError {
                echoStatus = "Cancelled"
            } catch {
                echoStatus = "Error: \(error)"
            }
        }
    }

    private func startApiResultProbe(shouldFail: Bool) {
        apiResultTask?.cancel()
        apiResultStatus = "Starting…"

        apiResultTask = Task {
            do {
                apiResultStatus = "Awaiting Kotlin suspend…"
                let result = try await kmp.owner.skieSmokeTestViewModel().apiResultProbe(shouldFail: shouldFail)

                switch ComposeApp.onEnum(of: result) {
                case .success(let payload):
                    apiResultStatus = "Success: \(String(describing: payload.value))"
                case .failure(let payload):
                    apiResultStatus = "Failure: \(describeApiError(payload.error))"
                }
            } catch is CancellationError {
                apiResultStatus = "Cancelled"
            } catch {
                apiResultStatus = "Error: \(error)"
            }
        }
    }

    private func describeApiError(_ error: ApiError) -> String {
        switch ComposeApp.onEnum(of: error) {
        case .networkError(let payload):
            return payload.message ?? "NetworkError"
        case .unauthorizedError(let payload):
            return payload.message ?? "UnauthorizedError"
        case .notFoundError(let payload):
            return payload.message ?? "NotFoundError"
        case .parseError(let payload):
            return payload.message ?? "ParseError"
        case .genericApiError(let payload):
            return payload.message ?? "GenericApiError"
        case .providerAuthError(let payload):
            return payload.message ?? "ProviderAuthError"
        case .messageAbortedError(let payload):
            return payload.message ?? "MessageAbortedError"
        case .messageOutputLengthError(let payload):
            return payload.message ?? "MessageOutputLengthError"
        }
    }

}
