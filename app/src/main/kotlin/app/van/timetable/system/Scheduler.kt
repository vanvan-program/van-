// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.system

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.van.timetable.*
import app.van.timetable.domain.*
import app.van.timetable.widget.Widgets
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object Scheduler {
    private val mutex = Mutex()

    private fun app(c: Context) = c.applicationContext as VanApplication

    fun allowed(c: Context) =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    private fun post(c: Context, id: Int, notification: Notification): Boolean {
        if (
            Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    c,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
        )
            return false
        return try {
            NotificationManagerCompat.from(c).notify(id, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun channels(c: Context) {
        val m = c.getSystemService(NotificationManager::class.java)
        m.createNotificationChannel(
            NotificationChannel("courses", "课前提醒", NotificationManager.IMPORTANCE_DEFAULT)
        )
        m.createNotificationChannel(
            NotificationChannel("status", "课程状态", NotificationManager.IMPORTANCE_LOW)
        )
    }

    fun testNotification(c: Context) {
        channels(c)
        if (!allowed(c)) return
        post(
            c,
            3,
            NotificationCompat.Builder(c, "courses")
                .setSmallIcon(R.drawable.ic_van)
                .setContentTitle("van课表 · 通知测试")
                .setContentText("操作系统 · 示例楼A214 · 10:00开始")
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun content(c: Context, o: Occurrence?) =
        PendingIntent.getActivity(
            c,
            50,
            Intent(c, MainActivity::class.java)
                .putExtra("courseId", o?.course?.id)
                .putExtra("date", o?.date?.toString())
                .setData(android.net.Uri.parse("van://course/${o?.id?:"home"}")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun reminderItems(s: Schedule, date: LocalDate): List<Occurrence> {
        val all = CourseEngine.resolveDay(s, date)
        val groups = CourseEngine.conflicts(all)
        val excluded =
            groups
                .flatMap { g ->
                    val main = CourseEngine.main(g)
                    g.filter { it.id != main.id }
                }
                .map { it.id }
                .toSet()
        return all.filter { it.id !in excluded && it.course.reminder }
    }

    suspend fun reconcile(c: Context, eventTime: Long? = null) {
        mutex.withLock {
            val store = app(c).store
            val active = store.active()
            val s =
                active
                    ?: Schedule(
                        week1Monday =
                            LocalDate.now(ZoneId.of("Asia/Shanghai"))
                                .with(
                                    java.time.temporal.TemporalAdjusters.previousOrSame(
                                        DayOfWeek.MONDAY
                                    )
                                )
                                .toString()
                    )
            val p = store.prefs()
            val alarms = c.getSystemService(AlarmManager::class.java)
            val pending =
                PendingIntent.getBroadcast(
                    c,
                    1,
                    Intent(c, AlarmReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            alarms.cancel(pending)
            channels(c)
            val zone = ZoneId.of(s.zone)
            val now = Instant.now()
            val date = now.atZone(zone).toLocalDate()
            val candidates = (0..2).flatMap { d -> reminderItems(s, date.plusDays(d.toLong())) }
            if (p.reminders && eventTime != null && allowed(c)) {
                val due = candidates.filter { o ->
                    o.startsAt(zone)
                        .minusSeconds((o.course.leadMinutes ?: p.leadMinutes) * 60L)
                        .toEpochMilli() == eventTime && o.endsAt(zone) > now
                }
                val cache = c.getSharedPreferences("alarm_state", Context.MODE_PRIVATE)
                val sent = cache.getStringSet("sent", emptySet()).orEmpty()
                val fresh = due.filter { "${s.id}/${it.id}/$eventTime" !in sent }
                if (fresh.isNotEmpty()) {
                    val first = fresh.first()
                    val line =
                        fresh.joinToString("；") {
                            "${it.course.name} · ${it.place.ifBlank{"地点待定"}} · ${it.start}开始"
                        }
                    post(
                        c,
                        2,
                        NotificationCompat.Builder(c, "courses")
                            .setSmallIcon(R.drawable.ic_van)
                            .setContentTitle(line)
                            .setContentText(if (fresh.size > 1) "${fresh.size}门课程提醒" else "课前提醒")
                            .setStyle(NotificationCompat.BigTextStyle().bigText(line))
                            .setContentIntent(content(c, first))
                            .setAutoCancel(true)
                            .build(),
                    )
                    cache
                        .edit()
                        .putStringSet(
                            "sent",
                            (sent + fresh.map { "${s.id}/${it.id}/$eventTime" })
                                .toList()
                                .takeLast(100)
                                .toSet(),
                        )
                        .apply()
                }
            }
            if (active != null && p.statusNotification && allowed(c)) {
                val next = CourseEngine.next(s, now)
                if (next == null) NotificationManagerCompat.from(c).cancel(1)
                else
                    post(
                        c,
                        1,
                        NotificationCompat.Builder(c, "status")
                            .setSmallIcon(R.drawable.ic_van)
                            .setContentTitle("${next.course.name} · ${next.place} · ${next.start}")
                            .setContentText(
                                if (now >= next.startsAt(zone)) "正在上课" else "下一节 · ${next.date}"
                            )
                            .setContentIntent(content(c, next))
                            .setOnlyAlertOnce(true)
                            .setOngoing(true)
                            .build(),
                    )
            } else NotificationManagerCompat.from(c).cancel(1)
            Widgets.updateAll(c)
            val boundaries = mutableListOf(date.plusDays(1).atStartOfDay(zone).toInstant())
            boundaries += Widgets.boundaries(c, now)
            if (p.reminders)
                candidates.forEach { o ->
                    boundaries +=
                        o.startsAt(zone).minusSeconds((o.course.leadMinutes ?: p.leadMinutes) * 60L)
                }
            if (p.statusNotification || Widgets.hasAny(c))
                (0..1)
                    .flatMap { CourseEngine.resolveDay(s, date.plusDays(it.toLong())) }
                    .forEach { o ->
                        boundaries += o.startsAt(zone)
                        boundaries += o.endsAt(zone)
                    }
            val next = boundaries.filter { it > now }.minOrNull() ?: return@withLock
            val intent = Intent(c, AlarmReceiver::class.java).putExtra("at", next.toEpochMilli())
            val pi =
                PendingIntent.getBroadcast(
                    c,
                    1,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            try {
                if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms())
                    alarms.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        next.toEpochMilli(),
                        pi,
                    )
                else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toEpochMilli(), pi)
            } catch (_: SecurityException) {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toEpochMilli(), pi)
            }
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val pending = goAsync()
        (c.applicationContext as VanApplication).scope.launch {
            try {
                Scheduler.reconcile(c, i.getLongExtra("at", 0).takeIf { it > 0 })
            } finally {
                pending.finish()
            }
        }
    }
}

class ReconcileReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (
            i.action !in
                setOf(
                    Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_MY_PACKAGE_REPLACED,
                    Intent.ACTION_TIME_CHANGED,
                    Intent.ACTION_TIMEZONE_CHANGED,
                    AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
                )
        )
            return
        val pending = goAsync()
        (c.applicationContext as VanApplication).scope.launch {
            try {
                Scheduler.reconcile(c)
            } finally {
                pending.finish()
            }
        }
    }
}
