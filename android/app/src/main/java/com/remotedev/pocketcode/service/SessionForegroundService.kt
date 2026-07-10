package com.remotedev.pocketcode.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.remotedev.pocketcode.MainActivity
import com.remotedev.pocketcode.PocketcodeApp
import com.remotedev.pocketcode.connection.ConnState
import com.remotedev.pocketcode.notifications.AgentLiveTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the WebSocket connection alive when the app is backgrounded.
 * Started when a session connects; stops itself when the session ends.
 * Shows a persistent "PocketCode: connected to <machine>" notification
 * while running, matching CodeMote's "background the app, still running" behaviour.
 */
class SessionForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(NOTIF_ID, buildNotification("Connecting…"))
        // Combine connection state + live agent summary so the FG notification
        // mirrors CodeMote-style "still running / 1 waiting" at a glance.
        scope.launch {
            combine(
                PocketcodeApp.instance.connection.state,
                AgentLiveTracker.states,
            ) { state, _ -> state to AgentLiveTracker.summary() }
                .collect { (state, agentSummary) ->
                    val suffix = if (agentSummary.isNotEmpty()) " · $agentSummary" else ""
                    when (state) {
                        is ConnState.Connected    -> updateNotification("Connected to ${state.machine}$suffix")
                        is ConnState.Connecting   -> updateNotification("Connecting to ${state.machine}…")
                        is ConnState.Reconnecting -> updateNotification("Reconnecting (${state.attempt})…")
                        ConnState.Disconnected,
                        ConnState.Idle            -> stopSelf()
                        is ConnState.Error        -> stopSelf()
                    }
                }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Session", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("PocketCode")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .build()

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        private const val CHANNEL_ID = "session_fg"
        private const val NOTIF_ID = 9001

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, SessionForegroundService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, SessionForegroundService::class.java))
        }
    }
}
