#!/usr/bin/env python3
"""
pty-helper.py — Spawn a shell inside a real pseudo-terminal and relay I/O
via stdin/stdout pipes.

Why this exists:
  VS Code extension host processes don't have a TTY on stdin.  The classic
  `script -q /dev/null bash` trick fails with "tcgetattr/ioctl: Operation
  not supported on socket" because `script` unconditionally calls
  tcgetattr(STDIN_FILENO).

  This helper creates its own master/slave PTY pair, forks the shell onto
  the slave side, and copies data between our piped stdin/stdout and the
  master fd.  Programs inside the shell (Claude Code, Aider, etc.) see a
  real TTY and behave normally.

Usage:
  python3 pty-helper.py [shell] [cols] [rows]
  # defaults: $SHELL or /bin/bash, 120 cols, 40 rows

All arguments are optional.  Environment variables COLUMNS and LINES are
also respected if the positional args are omitted.
"""
import pty, os, sys, select, signal, struct, fcntl, termios, errno

def set_winsize(fd, rows, cols):
    """Set the terminal window size on a PTY file descriptor."""
    winsize = struct.pack('HHHH', rows, cols, 0, 0)
    fcntl.ioctl(fd, termios.TIOCSWINSZ, winsize)

def main():
    shell = sys.argv[1] if len(sys.argv) > 1 else os.environ.get('SHELL', '/bin/bash')
    cols  = int(sys.argv[2]) if len(sys.argv) > 2 else int(os.environ.get('COLUMNS', '120'))
    rows  = int(sys.argv[3]) if len(sys.argv) > 3 else int(os.environ.get('LINES', '40'))

    # Create PTY pair
    master_fd, slave_fd = pty.openpty()
    set_winsize(slave_fd, rows, cols)

    pid = os.fork()
    if pid == 0:
        # ── Child: run shell on the slave side of the PTY ──────────────
        os.close(master_fd)
        os.setsid()                          # new session
        fcntl.ioctl(slave_fd, termios.TIOCSCTTY, 0)  # controlling tty
        os.dup2(slave_fd, 0)
        os.dup2(slave_fd, 1)
        os.dup2(slave_fd, 2)
        if slave_fd > 2:
            os.close(slave_fd)

        # Set env so the shell and children know their terminal size
        os.environ['TERM']      = 'xterm-256color'
        os.environ['COLORTERM'] = 'truecolor'
        os.environ['COLUMNS']   = str(cols)
        os.environ['LINES']     = str(rows)
        os.environ.setdefault('LANG', 'en_US.UTF-8')

        os.execvp(shell, [shell, '-l'])
        # execvp doesn't return

    # ── Parent: relay I/O between our pipes and the PTY master ─────────
    os.close(slave_fd)

    stdin_fd  = sys.stdin.fileno()    # 0 — pipe from Node
    stdout_fd = sys.stdout.fileno()   # 1 — pipe to Node

    # Flush Python's stdout buffer to avoid delayed output
    sys.stdout = os.fdopen(stdout_fd, 'wb', 0)

    try:
        while True:
            # Check if child is still alive
            wpid, status = os.waitpid(pid, os.WNOHANG)
            if wpid != 0:
                break

            try:
                rfds, _, _ = select.select([stdin_fd, master_fd], [], [], 0.02)
            except (select.error, ValueError, OSError):
                break

            if stdin_fd in rfds:
                try:
                    data = os.read(stdin_fd, 16384)
                    if not data:
                        # Node closed our stdin — shell session ending
                        break
                    os.write(master_fd, data)
                except OSError as e:
                    if e.errno in (errno.EIO, errno.EBADF):
                        break
                    raise

            if master_fd in rfds:
                try:
                    data = os.read(master_fd, 16384)
                    if not data:
                        break
                    os.write(stdout_fd, data)
                except OSError as e:
                    if e.errno in (errno.EIO, errno.EBADF):
                        # PTY master closed — shell exited
                        break
                    raise

    except KeyboardInterrupt:
        pass
    finally:
        os.close(master_fd)
        try:
            os.kill(pid, signal.SIGHUP)
        except ProcessLookupError:
            pass
        try:
            os.waitpid(pid, 0)
        except ChildProcessError:
            pass

        # Exit with the shell's exit code if available
        if 'status' in dir() and os.WIFEXITED(status):
            sys.exit(os.WEXITSTATUS(status))

if __name__ == '__main__':
    main()
