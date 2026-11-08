package dev.firetvlite.iptv;

import java.util.ArrayList;
import java.util.LinkedHashMap;

/** Alle Sender, einmal als Gesamtliste und einmal nach Gruppen sortiert (Reihenfolge wie in der Datei). */
public final class Playlist {
    public final ArrayList<Channel> all = new ArrayList<>();
    public final LinkedHashMap<String, ArrayList<Channel>> groups = new LinkedHashMap<>();

    void add(Channel channel) {
        all.add(channel);
        ArrayList<Channel> inGroup = groups.get(channel.group);
        if (inGroup == null) {
            inGroup = new ArrayList<>();
            groups.put(channel.group, inGroup);
        }
        inGroup.add(channel);
    }
}
