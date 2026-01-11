package com.ratulsarna.ocmobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ratulsarna.ocmobile.di.AppModule
import com.ratulsarna.ocmobile.ui.screen.chat.ChatViewModel
import com.ratulsarna.ocmobile.ui.screen.connect.ConnectViewModel
import com.ratulsarna.ocmobile.ui.screen.docs.MarkdownFileViewerViewModel
import com.ratulsarna.ocmobile.ui.screen.filebrowser.FileBrowserViewModel
import com.ratulsarna.ocmobile.ui.screen.sessions.SessionsViewModel
import com.ratulsarna.ocmobile.ui.screen.settings.SettingsViewModel
import com.ratulsarna.ocmobile.ui.screen.workspaces.WorkspacesViewModel

/**
 * iOS-facing ViewModel owners for SwiftUI.
 *
 * Compose currently provides a [ViewModelStoreOwner] on iOS via `ComposeUIViewController`.
 * When we migrate iOS UI to pure SwiftUI, Swift must own a [ViewModelStore] explicitly so
 * AndroidX ViewModels can be retained and, importantly, cleared deterministically (calling
 * `onCleared()` and cancelling `viewModelScope`).
 *
 * These owners mirror the scoping used in `AppNavigation`:
 * - App-scoped (hoisted) ViewModels: chat/settings/fileBrowser
 * - Screen-scoped ViewModels: sessions
 * - Parameter-scoped ViewModels: markdown viewer
 */

class IosAppViewModelOwner : ViewModelStoreOwner {

    override val viewModelStore: ViewModelStore = ViewModelStore()

    fun clear() {
        viewModelStore.clear()
    }

    /** Matches Compose `viewModel(key = "chat") { AppModule.createChatViewModel() }` */
    fun chatViewModel(): ChatViewModel = get(key = "chat") { AppModule.createChatViewModel() }

    /** Matches Compose `viewModel(key = "settings") { AppModule.createSettingsViewModel() }` */
    fun settingsViewModel(): SettingsViewModel = get(key = "settings") { AppModule.createSettingsViewModel() }

    /** Matches Compose `viewModel(key = "workspaces") { AppModule.createWorkspacesViewModel() }` */
    fun workspacesViewModel(): WorkspacesViewModel = get(key = "workspaces") { AppModule.createWorkspacesViewModel() }

    /** App-scoped: used for onboarding/pairing flow. */
    fun connectViewModel(): ConnectViewModel = get(key = "connect") { AppModule.createConnectViewModel() }

    /** Matches Compose `viewModel(key = "fileBrowser") { AppModule.createFileBrowserViewModel() }` */
    fun fileBrowserViewModel(): FileBrowserViewModel = get(key = "fileBrowser") { AppModule.createFileBrowserViewModel() }

    /** Matches Compose `viewModel(key = "markdown-$path-$openId") { AppModule.createMarkdownFileViewerViewModel(path) }` */
    fun markdownFileViewerViewModel(path: String, openId: Long): MarkdownFileViewerViewModel =
        get(key = "markdown-$path-$openId") { AppModule.createMarkdownFileViewerViewModel(path) }

    /**
     * Phase 0: ViewModel used only for validating SKIE interop + ViewModelStore lifecycle from SwiftUI.
     * Intentionally does not touch the network.
     */
    fun skieSmokeTestViewModel(): SkieSmokeTestViewModel =
        get(key = "skieSmoke") { SkieSmokeTestViewModel() }

    private inline fun <reified VM : ViewModel> get(key: String, noinline create: () -> VM): VM {
        val factory = viewModelFactory {
            initializer {
                create()
            }
        }
        val provider = ViewModelProvider.create(viewModelStore, factory, CreationExtras.Empty)
        return provider[key, VM::class]
    }
}

/**
 * Screen-scoped owner for SwiftUI screens that should not share state across tabs/routes.
 */
class IosScreenViewModelOwner : ViewModelStoreOwner {

    override val viewModelStore: ViewModelStore = ViewModelStore()

    fun clear() {
        viewModelStore.clear()
    }

    fun sessionsViewModel(): SessionsViewModel = get(key = "sessions") { AppModule.createSessionsViewModel() }

    /** Matches Compose `viewModel(key = "markdown-$path-$openId") { AppModule.createMarkdownFileViewerViewModel(path) }` */
    fun markdownFileViewerViewModel(path: String, openId: Long): MarkdownFileViewerViewModel =
        get(key = "markdown-$path-$openId") { AppModule.createMarkdownFileViewerViewModel(path) }

    private inline fun <reified VM : ViewModel> get(key: String, noinline create: () -> VM): VM {
        val factory = viewModelFactory {
            initializer {
                create()
            }
        }
        val provider = ViewModelProvider.create(viewModelStore, factory, CreationExtras.Empty)
        return provider[key, VM::class]
    }
}
