import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ApprovalDetector } from './agent-detector';

test('ApprovalDetector fires on common y/n prompts', () => {
  const d = new ApprovalDetector();
  const snip = d.check('tab-a', 'Allow this action? (y/n)');
  assert.ok(snip, 'expected a snippet for (y/n)');
  assert.match(snip!, /y\/n/i);
});

// Real CLI prompt shapes, transcribed from actual stdout. One per agent.
// If a tool changes its prompt format, the corresponding test fails first.
const CLI_PROMPTS: Array<[string, string]> = [
  ['claude-code', 'Allow this action? Use Bash to run: rm -rf node_modules\n\n  1. Yes 2. No\n(y/n)'],
  ['claude-code-dontask', 'Do you want to proceed?\n  ❯ 1. Yes\n    2. Yes, and don\'t ask again for this command (y/n)'],
  ['codex', '  Allow command? [Y/n] '],
  ['gemini', 'Do you want to continue? (Y/n)'],
  ['aider', 'Allow edits to src/foo.ts? (y)es/(n)o '],
  ['aider-confirm', 'Y, edit src/foo.ts?'],
  ['generic-bracket', 'Run command? [y/N] '],
];

for (const [cli, prompt] of CLI_PROMPTS) {
  test(`ApprovalDetector fires on ${cli} prompt shape`, () => {
    const d = new ApprovalDetector();
    const snip = d.check(`tab-${cli}`, prompt);
    assert.ok(snip, `expected snippet for ${cli} prompt`);
  });
}

test('ApprovalDetector cooldown suppresses re-fire on same tab', () => {
  const d = new ApprovalDetector();
  assert.ok(d.check('tab-a', 'Continue? (yes/no)'));
  assert.equal(d.check('tab-a', 'Continue? (yes/no) again'), null);
});

test('ApprovalDetector clear() lets the next prompt fire immediately', () => {
  const d = new ApprovalDetector();
  assert.ok(d.check('tab-a', 'Approve?'));
  d.clear('tab-a');
  assert.ok(d.check('tab-a', 'Approve? again'));
});

test('ApprovalDetector tracks tabs independently', () => {
  const d = new ApprovalDetector();
  assert.ok(d.check('tab-a', 'do you want to proceed?'));
  assert.ok(d.check('tab-b', 'do you want to proceed?'));
  assert.equal(d.check('tab-a', 'do you want to proceed? still'), null);
});

test('ApprovalDetector forget() drops tab state', () => {
  const d = new ApprovalDetector();
  assert.ok(d.check('tab-a', '[Y/n] proceed'));
  d.forget('tab-a');
  assert.ok(d.check('tab-a', '[Y/n] proceed again'));
});

test('ApprovalDetector ignores non-prompt output', () => {
  const d = new ApprovalDetector();
  assert.equal(d.check('tab-a', 'compiling 12 files…'), null);
  assert.equal(d.check('tab-a', 'Running tests…'), null);
});
