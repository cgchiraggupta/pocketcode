package com.remotedev.pocketcode.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.remotedev.pocketcode.MainActivity
import com.remotedev.pocketcode.PocketcodeApp

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val session = intent.getStringExtra("session") ?: return
        val app = PocketcodeApp.instance
        when (intent.action) {
            ACTION_APPROVE -> app.connection.send("""{"t":"agent.approve","session":"$session"}""")
            ACTION_REJECT -> app.connection.send("""{"t":"agent.reject","session":"$session"}""")
            ACTION_VIEW_DIFF -> {
                val open = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("openDiffFor", session)
                }
                context.startActivity(open)
            }
        }
    }

    companion object {
        const val ACTION_APPROVE = "remotedev.action.approve"
        const val ACTION_REJECT = "remotedev.action.reject"
        const val ACTION_VIEW_DIFF = "remotedev.action.viewDiff"
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
        val view = pendingFor(ctx, NotificationActionReceiver.ACTION_VIEW_DIFF, sessionId, 2)
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(title)
            .setContentText(text)
            .addAction(0, "View diff", view)
            .setAutoCancel(true)
            .build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(sessionId.hashCode(), n)
    }

    private fun pendingFor(ctx: Context, action: String, sessionId: String, offset: Int): PendingIntent {
        val intent = Intent(ctx, NotificationActionReceiver::class.java).apply {
            this.action = action
            putExtra("session", sessionId)
        }
        return PendingIntent.getBroadcast(
            ctx, sessionId.hashCode() + offset, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
