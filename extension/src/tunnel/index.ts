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

export async function detect(preferred: 'auto' | 'tailscale' | 'devtunnel'): Promise<TunnelProvider> {
  if (preferred === 'auto') {
    if (await which('tailscale')) return new (await import('./tailscale')).Tailscale();
    if (await which('devtunnel')) return new (await import('./devtunnel')).DevTunnel();
    throw new Error('No tunnel CLI found. Install Tailscale (https://tailscale.com) or devtunnel (https://aka.ms/devtunnel).');
  }
  if (preferred === 'tailscale') return new (await import('./tailscale')).Tailscale();
  return new (await import('./devtunnel')).DevTunnel();
}
