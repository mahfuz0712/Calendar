package com.mux.calendar

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** App settings (small preferences, kept in SharedPreferences). */
object Store {
    private lateinit var sp: SharedPreferences
    var accent by mutableIntStateOf(0)
    var defaultView by mutableIntStateOf(0)      // 0 month, 1 week, 2 day, 3 agenda
    var weekStart by mutableIntStateOf(1)        // ISO day: 1 Monday, 7 Sunday, 6 Saturday
    var use24h by mutableStateOf(true)
    var defaultReminder by mutableIntStateOf(10) // minutes, -1 = none
    var ownZone by mutableStateOf(false)         // show every event in its own time zone

    fun init(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences("mux_calendar", Context.MODE_PRIVATE)
        accent = sp.getInt("accent", 0)
        defaultView = sp.getInt("view", 0)
        weekStart = sp.getInt("weekStart", 1)
        use24h = sp.getBoolean("use24h", true)
        defaultReminder = sp.getInt("reminder", 10)
        ownZone = sp.getBoolean("ownZone", false)
    }

    fun updateAccent(i: Int) { accent = i; sp.edit().putInt("accent", i).apply() }
    fun updateDefaultView(i: Int) { defaultView = i; sp.edit().putInt("view", i).apply() }
    fun updateWeekStart(i: Int) { weekStart = i; sp.edit().putInt("weekStart", i).apply() }
    fun updateUse24h(b: Boolean) { use24h = b; sp.edit().putBoolean("use24h", b).apply() }
    fun updateDefaultReminder(m: Int) { defaultReminder = m; sp.edit().putInt("reminder", m).apply() }
    fun updateOwnZone(b: Boolean) { ownZone = b; sp.edit().putBoolean("ownZone", b).apply() }
}

/** Opened from a notification tap. */
object Nav {
    var openEventId by mutableLongStateOf(-1L)
}

@HiltViewModel
class CalVm @Inject constructor(private val repo: EventRepository) : ViewModel() {
    val events: StateFlow<List<EventFull>> = repo.events.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val categories: StateFlow<List<CategoryEntity>> = repo.categories.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    var query by mutableStateOf("")

    @OptIn(ExperimentalCoroutinesApi::class)
    val results: StateFlow<List<EventFull>> = snapshotFlow { query }
        .flatMapLatest { q -> if (q.isBlank()) flowOf(emptyList()) else repo.search(q.trim()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun save(e: EventEntity, rule: RuleEntity?, reminders: List<Int>) { viewModelScope.launch { repo.save(e, rule, reminders) } }
    fun delete(id: Long) { viewModelScope.launch { repo.delete(id) } }
    fun skip(ef: EventFull, date: LocalDate) { viewModelScope.launch { repo.skipOccurrence(ef, date) } }
    fun addCategory(name: String, color: Int) { viewModelScope.launch { repo.addCategory(name, color) } }
    fun deleteCategory(id: Long) { viewModelScope.launch { repo.deleteCategory(id) } }
}
