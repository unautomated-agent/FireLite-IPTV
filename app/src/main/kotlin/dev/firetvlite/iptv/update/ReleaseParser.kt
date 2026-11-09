package dev.firetvlite.iptv.update

import org.json.JSONObject

/** Reads the answer of GitHub's "latest release" endpoint. Pure logic, covered by unit tests. */
object ReleaseParser {
    const val APK_NAME = "firelite.apk"

    private val buildTag = Regex("^build-(\\d+)$")

    sealed interface Result {
        data class Found(val release: ReleaseInfo) : Result

        /** The release exists but has no [APK_NAME] attached. */
        data object NoApk : Result

        /** The tag is not of the form `build-<N>`, so the version cannot be compared. */
        data object UnknownTag : Result
    }

    /** @throws org.json.JSONException if [json] is not valid JSON. */
    fun parse(json: String, versionCodeOffset: Int): Result {
        val root = JSONObject(json)
        val tag = root.optString("tag_name")
        val buildNumber = buildTag.matchEntire(tag)?.groupValues?.get(1)?.toIntOrNull()
            ?: return Result.UnknownTag

        val assets = root.optJSONArray("assets") ?: return Result.NoApk
        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            val url = asset.optString("browser_download_url")
            if (asset.optString("name") == APK_NAME && url.isNotEmpty()) {
                return Result.Found(
                    ReleaseInfo(
                        tag = tag,
                        buildNumber = buildNumber,
                        versionCode = versionCodeOffset.toLong() + buildNumber,
                        apkUrl = url,
                        apkSizeBytes = asset.optLong("size", -1L),
                    ),
                )
            }
        }
        return Result.NoApk
    }

    fun isNewer(installedVersionCode: Long, release: ReleaseInfo): Boolean =
        release.versionCode > installedVersionCode
}
