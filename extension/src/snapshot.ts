import { simpleGit } from 'simple-git';
import { randomUUID } from 'node:crypto';

export interface Snapshot { id: string; label: string; tag: string; createdAt: number; }

export class Snapshots {
  private list: Snapshot[] = [];
  constructor(private root: string) {}

  async create(label?: string): Promise<Snapshot> {
    const g = simpleGit(this.root);
    const id = randomUUID().slice(0, 8);
    const tag = `pocketcode-snap-${Date.now()}-${id}`;
    await g.raw(['stash', 'push', '-u', '-m', tag]);
    await g.raw(['tag', tag]);
    const s: Snapshot = { id, label: label ?? tag, tag, createdAt: Date.now() };
    this.list.push(s);
    return s;
  }

  async revert(id: string): Promise<void> {
    const s = this.list.find((x) => x.id === id);
    if (!s) throw new Error('snapshot not found');
    const g = simpleGit(this.root);
    await g.raw(['reset', '--hard', s.tag]);
    await g.raw(['stash', 'pop']).catch(() => { /* ponytail: empty stash is fine */ });
    this.list = this.list.filter((x) => x.id !== id);
  }
}
