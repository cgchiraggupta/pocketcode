import { spawn } from 'node:child_process';
import { TunnelProvider } from './index';

export class DevTunnel implements TunnelProvider {
  name = 'devtunnel';
  private proc: import('node:child_process').ChildProcess | null = null;
  private port = 0;

  async start(localPort: number): Promise<{ publicHost: string; publicPort: number }> {
    this.port = localPort;
    const proc = spawn('devtunnel', [
      'host', '-p', String(localPort), '--allow-anonymous',
    ], { stdio: ['ignore', 'pipe', 'pipe'] });
    this.proc = proc;

    const out: Buffer[] = [];
    proc.stdout!.on('data', (b) => out.push(b));
    proc.stderr!.on('data', (b) => out.push(b));

    const url = await new Promise<string>((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('devtunnel host timed out')), 15_000);
      const check = setInterval(() => {
        const s = Buffer.concat(out).toString();
        const m = s.match(/https:\/\/[a-z0-9.-]+\.devtunnels\.ms/i);
        if (m) { clearTimeout(timer); clearInterval(check); resolve(m[0]); }
      }, 200);
      proc.on('exit', (code) => {
        clearTimeout(timer); clearInterval(check);
        reject(new Error(`devtunnel exited ${code}: ${Buffer.concat(out).toString()}`));
      });
    });

    const u = new URL(url);
    return { publicHost: u.hostname, publicPort: 443 };
  }

  async stop(): Promise<void> {
    if (!this.proc) return;
    this.proc.kill('SIGTERM');
    this.proc = null;
  }
}
