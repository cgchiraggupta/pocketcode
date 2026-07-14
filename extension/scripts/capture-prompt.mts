// scripts/capture-prompt.mts
// ponytail: spawn a real agent CLI in the scratch dir, tee stdout (raw, with
// ANSI) to a file, leave stdin open so you can answer y/n. Used to build the
// CLI_PROMPTS fixtures in agent-detector.test.ts from real output, not
// invented strings. Run once per agent, copy the captured chunk into the test.
//
// usage:  npx tsx scripts/capture-prompt.mts <bin> [args...]
// example: npx tsx scripts/capture-prompt.mts claude
//          npx tsx scripts/capture-prompt.mts aider --model sonnet
//
// output: ./captures/<bin>-<unix-ms>.raw  (binary-safe, ANSI preserved)
//
// NOTE: this spawns the child on a real pty (via node-pty), not plain pipes.
// Ink-based CLIs (Claude Code among them) check stdout.isTTY and silently
// fall back to headless "--print" mode if it's a plain pipe, which is why
// this used to fail with "Input must be provided either through stdin or as
// a prompt argument when using --print" for claude specifically (Gemini's
// CLI doesn't gate on isTTY, so it worked even over a plain pipe).
import * as pty from 'node-pty';
import { mkdirSync, createWriteStream, writeFileSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

const [, , bin, ...args] = process.argv;
if (!bin) {
  console.error('usage: npx tsx scripts/capture-prompt.mts <bin> [args...]');
  process.exit(2);
}

mkdirSync('captures', { recursive: true });
const stamp = Date.now();
const safeBin = bin.replace(/[^a-z0-9_-]/gi, '_');
const file = join('captures', `${safeBin}-${stamp}.raw`);
const out = createWriteStream(file, { flags: 'w' });

console.log(`[capture] running: ${bin} ${args.join(' ')}`);
console.log(`[capture] writing: ${file}`);
console.log('[capture] trigger a y/n prompt, then Ctrl-D to stop. raw bytes incl. ANSI preserved.');

const cols = (process.stdout.columns as number) || 120;
const rows = (process.stdout.rows as number) || 40;

const child = pty.spawn(bin, args, {
  name: 'xterm-256color',
  cols,
  rows,
  cwd: process.cwd(),
  env: { ...process.env, FORCE_COLOR: '1' } as { [key: string]: string },
});

child.onData((data: string) => {
  process.stdout.write(data);
  out.write(data);
});

// Forward our real stdin keystrokes straight into the pty, byte for byte.
if (process.stdin.isTTY) process.stdin.setRawMode(true);
process.stdin.resume();
process.stdin.on('data', (d: Buffer) => {
  // Ctrl-D (0x04) stops the capture, same as before.
  if (d.length === 1 && d[0] === 0x04) {
    child.kill();
    return;
  }
  child.write(d.toString('utf8'));
});

if (process.stdout.on) {
  process.stdout.on('resize', () => {
    const c = (process.stdout.columns as number) || cols;
    const r = (process.stdout.rows as number) || rows;
    try { child.resize(c, r); } catch { /* ignore */ }
  });
}

child.onExit(({ exitCode }: { exitCode: number }) => {
  if (process.stdin.isTTY) process.stdin.setRawMode(false);
  process.stdin.pause();
  out.end(() => {
    writeFileSync(`${file}.meta`, JSON.stringify({ bin, args, code: exitCode, stamp }, null, 2));
    console.log(`\n[capture] done. exit=${exitCode}. last 200 lines of ${file}:`);
    const buf = readFileSync(file);
    const lines = buf.toString('utf8').split('\n');
    console.log(lines.slice(-200).join('\n'));
    process.exit(0);
  });
});
