package com.mux.calendar

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: Int
)

/**
 * Timed events: startUtc / endUtc are real instants, zoneId is the time zone the event belongs to.
 * All-day events: startUtc is the first day at 00:00 UTC, endUtc is the day after the last day at 00:00 UTC
 * (all-day events are "floating": they stay on the same date wherever you are).
 */
@Entity(
    tableName = "events",
    foreignKeys = [ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("categoryId"), Index("startUtc")]
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String = "",
    val location: String = "",
    val allDay: Boolean = false,
    val startUtc: Long,
    val endUtc: Long,
    val zoneId: String,
    val categoryId: Long? = null
)

/** RFC 5545 style rule, e.g. FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE;COUNT=10. exdates = skipped occurrence dates (yyyy-MM-dd, comma separated). */
@Entity(
    tableName = "recurrence_rules",
    foreignKeys = [ForeignKey(entity = EventEntity::class, parentColumns = ["id"], childColumns = ["eventId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["eventId"], unique = true)]
)
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: Long,
    val rrule: String,
    val exdates: String = ""
)

@Entity(
    tableName = "reminders",
    foreignKeys = [ForeignKey(entity = EventEntity::class, parentColumns = ["id"], childColumns = ["eventId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("eventId")]
)
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: Long,
    val minutesBefore: Int
)

data class EventFull(
    @Embedded val event: EventEntity,
    @Relation(parentColumn = "id", entityColumn = "eventId") val rule: RuleEntity?,
    @Relation(parentColumn = "id", entityColumn = "eventId") val reminders: List<ReminderEntity>,
    @Relation(parentColumn = "categoryId", entityColumn = "id") val category: CategoryEntity?
)

@Dao
abstract class EventDao {
    @Transaction @Query("SELECT * FROM events ORDER BY startUtc")
    abstract fun observeAll(): Flow<List<EventFull>>

    @Transaction @Query("SELECT * FROM events")
    abstract suspend fun getAll(): List<EventFull>

    @Transaction @Query("SELECT * FROM events WHERE id = :id")
    abstract suspend fun get(id: Long): EventFull?

    @Transaction
    @Query("SELECT * FROM events WHERE title LIKE '%' || :q || '%' OR description LIKE '%' || :q || '%' OR location LIKE '%' || :q || '%' ORDER BY startUtc DESC")
    abstract fun search(q: String): Flow<List<EventFull>>

    @Insert abstract suspend fun insert(e: EventEntity): Long
    @Update abstract suspend fun update(e: EventEntity)
    @Query("DELETE FROM events WHERE id = :id") abstract suspend fun delete(id: Long)

    @Insert abstract suspend fun insertRule(r: RuleEntity)
    @Query("DELETE FROM recurrence_rules WHERE eventId = :id") abstract suspend fun deleteRule(id: Long)
    @Query("UPDATE recurrence_rules SET exdates = :ex WHERE eventId = :id") abstract suspend fun setExdates(id: Long, ex: String)

    @Insert abstract suspend fun insertReminders(list: List<ReminderEntity>)
    @Query("DELETE FROM reminders WHERE eventId = :id") abstract suspend fun deleteReminders(id: Long)

    @Transaction
    open suspend fun saveFull(e: EventEntity, rule: RuleEntity?, reminders: List<Int>): Long {
        val id = if (e.id == 0L) insert(e) else { update(e); e.id }
        deleteRule(id)
        if (rule != null) insertRule(rule.copy(id = 0, eventId = id))
        deleteReminders(id)
        if (reminders.isNotEmpty()) insertReminders(reminders.distinct().map { ReminderEntity(eventId = id, minutesBefore = it) })
        return id
    }
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY id") fun observeAll(): Flow<List<CategoryEntity>>
    @Insert suspend fun insert(c: CategoryEntity): Long
    @Query("DELETE FROM categories WHERE id = :id") suspend fun delete(id: Long)
}

@Database(
    entities = [CategoryEntity::class, EventEntity::class, RuleEntity::class, ReminderEntity::class],
    version = 1, exportSchema = false
)
abstract class AppDb : RoomDatabase() {
    abstract fun events(): EventDao
    abstract fun categories(): CategoryDao
}

@Module
@InstallIn(SingletonComponent::class)
object DbModule {
    @Provides @Singleton
    fun db(@ApplicationContext c: Context): AppDb =
        Room.databaseBuilder(c, AppDb::class.java, "mux_calendar.db")
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    listOf(
                        "Personal" to 0xFF00E5FF, "Work" to 0xFFFFB300, "Meeting" to 0xFFB388FF,
                        "Health" to 0xFF00FF66, "Birthday" to 0xFFFF4081, "Travel" to 0xFF40C4FF
                    ).forEach { (n, col) -> db.execSQL("INSERT INTO categories (name, color) VALUES ('$n', ${col.toInt()})") }
                }
            })
            .build()

    @Provides fun eventDao(db: AppDb): EventDao = db.events()
    @Provides fun categoryDao(db: AppDb): CategoryDao = db.categories()
}

@Singleton
class EventRepository @Inject constructor(
    private val dao: EventDao,
    private val catDao: CategoryDao,
    private val scheduler: ReminderScheduler
) {
    val events: Flow<List<EventFull>> = dao.observeAll()
    val categories: Flow<List<CategoryEntity>> = catDao.observeAll()

    fun search(q: String): Flow<List<EventFull>> = dao.search(q)
    suspend fun all(): List<EventFull> = dao.getAll()
    suspend fun get(id: Long): EventFull? = dao.get(id)

    suspend fun save(e: EventEntity, rule: RuleEntity?, reminders: List<Int>): Long {
        val id = dao.saveFull(e, rule, reminders)
        scheduler.requestReschedule()
        return id
    }

    suspend fun delete(id: Long) { dao.delete(id); scheduler.requestReschedule() }

    /** Removes a single occurrence of a repeating event. */
    suspend fun skipOccurrence(ef: EventFull, date: java.time.LocalDate) {
        val r = ef.rule ?: return
        val list = r.exdates.split(',').filter { it.isNotBlank() }.toMutableSet()
        list += date.toString()
        dao.setExdates(ef.event.id, list.sorted().joinToString(","))
        scheduler.requestReschedule()
    }

    suspend fun addCategory(name: String, color: Int) { catDao.insert(CategoryEntity(name = name, color = color)) }
    suspend fun deleteCategory(id: Long) { catDao.delete(id) }
}
