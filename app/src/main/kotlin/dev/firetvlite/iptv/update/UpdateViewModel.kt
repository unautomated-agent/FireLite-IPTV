package dev.firetvlite.iptv.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.firetvlite.iptv.BuildConfig
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException

/**
 * Checks GitHub for a newer build when the app starts, and downloads and installs it on request.
 * It lives as long as the activity, so a running download survives screen changes.
 */
class UpdateViewModel(application: Application) : AndroidViewModel(application) {

    private val app: Application = application

    private val repository: UpdateRepository? = BuildConfig.UPDATE_REPOSITORY
        .takeIf { it.isNotBlank() }
        ?.let { UpdateRepository(it, BuildConfig.VERSION_CODE_OFFSET) }

    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()

    val installed: InstalledVersion = readInstalledVersion()

    private val updateFile = File(app.cacheDir, "updates/firelite.apk")

    init {
        // A finished update leaves its APK behind; it is no longer needed once this build runs.
        viewModelScope.launch(Dispatchers.IO) { updateFile.parentFile?.deleteRecursively() }
        checkForUpdate()
    }

    fun checkForUpdate() {
        val repo = repository
        if (repo == null) {
            mutableState.value = UpdateState.Failed(FailureReason.NotConfigured)
            return
        }
        when (mutableState.value) {
            is UpdateState.Checking, is UpdateState.Downloading -> return
            else -> Unit
        }

        mutableState.value = UpdateState.Checking
        viewModelScope.launch {
            val next: UpdateState = withContext(Dispatchers.IO) {
                try {
                    when (val result = repo.fetchLatest()) {
                        is ReleaseParser.Result.Found ->
                            if (ReleaseParser.isNewer(installed.versionCode, result.release)) {
                                UpdateState.Available(result.release)
                            } else {
                                UpdateState.UpToDate
                            }
                        ReleaseParser.Result.NoApk -> UpdateState.Failed(FailureReason.NoApk)
                        ReleaseParser.Result.UnknownTag -> UpdateState.Failed(FailureReason.UnknownTag)
                    }
                } catch (e: IOException) {
                    UpdateState.Failed(FailureReason.Network)
                } catch (e: JSONException) {
                    UpdateState.Failed(FailureReason.Network)
                }
            }
            mutableState.value = next
        }
    }

    /** Downloads the pending update and opens the system installer. Does nothing if there is none. */
    fun downloadAndInstall() {
        val repo = repository ?: return
        val release = mutableState.value.pendingRelease ?: return
        if (mutableState.value is UpdateState.Downloading) return

        if (!ApkInstaller.canInstall(app)) {
            // One-time permission. Jump straight to the right settings page if the device has one.
            ApkInstaller.openPermissionSettings(app)
            mutableState.value = UpdateState.Failed(FailureReason.PermissionNeeded, release)
            return
        }

        mutableState.value = UpdateState.Downloading(release, null)
        viewModelScope.launch {
            val downloaded: Boolean = withContext(Dispatchers.IO) {
                try {
                    repo.download(release, updateFile) { progress ->
                        mutableState.value = UpdateState.Downloading(release, progress)
                    }
                    true
                } catch (e: IOException) {
                    false
                }
            }
            mutableState.value = when {
                !downloaded -> UpdateState.Failed(FailureReason.Download, release)
                // The installer is open now. If the user cancels, the update simply stays available.
                ApkInstaller.install(app, updateFile) -> UpdateState.Available(release)
                else -> UpdateState.Failed(FailureReason.InstallerUnavailable, release)
            }
        }
    }

    fun openInstallSettings() {
        ApkInstaller.openPermissionSettings(app)
    }

    private fun readInstalledVersion(): InstalledVersion {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        return InstalledVersion(
            versionName = info.versionName ?: "?",
            versionCode = info.longVersionCode,
        )
    }
}
