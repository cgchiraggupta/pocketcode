import { randomBytes, createHash } from 'node:crypto';

export function newToken(): string {
  return randomBytes(32).toString('base64url');
}

export function hashToken(raw: string): string {
  return createHash('sha256').update(raw).digest('hex');
}

export function fingerprint(host: string, port: number, fp?: string): string {
  if (fp) return fp;
  return createHash('sha256').update(`${host}:${port}`).digest('hex').slice(0, 16);
}
