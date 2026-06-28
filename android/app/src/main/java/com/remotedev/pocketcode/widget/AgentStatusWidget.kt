package com.remotedev.pocketcode.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.remotedev.pocketcode.MainActivity
import com.remotedev.pocketcode.R

class AgentStatusWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(ctx.packageName, R.layout.widget_agent_status)
        views.setTextViewText(R.id.widget_status_text, "Agent: idle")
        val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        views.setOnClickPendingIntent(R.id.widget_status_text, pi)
        mgr.updateAppWidget(ids, views)
    }

    companion object {
        fun push(ctx: Context, status: String) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val views = RemoteViews(ctx.packageName, R.layout.widget_agent_status)
            views.setTextViewText(R.id.widget_status_text, "Agent: $status")
            mgr.updateAppWidget(ComponentName(ctx, AgentStatusWidget::class.java), views)
        }
    }
}
