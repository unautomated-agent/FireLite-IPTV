package dev.firetvlite.iptv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import dev.firetvlite.iptv.data.db.ProgrammeEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

fun clockTime(time: Long): String = SimpleDateFormat("HH:mm", Locale("pl", "PL")).format(Date(time))

fun programmeTimes(programme: ProgrammeEntity): String = "${clockTime(programme.start)}–${clockTime(programme.stop)}"

@Composable
fun wallClock() = produceState(System.currentTimeMillis()) {
    while (true) { value = System.currentTimeMillis(); delay(1_000) }
}
