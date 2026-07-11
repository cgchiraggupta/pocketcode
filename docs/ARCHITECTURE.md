# RemoteDev — Architecture

> Phone ⇄ Editor, peer-to-peer, zero third-party relay.

## High-level

```
┌────────────────────────────────┐                       ┌─────────────────────────────────┐
│       Android (Kotlin)         │                       │   VS Code / Cursor / Windsurf   │
│                                │                       │   Extension (Node + TS)         │
│  ┌──────────────────────────┐  │   WebSocket           │  ┌──────────────────────────┐  │
│  │ UI (Jetpack Compose)     │  │ ◄───────────────────► │  │ Local HTTP/WS server     │  │
│  │  - Pairing (QR)          │  │   TLS via tunnel      │  │   :PORT (loopback only)  │  │
│  │  - Terminal tabs          │  │                       │  └────────────┬─────────────┘  │
│  │  - File tree             │  │                       │               │                │
│  │  - Git panel             │  │                       │               ▼                │
│  │  - Agent timeline        │  │                       │  ┌──────────────────────────┐  │
│  └────────────┬─────────────┘  │                       │  │  Domain managers         │  │
│               │                │                       │  │  - PTY (child_process)   │  │
│  ┌────────────▼─────────────┐  │                       │  │  - Files                 │  │
│  │  Client (OkHttp/WS)      │  │                       │  │  - Git                   │  │
│  └────────────┬─────────────┘  │                       │  │  - Dev-server registry   │  │
│               │                │                       │  │  - Tunnel                │  │
│  ┌────────────▼─────────────┐  │                       │  └──────────────────────────┘  │
│  │  SpeechRecognizer / etc  │  │                       │               │                │
│  └──────────────────────────┘  │                       │               ▼                │
│                                │                       │  ┌──────────────────────────┐  │
│                                │                       │  │  System                  │  │
│                                │                       │  │  /bin/zsh, node, git...  │  │
│                                │                       │  └──────────────────────────┘  │
└────────────────────────────────┘                       └─────────────────────────────────┘
                       │                                              │
                       │           Tunnel (devtunnel OR Tailscale)  │
                       └──────────────────────────────────────────────┘
                                       (peer-to-peer)
```

## Wire protocol

**All realtime traffic travels over a single WebSocket** at
`wss://<host>:<port>/ws?token=...`.  This includes: terminal I/O, file change
events, dev-server log lines, agent activity notifications, agent approval
decisions, and proactive token refresh.

**There is no REST API surface** beyond `GET /api/health`. The `routes/` module
described in earlier documentation was never built; everything goes through the
WS discriminated union in `extension/src/server/protocol.ts`.

### Message categories (protocol.ts)

| Prefix           | Direction     | Purpose                                       |
|------------------|---------------|-----------------------------------------------|
| `term.*`         | bidirectional | PTY lifecycle, I/O, resize, tab list          |
| `fs.*`           | client→server | File tree, read, write, mkdir, rename, delete |
| `git.*`          | client→server | Status, diff, stage, commit, push, pull, log  |
| `devservers`     | client→server | List running processes (managed + unmanaged)  |
| `devserver.start`| client→server | Launch a managed dev-server process           |
| `devserver.stop` | client→server | Kill a managed dev-server process             |
| `devserver.log`  | client→server | Stream stdout/stderr of a managed process     |
| `workspace.*`    | client→server | List and switch VS Code workspace folders     |
| `snapshot.*`     | client→server | Pre-agent stash snapshots                    |
| `agent.approve`  | client→server | Write "y\n" to the active PTY (agent stdin)  |
| `agent.reject`   | client→server | Write "n\n" to the active PTY (agent stdin)  |
| `agent.event`    | server→client | Agent activity event (pushed proactively)     |
| `token.refresh`  | server→client | Proactive token renewal before expiry         |
| `pong`           | client→server | Keepalive response                            |

## Connection lifecycle

1. **Start session** (`RemoteDev: Start Mobile Session`):
   - Detect tunnel binary (devtunnel by default, then tailscale, then ssh).
   - Generate a stable tunnel name per workspace (persisted in `workspaceState`)
     so devtunnel reuses the same `*.devtunnels.ms` hostname across restarts.
   - Bind local server on `127.0.0.1:RANDOM_PORT` (loopback only).
   - Forward the port via the tunnel → get a public hostname.
   - Generate a one-time pairing token (32 random bytes, base64url).
   - Show QR code in a Webview panel: `{ url, token, fingerprint }`.
   - Token stored in-memory only; never written to disk in plaintext.
2. **Pair** (Android):
   - Scan QR → `wss://.../?token=...`
   - Server validates token, marks `bound` to that device fingerprint, sets expiry (default 7 days).
   - Token persisted to `EncryptedSharedPreferences` on Android.
3. **Operate**: all ops stream over the single WS. PTY I/O is multiplexed by tab id.
   Voice input in the terminal app routes through `term.input` → PTY stdin, so
   speech-recognised text reaches agent CLIs (Claude Code, Aider, etc.) as real input.
4. **Token refresh**: server sends `token.refresh` before expiry. Client silently
   updates its persisted token. No QR re-scan required.
