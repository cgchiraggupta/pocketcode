import { spawn, spawnSync } from 'node:child_process';
import { TunnelProvider } from './index';

export class Tailscale implements TunnelProvider {
  name = 'tailscale';
  private proc: import('node:child_process').ChildProcess | null = null;
  private port = 0;

  async start(localPort: number): Promise<{ publicHost: string; publicPort: number }> {
    this.port = localPort;
    // ponytail: Tailscale v1.50+ CLI is just `tailscale serve --bg <port>`. No path, no http prefix.
    const proc = spawn('tailscale', ['serve', '--bg', String(localPort)], {
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    this.proc = proc;
    const stderr: Buffer[] = [];
    const stdout: Buffer[] = [];
    proc.stderr!.on('data', (b) => stderr.push(b));
    proc.stdout!.on('data', (b) => stdout.push(b));

    const code: number | null = await new Promise((resolve) => {
      const t = setTimeout(() => { try { proc.kill('SIGTERM'); } catch {} resolve(0); }, 5000);
      proc.on('exit', (c) => { clearTimeout(t); resolve(c); });
    });
    if (code !== 0) {
      const msg = Buffer.concat(stderr).toString() || Buffer.concat(stdout).toString();
      if (msg.includes('Serve is not enabled') || msg.includes('funnel/serve')) {
        throw new Error('Tailscale Serve is not enabled on your tailnet. Enable it at https://login.tailscale.com/admin/dns or ask your tailnet admin.');
      }
      throw new Error(`tailscale serve failed: ${msg.trim()}`);
    }

    const status = spawnSync('tailscale', ['serve', 'status', '--json']);
    if (status.status !== 0) throw new Error(`tailscale serve status failed: ${status.stderr.toString()}`);
    const j = JSON.parse(status.stdout.toString());
    // ponytail: pick the first TCP or HTTPS entry. tailscale serves on 443 by default with the device's tailnet DNS name.
    const httpsEntry = (j.HTTPS ?? j.TCP ?? [])[0];
    if (!httpsEntry) throw new Error('no entry in tailscale serve status');
    return { publicHost: httpsEntry.HostName, publicPort: httpsEntry.Port ?? 443 };
  }

  async stop(): Promise<void> {
    if (!this.proc) return;
    spawn('tailscale', ['serve', 'reset']).on('exit', () => {});
    this.proc.kill('SIGTERM');
    this.proc = null;
  }
}
