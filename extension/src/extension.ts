import * as vscode from 'vscode';
import * as QRCode from 'qrcode';
import { detect } from './tunnel';
import { Server } from './server';
import { Auth } from './server/auth';
import { PairingQR } from './server/protocol';

let server: Server | null = null;
let tunnel: { stop(): Promise<void> } | null = null;
let statusBar: vscode.StatusBarItem;
let qrPanel: vscode.WebviewPanel | null = null;
let lastQR: PairingQR | null = null;

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

  const auth = new Auth(vscode.workspace.getConfiguration().get<number>('remoteDev.tokenExpiryMinutes', 1440));
  auth.startGC();

  ctx.subscriptions.push(
    vscode.commands.registerCommand('remotedev.start', () => start(auth)),
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

async function start(auth: Auth) {
  if (server) { vscode.window.showInformationMessage('RemoteDev already running.'); return; }
  const cfg = vscode.workspace.getConfiguration().get<any>('remoteDev', {});
  const root = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
  if (!root) { vscode.window.showErrorMessage('Open a folder first.'); return; }

  server = new Server({
    port: cfg.localPort ?? 0,
    workspaceRoot: root,
    auth,
    maxTerminals: cfg.maxTerminals ?? 16,
  });

  const port = await server.listen();

  const preferred = vscode.workspace.getConfiguration().get<'auto' | 'tailscale' | 'devtunnel' | 'ssh'>('remoteDev.preferredTunnel', 'auto');
  const provider = await detect(preferred);
  const pub = await provider.start(port);
  tunnel = provider;

  lastQR = server.buildPairingQR(pub.publicHost, pub.publicPort);
  await showQR(lastQR);
  updateStatus(auth.connectedDeviceCount());
}

async function stop() {
  if (tunnel) await tunnel.stop().catch(() => {});
  if (server) server.close();
  tunnel = null; server = null;
  updateStatus(0);
  vscode.window.showInformationMessage('RemoteDev stopped.');
}

async function showQR(qr: PairingQR | null) {
  if (!qr) { vscode.window.showInformationMessage('No active session.'); return; }
  if (qrPanel) { qrPanel.reveal(); return; }
  qrPanel = vscode.window.createWebviewPanel('remotedev.qr', 'RemoteDev — Pair', vscode.ViewColumn.Two, { enableScripts: false });
  qrPanel.onDidDispose(() => qrPanel = null);
  const dataUrl = await QRCode.toDataURL(JSON.stringify(qr), { errorCorrectionLevel: 'M', margin: 1, scale: 6 });
  qrPanel.webview.html = `
    <html><body style="font-family:system-ui;background:#0e0e10;color:#fff;display:flex;flex-direction:column;align-items:center;padding:24px">
      <h2>Scan with the PocketCode app</h2>
      <img src="${dataUrl}" style="background:#fff;padding:12px;border-radius:8px"/>
      <p style="opacity:.7;max-width:340px;text-align:center;margin-top:12px">
        URL: <code>${qr.url}</code><br/>
        Fingerprint: <code>${qr.fp}</code><br/>
        Expires: ${new Date(qr.exp).toLocaleString()}
      </p>
      <p style="opacity:.5;margin-top:24px">Token is shown ONCE. Pairing binds this device until you Disconnect All.</p>
    </body></html>`;
}

export function deactivate() { stop(); }
