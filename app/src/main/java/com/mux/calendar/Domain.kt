package com.mux.calendar

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

// ---------------------------------------------------------------- formatting
private val F24: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
private val F12: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
private val FDATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH)
private val FSHORT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

fun fmtT(t: LocalTime): String = (if (Store.use24h) F24 else F12).format(t)
fun fmtT(z: ZonedDateTime): String = fmtT(z.toLocalTime())
fun fmtD(d: LocalDate): String = FDATE.format(d).uppercase()
fun fmtShort(d: LocalDate): String = FSHORT.format(d).uppercase()
fun dowShort(d: DayOfWeek): String = d.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).uppercase()
fun monthName(m: YearMonth): String = m.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH).uppercase() + " " + m.year

fun zoneOf(id: String): ZoneId = try { ZoneId.of(id) } catch (e: Exception) { ZoneId.systemDefault() }

fun zoneLabel(z: ZoneId, at: Instant = Instant.now()): String {
    val off = z.rules.getOffset(at).id.let { if (it == "Z") "+00:00" else it }
    return "${z.id}  (GMT$off)"
}

// ---------------------------------------------------------------- recurrence rules
enum class Freq { DAILY, WEEKLY, MONTHLY, YEARLY }

data class Rule(
    val freq: Freq,
    val interval: Int = 1,
    val byDay: List<Pair<Int, DayOfWeek>> = emptyList(), // (ordinal, weekday): 0 = every, 2 = second, -1 = last
    val byMonthDay: List<Int> = emptyList(),
    val byMonth: List<Int> = emptyList(),
    val count: Int? = null,
    val until: LocalDate? = null
)

private val DOW = mapOf(
    "MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY, "TH" to DayOfWeek.THURSDAY,
    "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY, "SU" to DayOfWeek.SUNDAY
)

fun dowCode(d: DayOfWeek): String = DOW.entries.first { it.value == d }.key

/** Parses the supported subset of RRULE (FREQ, INTERVAL, BYDAY, BYMONTHDAY, BYMONTH, COUNT, UNTIL). Returns null when invalid. */
fun parseRRule(text: String): Rule? {
    try {
        val body = text.trim().removePrefix("RRULE:").trim()
        if (body.isEmpty()) return null
        val m = body.split(';').filter { it.isNotBlank() }.associate {
            val parts = it.split('=', limit = 2)
            parts[0].trim().uppercase() to parts[1].trim()
        }
        val freq = Freq.valueOf(m["FREQ"]?.uppercase() ?: return null)
        val interval = (m["INTERVAL"]?.toInt() ?: 1).coerceAtLeast(1)
        val byDay = ArrayList<Pair<Int, DayOfWeek>>()
        m["BYDAY"]?.split(',')?.filter { it.isNotBlank() }?.forEach { tok ->
            val t = tok.trim().uppercase()
            val d = DOW[t.takeLast(2)] ?: return null
            val n = t.dropLast(2).ifEmpty { "0" }.toInt()
            byDay += n to d
        }
        val byMonthDay = m["BYMONTHDAY"]?.split(',')?.map { it.trim().toInt() } ?: emptyList()
        val byMonth = m["BYMONTH"]?.split(',')?.map { it.trim().toInt() } ?: emptyList()
        if (byMonthDay.any { it == 0 || it < -31 || it > 31 } || byMonth.any { it < 1 || it > 12 }) return null
        val count = m["COUNT"]?.toInt()
        val until = m["UNTIL"]?.let { LocalDate.parse(it.trim().take(8), DateTimeFormatter.BASIC_ISO_DATE) }
        return Rule(freq, interval, byDay, byMonthDay, byMonth, count, until)
    } catch (e: Exception) {
        return null
    }
}

