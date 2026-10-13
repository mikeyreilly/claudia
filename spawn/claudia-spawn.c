/*
 * claudia-spawn: runs a tool program without Claudia's terminal.
 *
 *     claudia-spawn PATH ARGV0 [ARG...]
 *
 * Tool processes would otherwise share the terminal that Claudia's line editor
 * reads. A program that opens /dev/tty for a password or confirmation (ssh for
 * a key passphrase, git credential prompts, sudo, gpg) then competes with
 * Claudia for keystrokes, splitting key sequences between them. If it is
 * killed mid-prompt it cannot restore the terminal modes it changed, and
 * Ctrl-C and Ctrl-D stop reaching Claudia.
 *
 * On macOS and Linux this calls setsid(), so the program runs in a new session
 * with no controlling terminal and opening /dev/tty fails, then replaces
 * itself with PATH (an absolute path; there is no PATH search) using ARGV0 as
 * its argv[0]. The program keeps this process's PID, standard streams,
 * environment and working directory, and its exit status is the program's.
 * When PATH cannot be executed, this prints the reason on stderr and exits
 * with 127 (not found) or 126 (any other failure), as shells do.
 *
 * Claudia (com.quaxt.claudia.shell.DetachedProcess) looks for this program at
 * spawn/build/claudia-spawn in the checkout it was built from; install.sh
 * builds it there. When it is absent, tools share Claudia's terminal.
 *
 * Windows is not supported yet. A Windows version will be found as
 * spawn/build/claudia-spawn.exe. Windows has no exec, so it will have to stay
 * running: start the program with CreateProcessW under its own console
 * (CREATE_NO_WINDOW or DETACHED_PROCESS) so it cannot read or reconfigure
 * Claudia's, pass the rest of its own command line through unchanged rather
 * than re-quoting it, put the program in a job object that kills it with the
 * launcher, wait for it, and exit with its exit code.
 */
#if defined(_WIN32)
#error "claudia-spawn has no Windows implementation yet; see the comment above."
#endif

#define _POSIX_C_SOURCE 200809L

#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char **argv) {
    if (argc < 3) {
        fputs("usage: claudia-spawn PATH ARGV0 [ARG...]\n", stderr);
        return 127;
    }
    /*
     * This fails only for a process group leader, which a freshly started
     * child is not. Run the program anyway: it would merely share the
     * terminal, as it does when this launcher is not installed.
     */
    (void)setsid();
    execv(argv[1], argv + 2);
    int error = errno;
    fprintf(stderr, "%s: %s\n", argv[1], strerror(error));
    return error == ENOENT ? 127 : 126;
}
