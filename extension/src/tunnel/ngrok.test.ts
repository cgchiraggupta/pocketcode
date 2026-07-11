import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as http from 'node:http';
import { pollNgrokApi } from './ngrok';

test('pollNgrokApi returns the first https tunnel URL', async () => {
  const server = http.createServer((_req, res) => {
    res.setHeader('content-type', 'application/json');
    res.end(JSON.stringify({
      tunnels: [
        { public_url: 'http://abc123.ngrok-free.app' },
        { public_url: 'https://abc123.ngrok-free.app' },
      ],
    }));
  });
  await new Promise<void>((r) => server.listen(0, r));
  const port = (server.address() as any).port;
  try {
    const url = await pollNgrokApi(2_000, `http://127.0.0.1:${port}/api/tunnels`);
    assert.equal(url, 'https://abc123.ngrok-free.app');
  } finally {
    server.close();
  }
});

test('pollNgrokApi gives up and returns null once hasExited() is true', async () => {
  const url = await pollNgrokApi(5_000, 'http://127.0.0.1:1/api/tunnels', () => true);
  assert.equal(url, null);
});

test('pollNgrokApi times out and returns null if nothing ever answers', async () => {
  const url = await pollNgrokApi(300, 'http://127.0.0.1:1/api/tunnels');
  assert.equal(url, null);
});
