import { simpleGit } from 'simple-git';
import { randomUUID } from 'node:crypto';

export interface Snapshot { id: string; label: string; tag: string; createdAt: number; hasStash: boolean; }

export class Snapshots {
  private snaps: Snapshot[] = [];
  constructor(private root: string) {}

  list(): Snapshot[] { return this.snaps; }

  async create(label?: string): Promise<Snapshot> {
    const g = simpleGit(this.root);
    const id = randomUUID().slice(0, 8);
    const tag = `pocketcode-snap-${Date.now()}-${id}`;
    const status = await g.status();
    const hasChanges = !status.isClean();
    if (hasChanges) {
      // ponytail: only stash if there's something to stash. Otherwise `stash push`
      // is a no-op and `revert()` would try to pop a non-existent stash.
      await g.raw(['stash', 'push', '-u', '-m', tag]);
    }
    await g.raw(['tag', tag]);
    const s: Snapshot = { id, label: label ?? tag, tag, createdAt: Date.now(), hasStash: hasChanges };
    this.snaps.push(s);
    return s;
  }

  async revert(id: string): Promise<void> {
    const s = this.snaps.find((x) => x.id === id);
    if (!s) throw new Error('snapshot not found');
    const g = simpleGit(this.root);
    await g.raw(['reset', '--hard', s.tag]);
    if (s.hasStash) {
      await g.raw(['stash', 'pop']).catch(() => { /* ponytail: stash conflict -- caller should resolve */ });
    }
    this.snaps = this.snaps.filter((x) => x.id !== id);
  }
}
