package dev.firetvlite.iptv.ui.components

import androidx.annotation.PluralsRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext

/**
 * Locale-correct plural text. Polish has four forms (1 kanał, 2 kanały, 5 kanałów, 1,5 kanału),
 * which Android resolves from the `<plurals>` resource.
 */
@Composable
@ReadOnlyComposable
fun pluralString(@PluralsRes id: Int, count: Int): String =
    LocalContext.current.resources.getQuantityString(id, count, count)
