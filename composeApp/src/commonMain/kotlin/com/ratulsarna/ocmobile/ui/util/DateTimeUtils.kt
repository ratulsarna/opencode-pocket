package com.ratulsarna.ocmobile.ui.util

import kotlin.time.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

/**
 * Formats an Instant as a smart date/time string:
 * - Today: "2:30 pm"
 * - Yesterday: "Yesterday 2:30 pm"
 * - Older: "Dec 18, 2:30 pm"
 */
fun formatSmartDateTime(instant: Instant): String {
    val now = Clock.System.now()
    val today = now.toLocalDateTime(TimeZone.currentSystemDefault()).date
    val messageDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    val messageDate = messageDateTime.date

    val hour12 = when {
        messageDateTime.hour == 0 -> 12
        messageDateTime.hour > 12 -> messageDateTime.hour - 12
        else -> messageDateTime.hour
    }
    val amPm = if (messageDateTime.hour < 12) "am" else "pm"
    val timeStr = "$hour12:${messageDateTime.minute.toString().padStart(2, '0')} $amPm"

    return when (messageDate) {
        today -> timeStr
        today.minus(1, DateTimeUnit.DAY) -> "Yesterday $timeStr"
        else -> {
            val monthStr = messageDateTime.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
            "$monthStr ${messageDate.dayOfMonth}, $timeStr"
        }
    }
}

/**
 * Formats an Instant as an absolute date/time string.
 * Example: "Dec 18, 2025 at 14:30"
 */
fun formatDateTime(instant: Instant): String {
    val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    val monthStr = local.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
    return "$monthStr ${local.dayOfMonth}, ${local.year} at ${local.hour}:${local.minute.toString().padStart(2, '0')}"
}
