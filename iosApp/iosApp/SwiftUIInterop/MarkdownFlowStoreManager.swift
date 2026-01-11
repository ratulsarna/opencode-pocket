import SwiftUI

struct MarkdownRouteKey: Hashable {
    let path: String
    let openId: Int64
}

@MainActor
@Observable
final class MarkdownFlowStoreManager {
    private(set) var stores: [MarkdownRouteKey: KmpScreenOwnerStore] = [:]
    private var nextOpenId: Int64 = 1

    func ensureStore(for key: MarkdownRouteKey) {
        if stores[key] == nil {
            stores[key] = KmpScreenOwnerStore()
        }
    }

    func prune(activeKeys: Set<MarkdownRouteKey>) {
        if activeKeys.isEmpty {
            stores = [:]
            return
        }
        stores = stores.filter { activeKeys.contains($0.key) }
    }

    func openFile(path: String) -> MarkdownRouteKey {
        let openId = nextOpenId
        nextOpenId += 1
        let key = MarkdownRouteKey(path: path, openId: openId)
        ensureStore(for: key)
        return key
    }
}
