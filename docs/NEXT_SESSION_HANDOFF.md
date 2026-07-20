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
- Git-panel improvements are implemented: unified diffs (including staged diffs), stage/unstage state, commit locally, push to origin, status refresh after saving or opening Git, and explicit operation feedback.
- The phone now displays `Pushed to origin/main — local and remote are in sync.` only after a successful server-side push response.

## Current change awaiting device verification

The current uncommitted implementation adds a branch picker to the Git panel:

- Tapping the active-branch header (`main`) requests `git.branches` and opens a picker.
- Existing branches can be selected to check out.
- A new branch name can be entered and created from the same picker.
- `git.checkout` now returns `git.result` with fresh status, and Android displays `Switched to <branch>.`

Touched files for this change:

- `extension/src/server/protocol.ts`
- `extension/src/server/index.ts`
- `android/app/src/main/java/com/remotedev/pocketcode/connection/ConnectionManager.kt`
- `android/app/src/main/java/com/remotedev/pocketcode/git/GitPanel.kt`
- `android/app/src/main/java/com/remotedev/pocketcode/ui/Root.kt`
- `CONTEXT.md`

Validation already passed before device test:

```bash
cd extension && npm run build && npm test
cd ../android && ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Then install the APK:

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

Because Cursor runs the installed extension, package and reinstall it after extension changes, then reload Cursor or restart the PocketCode session:

```bash
cd extension
npm run package
/Applications/Cursor.app/Contents/Resources/app/bin/cursor \
  --install-extension "$PWD/remotedev-pocketcode-0.1.1.vsix" --force
```

Device verification for branch picker:

1. Reload Cursor and reconnect the phone.
2. Open Git and tap `main`.
3. Confirm the picker lists `main` and marks it current.
4. Create a harmless branch such as `test/mobile-branch`.
5. Confirm the header changes to that branch and shows `Switched to test/mobile-branch.`
6. Switch back to `main` before ending the test.
7. If passed, commit/push the files above and record a dated Task 2 note in `docs/EXECUTION_PLAN.md` only after user confirmation.

## Next planned work

After the user confirms Task 2 passes, begin Task 3: Native per-agent chat UI. Keep v1 narrow:

- Support Claude Code and Codex CLI first.
- Normalize structured agent events; do not overload `term.data`.
- Keep the raw terminal as the fallback/debug view.
- Preserve existing approve/reject routing per terminal tab.

Do not start Tasks 4, 5, or 6 until Task 3 scope is agreed or the user explicitly requests them.