fun describeRule(r: Rule): String {
    val unit = when (r.freq) { Freq.DAILY -> "day"; Freq.WEEKLY -> "week"; Freq.MONTHLY -> "month"; Freq.YEARLY -> "year" }
    val sb = StringBuilder(if (r.interval == 1) "Every $unit" else "Every ${r.interval} ${unit}s")
    if (r.byDay.isNotEmpty()) {
        sb.append(" on ").append(r.byDay.joinToString(", ") { (n, d) -> (if (n == 0) "" else "$n.") + dowCode(d) })
    }
    if (r.byMonthDay.isNotEmpty()) sb.append(" on day ").append(r.byMonthDay.joinToString(", "))
    r.count?.let { sb.append(", $it times") }
    r.until?.let { sb.append(", until ").append(it) }
    return sb.toString()
}

private fun weekStartMonday(d: LocalDate): LocalDate = d.minusDays((d.dayOfWeek.value - 1).toLong())

private fun periodStart(start: LocalDate, r: Rule, p: Long): LocalDate = when (r.freq) {
    Freq.DAILY -> start.plusDays(p * r.interval)
    Freq.WEEKLY -> weekStartMonday(start).plusWeeks(p * r.interval)
    Freq.MONTHLY -> YearMonth.from(start).plusMonths(p * r.interval).atDay(1)
    Freq.YEARLY -> LocalDate.of(start.year, 1, 1).plusYears(p * r.interval)
}

private fun monthDates(ym: YearMonth, start: LocalDate, r: Rule): List<LocalDate> {
    val out = ArrayList<LocalDate>()
    val len = ym.lengthOfMonth()
    if (r.byMonthDay.isNotEmpty()) {
        for (n in r.byMonthDay) {
            val d = if (n > 0) n else len + n + 1
            if (d in 1..len) out += ym.atDay(d)
        }
    } else if (r.byDay.isNotEmpty()) {
        for ((ord, dow) in r.byDay) {
            val all = (1..len).map { ym.atDay(it) }.filter { it.dayOfWeek == dow }
            if (ord == 0) out += all else {
                val i = if (ord > 0) ord - 1 else all.size + ord
                all.getOrNull(i)?.let { out += it }
            }
        }
    } else if (start.dayOfMonth <= len) {
        out += ym.atDay(start.dayOfMonth)
    }
    return out.distinct().sorted()
}

private fun candidates(start: LocalDate, r: Rule, p: Long): List<LocalDate> {
    val ps = periodStart(start, r, p)
    return when (r.freq) {
        Freq.DAILY -> listOf(ps)
        Freq.WEEKLY -> {
            val days = if (r.byDay.isEmpty()) listOf(start.dayOfWeek) else r.byDay.map { it.second }.distinct()
            days.map { ps.plusDays((it.value - 1).toLong()) }.sorted()
        }
        Freq.MONTHLY -> monthDates(YearMonth.from(ps), start, r)
        Freq.YEARLY -> {
            val months = if (r.byMonth.isEmpty()) listOf(start.monthValue) else r.byMonth.sorted()
            months.flatMap { monthDates(YearMonth.of(ps.year, it), start, r) }
        }
    }
}

/** All occurrence dates of the rule (starting at [start]) that fall between [from] and [to], both inclusive. */
fun expandDates(start: LocalDate, r: Rule, from: LocalDate, to: LocalDate): List<LocalDate> {
    val out = ArrayList<LocalDate>()
    val hardEnd = r.until?.let { if (it.isBefore(to)) it else to } ?: to
    val maxCount = r.count
    var counted = 0
    var p = 0L
    while (p < 100_000) {
        for (d in candidates(start, r, p)) {
            if (d.isBefore(start)) continue
            if (d.isAfter(hardEnd)) return out
            counted++
            if (maxCount != null && counted > maxCount) return out
            if (!d.isBefore(from)) out += d
        }
        p++
        if (periodStart(start, r, p).isAfter(hardEnd)) break
    }
    return out
}

