package dev.firetvlite.iptv;

import java.util.List;

/** Einfacher Speicher im Arbeitsspeicher, ueber den die beiden Bildschirme Daten austauschen. */
final class Store {
    /** Die geladene Playlist. */
    static volatile Playlist playlist;
    /** Die Senderliste, in der gerade umgeschaltet wird (Kategorie oder Favoriten). */
    static List<Channel> zapList;
    /** Position des laufenden Senders in zapList. */
    static int zapIndex;

    private Store() {
    }
}
