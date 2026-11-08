package dev.firetvlite.iptv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** Gespeicherte Einstellungen: Playlist-URL, User-Agent und Favoriten. */
final class Prefs {
    static final String DEFAULT_USER_AGENT = "FireLite/1.0";

    private static final String FILE = "firelite";
    private static final String KEY_URL = "url";
    private static final String KEY_UA = "ua";
    private static final String KEY_FAVORITES = "favorites";

    /** Gemeinsamer Zwischenspeicher, damit beide Bildschirme dieselben Favoriten sehen. */
    private static Set<String> favoriteCache;

    private final SharedPreferences prefs;

    Prefs(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    String url() {
        return prefs.getString(KEY_URL, "");
    }

    String userAgent() {
        String ua = prefs.getString(KEY_UA, "");
        return ua.isEmpty() ? DEFAULT_USER_AGENT : ua;
    }

    void save(String url, String userAgent) {
        prefs.edit().putString(KEY_URL, url).putString(KEY_UA, userAgent).apply();
    }

    boolean isFavorite(String channelUrl) {
        return favorites().contains(channelUrl);
    }

    /** Schaltet den Favoriten um und gibt zurueck, ob der Sender jetzt ein Favorit ist. */
    boolean toggleFavorite(String channelUrl) {
        Set<String> favorites = favorites();
        boolean nowFavorite;
        if (favorites.contains(channelUrl)) {
            favorites.remove(channelUrl);
            nowFavorite = false;
        } else {
            favorites.add(channelUrl);
            nowFavorite = true;
        }
        // Android verlangt eine neue Kopie, sonst wird die Aenderung nicht zuverlaessig gespeichert.
        prefs.edit().putStringSet(KEY_FAVORITES, new HashSet<>(favorites)).apply();
        return nowFavorite;
    }

    private Set<String> favorites() {
        if (favoriteCache == null) {
            favoriteCache = new HashSet<>(prefs.getStringSet(KEY_FAVORITES, new HashSet<String>()));
        }
        return favoriteCache;
    }
}
