import { test } from 'node:test';
import assert from 'node:assert/strict';
import { extractCloudflareUrl } from './cloudflare';

test('extractCloudflareUrl finds a trycloudflare URL inside a banner', () => {
  const banner = `
2024-01-01T00:00:00Z INF +--------------------------------------------------------------------------------------------+
2024-01-01T00:00:00Z INF |  Your quick Tunnel has been created! Visit it at (it may take some time to be reachable):  |
2024-01-01T00:00:00Z INF |  https://random-words-here.trycloudflare.com                                                |
2024-01-01T00:00:00Z INF +--------------------------------------------------------------------------------------------+
`;
  assert.equal(extractCloudflareUrl(banner), 'https://random-words-here.trycloudflare.com');
});

test('extractCloudflareUrl returns null when no URL is present yet', () => {
  assert.equal(extractCloudflareUrl('starting tunnel...'), null);
});
