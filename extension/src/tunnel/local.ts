import * as os from 'node:os';
import { TunnelProvider } from './index';

/**
 * No-relay "tunnel": expose the already-listening local port on a host the phone
 * can reach on the same LAN / loopback. Used by the headless CLI for same-network
 * pairing and local smoke tests without devtunnel/tailscale.
 */
export class LocalTunnel implements TunnelProvider {
  name = 'local';
  private host: string;

  constructor(host?: string) {
    this.host = host ?? pickLanAddress() ?? '127.0.0.1';
  }

  async start(localPort: number): Promise<{ publicHost: string; publicPort: number }> {
    return { publicHost: this.host, publicPort: localPort };
  }

  async stop(): Promise<void> { /* nothing to tear down */ }
}

function pickLanAddress(): string | null {
  const ifaces = os.networkInterfaces();
  for (const list of Object.values(ifaces)) {
    if (!list) continue;
    for (const info of list) {
      if (info.family === 'IPv4' && !info.internal) return info.address;
    }
  }
  return null;
}
