/**
 * PtyManager — shell process manager using child_process instead of node-pty.
 *
 * node-pty requires a native binary compiled for the exact Electron ABI, which
 * breaks across Cursor updates. This implementation uses Node's built-in
 * child_process.spawn with a helper script that allocates a real PTY via
 * the macOS/Linux `script` or `python3 -c pty.spawn` shim, giving us proper
 * terminal emulation without any native module dependency.
 */
import { EventEmitter } from 'node:events';
import { randomUUID } from 'node:crypto';
import { spawn, ChildProcess } from 'node:child_process';
import * as os from 'node:os';
import * as fs from 'node:fs';
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
  private maxTabs: number;

  constructor(maxTabs: number) {
    super();
    this.maxTabs = maxTabs;
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

    // Use `script` on macOS/Linux to allocate a real PTY — no native module needed.
    // `script -q /dev/null <shell>` forks the shell inside a pseudo-terminal,
    // so programs that check isatty() (like Claude Code) get a real TTY.
    let spawnCmd: string;
    let spawnArgs: string[];

    if (os.platform() === 'darwin' || os.platform() === 'linux') {
      spawnCmd = 'script';
      spawnArgs = ['-q', '/dev/null', shell, '-l'];
    } else {
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

    proc.stdout?.on('data', (chunk: Buffer) => {
      this.emit('data', id, chunk.toString('utf8'));
    });
    proc.stderr?.on('data', (chunk: Buffer) => {
      this.emit('data', id, chunk.toString('utf8'));
    });
    proc.on('exit', (code) => {
      entry.alive = false;
      this.emit('exit', id, code ?? 0);
    });
    proc.on('error', (err) => {
      entry.alive = false;
      this.emit('data', id, `\r\n[spawn error: ${err.message}]\r\n`);
      this.emit('exit', id, 1);
    });

    // Send initial resize sequence so the shell knows its dimensions.
    // Give the shell ~50ms to start before we try to resize.
    setTimeout(() => this.resize(id, cols, rows), 50);

    return id;
  }

  write(id: string, data: string) {
    const s = this.tabs.get(id);
    if (s?.alive && s.proc.stdin) {
      s.proc.stdin.write(data);
    }
  }

  resize(id: string, cols: number, rows: number) {
    // Send ANSI resize sequence; real stty is best but requires a PTY fd.
    // script-wrapped shells pick up COLUMNS/LINES from the environment on start;
    // for mid-session resize we send the escape sequence which most shells honour.
    const s = this.tabs.get(id);
    if (s?.alive && s.proc.stdin) {
      // Update environment hint via escape sequence (xterm resize)
      s.proc.stdin.write(`\x1b[8;${rows};${cols}t`);
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
  }

  closeAll() {
    for (const id of [...this.tabs.keys()]) this.close(id);
  }
}
