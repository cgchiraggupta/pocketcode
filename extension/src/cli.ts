#!/usr/bin/env node
/**
 * pocketcode-cli — headless PocketCode server (no VS Code/Cursor required).
 *
 * Usage:
 *   node out/cli.js [cwd] [--port 0] [--tunnel local|auto|devtunnel|tailscale|tailscale-ip|ssh]
 *   npm run cli -- --tunnel local --port 8765
 *
 * Prints an ASCII QR + the pairing JSON payload so a phone can pair without
 * the extension webview. Same wire protocol as the VS Code extension.
 */
import * as fs from 'node:fs';
import * as path from 'node:path';
import * as QRCode from 'qrcode';
import { Auth } from './server/auth';
import { Server } from './server';
import { detect, TunnelPref } from './tunnel';

export interface CliArgs {
  cwd: string;
  port: number;
  tunnel: TunnelPref;
  tokenExpiryMinutes: number;
  maxTerminals: number;
  host?: string;          // for --tunnel local
  sshTarget?: string;
  help: boolean;
}

export function parseArgs(argv: string[]): CliArgs {
  const args: CliArgs = {
    cwd: process.cwd(),
    port: 0,
    tunnel: 'auto',
    tokenExpiryMinutes: 10_080,
    maxTerminals: 16,
    help: false,
  };
  const pos: string[] = [];
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '-h' || a === '--help') args.help = true;
    else if (a === '--port') args.port = Number(argv[++i]);
    else if (a === '--tunnel') args.tunnel = argv[++i] as TunnelPref;
    else if (a === '--token-expiry-minutes') args.tokenExpiryMinutes = Number(argv[++i]);
    else if (a === '--max-terminals') args.maxTerminals = Number(argv[++i]);
    else if (a === '--host') args.host = argv[++i];
    else if (a === '--ssh-target') args.sshTarget = argv[++i];
    else if (a.startsWith('-')) throw new Error(`Unknown flag: ${a}`);
    else pos.push(a);
  }
  if (pos[0]) args.cwd = path.resolve(pos[0]);
  return args;
}

function printHelp() {
  console.log(`pocketcode-cli — headless PocketCode (CodeMote-style, no editor)

Usage:
  pocketcode-cli [cwd] [options]

Options:
  --port <n>                   Local listen port (0 = ephemeral). Default: 0
  --tunnel <name>              auto|local|devtunnel|tailscale|tailscale-ip|ssh
  --host <ip>                  Public host when --tunnel local (default: LAN IP)
  --ssh-target <user@host>     SSH reverse-tunnel target
  --token-expiry-minutes <n>   Pairing token lifetime (default: 10080 = 7d)
  --max-terminals <n>          Max concurrent PTY tabs (default: 16)
  -h, --help                   Show this help

Env:
  REMOTEDEV_SSH_TARGET         Fallback SSH target
  REMOTEDEV_SSH_REMOTE_PORT    Fallback SSH remote port
`);
}

export async function main(argv: string[] = process.argv.slice(2)): Promise<void> {
  const args = parseArgs(argv);
  if (args.help) {
    printHelp();
    return;
  }

  if (!fs.existsSync(args.cwd) || !fs.statSync(args.cwd).isDirectory()) {
    throw new Error(`cwd is not a directory: ${args.cwd}`);
  }

  if (args.sshTarget) process.env.REMOTEDEV_SSH_TARGET = args.sshTarget;

  const auth = new Auth(args.tokenExpiryMinutes);
  auth.startGC();

  const server = new Server({
    port: args.port,
    workspaceRoot: args.cwd,
    auth,
    maxTerminals: args.maxTerminals,
  });

  const localPort = await server.listen();

  const provider = await detect(args.tunnel, { localHost: args.host });
  // local / tailscale-ip need non-loopback bind so the phone can reach us.
  if (provider.name === 'local' || provider.name === 'tailscale-ip') {
    server.rebindAll();
  }

  const pub = await provider.start(localPort);
  const secure = provider.name !== 'tailscale-ip' && provider.name !== 'ssh' && provider.name !== 'local';
  const qr = server.buildPairingQR(pub.publicHost, pub.publicPort, secure);
  const payload = JSON.stringify(qr);

  const ascii = await QRCode.toString(payload, { type: 'terminal', small: true, errorCorrectionLevel: 'M' });

  console.log('');
  console.log('PocketCode headless server running');
  console.log(`  cwd:     ${args.cwd}`);
  console.log(`  local:   127.0.0.1:${localPort}`);
  console.log(`  tunnel:  ${provider.name} → ${qr.url}`);
  console.log(`  fp:      ${qr.fp}`);
  console.log(`  expires: ${new Date(qr.exp).toLocaleString()}`);
  console.log('');
  console.log('Scan with the PocketCode Android app:');
  console.log(ascii);
  console.log('');
  console.log('Pairing payload (also embedded in QR):');
  console.log(payload);
  console.log('');
  console.log('Press Ctrl+C to stop.');

  auth.on('device.bound', (d) => {
    console.log(`[pair] phone bound: ${d.id.slice(0, 8)}… (${auth.connectedDeviceCount()} device(s))`);
  });

  const shutdown = async () => {
    console.log('\nShutting down…');
    await provider.stop().catch(() => {});
    server.close();
    process.exit(0);
  };
  process.on('SIGINT', () => { void shutdown(); });
  process.on('SIGTERM', () => { void shutdown(); });
}

if (require.main === module) {
  main().catch((e) => {
    console.error(`pocketcode-cli: ${(e as Error).message ?? e}`);
    process.exit(1);
  });
}
