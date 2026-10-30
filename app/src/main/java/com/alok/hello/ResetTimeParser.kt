package com.alok.hello

import java.util.Calendar
import java.util.TimeZone

/**
 * Pure JVM logic (no Android imports) so it can be unit-tested anywhere.
 * Understands things like:
 *   "Your limit will reset at 2:30 PM"   "resets 2:30pm"   "limit 14:30"
 *   "resets in 3 hours 20 minutes"       "in 45 min"       "2:30 baje"   "3 pm"
 * Returns the epoch millis of the reset moment, or null if no time found.
 */
object ResetTimeParser {

    private val relStart = Regex("""\bin\s+(\d+)\s*(?:hours?|hrs?|h|minutes?|mins?|m)\b""")
    private val relHours = Regex("""(\d+)\s*(?:hours?|hrs?|h)\b""")
    private val relMins = Regex("""(\d+)\s*(?:minutes?|mins?|m)\b""")
    private val hm = Regex("""\b(\d{1,2})[:.](\d{2})\s*(am|pm)?(?![a-z])""")
    private val hOnly = Regex("""\b(\d{1,2})\s*(am|pm)(?![a-z])""")
    private val baje = Regex("""\b(\d{1,2})\s*baje""")

    fun parse(
        raw: String,
        now: Long = System.currentTimeMillis(),
        tz: TimeZone = TimeZone.getDefault()
    ): Long? {
        var t = raw.lowercase()
            .replace(' ', ' ')
            .replace(' ', ' ')
            .replace("a.m.", "am")
            .replace("p.m.", "pm")
        val ri = t.indexOf("reset")
        if (ri >= 0) {
            t = t.substring(maxOf(0, ri - 40), minOf(t.length, ri + 120))
        }
        relative(t, now)?.let { return it }
        return absolute(t, now, tz)
    }

    private fun relative(t: String, now: Long): Long? {
        val m = relStart.find(t) ?: return null
        val tail = t.substring(m.range.first)
        var mins = 0L
        relHours.findAll(tail).forEach { mins += it.groupValues[1].toLong() * 60 }
        relMins.findAll(tail).forEach { mins += it.groupValues[1].toLong() }
        return if (mins > 0) now + mins * 60_000 else null
    }

    private fun absolute(t: String, now: Long, tz: TimeZone): Long? {
        val hour: Int
        val minute: Int
        val ap: String?
        val a = hm.find(t)
        if (a != null) {
            hour = a.groupValues[1].toInt()
            minute = a.groupValues[2].toInt()
            ap = a.groups[3]?.value
        } else {
            val b = hOnly.find(t)
            if (b != null) {
                hour = b.groupValues[1].toInt()
                minute = 0
                ap = b.groupValues[2]
            } else {
                val c = baje.find(t) ?: return null
                hour = c.groupValues[1].toInt()
                minute = 0
                ap = null
            }
        }
        if (hour > 23 || minute > 59) return null

        val candidates: List<Int> = when {
            ap == "am" -> listOf(hour % 12)
            ap == "pm" -> listOf(hour % 12 + 12)
            hour == 0 || hour >= 13 -> listOf(hour)
            else -> listOf(hour % 12, hour % 12 + 12)
        }

        var best: Long? = null
        for (hh in candidates) {
            val c = Calendar.getInstance(tz)
            c.timeInMillis = now
            c.set(Calendar.HOUR_OF_DAY, hh)
            c.set(Calendar.MINUTE, minute)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 1)
            val v = c.timeInMillis
            if (best == null || v < best) best = v
        }
        return best
    }
}
