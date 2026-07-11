# PocketCode — Session Progress Log

_This file is the resume point. If a session gets cut, read this file first, then `CONTEXT.md` for full architecture._

## Reference: CodeMote feature-parity gaps identified (2026-07-10)

Source: https://www.producthunt.com/products/codemote-remote-control-for-any-ai

| # | Gap | Status |
|---|---|---|
| 1 | Per-agent addressable approve/reject (was hitting "most recent PTY" instead of the right tab) | ✅ **DONE** |
| 1b | (found while fixing #1) Server never actually emitted `agent.event` at all — no approval-prompt detection existed. This was the real root cause, bigger than originally scoped. | ✅ **DONE** |
| 2 | Reconnect resume / scrollback replay correctness | ✅ **DONE** |
| 3 | Live-updating notification (lock-screen-style terminal + auto waiting state) | ✅ **DONE** |
| 4 | Headless CLI mode (`npx`-style, no VS Code needed) | ✅ **DONE** (MVP) |
| 5 | ngrok / Cloudflare tunnel options (CodeMote has these, PocketCode only has devtunnel/tailscale/tailscale-ip/ssh) | ⏳ NOT STARTED (noted, not prioritized yet) |

**Priority order chosen (senior-eng call, user delegated authority):** 1 → 2 → 3 → 4. Item 5 deprioritized.

---

## What was actually done — Item #1 + #1b (COMPLETE)

Root cause found while starting on "fix approve/reject targeting": the whole notification-driven approval flow was **unwired end-to-end**. The PTY-write mechanism (`y\n`/`n\n` on approve/reject) worked, but nothing ever detected "agent is waiting for input" server-side and pushed an `agent.event` to tell the phone to notify. `Notifier.show()` was always called with a hardcoded `"agent-session"` string — there was no way to distinguish between multiple running terminals/agents at all.

### Files changed

- **`extension/src/server/protocol.ts`** — `agent.event` message now carries a `tab: string` field so the client knows which terminal the event belongs to.
- **`extension/src/agent-detector.ts`** (NEW) — `ApprovalDetector` class. Regex-based, agent-agnostic y/n-prompt detection scanning PTY output. 15s per-tab cooldown to avoid re-firing on repeated prompts. Exposes `clear()` (single tab) and `forget()` (all state for a tab, called on exit).
- **`extension/src/server/index.ts`** — Wired `ApprovalDetector` into `pty.on('data')` to broadcast `agent.event` when a prompt is detected. `pty.on('exit')` calls `detector.forget()` for cleanup. **Real bug fixed**: `agent.approve` / `agent.reject` handlers now target `msg.session` (the specific tab the client is acting on) instead of always resolving to "most recently active alive tab" — this was silently approving/rejecting the wrong terminal when multiple agents were running. Falls back to most-recent only if the requested tab is no longer alive.
- **`android/.../agent/AgentTimeline.kt`** — `AgentEvent` data class gained `tab: String = ""`. `AgentEventParser.parse()` now takes a `tab` param and threads it through.
- **`android/.../notifications/Notifier.kt`** — Added `showInfo()` for non-actionable notifications. Approve/reject action buttons are now only shown for real `awaiting_approval` kind events; everything else (completion, failure, generic info) uses the plain info notification.
- **`android/.../connection/ConnectionManager.kt`** — `agent.event` handler now uses the real `tab` from the parsed event (was hardcoded `"agent-session"` before), and branches actionable vs. info notification based on `kind`. `term.data` handler passes `tabId` into the parser. `term.exit` now also fires a completion/failure notification (this is partial coverage toward gap #3, done as a natural side effect here).

### Verification done this session

- `tsc --noEmit` — clean.
- Extension `npm run build` + existing test suite — pass.
- Android `./gradlew :app:compileDebugKotlin` — BUILD SUCCESSFUL.
- **NOT yet tested on an actual device/emulator.** This is the main outstanding risk on item #1 — logic is verified at compile-time only, not runtime-verified against a real agent CLI's actual prompt output.

---

## Known pre-existing state (not from this session, do not touch/re-litigate)

Repo was NOT a clean checkout before this session started. Pre-existing uncommitted WIP (905 insertions / 206 deletions across 18 files), including:
- untracked `android/.../commands/` — SavedCommands feature, someone else's in-progress work
- untracked `extension/src/pty/pty-helper.py` — core PTY file, not in git at all (intentional, not a mistake)

This predates and is separate from feature-parity work. Do not commit over it, do not "clean it up" — just work alongside it.

Also intentional-by-design (not bugs to fix silently):
- Auth is in-memory only server-side (no disk persistence).
- Android encrypts its token copy but Room DB session history is stored plaintext.
- Windows has no PTY support at all — known gap, out of scope here.

---

## What was actually done — Item #2 (COMPLETE)

Confirmed the suspected gap by reading `extension/src/pty/manager.ts`: there was
zero output buffering. `pty.on('data')` just re-emitted raw chunks to whatever
WS clients happened to be connected at that instant — a client that dropped
(wifi blip, phone locked, tunnel hiccup) and reconnected got a blank terminal,
no matter how much happened while it was away. `handleWs` only ever sent
`term.list` (id/title/alive) on connect, never any content.

### Files changed

- **`extension/src/pty/manager.ts`** — Added a per-tab ring buffer (`buffers: Map<string, string>`),
  capped at `maxBufferChars = 200_000` (~200KB/tab). Both stdout and stderr listeners
  now route through a shared `onOutput` handler that appends to the buffer before
  emitting `data` as before. New `getBuffer(id)` public method returns the full
  buffered scrollback for a tab. Buffer is cleared on explicit `close()` (user closes
  the tab) but deliberately **not** cleared on process `exit` — a reconnecting client
  should still be able to see how/why a tab ended, not just that it's gone.
- **`extension/src/server/protocol.ts`** — Added `term.replay` message
  (`{ t: 'term.replay'; tab: string; data: string }`), documented as authoritative
  (replace, not append) scrollback sent once per known tab right after `term.list`
  on every (re)connect. Also fixed a now-stale comment on `agent.approve`/`agent.reject`
  that still described the pre-1b "most recently active PTY tab" behavior — updated
  to reflect the per-session targeting fixed in item #1b.
- **`extension/src/server/index.ts`** — `handleWs` now loops `this.pty.list()`
  right after sending `term.list` and sends one `term.replay` per tab that has
  anything buffered.
- **`android/.../connection/ConnectionManager.kt`** — New `"term.replay"` branch
  in the inbound message handler, inserted right before `"term.data"`. Splits the
  replayed buffer into lines the same way `term.data` does (via `renderAnsi`,
  capped at 2000 lines), but **replaces** `tab.lines` wholesale rather than
  appending — appending would double up content on a quick reconnect where nothing
  was actually missed. Intentionally **not** fed through `AgentEventParser` or
  `CostTracker`: this is output that (should have) already been processed the first
  time it streamed through as `term.data`; re-running it would re-fire approval
  notifications for prompts the user already answered while disconnected.
- **`extension/src/pty/manager.test.ts`** (NEW) — 3 unit tests: (1) integration
  test spawning a real PTY, echoing a marker string, confirming `getBuffer()`
  contains it; (2) buffer cap enforcement — pushes 150KB + 100KB directly via the
  private accumulator and confirms the result is exactly 200KB with the oldest bytes
  dropped from the front; (3) confirms `close()` clears the buffer for a real opened
  tab.

### Verification done this session

- `tsc --noEmit` — clean.
- Extension `npm run build` — clean.
- Extension `npm test` — **4/4 pass** (1 pre-existing + 3 new for this item).
- Android `./gradlew :app:compileDebugKotlin` — BUILD SUCCESSFUL.
- **NOT yet tested on an actual device/emulator** for the reconnect flow specifically
  (i.e. actually killing wifi mid-session on a phone and confirming the terminal
  repaints correctly). The PTY-side buffer/replay logic has a real integration test;
  the Android-side line-replacement logic does not, since it needs a live WS round
  trip to exercise meaningfully.
- Known limitation carried forward: the 200KB/2000-line caps are independent
  constants on each side (server buffer measured in chars, Android cap in rendered
  lines) — they're not mathematically related, just both "big enough to be useful,
  small enough not to be a problem." Fine for now, worth revisiting if either side's
  cap turns out to feel wrong in practice.

---

## What was actually done — Item #3 (COMPLETE) — branch `GROKCHANGES`

CodeMote's signature UX is a **single live card per agent session** that flips Running ↔ Waiting in place (not a stack of one-shots). Android has no iOS Live Activities, so we implement the closest equivalent: stable notification IDs + `setOnlyAlertOnce` + ongoing flags + a sticky Waiting state.

### Files changed

- **`android/.../notifications/AgentLiveState.kt`** (NEW) — pure `LiveAgentState` sealed class (`Running` / `Waiting(snippet)` / `Finished(code)`) + `AgentLiveTracker` StateFlow map with transition rules. Waiting is sticky against Running unless `force=true` (approve/reject/exit). Exposes `summary()` for the FG service.
- **`android/.../notifications/Notifier.kt`** — `updateLive()` / `clearLive()`; channels `agent_live` (LOW) and `agent_waiting` (HIGH). Same `tabId.hashCode()` id is re-notified in place. Approve/Reject now flip the live card to Running instead of cancelling it.
- **`android/.../connection/ConnectionManager.kt`** — `term.data` → Running (if changed); `agent.event awaiting_approval` → Waiting; `term.exit` → Finished; disconnect clears all live cards.
- **`android/.../service/SessionForegroundService.kt`** — FG notification text appends tracker summary (`Connected to X · 1 waiting · 2 running`).
- **`android/app/build.gradle.kts`** — JUnit + coroutines-test for unit tests.
- **`android/.../AgentLiveTrackerTest.kt`** (NEW) — 9 unit tests for transition stickiness / summary / clear.
- **`extension/src/agent-detector.test.ts`** (NEW) — 6 unit tests for ApprovalDetector patterns + cooldown.
- **`extension/package.json`** — `npm test` finds all `out/**/*.test.js` (top-level included).

### Verification

- Extension `npm run build` + `npm test` — **10/10 pass** (token + buffer + detector).
- Android `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest` — **9/9 pass**.
- **NOT yet device-tested** (need real agent y/n + lock screen / shade observation).

### Known Android limits vs CodeMote iOS Live Activities

- No true lock-screen Live Activity API on stock Android; ongoing + high-importance heads-up is the practical equivalent.
- Channel switch (live ↔ waiting) may re-alert once when entering Waiting — intentional so the user notices approval needed.
- Finished cards auto-cancel on tap; they stay until user dismisses (no timed auto-remove yet).

---

## What was actually done — Item #4 (COMPLETE MVP) — branch `GROKCHANGES`

Standalone Node CLI so PocketCode runs on a bare VPS / SSH box with no VS Code/Cursor.

### Files changed

- **`extension/src/cli.ts`** (NEW) — `pocketcode-cli` entry: parse args, start `Server` + tunnel, print ASCII QR + pairing JSON, SIGINT shutdown.
- **`extension/src/cli.test.ts`** (NEW) — 4 unit tests for `parseArgs`.
- **`extension/src/tunnel/local.ts`** (NEW) — LAN/loopback "tunnel" (no external binary); auto-detect falls back here.
- **`extension/src/tunnel/index.ts`** — `TunnelPref` adds `local`; `auto` falls back to LocalTunnel.
- **`extension/src/tunnel/ssh.ts`** — no hard `import vscode`; reads constructor opts → env → optional vscode settings.
- **`extension/src/server/index.ts`** — removed `vscode` import; `listWorkspaces` / `openWorkspaceFolder` injected via `ServerOpts` (headless defaults to single root).
- **`extension/src/extension.ts`** — injects VS Code workspace hooks into Server.
- **`extension/package.json`** — `"bin": { "pocketcode-cli": "./out/cli.js" }`, `"cli"` script.

### Run

```bash
cd extension && npm run build
npm run cli -- /path/to/project --tunnel local --port 8765
# or: node out/cli.js --help
```

### Verification

- `npm test` — **14/14 pass** (detector + buffer + token + cli parseArgs).
- Smoke: `node out/cli.js … --tunnel local --port 18765` → health `{"ok":true,"devices":0}`, QR printed, clean SIGINT.
- **Not yet published to npm** as a standalone package name; bin works via local `node out/cli.js` / npm link.

### Follow-ups (not blocking MVP)

- Persist tunnel id for headless devtunnel (extension uses workspaceState).
- Optional `npx` package split if we want install without cloning the monorepo.
- Item #5 tunnels (ngrok/cloudflare) still open.

---

## Next up: Item #5 — ngrok / Cloudflare tunnels (optional)

Deprioritized earlier. Only pick up if phones need public reach without tailscale/devtunnel. Alternative: document `local` + `devtunnel` + `tailscale` as the supported set.

---

## Environment notes (for future sessions)

- Repo: `/Users/apple/Downloads/pocketcode`, two halves: `extension/` (Node/TS VS Code ext, Fastify-style hand-rolled WS server) and `android/` (Kotlin/Compose, package `com.remotedev.pocketcode`).
- Wire protocol: `WsMsg` discriminated union in `extension/src/server/protocol.ts` — shared contract, any change must mirror on Android.
- PTY: custom Python `openpty()` shim (`pty-helper.py`), deliberately not `node-pty`.
- Mac: username `apple`. `JAVA_HOME=/opt/homebrew/opt/openjdk@17` required for Gradle builds.
- Edit pattern for remote files: base64-encode content, `echo 'B64' | base64 -d > ~/path` via osascript `do shell script`; for surgical edits use Python `str.replace()` with `assert content.count(anchor) == 1` guards.
- Chandar's standing instruction: keep this file (and CONTEXT.md) continuously updated in-repo so any fresh Claude session can resume instantly, even mid-task. Update after every meaningful chunk of work, not just at session end.

---

## What was actually done — Item #5 (COMPLETE, verified by a fresh Claude session)

_Verified this session: independently re-ran `tsc --noEmit`, `npm run build`,
`npm test` (fresh, non-cached), and Android `./gradlew :app:compileDebugKotlin
:app:testDebugUnitTest --rerun` before touching anything, to confirm items
#1-#4 above were real and not just claims in this file. Extension: 14/14 green.
Android: 9/9 green (checked the actual JUnit XML results, not just Gradle's
UP-TO-DATE cache). Then implemented #5 on top._

Added `ngrok` and `cloudflare` as explicit `TunnelPref` options (CodeMote
supports both; PocketCode only had devtunnel/tailscale/tailscale-ip/ssh/local).

### Files changed
- **`extension/src/tunnel/ngrok.ts`** (NEW) — `NgrokTunnel implements TunnelProvider`.
  Spawns `ngrok http <port> --log=stdout`, then polls ngrok's own local
  inspection API (`127.0.0.1:4040/api/tunnels`) for the first `https://` tunnel
  URL rather than scraping stdout — the local API contract is stable across
  ngrok versions, stdout log format isn't. `pollNgrokApi()` exported standalone
  (injectable API URL + exit-check) so it's unit-testable without spawning a
  real ngrok process.
- **`extension/src/tunnel/cloudflare.ts`** (NEW) — `CloudflareTunnel implements
  TunnelProvider`. Spawns `cloudflared tunnel --url http://localhost:<port>`
  (Cloudflare's free "quick tunnel," no account needed — deliberately not the
  named-tunnel flow, which requires a Cloudflare account/DNS setup).
  `extractCloudflareUrl()` is a pure regex extractor pulled out for the same
  testability reason as above.
- **`extension/src/tunnel/index.ts`** — `TunnelPref` gained `'ngrok' |
  'cloudflare'`. `detect()` wires both in as **explicit-only** choices — same
  treatment as plain `'tailscale'` and `'ssh'` — deliberately **not** added to
  the `'auto'` fallback cascade, since both need an external binary (and ngrok
  typically an auth token too); `auto` should keep preferring the zero-setup
  options (devtunnel → tailscale-ip → ssh → local) rather than silently
  reaching for one of these.
- **`extension/src/cli.ts`** — help text / usage comment updated to list the
  two new `--tunnel` values.
- **`extension/src/tunnel/ngrok.test.ts`**, **`cloudflare.test.ts`** (NEW) — 5
  unit tests total: ngrok API-polling happy path, exit-abort path, timeout
  path (using a real local `http` server as a stand-in for ngrok's API, no
  network/external binary involved); cloudflare URL extraction from a
  realistic banner, and the no-URL-yet case.

### Verification done this session
- `tsc --noEmit -p .` — clean.
- `npm run build` — clean.
- `npm test` — **19/19 pass** (14 pre-existing + 5 new).
- Android `./gradlew :app:compileDebugKotlin` — BUILD SUCCESSFUL (untouched by
  this item, extension-only change; confirmed via `git status` that only
  extension files + the 4 new tunnel files show up as changed).
- **NOT tested against a real `ngrok`/`cloudflared` binary** — no such binaries
  in this sandboxed environment. The unit tests cover the parsing/polling
  logic in isolation (via a local stand-in HTTP server for ngrok, and a
  hardcoded realistic banner string for cloudflare) but the actual
  `spawn('ngrok', ...)` / `spawn('cloudflared', ...)` code paths are
  unexercised. **Recommend a real smoke test before relying on this**: install
  `ngrok`/`cloudflared` on the Mac, run e.g.
  `node out/cli.js --tunnel ngrok --port 18765` and
  `node out/cli.js --tunnel cloudflare --port 18765`, confirm a real public URL
  comes back and a phone can actually reach it.

---

## Status as of this session: all 5 CodeMote parity gaps are implemented and green

| # | Gap | Status |
|---|---|---|
| 1 / 1b | Per-agent approve/reject + approval-prompt detection | ✅ DONE, compile/unit verified, not device-tested |
| 2 | Reconnect resume / scrollback replay | ✅ DONE, compile/unit verified, not device-tested |
| 3 | Live-updating notification w/ waiting state | ✅ DONE, compile/unit verified, not device-tested |
| 4 | Headless CLI mode | ✅ DONE (MVP), smoke-tested locally with `--tunnel local`, not published to npm |
| 5 | ngrok / Cloudflare tunnels | ✅ DONE, unit-tested, **not** tested against real ngrok/cloudflared binaries |

**Nothing left from the original CodeMote parity list.** The common thread
across all 5 items: everything is verified at compile-time and unit-test
level, on this Mac, in this sandboxed session — but **none of it has been
run end-to-end on an actual paired Android device** yet. That's the one
real gap remaining before calling this "done done." Suggested device-test
checklist for the next session (or for Chandar directly):
1. Pair phone, run two terminals with a real agent CLI in each, trigger a
   real y/n prompt in one — confirm only that terminal's notification fires
   and Approve/Reject answers the right one (#1/#1b).
2. Kill wifi mid-session, reconnect — confirm scrollback repaints instead of
   showing blank (#2).
3. Watch the notification shade / a locked phone through a Running → Waiting
   → Running cycle — confirm it updates in place rather than stacking (#3).
4. Run `node out/cli.js /some/repo --tunnel devtunnel` on a bare terminal
   (no VS Code open) and pair from the phone — confirm it behaves like a
   normal session (#4).
5. If ngrok/cloudflared are installed: `--tunnel ngrok` / `--tunnel cloudflare`
   and confirm a real public URL is returned and reachable (#5).

Also still true from before, unrelated to this work, not touched: the
pre-existing WIP (`android/.../commands/` SavedCommands dir, untracked
`pty-helper.py`) remains uncommitted. Nothing has been committed by any
session so far — `git log` HEAD is still `79ad46e`. Worth deciding when to
commit everything as a checkpoint.
