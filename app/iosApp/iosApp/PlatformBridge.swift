import AuthenticationServices
import CryptoKit
import Foundation
import QuickLook
import Shared
import UIKit
import UniformTypeIdentifiers
import VisionKit

final class PlatformBridge: NSObject, IosPlatformBridge, ASWebAuthenticationPresentationContextProviding {
    static let shared = PlatformBridge()

    private let keychainService = "com.ashish.allthedocs.vault"
    private let keychainAccount = "vault-key"
    private let maxPageDimension: CGFloat = 1600

    private var activeScanDelegate: ScanDelegate?
    private var activePickDelegate: PickDelegate?
    private var activePreview: PreviewSource?
    private var activeAuthSession: ASWebAuthenticationSession?

    func seal(data: Data) -> Data? {
        guard let key = vaultKey() else { return nil }
        return try? AES.GCM.seal(data, using: key).combined
    }

    func open(data: Data) -> Data? {
        guard let key = vaultKey(), let box = try? AES.GCM.SealedBox(combined: data) else { return nil }
        return try? AES.GCM.open(box, using: key)
    }

    func scanDocument(completion: @escaping (PickedDocument?, String?) -> Void) {
        DispatchQueue.main.async {
            guard VNDocumentCameraViewController.isSupported else {
                completion(nil, "Scanning isn't supported on this device. Use Choose file instead.")
                return
            }
            guard let presenter = Self.topViewController() else {
                completion(nil, "Couldn't open the scanner")
                return
            }
            let delegate = ScanDelegate { [weak self] scan, error in
                self?.activeScanDelegate = nil
                guard let self, let scan else {
                    completion(nil, error)
                    return
                }
                completion(PickedDocument(data: self.pdfData(from: scan), mimeType: "application/pdf", fileName: "Scan.pdf"), nil)
            }
            self.activeScanDelegate = delegate
            let scanner = VNDocumentCameraViewController()
            scanner.delegate = delegate
            presenter.present(scanner, animated: true)
        }
    }

