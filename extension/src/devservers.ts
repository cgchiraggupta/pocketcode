import { exec, spawn, ChildProcess } from 'node:child_process';
import { promisify } from 'node:util';
import { createConnection } from 'node:net';
import { randomUUID } from 'node:crypto';
import { EventEmitter } from 'node:events';

const execp = promisify(exec);

export interface DevServer {
  pid: number;
  cmd: string;
  port?: number;
  managed?: boolean;   // true = started via devserver.start, false = found via lsof
}

// ponytail: lsof-on-localhost is the cheapest thing that works on mac/linux.
// Windows support deferred — node v22 + `netstat -ano` parsing if anyone asks.
export async function listDevServers(): Promise<DevServer[]> {
  if (process.platform === 'win32') return [];
  const { stdout } = await execp(`lsof -nP -iTCP -sTCP:LISTEN 2>/dev/null || true`).catch(() => ({ stdout: '' }));
  const out: DevServer[] = [];
  for (const line of stdout.split('\n').slice(1)) {
    const m = line.trim().match(/^(\S+)\s+(\d+)\s+\S+\s+\S+\s+\S+\s+\S+\s+\S+\s+TCP\s+\S+:(\d+)\s+\(LISTEN\)/);
    if (m) out.push({ cmd: m[1], pid: Number(m[2]), port: Number(m[3]), managed: false });
  }
  return out;
}

export async function portReachable(port: number): Promise<boolean> {
  return new Promise((resolve) => {
    const s = createConnection({ port, host: '127.0.0.1' }, () => { s.destroy(); resolve(true); });
    s.on('error', () => resolve(false));
  });
}

// ─── Managed dev-server registry ───────────────────────────────────────────
// Tracks processes started explicitly via the `devserver.start` WS message.
// These are the only ones we can stream stdout/stderr for, because we hold
// the ChildProcess reference. Processes found only via lsof are unmanaged.

export interface ManagedDevServer {
  id: string;
  pid: number;
  cmd: string;
  cwd: string;
  port?: number;
  proc: ChildProcess;
}

export class DevServerRegistry extends EventEmitter {
  private servers = new Map<string, ManagedDevServer>();

  /** Launch `cmd` in `cwd` and track the resulting process. */
  start(cmd: string, cwd: string): ManagedDevServer {
    const id = randomUUID();
    const proc = spawn(cmd, { shell: true, cwd, stdio: ['ignore', 'pipe', 'pipe'] });
    const entry: ManagedDevServer = { id, pid: proc.pid ?? 0, cmd, cwd, proc };
    this.servers.set(id, entry);

    // Detect port from stdout/stderr heuristic (e.g. "Listening on :3000", "port 5173", etc.)
    const portRe = /(?:port|:\s*)(\d{4,5})/i;
    const detectPort = (chunk: Buffer) => {
      const m = chunk.toString().match(portRe);
      if (m && !entry.port) {
        entry.port = Number(m[1]);
        this.emit('port.detected', entry);
      }
    };
    proc.stdout?.on('data', detectPort);
    proc.stderr?.on('data', detectPort);

    proc.on('exit', () => this.servers.delete(id));
    proc.on('error', () => this.servers.delete(id));

    return entry;
  }

  /** Stop a managed server by PID. Returns true if found and killed. */
  stop(pid: number): boolean {
    for (const [id, entry] of this.servers) {
      if (entry.pid === pid) {
        try { entry.proc.kill('SIGTERM'); } catch { /* already dead */ }
        this.servers.delete(id);
        return true;
      }
    }
    return false;
  }

  /** Find a managed server by the port it's listening on. */
  getByPort(port: number): ManagedDevServer | undefined {
    for (const entry of this.servers.values()) {
      if (entry.port === port) return entry;
    }
    return undefined;
  }

  /** Find a managed server by PID. */
  getByPid(pid: number): ManagedDevServer | undefined {
    for (const entry of this.servers.values()) {
      if (entry.pid === pid) return entry;
    }
    return undefined;
  }

  /** All currently running managed servers. */
  getAll(): ManagedDevServer[] {
    return Array.from(this.servers.values());
  }

  /**
   * Subscribe to stdout+stderr of a managed server identified by port.
   * Returns an unsubscribe function. Call it when the WS connection closes.
   */
  stream(port: number, onData: (chunk: string) => void): (() => void) | null {
    const entry = this.getByPort(port);
    if (!entry) return null;
    const handler = (chunk: Buffer) => onData(chunk.toString('utf8'));
    entry.proc.stdout?.on('data', handler);
    entry.proc.stderr?.on('data', handler);
    return () => {
      entry.proc.stdout?.off('data', handler);
      entry.proc.stderr?.off('data', handler);
    };
  }
}
