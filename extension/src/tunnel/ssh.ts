import { spawn } from 'node:child_process';
import * as vscode from 'vscode';
import { TunnelProvider } from './index';

export class SSHTunnel implements TunnelProvider {
  name = 'ssh';
  private proc: import('node:child_process').ChildProcess | null = null;

  async start(localPort: number): Promise<{ publicHost: string; publicPort: number }> {
    const cfg = vscode.workspace.getConfiguration();
    const target = cfg.get<string>('remoteDev.sshTarget');
    if (!target) throw new Error('remoteDev.sshTarget configuration is missing. Define it in settings.');

    const remotePort = cfg.get<number>('remoteDev.sshRemotePort') || (20000 + Math.floor(Math.random() * 10000));

    const proc = spawn('ssh', [
      '-N', '-R', `${remotePort}:localhost:${localPort}`, target
    ], { stdio: ['ignore', 'pipe', 'pipe'] });

    this.proc = proc;
    const stderrBuf: Buffer[] = [];
    proc.stderr!.on('data', (b) => stderrBuf.push(b));

    // ponytail: wait for actual success or error, not a blind timer.
    // SSH's "Allocated port N for remote forward" lands on stderr.
    const started = await new Promise<boolean>((resolve, reject) => {
      const deadline = setTimeout(() => {
        proc.kill('SIGTERM');
        reject(new Error(`ssh tunnel timed out. stderr: ${Buffer.concat(stderrBuf).toString()}`));
      }, 10_000);
      proc.stderr!.on('data', (b: Buffer) => {
        const s = b.toString();
        if (/Allocated port \d+/i.test(s)) { clearTimeout(deadline); resolve(true); }
        if (/Permission denied|Connection refused|Host key verification failed/i.test(s)) {
          clearTimeout(deadline); proc.kill('SIGTERM');
          reject(new Error(`ssh tunnel failed: ${s.trim()}`));
        }
      });
      proc.on('error', (err) => { clearTimeout(deadline); reject(err); });
      proc.on('exit', (code) => {
        clearTimeout(deadline);
        reject(new Error(`ssh tunnel process exited with code ${code}. stderr: ${Buffer.concat(stderrBuf).toString()}`));
      });
    });
    if (!started) throw new Error('ssh tunnel did not report success');

    const host = target.split('@').pop() || 'localhost';
    return { publicHost: host, publicPort: remotePort };
  }

  async stop(): Promise<void> {
    if (!this.proc) return;
    this.proc.kill('SIGTERM');
    this.proc = null;
  }
}
