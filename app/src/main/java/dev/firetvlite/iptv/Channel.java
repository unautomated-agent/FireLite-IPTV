package dev.firetvlite.iptv;

/** Ein Sender aus der Playlist. Unveraenderlich. */
public final class Channel {
    public final String name;
    public final String group;
    public final String url;

    public Channel(String name, String group, String url) {
        this.name = name;
        this.group = group;
        this.url = url;
    }
}
