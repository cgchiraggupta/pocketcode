import { spawn, spawnSync } from 'node:child_process';
import { TunnelProvider } from './index';

// ponytail: bypass `tailscale serve` — bind on the host's tailnet IP and let Tailscale route.
// Works on any tailnet (no "Serve" admin toggle needed). Reachable only by devices on the same tailnet.
export class TailscaleIP implements TunnelProvider {
  name = 'tailscale-ip';
  private port = 0;

  async start(localPort: number): Promise<{ publicHost: string; publicPort: number }> {
    this.port = localPort;
    const ip = this.tailnetIPv4();
    if (!ip) throw new Error('No tailnet IPv4 found. Is Tailscale running?');
    return { publicHost: ip, publicPort: localPort };
  }

  async stop(): Promise<void> {
    this.port = 0;
  }

  private tailnetIPv4(): string | null {
    const r = spawnSync('tailscale', ['ip', '-4']);
    if (r.status !== 0) return null;
    const line = r.stdout.toString().split('\n').find((l) => /^\d+\.\d+\.\d+\.\d+$/.test(l.trim()));
    return line ? line.trim() : null;
  }
}
