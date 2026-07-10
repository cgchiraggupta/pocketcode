package com.remotedev.pocketcode.connection

import android.content.Context
import com.remotedev.pocketcode.PocketcodeApp
import com.remotedev.pocketcode.pairing.PairedMachine
import com.remotedev.pocketcode.files.FsNode
import com.remotedev.pocketcode.git.GitStatus
import com.remotedev.pocketcode.agent.AgentEvent
import com.remotedev.pocketcode.agent.CostTracker
import com.remotedev.pocketcode.agent.CostUpdate
import com.remotedev.pocketcode.notifications.AgentLiveTracker
import com.remotedev.pocketcode.notifications.LiveAgentState
import com.remotedev.pocketcode.notifications.Notifier
import com.remotedev.pocketcode.terminal.Tab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
    data class Reconnecting(val machine: String, val attempt: Int) : ConnState()
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
    private val costState = CostTracker.State()
    val costFlow = MutableStateFlow<CostUpdate?>(null)
    val openFile = MutableStateFlow<Pair<String, String>?>(null)
    val terminalTabs = MutableStateFlow<List<Tab>>(emptyList())
    val devServers = MutableStateFlow<List<String>>(emptyList())
    val workspaces = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val lastConnectUrl = MutableStateFlow<String?>(null)

    private var lastMachine: PairedMachine? = null
    private var userDisconnected = false
    private var reconnectJob: Job? = null
    private var activeWs: WebSocket? = null

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
                            // Server now stamps the originating PTY tab id on every
                            // agent.event (see protocol.ts) -- previously this was
                            // always hardcoded to "agent-session", so a phone running
                            // 3 terminals had no way to tell which one needed attention.
                            val tab = obj["tab"]?.jsonPrimitive?.content ?: "agent-session"
                            val payloadStr = obj["payload"]?.toString() ?: ""
                            val ev = AgentEvent(System.currentTimeMillis(), kind, payloadStr, tab)
                            agentEvents.value = agentEvents.value + ev
                            val app = PocketcodeApp.instance
                            scope.launch {
                                app.db.dao().addEvent(com.remotedev.pocketcode.persistence.StoredEvent(
                                    session = tab,
                                    kind = kind,
                                    summary = payloadStr,
                                    ts = ev.ts
                                ))
                            }
                            // Live notification: Waiting state gets Approve/Reject on the
                            // same per-tab notification id (in-place update). Other kinds
                            // stay informational one-shots so they don't stomp the live card.
                            if (kind == "awaiting_approval") {
                                val snippet = extractApprovalSnippet(payloadStr)
                                if (AgentLiveTracker.apply(tab, LiveAgentState.Waiting(snippet))) {
                                    val title = terminalTabs.value.firstOrNull { it.id == tab }?.title
                                    Notifier.updateLive(ctx, tab, LiveAgentState.Waiting(snippet), title)
                                }
                            } else {
                                Notifier.showInfo(ctx, "Agent: $kind", payloadStr, tab)
                            }
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
                        "term.replay" -> {
                            // Server's capped scrollback buffer for a tab, sent right after
                            // term.list on every (re)connect. Treated as authoritative --
                            // REPLACES local lines for that tab rather than appending, since
                            // otherwise a quick reconnect where nothing was actually missed
                            // would duplicate everything already rendered locally.
                            val tabId = obj["tab"]?.jsonPrimitive?.content ?: return@runCatching
                            val data = obj["data"]?.jsonPrimitive?.content ?: ""
                            val newLines = data.split('\n').map { line ->
                                com.remotedev.pocketcode.terminal.renderAnsi(line)
                            }
                            val capped = if (newLines.size > 2000) newLines.takeLast(2000) else newLines
                            terminalTabs.value = terminalTabs.value.map { tab ->
                                if (tab.id == tabId) tab.copy(lines = capped) else tab
                            }
                            // Intentionally NOT fed through AgentEventParser or CostTracker --
                            // this is historical output that was already processed (or should
                            // have been) the first time it streamed through as term.data; running
                            // it again would re-fire approval notifications for prompts the user
                            // already answered while disconnected.
                        }
                        "term.data" -> {
                            val tabId = obj["tab"]?.jsonPrimitive?.content ?: ""
                            val data = obj["data"]?.jsonPrimitive?.content ?: ""
                            terminalTabs.value = terminalTabs.value.map { tab ->
                                if (tab.id == tabId) {
                                    // Split chunk into lines so each line renders separately.
                                    // Keep a trailing partial line (no newline yet) as the last
                                    // entry so it gets appended on the next chunk.
                                    val newLines = data.split('\n').map { line ->
                                        com.remotedev.pocketcode.terminal.renderAnsi(line)
                                    }
                                    val merged = tab.lines + newLines
                                    // Cap at 2000 lines to prevent OOM on long sessions
                                    val capped = if (merged.size > 2000) merged.takeLast(2000) else merged
                                    tab.copy(lines = capped)
                                } else {
                                    tab
                                }
                            }
                            // Feed the same terminal chunk through the parsers.
                            com.remotedev.pocketcode.agent.AgentEventParser.parse(data, tabId) { ev ->
                                agentEvents.value = agentEvents.value + ev
                                scope.launch {
                                    PocketcodeApp.instance.db.dao().addEvent(
                                        com.remotedev.pocketcode.persistence.StoredEvent(
                                            session = "current", kind = ev.kind, summary = ev.summary, ts = ev.ts
                                        )
                                    )
                                }
                                com.remotedev.pocketcode.widget.AgentStatusWidget.push(ctx, ev.kind)
                            }
                            CostTracker.consume(data, costState)?.let { costFlow.value = it }
                            // Live status: first (or subsequent non-waiting) output marks
                            // the tab Running. Waiting is sticky until approve/reject/exit.
                            if (tabId.isNotEmpty() &&
                                AgentLiveTracker.apply(tabId, LiveAgentState.Running)
                            ) {
                                val title = terminalTabs.value.firstOrNull { it.id == tabId }?.title
                                Notifier.updateLive(ctx, tabId, LiveAgentState.Running, title)
                            }
                        }
                        "error" -> {
                            val msg = obj["msg"]?.jsonPrimitive?.content ?: "unknown error"
                            android.util.Log.e("PocketCode", "Server error: $msg")
                            Notifier.show(ctx, "Server error", msg, "server-error")
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
                            // Live card flips to Finished (auto-cancel) instead of a separate
                            // one-shot that would stack next to the ongoing Running card.
                            if (tabId.isNotEmpty()) {
                                val finished = LiveAgentState.Finished(code)
                                AgentLiveTracker.apply(tabId, finished, force = true)
                                val title = terminalTabs.value.firstOrNull { it.id == tabId }?.title
                                Notifier.updateLive(ctx, tabId, finished, title)
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
                        "token.refresh" -> {
                            // Server is proactively rotating our token before it expires.
                            // Persist the new token into the machine record so it survives
                            // a reconnect (the old token stays valid for a 2-minute grace period).
                            val newToken = obj["token"]?.jsonPrimitive?.content ?: return@runCatching
                            val newExp = obj["exp"]?.jsonPrimitive?.longOrNull ?: return@runCatching
                            val machine = lastMachine ?: return@runCatching
                            val updated = machine.copy(token = newToken)
                            lastMachine = updated
                            PocketcodeApp.instance.machines.updateToken(machine.id, newToken)
                            android.util.Log.i("PocketCode", "Token refreshed silently; new expiry $newExp")
                        }
                    }
                }
            }
        }
    }

    fun connect(machine: PairedMachine) {
        userDisconnected = false
        lastMachine = machine
        reconnectJob?.cancel()
        com.remotedev.pocketcode.service.SessionForegroundService.start(ctx)
        openWebSocket(machine)
    }

    private fun openWebSocket(machine: PairedMachine, attempt: Int = 0) {
        activeWs?.close(1000, "reconnect")
        _state.value = if (attempt > 0) {
            ConnState.Reconnecting(machine.name, attempt)
        } else {
            ConnState.Connecting(machine.name)
        }
        val wsUrl = buildWsUrl(machine)
        lastConnectUrl.value = wsUrl.replace(Regex("token=[^&]+"), "token=…")
        // Validate: reject obviously bad URLs (e.g. stale serialised objects) without
        // using toHttpUrlOrNull() — OkHttp accepts ws/wss directly in Request.Builder
        // but toHttpUrlOrNull() rejects them, causing a false "Invalid pairing URL".
        if (machine.url.contains("PairedMachine(") || !wsUrl.startsWith("ws")) {
            _state.value = ConnState.Error("Invalid pairing URL — scan a fresh QR code")
            return
        }
        val req = Request.Builder()
            .url(wsUrl)
            .addHeader("x-device-id", deviceId())
            .addHeader("x-device-fingerprint", androidId())
            .build()
        client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                activeWs = ws
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
                activeWs = null
                if (userDisconnected) {
                    _state.value = ConnState.Idle
                    return
                }
                _state.value = ConnState.Error(t.message ?: "connection failed")
                scheduleReconnect(machine, attempt)
            }
            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                activeWs = null
                if (userDisconnected || code == 1000) {
                    _state.value = ConnState.Disconnected
                    return
                }
                _state.value = ConnState.Disconnected
                scheduleReconnect(machine, attempt)
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                activeWs = null
            }
        })
    }

    private fun scheduleReconnect(machine: PairedMachine, attempt: Int) {
        if (userDisconnected) return
        reconnectJob?.cancel()
        val nextAttempt = attempt + 1
        val delayMs = minOf(30_000L, 1_000L * (1 shl minOf(nextAttempt - 1, 4)))
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!userDisconnected && lastMachine?.id == machine.id) {
                // Use the most-recent machine record — may have a refreshed token
                val current = PocketcodeApp.instance.machines.get(machine.id) ?: machine
                openWebSocket(current, nextAttempt)
            }
        }
    }

    fun send(jsonMsg: String) {
        val s = _state.value
        if (s is ConnState.Connected) s.ws.send(jsonMsg)
    }

    fun disconnect() {
        userDisconnected = true
        reconnectJob?.cancel()
        val s = _state.value
        if (s is ConnState.Connected) {
            s.ws.close(1000, "user")
        }
        activeWs?.close(1000, "user")
        activeWs = null
        _state.value = ConnState.Idle
        // Drop live agent cards so a later session doesn't inherit stale Running/Waiting.
        for (tabId in AgentLiveTracker.states.value.keys.toList()) {
            Notifier.clearLive(ctx, tabId)
        }
        AgentLiveTracker.clearAll()
        com.remotedev.pocketcode.service.SessionForegroundService.stop(ctx)
    }

    /** Pull a short human string out of the server payload blob (`{"snippet":"..."}` or raw). */
    private fun extractApprovalSnippet(payloadStr: String): String {
        return runCatching {
            val el = json.parseToJsonElement(payloadStr)
            el.jsonObject["snippet"]?.jsonPrimitive?.content
                ?: el.jsonObject["summary"]?.jsonPrimitive?.content
                ?: payloadStr
        }.getOrDefault(payloadStr).take(200)
    }

    private fun buildWsUrl(machine: PairedMachine): String {
        val base = machine.url.trim()
        if (base.contains("token=")) return base
        if (machine.token.isEmpty()) return base
        // toHttpUrlOrNull() rejects ws/wss schemes — swap to http/https for parsing,
        // then restore the original scheme after appending the token.
        val isWss = base.startsWith("wss://")
        val isWs  = base.startsWith("ws://")
        val parseable = when {
            isWss -> base.replaceFirst("wss://", "https://")
            isWs  -> base.replaceFirst("ws://",  "http://")
            else  -> base
        }
        val parsed = parseable.toHttpUrlOrNull() ?: return base
        val withToken = parsed.newBuilder()
            .addQueryParameter("token", machine.token)
            .build()
            .toString()
        return when {
            isWss -> withToken.replaceFirst("https://", "wss://")
            isWs  -> withToken.replaceFirst("http://",  "ws://")
            else  -> withToken
        }
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
