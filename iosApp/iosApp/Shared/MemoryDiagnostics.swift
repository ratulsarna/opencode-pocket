import Foundation
import UIKit

#if DEBUG
import Darwin
import MachO

@MainActor
final class MemoryDiagnostics {
    static let shared = MemoryDiagnostics()

    private var timer: DispatchSourceTimer?
    private var isObservingMemoryWarnings = false

    private init() {}

    func start(intervalSeconds: TimeInterval = 10) {
        guard timer == nil else { return }

        if !isObservingMemoryWarnings {
            NotificationCenter.default.addObserver(
                self,
                selector: #selector(onMemoryWarning),
                name: UIApplication.didReceiveMemoryWarningNotification,
                object: nil
            )
            isObservingMemoryWarnings = true
        }

        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .utility))
        timer.schedule(deadline: .now() + intervalSeconds, repeating: intervalSeconds)
        timer.setEventHandler { [weak self] in
            self?.log(context: "periodic")
        }
        timer.resume()
        self.timer = timer

        log(context: "started")
    }

    func stop() {
        timer?.cancel()
        timer = nil

        if isObservingMemoryWarnings {
            NotificationCenter.default.removeObserver(
                self,
                name: UIApplication.didReceiveMemoryWarningNotification,
                object: nil
            )
            isObservingMemoryWarnings = false
        }
    }

    @objc
    private func onMemoryWarning() {
        log(context: "memory_warning")
    }

    nonisolated private func log(context: String) {
        guard let footprint = currentPhysicalFootprintBytes() else {
            print("[oc-pocket] mem(\(context)): footprint=unknown")
            return
        }
        let mb = Double(footprint) / (1024.0 * 1024.0)
        print(String(format: "[oc-pocket] mem(%@): footprint=%.1fMB", context, mb))
    }

    nonisolated private func currentPhysicalFootprintBytes() -> UInt64? {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<integer_t>.size)

        let kr: kern_return_t = withUnsafeMutablePointer(to: &info) { infoPtr in
            infoPtr.withMemoryRebound(to: integer_t.self, capacity: Int(count)) { intPtr in
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), intPtr, &count)
            }
        }

        guard kr == KERN_SUCCESS else { return nil }
        return info.phys_footprint
    }
}
#endif
