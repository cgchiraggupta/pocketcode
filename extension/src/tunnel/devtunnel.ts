import { execFile, spawn } from 'node:child_process';
import { promisify } from 'node:util';
import { TunnelProvider } from './index';

const execFileAsync = promisify(execFile);

// Reuse a service-assigned tunnel ID (stored in workspaceState) so the
// *.devtunnels.ms hostname stays stable across restarts when the local port
// is unchanged. Custom tunnel names (--name / user-chosen IDs) require an
// org feature that is disabled on many accounts.

export class DevTunnel implements TunnelProvider {
  name = 'devtunnel';
  private proc: import('node:child_process').ChildProcess | null = null;
  private port = 0;
  private tunnelId?: string;

  constructor(tunnelId?: string) {
    this.tunnelId = normalizeTunnelId(tunnelId);
  }

  getTunnelId(): string | undefined { return this.tunnelId; }

  async start(localPort: number): Promise<{ publicHost: string; publicPort: number }> {
    this.port = localPort;

    if (this.tunnelId) {
      try {
        return await this.hostExisting(this.tunnelId, localPort);
      } catch {
        this.tunnelId = undefined;
      }
    }

    return this.hostFresh(localPort);
  }

  async stop(): Promise<void> {
    if (!this.proc) return;
    this.proc.kill('SIGTERM');
    this.proc = null;
  }

  private async hostFresh(localPort: number): Promise<{ publicHost: string; publicPort: number }> {
    const proc = spawn('devtunnel', [
      'host',
      '-p', String(localPort),
      '--allow-anonymous',
    ], { stdio: ['ignore', 'pipe', 'pipe'] });
    this.proc = proc;

    const { url, tunnelId } = await watchDevtunnel(proc);
    if (tunnelId) this.tunnelId = tunnelId;
    const u = new URL(url);
    return { publicHost: u.hostname, publicPort: 443 };
  }

  private async hostExisting(tunnelId: string, localPort: number): Promise<{ publicHost: string; publicPort: number }> {
    await this.syncPort(tunnelId, localPort);

    const proc = spawn('devtunnel', [
      'host', tunnelId,
      '--allow-anonymous',
    ], { stdio: ['ignore', 'pipe', 'pipe'] });
    this.proc = proc;

    const { url } = await watchDevtunnel(proc);
    const u = new URL(url);
    return { publicHost: u.hostname, publicPort: 443 };
  }

  private async syncPort(tunnelId: string, localPort: number): Promise<void> {
    const { stdout } = await execFileAsync('devtunnel', ['port', 'list', tunnelId, '-j'], { timeout: 15_000 });
    const ports: number[] = JSON.parse(stdout).ports?.map((p: { portNumber: number }) => p.portNumber) ?? [];

    if (ports.includes(localPort)) {
      for (const p of ports) {
        if (p !== localPort) await runDevtunnel(['port', 'delete', tunnelId, '-p', String(p)]);
      }
      return;
    }

    for (const p of ports) {
      await runDevtunnel(['port', 'delete', tunnelId, '-p', String(p)]);
    }
    await runDevtunnel(['port', 'create', tunnelId, '-p', String(localPort)]);
  }
}

async function runDevtunnel(args: string[]): Promise<void> {
  try {
    await execFileAsync('devtunnel', args, { timeout: 15_000 });
  } catch (e: any) {
    const msg = [e?.stdout, e?.stderr, e?.message].filter(Boolean).join('\n');
    throw new Error(msg || 'devtunnel command failed');
  }
}

function watchDevtunnel(proc: import('node:child_process').ChildProcess): Promise<{ url: string; tunnelId?: string }> {
  const out: Buffer[] = [];
  proc.stdout!.on('data', (b) => out.push(b));
  proc.stderr!.on('data', (b) => out.push(b));

  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('devtunnel host timed out')), 20_000);
    const check = setInterval(() => {
      const s = Buffer.concat(out).toString();
      const url = s.match(/https:\/\/[a-z0-9.-]+\.devtunnels\.ms/i)?.[0];
      if (!url) return;
      clearTimeout(timer);
      clearInterval(check);
      const tunnelId = normalizeTunnelId(
        s.match(/Ready to accept connections for tunnel:\s*(\S+)/i)?.[1],
      );
      resolve({ url, tunnelId });
    }, 200);
    proc.on('exit', (code) => {
      clearTimeout(timer);
      clearInterval(check);
      reject(new Error(`devtunnel exited ${code}: ${Buffer.concat(out).toString()}`));
    });
  });
}

function normalizeTunnelId(id?: string): string | undefined {
  if (!id) return undefined;
  const bare = id.split('.')[0];
  if (!bare || bare.startsWith('pocketcode-')) return undefined;
  return bare;
}