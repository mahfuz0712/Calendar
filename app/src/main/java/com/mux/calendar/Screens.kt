package com.mux.calendar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

private val WHITE = Color(0xFFD8F3F7)

@Composable
fun SearchScreen(vm: CalVm, zone: ZoneId, onOcc: (Occ) -> Unit) {
    val res by vm.results.collectAsState()
    val today = LocalDate.now()
    val occs = remember(res, zone, Store.ownZone) {
        val all = res.mapNotNull { nearestOcc(it, today, zone, Store.ownZone) }
        val (up, past) = all.partition { !it.lastDay.isBefore(today) }
        up.sortedBy { it.startZ.toInstant() } + past.sortedByDescending { it.startZ.toInstant() }
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 6.dp).border(1.dp, cDim).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            T("> ", 15f, cMain, true)
            Box(Modifier.weight(1f)) {
                if (vm.query.isEmpty()) T("SEARCH TITLE, PLACE, NOTES...", 14f, cSub)
                BasicTextField(
                    vm.query, { vm.query = it }, singleLine = true,
                    textStyle = TextStyle(color = cMain, fontFamily = Mono, fontSize = 15.sp),
                    cursorBrush = SolidColor(cMain), modifier = Modifier.fillMaxWidth()
                )
            }
            if (vm.query.isNotEmpty()) T("[X]", 13f, cMain, mod = Modifier.clickable { vm.query = "" })
        }
        T(
            if (vm.query.isBlank()) "// TYPE TO SEARCH PAST AND FUTURE EVENTS" else "// ${occs.size} RESULTS",
            11f, cSub, mod = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(occs, key = { it.key }) { o ->
                Column(Modifier.fillMaxWidth().clickable { onOcc(o) }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(4.dp).height(40.dp).background(o.color()))
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            T(o.ev.event.title, 14f, WHITE, true)
                            T(fmtD(o.firstDay) + "  " + timeText(o), 11f, cSub, mod = Modifier.padding(top = 2.dp), lines = 2)
                            if (o.ev.event.location.isNotBlank()) T(o.ev.event.location, 10f, cSub)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            if (o.lastDay.isBefore(today)) T("PAST", 9f, cSub)
                            if (o.repeats) T("REPEAT", 9f, cSub)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    cats: List<CategoryEntity>, onAddCategory: (String, Int) -> Unit, onDeleteCategory: (Long) -> Unit,
    onNotifPermission: () -> Unit, onUpdateFound: (UpdateInfo) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    val swatches = listOf(0xFF00E5FF, 0xFFFFB300, 0xFFB388FF, 0xFF00FF66, 0xFFFF4081, 0xFF40C4FF, 0xFFFF6E40, 0xFFFFEB3B)
    var swatch by remember { mutableIntStateOf(0) }
    @Composable fun section(t: String) = T("// $t", 12f, cSub, true, Modifier.padding(top = 22.dp, bottom = 8.dp))
    @Composable fun options(labels: List<String>, selected: Int, set: (Int) -> Unit) =
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            labels.forEachIndexed { i, l -> Chip(l, selected == i, Modifier.weight(1f)) { set(i) } }
        }
    val notifOk = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val exactOk = Alarms.canExact(ctx)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        T("SYSTEM CONFIG", 16f, cMain, true)

        section("INTERFACE COLOR")
        options(ACCENTS.map { it.name }, Store.accent) { Store.updateAccent(it) }

        section("DEFAULT VIEW")
        options(listOf("MONTH", "WEEK", "DAY", "AGENDA"), Store.defaultView) { Store.updateDefaultView(it) }

        section("WEEK STARTS ON")
        val ws = listOf(1, 7, 6)
        options(listOf("MON", "SUN", "SAT"), ws.indexOf(Store.weekStart).coerceAtLeast(0)) { Store.updateWeekStart(ws[it]) }

        section("TIME FORMAT")
        options(listOf("24 HOUR", "12 HOUR"), if (Store.use24h) 0 else 1) { Store.updateUse24h(it == 0) }

        section("TIME ZONE DISPLAY")
        options(listOf("MY DEVICE ZONE", "EACH EVENT'S ZONE"), if (Store.ownZone) 1 else 0) { Store.updateOwnZone(it == 1) }
        T("Now: ${ZoneId.systemDefault().id}. Events follow it automatically when you travel.", 10f, cSub, mod = Modifier.padding(top = 6.dp), lines = 3)

        section("DEFAULT REMINDER FOR NEW EVENTS")
        val rem = listOf(-1, 0, 10, 30, 60)
        options(listOf("NONE", "AT TIME", "10 MIN", "30 MIN", "1 H"), rem.indexOf(Store.defaultReminder).coerceAtLeast(0)) { Store.updateDefaultReminder(rem[it]) }

        section("NOTIFICATIONS")
        T("NOTIFICATIONS: " + if (notifOk) "ALLOWED" else "BLOCKED", 11f, if (notifOk) cMain else Color(0xFFFF6B6B))
        T("EXACT ALARMS: " + if (exactOk) "ALLOWED" else "NOT ALLOWED (REMINDERS MAY BE A BIT LATE)", 11f, if (exactOk) cMain else Color(0xFFFF6B6B), mod = Modifier.padding(top = 4.dp), lines = 2)
        if (!notifOk) Chip("ALLOW NOTIFICATIONS", true, Modifier.padding(top = 8.dp).fillMaxWidth()) { onNotifPermission() }
        if (!exactOk && Build.VERSION.SDK_INT >= 31) {
            Chip("ALLOW EXACT ALARMS", true, Modifier.padding(top = 8.dp).fillMaxWidth()) {
                try { ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))) } catch (e: Exception) { }
            }
        }

        section("CATEGORIES")
        cats.forEach { c ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).background(Color(c.color)))
                T(c.name, 13f, WHITE, mod = Modifier.weight(1f).padding(start = 10.dp))
                T("[DEL]", 11f, Color(0xFFFF6B6B), mod = Modifier.clickable { onDeleteCategory(c.id) }.padding(8.dp))
            }
        }
        Box(Modifier.fillMaxWidth().padding(top = 8.dp).border(1.dp, cDim).padding(10.dp)) {
            if (newName.isEmpty()) T("NEW CATEGORY NAME...", 13f, cSub)
            BasicTextField(
                newName, { newName = it }, singleLine = true,
                textStyle = TextStyle(color = cMain, fontFamily = Mono, fontSize = 14.sp),
                cursorBrush = SolidColor(cMain), modifier = Modifier.fillMaxWidth()
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            swatches.forEachIndexed { i, col ->
                Box(
                    Modifier.weight(1f).height(32.dp).background(Color(col.toInt()))
                        .then(if (swatch == i) Modifier.border(2.dp, Color.White) else Modifier)
                        .clickable { swatch = i }
                )
            }
        }
        Chip("ADD CATEGORY", false, Modifier.padding(top = 8.dp).fillMaxWidth()) {
            if (newName.isNotBlank()) { onAddCategory(newName.trim(), swatches[swatch].toInt()); newName = "" }
        }

        section("UPDATES")
        Chip("CHECK FOR UPDATES", false, Modifier.fillMaxWidth()) {
            scope.launch {
                status = "CHECKING..."
                if (!isOnline(ctx)) { status = "NO INTERNET CONNECTION"; return@launch }
                val (ok, info) = checkUpdate(ctx)
                status = when {
                    !ok -> "CHECK FAILED"
                    info == null -> "UP TO DATE"
                    else -> "NEW VERSION AVAILABLE: v${info.version}"
                }
                if (info != null) onUpdateFound(info)
            }
        }
        if (status.isNotEmpty()) T(status, 12f, cSub, mod = Modifier.padding(top = 8.dp), lines = 2)

        section("ABOUT")
        T("CALENDAR v${appVersion(ctx)}", 13f, cMain, true)
        T("CALENDAR CORE: MUX", 11f, cSub, mod = Modifier.padding(top = 4.dp))
        T("DATA: STORED ON THIS DEVICE ONLY", 11f, cSub, mod = Modifier.padding(top = 2.dp, bottom = 10.dp))
        T("DEV: ${AppInfo.DEV_NAME}", 12f, WHITE)
        T("MAIL: ${AppInfo.DEV_EMAIL}", 12f, cMain, mod = Modifier.clickable { openMail(ctx, AppInfo.DEV_EMAIL) }.padding(vertical = 6.dp))
        T("GITHUB: github.com/${AppInfo.GITHUB_USER}", 12f, cMain, mod = Modifier.clickable { openUrl(ctx, "https://github.com/${AppInfo.GITHUB_USER}") }.padding(vertical = 6.dp), lines = 2)

        section("MORE APPS")
        T("zApps Store >>", 13f, cMain, true, Modifier.clickable { openUrl(ctx, AppInfo.MORE_APPS) }.padding(vertical = 6.dp))
        T("zappsstore-frontend.onrender.com", 11f, cSub, lines = 2)
    }
}
