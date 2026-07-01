import { createServer, IncomingMessage, ServerResponse } from 'node:http';
import { WebSocketServer, WebSocket } from 'ws';
import * as vscode from 'vscode';
import { Auth } from './auth';
import { PtyManager } from '../pty/manager';
import { FilesManager } from '../files/manager';
import { GitManager } from '../git/manager';
import { listDevServers, portReachable } from '../devservers';
import { Snapshots } from '../snapshot';
import { WsMsg, PairingQR } from './protocol';
import { fingerprint, newToken } from '../security/token';
import { EventEmitter } from 'node:events';

export interface ServerOpts {
  port: number;                    // local port to bind (loopback)
  workspaceRoot: string;
  auth: Auth;
  maxTerminals: number;
}

export class Server extends EventEmitter {
  private http = createServer((req, res) => this.handleHttp(req, res));
  private wss = new WebSocketServer({ noServer: true });
  private clients = new Map<WebSocket, { deviceId: string; token: string }>();
  pty: PtyManager;
  files: FilesManager;
  git: GitManager;
  snaps: Snapshots;

  constructor(private opts: ServerOpts) {
    super();
    this.pty = new PtyManager(opts.maxTerminals);
    this.files = new FilesManager(opts.workspaceRoot);
    this.git = new GitManager(opts.workspaceRoot);
    this.snaps = new Snapshots(opts.workspaceRoot);

    this.wss.on('connection', (ws, req) => this.handleWs(ws, req));
    this.http.on('upgrade', (req, sock, head) => {
      const url = new URL(req.url ?? '/', 'http://x');
      if (url.pathname !== '/ws') { sock.destroy(); return; }
      const token = url.searchParams.get('token') ?? '';
      const fp = (req.headers['x-device-fingerprint'] as string) ?? 'unknown';
      const deviceId = (req.headers['x-device-id'] as string) ?? 'unknown';
      const dev = opts.auth.authenticate(token, deviceId, fp);
      if (!dev) { sock.write('HTTP/1.1 401 Unauthorized\r\n\r\n'); sock.destroy(); return; }
      this.wss.handleUpgrade(req, sock, head, (ws) => {
        this.wss.emit('connection', ws, req);
        this.clients.set(ws, { deviceId: dev.id, token });
      });
    });

    this.pty.on('data', (id, data) => this.broadcast({ t: 'term.data', tab: id, data }));
    this.pty.on('exit', (id, code) => this.broadcast({ t: 'term.exit', tab: id, code }));
    opts.auth.on('device.bound', (d) => this.emit('device.bound', d));
    opts.auth.on('device.fingerprintMismatch', (d) => this.emit('device.fingerprintMismatch', d));
  }

  listen(): Promise<number> {
    return new Promise((resolve) => {
      this.http.listen(this.opts.port, '127.0.0.1', () => {
        const addr = this.http.address();
        if (addr && typeof addr === 'object') resolve(addr.port);
        else resolve(this.opts.port);
      });
    });
  }

  close() {
    this.pty.closeAll();
    for (const ws of this.clients.keys()) ws.close();
    this.http.close();
  }

  buildPairingQR(publicHost: string, publicPort: number, certFp?: string): PairingQR {
    const token = newToken();
    this.opts.auth.issue(token);   // issue now; bind on first connect
    const url = `wss://${publicHost}:${publicPort}/ws`;
    return { v: 1, url, token, fp: fingerprint(publicHost, publicPort, certFp), exp: Date.now() + this.opts.auth['expiryMs'] };
  }

  private broadcast(msg: WsMsg) {
    const j = JSON.stringify(msg);
    for (const ws of this.clients.keys()) if (ws.readyState === ws.OPEN) ws.send(j);
  }

  private handleHttp(req: IncomingMessage, res: ServerResponse) {
    const url = new URL(req.url ?? '/', 'http://x');
    res.setHeader('content-type', 'application/json');
    if (url.pathname === '/api/health') { res.end(JSON.stringify({ ok: true, devices: this.opts.auth.connectedDeviceCount() })); return; }
    res.statusCode = 404;
    res.end(JSON.stringify({ error: 'not found' }));
  }

