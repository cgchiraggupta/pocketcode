import { spawn } from 'node:child_process';
import { TunnelProvider } from './index';

/**
 * Wraps `ngrok http <port>`. Ngrok's own local API (127.0.0.1:4040) is the
 * reliable way to read back the assigned public URL — stdout log format has
 * changed across ngrok major versions, but the local API contract hasn't.
 * Requires the user to have `ngrok` installed and (for anything beyond a
 * short-lived anonymous tunnel) authenticated via `ngrok config add-authtoken`.
 */
export class NgrokTunnel implements TunnelProvider {
  name = 'ngrok';
  private proc: import('node:child_process').ChildProcess | null = null;

  async start(localPort: number): Promise<{ publicHost: string; publicPort: number }> {
    const proc = spawn('ngrok', ['http', String(localPort), '--log=stdout'], {
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    this.proc = proc;
    const out: Buffer[] = [];
    proc.stdout!.on('data', (b) => out.push(b));
    proc.stderr!.on('data', (b) => out.push(b));

    let exited: number | null = null;
    proc.on('exit', (code) => { exited = code; });

    const url = await pollNgrokApi(20_000, undefined, () => exited !== null);
    if (!url) {
      proc.kill('SIGTERM');
      const tail = Buffer.concat(out).toString().slice(-800);
      throw new Error(
        exited !== null
          ? `ngrok exited before reporting a tunnel (code ${exited}). Output: ${tail}`
          : `ngrok did not report a public URL in time. Is it installed and authenticated (ngrok config add-authtoken ...)? Output: ${tail}`,
      );
    }
    const u = new URL(url);
    return { publicHost: u.hostname, publicPort: 443 };
  }

  async stop(): Promise<void> {
    if (!this.proc) return;
    this.proc.kill('SIGTERM');
    this.proc = null;
  }
}

/**
 * Polls ngrok's local inspection API for the first https public_url.
 * Exported standalone (and given an injectable apiUrl + bail-out check) so
 * it's unit-testable without spawning a real ngrok process.
 */
export async function pollNgrokApi(
  timeoutMs: number,
  apiUrl = 'http://127.0.0.1:4040/api/tunnels',
  hasExited: () => boolean = () => false,
): Promise<string | null> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (hasExited()) return null;
    try {
      const res = await fetch(apiUrl);
      if (res.ok) {
        const json: any = await res.json();
        const https = (json.tunnels ?? []).find((t: any) => typeof t.public_url === 'string' && t.public_url.startsWith('https://'));
        if (https) return https.public_url as string;
      }
    } catch {
      // ngrok's local API isn't up yet -- keep polling until the timeout.
    }
    await new Promise((r) => setTimeout(r, 300));
  }
  return null;
}
