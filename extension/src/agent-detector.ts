// agent-detector.ts
// Heuristic, agent-agnostic detection of "waiting for user approval" prompts
// in raw PTY output (works across Claude Code, Aider, Codex, etc. since they
// all eventually print a y/n-style prompt to stdout when they need permission).
//
// Server-side only. Feeds `agent.event` (kind: 'awaiting_approval') to phones
// so a notification with Approve/Reject actions can be raised — this was
// previously entirely unwired; `agent.event` had no producer.

// ponytail: regex set grew from 9 → 14 to cover real CLI shapes observed in
// the wild. Stripped (y/n)/(Y/n) variants down to one forgiving matcher so
// we don't trip on whitespace/bracket permutations. Add a new pattern only
// after seeing a real false-negative on a real CLI's stdout.
const APPROVAL_PATTERNS: RegExp[] = [
  // Generic y/N bracket forms — covers [Y/n], [y/N], (Y/n), (y/n)
  /[\[(]\s*[Yy]\s*\/\s*[Nn]\s*[\])]/,
  // "Allow ...?" / "Do you want to ...?" — the Claude Code, Codex, Gemini shape
  /(allow|approve|proceed|continue|confirm)\b[^\n]*\?/i,
  // "Allow this action?" — Claude Code permission prompt
  /allow this (action|command|tool|edit|file)\??/i,
  // "Do you want to proceed?" — Codex, Claude Code
  /do you want to proceed\??/i,
  // "Press enter to continue" — multi-line tool confirmations
  /press enter to continue/i,
  // Aider's spelled-out "(y)es/(n)o" form
  /\(\s*y\s*\)\s*es\s*\/\s*\(\s*n\s*\)\s*o/i,
  // Aider's "Yes, edit ..." confirmation
  /^\s*Y\s*,\s*edit\b/im,
  // "Approve this change?" — generic
  /approve (this|these)?\s*(change|edit|action|command|tool)?\??/i,
  // Trailing standalone "y/N" after a colon — covers "Run? [y/N]"
  /:\s*[\[(]\s*[Yy]\s*\/\s*[Nn]\s*[\])]\s*$/m,
  // Claude Code's "Yes, and don't ask again" — still a y/n prompt underneath
  /don'?t ask again\s*\(?[Yy]\/?[Nn]?\)?/i,
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

// ponytail: quick smoke harness. `npx tsx agent-detector.ts` shows which
// real CLI prompts trigger a notification. Update the samples if a new
// agent shows up in the wild.
if (require.main === module) {
  const d = new ApprovalDetector();
  const samples: Array<[string, string]> = [
    ['claude', 'Allow this action? (y/n)'],
    ['codex', 'Allow command? [Y/n]'],
    ['gemini', 'Do you want to continue? (Y/n)'],
    ['aider', 'Allow edits? (y)es/(n)o'],
    ['noise', 'compiling 12 files… running tests…'],
  ];
  for (const [tag, prompt] of samples) {
    const r = d.check(`tab-${tag}`, prompt);
    console.log(r ? `HIT  ${tag}: ${r.slice(0, 60)}` : `miss ${tag}`);
  }
}
