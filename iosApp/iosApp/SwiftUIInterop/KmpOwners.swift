import Foundation
import ComposeApp

@MainActor
final class KmpAppOwnerStore: ObservableObject {
    let owner = IosAppViewModelOwner()

    deinit {
        owner.clear()
    }
}

@MainActor
final class KmpScreenOwnerStore: ObservableObject {
    let owner = IosScreenViewModelOwner()

    deinit {
        owner.clear()
    }
}

