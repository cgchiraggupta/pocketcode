import { simpleGit, SimpleGit } from 'simple-git';

export class GitManager {
  private g: SimpleGit;
  constructor(root: string) { this.g = simpleGit(root); }

  async status() {
    return this.g.status();
  }

  async diff(path?: string, staged = false) {
    return staged ? this.g.diff(['--staged', ...(path ? ['--', path] : [])])
                  : this.g.diff([...(path ? ['--', path] : [])]);
  }

  async stage(paths: string[]) { return this.g.add(paths); }
  async unstage(paths: string[]) { return this.g.reset(['HEAD', '--', ...paths]); }

  async commit(message: string, amend = false) {
    return amend ? this.g.commit(message, ['--amend']) : this.g.commit(message);
  }
  async push(remote = 'origin', branch?: string) { return branch ? this.g.push(remote, branch) : this.g.push(); }
  async pull(remote = 'origin', branch?: string) { return branch ? this.g.pull(remote, branch) : this.g.pull(); }

  async branches() {
    const b = await this.g.branchLocal();
    return { current: b.current, all: b.all };
  }

  async checkout(name: string, create = false) {
    return create ? this.g.checkoutLocalBranch(name) : this.g.checkout(name);
  }

  async log(max = 50) {
    const out = await this.g.raw(['log', `--max-count=${max}`, '--pretty=format:%H%x09%an%x09%ae%x09%at%x09%s']);
    return out.split('\n').map((l) => {
      const [hash, author, email, ts, ...rest] = l.split('\t');
      return { hash, author, email, ts: Number(ts) * 1000, subject: rest.join('\t') };
    });
  }
}