    func pickDocument(completion: @escaping (PickedDocument?, String?) -> Void) {
        DispatchQueue.main.async {
            guard let presenter = Self.topViewController() else {
                completion(nil, "Couldn't open the file picker")
                return
            }
            let delegate = PickDelegate { [weak self] url in
                self?.activePickDelegate = nil
                guard let url else { return }
                completion(Self.readPicked(url: url), nil)
            }
            self.activePickDelegate = delegate
            let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.pdf, .jpeg, .png], asCopy: true)
            picker.delegate = delegate
            picker.allowsMultipleSelection = false
            presenter.present(picker, animated: true)
        }
    }

    func openDigiLocker(completion: @escaping (PickedDocument?, String?) -> Void) {
        DispatchQueue.main.async {
            guard let presenter = Self.topViewController() else {
                completion(nil, "Couldn't open DigiLocker")
                return
            }
            let navigation = UINavigationController(rootViewController: DigiLockerViewController(onFinish: completion))
            navigation.modalPresentationStyle = .fullScreen
            presenter.present(navigation, animated: true)
        }
    }

    func authenticate(url: String, callbackScheme: String, completion: @escaping (String?, String?) -> Void) {
        DispatchQueue.main.async {
            guard let authURL = URL(string: url) else {
                completion(nil, "Invalid sign-in URL")
                return
            }
            let session = ASWebAuthenticationSession(url: authURL, callbackURLScheme: callbackScheme) { [weak self] callbackURL, error in
                self?.activeAuthSession = nil
                if let callbackURL {
                    completion(callbackURL.absoluteString, nil)
                } else if let error = error as? ASWebAuthenticationSessionError, error.code == .canceledLogin {
                    completion(nil, "Sign-in was cancelled")
                } else {
                    completion(nil, error?.localizedDescription ?? "Sign-in failed")
                }
            }
            session.presentationContextProvider = self
            session.prefersEphemeralWebBrowserSession = false
            self.activeAuthSession = session
            if !session.start() {
                self.activeAuthSession = nil
                completion(nil, "Couldn't start sign-in")
            }
        }
    }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        return scenes.flatMap(\.windows).first(where: \.isKeyWindow) ?? ASPresentationAnchor()
    }

    func handleIncoming(url: URL) {
        let scoped = url.startAccessingSecurityScopedResource()
        defer {
            if scoped { url.stopAccessingSecurityScopedResource() }
            if url.path.contains("/Inbox/") { try? FileManager.default.removeItem(at: url) }
        }
        guard let data = try? Data(contentsOf: url) else { return }
        let mimeType = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
        let document = PickedDocument(data: data, mimeType: mimeType, fileName: url.lastPathComponent)
        if let error = MainViewControllerKt.deliverIncomingDocument(document: document) {
            Self.topViewController()?.present(Self.alert(error), animated: true)
        }
    }

    private static func alert(_ message: String) -> UIAlertController {
        let alert = UIAlertController(title: "Couldn't add the document", message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "OK", style: .default))
        return alert
    }

    func preview(data: Data, fileName: String) {
        DispatchQueue.main.async {
            let directory = FileManager.default.temporaryDirectory.appendingPathComponent("viewer", isDirectory: true)
            try? FileManager.default.removeItem(at: directory)
            try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let safeName = fileName.replacingOccurrences(of: "/", with: "_")
            let url = directory.appendingPathComponent(safeName.isEmpty ? "document" : safeName)
            guard (try? data.write(to: url, options: [.completeFileProtection])) != nil,
                  let presenter = Self.topViewController() else { return }
            let source = PreviewSource(url: url) { [weak self] in
                try? FileManager.default.removeItem(at: directory)
                self?.activePreview = nil
            }
            self.activePreview = source
            let controller = QLPreviewController()
            controller.dataSource = source
            controller.delegate = source
            presenter.present(controller, animated: true)
        }
    }

    private func vaultKey() -> SymmetricKey? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: keychainAccount,
            kSecReturnData as String: true,
        ]
        var item: CFTypeRef?
        if SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess, let data = item as? Data {
            return SymmetricKey(data: data)
        }
        let key = SymmetricKey(size: .bits256)
        let attributes: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: keychainAccount,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            kSecValueData as String: key.withUnsafeBytes { Data($0) },
        ]
        return SecItemAdd(attributes as CFDictionary, nil) == errSecSuccess ? key : nil
    }

    private func pdfData(from scan: VNDocumentCameraScan) -> Data {
        let renderer = UIGraphicsPDFRenderer(bounds: .zero)
        return renderer.pdfData { context in
            for index in 0..<scan.pageCount {
                let page = compressed(scan.imageOfPage(at: index))
                let bounds = CGRect(origin: .zero, size: page.size)
                context.beginPage(withBounds: bounds, pageInfo: [:])
                page.draw(in: bounds)
            }
        }
    }

    private func compressed(_ image: UIImage) -> UIImage {
        let scale = min(1, maxPageDimension / max(image.size.width, image.size.height))
        let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        let resized = UIGraphicsImageRenderer(size: size, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: size))
        }
        guard let jpeg = resized.jpegData(compressionQuality: 0.7), let reloaded = UIImage(data: jpeg) else { return resized }
        return reloaded
    }

    private static func readPicked(url: URL) -> PickedDocument? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url) else { return nil }
        let mimeType = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
        return PickedDocument(data: data, mimeType: mimeType, fileName: url.lastPathComponent)
    }

    private static func topViewController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let window = scenes.flatMap(\.windows).first(where: \.isKeyWindow) ?? scenes.first?.windows.first
        var top = window?.rootViewController
        while let presented = top?.presentedViewController {
            top = presented
        }
        return top
    }
}

private final class ScanDelegate: NSObject, VNDocumentCameraViewControllerDelegate {
    private let onFinish: (VNDocumentCameraScan?, String?) -> Void

    init(onFinish: @escaping (VNDocumentCameraScan?, String?) -> Void) {
        self.onFinish = onFinish
    }

    func documentCameraViewController(_ controller: VNDocumentCameraViewController, didFinishWith scan: VNDocumentCameraScan) {
        controller.dismiss(animated: true)
        onFinish(scan, nil)
    }

    func documentCameraViewControllerDidCancel(_ controller: VNDocumentCameraViewController) {
        controller.dismiss(animated: true)
        onFinish(nil, nil)
    }

    func documentCameraViewController(_ controller: VNDocumentCameraViewController, didFailWithError error: Error) {
        controller.dismiss(animated: true)
        onFinish(nil, error.localizedDescription)
    }
}

private final class PickDelegate: NSObject, UIDocumentPickerDelegate {
    private let onFinish: (URL?) -> Void

    init(onFinish: @escaping (URL?) -> Void) {
        self.onFinish = onFinish
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        onFinish(urls.first)
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        onFinish(nil)
    }
}

private final class PreviewSource: NSObject, QLPreviewControllerDataSource, QLPreviewControllerDelegate {
    private let url: URL
    private let onDismiss: () -> Void

    init(url: URL, onDismiss: @escaping () -> Void) {
        self.url = url
        self.onDismiss = onDismiss
    }

    func numberOfPreviewItems(in controller: QLPreviewController) -> Int { 1 }

    func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem {
        url as NSURL
    }

    func previewControllerDidDismiss(_ controller: QLPreviewController) {
        onDismiss()
    }
}
