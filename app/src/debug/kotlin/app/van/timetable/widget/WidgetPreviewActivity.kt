// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.widget

import android.app.Activity
import android.appwidget.*
import android.content.ComponentName
import android.os.Bundle
import android.widget.LinearLayout

/** A real widget host used only by local instrumentation tests, excluded from release. */
class WidgetPreviewActivity : Activity() {
    lateinit var host: AppWidgetHost
    var widgetId = 0
    lateinit var widgetView: AppWidgetHostView
    val additional = mutableMapOf<Int, AppWidgetHostView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = AppWidgetHost(this, 6215)
        host.startListening()
        widgetId = host.allocateAppWidgetId()
        val container =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 70, 24, 24)
            }
        setContentView(container)
    }

    fun bind(provider: Class<*> = DualWidget::class.java): Boolean {
        val manager = AppWidgetManager.getInstance(this)
        if (!manager.bindAppWidgetIdIfAllowed(widgetId, ComponentName(this, provider))) return false
        widgetView = host.createView(this, widgetId, manager.getAppWidgetInfo(widgetId))
        (findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as LinearLayout)
            .addView(
                widgetView,
                LinearLayout.LayoutParams(-1, (resources.displayMetrics.density * 240).toInt()),
            )
        resize(240)
        return true
    }

    fun resize(heightDp: Int) {
        val density = resources.displayMetrics.density
        val container = widgetView.parent as LinearLayout
        val widthDp =
            ((resources.displayMetrics.widthPixels -
                    container.paddingLeft -
                    container.paddingRight) / density)
                .toInt()
        widgetView.layoutParams = LinearLayout.LayoutParams(-1, (density * heightDp).toInt())
        widgetView.updateAppWidgetSize(Bundle(), widthDp, heightDp, widthDp, heightDp)
    }

    fun addWidget(): Int {
        val id = host.allocateAppWidgetId()
        val manager = AppWidgetManager.getInstance(this)
        check(manager.bindAppWidgetIdIfAllowed(id, ComponentName(this, DualWidget::class.java)))
        val view = host.createView(this, id, manager.getAppWidgetInfo(id))
        view.updateAppWidgetSize(Bundle(), 380, 200, 380, 200)
        (findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as LinearLayout)
            .addView(
                view,
                LinearLayout.LayoutParams(-1, (resources.displayMetrics.density * 200).toInt()),
            )
        additional[id] = view
        return id
    }

    override fun onDestroy() {
        host.stopListening()
        host.deleteAppWidgetId(widgetId)
        additional.keys.forEach { host.deleteAppWidgetId(it) }
        super.onDestroy()
    }
}
