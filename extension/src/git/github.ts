import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

const execFileAsync = promisify(execFile);

export interface PullRequestSummary {
  number: number;
  title: string;
  author: string;
  state: 'open' | 'closed';
  draft: boolean;
  updatedAt: string;
  head: string;
  base: string;
}

export interface PullRequestFile {
  filename: string;
  status: string;
  additions: number;
  deletions: number;
  patch?: string;
}

export interface PullRequestCommit {
  sha: string;
  message: string;
  author: string;
}

export interface PullRequestDetail extends PullRequestSummary {
  body: string;
  files: PullRequestFile[];
  commits: PullRequestCommit[];
}

type Repo = { owner: string; name: string };

/** GitHub REST client. The access token stays in the desktop extension process. */
export class GitHubClient {
  constructor(private readonly root: string, private readonly getToken: () => Promise<string>) {}

  private async repo(): Promise<Repo> {
    const { stdout } = await execFileAsync('git', ['remote', 'get-url', 'origin'], { cwd: this.root });
    const remote = stdout.trim();
    const match = remote.match(/github\.com[/:]([^/]+)\/([^/#]+?)(?:\.git)?$/i);
    if (!match) throw new Error('The current repository does not have a GitHub origin.');
    return { owner: match[1], name: match[2] };
  }

  private async request(path: string, init?: RequestInit): Promise<any> {
    const token = await this.getToken();
    const res = await fetch(`https://api.github.com${path}`, {
      ...init,
      headers: {
        Accept: 'application/vnd.github+json',
        Authorization: `Bearer ${token}`,
        'X-GitHub-Api-Version': '2022-11-28',
        ...(init?.headers ?? {}),
      },
    });
    if (!res.ok) throw new Error(`GitHub ${res.status}: ${await res.text()}`);
    return res.status === 204 ? null : res.json();
  }

  async listPullRequests(): Promise<PullRequestSummary[]> {
    const { owner, name } = await this.repo();
    const prs = await this.request(`/repos/${owner}/${name}/pulls?state=open&per_page=50`);
    return prs.map((pr: any) => this.summary(pr));
  }

  async getPullRequest(number: number): Promise<PullRequestDetail> {
    const { owner, name } = await this.repo();
    const [pr, files, commits] = await Promise.all([
      this.request(`/repos/${owner}/${name}/pulls/${number}`),
      this.request(`/repos/${owner}/${name}/pulls/${number}/files?per_page=100`),
      this.request(`/repos/${owner}/${name}/pulls/${number}/commits?per_page=100`),
    ]);
    return {
      ...this.summary(pr), body: pr.body ?? '',
      files: files.map((f: any) => ({ filename: f.filename, status: f.status, additions: f.additions, deletions: f.deletions, patch: f.patch })),
      commits: commits.map((c: any) => ({ sha: c.sha, message: c.commit?.message ?? '', author: c.commit?.author?.name ?? c.author?.login ?? 'Unknown' })),
    };
  }

  async mergePullRequest(number: number, method: 'merge' | 'squash' | 'rebase' = 'squash'): Promise<void> {
    const { owner, name } = await this.repo();
    await this.request(`/repos/${owner}/${name}/pulls/${number}/merge`, { method: 'PUT', body: JSON.stringify({ merge_method: method }) });
  }

  async closePullRequest(number: number): Promise<void> {
    const { owner, name } = await this.repo();
    await this.request(`/repos/${owner}/${name}/pulls/${number}`, { method: 'PATCH', body: JSON.stringify({ state: 'closed' }) });
  }

  private summary(pr: any): PullRequestSummary {
    return { number: pr.number, title: pr.title, author: pr.user?.login ?? 'Unknown', state: pr.state, draft: Boolean(pr.draft), updatedAt: pr.updated_at, head: pr.head?.ref ?? '', base: pr.base?.ref ?? '' };
  }
}
