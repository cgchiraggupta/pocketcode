package com.remotedev.pocketcode.connection

import android.content.Context
import com.remotedev.pocketcode.PocketcodeApp
import com.remotedev.pocketcode.pairing.PairedMachine
import com.remotedev.pocketcode.files.FsNode
import com.remotedev.pocketcode.git.GitStatus
import com.remotedev.pocketcode.agent.AgentEvent
import com.remotedev.pocketcode.terminal.Tab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*
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

class ConnectionManager(private val ctx: Context) {
    val json = Json { ignoreUnknownKeys = true; classDiscriminator = "t" }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<ConnState>(ConnState.Idle)
    val state: StateFlow<ConnState> = _state

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    // ponytail: SharedFlow not StateFlow -- StateFlow conflates emissions and drops messages
    // under load. SharedFlow with UNLIMITED buffer keeps every WS frame intact.
    val inbound = MutableSharedFlow<JsonElement>(extraBufferCapacity = 64, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    val inboundEvents: SharedFlow<JsonElement> = inbound

    val fileTree = MutableStateFlow<List<FsNode>>(emptyList())
    val gitStatus = MutableStateFlow<GitStatus>(GitStatus())
    val gitDiff = MutableStateFlow<String>("")
    val agentEvents = MutableStateFlow<List<AgentEvent>>(emptyList())
    val openFile = MutableStateFlow<Pair<String, String>?>(null)
    val terminalTabs = MutableStateFlow<List<Tab>>(emptyList())
    val devServers = MutableStateFlow<List<String>>(emptyList())
    val workspaces = MutableStateFlow<List<Pair<String, String>>>(emptyList())

    init {
        scope.launch {
            inbound.collect { element ->
                runCatching {
                    val obj = element.jsonObject
                    val type = obj["t"]?.jsonPrimitive?.content ?: return@runCatching
                    when (type) {
                        "fs.tree" -> {
                            val nodes = json.decodeFromJsonElement<List<FsNode>>(obj["nodes"] ?: JsonArray(emptyList()))
                            fileTree.value = nodes
                        }
                        "fs.read" -> {
                            val path = obj["path"]?.jsonPrimitive?.content ?: ""
                            val content = obj["content"]?.jsonPrimitive?.content ?: ""
                            openFile.value = Pair(path, content)
                        }
                        "git.status" -> {
                            val status = json.decodeFromJsonElement<GitStatus>(element)
                            gitStatus.value = status
                        }
                        "git.diff" -> {
                            val text = obj["text"]?.jsonPrimitive?.content ?: ""
                            gitDiff.value = text
                        }
                        "agent.event" -> {
                            val kind = obj["kind"]?.jsonPrimitive?.content ?: ""
                            val payloadStr = obj["payload"]?.toString() ?: ""
                            val ev = AgentEvent(System.currentTimeMillis(), kind, payloadStr)
                            agentEvents.value = agentEvents.value + ev
                            val app = PocketcodeApp.instance
                            scope.launch {
                                app.db.dao().addEvent(com.remotedev.pocketcode.persistence.StoredEvent(
                                    session = "current",
                                    kind = kind,
                                    summary = payloadStr,
                                    ts = ev.ts
                                ))
                            }
                            com.remotedev.pocketcode.notifications.Notifier.show(ctx, "Agent: $kind", payloadStr, "agent-session")
                        }
                        "term.list" -> {
                            val arr = obj["tabs"]?.jsonArray ?: return@runCatching
                            val newTabs = arr.map { item ->
                                val tabObj = item.jsonObject
                                val id = tabObj["id"]?.jsonPrimitive?.content ?: ""
                                val title = tabObj["title"]?.jsonPrimitive?.content ?: ""
                                val alive = tabObj["alive"]?.jsonPrimitive?.boolean ?: true
                                val existing = terminalTabs.value.firstOrNull { it.id == id }
                                if (existing != null) {
                                    existing.copy(title = title, alive = alive)
                                } else {
                                    Tab(id, title, alive)
                                }
                            }
                            terminalTabs.value = newTabs
                        }
                        "term.data" -> {
                            val tabId = obj["tab"]?.jsonPrimitive?.content ?: ""
                            val data = obj["data"]?.jsonPrimitive?.content ?: ""
                            terminalTabs.value = terminalTabs.value.map { tab ->
                                if (tab.id == tabId) {
                                    val rendered = com.remotedev.pocketcode.terminal.renderAnsi(data)
                                    tab.copy(lines = tab.lines + rendered)
                                } else {
                                    tab
                                }
                            }
                        }
                        "term.exit" -> {
                            val tabId = obj["tab"]?.jsonPrimitive?.content ?: ""
                            val code = obj["code"]?.jsonPrimitive?.intOrNull ?: 0
                            terminalTabs.value = terminalTabs.value.map { tab ->
                                if (tab.id == tabId) {
                                    val exitMsg = androidx.compose.ui.text.buildAnnotatedString {
                                        append("\n[Process exited with code $code]\n")
                                    }
                                    tab.copy(alive = false, lines = tab.lines + exitMsg)
                                } else {
                                    tab
                                }
                            }
                        }
                        "devservers" -> {
                            val arr = obj["list"]?.jsonArray ?: return@runCatching
                            devServers.value = arr.map { it.jsonObject["port"]?.jsonPrimitive?.content ?: "" }
                        }
                        "workspace.list" -> {
                            val arr = obj["list"]?.jsonArray ?: return@runCatching
                            workspaces.value = arr.map {
                                val wObj = it.jsonObject
                                Pair(wObj["name"]?.jsonPrimitive?.content ?: "", wObj["uri"]?.jsonPrimitive?.content ?: "")
                            }
                        }
                    }
                }
            }
        }
    }

    fun connect(machine: PairedMachine) {
        _state.value = ConnState.Connecting(machine.name)
        // ponytail: token is in the URL query string (per extension/src/server/index.ts:40-41).
        val wsUrl = if (machine.token.isNotEmpty() && !machine.url.contains("token=")) {
            val sep = if (machine.url.contains("?")) "&" else "?"
            "$machine.url${sep}token=${machine.token}"
        } else machine.url
        val req = Request.Builder()
            .url(wsUrl)
            .addHeader("x-device-id", deviceId())
            .addHeader("x-device-fingerprint", androidId())
            .build()
        val ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                _state.value = ConnState.Connected(machine.name, ws)
                send("""{"t":"fs.tree"}""")
                send("""{"t":"git.status"}""")
                send("""{"t":"term.list"}""")
                send("""{"t":"devservers"}""")
                send("""{"t":"workspace.list"}""")
            }
            override fun onMessage(ws: WebSocket, text: String) {
                runCatching { json.parseToJsonElement(text) }
                    .onSuccess { inbound.tryEmit(it) }
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
    private fun androidId(): String =
        android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "unknown"
}
