import { EventEmitter } from 'node:events';
import { hashToken, newToken } from '../security/token';

export interface BoundDevice {
  id: string;
  fingerprint: string;
  lastSeenAt: number;
  boundAt: number;
}

export interface TokenRecord {
  hash: string;
  exp: number;
  devices: Map<string, BoundDevice>;   // deviceId -> device
}

// In-memory only. Lost on restart — by design.
export class Auth extends EventEmitter {
  private tokens = new Map<string, TokenRecord>();
  private expiryMs: number;

  constructor(expiryMinutes: number) {
    super();
    this.expiryMs = expiryMinutes * 60 * 1000;
  }

  setExpiry(minutes: number) { this.expiryMs = minutes * 60 * 1000; }

  issue(rawToken: string): { hash: string; exp: number } {
    const hash = hashToken(rawToken);
    const exp = Date.now() + this.expiryMs;
    this.tokens.set(hash, { hash, exp, devices: new Map() });
    this.gc();
    return { hash, exp };
  }

  // Returns the device record if accepted, null if token is invalid/expired.
  // Binds a device fingerprint to this token on first use.
  authenticate(rawToken: string, deviceId: string, deviceFp: string): BoundDevice | null {
    const rec = this.tokens.get(hashToken(rawToken));
    if (!rec) return null;
    if (Date.now() > rec.exp) {
      this.tokens.delete(rec.hash);
      return null;
    }
    let dev = rec.devices.get(deviceId);
    if (!dev) {
      dev = { id: deviceId, fingerprint: deviceFp, boundAt: Date.now(), lastSeenAt: Date.now() };
      rec.devices.set(deviceId, dev);
      this.emit('device.bound', dev);
    } else {
      if (dev.fingerprint !== deviceFp) {
        this.emit('device.fingerprintMismatch', dev);
        return null;        // ponytail: reject on mismatch, no fancy override flow
      }
      dev.lastSeenAt = Date.now();
    }
    return dev;
  }

  // Renew a token proactively if it expires within 6h.
  // Issues a new token with the same device bindings; the old token remains
  // valid for a 2-minute grace period so any in-flight reconnects still work.
  // Returns { newRawToken, exp } if renewal happened, null if token is healthy.
  renewIfExpiring(rawToken: string): { newRawToken: string; exp: number } | null {
    const oldHash = hashToken(rawToken);
    const rec = this.tokens.get(oldHash);
    if (!rec) return null;
    const remaining = rec.exp - Date.now();
    if (remaining > 6 * 60 * 60 * 1000) return null;   // > 6h left, no renewal needed

    const newRawToken = newToken();
    const newHash = hashToken(newRawToken);
    const newExp = Date.now() + this.expiryMs;
    // Migrate device bindings to new record
    this.tokens.set(newHash, { hash: newHash, exp: newExp, devices: new Map(rec.devices) });
    // Keep the old token valid briefly so pending reconnects don't fail
    setTimeout(() => this.tokens.delete(oldHash), 2 * 60 * 1000);
    return { newRawToken, exp: newExp };
  }

  revokeDevice(rawToken: string, deviceId: string): boolean {
    const rec = this.tokens.get(hashToken(rawToken));
    if (!rec) return false;
    return rec.devices.delete(deviceId);
  }

  revokeAll(): number {
    let n = 0;
    for (const rec of this.tokens.values()) { n += rec.devices.size; rec.devices.clear(); }
    return n;
  }

  connectedDeviceCount(): number {
    let n = 0;
    for (const rec of this.tokens.values()) n += rec.devices.size;
    return n;
  }

  gc() {
    const now = Date.now();
    for (const [h, rec] of this.tokens) if (now > rec.exp) this.tokens.delete(h);
  }

  startGC() {
    setInterval(() => this.gc(), 60_000).unref();
  }
}
