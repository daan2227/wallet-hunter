package com.btcseedrecovery

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.app.PendingIntent

class HunterWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { updateWidget(ctx, mgr, it) }
    }

    companion object {
        fun updateWidget(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val prefs = ctx.getSharedPreferences("widget_stats", Context.MODE_PRIVATE)
            val kps     = prefs.getFloat("kps", 0f)
            val count   = prefs.getLong("count", 0L)
            val running = prefs.getBoolean("running", false)
            val matches = prefs.getInt("matches", 0)

            val countStr = when {
                count >= 1_000_000_000L -> "%.1fB".format(count/1e9)
                count >= 1_000_000L -> "%.1fM".format(count/1e6)
                count >= 1000L -> "%.1fK".format(count/1e3)
                else -> "$count"
            }

            val views = RemoteViews(ctx.packageName, R.layout.widget_hunter)
            views.setTextViewText(R.id.widget_kps, "%.1f k/s".format(kps))
            views.setTextViewText(R.id.widget_count, countStr)
            views.setTextViewText(R.id.widget_status, if (running) "RUNNING" else "STOPPED")
            views.setTextViewText(R.id.widget_matches, "Matches: $matches")

            val pi = PendingIntent.getActivity(ctx, 0,
                Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_root, pi)
            mgr.updateAppWidget(id, views)
        }

        fun pushStats(ctx: Context, kps: Float, count: Long, running: Boolean, matches: Int) {
            ctx.getSharedPreferences("widget_stats", Context.MODE_PRIVATE).edit()
                .putFloat("kps", kps).putLong("count", count)
                .putBoolean("running", running).putInt("matches", matches).apply()
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(android.content.ComponentName(ctx, HunterWidget::class.java))
            ids.forEach { updateWidget(ctx, mgr, it) }
        }
    }
}
