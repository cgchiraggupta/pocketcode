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
                app.connection.respondToApproval(session, approve = true)
            }
            ACTION_REJECT -> {
                app.connection.respondToApproval(session, approve = false)
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
    }
}

object Notifier {
    /** One-shot / actionable agent events (legacy path + server errors). */
    const val CHANNEL_ID = "agent_events"
    /** Low-importance ongoing "agent running" updates (no sound on re-post). */
    const val CHANNEL_LIVE = "agent_live"
    /** High-importance channel for waiting-for-approval (heads-up once). */
    const val CHANNEL_WAITING = "agent_waiting"

    fun ensure(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Agent events", NotificationManager.IMPORTANCE_DEFAULT)
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_LIVE, "Agent live status", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Ongoing per-session agent status (running / finished)"
                    setShowBadge(false)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_WAITING, "Agent needs approval", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Heads-up when an agent is waiting for Approve / Reject"
                }
            )
        }
    }

    /**
     * Show a notification with Approve / Reject / View Diff actions.
     *
     * [sessionId] is used both as a stable notification ID (via hashCode) and
     * as the value forwarded in the WS `agent.approve` / `agent.reject` message.
     * Prefer [updateLive] for ongoing session status; this remains for one-shots.
     */
    fun show(ctx: Context, title: String, text: String, sessionId: String) {
        val approve  = pendingFor(ctx, NotificationActionReceiver.ACTION_APPROVE,   sessionId, 0)
        val reject   = pendingFor(ctx, NotificationActionReceiver.ACTION_REJECT,    sessionId, 1)
        val viewDiff = pendingFor(ctx, NotificationActionReceiver.ACTION_VIEW_DIFF, sessionId, 2)

        val n = NotificationCompat.Builder(ctx, CHANNEL_WAITING)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .addAction(0, "✓ Approve", approve)
            .addAction(0, "✕ Reject",  reject)
            .addAction(0, "View diff", viewDiff)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
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

    /**
     * CodeMote-style **live** per-tab notification: same id is re-used so Android
     * updates in place instead of stacking. Waiting posts to a high-importance
     * channel with Approve/Reject; Running is low/ongoing; Finished is auto-cancel.
     */
    fun updateLive(
        ctx: Context,
        tabId: String,
        state: LiveAgentState,
        tabTitle: String? = null,
    ) {
        ensure(ctx)
        val label = tabTitle?.takeIf { it.isNotBlank() } ?: shortTab(tabId)
        val open = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("openDiffFor", tabId)
        }
        val openPending = PendingIntent.getActivity(
            ctx, tabId.hashCode(), open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = when (state) {
            is LiveAgentState.Waiting -> {
                val approve = pendingFor(ctx, NotificationActionReceiver.ACTION_APPROVE, tabId, 0)
                val reject  = pendingFor(ctx, NotificationActionReceiver.ACTION_REJECT,  tabId, 1)
                val viewDiff = pendingFor(ctx, NotificationActionReceiver.ACTION_VIEW_DIFF, tabId, 2)
                val body = state.snippet.ifBlank { "Agent is waiting for your decision" }
                NotificationCompat.Builder(ctx, CHANNEL_WAITING)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("⏸ Waiting · $label")
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .addAction(0, "✓ Approve", approve)
                    .addAction(0, "✕ Reject", reject)
                    .addAction(0, "View diff", viewDiff)
                    .setContentIntent(openPending)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
            }
            is LiveAgentState.Running -> {
                NotificationCompat.Builder(ctx, CHANNEL_LIVE)
                    .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                    .setContentTitle("▶ Running · $label")
                    .setContentText("Agent is working…")
                    .setContentIntent(openPending)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setSilent(true)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
            }
            is LiveAgentState.Finished -> {
                val ok = state.code == 0
                val title = if (ok) "✓ Finished · $label" else "✕ Failed · $label"
                val body = "Exited with code ${state.code}"
                NotificationCompat.Builder(ctx, CHANNEL_LIVE)
                    .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setContentIntent(openPending)
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            }
        }

        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(tabId.hashCode(), builder.build())
    }

    fun clearLive(ctx: Context, tabId: String) {
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(tabId.hashCode())
    }

    private fun shortTab(tabId: String): String =
        if (tabId.length <= 8) tabId else tabId.take(8)

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
