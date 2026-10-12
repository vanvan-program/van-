// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.widget

import android.app.PendingIntent
import android.appwidget.*
import android.content.*
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.*
import android.util.TypedValue
import android.view.View
import android.widget.*
import app.van.timetable.*
import app.van.timetable.domain.*
import app.van.timetable.system.Scheduler
import java.time.*
import kotlinx.coroutines.*

object Widgets {
    internal fun isDark(c: Context, id: Int, p: Preferences): Boolean {
        val mode =
            c.getSharedPreferences("widgets", Context.MODE_PRIVATE).getString("theme_$id", "app")
        return when (mode) {
            "dark" -> true
            "light" -> false
            else ->
                p.theme == "dark" ||
                    (p.theme == "system" &&
                        (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                            Configuration.UI_MODE_NIGHT_YES)
        }
    }

    internal fun height(c: Context, id: Int): Int =
        AppWidgetManager.getInstance(c)
            .getAppWidgetOptions(id)
            .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 130)

    private fun dp(c: Context, value: Int) = (value * c.resources.displayMetrics.density).toInt()

    suspend fun schedule(c: Context, id: Int): Schedule? {
        val store = (c.applicationContext as VanApplication).store
        val selected =
            c.getSharedPreferences("widgets", Context.MODE_PRIVATE).getString("schedule_$id", null)
        return if (selected.isNullOrBlank()) store.active()
        else store.all().firstOrNull { it.id == selected }
    }

    private val classes =
        listOf(DualWidget::class.java, TodayWidget::class.java, NextWidget::class.java)

    fun hasAny(c: Context) = classes.any {
        AppWidgetManager.getInstance(c).getAppWidgetIds(ComponentName(c, it)).isNotEmpty()
    }

