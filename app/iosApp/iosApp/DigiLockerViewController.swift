import Foundation
import Shared
import UIKit
import UniformTypeIdentifiers
import WebKit

final class DigiLockerViewController: UIViewController, WKNavigationDelegate, WKUIDelegate, WKDownloadDelegate {
    static let startURL = URL(string: "https://www.digilocker.gov.in/")!
    private static let trustedHostSuffixes = ["digilocker.gov.in", "digitallocker.gov.in", "meripehchaan.gov.in"]

    private let onFinish: (PickedDocument?, String?) -> Void
    private let statusLabel = UILabel()
    private let progressView = UIProgressView(progressViewStyle: .bar)
    private var webView: WKWebView!
    private var progressObservation: NSKeyValueObservation?
    private var pendingDownload: (url: URL, mimeType: String?, fileName: String)?
    private var finished = false

    init(onFinish: @escaping (PickedDocument?, String?) -> Void) {
        self.onFinish = onFinish
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "DigiLocker"
        view.backgroundColor = .systemBackground
        navigationItem.leftBarButtonItem = UIBarButtonItem(systemItem: .close, primaryAction: UIAction { [weak self] _ in
            self?.finish(nil, nil)
        })
        navigationItem.rightBarButtonItem = UIBarButtonItem(title: "Sign out", primaryAction: UIAction { [weak self] _ in
            self?.signOut()
        })

        statusLabel.numberOfLines = 0
        statusLabel.font = .preferredFont(forTextStyle: .footnote)
        statusLabel.text = "Sign in to DigiLocker yourself, open a document and tap Download. It comes straight into AllTheDocs; your login details never reach the app."
        let banner = UIView()
        banner.backgroundColor = .secondarySystemBackground
        banner.addSubview(statusLabel)

        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .default()
        webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = self
        webView.uiDelegate = self
        progressObservation = webView.observe(\.estimatedProgress, options: [.new]) { [weak self] webView, _ in
            self?.progressView.progress = Float(webView.estimatedProgress)
            self?.progressView.isHidden = webView.estimatedProgress >= 1
        }

        let stack = UIStackView(arrangedSubviews: [banner, progressView, webView])
        stack.axis = .vertical
        [stack, statusLabel].forEach { $0.translatesAutoresizingMaskIntoConstraints = false }
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            stack.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            stack.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            statusLabel.topAnchor.constraint(equalTo: banner.topAnchor, constant: 8),
            statusLabel.bottomAnchor.constraint(equalTo: banner.bottomAnchor, constant: -8),
            statusLabel.leadingAnchor.constraint(equalTo: banner.leadingAnchor, constant: 16),
            statusLabel.trailingAnchor.constraint(equalTo: banner.trailingAnchor, constant: -16),
        ])
        webView.load(URLRequest(url: Self.startURL))
    }

    func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationAction: WKNavigationAction,
        decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
    ) {
        guard navigationAction.request.url?.scheme == "https" else {
            decisionHandler(.cancel)
            return
        }
        decisionHandler(navigationAction.shouldPerformDownload ? .download : .allow)
    }

    func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationResponse: WKNavigationResponse,
        decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void
    ) {
        let response = navigationResponse.response
        let mimeType = response.mimeType?.lowercased() ?? ""
        let isAttachment = (response as? HTTPURLResponse)?
            .value(forHTTPHeaderField: "Content-Disposition")?
            .lowercased()
            .hasPrefix("attachment") ?? false
        let isDocument = ["application/pdf", "image/jpeg", "image/png"].contains(mimeType)
        decisionHandler(isAttachment || isDocument || !navigationResponse.canShowMIMEType ? .download : .allow)
    }

    func webView(_ webView: WKWebView, navigationAction: WKNavigationAction, didBecome download: WKDownload) {
        download.delegate = self
    }

    func webView(_ webView: WKWebView, navigationResponse: WKNavigationResponse, didBecome download: WKDownload) {
        download.delegate = self
    }

    func webView(
        _ webView: WKWebView,
        createWebViewWith configuration: WKWebViewConfiguration,
        for navigationAction: WKNavigationAction,
        windowFeatures: WKWindowFeatures
    ) -> WKWebView? {
        if navigationAction.targetFrame == nil {
            webView.load(navigationAction.request)
        }
        return nil
    }

    func download(
        _ download: WKDownload,
        decideDestinationUsing response: URLResponse,
        suggestedFilename: String,
        completionHandler: @escaping (URL?) -> Void
    ) {
        guard Self.isTrusted(webView.url) else {
            setStatus("Downloads are only imported from DigiLocker pages.")
            completionHandler(nil)
            return
        }
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("digilocker", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let destination = directory.appendingPathComponent(UUID().uuidString)
        pendingDownload = (destination, response.mimeType, suggestedFilename)
        setStatus("Importing \(suggestedFilename)…")
        completionHandler(destination)
    }

    func downloadDidFinish(_ download: WKDownload) {
        guard let pending = pendingDownload else { return }
        pendingDownload = nil
        defer { try? FileManager.default.removeItem(at: pending.url) }
        guard let data = try? Data(contentsOf: pending.url) else {
            setStatus("Couldn't read the download.")
            return
        }
        let mimeType = pending.mimeType
            ?? UTType(filenameExtension: (pending.fileName as NSString).pathExtension)?.preferredMIMEType
            ?? "application/octet-stream"
        finish(PickedDocument(data: data, mimeType: mimeType, fileName: pending.fileName), nil)
    }

    func download(_ download: WKDownload, didFailWithError error: Error, resumeData: Data?) {
        if let pending = pendingDownload {
            try? FileManager.default.removeItem(at: pending.url)
        }
        pendingDownload = nil
        setStatus("Download failed: \(error.localizedDescription)")
    }

    private func signOut() {
        let store = WKWebsiteDataStore.default()
        store.removeData(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(), modifiedSince: .distantPast) { [weak self] in
            self?.setStatus("Signed out of DigiLocker.")
            self?.webView.load(URLRequest(url: Self.startURL))
        }
    }

    private func setStatus(_ text: String) {
        statusLabel.text = text
    }

    private func finish(_ document: PickedDocument?, _ error: String?) {
        guard !finished else { return }
        finished = true
        dismiss(animated: true) { [onFinish] in
            onFinish(document, error)
        }
    }

    private static func isTrusted(_ url: URL?) -> Bool {
        guard let url, url.scheme == "https", let host = url.host?.lowercased() else { return false }
        return trustedHostSuffixes.contains { host == $0 || host.hasSuffix("." + $0) }
    }
}
