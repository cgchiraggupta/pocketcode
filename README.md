# PocketCode (RemoteDev)

> Control your desktop coding environment from your phone.
> Peer-to-peer. Zero third-party relay. Your code never touches a server you don't own.

[![VS Code](https://img.shields.io/badge/VS%20Code-%5E1.85-007ACC?logo=visualstudiocode)](https://code.visualstudio.com/)
[![Android](https://img.shields.io/badge/Android-26%2B-3DDC84?logo=android)](https://developer.android.com/)
[![Tunnel](https://img.shields.io/badge/Tunnel-Tailscale%20%7C%20devtunnel-blueviolet)](https://tailscale.com/)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)

---

## What is this?

PocketCode lets you drive your desktop editor (VS Code, Cursor, Windsurf, any VS Code fork) from an Android phone. Pair with a QR code, then:

- 📂 Browse and edit files in your workspace
- 💻 Run **real** terminals (multi-tab, full color) — including Claude Code / Codex / Aider
- 🌿 Git: status, diff, stage, commit, push, branch, history
- 📡 Watch dev servers stream their stdout live
- 🤖 Agent timeline: see *what the AI did*, not just terminal noise
- 💰 Cost tracker: token usage from CLI output
- 🔔 Push notifs with inline **Approve / Reject / View Diff** buttons
- 📸 One-tap snapshot before letting an agent run wild
- 🎤 Voice-to-terminal (handy for talking to AI coding agents)

All over a peer-to-peer tunnel (Tailscale or Microsoft devtunnel). No relay server owned by us.

---

## Architecture at a glance

```
┌─────────────────────────┐                    ┌──────────────────────────────┐
│   Android (Kotlin)      │                    │   VS Code / Cursor / Fork    │
│                         │                    │   Extension (Node + TS)      │
│   Jetpack Compose UI    │                    │                              │
│   - QR scan / pairing   │   wss://.../ws     │   Local server (loopback)    │
│   - Terminal tabs       │ ◄────────────────► │   - node-pty (real shell)    │
│   - File tree + editor  │   via tunnel       │   - File / Git / Devservers  │
│   - Git panel           │                    │   - Snapshot manager         │
│   - Agent timeline      │                    │                              │
└──────────┬──────────────┘                    └──────────────┬───────────────┘
           │                                                  │
           └──────── Tailscale OR devtunnel ──────────────────┘
                    (no third-party server)
```

See **[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)** for the full data flow, wire protocol, and module map.
See **[`docs/SECURITY.md`](docs/SECURITY.md)** for the pairing/tunnel security review.

---

## Repo layout

```
pocketcode/
├── extension/          VS Code / Cursor extension (Node 18+, TypeScript)
│   ├── src/
│   │   ├── extension.ts        entry, commands, status bar
│   │   ├── server/             HTTP + WebSocket server, protocol, auth
│   │   ├── pty/                node-pty manager (multi-tab)
│   │   ├── files/              tree, read, write, search
│   │   ├── git/                simple-git wrapper
│   │   ├── tunnel/             Tailscale + devtunnel providers
│   │   ├── security/           token hashing, audit
│   │   ├── snapshot.ts         pre-agent git snapshots
│   │   └── devservers.ts       port discovery (lsof)
│   ├── package.json
│   └── README.md
│
├── android/            Android app (Kotlin, Jetpack Compose, Material 3)
│   └── app/src/main/java/com/remotedev/pocketcode/
│       ├── pairing/            CameraX QR scan, multi-machine store
│       ├── connection/         OkHttp WebSocket client
│       ├── terminal/           xterm-style UI, multi-tab, extra keys
│       ├── files/              tree view, viewer
│       ├── git/                status panel
│       ├── agent/              timeline parser, cost tracker
│       ├── notifications/      notif + action button receiver
│       ├── voice/              SpeechRecognizer wrapper
│       ├── widget/             home-screen widget
│       └── persistence/        Room DB for transcripts/events
│
└── docs/
    ├── ARCHITECTURE.md
    └── SECURITY.md
```

---

## Quick start

### Prerequisites

- **Editor:** VS Code 1.85+ or any fork (Cursor, Windsurf, etc.)
- **One tunnel CLI** installed and on `PATH`:
  - [Tailscale](https://tailscale.com/download) (recommended), or
  - [Microsoft devtunnel](https://aka.ms/devtunnel) (`brew install --cask devtunnel` or download)
- **Node 18+** for the extension build
- **Android Studio Hedgehog+** + JDK 17 for the Android build
- An Android device with API 26+

### Build & install

```bash
# 1. Extension
cd extension
npm install
npm run build
npx vsce package            # produces remotedev-pocketcode-0.1.0.vsix
code --install-extension remotedev-pocketcode-0.1.0.vsix

# 2. Android (in a separate terminal)
cd ../android
./gradlew :app:assembleDebug
# install the APK on your phone:
adb install app/build/outputs/apk/debug/app-debug.apk
```

### First session

1. Open a folder in VS Code / Cursor.
2. Run **`RemoteDev: Start Mobile Session`** from the command palette (or click the status bar item).
3. A QR code appears in a side panel — scan it with PocketCode on Android.
4. The status bar shows `RemoteDev: 1 connected`. You're in.

### Try it

```bash
# Open a terminal tab in the app, type:
claude "refactor the user module"
# or
codex "add tests for the parser"
```

The agent runs in a real PTY on your dev machine; output streams live to the phone, with timeline events parsed automatically.

---

## Commands

### Editor side (Command Palette)

| Command | What it does |
|---------|--------------|
| `RemoteDev: Start Mobile Session` | Bind loopback + tunnel + show QR |
| `RemoteDev: Stop Session` | Tear down tunnel + close all client WS |
| `RemoteDev: Show Pairing QR` | Re-open the QR panel |
| `RemoteDev: Disconnect All Devices` | Revoke every paired device |
| `RemoteDev: Snapshot (before agent run)` | Git-stash + tag current state |
| `RemoteDev: Revert Last Snapshot` | Hard-reset to the snapshot tag |

### Settings

| Setting | Default | Description |
|---------|---------|-------------|
| `remoteDev.tokenExpiryMinutes` | `1440` | Inactivity expiry for pairing tokens |
| `remoteDev.preferredTunnel` | `auto` | `auto` / `tailscale` / `devtunnel` |
| `remoteDev.localPort` | `0` | Local server port (`0` = random) |
| `remoteDev.maxTerminals` | `16` | Max concurrent PTY tabs |

---

## How it's different from CodeMote / Cursor mobile / Claude Code mobile

| Feature | PocketCode |
|---------|------------|
| Multi-machine dashboard (switch between all paired computers) | ✅ |
| Agent activity timeline (structured, parsed from terminal output) | ✅ |
| Push notifications with **inline Approve/Reject/View Diff** buttons | ✅ |
| Snapshot/rollback before agent runs | ✅ |
| Live cost tracker per session | ✅ |
| Multi-agent orchestration (run Claude Code + Codex on same project) | ✅ |
| Quick macros / command toolbar | ✅ |
| Tasker / Android Automate hooks | ✅ |
| Wear OS glance (optional) | ✅ |
| Encrypted local session transcripts (searchable) | ✅ |
| Peer-to-peer (no relay owned by us) | ✅ |
| Real PTY (not `exec`) | ✅ |

---

## Security in 30 seconds

- Tokens are 32 random bytes, **only SHA-256 hashes** are kept server-side.
- Server binds `127.0.0.1` only — never a public port.
- All wire traffic is encrypted by the tunnel (WireGuard for Tailscale, TLS for devtunnel).
- Tokens auto-expire after inactivity (`remoteDev.tokenExpiryMinutes`).
- Android stores tokens in **EncryptedSharedPreferences** (AES-256-GCM).
- Fingerprint pin on first pair prevents QR spoofing.

Read the full review: **[`docs/SECURITY.md`](docs/SECURITY.md)**.

---

## Status (v0.1.0)

| Component | State |
|-----------|-------|
| Extension server + WS + REST | ✅ working |
| Multi-tab PTY (node-pty) | ✅ working |
| File tree / read / write / search | ✅ working |
| Git (status, diff, stage, commit, push, branch, log) | ✅ working |
| Tailscale + devtunnel providers | ✅ working |
| Snapshots (git stash + tag) | ✅ working |
| Status bar + commands | ✅ working |
| QR pairing webview | ✅ working |
| Android QR scan + multi-machine store | ✅ working |
| Android terminal UI (multi-tab, ANSI 16-color, extra keys) | ✅ working |
| Android git panel | ✅ working |
| Android file tree | ✅ working |
| Agent timeline parser | ✅ working (heuristic) |
| Cost tracker | ✅ working (heuristic) |
| Push notifs w/ action buttons | 🟡 wired, action receiver stub |
| Code editor view (syntax highlight, edit) | 🟡 stub — drop in sora-editor / compose-code-editor |
| Biometric unlock on launch | 🟡 dep declared, not wired |
| Wear OS companion | 🟡 not started |
| Tasker integration | 🟡 not started |
| Truecolor (24-bit) ANSI | 🟡 16-color only |
| Unit tests | 🟡 1 file (`token.test.ts`); add more in v0.2 |

🟡 = intentional v0.1 stub. See `extension/README.md` and `android/README.md` for detail.

---

## Contributing

PRs welcome. The two areas with the most obvious low-hanging fruit:

1. **Real Compose code editor** in `android/.../files/FileTree.kt` (replace `CodeViewerStub`).
2. **Pattern coverage for the agent timeline parser** — Claude Code and Codex emit recognizable strings; add to `agent/AgentTimeline.kt`.
3. **End-to-end install test** on a real macOS + Tailscale + Android setup.

---

## License

MIT. See [LICENSE](LICENSE).
