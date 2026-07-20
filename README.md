# PocketCode

<!-- Documentation touchpoint: README-only marker for non-functional change verification. -->

**Control your code editor from your phone.** Peer-to-peer, zero relay.

Scan a QR code and get a real terminal, file browser, Git controls, and live agent timeline on your phone.

## Features

- **Real PTY** — Full `node-pty` terminals with multiple tabs and ANSI color
- **Files** — Tree navigation, read/write files directly from the phone
- **Git** — Status, diff viewer, stage, commit, push, pull, branches, and log
- **Dev Servers** — Stream logs from local development servers
- **Snapshots** — One-tap pre-agent snapshots with instant revert
- **Agent Timeline** — Live activity + cost tracking for Claude, Codex, Aider, etc.
- **Notifications** — Actionable local notifications (Approve / Reject / View Diff)
- **Multi-machine** — Pair and switch between several computers
- **Flexible tunneling** — Tailscale, devtunnel, or SSH reverse tunnels

## Requirements

- A tunnel provider:
  - [Tailscale](https://tailscale.com) (recommended)
  - [devtunnel](https://aka.ms/devtunnel)
  - Any SSH target that supports `-R` reverse forwarding
- VS Code, Cursor, or compatible editor
- Android device (API 26+)

## Quick Start

### 1. Install the VS Code extension

```bash
cd extension
npm install
npm run build
npx vsce package
code --install-extension remotedev-pocketcode-*.vsix
```

### 2. Set up a tunnel

```bash
# Option A (recommended)
brew install tailscale

# Option B
# Follow https://aka.ms/devtunnel

# Option C (SSH)
# Any host you can reach with `ssh -R`
```

### 3. Build and install the Android app

```bash
cd android
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 4. Connect from your phone
//hello 




1. Open a project in VS Code
2. Run **RemoteDev: Start Mobile Session** (or click the status bar)
3. Scan the QR code using the PocketCode Android app
4. Use the full remote session — terminal, files, Git, and agent oversight

## SSH Tunnel Configuration

Add to your VS Code settings when using SSH:

```json
{
  "remoteDev.preferredTunnel": "ssh",
  "remoteDev.sshTarget": "user@your-host.example.com",
  "remoteDev.sshRemotePort": 0
}
```

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Security Model](docs/SECURITY.md)
- [Extension details](extension/README.md)
- [Android details](android/README.md)

## License

MIT
