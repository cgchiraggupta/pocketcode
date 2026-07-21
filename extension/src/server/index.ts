import { createServer, IncomingMessage, ServerResponse } from 'node:http';
import { WebSocketServer, WebSocket } from 'ws';
import * as path from 'node:path';
import { pathToFileURL } from 'node:url';
import { Auth } from './auth';
import { PtyManager } from '../pty/manager';
import { FilesManager } from '../files/manager';
import { GitManager } from '../git/manager';
import { listDevServers, DevServerRegistry } from '../devservers';
import { Snapshots } from '../snapshot';
import { ApprovalDetector } from '../agent-detector';
import { AgentEventNormalizer } from '../agent-events';
import { WsMsg, PairingQR } from './protocol';
import { fingerprint, newToken } from '../security/token';
import { EventEmitter } from 'node:events';

export interface WorkspaceFolderInfo {
  name: string;
  uri: string;
}

export interface ServerOpts {
  port: number;                    // local port to bind (loopback)
  workspaceRoot: string;
  auth: Auth;
  maxTerminals: number;
  /** Optional multi-folder listing (VS Code host). Headless defaults to single root. */
  listWorkspaces?: () => WorkspaceFolderInfo[];
  /** Optional open-folder hook (VS Code only). Headless returns an error. */
  openWorkspaceFolder?: (folderUri: string) => void;
}

interface ClientState {
  deviceId: string;
  token: string;               // raw token this client authenticated with
  logUnsubs: Map<number, () => void>;  // port -> unsubscribe fn for streaming logs
}

