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

  | { t: 'devservers' }
  | { t: 'devserver.log'; port: number; follow?: boolean }

  | { t: 'workspace.list' }
  | { t: 'workspace.switch'; folderUri: string }

  | { t: 'snapshot.create'; label?: string }
  | { t: 'snapshot.list' }
  | { t: 'snapshot.revert'; id: string }

  | { t: 'pong' }
  | { t: 'error'; msg: string; trace?: string }
  | { t: 'agent.event'; kind: string; payload: unknown };
