import * as vscode from 'vscode';
import * as QRCode from 'qrcode';
import { detect } from './tunnel';
import { DevTunnel } from './tunnel/devtunnel';
import { Server } from './server';
import { Auth } from './server/auth';
import { PairingQR } from './server/protocol';

let server: Server | null = null;
let tunnel: { stop(): Promise<void> } | null = null;
let statusBar: vscode.StatusBarItem;
let qrPanel: vscode.WebviewPanel | null = null;
let lastQR: PairingQR | null = null;

// ponytail: stable devtunnel ID key — stored in workspaceState so it persists
// across extension reloads and VS Code restarts. Same tunnel ID → same devtunnels.ms
// hostname → previously-paired phones reconnect without a fresh QR scan.
const TUNNEL_ID_KEY = 'remotedev.devtunnelId';
const LEGACY_TUNNEL_NAME_KEY = 'remotedev.devtunnelName';

function updateStatus(devices: number) {
  if (devices === 0) {
    statusBar.text = '$(device-mobile) RemoteDev: idle';
    statusBar.command = 'remotedev.start';
  } else {
    statusBar.text = `$(device-mobile) RemoteDev: ${devices} connected`;
    statusBar.command = 'remotedev.showQR';
  }
}

export async function activate(ctx: vscode.ExtensionContext) {
  statusBar = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 1000);
  statusBar.show();
  updateStatus(0);

  // Default to 10 080 min (7 days) — the sliding window before a full re-pair is needed.
  // Silent token-refresh via the WS connection handles the common case; the long default
  // means even users who only pair occasionally don't get kicked out.
  const auth = new Auth(vscode.workspace.getConfiguration().get<number>('remoteDev.tokenExpiryMinutes', 10_080));
  auth.startGC();

  ctx.subscriptions.push(
    vscode.commands.registerCommand('remotedev.start', () => start(auth, ctx).catch((e) => vscode.window.showErrorMessage(`RemoteDev failed: ${(e as Error).message ?? e}`))),
    vscode.commands.registerCommand('remotedev.stop', () => stop()),
    vscode.commands.registerCommand('remotedev.disconnectAll', () => { const n = auth.revokeAll(); vscode.window.showInformationMessage(`Disconnected ${n} device(s).`); updateStatus(0); }),
    vscode.commands.registerCommand('remotedev.showQR', () => showQR(lastQR)),
    vscode.commands.registerCommand('remotedev.snapshot', async () => { if (!server) return; const s = await server.snaps.create(); vscode.window.showInformationMessage(`Snapshot ${s.id} created`); }),
    vscode.commands.registerCommand('remotedev.revert', async () => {
      if (!server) return;
      const list: any[] = (server.snaps as any).list ?? [];
      const last = list[list.length - 1];
      if (!last) { vscode.window.showInformationMessage('No snapshot to revert.'); return; }
      const choice = await vscode.window.showWarningMessage(`Revert snapshot ${last.id}?`, 'Revert');
      if (choice === 'Revert') { await server.snaps.revert(last.id).catch((e) => vscode.window.showErrorMessage(String(e))); }
    }),
  );

  auth.on('device.bound', (d) => { updateStatus(auth.connectedDeviceCount()); vscode.window.showInformationMessage(`Phone paired: ${d.id.slice(0, 6)}`); });
}

