import Foundation
import ComposeApp

@MainActor
final class KmpUiEventBridge<Element>: ObservableObject {
    private var collectionTask: Task<Void, Never>?
    private var flowIdentity: ObjectIdentifier?

    func start<Flow: SkieSwiftFlowProtocol>(
        flow: Flow,
        onEvent: @escaping @MainActor (Element) -> Void
    ) where Flow.Element == Element {
        let newIdentity = ObjectIdentifier(flow as AnyObject)
        if collectionTask != nil, flowIdentity == newIdentity {
            return
        }

        stop()
        flowIdentity = newIdentity

        collectionTask = Task.detached(priority: .userInitiated) { [flow] in
            do {
                for try await event in flow {
                    if Task.isCancelled { break }
                    await onEvent(event)
                }
            } catch {
                return
            }
        }
    }

    func stop() {
        collectionTask?.cancel()
        collectionTask = nil
        flowIdentity = nil
    }

    deinit {
        collectionTask?.cancel()
    }
}
