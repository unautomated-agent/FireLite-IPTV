package dev.firetvlite.iptv;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Laedt eine Playlist aus dem Internet in eine Datei und liest sie von dort. Laeuft nie im UI-Thread. */
public final class PlaylistLoader {
    private static final long MAX_BYTES = 200L * 1024 * 1024;
    private static final int MAX_REDIRECTS = 6;

    private PlaylistLoader() {
    }

    /** Speichert die Antwort erst in eine .tmp-Datei; die alte Datei bleibt bei Fehlern unveraendert. */
    public static void download(String urlText, String userAgent, File target) throws IOException {
        String current = urlText;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", userAgent);
            try {
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = conn.getHeaderField("Location");
                    if (location == null) {
                        throw new IOException("Weiterleitung ohne Ziel (HTTP " + code + ")");
                    }
                    current = new URL(new URL(current), location).toString();
                    continue;
                }
                if (code != 200) {
                    throw new IOException("Server antwortet mit HTTP " + code);
                }
                File tmp = new File(target.getPath() + ".tmp");
                long total = 0;
                try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                    byte[] buffer = new byte[32 * 1024];
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        total += n;
                        if (total > MAX_BYTES) {
                            throw new IOException("Playlist ist groesser als 200 MB");
                        }
                        out.write(buffer, 0, n);
                    }
                }
                if (target.exists() && !target.delete()) {
                    throw new IOException("Alte Playlist konnte nicht ersetzt werden");
                }
                if (!tmp.renameTo(target)) {
                    throw new IOException("Playlist konnte nicht gespeichert werden");
                }
                return;
            } finally {
                conn.disconnect();
            }
        }
        throw new IOException("Zu viele Weiterleitungen");
    }

    public static Playlist parseFile(File file) throws IOException {
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            return M3uParser.parse(reader);
        }
    }
}