export class Server extends EventEmitter {
  private http = createServer((req, res) => this.handleHttp(req, res));
  private bindHost = '127.0.0.1';
  private wss = new WebSocketServer({ noServer: true });
  private clients = new Map<WebSocket, ClientState>();
  pty: PtyManager;
  files: FilesManager;
  git: GitManager;
  snaps: Snapshots;
  devRegistry = new DevServerRegistry();
  private approvalDetector = new ApprovalDetector();
  private agentEvents = new AgentEventNormalizer();

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
        this.clients.set(ws, { deviceId: dev.id, token, logUnsubs: new Map() });
      });
    });

    this.pty.on('data', (id, data) => {
      this.broadcast({ t: 'term.data', tab: id, data });
      for (const event of this.agentEvents.consume(id, data)) {
        this.broadcast({ t: 'agent.event', tab: id, event });
      }
      // Agent-agnostic: scan raw PTY output for a y/n-style approval prompt.
      // Previously nothing ever produced 'agent.event' -- the approve/reject
      // wire path existed but had no trigger telling the phone when to fire it.
      const snippet = this.approvalDetector.check(id, data);
      if (snippet) {
        this.broadcast({ t: 'agent.event', tab: id, kind: 'awaiting_approval', payload: { snippet } });
      }
    });
    this.pty.on('exit', (id, code) => {
      this.broadcast({ t: 'term.exit', tab: id, code });
      this.approvalDetector.forget(id);
      this.agentEvents.forget(id);
    });
    opts.auth.on('device.bound', (d) => this.emit('device.bound', d));
    opts.auth.on('device.fingerprintMismatch', (d) => this.emit('device.fingerprintMismatch', d));
  }

  listen(): Promise<number> {
    return new Promise((resolve) => {
      this.http.listen(this.opts.port, this.bindHost, () => {
        const addr = this.http.address();
        if (addr && typeof addr === 'object') resolve(addr.port);
        else resolve(this.opts.port);
      });
    });
  }

  rebindAll() {
    this.bindHost = '0.0.0.0';
    // ponytail: if already listening, close and re-listen. Cheap and only happens once on tailscale-ip start.
    if (this.http.listening) {
      this.http.close(() => {
        this.http.listen(this.opts.port, this.bindHost);
      });
    }
  }

  close() {
    this.pty.closeAll();
    for (const ws of this.clients.keys()) ws.close();
    this.http.close();
  }

  buildPairingQR(publicHost: string, publicPort: number, secure: boolean, certFp?: string): PairingQR {
    const token = newToken();
    this.opts.auth.issue(token);   // issue now; bind on first connect
    const scheme = secure ? 'wss' : 'ws';
    const url = `${scheme}://${publicHost}:${publicPort}/ws`;
    return { v: 1, url, token, fp: fingerprint(publicHost, publicPort, certFp), exp: Date.now() + this.opts.auth['expiryMs'] };
  }

  private broadcast(msg: WsMsg) {
    const j = JSON.stringify(msg);
    for (const ws of this.clients.keys()) if (ws.readyState === ws.OPEN) ws.send(j);
  }

  private send(ws: WebSocket, msg: WsMsg) {
    if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(msg));
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
        const reply = await this.dispatch(msg, ws);
        if (reply) ws.send(JSON.stringify(reply));
      } catch (e: unknown) {
        ws.send(JSON.stringify({ t: 'error', msg: e instanceof Error ? e.message : String(e) }));
      }
    });
    ws.on('close', () => {
      // Clean up any active log stream subscriptions for this client
      const state = this.clients.get(ws);
      if (state) {
        for (const unsub of state.logUnsubs.values()) unsub();
        this.clients.delete(ws);
      }

      // Proactively refresh the token if it's expiring soon, then emit to all remaining clients
      // (handled in extension.ts via 'token.refresh' event on server — not needed here directly).
    });
    ws.send(JSON.stringify({ t: 'term.list', tabs: this.pty.list() }));

    // Reconnect/scrollback support: replay each tab's buffered output right
    // after the tab list, so a client that was disconnected (wifi drop, phone
    // locked, tunnel blip) can repaint what it missed instead of showing a
    // blank terminal. One term.replay per tab that has anything buffered.
    for (const tab of this.pty.list()) {
      const buffered = this.pty.getBuffer(tab.id);
      if (buffered) this.send(ws, { t: 'term.replay', tab: tab.id, data: buffered });
    }

    // Proactively offer a token refresh to this newly-connected client if the token is close to expiry.
    const state = this.clients.get(ws);
    if (state) {
      const renewal = this.opts.auth.renewIfExpiring(state.token);
      if (renewal) {
        state.token = renewal.newRawToken;
        this.send(ws, { t: 'token.refresh', token: renewal.newRawToken, exp: renewal.exp });
      }
    }
  }

  private async dispatch(msg: WsMsg, ws: WebSocket): Promise<WsMsg | null> {
    switch (msg.t) {
      case 'term.open': {
        this.pty.open({ cols: msg.cols, rows: msg.rows, cwd: msg.cwd });
        return { t: 'term.list', tabs: this.pty.list() };
      }
      case 'term.input':
        this.agentEvents.observeInput(msg.tab, msg.data);
        this.pty.write(msg.tab, msg.data);
        return null;
      case 'term.resize': this.pty.resize(msg.tab, msg.cols, msg.rows); return null;
      case 'term.close':
        this.agentEvents.forget(msg.tab);
        this.pty.close(msg.tab);
        return { t: 'term.list', tabs: this.pty.list() };
      case 'term.list': return { t: 'term.list', tabs: this.pty.list() };

      case 'fs.tree': return { t: 'fs.tree' as any, ...(await this.tree(msg.path, msg.depth)) } as any;
      case 'fs.read': return { t: 'fs.read' as any, path: msg.path, content: await this.files.read(msg.path) } as any;
      // A mobile save can change the repository state while the Git panel is
      // already open. Return the refreshed status in the same response so the
      // client never has to guess whether it should reload the changed-files list.
      case 'fs.write':
        await this.files.write(msg.path, (msg as any).content);
        return { t: 'git.status' as any, ...(await this.git.status() as any) } as any;
      case 'fs.mkdir': await this.files.mkdir(msg.path); return null;
      case 'fs.rename': await this.files.rename((msg as any).from, (msg as any).to); return null;
      case 'fs.delete': await this.files.delete(msg.path, (msg as any).recursive); return null;
      case 'fs.search': return { t: 'fs.search' as any, results: await this.files.search((msg as any).query, (msg as any).regex, (msg as any).max) } as any;

      case 'git.status': return { t: 'git.status' as any, ...(await this.git.status() as any) } as any;
      case 'git.diff': return { t: 'git.diff' as any, text: await this.git.diff(msg.path, msg.staged) } as any;
      case 'git.stage': await this.git.stage(msg.paths); return { t: 'git.result', action: 'stage', ...(await this.git.status()) };
      case 'git.unstage': await this.git.unstage(msg.paths); return { t: 'git.result', action: 'unstage', ...(await this.git.status()) };
      case 'git.commit': await this.git.commit(msg.message, msg.amend); return { t: 'git.result', action: 'commit', ...(await this.git.status()) };
      case 'git.push': await this.git.push(); return { t: 'git.result', action: 'push', ...(await this.git.status()) };
      case 'git.pull': await this.git.pull(); return { t: 'git.result', action: 'pull', ...(await this.git.status()) };
      case 'git.branches': return { t: 'git.branches' as any, ...(await this.git.branches()) } as any;
      case 'git.checkout':
        await this.git.checkout(msg.name, msg.create);
        return { t: 'git.result', action: 'checkout', ...(await this.git.status()) };
      case 'git.log': return { t: 'git.log' as any, entries: await this.git.log(msg.max) } as any;

      case 'devservers': {
        // Merge managed + discovered via lsof
        const discovered = await listDevServers();
        const managed = this.devRegistry.getAll().map(s => ({ pid: s.pid, cmd: s.cmd, port: s.port, managed: true }));
        const managedPids = new Set(managed.map(s => s.pid));
        const merged = [...managed, ...discovered.filter(s => !managedPids.has(s.pid))];
        return { t: 'devservers' as any, list: merged } as any;
      }

      case 'devserver.start': {
        const srv = this.devRegistry.start((msg as any).cmd, (msg as any).cwd ?? this.opts.workspaceRoot);
        return { t: 'devservers' as any, list: this.devRegistry.getAll() } as any;
      }

      case 'devserver.stop': {
        const stopped = this.devRegistry.stop((msg as any).pid);
        if (!stopped) return { t: 'error', msg: `No managed dev server with pid ${(msg as any).pid}` };
        return { t: 'devservers' as any, list: this.devRegistry.getAll() } as any;
      }

      case 'devserver.log': {
        const port = (msg as any).port as number;
        const follow = (msg as any).follow as boolean | undefined;

        // First send any static info for unmanaged servers
        const discovered = await listDevServers();
        const unmanaged = discovered.find(s => s.port === port);

        const state = this.clients.get(ws);
        if (!state) return null;

        if (follow) {
          // Cancel any existing stream for this port first
          const existing = state.logUnsubs.get(port);
          if (existing) { existing(); state.logUnsubs.delete(port); }

          const unsub = this.devRegistry.stream(port, (chunk) => {
            this.send(ws, { t: 'devserver.log' as any, port, data: chunk } as any);
          });
          if (unsub) {
            state.logUnsubs.set(port, unsub);
            return null;   // streaming; chunks delivered async
          }
          // Not a managed server — fall through to static info
        }

        if (unmanaged) {
          return { t: 'devserver.log' as any, port, data: `Dev Server (unmanaged)\nCommand: ${unmanaged.cmd}\nPID: ${unmanaged.pid}\nPort: ${port}\n\nTip: start via "devserver.start" to enable live log streaming.` } as any;
        }
        return { t: 'error', msg: `No dev server on port ${port}` };
      }

      case 'workspace.list': {
        const list = this.opts.listWorkspaces?.() ?? [{
          name: path.basename(this.opts.workspaceRoot) || this.opts.workspaceRoot,
          uri: pathToFileURL(this.opts.workspaceRoot).href,
        }];
        return { t: 'workspace.list' as any, list } as any;
      }
      case 'workspace.switch': {
        if (!this.opts.openWorkspaceFolder) {
          return { t: 'error', msg: 'workspace.switch is only available inside VS Code/Cursor' };
        }
        this.opts.openWorkspaceFolder((msg as any).folderUri);
        return null;
      }

      case 'snapshot.create': return { t: 'snapshot.list' as any, ...(await this.snaps.create(msg.label)) } as any;
      case 'snapshot.list': return { t: 'snapshot.list' as any, list: this.snaps.list() } as any;
      case 'snapshot.revert': await this.snaps.revert(msg.id); return null;

      case 'agent.approve':
      case 'agent.reject': {
        // Forward the decision to the PTY tab the phone actually tapped
        // approve/reject for (msg.session carries that tab id -- see
        // protocol.ts). Only fall back to "most recently active alive tab"
        // if that specific tab is gone (e.g. it already exited), so a stale
        // tap on an old notification doesn't get misrouted to a *different*
        // live agent session when multiple terminals are running.
        // "y\n" for approve, "n\n" for reject — what Claude Code / Codex / Aider read on stdin.
        const answer = msg.t === 'agent.approve' ? 'y\n' : 'n\n';
        const tabs = this.pty.list();
        const alive = tabs.filter(t => t.alive);
        const requested = alive.find(t => t.id === (msg as any).session);
        const target = requested ?? alive[alive.length - 1];
        if (target) {
          this.pty.write(target.id, answer);
          this.approvalDetector.clear(target.id);
        }
        return null;
      }

      case 'pong': return null;   // ponytail: keepalive from client -- silently drop, don't error.
      case 'agent.event': return null;   // ponytail: client-originated event -- accept silently.
      case 'token.refresh': return null; // server-originated only; silently ignore if client echoes it.

      default: return { t: 'error', msg: `unknown message ${(msg as any).t}` };
    }
  }

  private async tree(path?: string, depth?: number) {
    const nodes = await this.files.tree(path, depth);
    return { nodes };
  }
}
