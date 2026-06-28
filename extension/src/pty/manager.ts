import { EventEmitter } from 'node:events';
import { randomUUID } from 'node:crypto';
import * as os from 'node:os';
import * as pty from 'node-pty';

export interface PtyTab {
  id: string;
  title: string;
  alive: boolean;
  exitCode?: number;
}

export class PtyManager extends EventEmitter {
  private tabs = new Map<string, pty.IPty>();
  private titles = new Map<string, string>();
  private maxTabs: number;

  constructor(maxTabs: number) {
    super();
    this.maxTabs = maxTabs;
  }

  list(): PtyTab[] {
    return Array.from(this.tabs.entries()).map(([id, p]) => ({
      id, title: this.titles.get(id) ?? id.slice(0, 6), alive: p.pid > 0,
    }));
  }

  open(opts?: { cwd?: string; cols?: number; rows?: number; title?: string }): string {
    if (this.tabs.size >= this.maxTabs) throw new Error(`max terminals (${this.maxTabs}) reached`);
    const id = randomUUID();
    const shell = process.env.SHELL || (os.platform() === 'win32' ? 'powershell.exe' : 'bash');
    const p = pty.spawn(shell, [], {
      name: 'xterm-256color',
      cols: opts?.cols ?? 100,
      rows: opts?.rows ?? 30,
      cwd: opts?.cwd ?? os.homedir(),
      env: { ...process.env, TERM: 'xterm-256color', COLORTERM: 'truecolor' },
    });
    this.tabs.set(id, p);
    this.titles.set(id, opts?.title ?? `term-${this.tabs.size}`);
    p.onData((data) => this.emit('data', id, data));
    p.onExit(({ exitCode }) => {
      this.emit('exit', id, exitCode);
    });
    return id;
  }

  write(id: string, data: string) { this.tabs.get(id)?.write(data); }
  resize(id: string, cols: number, rows: number) { try { this.tabs.get(id)?.resize(cols, rows); } catch { /* ponytail: resize races are fine */ } }
  rename(id: string, title: string) { this.titles.set(id, title); }

  close(id: string) {
    const p = this.tabs.get(id);
    if (!p) return;
    try { p.kill(); } catch { /* ponytail: already dead */ }
    this.tabs.delete(id);
    this.titles.delete(id);
  }

  closeAll() { for (const id of [...this.tabs.keys()]) this.close(id); }
}
