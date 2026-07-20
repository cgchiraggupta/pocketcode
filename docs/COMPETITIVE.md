# Competitive Landscape & Launch Plan

_PocketCode vs. Claude Code remote, Codex remote, and CodeMote._
_Internal doc — cross-check before using publicly._

---

## TL;DR

- **The real competitor is CodeMote (#19 on Product Hunt), not Claude/Codex remote.** Those are a different product category ("AI agent as a service"). CodeMote is "remote control your dev box from your phone" — same wedge as PocketCode.
- **PocketCode's market basis is "self-hosted, P2P, no relay, privacy-first remote for the AI-coding-CLI era."** That's a real segment. Tailscale exists for the same reason in VPN.
- **Feature parity is roughly 80%.** What's missing is positioning, onboarding speed, and a launch. Not features.
- **For "ship by weekend": stop building, start shipping.** Landing page, default tunnel, demo GIF, PWA, PH launch prep.

---

## 1. Category clarification (so we don't compare wrong things)

| Category | Examples | What it is |
|---|---|---|
| **AI coding agent as a service** | Claude Code remote, Codex cloud | The model + agent loop + UI is the product. You pay per token / per seat. Code goes to vendor cloud. |
| **Remote for your dev box** | PocketCode, CodeMote | A control plane. The agent runs on your machine, on your subscription. App is a thin client. |

PocketCode is in the second category. Comparing it to the first is apples-to-oranges and will produce bad positioning. **Do not market as "Claude Code but free."** It is "the remote for whatever agent you already use."

---

## 2. vs. Claude Code remote / Codex remote (the "obvious" comparison)

| Capability | Claude/Codex remote | PocketCode |
|---|---|---|
| Runs an AI agent | ✅ yes | ❌ no (relays to whatever is in your terminal) |
| 5-minute setup, no infra | ✅ yes | ❌ no (tunnel + pair + keep machine on) |
| Mobile client polish | high | medium (Compose, decent) |
| Cross-platform | web / iOS / Android | **Android only** (PWA possible) |
| Locked to one vendor's model | ✅ yes | ❌ no — runs any agent in the terminal |
| Code leaves your network for inference | yes (vendor cloud) | **no** for transport; agent itself still hits model API |
| Per-token cost / usage caps | yes | **none** |
| Works with existing Cursor/Claude/Copilot sub | ❌ no | **yes** |
| Session history storage | vendor-side | local Room DB (currently plaintext — known gap) |

**The catch, stated bluntly:** Claude/Codex remote *is* the agent. PocketCode is a *remote for an agent you already have*. Different products. The user who wants "ask AI to fix my code from bed" wants Claude/Codex. The user who wants "drive the agent on my workstation, on my subscription, no third party touching my files" is PocketCode's market.

**Why anyone uses PocketCode over them:**
1. Vendor lock-in escape — Cursor+Claude+Codex in one terminal, one phone app.
2. Privacy — code stays on your box, P2P through your tunnel.
3. Free, no rate limits, no daily-budget walls.
4. Already pays for Cursor/Claude Max — uses that sub from the phone instead of buying another remote-seat from the vendor.

**Honest lacks if we ship by weekend:**
- No iOS native. (PWA mitigates.)
- No real agent UI — `agent.approve/reject` is "write `y\n` to stdin." Works for Claude Code, brittle for arrow-key UIs. **This is the make-or-break demo.**
- Cost tracker is heuristic (`AgentTimeline.kt` parses `term.data` chunks). Looks fake under scrutiny. Fine for "good enough," bad if marketed.
- Session logs in Room are **plaintext**. Ship without fixing it and do not market as "private." Marketing will eat us.
- Windows PTY = raw shell, no colors, no resize, no prompts. Windows users bounce.
- No background agent. If laptop sleeps or VS Code closes, dead. Claude/Codex remote doesn't have this because the agent *is* the product.
- Setup friction. Tunnel + QR + pair + keep-it-running. Claude remote is "log in." Onboarding has to be near-instant or people won't finish.
- No session replay/sharing. Claude remote has session-link share. PocketCode has nothing.

---

## 3. vs. CodeMote (the *actual* competitor)

`https://codemote.caste.work` — #19 on Product Hunt, ~3.2K stars, 7 contributors, last commit ~2 months ago, 3 paid tiers. They are the closed-source-feeling, relay-or-planned-relay version of PocketCode.

### Where CodeMote is ahead (what we are behind on)

1. **They shipped a product + a PH launch + pricing tiers.** We have a working tree with uncommitted WIP and an untracked PTY helper (`extension/src/pty/pty-helper.py` is not in git). They are *sellable* today. We are not.
2. **iOS app exists.** We have none. Even a web-wrapped iOS app is a conversion lever we don't have.
3. **Shipped positioning.** "Control Cursor/Claude Code/Copilot from your phone, P2P, ~30min setup." That headline converts.
4. **Multi-machine, multi-tab PTY, file tree, git, voice, widgets** — they have all of it shipped *and* positioned. We have all of it in code, but not shipped.

### Where PocketCode beats CodeMote (the real wedge)

- **Fully self-hosted P2P.** We bind loopback only. They have a pricing page, which means a relay (current or planned). This is a real privacy story.
- **Tunnel choice is yours** — devtunnel (default), Tailscale serve, Tailscale IP, SSH. CodeMote appears opinionated. Power users pick us.
- **No vendor relay = no vendor outage.** When their server hiccups, we keep working.
- **In-memory auth, no DB of tokens server-side.** They almost certainly can't say this. Our `Auth` class keeps `sha256(token) → {exp, devices}` in a `Map`. Nothing touches disk server-side. That's a verifiable claim.
- **Token rotation with device binding** — device fingerprint mismatch rejects, old token stays valid 2 min for reconnects. Their "phone stolen" story is probably weaker.
- **No telemetry surface.** A relay implies logs.

### The positioning line (one sentence, internal)

> "PocketCode is the self-hosted, no-relay, P2P remote for the AI-coding-CLI era. Your code, your model, your tunnel, your box."

That is the wedge. It is a real segment. Tailscale is in the same segment for VPN. 1Password is in the same segment for password managers. The "yours, not theirs" niche is durable.

---

## 4. What we lack vs. CodeMote, ranked by weekend-fixability

| Gap | Fix in 48h? | Cost / effort | Notes |
|---|---|---|---|
| Landing page + positioning | ✅ yes | 1 evening, one page, one Loom | Stop coding, write copy |
| iOS / PWA wrapper | ✅ yes (PWA via Tailscale/devtunnel URL) | 2 hrs, no native code | iOS devs are highest-LTV cohort |
| Public install instructions | ✅ already in README, needs polish | 1 hr | |
| Screenshots / demo GIF | ✅ yes | 1 hr | Sell the `agent.approve` moment |
| Onboarding < 5 min | ⚠️ tunnel choice matters | 2 hrs | Default to devtunnel, hide Tailscale in settings |
| Trust story (P2P, no relay, in-mem auth) | ✅ already true, write it | 30 min | Verifiable claim |
| **Product Hunt launch prep** | ✅ yes, this is the actual product | 2 days | Hunter, assets, schedule |
| Session history encryption | ❌ punt | — | Don't market session replay, or remove the feature |
| Windows PTY | ❌ punt | — | Raw shell is honest v1 |
| Cost tracker polish | ❌ punt | — | Mention as roadmap |
| iOS native | ❌ punt for v1 | — | PWA covers the demo |
| Relay / hosted option | ❌ never, by design | — | This is the brand |

---

## 5. The weekend plan (the actual play)

**Stop adding features. The features we have are ~80% of CodeMote. What's missing is go-to-market.**

### Day 1 (Friday) — positioning + assets
- [ ] Landing page (single page): headline, 3 bullets, hero GIF, install steps, GitHub link.
- [ ] Hero GIF/video: phone screen, Claude Code asks "Allow this edit? [y/n]", tap **Approve**, terminal shows "y\n", edit applies. **This is the demo that closes the sale.** 30s max.
- [ ] Trust story page or section: "P2P. No relay. Auth is in-memory, never touches disk. Token rotates on renew. Device fingerprint bound."
- [ ] Decide: devtunnel as the *only* default, Tailscale as "advanced." Cut SSH for v1. Fewer choices = faster setup.

### Day 2 (Saturday) — onboarding + PWA
- [ ] Default tunnel = devtunnel. Persist tunnel name across restarts (you already do this per `extension.ts`).
- [ ] Onboarding time target: < 5 min from `code --install-extension` to first terminal on phone. Measure it.
- [ ] PWA: wrap the devtunnel/Tailscale URL in a manifest + service worker. Sideload instructions for iOS. This is the iOS coverage without a native app.
- [ ] Screenshots: terminal view, agent approve moment, file tree, git panel.

### Day 3 (Sunday) — launch prep
- [ ] Product Hunt page: title, tagline, hero GIF, first comment, maker comment.
- [ ] HN post (Show HN): "Show HN: PocketCode — Self-hosted P2P remote for Claude Code / Cursor / Codex" + the hero GIF.
- [ ] README: 30-second quickstart, one command, one QR scan. Cut everything else.
- [ ] Submit PH for Tuesday/Wednesday launch.

### Explicit non-goals for v1 (and what to say about them)

- **No iOS native** → PWA, "iOS support via PWA, native later."
- **Session history not encrypted** → "Local only, never leaves device, encryption on the roadmap." Do not say "encrypted." Do not say "private" without qualifying.
- **Windows PTY = raw shell** → "Best on macOS/Linux. Windows support is raw shell today; full PTY is on the roadmap."
- **Cost tracker is heuristic** → "Advisory cost estimate based on PTY activity. Not a billing source of truth." Or just don't ship it yet.

---

## 6. Risks we should name in the launch

1. **Setup time is the conversion killer.** Claude/Codex remote is "log in." We are "tunnel + pair + keep-it-running." If we cannot get first-session under 5 minutes, retention dies. This is the single most important number to instrument.
2. **The PTY helper is not in git.** If the dev box dies between now and Monday, we lose the core of the architecture. Commit it today.
3. **No relay = we can't recover lost tokens.** That is a feature and a bug. Document the recovery story (re-pair from VS Code) honestly.
4. **Phone battery / background WS.** `SessionForegroundService` is Doze-safe, but we should test: phone in pocket, 30 min later, still connected? If not, we have a launch-day bug.

---

## 7. Things to verify before using any of this publicly

- [ ] "CodeMote has a relay" — I inferred this from the pricing page. **Confirm by signing up for their free tier and reading the ToS / privacy policy.** If they are pure P2P too, our wedge narrows.
- [ ] "CodeMote has no token rotation" — I inferred this. **Confirm or contact them.** If they do, we need a different angle.
- [ ] "CodeMote is 3.2K stars, 7 contributors" — I read this off their public page. **Re-verify on the day of launch.** Numbers move.
- [ ] "PocketCode feature parity is ~80%" — I read this from `CONTEXT.md` and the working tree. **Re-verify by walking through their screenshots side-by-side with ours the night before launch.**
- [ ] PH rank #19 is recent — **re-verify the week of launch.** If they have slipped, our headline changes.

---

## 8. What I deliberately did not cover

- Detailed CodeMote feature-by-feature audit (do this the day before launch, not now).
- Pricing tier design (we are self-hosted; pricing is "free, donations, maybe a hosted relay in v2 if demand justifies it" — decide later).
- HN headline drafting (do it Sunday, fresh).
- iOS native port plan (out of scope for weekend).
- Session-history encryption (out of scope; punt and document).
- Windows PTY (out of scope; punt and document).

**Add these when:** the launch has shipped, or when the post-launch feedback tells us which gap is now the highest-leverage one to close.
