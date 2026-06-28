# PocketCode (RemoteDev)

Control your desktop coding environment (VS Code / Cursor / any VS Code fork) from an Android phone.
Peer-to-peer, zero third-party relay.

## Layout

```
pocketcode/
  extension/    VS Code / Cursor extension (Node + TS)
  android/      Android app (Kotlin + Jetpack Compose)
  docs/
    ARCHITECTURE.md
    SECURITY.md
```

## Quick start

```bash
# Extension
cd extension && npm install && npm run build && npx vsce package
code --install-extension remotedev-pocketcode-0.1.0.vsix

# Android
cd android && ./gradlew :app:assembleDebug
```

Requires either **Tailscale** or **devtunnel** on the dev machine's PATH.

## Status

v0.1 skeleton — extension server, PTY, file, git, tunnel providers, pairing all wired.
Android app has all modules with working scaffolding for pairing + terminal UI.
See `extension/README.md` and `android/README.md` for what is and isn't finished.
