import { spawn } from 'node:child_process';
import { TunnelProvider } from './index';

export class Tailscale implements TunnelProvider {
  name = 'tailscale';
  private proc: import('node:child_process').ChildProcess | null = null;
  private port = 0;

  async start(localPort: number): Promise<{ publicHost: string; publicPort: number }> {
    this.port = localPort;
    // ponytail: rely on user having set up tailnet already. We just expose the port.
    const proc = spawn('tailscale', ['serve', 'https', '/', `http://localhost:${localPort}`], {
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    this.proc = proc;
    const stderr: Buffer[] = [];
    proc.stderr!.on('data', (b) => stderr.push(b));

    // Wait for "https://..." line on stderr, with timeout.
    const url = await new Promise<string>((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('tailscale serve timed out')), 8000);
      proc.stderr!.on('data', (b: Buffer) => {
        const s = b.toString();
        const m = s.match(/https:\/\/[^\s]+/);
        if (m) { clearTimeout(timer); resolve(m[0]); }
      });
      proc.on('exit', (code) => {
        clearTimeout(timer);
        reject(new Error(`tailscale exited ${code}: ${Buffer.concat(stderr).toString()}`));
      });
    });

    // ponytail: parse host from the URL. tailscale serves on 443 by default with their hostname.
    const u = new URL(url);
    return { publicHost: u.hostname, publicPort: 443 };
  }

  async stop(): Promise<void> {
    if (!this.proc) return;
    spawn('tailscale', ['serve', 'reset']).on('exit', () => {});
    this.proc.kill('SIGTERM');
    this.proc = null;
  }
}