5. **Agent approval**: notification Approve/Reject buttons send `agent.approve` or
   `agent.reject` over WS; the server writes "y\n" / "n\n" to the active PTY.
6. **Disconnect**: server revokes token; client WS closes. Auto-expire after 7 days
   of inactivity (configurable via `remoteDev.tokenExpiryMinutes`).

## Dev-server log streaming

Dev servers fall into two categories:

- **Unmanaged** — already running processes discovered via `lsof`. We can report
  PID/port/command but cannot stream their stdout (we don't hold the fd). The
  `devserver.log` response for these includes a tip to start via `devserver.start`.
- **Managed** — launched via the `devserver.start` WS message. The server holds the
  `ChildProcess` reference and can pipe stdout/stderr in real-time. Send
  `devserver.log { port, follow: true }` to subscribe; chunks arrive as
  `devserver.log { port, data }` messages until the subscription is cancelled or
  the WS closes.

## Why peer-to-peer

The user explicitly demanded **no third-party relay**. We support two tunnels:

| Tunnel        | Trust model                              | Self-hostable |
|---------------|-------------------------------------------|---------------|
| devtunnel     | Microsoft-hosted, free for dev           | n/a (binary)  |
| Tailscale     | Your own tailnet (WireGuard, DERP relay) | Yes (Headscale) |
| SSH           | Your own target machine                  | Yes           |

All forward a TCP port to localhost. The extension itself never opens a public socket.

## Module layout (extension)

```
extension/src/
  extension.ts          # activation, commands, status bar, stable tunnel-name persistence
  server/
    index.ts            # http + ws bootstrap, dispatch, token-refresh on connect
    protocol.ts         # wire types (copy into android if you need them there too)
    auth.ts             # token issuance, binding, expiry, proactive renewal
  pty/
    manager.ts          # child_process + script(1) PTY shim, multi-tab
  git/
    manager.ts          # simple-git wrapper
  files/
    manager.ts          # vscode.workspace.fs + tree walk
  devservers.ts         # lsof discovery + DevServerRegistry (managed processes)
  tunnel/
    index.ts            # provider interface + auto-detect
    tailscale.ts        # serve mode
    tailscale-ip.ts     # direct IP mode
    devtunnel.ts        # named/persistent tunnel (--name <stable-id>)
    ssh.ts              # SSH reverse tunnel
  security/
    token.ts            # crypto.randomBytes, hashing
  snapshot.ts           # pre-agent git stash snapshots
```

## Module layout (android)

```
android/app/src/main/java/com/remotedev/pocketcode/
  MainActivity.kt         # biometric unlock, theme (ClaudeColors)
  PocketcodeApp.kt        # singleton: connection, machines, savedCommands, db
  pairing/                # QR scan, manual paste, multi-machine, token persistence
  connection/             # WS client, reconnect/backoff, SharedFlow dispatch, token.refresh handler
  terminal/               # terminal view, ANSI renderer, extra-keys bar, SavedCommandBar
  commands/               # SavedCommands store + one-tap bar UI
  files/                  # tree, viewer, editor
  git/                    # diff, history, branch
  agent/                  # AgentTimeline (wired to PTY data stream), CostTracker
  notifications/          # local notifs with Approve/Reject/View-diff action buttons
  voice/                  # SpeechRecognizer wrapper (result routes to term.input → PTY stdin)
  widget/                 # homescreen widget
  service/                # SessionForegroundService (Doze-safe background WS)
  persistence/            # Room: agent event history
```

## Cross-cutting concerns

- **Token storage**: server keeps `sha256(token)` only; raw token never persisted. Android keeps token in `EncryptedSharedPreferences`.
- **Token refresh**: server proactively sends `token.refresh` when <6h remain on the expiry window. Client updates EncryptedSharedPreferences silently.
- **Agent approval**: `agent.approve` / `agent.reject` WS messages are handled server-side by writing "y\n" / "n\n" to the most recently active alive PTY tab.
- **Agent timeline**: `AgentEventParser.parse()` runs on every `term.data` chunk in `ConnectionManager`. Parsed events flow into `agentEvents` StateFlow which `AgentTimelineScreen` reads.
- **Voice input**: `VoiceInput.text` changes trigger `onInput(text + "\n")` in `TerminalScreen`, which calls the caller-supplied lambda. In `Root.kt` that lambda sends a `term.input` WS message to the active PTY tab — identical path to typed text.
- **Saved commands**: stored in plain SharedPreferences (no secrets). `SavedCommandBar` in `TerminalScreen` runs them via the same `onInput` path.
- **Dev-server streaming**: managed servers started via `devserver.start` are tracked in `DevServerRegistry`; `devserver.log { follow: true }` pipes their stdout/stderr per-client via registered listeners cleaned up on WS close.
- **Snapshots**: pre-agent `git stash push` + tag, reverted via `git stash pop` or tag reset.
- **Cost tracker**: parse token counts from CLI stdout (best-effort heuristic in `CostTracker.kt`).
- **Foreground service**: `SessionForegroundService` holds a foreground service notification while the WS is connected, preventing Android Doze from killing the process. Started by `ConnectionManager.connect()`, stopped by `disconnect()`.
