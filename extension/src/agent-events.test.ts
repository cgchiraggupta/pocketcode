import { test } from 'node:test';
import assert from 'node:assert/strict';
import { AgentEventNormalizer, detectSupportedAgent } from './agent-events';

test('recognises only Claude Code and Codex CLI launches', () => {
  assert.equal(detectSupportedAgent('claude --dangerously-skip-permissions\n'), 'claude-code');
  assert.equal(detectSupportedAgent('codex'), 'codex-cli');
  assert.equal(detectSupportedAgent('git status'), null);
});

test('activates from xterm keystrokes only once Enter arrives', () => {
  const normalizer = new AgentEventNormalizer();
  for (const key of 'claude') assert.equal(normalizer.observeInput('tab', key), null);
  assert.equal(normalizer.observeInput('tab', '\r'), 'claude-code');
  assert.equal(normalizer.consume('tab', '⏺ Read(README.md)\n')[0]?.type, 'tool_call');
});

test('normalises Claude tool calls and questions without reading term.data on the client', () => {
  const normalizer = new AgentEventNormalizer();
  normalizer.startFromInput('claude-tab', 'claude\n');
  const events = normalizer.consume('claude-tab', '\u001b[32m⏺ Read(src/app.ts)\u001b[0m\nWould you like me to run the tests?\n');
  assert.deepEqual(events.map(({ type, content }) => ({ type, content })), [
    { type: 'tool_call', content: 'Read: src/app.ts' },
    { type: 'question', content: 'Would you like me to run the tests?' },
  ]);
});

test('normalises Codex tool and diff activity per tab', () => {
  const normalizer = new AgentEventNormalizer();
  normalizer.startFromInput('codex-tab', 'codex\n');
  const events = normalizer.consume('codex-tab', '• Ran npm test\n• Edited src/server.ts\n');
  assert.deepEqual(events.map(({ type, content }) => ({ type, content })), [
    { type: 'tool_call', content: 'Ran: npm test' },
    { type: 'diff', content: 'Edited: src/server.ts' },
  ]);
});

test('does not classify output in a non-agent terminal', () => {
  const normalizer = new AgentEventNormalizer();
  assert.deepEqual(normalizer.consume('shell', '• Ran npm test\n'), []);
});
