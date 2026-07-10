import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseArgs } from './cli';

test('parseArgs defaults', () => {
  const a = parseArgs([]);
  assert.equal(a.port, 0);
  assert.equal(a.tunnel, 'auto');
  assert.equal(a.tokenExpiryMinutes, 10_080);
  assert.equal(a.maxTerminals, 16);
  assert.equal(a.help, false);
});

test('parseArgs flags and positional cwd', () => {
  const a = parseArgs([
    '/tmp/project',
    '--port', '8765',
    '--tunnel', 'local',
    '--host', '192.168.1.10',
    '--token-expiry-minutes', '60',
    '--max-terminals', '4',
    '--ssh-target', 'user@box',
  ]);
  assert.equal(a.cwd, '/tmp/project');
  assert.equal(a.port, 8765);
  assert.equal(a.tunnel, 'local');
  assert.equal(a.host, '192.168.1.10');
  assert.equal(a.tokenExpiryMinutes, 60);
  assert.equal(a.maxTerminals, 4);
  assert.equal(a.sshTarget, 'user@box');
});

test('parseArgs help', () => {
  assert.equal(parseArgs(['--help']).help, true);
  assert.equal(parseArgs(['-h']).help, true);
});

test('parseArgs rejects unknown flag', () => {
  assert.throws(() => parseArgs(['--nope']), /Unknown flag/);
});
