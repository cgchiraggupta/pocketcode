# PocketCode — Next Session Handoff

Use this file as the prompt context for the next coding-agent session.

## Start here

1. Read `docs/EXECUTION_PLAN.md` in full.
2. Read `CONTEXT.md` in full. `extension/src/server/protocol.ts` is the wire-protocol source of truth.
3. Check `git status --short`, `git log --oneline --decorate -8`, and the currently connected Android device before making changes.
4. Preserve user changes. Stage only files belonging to the current change. After each tested implementation unit, commit and push it to `origin/main`.

## Completed and verified

- Task 0: passed on physical Android for Claude Code, Codex CLI, and OpenCode. Do not modify `ApprovalDetector` unless the user explicitly asks.
- Task 1: passed. Phone editor opens, edits, saves via `fs.write`, and changes appear on the Mac. It has Undo, lightweight comment highlighting, and a mobile code-key strip.
- Task 2: passed on physical Android. Unified staged/unstaged diffs, stage/unstage, local commit, push confirmation, and branch switching/creation all work.
- Git-panel improvements are implemented: unified diffs (including staged diffs), stage/unstage state, commit locally, push to origin, status refresh after saving or opening Git, branch picker, and explicit operation feedback.
- The phone now displays `Pushed to origin/main — local and remote are in sync.` only after a successful server-side push response.
- The mobile editor is not LSP-backed. Its highlighting is deliberately lightweight; it does not provide language-server completion, diagnostics, go-to-definition, hover, or formatting.

## Next planned work

Begin Task 3: Native per-agent chat UI. Keep v1 narrow:

- Support Claude Code and Codex CLI first.
- Normalize structured agent events; do not overload `term.data`.
- Keep the raw terminal as the fallback/debug view.
- Preserve existing approve/reject routing per terminal tab.

Do not start Tasks 4, 5, or 6 until Task 3 scope is agreed or the user explicitly requests them.
