import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setTimeout as sleep } from 'node:timers/promises';
import { PtyManager } from './manager';

test('PtyManager buffers recent output per tab and exposes it via getBuffer', async () => {
  const mgr = new PtyManager(4);
  const id = mgr.open({ cols: 80, rows: 24 });
  mgr.write(id, 'echo hello-buffer-test\n');
  // Give the PTY a moment to actually run the command and emit output.
  await sleep(500);
  const buf = mgr.getBuffer(id);
  assert.ok(buf.includes('hello-buffer-test'), `expected buffer to contain echoed text, got: ${buf}`);
  mgr.close(id);
});

test('PtyManager caps the per-tab buffer instead of growing unbounded', () => {
  const mgr = new PtyManager(1);
  const id = 'fake-tab-for-cap-test';
  // Reach into the private accumulator directly -- this test is about the capping
  // arithmetic, not about spawning a real shell that produces 200KB+ of output.
  const append = (mgr as unknown as { appendToBuffer(id: string, s: string): void }).appendToBuffer.bind(mgr);
  append(id, 'x'.repeat(150_000));
  append(id, 'y'.repeat(100_000));
  const buf = mgr.getBuffer(id);
  assert.equal(buf.length, 200_000);
  // Oldest bytes (front of the 'x' run) should have been dropped first, tail kept.
  assert.ok(buf.endsWith('y'.repeat(100_000)));
});

test('PtyManager clears a tab\'s buffer on explicit close', () => {
  const mgr = new PtyManager(2);
  // Use a real opened tab -- close() looks the id up in the tabs map first and
  // no-ops if it doesn't find one, so a made-up id wouldn't exercise this path.
  const id = mgr.open({ cols: 80, rows: 24 });
  const append = (mgr as unknown as { appendToBuffer(id: string, s: string): void }).appendToBuffer.bind(mgr);
  append(id, 'some output');
  assert.equal(mgr.getBuffer(id), 'some output');
  mgr.close(id);
  assert.equal(mgr.getBuffer(id), '');
});
