package com.quaxt.claudia.shell;

import com.quaxt.claudia.RunningBuild;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Starts child processes without Claudia's terminal.
 *
 * <p>Tool processes otherwise share the terminal that Claudia's line editor reads.
 * A program that opens {@code /dev/tty} for a password or confirmation (ssh for a
 * key passphrase, git credential prompts, sudo, gpg) then competes with Claudia for
 * keystrokes, splitting key sequences between them, and can leave the terminal's
 * modes changed when it is killed.
 *
 * <p>On macOS/Linux Java cannot start a process in a new session itself, so the command is run
 * through {@code claudia-spawn} (built by {@code install.sh} from
 * {@code spawn/claudia-spawn.c} into {@code spawn/build/} of the checkout that
 * built Claudia). It calls {@code setsid()} and then execs the program in the same
 * process, so opening {@code /dev/tty} fails and such prompts fail promptly.
 *
 * <p>On Windows {@code install.ps1} builds {@code claudia-spawn.exe}. It frees the
 * inherited console and starts the resolved program with a hidden console, passing
 * Java's native command line through unchanged. Console-only prompts wait unseen
 * until timeout: load SSH keys into ssh-agent first. A job kills the child tree when
 * the launcher is terminated, but allows background children to survive normal exit.
 * {@link Process#pid()} is the launcher PID; the real program is a descendant.
 *
 * <p>The builder is left unchanged when the launcher was not built, for Windows
 * batch files (and other non-.exe names under strict JDK quoting), or when the program cannot
 * be resolved using the parent's {@code PATH}; {@link ProcessBuilder#start()} then
 * reports that failure as usual.
 */
public final class DetachedProcess {
    private DetachedProcess() {}

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    private static final class Installed {
        static final Path LAUNCHER =
                RunningBuild.artifact().flatMap(artifact -> launcherFor(artifact, WINDOWS)).orElse(null);
    }

    /** The installed launcher, if the running build has one; without it, {@link #detach} changes nothing. */
    public static Optional<Path> launcher() {
        return Optional.ofNullable(Installed.LAUNCHER);
    }

    /**
     * Returns {@code <repo>/spawn/build/claudia-spawn[.exe]} for a Claudia jar or native
     * executable at {@code <repo>/target/<artifact>}, when it is an executable file.
     */
    public static Optional<Path> launcherFor(Path artifact, boolean windows) {
        return RunningBuild.executable(artifact, "spawn", "build", windows ? "claudia-spawn.exe" : "claudia-spawn");
    }

    /** Rewrites {@code builder}'s command to run without a controlling terminal, where possible. */
    public static ProcessBuilder detach(ProcessBuilder builder) {
        return detach(builder, Installed.LAUNCHER);
    }

    static ProcessBuilder detach(ProcessBuilder builder, Path launcher) {
        List<String> command = builder.command();
        if (launcher == null || command.isEmpty()) return builder;
        // In particular MavenTestLaunch's mvn.cmd stays attached. Wrapping batch
        // files would bypass ProcessImpl's special quoting and permit injection.
        if (WINDOWS && batch(command.getFirst())) return builder;
        Path program = resolve(command.getFirst(), builder.directory());
        if (program == null) return builder;
        // Strict ProcessImpl uses batch-style verification for every non-.exe
        // extension, even native .com programs. Switching that to the launcher's
        // EXE quoting would change arguments (or bypass its quote validation).
        if (WINDOWS && !program.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".exe")
                && "false".equalsIgnoreCase(System.getProperty("jdk.lang.Process.allowAmbiguousCommands", "true")))
            return builder;
        List<String> detached = new ArrayList<>(command.size() + 2);
        detached.add(launcher.toString());
        detached.add(program.toString());
        // Both Windows EXE modes in ProcessImpl quote all remaining arguments the
        // same way, regardless of the executable name. Preserve legacy pre-quoting
        // from ShellCommandLine/ProcessEnvironment; do not quote these again.
        // A Windows path cannot contain a quote or end in a backslash (it's a file),
        // so its ordinary-argument quoting also works as the child's argv[0].
        detached.addAll(WINDOWS ? command.subList(1, command.size()) : command);
        builder.command(detached);
        return builder;
    }

    /** The executable ProcessBuilder would run: relative paths use the child cwd on POSIX,
     * the parent cwd on Windows; bare names use the parent's search path. */
    static Path resolve(String program, File directory) {
        try {
            if (program.isEmpty()) return null;
            if (WINDOWS) return resolveWindows(program);
            Path base = directory == null ? Path.of("").toAbsolutePath() : directory.toPath().toAbsolutePath();
            if (program.indexOf('/') >= 0) return executable(base.resolve(program));
            String path = System.getenv("PATH");
            for (String entry : (path == null ? "/bin:/usr/bin" : path).split(":", -1)) {
                Path candidate = executable(base.resolve(entry.isEmpty() ? "." : entry).resolve(program));
                if (candidate != null) return candidate;
            }
            return null;
        } catch (InvalidPathException error) {
            return null;
        }
    }

    private static boolean batch(String program) {
        String lower = program.toLowerCase(Locale.ROOT);
        return lower.endsWith(".bat") || lower.endsWith(".cmd");
    }

    private static Path resolveWindows(String program) {
        // ProcessImpl accepts a quoted executable token; quotes are not path chars.
        if (program.length() > 1 && program.startsWith("\"") && program.endsWith("\""))
            program = program.substring(1, program.length() - 1);
        if (program.isEmpty() || batch(program)) return null;
        Path requested = Path.of(program);
        Path parent = Path.of("").toAbsolutePath();
        // On Windows even explicit relative executable paths resolve against the
        // parent's cwd, not lpCurrentDirectory (which only sets the child's cwd).
        if (requested.isAbsolute() || program.indexOf('/') >= 0 || program.indexOf('\\') >= 0)
            return windowsExecutable(parent.resolve(requested));
        if (program.indexOf(':') >= 0) // Drive-relative paths, such as C:tool.exe.
            return windowsExecutable(requested.toAbsolutePath());
        // CreateProcess searches the parent's application/current/system directories
        // before PATH. The builder's environment does not affect this search.
        List<Path> directories = new ArrayList<>();
        ProcessHandle.current().info().command().ifPresent(command -> {
            Path application = Path.of(command).toAbsolutePath().getParent();
            if (application != null) directories.add(application);
        });
        directories.add(parent);
        String root = System.getenv("SystemRoot");
        if (root != null) {
            directories.add(Path.of(root, "System32"));
            directories.add(Path.of(root, "System"));
            directories.add(Path.of(root));
        }
        String path = System.getenv("PATH");
        if (path != null) for (String entry : path.split(";", -1)) {
            if (entry.length() > 1 && entry.startsWith("\"") && entry.endsWith("\""))
                entry = entry.substring(1, entry.length() - 1);
            try { directories.add(parent.resolve(entry.isEmpty() ? "." : entry)); }
            catch (InvalidPathException ignored) { /* Ignore unusable PATH entries. */ }
        }
        for (Path search : directories) {
            Path found = windowsExecutable(search.resolve(program));
            if (found != null) return found;
        }
        return null;
    }

    private static Path windowsExecutable(Path path) {
        // CreateProcess appends .exe only when the final component has no extension.
        if (path.getFileName() == null) return null;
        if (path.getFileName().toString().indexOf('.') < 0)
            path = path.resolveSibling(path.getFileName() + ".exe");
        return executable(path);
    }

    private static Path executable(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        return Files.isRegularFile(absolute) && Files.isExecutable(absolute) ? absolute : null;
    }
}
