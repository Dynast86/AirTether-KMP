import Darwin
import Foundation
import Photos
import Shared
import SwiftUI

@main
struct iOSApp: App {
    init() {
        IosPlatformBridges.register()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

private enum IosPlatformBridges {
    static func register() {
        IosNetworkIpBridge.shared.resolveLocalIp = {
            resolveLocalIPv4Address()
        }

        IosPhotoLibraryBridge.shared.onExportToPhotos = { filePath in
            exportFileToPhotoLibrary(filePath: filePath)
        }
    }

    /// Resolves the device's active local IPv4 address (preferring Wi-Fi `en0` or personal hotspot `bridge100`).
    private static func resolveLocalIPv4Address() -> String? {
        var ifaddrPointer: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddrPointer) == 0, let firstAddr = ifaddrPointer else {
            return nil
        }
        defer { freeifaddrs(ifaddrPointer) }

        var preferredAddress: String?
        var fallbackAddress: String?

        var cursor: UnsafeMutablePointer<ifaddrs>? = firstAddr
        while let current = cursor?.pointee {
            defer { cursor = current.ifa_next }

            let flags = Int32(current.ifa_flags)
            let isUp = (flags & IFF_UP) != 0
            let isLoopback = (flags & IFF_LOOPBACK) != 0
            guard isUp, !isLoopback, let addr = current.ifa_addr else {
                continue
            }

            guard addr.pointee.sa_family == UInt8(AF_INET) else {
                continue
            }

            let interfaceName = String(cString: current.ifa_name)
            var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            let result = getnameinfo(
                addr,
                socklen_t(addr.pointee.sa_len),
                &hostname,
                socklen_t(hostname.count),
                nil,
                0,
                NI_NUMERICHOST
            )
            guard result == 0 else { continue }

            let ip = String(cString: hostname)
            guard !ip.isEmpty, !ip.hasPrefix("169.254.") else { continue }

            if interfaceName == "en0" || interfaceName == "bridge100" {
                preferredAddress = ip
                break
            } else if interfaceName.hasPrefix("en") && fallbackAddress == nil {
                fallbackAddress = ip
            }
        }

        return preferredAddress ?? fallbackAddress
    }

    /// Saves a completed capture file into the iOS Photos library using `PHPhotoLibrary`.
    private static func exportFileToPhotoLibrary(filePath: String) {
        let normalizedPath = filePath.hasPrefix("file://")
            ? String(filePath.dropFirst("file://".count))
            : filePath
        let fileURL = URL(fileURLWithPath: normalizedPath)
        guard FileManager.default.fileExists(atPath: fileURL.path) else {
            return
        }

        let performSave = {
            PHPhotoLibrary.shared().performChanges({
                let creationRequest = PHAssetCreationRequest.forAsset()
                creationRequest.addResource(with: .photo, fileURL: fileURL, options: nil)
            }, completionHandler: nil)
        }

        let status = PHPhotoLibrary.authorizationStatus(for: .addOnly)
        switch status {
        case .authorized, .limited:
            performSave()
        case .notDetermined:
            PHPhotoLibrary.requestAuthorization(for: .addOnly) { newStatus in
                if newStatus == .authorized || newStatus == .limited {
                    performSave()
                }
            }
        default:
            break
        }
    }
}