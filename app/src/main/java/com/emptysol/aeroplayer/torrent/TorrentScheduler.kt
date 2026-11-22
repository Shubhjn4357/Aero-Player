package com.emptysol.aeroplayer.torrent

import org.json.JSONArray
import java.util.Calendar

enum class ScheduleDirective {
    DOWNLOAD_AND_SEED,
    SEED_ONLY,
    PAUSE_ALL
}

/**
 * Evaluates day-of-week & time-window rules for scheduled downloading, seeding, or pausing.
 * Days follow ISO convention: 1 = Monday .. 7 = Sunday.
 */
object TorrentScheduler {

    fun parseDaysJson(daysJson: String): Set<Int> {
        return try {
            val arr = JSONArray(daysJson)
            val set = linkedSetOf<Int>()
            for (i in 0 until arr.length()) {
                val d = arr.optInt(i, -1)
                if (d in 1..7) set.add(d)
            }
            set
        } catch (_: Exception) {
            (1..7).toSet()
        }
    }

    fun evaluate(
        schedulerEnabled: Boolean,
        daysJson: String,
        startMinuteOfDay: Int,
        endMinuteOfDay: Int,
        activeAction: String,
        calendar: Calendar = Calendar.getInstance()
    ): ScheduleDirective {
        if (!schedulerEnabled) return ScheduleDirective.DOWNLOAD_AND_SEED

        val allowedDays = parseDaysJson(daysJson)
        if (allowedDays.isEmpty()) return ScheduleDirective.PAUSE_ALL

        val calDay = calendar.get(Calendar.DAY_OF_WEEK)
        // Convert Calendar.DAY_OF_WEEK (Sun=1..Sat=7) -> ISO (Mon=1..Sun=7)
        val isoDay = when (calDay) {
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            Calendar.SATURDAY -> 6
            Calendar.SUNDAY -> 7
            else -> 1
        }
        val minuteOfDay = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        val start = startMinuteOfDay.coerceIn(0, 1439)
        val end = endMinuteOfDay.coerceIn(0, 1439)

        val inDay = isoDay in allowedDays
        val inTimeWindow = if (start <= end) {
            minuteOfDay in start..end
        } else {
            // Wrap-around window (e.g. 22:00 -> 06:00)
            minuteOfDay >= start || minuteOfDay <= end
        }

        if (!inDay || !inTimeWindow) {
            return ScheduleDirective.PAUSE_ALL
        }

        return when (activeAction.uppercase()) {
            "SEED_ONLY" -> ScheduleDirective.SEED_ONLY
            "PAUSE_ALL" -> ScheduleDirective.PAUSE_ALL
            else -> ScheduleDirective.DOWNLOAD_AND_SEED
        }
    }

    fun formatMinute(minute: Int): String {
        val m = minute.coerceIn(0, 1439)
        val h = m / 60
        val min = m % 60
        return "%02d:%02d".format(h, min)
    }
}
