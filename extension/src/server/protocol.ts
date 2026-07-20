// Wire protocol shared between extension and android.
// Keep this file dependency-free so it can be copy-pasted into android.

export type SessionId = string;
export type TabId = string;
export type DeviceId = string;

export interface PairingQR {
  v: 1;
  url: string;          // wss://...
  token: string;        // raw, sent once
  fp: string;           // server cert / tunnel host fingerprint
  exp: number;          // epoch ms
}

export type WsMsg =
  | { t: 'term.open'; tab?: string; cols?: number; rows?: number; cwd?: string }
  | { t: 'term.input'; tab: string; data: string }
  | { t: 'term.resize'; tab: string; cols: number; rows: number }
  | { t: 'term.close'; tab: string }
  | { t: 'term.data'; tab: string; data: string }       // server -> client
  | { t: 'term.exit'; tab: string; code: number }
  | { t: 'term.list'; tabs: Array<{ id: string; title: string; alive: boolean }> }
  // Buffered scrollback replay: sent once per known tab immediately after
  // term.list on every (re)connect, so a client that was disconnected (wifi
  // drop, phone locked, tunnel blip) can repaint what it missed. `data` is
  // the server's capped ring buffer for that tab, not an incremental diff --
  // clients should treat it as authoritative and replace local scrollback
  // for that tab rather than appending.
  | { t: 'term.replay'; tab: string; data: string }

  | { t: 'fs.tree'; path?: string; depth?: number }
  | { t: 'fs.read'; path: string }
  | { t: 'fs.write'; path: string; content: string }
  | { t: 'fs.mkdir'; path: string }
  | { t: 'fs.rename'; from: string; to: string }
  | { t: 'fs.delete'; path: string; recursive?: boolean }
  | { t: 'fs.search'; query: string; regex?: boolean; max?: number }

  | { t: 'git.status' }
  | { t: 'git.diff'; path?: string; staged?: boolean }
  | { t: 'git.stage'; paths: string[] }
  | { t: 'git.unstage'; paths: string[] }
  | { t: 'git.commit'; message: string; amend?: boolean }
  | { t: 'git.push'; remote?: string; branch?: string }
  | { t: 'git.pull'; remote?: string; branch?: string }
  | { t: 'git.branches' }
  | { t: 'git.checkout'; name: string; create?: boolean }
  | { t: 'git.log'; max?: number }
  // Server acknowledgement for a completed Git mutation. Includes the fresh
  // porcelain status so clients can update the file list and stateful UI together.
  | { t: 'git.result'; action: 'stage' | 'unstage' | 'commit' | 'push' | 'pull'; current?: string | null; files: Array<unknown> }

  | { t: 'devservers' }
  | { t: 'devserver.start'; cmd: string; cwd?: string }        // launch a tracked dev server process
  | { t: 'devserver.stop'; pid: number }                       // stop a managed dev server
  | { t: 'devserver.log'; port: number; follow?: boolean }     // follow=true → stream stdout/stderr

  | { t: 'workspace.list' }
  | { t: 'workspace.switch'; folderUri: string }

  | { t: 'snapshot.create'; label?: string }
  | { t: 'snapshot.list' }
  | { t: 'snapshot.revert'; id: string }

  // Agent approval: sent by the android client when the user taps Approve/Reject on a notification.
  // `session` is the specific PTY tab id the notification was for (see agent.event below).
  // The server writes "y\n" or "n\n" to that tab; falls back to the most recently active
  // alive tab only if the requested one is already gone (e.g. a stale tap on an old notification).
  | { t: 'agent.approve'; session: string }
  | { t: 'agent.reject'; session: string }

  // Token refresh: sent server→client before the pairing token expires.
  // The client should persist the new token and use it on the next reconnect.
  | { t: 'token.refresh'; token: string; exp: number }

  | { t: 'pong' }
  | { t: 'error'; msg: string; trace?: string }
  | { t: 'agent.event'; tab: string; kind: string; payload: unknown };
