package dev.firetvlite.iptv.ui.screens

import java.io.File
import java.nio.ByteBuffer
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class LauncherArtworkTest {
    private val android = "http://schemas.android.com/apk/res/android"
    private fun document(path: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(File(path))

    @Test fun applicationAndActivityProvideBothRasterBannerAndTvAwareIcon() {
        val manifest = document("src/main/AndroidManifest.xml")
        assertEquals("@drawable/launcher_icon", (manifest.getElementsByTagName("application").item(0) as org.w3c.dom.Element)
            .getAttributeNS(android, "roundIcon"))
        for (tag in listOf("application", "activity")) {
            val element = manifest.getElementsByTagName(tag).item(0) as org.w3c.dom.Element
            assertEquals("@drawable/banner", element.getAttributeNS(android, "banner"))
            assertEquals("@drawable/launcher_icon", element.getAttributeNS(android, "icon"))
        }
        assertEquals("@drawable/fire_tv_launcher", document("src/main/res/values-television/launcher.xml")
            .getElementsByTagName("drawable").item(0).textContent)
        assertEquals("@mipmap/ic_launcher", document("src/main/res/values/launcher.xml")
            .getElementsByTagName("drawable").item(0).textContent)
        assertFalse(File("src/main/res/drawable/banner.xml").exists())
    }

    @Test fun packagedRasterSizesAspectRatioAndRgbFormatAreCorrect() {
        for ((directory, width) in listOf("drawable" to 160, "drawable-xhdpi" to 320,
            "drawable-xxhdpi" to 480, "drawable-xxxhdpi" to 640, "drawable-nodpi" to 1280)) {
            val name = if (directory == "drawable-nodpi") "fire_tv_launcher" else "banner"
            val png = File("src/main/res/$directory/$name.png").readBytes()
            assertArrayEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10), png.copyOf(8))
            val header = ByteBuffer.wrap(png)
            assertEquals(13, header.getInt(8))
            assertEquals(0x49484452, header.getInt(12))
            assertEquals(width, header.getInt(16))
            assertEquals(width * 9 / 16, header.getInt(20))
            assertEquals(8, png[24].toInt())
            assertEquals(2, png[25].toInt())
        }
    }
}
