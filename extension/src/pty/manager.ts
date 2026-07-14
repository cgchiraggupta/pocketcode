/**
 * PtyManager — shell process manager using a Python PTY helper.
 *
 * node-pty requires a native binary compiled for the exact Electron ABI, which
 * breaks across Cursor/VS Code updates. This implementation uses a small Python
 * script (pty-helper.py) that creates a real PTY via openpty(), forks the shell
 * onto the slave side, and relays I/O between our piped stdio and the PTY master.
 *
 * Why not `script -q /dev/null`?
 *   `script` calls tcgetattr(stdin) on startup. In the VS Code extension host,
 *   stdin is a pipe (not a TTY), so it fails with "Operation not supported on
 *   socket" and exits immediately. The Python helper never touches its own
 *   stdin's tty-ness — it creates a fresh master/slave pair internally.
 */
import { EventEmitter } from 'node:events';
import { randomUUID } from 'node:crypto';
import { spawn, ChildProcess } from 'node:child_process';
import * as os from 'node:os';
import * as path from 'node:path';

export interface PtyTab {
  id: string;
  title: string;
  alive: boolean;
  exitCode?: number;
}

interface ShellProc {
  proc: ChildProcess;
  alive: boolean;
}

export class PtyManager extends EventEmitter {
  private tabs = new Map<string, ShellProc>();
  private titles = new Map<string, string>();
  // Reconnect/scrollback support: per-tab ring buffer of recent output.
  // Previously nothing was retained -- a client that dropped and reconnected
  // (flaky wifi, phone locked, tunnel blip) got nothing that happened while
  // it was away. Capped so long-running sessions don't grow unbounded.
  private buffers = new Map<string, string>();
  private readonly maxBufferChars = 200_000; // ~200KB/tab, enough for meaningful scrollback
  private maxTabs: number;
  private helperPath: string;

  constructor(maxTabs: number) {
    super();
    this.maxTabs = maxTabs;
    // pty-helper.py lives next to the compiled JS in the out/ directory.
    // At build time it's copied there (see package.json scripts).  At dev
    // time it sits alongside the TS source.  We check both locations.
    const outPath = path.join(__dirname, 'pty-helper.py');
    const srcPath = path.join(__dirname, '..', 'src', 'pty', 'pty-helper.py');
    // __dirname at runtime is extension/out/pty/ (after tsc compile)
    this.helperPath = outPath;
    // Fallback: if the file doesn't exist next to the JS, try src path
    try {
      require('node:fs').accessSync(outPath);
    } catch {
      this.helperPath = srcPath;
    }
  }

  list(): PtyTab[] {
    return Array.from(this.tabs.entries()).map(([id, s]) => ({
      id,
      title: this.titles.get(id) ?? id.slice(0, 6),
      alive: s.alive,
    }));
  }

  open(opts?: { cwd?: string; cols?: number; rows?: number; title?: string }): string {
    if (this.tabs.size >= this.maxTabs) {
      throw new Error(`max terminals (${this.maxTabs}) reached`);
    }

    const id = randomUUID();
    const shell = process.env.SHELL || (os.platform() === 'win32' ? 'powershell.exe' : '/bin/bash');
    const cwd = opts?.cwd ?? os.homedir();
    const cols = opts?.cols ?? 220;
    const rows = opts?.rows ?? 50;

    let spawnCmd: string;
    let spawnArgs: string[];

    if (os.platform() === 'darwin' || os.platform() === 'linux') {
      // Use our Python PTY helper which creates a real PTY pair.
      // It accepts: shell, cols, rows as positional args.
      spawnCmd = 'python3';
      spawnArgs = [this.helperPath, shell, String(cols), String(rows)];
    } else {
      // Windows: no PTY shim, fall back to raw shell
      spawnCmd = shell;
      spawnArgs = [];
    }

    const proc = spawn(spawnCmd, spawnArgs, {
      cwd,
      env: {
        ...process.env,
        TERM: 'xterm-256color',
        COLORTERM: 'truecolor',
        COLUMNS: String(cols),
        LINES: String(rows),
        LANG: process.env.LANG ?? 'en_US.UTF-8',
      },
      stdio: ['pipe', 'pipe', 'pipe'],
    });

    const entry: ShellProc = { proc, alive: true };
    this.tabs.set(id, entry);
    this.titles.set(id, opts?.title ?? `bash-${this.tabs.size}`);

    const onOutput = (chunk: Buffer) => {
      const str = chunk.toString('utf8');
      this.appendToBuffer(id, str);
      this.emit('data', id, str);
    };
    proc.stdout?.on('data', onOutput);
    proc.stderr?.on('data', onOutput);
    proc.on('exit', (code) => {
      entry.alive = false;
      this.emit('exit', id, code ?? 0);
    });
    proc.on('error', (err) => {
      entry.alive = false;
      this.emit('data', id, `\r\n[spawn error: ${err.message}]\r\n`);
      this.emit('exit', id, 1);
    });

    return id;
  }

  private appendToBuffer(id: string, str: string) {
    const cur = (this.buffers.get(id) ?? '') + str;
    this.buffers.set(
      id,
      cur.length > this.maxBufferChars ? cur.slice(cur.length - this.maxBufferChars) : cur
    );
  }

  // Full buffered scrollback for a tab, sent to newly-(re)connected clients so
  // they can repaint what happened while disconnected. Empty string if unknown
  // tab or nothing buffered yet.
  getBuffer(id: string): string {
    return this.buffers.get(id) ?? '';
  }

  write(id: string, data: string) {
    const s = this.tabs.get(id);
    if (s?.alive && s.proc.stdin) {
      s.proc.stdin.write(data);
    }
  }

  resize(id: string, cols: number, rows: number) {
    // Out-of-band control sentinel.  pty-helper.py intercepts this in its
    // stdin relay and calls set_winsize() + SIGWINCH instead of forwarding
    // it as keystrokes (the old ANSI escape got typed into the shell).
    const s = this.tabs.get(id);
    if (s?.alive && s.proc.stdin) {
      s.proc.stdin.write(`\x00RESIZE:${cols}:${rows}\n`);
    }
  }

  rename(id: string, title: string) {
    this.titles.set(id, title);
  }

  close(id: string) {
    const s = this.tabs.get(id);
    if (!s) return;
    s.alive = false;
    try { s.proc.kill('SIGHUP'); } catch { /* already dead */ }
    this.tabs.delete(id);
    this.titles.delete(id);
    this.buffers.delete(id);
  }

  closeAll() {
    for (const id of [...this.tabs.keys()]) this.close(id);
  }
}