  private async handleWs(ws: WebSocket, _req: IncomingMessage) {
    ws.on('message', async (raw) => {
      let msg: WsMsg;
      try { msg = JSON.parse(raw.toString()); } catch { return ws.send(JSON.stringify({ t: 'error', msg: 'bad json' })); }
      try {
        const reply = await this.dispatch(msg);
        if (reply) ws.send(JSON.stringify(reply));
      } catch (e: any) {
        ws.send(JSON.stringify({ t: 'error', msg: e?.message ?? String(e) }));
      }
    });
    ws.send(JSON.stringify({ t: 'term.list', tabs: this.pty.list() }));
  }

  private async dispatch(msg: WsMsg): Promise<WsMsg | null> {
    switch (msg.t) {
      case 'term.open': {
        const id = this.pty.open({ cols: msg.cols, rows: msg.rows, cwd: msg.cwd });
        return { t: 'term.list', tabs: this.pty.list() };
      }
      case 'term.input': this.pty.write(msg.tab, msg.data); return null;
      case 'term.resize': this.pty.resize(msg.tab, msg.cols, msg.rows); return null;
      case 'term.close': this.pty.close(msg.tab); return { t: 'term.list', tabs: this.pty.list() };
      case 'term.list': return { t: 'term.list', tabs: this.pty.list() };

      case 'fs.tree': return { t: 'fs.tree' as any, ...(await this.tree(msg.path, msg.depth)) } as any;   // type-stripped for brevity
      case 'fs.read': return { t: 'fs.read' as any, path: msg.path, content: await this.files.read(msg.path) } as any;
      case 'fs.write': await this.files.write(msg.path, (msg as any).content); return null;
      case 'fs.mkdir': await this.files.mkdir(msg.path); return null;
      case 'fs.rename': await this.files.rename((msg as any).from, (msg as any).to); return null;
      case 'fs.delete': await this.files.delete(msg.path, (msg as any).recursive); return null;
      case 'fs.search': return { t: 'fs.search' as any, results: await this.files.search((msg as any).query, (msg as any).regex, (msg as any).max) } as any;

      case 'git.status': return { t: 'git.status' as any, ...(await this.git.status() as any) } as any;
      case 'git.diff': return { t: 'git.diff' as any, text: await this.git.diff(msg.path, msg.staged) } as any;
      case 'git.stage': await this.git.stage(msg.paths); return { t: 'git.status' as any, ...(await this.git.status() as any) } as any;
      case 'git.unstage': await this.git.unstage(msg.paths); return { t: 'git.status' as any, ...(await this.git.status() as any) } as any;
      case 'git.commit': await this.git.commit(msg.message, msg.amend); return null;
      case 'git.push': await this.git.push(); return null;
      case 'git.pull': await this.git.pull(); return null;
      case 'git.branches': return { t: 'git.branches' as any, ...(await this.git.branches()) } as any;
      case 'git.checkout': await this.git.checkout(msg.name, msg.create); return null;
      case 'git.log': return { t: 'git.log' as any, entries: await this.git.log(msg.max) } as any;

      case 'devservers': return { t: 'devservers' as any, list: await listDevServers() } as any;
      case 'devserver.log': {
        const servers = await listDevServers();
        const srv = servers.find(s => s.port === (msg as any).port);
        if (!srv) return { t: 'error', msg: `No dev server listening on port ${(msg as any).port}` };
        return { t: 'devserver.log' as any, port: (msg as any).port, data: `Dev Server Info:\nCommand: ${srv.cmd}\nPID: ${srv.pid}\nPort: ${srv.port}\n\nNote: capture logs by launching in terminal.` } as any;
      }
      case 'workspace.list': return { t: 'workspace.list' as any, list: vscode.workspace.workspaceFolders?.map(f => ({ name: f.name, uri: f.uri.toString() })) ?? [] } as any;
      case 'workspace.switch': {
        const uri = vscode.Uri.parse((msg as any).folderUri);
        vscode.commands.executeCommand('vscode.openFolder', uri);
        return null;
      }

      case 'snapshot.create': return { t: 'snapshot.list' as any, ...(await this.snaps.create(msg.label)) } as any;
      case 'snapshot.list': return { t: 'snapshot.list' as any, list: this.snaps.list() } as any;
      case 'snapshot.revert': await this.snaps.revert(msg.id); return null;

      case 'pong': return null;   // ponytail: keepalive from client -- silently drop, don't error.
      case 'agent.event': return null;   // ponytail: client-originated event -- accept silently.

      default: return { t: 'error', msg: `unknown message ${(msg as any).t}` };
    }
  }

  private async tree(path?: string, depth?: number) {
    const nodes = await this.files.tree(path, depth);
    return { nodes };
  }
}
