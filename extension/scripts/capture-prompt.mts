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

import { spawn } from 'node:child_process';
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

const child = spawn(bin, args, {
  stdio: ['inherit', 'pipe', 'pipe'],
  env: { ...process.env, FORCE_COLOR: '1' }, // ponytail: keep colors so we capture real bytes
});

child.stdout.on('data', (b: Buffer) => process.stdout.write(b) || out.write(b));
child.stderr.on('data', (b: Buffer) => process.stderr.write(b) || out.write(b));

child.on('exit', (code) => {
  out.end(() => {
    writeFileSync(`${file}.meta`, JSON.stringify({ bin, args, code, stamp }, null, 2));
    console.log(`\n[capture] done. exit=${code}. last 200 lines of ${file}:`);
    // ponytail: quick-tail the last 200 lines so you can eyeball the prompt
    // without opening the file. The full raw is in <file>.
    const buf = readFileSync(file);
    const lines = buf.toString('utf8').split('\n');
    console.log(lines.slice(-200).join('\n'));
  });
});
