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
 * <p>Java cannot start a process in a new session itself, so the command is run
 * through {@code claudia-spawn} (built by {@code install.sh} from
 * {@code spawn/claudia-spawn.c} into {@code spawn/build/} of the checkout that
 * built Claudia). It calls {@code setsid()} and then execs the program in the same
 * process, so opening {@code /dev/tty} fails and such prompts fail promptly.
 *
 * <p>The builder is left unchanged, so the program shares Claudia's terminal, when
 * the launcher was not built, on Windows (no launcher yet), or when the program
 * cannot be resolved as {@link ProcessBuilder} would (the parent's {@code PATH});
 * {@link ProcessBuilder#start()} then reports that failure as usual.
 */
public final class DetachedProcess {
    private DetachedProcess() {}

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    private static final class Installed {
        static final Path LAUNCHER = WINDOWS ? null
                : RunningBuild.artifact().flatMap(artifact -> launcherFor(artifact, false)).orElse(null);
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
        // Windows: reserved for a claudia-spawn.exe with its own protocol; see spawn/claudia-spawn.c.
        List<String> command = builder.command();
        if (launcher == null || WINDOWS || command.isEmpty()) return builder;
        Path program = resolve(command.getFirst(), builder.directory());
        if (program == null) return builder;
        List<String> detached = new ArrayList<>(command.size() + 2);
        detached.add(launcher.toString());
        detached.add(program.toString());
        detached.addAll(command); // The original program name stays the program's argv[0].
        builder.command(detached);
        return builder;
    }

    /** The executable ProcessBuilder would run: a path relative to the directory, or found on the parent's PATH. */
    static Path resolve(String program, File directory) {
        try {
            if (program.isEmpty()) return null;
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

    private static Path executable(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        return Files.isRegularFile(absolute) && Files.isExecutable(absolute) ? absolute : null;
    }
}
