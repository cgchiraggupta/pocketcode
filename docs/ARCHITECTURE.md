# RemoteDev — Architecture

> Phone ⇄ Editor, peer-to-peer, zero third-party relay.

## High-level

```
┌────────────────────────────────┐                       ┌─────────────────────────────────┐
│       Android (Kotlin)         │                       │   VS Code / Cursor / Fork      │
│                                │                       │   Extension (Node + TS)        │
│  ┌──────────────────────────┐  │   WebSocket + REST    │  ┌──────────────────────────┐  │
│  │ UI (Jetpack Compose)     │  │ ◄───────────────────► │  │ Local HTTP/WS server     │  │
│  │  - Pairing (QR)          │  │   TLS via tunnel      │  │   :PORT (loopback only)  │  │
│  │  - Terminal tabs         │  │                       │  └────────────┬─────────────┘  │
│  │  - File tree             │  │                       │               │                │
│  │  - Git panel             │  │                       │               ▼                │
│  │  - Agent timeline        │  │                       │  ┌──────────────────────────┐  │
│  └────────────┬─────────────┘  │                       │  │  Domain managers         │  │
│               │                │                       │  │  - PTY (node-pty)        │  │
│  ┌────────────▼─────────────┐  │                       │  │  - Files                 │  │
│  │  Client (OkHttp/WS)      │  │                       │  │  - Git                   │  │
│  └────────────┬─────────────┘  │                       │  │  - Dev-server watcher    │  │
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
                       │           Tunnel (Tailscale OR devtunnel)  │
                       └──────────────────────────────────────────────┘
                                       (peer-to-peer)
```

## Wire protocol

Single WebSocket at `wss://<host>:<port>/ws?token=...` carries **all** realtime traffic:
terminal I/O, file change events, dev-server log lines, agent activity notifications.

REST surface for non-streaming ops lives at `https://<host>:<port>/api/*`:
- `GET  /api/health`
- `GET  /api/fs/tree?path=...`
- `GET/POST/PUT/DELETE /api/fs/file?path=...`
- `POST /api/fs/mkdir|rename`
- `GET/POST /api/git/*`  (status, diff, log, branch, stage, commit, push, pull)
- `GET  /api/devservers`
- `GET  /api/workspace/list`, `POST /api/workspace/switch`
- `POST /api/terminal/open`, `WS  /api/terminal/:id/io` (raw stream)
- `POST /api/snapshot/create`, `POST /api/snapshot/revert/:id`

Messages are JSON with discriminated `type` field. See `extension/src/server/protocol.ts`.

## Connection lifecycle

1. **Start session** (`RemoteDev: Start Mobile Session`):
   - Detect tunnel binary (tailscale, then devtunnel). Auto-start tunnel if not up.
   - Bind local server on `127.0.0.1:RANDOM_PORT` (loopback only).
   - Forward the port via the tunnel → get a public-ish hostname.
   - Generate a one-time pairing token (32 random bytes, base64url).
   - Show QR code in a Webview panel: `{ url, token, fingerprint }`.
   - Token stored in-memory only; never written to disk in plaintext.
2. **Pair** (Android):
   - Scan QR → `wss://.../?token=...`
   - Server validates token, marks `bound` to that device fingerprint, sets expiry (default 24h sliding).
3. **Operate**: all ops stream over the single WS. PTY I/O is multiplexed by tab id.
4. **Disconnect**: server revokes token; client WS closes. Auto-expire if no traffic for N minutes.

## Why peer-to-peer

The user explicitly demanded **no third-party relay**. We support two tunnels:

| Tunnel        | Trust model                              | Self-hostable |
|---------------|-------------------------------------------|---------------|
| Tailscale     | Your own tailnet (WireGuard, DERP relay) | Yes (Headscale) |
| devtunnel     | Microsoft-hosted, free for dev           | Yes (it's a binary) |

Both forward a TCP port to localhost. The extension itself never opens a public socket.

## Module layout (extension)

```
extension/src/
  extension.ts          # activation, commands, status bar
  server/
    index.ts            # http + ws bootstrap
    protocol.ts         # wire types (shared with android)
    routes/
      fs.ts
      git.ts
      terminal.ts
      workspace.ts
      devservers.ts
      snapshot.ts
    auth.ts             # token issuance, binding, expiry
  pty/
    manager.ts          # node-pty lifecycle, multi-tab
  git/
    manager.ts          # simple-git wrapper
  files/
    manager.ts          # vscode.workspace.fs + tree walk
  tunnel/
    index.ts            # provider interface
    tailscale.ts
    devtunnel.ts
    detect.ts
  security/
    token.ts            # crypto.randomBytes, hashing
    audit.ts            # log every command to encrypted transcript
  ui/
    qrpanel.ts          # Webview QR
    statusbar.ts
```

## Module layout (android)

```
android/app/src/main/java/com/remotedev/pocketcode/
  MainActivity.kt
  PocketcodeApp.kt
  pairing/        # QR scan, manual paste, multi-machine
  connection/     # client, reconnect, status
  terminal/       # terminal view, xterm-style, extra keys row
  files/          # tree, viewer, editor
  git/            # diff, history, branch
  agent/          # timeline, cost, multi-agent orchestration
  notifications/  # FCM-free local notifs with action buttons
  voice/          # SpeechRecognizer wrapper
  widget/         # homescreen widget
  persistence/    # Room, encrypted transcripts
```

## Cross-cutting concerns

- **Token storage**: server keeps `sha256(token)` only; raw token never persisted. Android keeps token in `EncryptedSharedPreferences`.
- **Transcripts**: terminal bytes encrypted with AES-GCM using a per-session key derived from the pairing token; saved to Room.
- **Activity timeline**: pattern parser over terminal output — see `extension/src/agent/parse.ts` (regex for `claude-code`/`codex`/`aider` output markers).
- **Snapshots**: pre-agent `git stash push` + tag, reverted via `git stash pop` or tag reset.
- **Cost tracker**: parse token counts from CLI stdout (best-effort, see `agent/cost.ts`).
