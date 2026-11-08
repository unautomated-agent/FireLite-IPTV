package dev.firetvlite.iptv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;

/**
 * Liest M3U/M3U8-Playlists. Verstanden werden #EXTINF (mit group-title und tvg-name) und #EXTGRP.
 * Alles andere mit '#' wird ueberlesen. Gelesen wird Zeile fuer Zeile, damit auch sehr grosse
 * Listen wenig Speicher brauchen.
 */
public final class M3uParser {
    public static final String NO_GROUP = "Ohne Gruppe";

    private M3uParser() {
    }

    public static Playlist parse(Reader source) throws IOException {
        BufferedReader reader = (source instanceof BufferedReader)
                ? (BufferedReader) source
                : new BufferedReader(source, 1 << 16);
        Playlist playlist = new Playlist();
        HashMap<String, String> pool = new HashMap<>();

        String name = null;   // Name aus dem letzten #EXTINF
        String group = null;  // Gruppe aus dem letzten #EXTINF oder #EXTGRP
        boolean firstLine = true;
        String line;
        while ((line = reader.readLine()) != null) {
            if (firstLine) {
                firstLine = false;
                if (!line.isEmpty() && line.charAt(0) == '\uFEFF') {
                    line = line.substring(1);
                }
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }

            if (line.charAt(0) == '#') {
                if (line.regionMatches(true, 0, "#EXTINF", 0, 7)) {
                    int colon = line.indexOf(':');
                    String rest = colon >= 0 ? line.substring(colon + 1) : "";
                    int comma = firstCommaOutsideQuotes(rest);
                    String attrs = comma >= 0 ? rest.substring(0, comma) : rest;
                    name = comma >= 0 ? rest.substring(comma + 1).trim() : "";
                    if (name.isEmpty()) {
                        String tvgName = attribute(attrs, "tvg-name");
                        if (tvgName != null) {
                            name = tvgName;
                        }
                    }
                    String g = attribute(attrs, "group-title");
                    group = (g != null && !g.isEmpty()) ? g : null;
                } else if (line.regionMatches(true, 0, "#EXTGRP:", 0, 8)) {
                    String g = line.substring(8).trim();
                    if (group == null && !g.isEmpty()) {
                        group = g;
                    }
                }
                continue;
            }

            // Keine Kommentarzeile: das ist die Stream-Adresse des zuletzt gelesenen Senders.
            if (looksLikeUrl(line)) {
                String channelName = (name == null || name.isEmpty()) ? line : name;
                String channelGroup = intern(pool, group == null ? NO_GROUP : group);
                playlist.add(new Channel(channelName, channelGroup, line));
            }
            name = null;
            group = null;
        }
        return playlist;
    }

    /** Erstes Komma, das nicht innerhalb von Anfuehrungszeichen steht, sonst -1. */
    static int firstCommaOutsideQuotes(String s) {
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (c == ',' && !quoted) {
                return i;
            }
        }
        return -1;
    }

    /** Wert von key="..." (Gross-/Kleinschreibung egal) oder null. */
    static String attribute(String attrs, String key) {
        String needle = key + "=\"";
        int max = attrs.length() - needle.length();
        for (int i = 0; i <= max; i++) {
            boolean atBoundary = i == 0 || Character.isWhitespace(attrs.charAt(i - 1));
            if (atBoundary && attrs.regionMatches(true, i, needle, 0, needle.length())) {
                int start = i + needle.length();
                int end = attrs.indexOf('"', start);
                if (end < 0) {
                    return null;
                }
                return attrs.substring(start, end).trim();
            }
        }
        return null;
    }

    /** true bei "scheme://..." am Zeilenanfang, z. B. http://, https://, udp://. */
    static boolean looksLikeUrl(String line) {
        int sep = line.indexOf("://");
        if (sep <= 0) {
            return false;
        }
        for (int i = 0; i < sep; i++) {
            char c = line.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static String intern(HashMap<String, String> pool, String s) {
        String existing = pool.get(s);
        if (existing != null) {
            return existing;
        }
        pool.put(s, s);
        return s;
    }
}
