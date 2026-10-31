package com.mux.calendar

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Thin wrapper around AlarmManager. */
object Alarms {
    const val ACTION_FIRE = "com.mux.calendar.FIRE"
    const val ACTION_SNOOZE = "com.mux.calendar.SNOOZE"

    private fun pi(ctx: Context, code: Int, extras: Bundle?): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_FIRE).setData(Uri.parse("mux-cal://alarm/$code"))
        if (extras != null) i.putExtras(extras)
        return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun canExact(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    }

    fun schedule(ctx: Context, atMs: Long, code: Int, extras: Bundle) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val p = pi(ctx, code, extras)
        try {
            if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, p)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, p)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, p)
        }
    }

    fun cancel(ctx: Context, code: Int) {
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi(ctx, code, null))
    }
}

object Notifier {
    const val CHANNEL = "mux_cal_reminders"

    fun createChannel(ctx: Context) {
        val ch = NotificationChannel(CHANNEL, "Event reminders", NotificationManager.IMPORTANCE_HIGH)
        ch.description = "Alerts before your events start"
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    fun notifId(ex: Bundle): Int = (ex.getLong("eventId") * 31 + ex.getLong("startMs") / 60_000L).toInt()

    fun cancel(ctx: Context, id: Int) {
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id)
    }

    fun show(ctx: Context, ex: Bundle) {
        createChannel(ctx)
        val id = notifId(ex)
        val title = ex.getString("title") ?: "Event"
        val minutes = ex.getInt("minutes")
        val startMs = ex.getLong("startMs")
        val allDay = ex.getBoolean("allDay")
        val loc = ex.getString("location").orEmpty()
        val z = Instant.ofEpochMilli(startMs).atZone(ZoneId.systemDefault())
        val whenText = if (allDay) "All day, " + fmtShort(z.toLocalDate()) else fmtT(z)
        val lead = when {
            allDay -> "Today"
            minutes <= 0 -> "Starting now"
            minutes < 60 -> "In $minutes min"
            minutes < 1440 -> "In ${minutes / 60} h"
            else -> "In ${minutes / 1440} d"
        }
        val text = listOf(lead, whenText, loc).filter { it.isNotBlank() }.joinToString(" - ")

        val open = PendingIntent.getActivity(
            ctx, id, Intent(ctx, MainActivity::class.java).putExtra("eventId", ex.getLong("eventId"))
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val snooze = PendingIntent.getBroadcast(
            ctx, id, Intent(ctx, ReminderReceiver::class.java).setAction(Alarms.ACTION_SNOOZE)
                .setData(Uri.parse("mux-cal://snooze/$id")).putExtras(ex),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_cal)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "Snooze 10 min", snooze)
            .build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, n)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val ex = intent.extras ?: return
        when (intent.action) {
            Alarms.ACTION_FIRE -> {
                Notifier.show(ctx, ex)
                // keep a rolling window of upcoming alarms
                WorkManager.getInstance(ctx).enqueueUniqueWork(
                    "mux_cal_reschedule", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<RescheduleWorker>().build()
                )
            }
            Alarms.ACTION_SNOOZE -> {
                Notifier.cancel(ctx, Notifier.notifId(ex))
                val code = ("snooze" + Notifier.notifId(ex) + System.currentTimeMillis()).hashCode()
                Alarms.schedule(ctx, System.currentTimeMillis() + 10 * 60_000L, code, Bundle(ex))
            }
        }
    }
}

/** Re-plans alarms after reboot, app update, time or time zone change. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "mux_cal_reschedule", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<RescheduleWorker>().build()
        )
    }
}

@Singleton
class ReminderScheduler @Inject constructor(@ApplicationContext private val ctx: Context) {
    private val sp = ctx.getSharedPreferences("mux_cal_alarms", Context.MODE_PRIVATE)

    fun requestReschedule() {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "mux_cal_reschedule", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<RescheduleWorker>().build()
        )
    }

    /** Plans alarms for the next 32 days (nearest 60 reminders). Old alarms are cancelled first. */
    fun reschedule(events: List<EventFull>) {
        (sp.getString("codes", "") ?: "").split(',').mapNotNull { it.toIntOrNull() }.forEach { Alarms.cancel(ctx, it) }

        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val from: LocalDate = LocalDate.now(zone).minusDays(1)
        val to: LocalDate = from.plusDays(33)

        class Trig(val at: Long, val code: Int, val extras: Bundle)
        val trigs = ArrayList<Trig>()
        for (ef in events) {
            if (ef.reminders.isEmpty()) continue
            for (o in occurrencesBetween(listOf(ef), from, to, zone, false)) {
                // all-day events notify at 09:00 of the day, minus the chosen lead time
                val base = if (o.allDay) o.firstDay.atTime(9, 0).atZone(zone).toInstant().toEpochMilli() else o.startZ.toInstant().toEpochMilli()
                for (r in ef.reminders) {
                    val at = base - r.minutesBefore * 60_000L
                    if (at <= now) continue
                    val code = "${ef.event.id}|${o.date}|${r.minutesBefore}".hashCode()
                    val b = Bundle().apply {
                        putLong("eventId", ef.event.id)
                        putString("title", ef.event.title)
                        putString("location", ef.event.location)
                        putLong("startMs", base)
                        putInt("minutes", r.minutesBefore)
                        putBoolean("allDay", o.allDay)
                    }
                    trigs += Trig(at, code, b)
                }
            }
        }
        trigs.sortBy { it.at }
        val chosen = trigs.take(60)
        chosen.forEach { Alarms.schedule(ctx, it.at, it.code, it.extras) }
        sp.edit().putString("codes", chosen.joinToString(",") { it.code.toString() }).apply()
    }
}

@HiltWorker
class RescheduleWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val repo: EventRepository,
    private val scheduler: ReminderScheduler
) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        scheduler.reschedule(repo.all())
        return Result.success()
    }
}
