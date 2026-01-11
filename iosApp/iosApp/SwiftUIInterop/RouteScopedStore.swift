import SwiftUI

@MainActor
enum RouteScopedStore {
    static func ensure<Store>(_ store: Binding<Store?>, create: () -> Store) {
        if store.wrappedValue == nil {
            store.wrappedValue = create()
        }
    }

    static func sync<Route, Store>(
        _ store: Binding<Store?>,
        path: [Route],
        needsStore: (Route) -> Bool,
        create: () -> Store
    ) {
        if path.contains(where: needsStore) {
            ensure(store, create: create)
        } else {
            store.wrappedValue = nil
        }
    }
}
