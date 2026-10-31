package com.mux.calendar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AColor
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dagger.hilt.android.AndroidEntryPoint
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT)
        )
        Nav.openEventId = intent?.getLongExtra("eventId", -1L) ?: -1L
        setContent { App() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Nav.openEventId = intent.getLongExtra("eventId", -1L)
    }
}

enum class Tab(val label: String) { CAL("CALENDAR"), SEARCH("SEARCH"), SETTINGS("SYS") }

@Composable
fun App(vm: CalVm = hiltViewModel()) {
    val ctx = LocalContext.current
    val events by vm.events.collectAsState()
    val cats by vm.categories.collectAsState()
    var tab by remember { mutableStateOf(Tab.CAL) }
    var mode by remember { mutableIntStateOf(Store.defaultView) }
    var anchor by remember { mutableStateOf(LocalDate.now()) }
    var zoneTick by remember { mutableIntStateOf(0) }
    val zone = remember(zoneTick) { ZoneId.systemDefault() }
    var detail by remember { mutableStateOf<Occ?>(null) }
    var editor by remember { mutableStateOf<EditorArgs?>(null) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun askNotif() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(Unit) { askNotif() }
    LaunchedEffect(Unit) { if (isOnline(ctx)) update = checkUpdate(ctx).second }

    // a changed time zone / date is picked up when the app comes back to the front
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) zoneTick++ }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }

    // opened from a reminder notification
    LaunchedEffect(Nav.openEventId, events) {
        val id = Nav.openEventId
        if (id > 0 && events.isNotEmpty()) {
            events.firstOrNull { it.event.id == id }?.let { ef ->
                nearestOcc(ef, LocalDate.now(), zone, Store.ownZone)?.let { detail = it; anchor = it.firstDay; tab = Tab.CAL }
            }
            Nav.openEventId = -1L
        }
    }
    BackHandler(editor != null) { editor = null }

    MaterialTheme(colorScheme = darkColorScheme()) {
        update?.let { u ->
            UpdateDialog(u, appVersion(ctx), onUpdate = { openUrl(ctx, u.apkUrl); update = null }, onCancel = { update = null })
        }
        detail?.let { o ->
            EventDetail(
                o,
                onEdit = { detail = null; editor = EditorArgs(o.ev, o.date, null) },
                onDelete = { all -> if (all) vm.delete(o.ev.event.id) else vm.skip(o.ev, o.date); detail = null },
                onClose = { detail = null }
            )
        }
        HudBackground {
            val ed = editor
            if (ed != null) {
                EventEditor(
                    ed, cats,
                    onSave = { e, r, rem ->
                        vm.save(e, r, rem)
                        anchor = if (e.allDay) Instant.ofEpochMilli(e.startUtc).atZone(ZoneOffset.UTC).toLocalDate()
                        else Instant.ofEpochMilli(e.startUtc).atZone(zone).toLocalDate()
                        editor = null
                        tab = Tab.CAL
                    },
                    onClose = { editor = null }
                )
            } else {
                Column(Modifier.fillMaxSize().systemBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            T("CALENDAR", 20f, cMain, true)
                            T("// MUX CALENDAR CORE", 11f, cSub)
                        }
                        T("[ ${events.size} ]", 11f, cSub)
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(cDim))
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (tab) {
                            Tab.CAL -> CalendarScreen(
                                events, zone, mode, { mode = it }, anchor, { anchor = it },
                                onOcc = { detail = it },
                                onNew = { d, t -> editor = EditorArgs(null, d, t) }
                            )
                            Tab.SEARCH -> SearchScreen(vm, zone) { o -> anchor = o.firstDay; detail = o }
                            Tab.SETTINGS -> SettingsScreen(
                                cats, { n, c -> vm.addCategory(n, c) }, { vm.deleteCategory(it) },
                                onNotifPermission = { askNotif() }, onUpdateFound = { update = it }
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth().background(Color(0xFF050D12))) {
                        for (s in Tab.entries) {
                            Column(
                                Modifier.weight(1f).clickable { tab = s }.padding(vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                T(s.label, 12f, if (tab == s) cMain else cSub, tab == s)
                                Box(Modifier.padding(top = 4.dp).width(24.dp).height(2.dp).background(if (tab == s) cMain else Color.Transparent))
                            }
                        }
                    }
                }
            }
        }
    }
}
