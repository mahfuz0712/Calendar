package com.mux.calendar

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val WHITE = Color(0xFFD8F3F7)
private val RED = Color(0xFFFF6B6B)

fun reminderLabel(min: Int): String = when {
    min <= 0 -> "AT TIME"
    min < 60 -> "$min MIN"
    min < 1440 -> "${min / 60} H"
    else -> "${min / 1440} D"
}

fun pickDate(ctx: Context, d: LocalDate, onPick: (LocalDate) -> Unit) {
    DatePickerDialog(ctx, { _, y, m, day -> onPick(LocalDate.of(y, m + 1, day)) }, d.year, d.monthValue - 1, d.dayOfMonth).show()
}

fun pickTime(ctx: Context, t: LocalTime, onPick: (LocalTime) -> Unit) {
    TimePickerDialog(ctx, { _, h, m -> onPick(LocalTime.of(h, m)) }, t.hour, t.minute, Store.use24h).show()
}

// ---------------------------------------------------------------- event details
@Composable
fun EventDetail(o: Occ, onEdit: () -> Unit, onDelete: (all: Boolean) -> Unit, onClose: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    val e = o.ev.event
    val ez = zoneOf(e.zoneId)
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.fillMaxWidth().background(Color(0xFF050D12)).border(1.dp, cMain).padding(18.dp)
                .verticalScroll(rememberScrollState())
        ) {
            T("// ${o.ev.category?.name?.uppercase() ?: "EVENT"}", 11f, o.color(), true)
            T(e.title, 18f, WHITE, true, Modifier.padding(top = 4.dp), 3)
            T(fmtD(o.firstDay), 12f, cMain, mod = Modifier.padding(top = 8.dp))
            T(timeText(o), 13f, cMain, true)
            if (!o.allDay && o.startZ.zone != ez) {
                T(
                    "IN ${ez.id}: ${fmtT(o.startZ.withZoneSameInstant(ez))} - ${fmtT(o.endZ.withZoneSameInstant(ez))}",
                    10f, cSub, mod = Modifier.padding(top = 4.dp), lines = 2
                )
            }
            o.ev.rule?.let { r -> parseRRule(r.rrule)?.let { T("REPEATS: " + describeRule(it), 11f, cSub, mod = Modifier.padding(top = 8.dp), lines = 3) } }
            if (e.location.isNotBlank()) T("AT: ${e.location}", 12f, WHITE, mod = Modifier.padding(top = 8.dp), lines = 3)
            if (e.description.isNotBlank()) T(e.description, 12f, cSub, mod = Modifier.padding(top = 8.dp), lines = 12)
            if (o.ev.reminders.isNotEmpty()) {
                T("REMINDERS: " + o.ev.reminders.map { it.minutesBefore }.sorted().joinToString(", ") { reminderLabel(it) } + " BEFORE", 10f, cSub, mod = Modifier.padding(top = 8.dp), lines = 2)
            }
            Spacer(Modifier.height(14.dp))
            if (!confirm) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("EDIT", true, Modifier.weight(1f), onEdit)
                    Chip("DELETE", false, Modifier.weight(1f)) { confirm = true }
                    Chip("CLOSE", false, Modifier.weight(1f), onClose)
                }
            } else {
                T(if (o.repeats) "DELETE WHICH?" else "DELETE THIS EVENT?", 12f, RED, true, Modifier.padding(bottom = 8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (o.repeats) {
                        Chip("THIS ONE", false, Modifier.weight(1f)) { onDelete(false) }
                        Chip("ALL", true, Modifier.weight(1f)) { onDelete(true) }
                    } else {
                        Chip("YES, DELETE", true, Modifier.weight(1f)) { onDelete(true) }
                    }
                    Chip("CANCEL", false, Modifier.weight(1f)) { confirm = false }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- time zone picker
@Composable
fun ZonePicker(current: String, onPick: (String) -> Unit, onClose: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val all = remember { ZoneId.getAvailableZoneIds().filter { it.contains('/') || it == "UTC" }.sorted() }
    val shown = remember(q) { all.filter { q.isBlank() || it.contains(q.trim(), true) }.take(150) }
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).background(Color(0xFF050D12)).border(1.dp, cMain).padding(16.dp)) {
            T("// TIME ZONE", 12f, cSub)
            Box(Modifier.fillMaxWidth().padding(top = 10.dp).border(1.dp, cDim).padding(10.dp)) {
                if (q.isEmpty()) T("SEARCH CITY OR REGION...", 13f, cSub)
                BasicTextField(
                    q, { q = it }, singleLine = true,
                    textStyle = TextStyle(color = cMain, fontFamily = Mono, fontSize = 14.sp),
                    cursorBrush = SolidColor(cMain), modifier = Modifier.fillMaxWidth()
                )
            }
            Chip("USE DEVICE ZONE (${ZoneId.systemDefault().id})", false, Modifier.fillMaxWidth().padding(top = 8.dp)) { onPick(ZoneId.systemDefault().id) }
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(shown) { id ->
                    T(id, 12f, if (id == current) cMain else WHITE, id == current, Modifier.fillMaxWidth().clickable { onPick(id) }.padding(vertical = 9.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- editor
class EditorArgs(val ef: EventFull?, val date: LocalDate, val time: LocalTime?)

private fun initStart(a: EditorArgs): LocalDateTime {
    val ef = a.ef
    if (ef != null) {
        val e = ef.event
        return if (e.allDay) Instant.ofEpochMilli(e.startUtc).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay()
        else Instant.ofEpochMilli(e.startUtc).atZone(zoneOf(e.zoneId)).toLocalDateTime()
    }
    val t = a.time ?: LocalTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0)
    return a.date.atTime(t)
}

private fun initEnd(a: EditorArgs, start: LocalDateTime): LocalDateTime {
    val ef = a.ef
    if (ef != null) {
        val e = ef.event
        return if (e.allDay) Instant.ofEpochMilli(e.endUtc).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1).atStartOfDay()
        else Instant.ofEpochMilli(e.endUtc).atZone(zoneOf(e.zoneId)).toLocalDateTime()
    }
    return start.plusHours(1)
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, lines: Int = 1) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        T(label, 10f, cSub, true)
        Box(Modifier.fillMaxWidth().padding(top = 4.dp).border(1.dp, cDim).padding(10.dp)) {
            BasicTextField(
                value, onChange, singleLine = lines == 1, minLines = lines,
                textStyle = TextStyle(color = WHITE, fontFamily = Mono, fontSize = 14.sp),
                cursorBrush = SolidColor(cMain), modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun DateTimeRow(label: String, value: LocalDateTime, allDay: Boolean, onChange: (LocalDateTime) -> Unit) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        T(label, 10f, cSub, true, Modifier.width(52.dp))
        Chip(fmtD(value.toLocalDate()), false, Modifier.weight(1f)) {
            pickDate(ctx, value.toLocalDate()) { d -> onChange(d.atTime(value.toLocalTime())) }
        }
        if (!allDay) {
            Spacer(Modifier.width(6.dp))
            Chip(fmtT(value.toLocalTime()), false, Modifier.width(96.dp)) {
                pickTime(ctx, value.toLocalTime()) { t -> onChange(value.toLocalDate().atTime(t)) }
            }
        }
    }
}

@Composable
private fun Section(t: String) = T("// $t", 11f, cSub, true, Modifier.padding(top = 20.dp, bottom = 6.dp))

@Composable
fun EventEditor(
    args: EditorArgs, cats: List<CategoryEntity>,
    onSave: (EventEntity, RuleEntity?, List<Int>) -> Unit, onClose: () -> Unit
) {
    val ef = args.ef
    val startInit = remember { initStart(args) }
    var title by remember { mutableStateOf(ef?.event?.title ?: "") }
    var desc by remember { mutableStateOf(ef?.event?.description ?: "") }
    var loc by remember { mutableStateOf(ef?.event?.location ?: "") }
    var allDay by remember { mutableStateOf(ef?.event?.allDay ?: false) }
    var zoneId by remember { mutableStateOf(ef?.event?.zoneId ?: ZoneId.systemDefault().id) }
    var catId by remember { mutableStateOf(ef?.event?.categoryId) }
    var start by remember { mutableStateOf(startInit) }
    var end by remember { mutableStateOf(initEnd(args, startInit)) }
    var reminders by remember {
        mutableStateOf<Set<Int>>(
            ef?.reminders?.map { it.minutesBefore }?.toSet() ?: if (Store.defaultReminder >= 0) setOf(Store.defaultReminder) else emptySet()
        )
    }

    // repeat
    val parsed = remember { ef?.rule?.let { parseRRule(it.rrule) } }
    val simple = parsed != null && parsed.byMonthDay.isEmpty() && parsed.byMonth.isEmpty() &&
        (if (parsed.freq == Freq.WEEKLY) parsed.byDay.all { it.first == 0 } else parsed.byDay.isEmpty())
    var rKind by remember { mutableIntStateOf(if (ef?.rule == null) 0 else if (simple && parsed != null) parsed.freq.ordinal + 1 else 5) }
    var interval by remember { mutableIntStateOf(parsed?.interval ?: 1) }
    var weekDays by remember { mutableStateOf<Set<DayOfWeek>>(parsed?.byDay?.map { it.second }?.toSet() ?: setOf(startInit.dayOfWeek)) }
    var endMode by remember { mutableIntStateOf(if (parsed?.count != null) 1 else if (parsed?.until != null) 2 else 0) }
    var countN by remember { mutableIntStateOf(parsed?.count ?: 10) }
    var untilDate by remember { mutableStateOf(parsed?.until ?: startInit.toLocalDate().plusMonths(3)) }
    var customText by remember { mutableStateOf(ef?.rule?.rrule ?: "FREQ=MONTHLY;BYDAY=2TU") }
    var error by remember { mutableStateOf<String?>(null) }
    var zoneDialog by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    fun ruleText(): String? = when (rKind) {
        0 -> null
        5 -> customText.trim().removePrefix("RRULE:").ifEmpty { null }
        else -> buildString {
            append("FREQ=").append(listOf("", "DAILY", "WEEKLY", "MONTHLY", "YEARLY")[rKind])
            if (interval > 1) append(";INTERVAL=").append(interval)
            if (rKind == 2) {
                val ds = weekDays.ifEmpty { setOf(start.dayOfWeek) }
                append(";BYDAY=").append(ds.sortedBy { it.value }.joinToString(",") { dowCode(it) })
            }
            if (endMode == 1) append(";COUNT=").append(countN)
            if (endMode == 2) append(";UNTIL=").append(untilDate.format(DateTimeFormatter.BASIC_ISO_DATE))
        }
    }

    fun save() {
        if (title.isBlank()) { error = "TITLE IS REQUIRED"; return }
        val zone = zoneOf(zoneId)
        val sUtc: Long
        val eUtc: Long
        if (allDay) {
            val sd = start.toLocalDate()
            val ed = if (end.toLocalDate().isBefore(sd)) sd else end.toLocalDate()
            sUtc = sd.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            eUtc = ed.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        } else {
            if (end.isBefore(start)) { error = "END IS BEFORE START"; return }
            sUtc = start.atZone(zone).toInstant().toEpochMilli()
            eUtc = end.atZone(zone).toInstant().toEpochMilli()
        }
        val rt = ruleText()
        if (rt != null && parseRRule(rt) == null) { error = "INVALID REPEAT RULE"; return }
        val entity = EventEntity(
            id = ef?.event?.id ?: 0L, title = title.trim(), description = desc.trim(), location = loc.trim(),
            allDay = allDay, startUtc = sUtc, endUtc = eUtc, zoneId = zoneId, categoryId = catId
        )
        val rule = rt?.let { RuleEntity(eventId = 0L, rrule = it, exdates = ef?.rule?.exdates ?: "") }
        onSave(entity, rule, reminders.sorted())
    }

    if (zoneDialog) ZonePicker(zoneId, onPick = { zoneId = it; zoneDialog = false }, onClose = { zoneDialog = false })

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            T("[CANCEL]", 11f, cMain, mod = Modifier.clickable(onClick = onClose).padding(8.dp))
            T(if (ef == null) "NEW EVENT" else "EDIT EVENT", 14f, cMain, true, Modifier.weight(1f), align = androidx.compose.ui.text.style.TextAlign.Center)
            T("[SAVE]", 11f, cMain, true, Modifier.clickable { save() }.padding(8.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(cDim))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            error?.let { T(it, 12f, RED, true, Modifier.padding(top = 4.dp)) }
            Field("TITLE", title, { title = it })
            Field("LOCATION", loc, { loc = it })
            Field("DESCRIPTION", desc, { desc = it }, lines = 3)

            Section("WHEN")
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("ALL DAY", 11f, cSub, mod = Modifier.weight(1f))
                Chip("ON", allDay, Modifier.width(70.dp)) { allDay = true }
                Spacer(Modifier.width(6.dp))
                Chip("OFF", !allDay, Modifier.width(70.dp)) { allDay = false }
            }
            DateTimeRow("START", start, allDay) { n -> val d = Duration.between(start, end); start = n; end = n.plus(d) }
            DateTimeRow("END", end, allDay) { end = it }

            Section("TIME ZONE")
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(if (allDay) "ALL-DAY EVENTS STAY ON THE SAME DATE EVERYWHERE" else zoneLabel(zoneOf(zoneId)), 11f, WHITE, mod = Modifier.weight(1f), lines = 2)
                if (!allDay) Chip("CHANGE", false, Modifier.width(90.dp)) { zoneDialog = true }
            }
            val ez = zoneOf(zoneId)
            if (!allDay && ez != ZoneId.systemDefault() && !end.isBefore(start)) {
                val s = start.atZone(ez).withZoneSameInstant(ZoneId.systemDefault())
                val e2 = end.atZone(ez).withZoneSameInstant(ZoneId.systemDefault())
                T("= ${fmtT(s)} - ${fmtT(e2)} YOUR TIME (${ZoneId.systemDefault().id})", 10f, cSub, mod = Modifier.padding(top = 4.dp), lines = 2)
            }

            Section("CATEGORY")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("NONE", catId == null, Modifier.width(80.dp)) { catId = null }
                cats.forEach { c -> Chip(c.name.uppercase(), catId == c.id, Modifier.width(100.dp)) { catId = c.id } }
            }

            Section("REPEAT")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("NONE", "DAILY", "WEEKLY", "MONTHLY", "YEARLY", "CUSTOM").forEachIndexed { i, n ->
                    Chip(n, rKind == i, Modifier.width(88.dp)) { rKind = i }
                }
            }
            if (rKind in 1..4) {
                val unit = listOf("", "DAY", "WEEK", "MONTH", "YEAR")[rKind]
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    T("EVERY", 11f, cSub)
                    T("[-]", 14f, cMain, true, Modifier.clickable { if (interval > 1) interval-- }.padding(horizontal = 12.dp, vertical = 6.dp))
                    T("$interval", 14f, WHITE, true)
                    T("[+]", 14f, cMain, true, Modifier.clickable { if (interval < 99) interval++ }.padding(horizontal = 12.dp, vertical = 6.dp))
                    T(unit + if (interval > 1) "S" else "", 11f, cSub)
                }
                if (rKind == 2) {
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        DayOfWeek.values().forEach { d ->
                            val on = d in weekDays
                            Chip(dowShort(d).take(2), on, Modifier.weight(1f)) { weekDays = if (on) weekDays - d else weekDays + d }
                        }
                    }
                }
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("NEVER ENDS", "AFTER N", "UNTIL").forEachIndexed { i, n -> Chip(n, endMode == i, Modifier.weight(1f)) { endMode = i } }
                }
                if (endMode == 1) {
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        T("STOP AFTER", 11f, cSub)
                        T("[-]", 14f, cMain, true, Modifier.clickable { if (countN > 1) countN-- }.padding(horizontal = 12.dp, vertical = 6.dp))
                        T("$countN", 14f, WHITE, true)
                        T("[+]", 14f, cMain, true, Modifier.clickable { if (countN < 999) countN++ }.padding(horizontal = 12.dp, vertical = 6.dp))
                        T("TIMES", 11f, cSub)
                    }
                }
                if (endMode == 2) {
                    Chip("UNTIL  " + fmtD(untilDate), false, Modifier.fillMaxWidth().padding(top = 8.dp)) { pickDate(ctx, untilDate) { untilDate = it } }
                }
            }
            if (rKind == 5) {
                Field("RRULE  (FREQ, INTERVAL, BYDAY, BYMONTHDAY, BYMONTH, COUNT, UNTIL)", customText, { customText = it })
                val ok = parseRRule(customText) != null
                T(if (ok) "OK: " + describeRule(parseRRule(customText)!!) else "INVALID RULE. EXAMPLE: FREQ=MONTHLY;BYDAY=2TU", 10f, if (ok) cSub else RED, mod = Modifier.padding(top = 4.dp), lines = 3)
            }

            Section("REMINDERS")
            val opts = listOf(0, 5, 10, 15, 30, 60, 120, 1440, 2880)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                opts.forEach { m ->
                    val on = m in reminders
                    Chip(reminderLabel(m), on, Modifier.width(84.dp)) { reminders = if (on) reminders - m else reminders + m }
                }
            }
            if (allDay) T("ALL-DAY REMINDERS COUNT FROM 09:00 OF THE DAY", 10f, cSub, mod = Modifier.padding(top = 6.dp), lines = 2)
            Spacer(Modifier.height(24.dp))
        }
    }
}
