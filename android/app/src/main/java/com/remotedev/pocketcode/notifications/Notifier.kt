package com.remotedev.pocketcode.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "remotedev.approve" -> { /* tell server: WS msg {"t":"agent.approve"} */ }
            "remotedev.reject" -> { /* ditto */ }
            "remotedev.viewDiff" -> { /* open MainActivity with extra */ }
        }
    }
}

object Notifier {
    const val CHANNEL_ID = "agent_events"

    fun ensure(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Agent events", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    fun show(ctx: Context, title: String, text: String, sessionId: String) {
        val approve = PendingIntent.getBroadcast(ctx, sessionId.hashCode(),
            Intent("remotedev.approve").putExtra("session", sessionId), PendingIntent.FLAG_IMMUTABLE)
        val reject = PendingIntent.getBroadcast(ctx, sessionId.hashCode() + 1,
            Intent("remotedev.reject").putExtra("session", sessionId), PendingIntent.FLAG_IMMUTABLE)
        val view = PendingIntent.getBroadcast(ctx, sessionId.hashCode() + 2,
            Intent("remotedev.viewDiff").putExtra("session", sessionId), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(title)
            .setContentText(text)
            .addAction(0, "Approve", approve)
            .addAction(0, "Reject", reject)
            .addAction(0, "View diff", view)
            .setAutoCancel(true)
            .build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(sessionId.hashCode(), n)
    }
}
