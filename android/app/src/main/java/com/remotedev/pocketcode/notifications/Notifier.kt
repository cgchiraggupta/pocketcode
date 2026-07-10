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
            ACTION_APPROVE -> {
                // Send agent.approve over WS so the server can write "y\n" to the PTY.
                app.connection.send("""{"t":"agent.approve","session":"$session"}""")
                // Dismiss the notification immediately so the user knows the tap landed.
                dismissNotification(context, session)
            }
            ACTION_REJECT -> {
                app.connection.send("""{"t":"agent.reject","session":"$session"}""")
                dismissNotification(context, session)
            }
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
        const val ACTION_APPROVE  = "remotedev.action.approve"
        const val ACTION_REJECT   = "remotedev.action.reject"
        const val ACTION_VIEW_DIFF = "remotedev.action.viewDiff"

        private fun dismissNotification(ctx: Context, session: String) {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(session.hashCode())
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

    /**
     * Show a notification with Approve / Reject / View Diff actions.
     *
     * [sessionId] is used both as a stable notification ID (via hashCode) and
     * as the value forwarded in the WS `agent.approve` / `agent.reject` message.
     * Pass a short stable string, e.g. the CLI session ID or "agent-session".
     */
    fun show(ctx: Context, title: String, text: String, sessionId: String) {
        val approve  = pendingFor(ctx, NotificationActionReceiver.ACTION_APPROVE,   sessionId, 0)
        val reject   = pendingFor(ctx, NotificationActionReceiver.ACTION_REJECT,    sessionId, 1)
        val viewDiff = pendingFor(ctx, NotificationActionReceiver.ACTION_VIEW_DIFF, sessionId, 2)

        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(title)
            .setContentText(text)
            .addAction(0, "✓ Approve", approve)
            .addAction(0, "✕ Reject",  reject)
            .addAction(0, "View diff", viewDiff)
            .setAutoCancel(true)
            .build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(sessionId.hashCode(), n)
    }

    /**
     * Show a plain informational notification with no action buttons --
     * for agent activity that isn't an approval request (session finished,
     * file changed, tests ran, etc). Tapping it just opens the app.
     */
    fun showInfo(ctx: Context, title: String, text: String, sessionId: String) {
        val open = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("openDiffFor", sessionId)
        }
        val openPending = PendingIntent.getActivity(
            ctx, sessionId.hashCode(), open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openPending)
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
