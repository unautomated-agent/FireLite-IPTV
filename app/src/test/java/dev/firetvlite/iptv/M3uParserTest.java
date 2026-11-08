package dev.firetvlite.iptv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import org.junit.Test;

public class M3uParserTest {

    private static Playlist parse(String text) throws IOException {
        return M3uParser.parse(new StringReader(text));
    }

    @Test
    public void parsesChannelsAndGroups() throws Exception {
        Playlist p = parse("#EXTM3U\n"
                + "#EXTINF:-1 tvg-id=\"a\" group-title=\"News\",Channel A\n"
                + "http://h/a.m3u8\n"
                + "#EXTINF:-1 group-title=\"Sport\",Channel B\n"
                + "http://h/b.ts\n"
                + "#EXTINF:-1 group-title=\"News\",Channel C\n"
                + "http://h/c\n");
        assertEquals(3, p.all.size());
        assertEquals(2, p.groups.size());
        assertEquals(2, p.groups.get("News").size());
        assertEquals("Channel A", p.all.get(0).name);
        assertEquals("http://h/a.m3u8", p.all.get(0).url);
        assertEquals("Sport", p.all.get(1).group);
    }

    @Test
    public void commaInsideQuotesDoesNotSplitTheName() throws Exception {
        Playlist p = parse("#EXTINF:-1 tvg-name=\"A, B\" group-title=\"X, Y\",Name, with comma\nhttp://h/1\n");
        assertEquals(1, p.all.size());
        assertEquals("Name, with comma", p.all.get(0).name);
        assertEquals("X, Y", p.all.get(0).group);
    }

    @Test
    public void extgrpIsUsedWhenThereIsNoGroupTitle() throws Exception {
        Playlist p = parse("#EXTINF:-1,Eins\n#EXTGRP:Kino\nhttp://h/1\n");
        assertEquals("Kino", p.all.get(0).group);
    }

    @Test
    public void groupTitleBeatsExtgrp() throws Exception {
        Playlist p = parse("#EXTINF:-1 group-title=\"A\",Eins\n#EXTGRP:B\nhttp://h/1\n");
        assertEquals("A", p.all.get(0).group);
    }

    @Test
    public void plainUrlListWithoutExtinfUsesUrlAsName() throws Exception {
        Playlist p = parse("http://h/1\nhttp://h/2\n");
        assertEquals(2, p.all.size());
        assertEquals("http://h/1", p.all.get(0).name);
        assertEquals(M3uParser.NO_GROUP, p.all.get(0).group);
    }

    @Test
    public void handlesBomAndWindowsLineEndings() throws Exception {
        Playlist p = parse("\uFEFF#EXTM3U\r\n#EXTINF:-1,One\r\nhttp://h/1\r\n");
        assertEquals(1, p.all.size());
        assertEquals("One", p.all.get(0).name);
    }

    @Test
    public void ignoresOptionsAndNonUrlLines() throws Exception {
        Playlist p = parse("#EXTM3U\n#EXTVLCOPT:http-user-agent=x\n<html><a href=\"http://x\">no</a></html>\n");
        assertTrue(p.all.isEmpty());
    }

    @Test
    public void attributeNamesAreCaseInsensitive() throws Exception {
        Playlist p = parse("#EXTINF:-1 Group-Title=\"Kids\",Toon\nhttp://h/1\n");
        assertEquals("Kids", p.all.get(0).group);
    }

    @Test
    public void emptyNameFallsBackToTvgName() throws Exception {
        Playlist p = parse("#EXTINF:-1 tvg-name=\"Fallback\",\nhttp://h/x\n");
        assertEquals("Fallback", p.all.get(0).name);
    }

    @Test
    public void infoIsNotCarriedOverToTheNextChannel() throws Exception {
        Playlist p = parse("#EXTINF:-1 group-title=\"G\",Eins\nhttp://h/1\nhttp://h/2\n");
        assertEquals("G", p.all.get(0).group);
        assertEquals(M3uParser.NO_GROUP, p.all.get(1).group);
        assertEquals("http://h/2", p.all.get(1).name);
    }
}
