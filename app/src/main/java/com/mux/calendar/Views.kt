package com.mux.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.max

private val WHITE = Color(0xFFD8F3F7)
private val HOUR_H = 56.dp

fun Occ.color(): Color = Color(ev.category?.color ?: cMain.toArgb())
fun Occ.covers(d: LocalDate): Boolean = !d.isBefore(firstDay) && !d.isAfter(lastDay)

fun buildByDay(occs: List<Occ>): Map<LocalDate, List<Occ>> {
    val m = HashMap<LocalDate, MutableList<Occ>>()
    for (o in occs) {
        var d = o.firstDay
        var n = 0
        while (!d.isAfter(o.lastDay) && n < 62) {
            m.getOrPut(d) { ArrayList() }.add(o)
            d = d.plusDays(1)
            n++
        }
    }
    return m
}

@Composable
fun OccRow(o: Occ, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(4.dp).height(40.dp).background(o.color()))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            T(o.ev.event.title, 14f, WHITE, true)
            T(timeText(o), 11f, cSub, mod = Modifier.padding(top = 2.dp))
            if (o.ev.event.location.isNotBlank()) T(o.ev.event.location, 10f, cSub)
        }
        if (o.repeats) T("REPEAT", 9f, cSub)
    }
}

@Composable
private fun DayCell(d: LocalDate, inMonth: Boolean, selected: Boolean, today: Boolean, list: List<Occ>, mod: Modifier, onClick: () -> Unit) {
    Column(
        mod.background(if (selected) cDim.copy(alpha = 0.45f) else Color.Transparent)
            .border(0.5.dp, cDim.copy(alpha = 0.35f))
            .then(if (today) Modifier.border(1.5.dp, cMain) else Modifier)
            .clickable(onClick = onClick).padding(2.dp)
    ) {
        T("${d.dayOfMonth}", 11f, if (!inMonth) cDim else if (today) cMain else WHITE, today)
        list.take(3).forEach { o ->
            Row(Modifier.padding(top = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(2.dp).height(9.dp).background(o.color()))
                T(o.ev.event.title, 7f, if (inMonth) WHITE else cSub, mod = Modifier.padding(start = 2.dp))
            }
        }
        if (list.size > 3) T("+${list.size - 3}", 8f, cSub)
    }
}

@Composable
fun MonthView(anchor: LocalDate, occs: List<Occ>, onPick: (LocalDate) -> Unit, onOcc: (Occ) -> Unit, onAdd: (LocalDate) -> Unit) {
    val ym = YearMonth.from(anchor)
    val first = ym.atDay(1)
    val shift = ((first.dayOfWeek.value - Store.weekStart + 7) % 7).toLong()
    val gridStart = first.minusDays(shift)
    val byDay = remember(occs) { buildByDay(occs) }
    val today = LocalDate.now()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth()) {
            for (i in 0 until 7) {
                T(dowShort(DayOfWeek.of((Store.weekStart - 1 + i) % 7 + 1)), 9f, cSub, mod = Modifier.weight(1f).padding(vertical = 4.dp), align = TextAlign.Center)
            }
        }
        Column(Modifier.weight(0.56f).fillMaxWidth()) {
            for (r in 0 until 6) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    for (c in 0 until 7) {
                        val d = gridStart.plusDays((r * 7 + c).toLong())
                        DayCell(d, d.month == ym.month, d == anchor, d == today, byDay[d].orEmpty(), Modifier.weight(1f).fillMaxHeight()) { onPick(d) }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(Color(0xFF050D12)).padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            T("// ${fmtD(anchor)}", 11f, cSub, true, Modifier.weight(1f))
            T("[+ ADD]", 11f, cMain, mod = Modifier.clickable { onAdd(anchor) }.padding(6.dp))
        }
        val dayList = byDay[anchor].orEmpty()
        if (dayList.isEmpty()) T("NO EVENTS", 11f, cSub, mod = Modifier.padding(16.dp))
        LazyColumn(Modifier.weight(0.44f).fillMaxWidth()) {
            items(dayList, key = { it.key }) { o -> OccRow(o) { onOcc(o) } }
        }
    }
}

private class Placed(val o: Occ, val s: Int, val e: Int, var lane: Int = 0, var lanes: Int = 1)

