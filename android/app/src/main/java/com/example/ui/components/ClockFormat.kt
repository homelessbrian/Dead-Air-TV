package com.example.ui.components

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Formats a wall-clock start time in 24h ("21:30") or 12h ("9:30 PM") style. */
fun formatClock(millis: Long, use24Hour: Boolean): String {
    if (millis <= 0L) return ""
    val pattern = if (use24Hour) "HH:mm" else "h:mm a"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))
}