// ---------------------------------------------------------------- occurrences
data class Occ(
    val ev: EventFull,
    val date: LocalDate,          // occurrence start date in the event's own zone (identifies it inside a series)
    val startZ: ZonedDateTime,    // in the display zone
    val endZ: ZonedDateTime,
    val allDay: Boolean
) {
    val key: String get() = "${ev.event.id}_$date"
    val firstDay: LocalDate get() = startZ.toLocalDate()
    val lastDay: LocalDate
        get() = when {
            allDay -> endZ.toLocalDate().minusDays(1)
            endZ.toLocalTime() == LocalTime.MIDNIGHT && endZ.isAfter(startZ) -> endZ.toLocalDate().minusDays(1)
            else -> endZ.toLocalDate()
        }
    val repeats: Boolean get() = ev.rule != null
}

/** Expands events (including repeating ones) into concrete occurrences that touch [from]..[to] in the display zone. */
fun occurrencesBetween(events: List<EventFull>, from: LocalDate, to: LocalDate, display: ZoneId, ownZone: Boolean): List<Occ> {
    val out = ArrayList<Occ>()
    for (ef in events) {
        val e = ef.event
        val ez = zoneOf(e.zoneId)
        val dz = if (ownZone) ez else display
        var baseDate: LocalDate
        var baseTime = LocalTime.MIDNIGHT
        var durMs = 0L
        val spanDays: Long
        if (e.allDay) {
            baseDate = Instant.ofEpochMilli(e.startUtc).atZone(ZoneOffset.UTC).toLocalDate()
            spanDays = ((e.endUtc - e.startUtc) / 86_400_000L).coerceAtLeast(1)
        } else {
            val z0 = Instant.ofEpochMilli(e.startUtc).atZone(ez)
            baseDate = z0.toLocalDate()
            baseTime = z0.toLocalTime()
            durMs = (e.endUtc - e.startUtc).coerceAtLeast(0)
            spanDays = durMs / 86_400_000L + 1
        }
        val rule = ef.rule?.let { parseRRule(it.rrule) }
        val skipped = ef.rule?.exdates?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        val dates = if (rule == null) listOf(baseDate) else expandDates(baseDate, rule, from.minusDays(spanDays + 2), to.plusDays(2))
        for (d in dates) {
            if (d.toString() in skipped) continue
            val sZ: ZonedDateTime
            val eZ: ZonedDateTime
            if (e.allDay) {
                sZ = d.atStartOfDay(dz)
                eZ = d.plusDays(spanDays).atStartOfDay(dz)
            } else {
                val s0 = ZonedDateTime.of(d, baseTime, ez)  // computed in the event's zone, so DST is handled
                sZ = s0.withZoneSameInstant(dz)
                eZ = s0.plus(Duration.ofMillis(durMs)).withZoneSameInstant(dz)
            }
            val o = Occ(ef, d, sZ, eZ, e.allDay)
            if (!o.firstDay.isAfter(to) && !o.lastDay.isBefore(from)) out += o
        }
    }
    return out.sortedWith(compareBy<Occ>({ !it.allDay }, { it.startZ.toInstant() }))
}

/** The occurrence closest to today (next one, otherwise the last one) - used by search. */
fun nearestOcc(ef: EventFull, today: LocalDate, display: ZoneId, ownZone: Boolean): Occ? {
    val all = occurrencesBetween(listOf(ef), today.minusYears(2), today.plusYears(2), display, ownZone)
    return all.firstOrNull { !it.lastDay.isBefore(today) } ?: all.lastOrNull()
}

fun timeText(o: Occ): String {
    if (o.allDay) return if (o.firstDay == o.lastDay) "ALL DAY" else "ALL DAY  ${fmtShort(o.firstDay)} - ${fmtShort(o.lastDay)}"
    return if (o.firstDay == o.lastDay) "${fmtT(o.startZ)} - ${fmtT(o.endZ)}"
    else "${fmtShort(o.firstDay)} ${fmtT(o.startZ)} - ${fmtShort(o.lastDay)} ${fmtT(o.endZ)}"
}
