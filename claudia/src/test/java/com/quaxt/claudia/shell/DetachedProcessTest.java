package com.quaxt.claudia.shell;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Builds spawn/claudia-spawn.c with the C compiler ($CC or cc); skipped without one. */
@DisabledOnOs(OS.WINDOWS)
class DetachedProcessTest {
    @TempDir static Path buildDir;
    @TempDir Path tempDir;
    private static Path launcher;

    @BeforeAll
    static void buildLauncher() throws Exception {
        Path source = Path.of("spawn", "claudia-spawn.c").toAbsolutePath();
        assertTrue(Files.isRegularFile(source), source + " is missing");
        String cc = System.getenv("CC") == null || System.getenv("CC").isBlank() ? "cc" : System.getenv("CC");
        Path binary = buildDir.resolve("claudia-spawn");
        Process compile;
        try {
            compile = new ProcessBuilder(cc, "-std=c99", "-Wall", "-Wextra", "-Werror", "-O2",
                    "-o", binary.toString(), source.toString()).redirectErrorStream(true).start();
        } catch (IOException noCompiler) {
            Assumptions.abort("No C compiler (" + cc + ") to build claudia-spawn: " + noCompiler.getMessage());
            return;
        }
        String output = new String(compile.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(compile.waitFor(120, TimeUnit.SECONDS));
        assertEquals(0, compile.exitValue(), output);
        launcher = binary;
    }

    private static String run(ProcessBuilder builder, String input) throws Exception {
        return run(builder, input, launcher);
    }

    private static String run(ProcessBuilder builder, String input, Path launcher) throws Exception {
        Process process = DetachedProcess.detach(builder.redirectErrorStream(true), launcher).start();
        try (var stdin = process.getOutputStream()) {
            stdin.write(input.getBytes(StandardCharsets.UTF_8));
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        return output + "exit=" + process.exitValue();
    }

    @Test
    void runsTheProgramAsTheLeaderOfANewSession() throws Exception {
        // A session leader leads its own process group; without a new session the
        // child would be in the test JVM's group.
        String output = run(new ProcessBuilder("sh", "-c", "echo $$ $(ps -o pgid= -p $$)"), "");
        String[] ids = output.lines().findFirst().orElseThrow().trim().split("\\s+");
        assertEquals(ids[0], ids[1], output);
        assertTrue(output.endsWith("exit=0"), output);
    }

    @Test
    void theProgramCannotOpenTheControllingTerminal() throws Exception {
        String output = run(new ProcessBuilder("sh", "-c",
                "if (exec 3</dev/tty) 2>/dev/null; then echo has-tty; else echo no-tty; fi"), "");
        assertEquals("no-tty\nexit=0", output);
    }

    @Test
    void keepsStdinStdoutArgumentsExitStatusAndArgv0() throws Exception {
        assertEquals("piped input\nexit=0", run(new ProcessBuilder("cat"), "piped input\n"));
        assertEquals("a b|'c'|\nexit=3", run(new ProcessBuilder("sh", "-c",
                "printf '%s|' \"$1\" \"$2\"; echo; exit 3", "name", "a b", "'c'"), ""));
        // ps shows the argv[0] the program was given, not the resolved path. The
        // trailing ":" stops sh from replacing itself with ps.
        String args = run(new ProcessBuilder("sh", "-c", "ps -o args= -p $$; :"), "");
        assertTrue(args.startsWith("sh -c "), args);
    }

    @Test
    void keepsTheProcessIdAndEnvironment() throws Exception {
        ProcessBuilder builder = new ProcessBuilder("sh", "-c", "echo $$ \"$CLAUDIA_SPAWN_TEST\"");
        builder.environment().put("CLAUDIA_SPAWN_TEST", "x=y z");
        Process process = DetachedProcess.detach(builder, launcher).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        // The launcher execs the program, so Process.pid() is the program's.
        assertEquals(process.pid() + " x=y z\n", output);
    }

    @Test
    void runsRelativeProgramsFromTheWorkingDirectory() throws Exception {
        Path script = tempDir.resolve("hello.sh");
        Files.writeString(script, "#!/bin/sh\necho hello from $(pwd -P)\n");
        assertTrue(script.toFile().setExecutable(true));
        String output = run(new ProcessBuilder("./hello.sh").directory(tempDir.toFile()), "");
        assertEquals("hello from " + tempDir.toRealPath() + "\nexit=0", output);
    }

    @Test
    void anUnresolvableProgramStillFailsToStart() {
        ProcessBuilder builder = new ProcessBuilder("no-such-program-for-claudia");
        assertSame(builder, DetachedProcess.detach(builder, launcher));
        assertEquals(List.of("no-such-program-for-claudia"), builder.command());
        assertThrows(IOException.class, builder::start);
    }

    @Test
    void withoutTheLauncherTheCommandIsUnchanged() {
        ProcessBuilder builder = new ProcessBuilder("sh", "-c", "true");
        assertSame(builder, DetachedProcess.detach(builder, null));
        assertEquals(List.of("sh", "-c", "true"), builder.command());
    }

    @Test
    void theLauncherReportsProgramsItCannotRun() throws Exception {
        Path missing = tempDir.resolve("missing");
        Path plain = Files.writeString(tempDir.resolve("plain"), "not executable\n");
        assertEquals(missing + ": No such file or directory\nexit=127",
                run(new ProcessBuilder(launcher.toString(), missing.toString(), "missing"), "", null));
        assertEquals(plain + ": Permission denied\nexit=126",
                run(new ProcessBuilder(launcher.toString(), plain.toString(), "plain"), "", null));
        assertTrue(run(new ProcessBuilder(launcher.toString()), "", null).endsWith("usage: claudia-spawn PATH ARGV0 [ARG...]\nexit=127"));
    }

    @Test
    void findsTheLauncherInTheCheckoutThatBuiltClaudia() throws Exception {
        Path jar = Files.createDirectories(tempDir.resolve("target")).resolve("claudia.jar");
        assertEquals(Optional.empty(), DetachedProcess.launcherFor(jar, false));
        Path binary = Files.createDirectories(tempDir.resolve("spawn").resolve("build")).resolve("claudia-spawn");
        Files.copy(launcher, binary, StandardCopyOption.COPY_ATTRIBUTES);
        assertEquals(Optional.of(binary), DetachedProcess.launcherFor(jar, false));
        assertEquals(Optional.empty(), DetachedProcess.launcherFor(jar, true), "Windows builds are named claudia-spawn.exe");
        // Tests run from a classes directory, which is not a build.
        assertEquals(Optional.empty(), DetachedProcess.launcher());
    }
}
