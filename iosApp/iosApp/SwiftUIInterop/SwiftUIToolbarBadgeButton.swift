import SwiftUI

struct SwiftUIToolbarBadgeButton: View {
    let systemImage: String
    let count: Int
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .frame(width: 44, height: 44, alignment: .center)
                .overlay {
                    if count > 0 {
                        let text = count > 99 ? "99+" : "\(count)"
                        let badgeCenterX: CGFloat = switch text.count {
                        case 1: 34
                        case 2: 32
                        default: 31
                        }
                        let badgeCenterY: CGFloat = 12

                        // Keep the badge fully inside the 44x44 tap target to avoid any clipping by
                        // the navigation bar’s pill/group container on newer iOS versions.
                        SwiftUIToolbarBadge(text: text)
                            .position(x: badgeCenterX, y: badgeCenterY)
                            .allowsHitTesting(false)
                    }
                }
        }
        .buttonStyle(.plain)
    }
}

private struct SwiftUIToolbarBadge: View {
    let text: String

    var body: some View {
        let horizontalPadding: CGFloat = switch text.count {
        case 1: 4.5
        case 2: 5.5
        default: 4.5
        }

        Text(text)
            .font(.system(size: 10, weight: .semibold))
            .monospacedDigit()
            .foregroundStyle(.white)
            .padding(.horizontal, horizontalPadding)
            .padding(.vertical, 1.5)
            .frame(minWidth: 15, minHeight: 15)
            .fixedSize(horizontal: true, vertical: true)
            .background(Color.red, in: Capsule())
    }
}