@Composable
private fun DayColumn(
    d: LocalDate, occs: List<Occ>, isToday: Boolean, modifier: Modifier,
    onOcc: (Occ) -> Unit, onEmpty: (LocalDate, LocalTime) -> Unit
) {
    val placed = remember(occs, d) {
        val items = occs.filter { !it.allDay && it.covers(d) }.map { o ->
            val s = if (o.firstDay == d) o.startZ.toLocalTime().toSecondOfDay() / 60 else 0
            val eRaw = if (o.lastDay == d) {
                val m = o.endZ.toLocalTime().toSecondOfDay() / 60
                if (m == 0 && o.endZ.toLocalDate() != d) 1440 else m
            } else 1440
            Placed(o, s, max(eRaw, s + 25).coerceAtMost(1440))
        }.sortedBy { it.s }
        var cluster = ArrayList<Placed>()
        var clusterEnd = -1
        val laneEnds = ArrayList<Int>()
        fun flush() {
            val n = (cluster.maxOfOrNull { it.lane } ?: -1) + 1
            cluster.forEach { it.lanes = n }
            cluster = ArrayList()
        }
        for (p in items) {
            if (p.s >= clusterEnd) { flush(); laneEnds.clear() }
            var lane = laneEnds.indexOfFirst { it <= p.s }
            if (lane == -1) { lane = laneEnds.size; laneEnds.add(p.e) } else laneEnds[lane] = p.e
            p.lane = lane
            cluster.add(p)
            clusterEnd = max(clusterEnd, p.e)
        }
        flush()
        items
    }
    BoxWithConstraints(
        modifier.height(HOUR_H * 24).border(0.5.dp, cDim.copy(alpha = 0.3f))
            .pointerInput(d) {
                detectTapGestures { off ->
                    val mins = (off.y / HOUR_H.toPx() * 60).toInt() / 30 * 30
                    onEmpty(d, LocalTime.of((mins / 60).coerceIn(0, 23), mins % 60))
                }
            }
    ) {
        val w = maxWidth
        for (h in 1 until 24) {
            Box(Modifier.offset(y = HOUR_H * h).fillMaxWidth().height(0.5.dp).background(cDim.copy(alpha = 0.25f)))
        }
        placed.forEach { p ->
            Box(
                Modifier.offset(x = w * (p.lane.toFloat() / p.lanes), y = HOUR_H * (p.s / 60f))
                    .width(w / p.lanes - 1.dp).height(HOUR_H * ((p.e - p.s) / 60f))
                    .background(p.o.color().copy(alpha = 0.88f))
                    .clickable { onOcc(p.o) }.padding(2.dp)
            ) { T(p.o.ev.event.title, 9f, Color.Black, true, lines = 3) }
        }
        if (isToday) {
            val nowMin = LocalTime.now().toSecondOfDay() / 60f
            Box(Modifier.offset(y = HOUR_H * (nowMin / 60f)).fillMaxWidth().height(2.dp).background(Color(0xFFFF3D3D)))
        }
    }
}

/** Week view (7 days) and day view (1 day). */
@Composable
fun TimeGrid(
    days: List<LocalDate>, occs: List<Occ>, onPickDay: (LocalDate) -> Unit,
    onOcc: (Occ) -> Unit, onEmpty: (LocalDate, LocalTime) -> Unit
) {
    val today = LocalDate.now()
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    LaunchedEffect(Unit) { scroll.scrollTo(with(density) { (HOUR_H * 7).roundToPx() }) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(40.dp))
            days.forEach { d ->
                Column(Modifier.weight(1f).clickable { onPickDay(d) }.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    T(dowShort(d.dayOfWeek), 9f, cSub)
                    T("${d.dayOfMonth}", 14f, if (d == today) cMain else WHITE, d == today)
                }
            }
        }
        val allDay = occs.filter { it.allDay }
        if (allDay.isNotEmpty()) {
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.width(40.dp))
                days.forEach { d ->
                    Column(Modifier.weight(1f)) {
                        allDay.filter { it.covers(d) }.take(3).forEach { o ->
                            T(
                                o.ev.event.title, 8f, Color.Black, true,
                                Modifier.fillMaxWidth().padding(1.dp).background(o.color().copy(alpha = 0.85f)).clickable { onOcc(o) }.padding(2.dp)
                            )
                        }
                    }
                }
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) {
            Column(Modifier.width(40.dp)) {
                for (h in 0 until 24) {
                    Box(Modifier.height(HOUR_H), Alignment.TopCenter) { if (h > 0) T(fmtT(LocalTime.of(h, 0)), 8f, cSub) }
                }
            }
            days.forEach { d -> DayColumn(d, occs, d == today, Modifier.weight(1f), onOcc, onEmpty) }
        }
    }
}

