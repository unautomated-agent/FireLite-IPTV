package dev.firetvlite.iptv.ui.components

import android.content.res.Configuration
import androidx.annotation.PluralsRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * The UI resources are Polish even when the device's system language is not.
 */
@Composable
@ReadOnlyComposable
fun pluralString(@PluralsRes id: Int, count: Int): String {
    val context = LocalContext.current
    val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale("pl", "PL")) }
    return context.createConfigurationContext(configuration).resources.getQuantityString(id, count, count)
}
