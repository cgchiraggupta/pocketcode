# PocketCode — CodeMote Parity Execution Plan
_Written 2026-07-19 after a direct code audit (`extension/src`, `android/app/src/main`) and a fetch of `codemote.caste.work`. Read this file top to bottom before starting any task below. This is written so an agent (Claude Code, Codex, or a human) can pick up any task in isolation and know exactly what to do._

## How to use this file
- Tasks are ordered. **Do not start Task 2 (chat UI) before Task 0 (detector gauntlet) passes.** Everything else can be parallelized.
- Each task lists: current state (verified against code, not assumed), what files it touches, what "done" means, and gotchas.
- When a task is finished, check its box and add a one-line dated note under it (same convention as `PROGRESS.md`).
- If you're an agent picking this up cold: read `CONTEXT.md` first for wire protocol, then this file for what to build.

---

## Task 0 — Validate the ApprovalDetector (BLOCKING — do first)
- [x] Status: passed
- **Why it blocks everything**: `PROGRESS.md` already flags that the widened regex set is sitting in `git stash`, untested on a real device against a real agent CLI. Task 2 (native chat UI) requires parsing agent output into structured events — if the detector's underlying assumptions are wrong, that work gets rebuilt.
- **Files**: `extension/src/agent-detector.ts`, `extension/src/agent-detector.test.ts`
- **What to do**:
  1. `git stash pop` to restore the widened regex WIP.
  2. Run the "Detector gauntlet" section at the bottom of `PROGRESS.md` — this is already written, just needs to be executed on a real phone against real CLIs.
  3. Test against at minimum: Claude Code, Codex CLI, opencode. Record false positives/negatives per agent.
  4. Only commit once it passes on all three.
- **Done when**: approve/reject detection is confirmed correct (no false-positive "success" states, no wrong-tab routing) across all three CLIs on a physical Android device.

- **2026-07-20** — Physical Android gauntlet confirmed passed for Claude Code, Codex CLI, and OpenCode. Approval routing was verified per terminal tab; repeat approval prompts are suppressed until the current decision is answered.

---

## Task 1 — In-app code editor
- [ ] Status: not started
- **Current state**: `android/README.md` explicitly names this a stub: `files/CodeViewerStub — replace with a real Compose code editor`. Backend is fully ready: `extension/src/files/manager.ts` has working `read(rel)` and `write(rel, content)`.
- **Files to touch**:
  - New: `android/app/src/main/java/com/remotedev/pocketcode/files/Editor.kt` (or similar — replace `CodeViewerStub`)
  - Wire to existing `ConnectionManager` WS send/receive for `fs.read` / `fs.write` (see `extension/src/server/protocol.ts` for message shapes)
