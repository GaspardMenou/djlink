package local.djlink;

import javax.swing.JOptionPane;
import java.net.URI;
import java.net.http.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.io.IOException;
import java.time.Duration;

/** Windows app-image entry point: native Java launcher + dedicated Edge app window. */
public final class WindowsLauncher {
    static boolean ready() {
        try {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:8080/api/state"))
                .timeout(Duration.ofMillis(600)).GET().build();
            var response = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(600)).build()
                .send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return false;
            var json = DjLink.JSON.fromJson(response.body(), com.google.gson.JsonObject.class);
            return json.has("settings") && json.getAsJsonArray("decks").size() == 4;
        } catch (Exception e) { return false; }
    }

    static Path edge() throws IOException {
        for (String variable : List.of("ProgramFiles(x86)", "ProgramFiles", "LOCALAPPDATA")) {
            String base = System.getenv(variable);
            if (base != null) {
                Path candidate = Path.of(base, "Microsoft", "Edge", "Application", "msedge.exe");
                if (Files.isRegularFile(candidate)) return candidate;
            }
        }
        throw new IOException("Microsoft Edge est nécessaire pour la fenêtre de DJ Link.");
    }

    public static void main(String[] args) {
        try {
            String base = System.getenv("LOCALAPPDATA");
            if (base == null) throw new IOException("Le lanceur Windows nécessite LOCALAPPDATA.");
            Path data = Path.of(base, "DJLink"); Files.createDirectories(data);
            try (FileChannel channel = FileChannel.open(data.resolve("app.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock lock = channel.tryLock()) {
                if (lock == null) { JOptionPane.showMessageDialog(null, "DJ Link est déjà ouvert."); return; }
                Path browser = edge();
                if (!ready()) {
                    System.setProperty("djlink.dataDir", data.resolve("logs").toString());
                    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
                    DjLink.main(args);
                    int attempts = 0;
                    while (!ready() && attempts++ < 40) Thread.sleep(250);
                    if (!ready()) throw new IOException("Le moteur ne répond pas sur le port 8080.");
                }
                Process window = new ProcessBuilder(browser.toString(), "--app=http://localhost:8080/",
                    "--user-data-dir=" + data.resolve("window"), "--no-first-run", "--disable-background-mode",
                    "--window-size=1420,940").redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                window.waitFor();
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, e.getMessage(), "DJ Link", JOptionPane.ERROR_MESSAGE);
        } finally { System.exit(0); } // Closes only this launcher's engine, MIDI and sockets through its shutdown hook.
    }
}
