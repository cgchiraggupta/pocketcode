import { spawn } from 'node:child_process';

export interface TunnelProvider {
  name: string;
  start(localPort: number): Promise<{ publicHost: string; publicPort: number }>;
  stop(): Promise<void>;
}

async function which(bin: string): Promise<string | null> {
  return new Promise((resolve) => {
    const cmd = process.platform === 'win32' ? 'where' : 'which';
    const p = spawn(cmd, [bin]);
    let out = '';
    p.stdout.on('data', (d) => out += d.toString());
    p.on('close', (code) => resolve(code === 0 ? out.trim().split('\n')[0] : null));
    p.on('error', () => resolve(null));
  });
}

export type TunnelPref = 'auto' | 'tailscale' | 'tailscale-ip' | 'devtunnel' | 'ssh';

export async function detect(preferred: TunnelPref): Promise<TunnelProvider> {
  if (preferred === 'auto') {
    // ponytail: tailscale-ip reaches the Mac over the tailnet directly (ws://).
    // tailscale serve needs a tailnet admin toggle — use remoteDev.preferredTunnel=tailscale for that.
    if (await which('tailscale')) return new (await import('./tailscale-ip')).TailscaleIP();
    if (await which('devtunnel')) return new (await import('./devtunnel')).DevTunnel();
    if (await which('ssh')) return new (await import('./ssh')).SSHTunnel();
    throw new Error('No tunnel CLI found. Install Tailscale (https://tailscale.com) or devtunnel (https://aka.ms/devtunnel) or configure SSH.');
  }
  if (preferred === 'tailscale') return new (await import('./tailscale')).Tailscale();
  if (preferred === 'tailscale-ip') return new (await import('./tailscale-ip')).TailscaleIP();
  if (preferred === 'devtunnel') return new (await import('./devtunnel')).DevTunnel();
  return new (await import('./ssh')).SSHTunnel();
}
