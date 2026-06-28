# Security Review — Pairing & Tunnel

## Threat model

| Adversary                       | Goal                              | Mitigation                                    |
|---------------------------------|-----------------------------------|-----------------------------------------------|
| Passive network observer        | Read commands/keys                | TLS via tunnel; no plaintext on the wire      |
| Active MITM on the tunnel       | Hijack pairing                    | QR embeds cert fingerprint; pin on first pair |
| Compromised phone app           | Replay session later              | Tokens are short-lived; revocable per-device  |
| Compromised VS Code             | (user already trusted it)         | Same as user's normal terminal access         |
| Malicious QR pasted by user     | Connect to attacker server        | First-pair fingerprint confirm dialog         |
| Co-located network attacker     | Port-scan tunnel endpoint         | Random port; tunnel-only (no public 0.0.0.0)   |
| Lost / stolen phone              | Attacker uses live session       | "Disconnect All" + per-device revocation      |

## Pairing flow (what actually happens)

1. User triggers `RemoteDev: Start Mobile Session`.
2. Server binds `127.0.0.1:<random 16-bit port>`. **Never** `0.0.0.0`.
3. Tunnel provider forwards that exact port:
   - **Tailscale**: `tailscale serve https / http://localhost:<port>` on the user's own tailnet.
   - **devtunnel**: `devtunnel host -p <port>` — port-forwarding only, no inbound listener.
4. Server generates `token = base64url(crypto.randomBytes(32))`.
5. QR encodes `{ url, token, serverCertSha256 }`.
6. Server stores `sha256(token)` + `deviceFingerprint` (claimed at first WS connect, confirmed via cert pin).
7. Token expires after 24h of inactivity, or on "Disconnect All".

### What's stored where

| Where                     | What                            | Form            |
|---------------------------|---------------------------------|-----------------|
| QR (in-memory, user)      | raw token                       | plaintext       |
| Server RAM                | `sha256(token)`, fingerprints   | hash            |
| Server disk               | nothing (transcripts encrypted) | —               |
| Android Keystore          | token, fingerprints             | AES-256-GCM     |
| Android Room DB           | encrypted session transcripts   | AES-256-GCM     |

## What we explicitly do NOT do

- **No third-party relay.** Tailscale uses DERP as a relay only when direct conn fails; DERP sees TLS bytes, can't decrypt. devtunnel is a relay but only forwards TCP — same property. We do not run our own relay.
- **No analytics, no telemetry, no update checks.** The extension makes zero outbound calls except to the configured tunnel CLI.
- **No tokens in plaintext on disk.** Both ends store hashes (server) or encrypted blobs (client).
- **No auto-accept of incoming terminals.** Terminal tab creation requires an explicit WS message AND a server-side per-session rate limit (10 tabs/min default).

## Known weaknesses (honest list)

1. **Tailscale DERP relay** sees your IP and that *some* connection is happening. Traffic is still E2E encrypted (WireGuard). If your threat model excludes Microsoft or Tailscale Inc, use Headscale + your own DERP.
2. **devtunnel** is Microsoft-hosted; same caveat. For air-gap, host your own SSH tunnel (extension supports `--ssh-target` as a third provider; TODO).
3. **PTY is full shell.** A compromised phone = full shell on the dev machine. This is the explicit user requirement (run Claude Code etc.). Mitigation: short-lived tokens, per-device revocation, status bar makes connected devices visible.
4. **QR transmission is visual.** Shoulder-surfing is on you. Token rotates on every "Start Session".
5. **Cost tracker / activity timeline parsing is heuristic.** A misbehaving agent could spoof "done". These are advisory, not security boundaries.

## Recommended hardening checklist (operator-side)

- [ ] Use a **tailnet-locked ACL** in Tailscale; restrict who can reach the tunnel port.
- [ ] Set `remoteDev.tokenExpiryMinutes` lower than 24h for hostile environments.
- [ ] Run the editor under a non-root user; the extension spawns PTYs as the editor user (already does).
- [ ] Enable macOS `Application Firewall` and only allow the editor + tunnel binary.
- [ ] On Android, enable biometric unlock for the app (`BiometricPrompt` on launch — TODO).
- [ ] "Disconnect All Devices" should be muscle memory before locking your laptop.

## Verdict

For the stated threat model (developer controlling their own dev box from their own phone, over their own network), this design is sound. The biggest residual risk is "compromised phone = full shell on dev box" — and that's inherent to the product, not a bug.
