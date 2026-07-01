import { promises as fs } from 'node:fs';
import * as path from 'node:path';

export interface FsNode {
  name: string;
  path: string;
  type: 'file' | 'dir';
  size?: number;
  children?: FsNode[];
}

const IGNORE = new Set(['node_modules', '.git', '.DS_Store', 'dist', 'out', 'build', '.next', '.venv']);

export class FilesManager {
  constructor(private root: string) {}

  async tree(rel = '.', depth = 4): Promise<FsNode[]> {
    const abs = this.resolve(rel);
    const stat = await fs.stat(abs);
    if (!stat.isDirectory()) {
      return [{ name: path.basename(abs), path: rel, type: 'file', size: stat.size }];
    }
    const out: FsNode[] = [];
    const entries = await fs.readdir(abs, { withFileTypes: true });
    for (const e of entries) {
      if (IGNORE.has(e.name)) continue;
      const childRel = rel === '.' ? e.name : path.posix.join(rel, e.name);
      if (e.isDirectory()) {
        const node: FsNode = { name: e.name, path: childRel, type: 'dir' };
        if (depth > 0) node.children = await this.tree(childRel, depth - 1);
        out.push(node);
      } else if (e.isFile()) {
        const s = await fs.stat(path.join(abs, e.name));
        out.push({ name: e.name, path: childRel, type: 'file', size: s.size });
      }
    }
    return out.sort((a, b) => (a.type === b.type ? a.name.localeCompare(b.name) : a.type === 'dir' ? -1 : 1));
  }

  resolve(rel: string): string {
    const abs = path.resolve(this.root, rel);
    // ponytail: trust boundary. Append a separator to the root so that
    // `/project-evil/` doesn't pass the startsWith check against `/project`.
    const rootWithSep = this.root.endsWith(path.sep) ? this.root : this.root + path.sep;
    if (abs !== this.root && !abs.startsWith(rootWithSep)) throw new Error('path escapes workspace');
    return abs;
  }

  async read(rel: string): Promise<string> { return fs.readFile(this.resolve(rel), 'utf8'); }
  async write(rel: string, content: string) { await fs.mkdir(path.dirname(this.resolve(rel)), { recursive: true }); await fs.writeFile(this.resolve(rel), content); }
  async mkdir(rel: string) { await fs.mkdir(this.resolve(rel), { recursive: true }); }
  async rename(from: string, to: string) { await fs.rename(this.resolve(from), this.resolve(to)); }
  async delete(rel: string, recursive = false) { await fs.rm(this.resolve(rel), { recursive, force: true }); }

  async search(query: string, regex = false, max = 200): Promise<Array<{ path: string; line: number; text: string }>> {
    const re = regex ? new RegExp(query) : null;
    const needle = query.toLowerCase();
    const out: Array<{ path: string; line: number; text: string }> = [];
    const walk = async (dir: string, rel: string) => {
      if (out.length >= max) return;
      const entries = await fs.readdir(dir, { withFileTypes: true });
      for (const e of entries) {
        if (out.length >= max) return;
        if (IGNORE.has(e.name)) continue;
        const abs = path.join(dir, e.name);
        const childRel = path.posix.join(rel, e.name);
        if (e.isDirectory()) await walk(abs, childRel);
        else if (e.isFile()) {
          const st = await fs.stat(abs).catch(() => null);
          if (st && st.size < 1_000_000) {
          const text = await fs.readFile(abs, 'utf8').catch(() => '');
          const lines = text.split('\n');
          for (let i = 0; i < lines.length; i++) {
            const match = re ? re.test(lines[i]) : lines[i].toLowerCase().includes(needle);
            if (match) out.push({ path: childRel, line: i + 1, text: lines[i].slice(0, 400) });
            if (out.length >= max) return;
          }
          }
        }
      }
    };
    await walk(this.root, '.');
    return out;
  }
}