- **What to build**:
  1. Compose screen that opens on tapping a file in `FileTree.kt`.
  2. Syntax highlighting — evaluate Sora Editor (Android AOSP-derived) vs. a lightweight Compose-native highlighter. Sora Editor is heavier but battle-tested; a custom highlighter is faster to ship but will need per-language tokenizers.
  3. Save action calls `fs.write` over the existing WS connection. Add a dirty-state indicator (unsaved changes).
  4. Handle large files gracefully (don't load 10k-line files into a single Compose text field without virtualization).
- **Done when**: can open a file from the tree, edit it, save it, and see the change reflected on the VS Code side.
- **Est**: 5-7 days.

---

## Task 2 — Diff viewer
- [ ] Status: not started
- **Current state**: backend `GitManager.diff(path?, staged?)` in `extension/src/git/manager.ts` already returns diff data. No rendering UI exists anywhere in Android — `GitPanel.kt` (218 lines) currently only does status/stage/commit/push, not diff display.
- **Files to touch**:
  - New: `android/app/src/main/java/com/remotedev/pocketcode/git/DiffView.kt`
  - Extend `GitPanel.kt` to navigate into `DiffView` per-file
- **What to build**:
  1. Parse the diff format returned by `simple-git`'s `diff()` (unified diff text) into line-level add/remove/context.
  2. Render as either unified or side-by-side (unified is faster to ship on mobile width).
  3. Tap a changed file in `GitPanel.kt`'s status list → opens `DiffView` for that file.
- **Done when**: staged and unstaged diffs render correctly for at least: added lines, removed lines, modified lines, new files, deleted files.
- **Est**: 3-4 days.

---

## Task 3 — Native per-agent chat UI
- [ ] Status: not started — **depends on Task 0 passing**
- **Current state**: today, agent interaction is raw terminal + `ApprovalDetector` regex watching for y/n-style prompts. No structured chat thread exists. This is CodeMote's headline 2.0 feature and the biggest visual gap.
- **Files to touch**:
  - Backend: `extension/src/agent-detector.ts` — extend beyond approve/reject into a general event emitter (message start/end, tool-call detected, subagent spawned, etc.)
  - Backend: `extension/src/server/protocol.ts` — add new message types for structured agent events (don't overload `term.data`)
  - Android: new `android/app/src/main/java/com/remotedev/pocketcode/agent/AgentChatScreen.kt`
  - Existing: `AgentLiveState.kt`, `AgentStatusWidget.kt`, `AgentTimeline.kt` — these already track agent state, extend rather than replace
- **What to build**:
  1. Extend the detector to classify more than approve/reject: distinguish "agent is typing/thinking," "tool call in progress," "agent produced a diff/file change," "agent asked a question."
  2. Define a small structured event schema (don't try to fully replicate each CLI's internal format — normalize to: `{type: 'message'|'tool_call'|'question'|'diff', content, agentId, timestamp}`).
  3. Build a chat-bubble UI that renders these events per agent thread, with multiple concurrent threads (tabs or a thread list).
  4. Keep the raw terminal view accessible as a fallback/debug view — don't remove it, since regex-based classification will miss things and users need an escape hatch.
- **Gotcha**: this is fundamentally harder than the other tasks because CLI output formats vary and evolve. Scope v1 tight: get Claude Code and Codex CLI working well, treat everything else as "falls back to raw terminal."
- **Done when**: at least 2 agent CLIs show a readable chat thread (not raw ANSI text) with visible tool-call/diff events, and approve/reject still routes correctly per-agent.
- **Est**: 7-10 days. Largest single task.

---

## Task 4 — Notes → agent
- [ ] Status: not started
- **Current state**: nothing exists for this today.
- **Files to touch**:
  - New: `android/app/src/main/java/com/remotedev/pocketcode/notes/NotesScreen.kt`
  - New: local Room table (reuse existing `Db.kt` patterns already used for transcripts)
- **What to build**:
  1. Simple local list: add/edit/delete notes, each with optional checklist items.
  2. "Send to agent" action on a note or checklist item — pipes the text into the currently active terminal/chat session as input.
  3. Optional (v1.1): shake-to-open gesture, matching CodeMote's pattern — not required for parity, nice-to-have.
- **Done when**: can capture a note, send it to an active agent session, and see it appear as input in that session.
- **Est**: 2-3 days (add ~1 day if including shake-gesture).

---

## Task 5 — PR review/merge (lowest priority — candidate for v1.1 cut)
- [ ] Status: not started
- **Current state**: `GitManager` in `extension/src/git/manager.ts` is local-git-only (status/diff/stage/commit/push/pull/branches/checkout/log). No GitHub API calls anywhere in the codebase.
- **Files to touch**:
  - New: `extension/src/git/github.ts` — GitHub REST API wrapper (list PRs, get PR diff/files/commits, merge, close)
  - New: OAuth flow for GitHub token (device flow is simplest for a VS Code extension context)
  - Android: new PR list/detail screen, reusing `DiffView.kt` from Task 2 for the file-level diff display
- **What to build**:
  1. GitHub OAuth (device code flow — no redirect URI needed, works well for CLI/extension contexts).
  2. List open PRs for the current repo, show files/commits, merge/close actions.
  3. Reuse `DiffView` from Task 2 rather than building a second diff renderer.
- **Decision needed**: ship this in v1, or document it as an explicit v1.1 gap (same pattern as the existing Windows-PTY punt in `docs/COMPETITIVE.md`)? Recommendation: cut for v1 — lowest synergy with everything else, and it's the one feature that's genuinely optional for "why would I use this over CodeMote."
- **Est**: 4-6 days if built.

---

## Task 6 — Verify dev server streaming survives backgrounding
- [ ] Status: not started (verification only, likely already works)
- **Current state**: `DevServerRegistry` in `extension/src/devservers.ts` and `SessionForegroundService.kt` on Android both exist and are designed for this. Needs confirmation, not new building.
- **What to do**: start a dev server, background the app for 30+ minutes, confirm logs are still streaming and the foreground service hasn't been killed by Doze.
- **Done when**: confirmed on a real device, not emulator (Doze behavior differs).
- **Est**: 1-2 days.

---

## Explicit non-goals (do not build, just document)
- Windows PTY beyond raw shell — already punted in `docs/COMPETITIVE.md`, stays punted.
- Session history encryption at rest — same, stays punted, don't market as "private" until fixed.
- iOS — out of scope entirely, Android is the whole point of differentiation.

## Suggested execution order for a single agent/dev
1. Task 0 (blocking, do alone first)
2. Task 1 + Task 2 in parallel (both are backend-ready, UI-only, no shared dependency)
3. Task 3 (needs Task 0 done, benefits from Task 2's diff rendering for "agent produced a diff" events)
4. Task 4 (small, can slot in anywhere)
5. Task 6 (quick verification, do whenever convenient)
6. Task 5 (last, or cut for v1.1 — make this call explicitly before launch, don't let it default-slip in)

**Total estimate: 5-6 weeks solo**, per the original audit. Update this file's checkboxes and add dated notes as tasks complete — same convention as `PROGRESS.md`.
