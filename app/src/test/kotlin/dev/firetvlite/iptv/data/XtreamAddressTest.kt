package dev.firetvlite.iptv.data

import dev.firetvlite.iptv.data.playlist.XtreamAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XtreamAddressTest {
    @Test fun detectsBothLivePathShapesAndExtensionsWithoutOpeningThem() {
        assertEquals("http://host:8080/xmltv.php?username=u&password=p",
            XtreamAddress.stream("http://host:8080/live/u/p/42.m3u8")?.epgUrl())
        assertEquals("https://host/xmltv.php?username=u&password=p",
            XtreamAddress.stream("https://host/u/p/42")?.epgUrl())
        assertEquals("ts", XtreamAddress.stream("https://host/u/p/42.ts")?.extension)
        assertEquals("https://host/xmltv.php?username=a%2Bb&password=p%26q",
            XtreamAddress.stream("https://host/a+b/p%26q/42.ts")?.epgUrl())
    }

    @Test fun ignoresNonXtreamAndMalformedAddresses() {
        listOf("", "file:///u/p/1", "http://host/movie/u/p/1.ts", "http://host/one.m3u8", "http://host/a/b/channel")
            .forEach { assertNull(XtreamAddress.stream(it)) }
        assertNull(XtreamAddress.playlist("http://host/get.php?username=u"))
        assertNull(XtreamAddress.playlist("http://host/list.m3u?username=u&password=p"))
    }
}
