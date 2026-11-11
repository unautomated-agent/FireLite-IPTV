package dev.firetvlite.iptv.ui.components

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val warsawTimeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.forLanguageTag("pl-PL"))
    .withZone(ZoneId.of("Europe/Warsaw"))

fun clockTime(time: Long): String = warsawTimeFormat.format(Instant.ofEpochMilli(time))

internal fun millisUntilNextMinute(time: Long): Long = 60_000L - Math.floorMod(time, 60_000L)
