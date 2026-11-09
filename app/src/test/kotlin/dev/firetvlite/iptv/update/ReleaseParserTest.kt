package dev.firetvlite.iptv.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseParserTest {

    private val apkUrl = "https://github.com/owner/repo/releases/download/build-12/firelite.apk"

    private fun releaseJson(tag: String, assets: String) =
        """{"tag_name":"$tag","assets":[$assets]}"""

    private val apkAsset =
        """{"name":"firelite.apk","browser_download_url":"$apkUrl","size":4242}"""

    @Test
    fun readsBuildNumberVersionCodeAndApk() {
        val result = ReleaseParser.parse(releaseJson("build-12", apkAsset), versionCodeOffset = 100)

        val release = (result as ReleaseParser.Result.Found).release
        assertEquals("build-12", release.tag)
        assertEquals(12, release.buildNumber)
        assertEquals(112L, release.versionCode)
        assertEquals(apkUrl, release.apkUrl)
        assertEquals(4242L, release.apkSizeBytes)
    }

    @Test
    fun picksTheApkAmongOtherAssets() {
        val other = """{"name":"notes.txt","browser_download_url":"https://example.com/notes.txt","size":1}"""
        val result = ReleaseParser.parse(releaseJson("build-3", "$other,$apkAsset"), 100)

        assertTrue(result is ReleaseParser.Result.Found)
    }

    @Test
    fun reportsAReleaseWithoutTheApk() {
        val other = """{"name":"notes.txt","browser_download_url":"https://example.com/notes.txt","size":1}"""

        assertEquals(ReleaseParser.Result.NoApk, ReleaseParser.parse(releaseJson("build-3", other), 100))
    }

    @Test
    fun reportsATagThatIsNotABuildNumber() {
        assertEquals(ReleaseParser.Result.UnknownTag, ReleaseParser.parse(releaseJson("v1.2.3", apkAsset), 100))
        assertEquals(ReleaseParser.Result.UnknownTag, ReleaseParser.parse(releaseJson("build-", apkAsset), 100))
    }

    @Test
    fun comparesVersionCodes() {
        val release = (ReleaseParser.parse(releaseJson("build-12", apkAsset), 100) as ReleaseParser.Result.Found).release

        assertTrue(ReleaseParser.isNewer(installedVersionCode = 111L, release = release))
        assertFalse(ReleaseParser.isNewer(installedVersionCode = 112L, release = release))
        assertFalse(ReleaseParser.isNewer(installedVersionCode = 130L, release = release))
    }

    @Test
    fun pendingReleaseOnlyExistsWhenANewerBuildIsKnown() {
        val release = (ReleaseParser.parse(releaseJson("build-12", apkAsset), 100) as ReleaseParser.Result.Found).release

        assertEquals(release, UpdateState.Available(release).pendingRelease)
        assertEquals(release, UpdateState.Failed(FailureReason.Download, release).pendingRelease)
        assertEquals(null, UpdateState.UpToDate.pendingRelease)
        assertEquals(null, UpdateState.Failed(FailureReason.Network).pendingRelease)
    }
}
