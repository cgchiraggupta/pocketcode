# RemoteDev (PocketCode) — VS Code Extension

Peer-to-peer remote control of this editor from the PocketCode Android app.

## Install (from source)

```bash
cd extension
npm install
npm run build
npx vsce package          # produces .vsix
code --install-extension remotedev-pocketcode-0.1.0.vsix
```

## Requirements

- One of: **Tailscale** (https://tailscale.com) or **devtunnel** (https://aka.ms/devtunnel) installed and on PATH.
- A workspace folder open.

## Usage

1. `RemoteDev: Start Mobile Session` (or click the status bar item).
2. Scan the QR with the Android app.
3. Open files, run terminals, commit, push, watch dev servers — all from your phone.

## Notes

- Local server binds `127.0.0.1:<random>` only.
- Tokens are SHA-256 hashed; raw token never persisted.
- `RemoteDev: Disconnect All Devices` revokes every paired device.

See `../docs/ARCHITECTURE.md` and `../docs/SECURITY.md`.
