/**
 * Conservative normalisation of the human-readable streams produced by the
 * two CLIs PocketCode supports natively in v1. This intentionally does not
 * parse TUI state or replace the terminal: unmatched output remains available
 * verbatim through `term.data` in the raw-terminal fallback.
 */
export type SupportedAgent = 'claude-code' | 'codex-cli';
export type NormalizedAgentEventType = 'message' | 'tool_call' | 'question' | 'diff';

export interface NormalizedAgentEvent {
  type: NormalizedAgentEventType;
  content: string;
  agentId: SupportedAgent;
  timestamp: number;
}

interface AgentSession {
  agentId: SupportedAgent;
  pending: string;
  last?: Pick<NormalizedAgentEvent, 'type' | 'content'>;
}

const ANSI = /\u001B(?:\][^\u0007\u001B]*(?:\u0007|\u001B\\)|\[[0-?]*[ -/]*[@-~]|.)/g;
const CLAUDE_TOOL = /^(?:⏺|●)\s*(Read|Write|Edit|Bash|Glob|Grep|Task|WebFetch|WebSearch)\((.+)\)$/i;
const CODEX_TOOL = /^(?:•|\*)\s*(Ran|Explored|Updated|Edited|Created|Deleted|Read|Wrote)\b[: ]*(.*)$/i;
const DIFF = /^(?:diff --git |@@ |(?:Edited|Created|Modified|Deleted|Wrote|Updated)\s+.+)/i;
const QUESTION = /(?:\?|\b(?:would you like|do you want|can you|which (?:option|approach)|please (?:choose|confirm))\b)/i;

/** Detect only explicit CLI launches, never infer an agent from arbitrary output. */
export function detectSupportedAgent(input: string): SupportedAgent | null {
  const command = input.trim().split(/\s+/);
  if (command.length === 0 || !command[0]) return null;
  const executable = command.find((part) => /^(?:claude|codex)$/i.test(part));
  if (executable?.toLowerCase() === 'claude') return 'claude-code';
  if (executable?.toLowerCase() === 'codex') return 'codex-cli';
  return null;
}

export class AgentEventNormalizer {
  private sessions = new Map<string, AgentSession>();
  private pendingCommands = new Map<string, string>();

  /**
   * Observe terminal keystrokes and activate on Enter. xterm forwards input a
   * key at a time, so inspecting each `term.input` frame independently would
   * never see the complete `claude` / `codex` command.
   */
  observeInput(tab: string, input: string): SupportedAgent | null {
    let command = this.pendingCommands.get(tab) ?? '';
    for (const char of input) {
      if (char === '\r' || char === '\n') {
        const agentId = detectSupportedAgent(command);
        command = '';
        if (agentId) {
          this.sessions.set(tab, { agentId, pending: '' });
          this.pendingCommands.delete(tab);
          return agentId;
        }
      } else if (char === '\b' || char === '\u007f') {
        command = command.slice(0, -1);
      } else if (char >= ' ' && char !== '\u001b') {
        command += char;
      }
    }
    this.pendingCommands.set(tab, command.slice(-2_000));
    return null;
  }

  startFromInput(tab: string, input: string): SupportedAgent | null {
    const agentId = detectSupportedAgent(input);
    if (agentId) this.sessions.set(tab, { agentId, pending: '' });
    return agentId;
  }

  consume(tab: string, chunk: string): NormalizedAgentEvent[] {
    const session = this.sessions.get(tab);
    if (!session) return [];
    const clean = chunk.replace(ANSI, '').replace(/\r/g, '\n');
    const combined = session.pending + clean;
    const lines = combined.split('\n');
    session.pending = lines.pop() ?? '';
    return lines.flatMap((line) => this.normaliseLine(session, line));
  }

  forget(tab: string) {
    this.sessions.delete(tab);
    this.pendingCommands.delete(tab);
  }

  private normaliseLine(session: AgentSession, raw: string): NormalizedAgentEvent[] {
    const line = raw.trim();
    if (!line || line.length < 3) return [];
    let type: NormalizedAgentEventType | null = null;
    let content = line;
    const claudeTool = line.match(CLAUDE_TOOL);
    const codexTool = line.match(CODEX_TOOL);
    if (claudeTool) {
      type = 'tool_call';
      content = `${claudeTool[1]}: ${claudeTool[2]}`;
    } else if (codexTool) {
      type = /^(?:Edited|Created|Deleted|Wrote|Updated)$/i.test(codexTool[1]) ? 'diff' : 'tool_call';
      content = `${codexTool[1]}${codexTool[2] ? `: ${codexTool[2]}` : ''}`;
    } else if (DIFF.test(line)) {
      type = 'diff';
    } else if (QUESTION.test(line)) {
      type = 'question';
    } else if (/^(?:⏺|●|•)\s+/.test(line)) {
      // Both CLIs prefix concise user-facing progress/final prose this way.
      type = 'message';
      content = line.replace(/^(?:⏺|●|•)\s+/, '');
    }
    if (!type || !content) return [];
    content = content.slice(0, 800);
    if (session.last?.type === type && session.last.content === content) return [];
    session.last = { type, content };
    return [{ type, content, agentId: session.agentId, timestamp: Date.now() }];
  }
}