@Composable
fun AgendaView(anchor: LocalDate, occs: List<Occ>, onOcc: (Occ) -> Unit) {
    val byDay = remember(occs) { buildByDay(occs) }
    val days = remember(byDay, anchor) {
        (0..120).map { anchor.plusDays(it.toLong()) }.filter { byDay[it]?.isNotEmpty() == true }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        if (days.isEmpty()) item { T("NO EVENTS IN THE NEXT 120 DAYS", 12f, cSub, mod = Modifier.padding(16.dp)) }
        days.forEach { d ->
            item(key = "h$d") { T(fmtD(d), 11f, cSub, true, Modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp)) }
            items(byDay[d].orEmpty(), key = { "${d}_${it.key}" }) { o -> OccRow(o) { onOcc(o) } }
        }
    }
}

/** Header (title, previous/next, view switch) + the selected view. */
@Composable
fun CalendarScreen(
    events: List<EventFull>, zone: ZoneId, mode: Int, onMode: (Int) -> Unit,
    anchor: LocalDate, onAnchor: (LocalDate) -> Unit,
    onOcc: (Occ) -> Unit, onNew: (LocalDate, LocalTime?) -> Unit
) {
    val first = YearMonth.from(anchor).atDay(1)
    val gridStart = first.minusDays(((first.dayOfWeek.value - Store.weekStart + 7) % 7).toLong())
    val weekStart = anchor.minusDays(((anchor.dayOfWeek.value - Store.weekStart + 7) % 7).toLong())
    val from = when (mode) { 0 -> gridStart; 1 -> weekStart; else -> anchor }
    val to = when (mode) { 0 -> gridStart.plusDays(41); 1 -> weekStart.plusDays(6); 2 -> anchor; else -> anchor.plusDays(120) }
    val occs = remember(events, from, to, zone, Store.ownZone) { occurrencesBetween(events, from, to, zone, Store.ownZone) }

    fun shift(n: Int): LocalDate = when (mode) {
        0 -> anchor.plusMonths(n.toLong())
        1 -> anchor.plusWeeks(n.toLong())
        2 -> anchor.plusDays(n.toLong())
        else -> anchor.plusDays(30L * n)
    }
    val title = when (mode) {
        0 -> monthName(YearMonth.from(anchor))
        1 -> "${fmtShort(weekStart)} - ${fmtShort(weekStart.plusDays(6))} ${weekStart.plusDays(6).year}"
        2 -> fmtD(anchor)
        else -> "AGENDA  FROM ${fmtShort(anchor)}"
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            T("<", 20f, cMain, true, Modifier.clickable { onAnchor(shift(-1)) }.padding(horizontal = 14.dp, vertical = 4.dp))
            T(title, 13f, cMain, true, Modifier.weight(1f), align = TextAlign.Center)
            T(">", 20f, cMain, true, Modifier.clickable { onAnchor(shift(1)) }.padding(horizontal = 14.dp, vertical = 4.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("MONTH", "WEEK", "DAY", "AGENDA").forEachIndexed { i, n -> Chip(n, mode == i, Modifier.weight(1f)) { onMode(i) } }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Chip("TODAY", false, Modifier.weight(1f)) { onAnchor(LocalDate.now()) }
            Chip("+ NEW EVENT", true, Modifier.weight(2f)) { onNew(anchor, null) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (mode) {
                0 -> MonthView(anchor, occs, onAnchor, onOcc) { onNew(it, null) }
                1 -> TimeGrid((0 until 7).map { weekStart.plusDays(it.toLong()) }, occs, { onAnchor(it); onMode(2) }, onOcc) { d, t -> onNew(d, t) }
                2 -> TimeGrid(listOf(anchor), occs, { onAnchor(it) }, onOcc) { d, t -> onNew(d, t) }
                else -> AgendaView(anchor, occs, onOcc)
            }
        }
    }
}
