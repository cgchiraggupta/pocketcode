// agent-detector.ts
// Heuristic, agent-agnostic detection of "waiting for user approval" prompts
// in raw PTY output (works across Claude Code, Aider, Codex, etc. since they
// all eventually print a y/n-style prompt to stdout when they need permission).
//
// Server-side only. Feeds `agent.event` (kind: 'awaiting_approval') to phones
// so a notification with Approve/Reject actions can be raised — this was
// previously entirely unwired; `agent.event` had no producer.

const APPROVAL_PATTERNS: RegExp[] = [
  /\(y\/n\)/i,
  /\[y\/N\]/i,
  /\[Y\/n\]/i,
  /do you want to proceed\??/i,
  /allow this (action|command|tool)\??/i,
  /continue\?\s*\(yes\/no\)/i,
  /press enter to continue/i,
  /\(yes\/no\)/i,
  /approve\?/i,
];

// Don't re-fire for the same tab more than once per window — agents often
// repaint the same prompt across several stdout chunks while awaiting input.
const COOLDOWN_MS = 15_000;

export class ApprovalDetector {
  private lastFired = new Map<string, number>();

  /**
   * Feed a raw PTY output chunk for a given tab.
   * Returns a short snippet to surface in a notification if this chunk looks
   * like an approval prompt and the per-tab cooldown has elapsed; otherwise null.
   */
  check(tab: string, chunk: string): string | null {
    if (!APPROVAL_PATTERNS.some((re) => re.test(chunk))) return null;
    const now = Date.now();
    const last = this.lastFired.get(tab) ?? 0;
    if (now - last < COOLDOWN_MS) return null;
    this.lastFired.set(tab, now);
    const lines = chunk.split('\n').map((l) => l.trim()).filter(Boolean);
    return (lines[lines.length - 1] ?? chunk).slice(0, 200);
  }

  /** Reset the cooldown for a tab — call once the user actually responds,
   *  so the *next* prompt (if any) can notify immediately rather than waiting
   *  out the rest of the previous cooldown window. */
  clear(tab: string) {
    this.lastFired.delete(tab);
  }

  /** Drop all state for a tab that's closed/exited. */
  forget(tab: string) {
    this.lastFired.delete(tab);
  }
}
