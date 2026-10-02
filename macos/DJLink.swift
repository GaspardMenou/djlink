import AppKit
import WebKit

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate, WKNavigationDelegate, WKUIDelegate {
    var window: NSWindow!
    var web: WKWebView!
    var engine: Process?
    var closing = false
    var engineLoaded = false
    let address = URL(string: "http://localhost:8080")! // Fixed local engine address.

    func applicationDidFinishLaunching(_ notification: Notification) {
        let menu = NSMenu()
        let appItem = NSMenuItem()
        let appMenu = NSMenu()
        appMenu.addItem(withTitle: "Quitter DJ Link", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        appItem.submenu = appMenu; menu.addItem(appItem)
        let editItem = NSMenuItem(title: "Édition", action: nil, keyEquivalent: "")
        let editMenu = NSMenu(title: "Édition")
        editMenu.addItem(withTitle: "Copier", action: #selector(NSText.copy(_:)), keyEquivalent: "c")
        editMenu.addItem(withTitle: "Coller", action: #selector(NSText.paste(_:)), keyEquivalent: "v")
        editMenu.addItem(withTitle: "Tout sélectionner", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a")
        editItem.submenu = editMenu; menu.addItem(editItem); NSApp.mainMenu = menu
        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1420, height: 940),
                          styleMask: [.titled, .closable, .miniaturizable, .resizable], backing: .buffered, defer: false)
        window.title = "DJ Link — XDJ-AZ"
        window.minSize = NSSize(width: 640, height: 480)
        web = WKWebView(); web.navigationDelegate = self; web.uiDelegate = self
        web.configuration.preferences.isElementFullscreenEnabled = true
        NSApp.applicationIconImage = NSImage(systemSymbolName: "waveform.path", accessibilityDescription: "DJ Link")
        window.contentView = web
        window.center(); window.makeKeyAndOrderFront(nil); NSApp.activate(ignoringOtherApps: true)
        web.loadHTMLString("<body style='background:#191626;color:#00cee3;font:24px system-ui;padding:40px'>DJ Link<br><p style='font-size:16px;color:#b3aabd'>Démarrage du moteur réseau…</p></body>", baseURL: nil)
        Task { await startEngine() }
    }

    func engineReady() async -> Bool {
        var request = URLRequest(url: address.appendingPathComponent("api/state"))
        request.timeoutInterval = 0.6
        guard let (data, response) = try? await URLSession.shared.data(for: request),
              (response as? HTTPURLResponse)?.statusCode == 200,
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let decks = json["decks"] as? [Any], decks.count == 4, json["settings"] != nil else { return false }
        return true
    }

    func startEngine() async {
        if await engineReady() { web.load(URLRequest(url: address)); return }
        guard let resources = Bundle.main.resourceURL else { fail("Ressources de l’application absentes."); return }
        guard let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else { fail("Dossier de données indisponible."); return }
        let logs = support.appendingPathComponent("DJLink/logs")
        let process = Process()
        process.executableURL = resources.appendingPathComponent("runtime/bin/java")
        process.arguments = ["-Ddjlink.dataDir=\(logs.path)", "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn", "-jar", resources.appendingPathComponent("djlink.jar").path]
        let log = FileManager.default.temporaryDirectory.appendingPathComponent("DJLink-engine.log")
        FileManager.default.createFile(atPath: log.path, contents: Data())
        do {
            let output = try FileHandle(forWritingTo: log)
            process.standardOutput = output; process.standardError = output
            process.terminationHandler = { [weak self] _ in
                Task { @MainActor in
                    guard let self, !self.closing, self.engineLoaded else { return }
                    self.engineLoaded = false
                    await self.startEngine()
                }
            }
            try process.run(); engine = process
            for _ in 0..<60 {
                if await engineReady() { engineLoaded = true; web.load(URLRequest(url: address)); return }
                if !process.isRunning { fail("Le moteur n’a pas démarré. Le port 8080 peut être occupé. Journal : \(log.path)"); return }
                try await Task.sleep(for: .milliseconds(250))
            }
            fail("Le moteur ne répond pas. Journal : \(log.path)")
        } catch { fail("Impossible de lancer le moteur : \(error.localizedDescription)") }
    }

    func fail(_ message: String) {
        let alert = NSAlert(); alert.messageText = "DJ Link"; alert.informativeText = message
        alert.runModal()
    }
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }
    func applicationWillTerminate(_ notification: Notification) { closing = true; if engine?.isRunning == true { engine?.terminate() } }
    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction) async -> WKNavigationActionPolicy {
        guard let url = navigationAction.request.url else { return .cancel }
        if url.scheme == "about" || (url.host == "localhost" && url.port == 8080) { return .allow }
        if ["http", "https"].contains(url.scheme) { NSWorkspace.shared.open(url) }
        return .cancel
    }
    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                 for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if let url = navigationAction.request.url, ["http", "https"].contains(url.scheme) { NSWorkspace.shared.open(url) }
        return nil
    }
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        guard (error as NSError).code != NSURLErrorCancelled else { return }
        Task { await startEngine() }
    }
}

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.setActivationPolicy(.regular)
app.run()
