package com.remotedev.pocketcode.connection

import android.content.Context
import com.remotedev.pocketcode.pairing.PairedMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.TimeUnit

sealed class ConnState {
    object Idle : ConnState()
    data class Connecting(val machine: String) : ConnState()
    data class Connected(val machine: String, val ws: WebSocket) : ConnState()
    data class Error(val reason: String) : ConnState()
    object Disconnected : ConnState()
}

class ConnectionManager(ctx: Context) {
    val json = Json { ignoreUnknownKeys = true; classDiscriminator = "t" }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<ConnState>(ConnState.Idle)
    val state: StateFlow<ConnState> = _state

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    val inbound = MutableStateFlow<JsonElement?>(null)

    fun connect(machine: PairedMachine) {
        _state.value = ConnState.Connecting(machine.name)
        val req = Request.Builder()
            .url(machine.url)
            .addHeader("x-device-id", deviceId())
            .addHeader("x-device-fingerprint", androidId(ctx))
            .build()
        val ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) { _state.value = ConnState.Connected(machine.name, ws) }
            override fun onMessage(ws: WebSocket, text: String) {
                runCatching { inbound.value = json.parseToJsonElement(text) }
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                _state.value = ConnState.Error(t.message ?: "failure")
            }
            override fun onClosing(ws: WebSocket, code: Int, reason: String) { _state.value = ConnState.Disconnected }
        })
    }

    fun send(jsonMsg: String) {
        val s = _state.value
        if (s is ConnState.Connected) s.ws.send(jsonMsg)
    }

    fun disconnect() {
        val s = _state.value
        if (s is ConnState.Connected) { s.ws.close(1000, "user"); _state.value = ConnState.Idle }
    }

    private fun deviceId(): String {
        val prefs = ctx.getSharedPreferences("pocketcode-device", Context.MODE_PRIVATE)
        var id = prefs.getString("id", null)
        if (id == null) { id = UUID.randomUUID().toString(); prefs.edit().putString("id", id).apply() }
        return id
    }
    private fun androidId(ctx: Context): String =
        android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "unknown"
}
