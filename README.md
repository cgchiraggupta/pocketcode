# PocketCode

Control your editor from your phone. Peer-to-peer, zero relay.

Scan a QR, get a terminal. That's it.

## Setup

```bash
# 1. Install a tunnel CLI
brew install tailscale      # or: https://aka.ms/devtunnel

# 2. Install the extension
cd extension && npm install && npm run build && npx vsce package
code --install-extension remotedev-pocketcode-0.1.0.vsix

# 3. Build Android
cd ../android && ./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Use

1. `RemoteDev: Start Mobile Session`
2. Scan QR with the app
3. Run `claude` or `codex` in a terminal tab. It just works.

## What's there

- Real PTY (node-pty), multi-tab, full color
- File tree, search, read/write
- Git: status, diff, stage, commit, push, branch, log
- Dev server stream watcher
- Snapshots (one-tap rollback before letting an agent run)
- Agent timeline + cost tracker (parsed from CLI output)
- Push notifs with Approve/Reject/View Diff buttons
- Multi-machine: pair several computers, switch between them

## What's not

- Code editor on Android is a stub. Drop in sora-editor / compose-code-editor.
- 16-color ANSI only. Truecolor later.
- Wear OS, Tasker hooks, biometric prompt — declared but unwired.
- 1 unit test. Add more.

See `extension/README.md` and `android/README.md` for the boring details, `docs/SECURITY.md` for the threat model.

MIT.
