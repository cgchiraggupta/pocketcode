# PocketCode (RemoteDev) — Android App

Kotlin + Jetpack Compose, Material 3. minSdk 26, target 35.

## Module split

- `pairing/` — QR scan (CameraX + zxing), multi-machine store (EncryptedSharedPreferences)
- `connection/` — OkHttp WebSocket, reconnect, status flow
- `terminal/` — xterm-style ANSI 16-color renderer, multi-tab, extra keys row
- `files/` — tree, viewer stub (drop in compose-code-editor / sora-editor)
- `git/` — status panel, commit/push/branch switch
- `agent/` — timeline parser, cost tracker
- `notifications/` — local notifs with Approve/Reject/View action buttons
- `voice/` — SpeechRecognizer wrapper
- `widget/` — home-screen widget
- `persistence/` — Room DB for encrypted transcripts and event log

## Build

```bash
cd android
./gradlew :app:assembleDebug
```

## What's stubbed (intentional, see notes)

- `git/GitPanelScreen` actions call server via WS — wire to `ConnectionManager.send` after pairing.
- `files/CodeViewerStub` — replace with a real Compose code editor.
- `agent/AgentTimelineScreen` + `cost/` parser — heuristic; production needs vendor-specific patterns.
- `notifications/` action receiver — wires to WS send in v2.
- Biometric prompt on launch — declared dep, not yet wired in `MainActivity`.

## See also

- `../docs/ARCHITECTURE.md`
- `../docs/SECURITY.md`
