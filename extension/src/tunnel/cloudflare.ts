import { spawn } from 'node:child_process';
import { TunnelProvider } from './index';

/**
 * Wraps `cloudflared tunnel --url http://localhost:<port>` — Cloudflare's
 * free "quick tunnel" mode. No account/auth needed, unlike a named
 * cloudflared tunnel, which is why this is the variant we automate here.
 * cloudflared prints the assigned *.trycloudflare.com URL to stdout/stderr
 * inside a bordered banner; we just regex-scan combined output for it.
 */
export class CloudflareTunnel implements TunnelProvider {
  name = 'cloudflare';
  private proc: import('node:child_process').ChildProcess | null = null;

  async start(localPort: number): Promise<{ publicHost: string; publicPort: number }> {
    const proc = spawn('cloudflared', [
      'tunnel', '--url', `http://localhost:${localPort}`,
    ], { stdio: ['ignore', 'pipe', 'pipe'] });
    this.proc = proc;
    const url = await watchCloudflared(proc);
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
 * Pure regex extraction, exported standalone so it's unit-testable without spawning cloudflared. */
export function extractCloudflareUrl(output: string): string | null {
  return output.match(/https:\/\/[a-z0-9-]+\.trycloudflare\.com/i)?.[0] ?? null;
}

function watchCloudflared(proc: import('node:child_process').ChildProcess): Promise<string> {
  const out: Buffer[] = [];
  proc.stdout!.on('data', (b) => out.push(b));
  proc.stderr!.on('data', (b) => out.push(b));
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      reject(new Error(`cloudflared tunnel timed out. Is cloudflared installed? Output: ${Buffer.concat(out).toString().slice(-500)}`));
    }, 20_000);
    const check = setInterval(() => {
      const s = Buffer.concat(out).toString();
      const url = extractCloudflareUrl(s);
      if (!url) return;
      clearTimeout(timer);
      clearInterval(check);
      resolve(url);
    }, 200);
    proc.on('exit', (code) => {
      clearTimeout(timer);
      clearInterval(check);
      reject(new Error(`cloudflared exited ${code}: ${Buffer.concat(out).toString()}`));
    });
    proc.on('error', (err) => {
      clearTimeout(timer);
      clearInterval(check);
      reject(err);
    });
  });
}
