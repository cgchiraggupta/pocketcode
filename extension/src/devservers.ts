import { exec } from 'node:child_process';
import { promisify } from 'node:util';
import { createConnection } from 'node:net';

const execp = promisify(exec);

export interface DevServer {
  pid: number;
  cmd: string;
  port?: number;
}

// ponytail: lsof-on-localhost is the cheapest thing that works on mac/linux.
// Windows support deferred — node v22 + `netstat -ano` parsing if anyone asks.
export async function listDevServers(): Promise<DevServer[]> {
  if (process.platform === 'win32') return [];
  const { stdout } = await execp(`lsof -nP -iTCP -sTCP:LISTEN 2>/dev/null || true`).catch(() => ({ stdout: '' }));
  const out: DevServer[] = [];
  for (const line of stdout.split('\n').slice(1)) {
    const m = line.trim().match(/^(\S+)\s+(\d+)\s+\S+\s+\S+\s+\S+\s+\S+\s+\S+\s+TCP\s+\S+:(\d+)\s+\(LISTEN\)/);
    if (m) out.push({ cmd: m[1], pid: Number(m[2]), port: Number(m[3]) });
  }
  return out;
}

export async function portReachable(port: number): Promise<boolean> {
  return new Promise((resolve) => {
    const s = createConnection({ port, host: '127.0.0.1' }, () => { s.destroy(); resolve(true); });
    s.on('error', () => resolve(false));
  });
}
