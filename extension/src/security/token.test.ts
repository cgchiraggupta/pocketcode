import { test } from 'node:test';
import assert from 'node:assert/strict';
import { hashToken } from './token';

test('hashToken is deterministic and not equal to input', () => {
  const t = 'abc';
  const h1 = hashToken(t);
  const h2 = hashToken(t);
  assert.equal(h1, h2);
  assert.notEqual(h1, t);
  assert.equal(h1.length, 64);
});