    private fun timetableIntent(c: Context, scheduleId: String?, date: String? = null) =
        Intent(c, MainActivity::class.java)
            .putExtra("scheduleId", scheduleId)
            .putExtra("date", date)
            .putExtra("openTimetable", true)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )

    suspend fun boundaries(c: Context, now: Instant): List<Instant> {
        val manager = AppWidgetManager.getInstance(c)
        val schedules =
            classes
                .flatMap { manager.getAppWidgetIds(ComponentName(c, it)).toList() }
                .mapNotNull { schedule(c, it) }
                .distinctBy { it.id }
        return schedules.flatMap { s ->
            val zone = ZoneId.of(s.zone)
            val date = now.atZone(zone).toLocalDate()
            listOf(date.plusDays(1).atStartOfDay(zone).toInstant()) +
                (0..1)
                    .flatMap { CourseEngine.resolveDay(s, date.plusDays(it.toLong())) }
                    .flatMap { listOf(it.startsAt(zone), it.endsAt(zone)) }
        }
    }

    suspend fun updateAll(c: Context) {
        val m = AppWidgetManager.getInstance(c)
        classes.forEach { clazz ->
            m.getAppWidgetIds(ComponentName(c, clazz)).forEach { id -> update(c, id, clazz) }
        }
    }

    suspend fun update(c: Context, id: Int, clazz: Class<*>? = null) {
        val app = c.applicationContext as VanApplication
        val s = schedule(c, id)
        val p = app.store.prefs()
        val m = AppWidgetManager.getInstance(c)
        val provider = clazz?.name ?: m.getAppWidgetInfo(id)?.provider?.className ?: return
        val type =
            if (provider.endsWith("NextWidget")) "next"
            else if (provider.endsWith("TodayWidget")) "today" else "dual"
        val dark = isDark(c, id, p)
        val widgetHeight = height(c, id)
        // Widget options report the available content size after host padding.
        val compact = widgetHeight < 140 || c.resources.configuration.fontScale > 1.15f
        val tiny = widgetHeight <= 100
        val text = Color.parseColor(if (dark) "#ECECF3" else "#252635")
        val secondary = Color.parseColor(if (dark) "#B3B3C2" else "#737583")
        val now = Instant.now()
        val zone = ZoneId.of(s?.zone ?: "Asia/Shanghai")
        val today = now.atZone(zone).toLocalDate()
        val view =
            RemoteViews(
                c.packageName,
                if (type == "next") R.layout.widget_next
                else if (compact) R.layout.widget_dual_compact else R.layout.widget_dual,
            )
        view.setInt(
            R.id.widget_root,
            "setBackgroundResource",
            if (dark) R.drawable.widget_dark else R.drawable.widget_light,
        )
        view.setTextViewText(
            R.id.widget_date,
            "${today.monthValue}.${today.dayOfMonth} ${s?.let { val week = CourseEngine.week(it, today)
 if (week < 1) "未开学" else if (week > it.maxWeeks) "已结课" else "第${week}周" }.orEmpty()} ${listOf("周一","周二","周三","周四","周五","周六","周日")[today.dayOfWeek.value-1]}",
        )
        view.setTextColor(R.id.widget_date, secondary)
        view.setTextViewText(
            R.id.widget_updated,
            "更新 ${now.atZone(zone).toLocalTime().withSecond(0).withNano(0)}${if (type == "next") " · ${s?.name ?: "请启用课表"}" else ""}",
        )
        view.setTextColor(R.id.widget_updated, secondary)
        val settings =
            PendingIntent.getActivity(
                c,
                id * 10 + 2,
                Intent(c, WidgetSettingsActivity::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .setData(Uri.parse("van://widget/$id/settings")),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        view.setOnClickPendingIntent(R.id.widget_updated, settings)
        val home =
            PendingIntent.getActivity(
                c,
                id * 10,
                timetableIntent(c, s?.id, today.toString()).setData(Uri.parse("van://widget/$id")),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        view.setOnClickPendingIntent(R.id.widget_date, home)
        view.setOnClickPendingIntent(R.id.widget_root, home)
        if (type == "next") {
            val next = s?.let { CourseEngine.next(it, now) }
            view.setTextViewText(
                R.id.next_name,
                next?.course?.name ?: if (s == null) "请启用课表" else "暂无后续课程",
            )
            view.setTextColor(R.id.next_name, text)
            view.setTextViewText(
                R.id.next_detail,
                next
                    ?.let {
                        "${if(now>=it.startsAt(zone))"正在上课"else"下一节"} · ${it.date}\n${it.place} · ${it.start}–${it.end}"
                    }
                    .orEmpty(),
            )
            view.setTextColor(R.id.next_detail, text)
            view.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(
                    c,
                    id * 10 + 1,
                    timetableIntent(c, s?.id, next?.date?.toString())
                        .setData(Uri.parse("van://widget/$id/next")),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } else {
            view.setTextViewText(R.id.widget_name, s?.name ?: "请选择课表")
            view.setTextColor(R.id.widget_name, secondary)
            view.setOnClickPendingIntent(R.id.widget_name, settings)
            view.setInt(
                R.id.widget_divider,
                "setBackgroundColor",
                Color.parseColor(if (dark) "#444450" else "#D5D4DE"),
            )
            view.setViewVisibility(
                R.id.widget_divider,
                if (type == "today") View.GONE else View.VISIBLE,
            )
            if (tiny) {
                view.setViewVisibility(R.id.widget_updated, View.GONE)
                view.setViewPadding(R.id.widget_root, dp(c, 8), 0, dp(c, 8), 0)
                view.setTextViewTextSize(R.id.today_title, TypedValue.COMPLEX_UNIT_SP, 11f)
                view.setTextViewTextSize(R.id.tomorrow_title, TypedValue.COMPLEX_UNIT_SP, 11f)
                view.setTextViewTextSize(R.id.widget_updated, TypedValue.COMPLEX_UNIT_SP, 8f)
                view.setTextViewTextSize(R.id.widget_name, TypedValue.COMPLEX_UNIT_SP, 10f)
                view.setTextViewTextSize(R.id.widget_date, TypedValue.COMPLEX_UNIT_SP, 9f)
            }
            listOf(R.id.today_column, R.id.today_content, R.id.today_title, R.id.today_empty)
                .forEach { view.setOnClickPendingIntent(it, home) }
            val tomorrowHome =
                PendingIntent.getActivity(
                    c,
                    id * 10 + 3,
                    timetableIntent(c, s?.id, today.plusDays(1).toString())
                        .setData(Uri.parse("van://widget/$id/tomorrow-blank")),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            listOf(
                    R.id.tomorrow_column,
                    R.id.tomorrow_content,
                    R.id.tomorrow_title,
                    R.id.tomorrow_empty,
                )
                .forEach { view.setOnClickPendingIntent(it, tomorrowHome) }
            view.setViewVisibility(
                R.id.tomorrow_column,
                if (type == "today") View.GONE else View.VISIBLE,
            )
            listOf(
                    R.id.today_title,
                    R.id.tomorrow_title,
                    R.id.today_empty,
                    R.id.tomorrow_empty,
                    R.id.widget_refresh,
                )
                .forEach { view.setTextColor(it, secondary) }
            val remaining = s?.let { CourseEngine.remaining(it, now) }.orEmpty()
            val tomorrow = s?.let { CourseEngine.resolveDay(it, today.plusDays(1)) }.orEmpty()
            val original = s?.let { CourseEngine.resolveDay(it, today) }.orEmpty()
            view.setTextViewText(
                R.id.today_empty,
                if (s == null) "请在App启用课表"
                else if (CourseEngine.holiday(s, today) != null) "假期无课"
                else if (original.isEmpty()) "今日无课" else "今日课程已结束",
            )
            view.setTextViewText(
                R.id.tomorrow_empty,
                if (s == null) "请启用课表"
                else if (CourseEngine.holiday(s, today.plusDays(1)) != null) "假期无课" else "明日无课",
            )
            bindList(
                c,
                view,
                id,
                R.id.today_list,
                R.id.today_empty,
                remaining,
                "today",
                now,
                zone,
                s?.id,
                dark,
                compact,
                tiny,
            )
            if (type == "dual")
                bindList(
                    c,
                    view,
                    id,
                    R.id.tomorrow_list,
                    R.id.tomorrow_empty,
                    tomorrow,
                    "tomorrow",
                    now,
                    zone,
                    s?.id,
                    dark,
                    compact,
                    tiny,
                )
            val refresh =
                Intent(c, Class.forName(provider))
                    .setAction("app.van.timetable.REFRESH")
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .setData(Uri.parse("van://refresh/$id"))
            view.setOnClickPendingIntent(
                R.id.widget_refresh,
                PendingIntent.getBroadcast(
                    c,
                    id,
                    refresh,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        m.updateAppWidget(id, view)
        if (Build.VERSION.SDK_INT < 31) {
            @Suppress("DEPRECATION") m.notifyAppWidgetViewDataChanged(id, R.id.today_list)
            @Suppress("DEPRECATION") m.notifyAppWidgetViewDataChanged(id, R.id.tomorrow_list)
        }
    }

    private fun bindList(
        c: Context,
        view: RemoteViews,
        id: Int,
        listId: Int,
        emptyId: Int,
        items: List<Occurrence>,
        column: String,
        now: Instant,
        zone: ZoneId,
        scheduleId: String?,
        dark: Boolean,
        compact: Boolean,
        tiny: Boolean,
    ) {
        view.setEmptyView(listId, emptyId)
        if (Build.VERSION.SDK_INT >= 31) {
            val builder =
                RemoteViews.RemoteCollectionItems.Builder()
                    .setHasStableIds(true)
                    .setViewTypeCount(2)
            items.forEach { o ->
                builder.addItem(stableId(o.id), item(c, o, now, zone, dark, compact, tiny))
            }
            view.setRemoteAdapter(listId, builder.build())
        } else {
            val intent =
                Intent(c, CourseListService::class.java)
                    .putExtra("id", id)
                    .putExtra("column", column)
                    .setData(Uri.parse("van://adapter/$id/$column"))
            @Suppress("DEPRECATION") view.setRemoteAdapter(listId, intent)
        }
        val intent = timetableIntent(c, scheduleId).setData(Uri.parse("van://widget/$id/$column"))
        val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        view.setPendingIntentTemplate(
            listId,
            PendingIntent.getActivity(
                c,
                id * 100 + listId % 100,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or mutable,
            ),
        )
    }

    fun stableId(value: String): Long =
        value.fold(-3750763034362895579L) { hash, ch ->
            (hash xor ch.code.toLong()) * 1099511628211L
        }

    fun item(
        c: Context,
        o: Occurrence,
        now: Instant,
        zone: ZoneId,
        dark: Boolean = false,
        compact: Boolean = true,
        tiny: Boolean = false,
    ) =
        RemoteViews(
                c.packageName,
                if (compact) R.layout.widget_item_compact else R.layout.widget_item,
            )
            .apply {
                val ongoing = now >= o.startsAt(zone) && now < o.endsAt(zone)
                val name = (if (o.adjusted) "【调】" else "") + o.course.name
                setTextViewText(R.id.item_name, name)
                setTextViewText(
                    R.id.item_place,
                    if (compact) o.place.ifBlank { "地点待定" }
                    else
                        listOf(o.place.ifBlank { "地点待定" }, o.session.teacher)
                            .filter { it.isNotBlank() }
                            .joinToString("  "),
                )
                setTextViewText(
                    R.id.item_time,
                    "${o.start}–${o.end}${if (ongoing && !compact) " · 上课中" else ""}",
                )
                setContentDescription(
                    R.id.item_root,
                    "${if (ongoing) "正在上课，" else ""}$name，${o.start}–${o.end}，${o.place.ifBlank { "地点待定" }}${o.session.teacher.takeIf { it.isNotBlank() }?.let { "，$it" }.orEmpty()}",
                )
                setTextColor(R.id.item_name, Color.parseColor(if (dark) "#ECECF3" else "#252635"))
                if (compact) {
                    setViewVisibility(R.id.item_status, if (ongoing) View.VISIBLE else View.GONE)
                    setTextColor(
                        R.id.item_status,
                        Color.parseColor(if (dark) "#9DBBFF" else "#4261A6"),
                    )
                }
                listOf(R.id.item_place, R.id.item_time).forEach {
                    setTextColor(it, Color.parseColor(if (dark) "#B3B3C2" else "#737583"))
                }
                if (tiny) {
                    setViewPadding(R.id.item_root, 0, 0, 0, 0)
                    setTextViewTextSize(R.id.item_name, TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextViewTextSize(R.id.item_time, TypedValue.COMPLEX_UNIT_SP, 9f)
                    setTextViewTextSize(R.id.item_place, TypedValue.COMPLEX_UNIT_SP, 9f)
                }
                setInt(
                    R.id.item_bar,
                    "setBackgroundColor",
                    CoursePalette.argb(o.course, true).toInt(),
                )
                setOnClickFillInIntent(
                    R.id.item_root,
                    Intent().putExtra("date", o.date.toString()),
                )
            }
}

open class VanWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        (c.applicationContext as VanApplication).scope.launch {
            try {
                Scheduler.reconcile(c)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        c: Context,
        m: AppWidgetManager,
        id: Int,
        options: Bundle,
    ) {
        onUpdate(c, m, intArrayOf(id))
    }

    override fun onReceive(c: Context, i: Intent) {
        if (i.action == "app.van.timetable.REFRESH")
            onUpdate(
                c,
                AppWidgetManager.getInstance(c),
                intArrayOf(i.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0)),
            )
        else super.onReceive(c, i)
    }

    override fun onDeleted(c: Context, ids: IntArray) {
        val edit = c.getSharedPreferences("widgets", Context.MODE_PRIVATE).edit()
        ids.forEach {
            edit.remove("theme_$it")
            edit.remove("schedule_$it")
        }
        edit.apply()
    }
}

class DualWidget : VanWidget()

class TodayWidget : VanWidget()

class NextWidget : VanWidget()

class CourseListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        object : RemoteViewsFactory {
            var list = emptyList<Occurrence>()
            var zone = ZoneId.of("Asia/Shanghai")
            var dark = false
            var compact = true
            var tiny = false
            val column = intent.getStringExtra("column")

            override fun onCreate() {}

            override fun onDataSetChanged() {
                runBlocking(Dispatchers.IO) {
                    val s = Widgets.schedule(applicationContext, intent.getIntExtra("id", 0))
                    val widgetId = intent.getIntExtra("id", 0)
                    val preferences = (applicationContext as VanApplication).store.prefs()
                    dark = Widgets.isDark(applicationContext, widgetId, preferences)
                    val widgetHeight = Widgets.height(applicationContext, widgetId)
                    compact = widgetHeight < 140 || resources.configuration.fontScale > 1.15f
                    tiny = widgetHeight <= 100
                    zone = ZoneId.of(s?.zone ?: "Asia/Shanghai")
                    list =
                        if (s == null) emptyList()
                        else if (column == "tomorrow")
                            CourseEngine.resolveDay(s, LocalDate.now(zone).plusDays(1))
                        else CourseEngine.remaining(s, Instant.now())
                }
            }

            override fun onDestroy() {
                list = emptyList()
            }

            override fun getCount() = list.size

            override fun getViewAt(position: Int) =
                list.getOrNull(position)?.let {
                    Widgets.item(applicationContext, it, Instant.now(), zone, dark, compact, tiny)
                }

            override fun getLoadingView(): RemoteViews? = null

            override fun getViewTypeCount() = 2

            override fun getItemId(position: Int) =
                list.getOrNull(position)?.let { Widgets.stableId(it.id) } ?: position.toLong()

            override fun hasStableIds() = true
        }
}
