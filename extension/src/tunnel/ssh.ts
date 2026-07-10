import { spawn } from 'node:child_process';
import { TunnelProvider } from './index';

/**
 * Reverse SSH tunnel. Config sources (first wins):
 * 1. Constructor options (CLI / programmatic)
 * 2. Env: REMOTEDEV_SSH_TARGET, REMOTEDEV_SSH_REMOTE_PORT
 * 3. VS Code settings remoteDev.sshTarget / remoteDev.sshRemotePort (when running as extension)
 */
export class SSHTunnel implements TunnelProvider {
  name = 'ssh';
  private proc: import('node:child_process').ChildProcess | null = null;
  private target?: string;
  private remotePortOpt?: number;

  constructor(opts?: { target?: string; remotePort?: number }) {
    this.target = opts?.target;
    this.remotePortOpt = opts?.remotePort;
  }

  async start(localPort: number): Promise<{ publicHost: string; publicPort: number }> {
    const target = this.target
      ?? process.env.REMOTEDEV_SSH_TARGET
      ?? readVscodeSetting<string>('remoteDev.sshTarget');
    if (!target) {
      throw new Error(
        'SSH target missing. Pass --ssh-target, set REMOTEDEV_SSH_TARGET, or configure remoteDev.sshTarget.',
      );
    }

    const remotePort = this.remotePortOpt
      ?? (process.env.REMOTEDEV_SSH_REMOTE_PORT ? Number(process.env.REMOTEDEV_SSH_REMOTE_PORT) : undefined)
      ?? readVscodeSetting<number>('remoteDev.sshRemotePort')
      ?? (20000 + Math.floor(Math.random() * 10000));

    const proc = spawn('ssh', [
      '-N', '-R', `${remotePort}:localhost:${localPort}`, target,
    ], { stdio: ['ignore', 'pipe', 'pipe'] });

    this.proc = proc;
    const stderrBuf: Buffer[] = [];
    proc.stderr!.on('data', (b) => stderrBuf.push(b));

    // Wait for actual success or error, not a blind timer.
    // SSH's "Allocated port N for remote forward" lands on stderr.
    await new Promise<void>((resolve, reject) => {
      const deadline = setTimeout(() => {
        proc.kill('SIGTERM');
        reject(new Error(`ssh tunnel timed out. stderr: ${Buffer.concat(stderrBuf).toString()}`));
      }, 10_000);
      proc.stderr!.on('data', (b: Buffer) => {
        const s = b.toString();
        if (/Allocated port \d+/i.test(s)) { clearTimeout(deadline); resolve(); }
        if (/Permission denied|Connection refused|Host key verification failed/i.test(s)) {
          clearTimeout(deadline); proc.kill('SIGTERM');
          reject(new Error(`ssh tunnel failed: ${s.trim()}`));
        }
      });
      proc.on('error', (err) => { clearTimeout(deadline); reject(err); });
      proc.on('exit', (code) => {
        clearTimeout(deadline);
        if (code !== 0 && code !== null) {
          reject(new Error(`ssh exited ${code}: ${Buffer.concat(stderrBuf).toString()}`));
        }
      });
    });

    // Public host is the SSH jump host (everything before the first space / user@host).
    const host = target.includes('@') ? target.split('@').pop()! : target;
    return { publicHost: host, publicPort: remotePort };
  }

  async stop(): Promise<void> {
    if (this.proc) {
      try { this.proc.kill('SIGTERM'); } catch { /* already dead */ }
      this.proc = null;
    }
  }
}

function readVscodeSetting<T>(key: string): T | undefined {
  try {
    // Optional: only present inside the VS Code extension host.
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const vscode = require('vscode') as typeof import('vscode');
    const parts = key.split('.');
    const section = parts[0];
    const rest = parts.slice(1).join('.');
    return vscode.workspace.getConfiguration(section).get<T>(rest);
  } catch {
    return undefined;
  }
}
