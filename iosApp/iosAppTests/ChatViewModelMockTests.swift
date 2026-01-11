import Foundation
import XCTest
import ComposeApp
 import OpenCodePocket

@MainActor
final class ChatViewModelMockTests: XCTestCase {
    override func setUp() {
        super.setUp()
        AppModule.shared.isMockMode = true
    }

    override func tearDown() {
        AppModule.shared.isMockMode = false
        super.tearDown()
    }

    func test_setInputTextUpdatesState() {
        let viewModel = AppModule.shared.createChatViewModel()

        viewModel.setInputText(text: "hello")

        XCTAssertEqual(viewModel.uiState.value.inputText, "hello")
        XCTAssertEqual(viewModel.uiState.value.inputCursor, 5)
    }

    func test_sendCurrentMessageClearsInputAndAddsOptimisticMessage() async {
        let viewModel = AppModule.shared.createChatViewModel()

        await waitUntil({ viewModel.uiState.value.currentSessionId != nil })
        await waitUntil({ viewModel.uiState.value.isLoading == false })

        viewModel.setInputText(text: "Hi")
        let beforeCount = viewModel.uiState.value.messages.count

        viewModel.sendCurrentMessage()

        let state = viewModel.uiState.value
        XCTAssertEqual(state.inputText, "")
        XCTAssertEqual(state.pendingAttachments.count, 0)
        XCTAssertTrue(state.messages.count >= beforeCount + 1)

        let containsSentMessage = state.messages.contains { message in
            guard let userMessage = message as? UserMessage else { return false }
            return userMessage.parts.contains { part in
                guard let textPart = part as? TextPart else { return false }
                return textPart.text == "Hi"
            }
        }
        XCTAssertTrue(containsSentMessage)
    }

    func test_addAttachmentUpdatesPendingAttachments() async {
        let viewModel = AppModule.shared.createChatViewModel()

        let bytes = Data([0x01, 0x02, 0x03]).toKotlinByteArray()
        let attachment = Attachment(
            id: UUID().uuidString,
            filename: "test.txt",
            mimeType: "text/plain",
            bytes: bytes,
            thumbnailBytes: nil
        )

        let beforeCount = viewModel.uiState.value.pendingAttachments.count
        viewModel.addAttachment(attachment: attachment)

        XCTAssertEqual(viewModel.uiState.value.pendingAttachments.count, beforeCount + 1)
        XCTAssertEqual(viewModel.uiState.value.pendingAttachments.last?.filename, "test.txt")
    }
}
