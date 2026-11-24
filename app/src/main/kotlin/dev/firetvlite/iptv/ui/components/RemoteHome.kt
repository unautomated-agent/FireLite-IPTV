package dev.firetvlite.iptv.ui.components

import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import dev.firetvlite.iptv.FireLiteApplication
import dev.firetvlite.iptv.playback.RemoteAlias

/** The learned Home button: leaves to the launcher, which closes the stream like Home does. */
internal object RemoteHome {
    fun open(context: Context) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun handle(context: Context, event: KeyEvent): Boolean {
        val remote = (context.applicationContext as FireLiteApplication).container.loadedRemoteKeys() ?: return false
        if (remote.learning.value != null || remote.keys.value.alias(event.keyCode) != RemoteAlias.Home) return false
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) open(context)
        return true
    }
}

/** A dialog is its own window and gets keys before the activity, so it has to watch for the Home button itself. */
@Composable
internal fun Modifier.remoteHomeKey(): Modifier {
    val context = LocalContext.current
    return onPreviewKeyEvent { RemoteHome.handle(context, it.nativeKeyEvent) }
}
