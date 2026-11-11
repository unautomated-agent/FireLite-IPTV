package dev.firetvlite.iptv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import dev.firetvlite.iptv.data.db.ProgrammeEntity
import kotlinx.coroutines.delay

fun programmeTimes(programme: ProgrammeEntity): String = "${clockTime(programme.start)}–${clockTime(programme.stop)}"

@Composable
fun wallClock() = produceState(System.currentTimeMillis()) {
    while (true) { value = System.currentTimeMillis(); delay(1_000) }
}