async function start(auth: Auth, ctx: vscode.ExtensionContext) {
  if (server) { vscode.window.showInformationMessage('RemoteDev already running.'); return; }
  const cfg = vscode.workspace.getConfiguration().get<any>('remoteDev', {});
  const root = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
  if (!root) { vscode.window.showErrorMessage('Open a folder first.'); return; }

  server = new Server({
    port: cfg.localPort ?? 0,
    workspaceRoot: root,
    auth,
    maxTerminals: cfg.maxTerminals ?? 16,
    listWorkspaces: () =>
      vscode.workspace.workspaceFolders?.map((f) => ({
        name: f.name,
        uri: f.uri.toString(),
      })) ?? [],
    openWorkspaceFolder: (folderUri) => {
      void vscode.commands.executeCommand('vscode.openFolder', vscode.Uri.parse(folderUri));
    },
    getGitHubToken: async () => {
      const session = await vscode.authentication.getSession('github', ['repo'], { createIfNone: true });
      return session.accessToken;
    },
  });

  const port = await server.listen();

  const preferred = vscode.workspace.getConfiguration().get<'auto' | 'tailscale' | 'tailscale-ip' | 'devtunnel' | 'ssh'>('remoteDev.preferredTunnel', 'auto');

  // ── Tunnel selection ───────────────────────────────────────────────────────
  // For devtunnel (auto or explicit), reuse a service-assigned tunnel ID so the
  // *.devtunnels.ms hostname stays the same across VS Code restarts when possible.
  // Custom tunnel names require an org feature many accounts don't have.
  const devtunnelOnPath = await checkBinary('devtunnel');

  let provider: Awaited<ReturnType<typeof detect>>;
  let devTunnel: DevTunnel | undefined;

  if ((preferred === 'auto' && devtunnelOnPath) || preferred === 'devtunnel') {
    // Purge legacy pocketcode-* custom names — they require a disabled devtunnel feature.
    const legacyName = ctx.workspaceState.get<string>(LEGACY_TUNNEL_NAME_KEY);
    if (legacyName) await ctx.workspaceState.update(LEGACY_TUNNEL_NAME_KEY, undefined);

    let tunnelId = ctx.workspaceState.get<string>(TUNNEL_ID_KEY);
    if (tunnelId?.startsWith('pocketcode-')) {
      tunnelId = undefined;
      await ctx.workspaceState.update(TUNNEL_ID_KEY, undefined);
    }
    devTunnel = new DevTunnel(tunnelId);
    provider = devTunnel;
  } else {
    // devtunnel not preferred or not on PATH — use standard auto-detect
    provider = await detect(preferred === 'auto' ? 'auto' : preferred);
  }

  // ponytail: for tailscale-ip the phone reaches us on our tailnet IP, so the server must bind on all interfaces, not 127.0.0.1.
  if (provider.name === 'tailscale-ip') {
    server.rebindAll();
  }

  const pub = await provider.start(port);
  if (devTunnel) {
    const tunnelId = devTunnel.getTunnelId();
    if (tunnelId) {
      await ctx.workspaceState.update(TUNNEL_ID_KEY, tunnelId);
      await ctx.workspaceState.update(LEGACY_TUNNEL_NAME_KEY, undefined);
    }
  }
  tunnel = provider;
  const secure = provider.name !== 'tailscale-ip' && provider.name !== 'ssh';
  lastQR = server.buildPairingQR(pub.publicHost, pub.publicPort, secure);
  await showQR(lastQR, provider.name);
  updateStatus(auth.connectedDeviceCount());
}

/** Returns true if `bin` is found on PATH. */
function checkBinary(bin: string): Promise<boolean> {
  return new Promise((resolve) => {
    const cmd = process.platform === 'win32' ? 'where' : 'which';
    const { spawn } = require('node:child_process') as typeof import('node:child_process');
    const p = spawn(cmd, [bin]);
    p.on('close', (code: number | null) => resolve(code === 0));
    p.on('error', () => resolve(false));
  });
}

async function stop() {
  if (tunnel) await tunnel.stop().catch(() => {});
  if (server) server.close();
  tunnel = null; server = null;
  updateStatus(0);
  vscode.window.showInformationMessage('RemoteDev stopped.');
}

async function showQR(qr: PairingQR | null, tunnelName?: string) {
  if (!qr) { vscode.window.showInformationMessage('No active session.'); return; }
  // Always rebuild the panel content so a restarted session shows the fresh QR,
  // not a stale one from the previous run.
  if (qrPanel) { qrPanel.dispose(); qrPanel = null; }
  qrPanel = vscode.window.createWebviewPanel('remotedev.qr', 'RemoteDev — Pair', vscode.ViewColumn.Two, { enableScripts: false });
  qrPanel.onDidDispose(() => qrPanel = null);
  const dataUrl = await QRCode.toDataURL(JSON.stringify(qr), { errorCorrectionLevel: 'M', margin: 1, scale: 6 });
  qrPanel.webview.html = `
    <html><body style="font-family:system-ui;background:#0e0e10;color:#fff;display:flex;flex-direction:column;align-items:center;padding:24px">
      <h2>Scan with the PocketCode app</h2>
      <img src="${dataUrl}" style="background:#fff;padding:12px;border-radius:8px"/>
      <p style="opacity:.7;max-width:340px;text-align:center;margin-top:12px">
        Tunnel: <code>${tunnelName ?? 'unknown'}</code><br/>
        URL: <code>${qr.url}</code><br/>
        Fingerprint: <code>${qr.fp}</code><br/>
        Expires: ${new Date(qr.exp).toLocaleString()}
      </p>
      <p style="opacity:.5;margin-top:24px">Token is shown ONCE. Pairing binds this device until you Disconnect All.</p>
    </body></html>`;
}

export function deactivate() { stop(); }
