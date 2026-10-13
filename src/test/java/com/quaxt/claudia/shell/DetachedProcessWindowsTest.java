package com.quaxt.claudia.shell;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real Win32 processes, not shell mocks. Requires clang; never touches an installed launcher. */
@EnabledOnOs(OS.WINDOWS)
class DetachedProcessWindowsTest {
    @TempDir static Path buildDir;
    @TempDir Path tempDir;
    private static Path launcher;
    private static Path helper;
    private static final byte[] EMPTY = new byte[0];

    @BeforeAll
    static void buildLauncherAndHelper() throws Exception {
        // Probe separately: only a missing compiler skips, compilation errors fail the tests.
        Process probe;
        try {
            probe = new ProcessBuilder("clang", "--version").redirectErrorStream(true).start();
        } catch (IOException missingCompiler) {
            Assumptions.abort("No clang to build Windows spawn fixtures: " + missingCompiler.getMessage());
            return;
        }
        assertEquals(0, collect(probe, EMPTY).exit());
        Path source = Path.of("spawn", "claudia-spawn.c").toAbsolutePath();
        assertTrue(Files.isRegularFile(source), source + " is missing");
        launcher = Files.createDirectories(buildDir.resolve("launcher space ü")).resolve("claudia-spawn.exe");
        compile(source, launcher);
        Path helperSource = buildDir.resolve("windows-helper.c");
        try (var resource = DetachedProcessWindowsTest.class.getResourceAsStream("detached-process-windows-helper.c")) {
            assertNotNull(resource, "Missing Win32 helper source");
            Files.copy(resource, helperSource);
        }
        helper = Files.createDirectories(buildDir.resolve("program space 漢字")).resolve("windows-helper.exe");
        compile(helperSource, helper, "-lshell32", "-luser32");
    }

    private static void compile(Path source, Path binary, String... libraries) throws Exception {
        // MinGW ld cannot write Unicode output paths. Compile in the ASCII fixture build
        // directory, then use Java's Unicode-aware filesystem API to publish the binary.
        Path output = buildDir.resolve("compiled-" + binary.getFileName());
        List<String> command = new ArrayList<>(List.of("clang", "-std=c99", "-Wall", "-Wextra", "-Werror",
                "-O2", "-o", output.toString(), source.toString()));
        command.addAll(List.of(libraries));
        try {
            Result result = collect(new ProcessBuilder(command).redirectErrorStream(true).start(), EMPTY);
            assertEquals(0, result.exit(), result.stdoutText());
            assertTrue(Files.isRegularFile(output));
            Files.move(output, binary);
        } finally {
            Files.deleteIfExists(output);
        }
    }

    private record Result(int exit, byte[] stdout, byte[] stderr) {
        String stdoutText() { return new String(stdout, StandardCharsets.UTF_8).replace("\r\n", "\n"); }
        String stderrText() { return new String(stderr, StandardCharsets.UTF_8).replace("\r\n", "\n"); }
    }

    /** Drain both pipes concurrently, with deadlines even when a broken launcher leaves a child alive. */
    private static Result collect(Process process, byte[] input) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<byte[]> stdout = executor.submit(() -> process.getInputStream().readAllBytes());
            Future<byte[]> stderr = executor.submit(() -> process.getErrorStream().readAllBytes());
            Future<?> writer = executor.submit((Callable<Void>) () -> {
                try (var stdin = process.getOutputStream()) { stdin.write(input); }
                return null;
            });
            try {
                assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Process timed out: " + process.pid());
                writer.get(5, TimeUnit.SECONDS);
                return new Result(process.exitValue(), stdout.get(5, TimeUnit.SECONDS), stderr.get(5, TimeUnit.SECONDS));
            } finally {
                stopTree(process);
                process.getInputStream().close();
                process.getErrorStream().close();
                process.getOutputStream().close();
                stdout.cancel(true);
                stderr.cancel(true);
                writer.cancel(true);
            }
        }
    }

    private static void stopTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) process.destroyForcibly();
    }

    private static ProcessBuilder command(String... arguments) {
        List<String> command = new ArrayList<>();
        command.add(helper.toString());
        command.addAll(List.of(arguments));
        return new ProcessBuilder(command);
    }

    private static Result detached(ProcessBuilder builder, byte[] input) throws Exception {
        assertSame(builder, DetachedProcess.detach(builder, launcher));
        assertEquals(launcher.toString(), builder.command().getFirst(), "The test must actually use the launcher");
        return collect(builder.start(), input);
    }

    private static String hex(String text) {
        StringBuilder result = new StringBuilder();
        text.chars().forEach(character -> result.append(String.format("%04x", character)));
        return result.toString();
    }

    private static final List<String> ARGUMENTS = List.of(
            "plain", "with spaces", "with\ttab", "", "\"", "embedded\"quote", "\"already quoted\"",
            "C:\\path\\", "C:\\path with spaces\\", "slash\\\"quote", "two\\\\\"quotes",
            "%CLAUDIA_SPAWN_TEST%", "&|<>^", "a & b | c < d > e ^ f", "café 漢字", "emoji 😀 𝄞");

    @Test
    void argumentQuotingIsIdenticalToDirectProcessBuilder() throws Exception {
        List<String> args = new ArrayList<>(List.of("args"));
        args.addAll(ARGUMENTS);
        Result direct = collect(command(args.toArray(String[]::new)).start(), EMPTY);
        Result wrapped = detached(command(args.toArray(String[]::new)), EMPTY);
        assertEquals(0, direct.exit());
        assertEquals(0, wrapped.exit(), wrapped.stderrText());
        assertArrayEquals(direct.stdout(), wrapped.stdout(), "Preserve Java's native command line verbatim");
    }

    @Test
    void fullyQuotedNativeArgumentsRoundTripIncludingLiteralQuotes() throws Exception {
        // ShellCommandLine's legacy Windows transport supplies an already quoted native argument.
        Assumptions.assumeFalse("false".equalsIgnoreCase(
                System.getProperty("jdk.lang.Process.allowAmbiguousCommands", "true")), "Legacy quoting only");
        List<String> args = new ArrayList<>(List.of("args"));
        ARGUMENTS.stream().map(ShellCommandLine::quoteWindowsArgument).forEach(args::add);
        Result result = detached(command(args.toArray(String[]::new)), EMPTY);
        assertEquals(0, result.exit(), result.stderrText());
        assertEquals(ARGUMENTS.stream().map(DetachedProcessWindowsTest::hex).toList(), result.stdoutText().lines().toList());
    }

    @Test
    void runProcessQuoteEscapingMatchesDirectLaunch() throws Exception {
        List<String> args = new ArrayList<>(List.of("args"));
        ARGUMENTS.stream().map(ProcessEnvironment::processBuilderArgument).forEach(args::add);
        Result direct = collect(command(args.toArray(String[]::new)).start(), EMPTY);
        Result wrapped = detached(command(args.toArray(String[]::new)), EMPTY);
        assertEquals(0, wrapped.exit(), wrapped.stderrText());
        assertArrayEquals(direct.stdout(), wrapped.stdout());
    }

    @Test
    void preservesBinaryPipesAndSeparateStderrWithoutDeadlocking() throws Exception {
        byte[] input = new byte[256 * 1024];
        for (int i = 0; i < input.length; i++) input[i] = (byte) i;
        Result result = detached(command("pipe"), input);
        assertEquals(0, result.exit(), result.stderrText());
        assertArrayEquals(input, result.stdout());
        assertEquals("stderr marker\n", result.stderrText());
    }

    @Test
    void mergedStdoutAndStderrHandlesAreDeduplicated() throws Exception {
        Result result = detached(command("pipe").redirectErrorStream(true), "input text\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(0, result.exit());
        assertEquals("stderr marker\ninput text\n", result.stdoutText());
        assertArrayEquals(EMPTY, result.stderr());
    }

    @Test
    void redirectedFileHandlesAndEofAlsoWork() throws Exception {
        Path input = Files.write(tempDir.resolve("input"), new byte[] {0, 1, 2, 13, 10, (byte) 255});
        Path output = tempDir.resolve("output");
        Path error = tempDir.resolve("error");
        ProcessBuilder builder = command("pipe").redirectInput(input.toFile())
                .redirectOutput(output.toFile()).redirectError(error.toFile());
        Result result = detached(builder, EMPTY);
        assertEquals(0, result.exit());
        assertArrayEquals(Files.readAllBytes(input), Files.readAllBytes(output));
        assertEquals("stderr marker\n", Files.readString(error).replace("\r\n", "\n"));
    }

    @Test
    void discardedOutputHandlesDoNotBreakLaunchOrPipeInput() throws Exception {
        Result result = detached(command("pipe").redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD), "discard me".getBytes(StandardCharsets.UTF_8));
        assertEquals(0, result.exit());
        assertArrayEquals(EMPTY, result.stdout());
        assertArrayEquals(EMPTY, result.stderr());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 3, 255, 1234567890L, 4294967295L})
    void propagatesAllBitsOfTheWindowsExitCode(long exitCode) throws Exception {
        Result result = detached(command("exit", Long.toString(exitCode)), EMPTY);
        assertEquals((int) exitCode, result.exit(), result.stderrText());
    }

    @Test
    void preservesWorkingDirectoryAndUnicodeEnvironment() throws Exception {
        Path directory = Files.createDirectory(tempDir.resolve("working space ü 漢字"));
        String value = "x=y z %PATH% & café 漢字 😀";
        ProcessBuilder builder = command("context").directory(directory.toFile());
        builder.environment().put("CLAUDIA_SPAWN_TEST", value);
        Result result = detached(builder, EMPTY);
        assertEquals(0, result.exit(), result.stderrText());
        assertEquals(List.of(hex(directory.toRealPath().toString()), hex(value)), result.stdoutText().lines().toList());
    }

    @Test
    void resolvesAbsoluteAndRelativeBackslashPathsAndAppendsExe() throws Exception {
        Path directory = Files.createDirectory(tempDir.resolve("relative tools"));
        Path copy = Files.copy(helper, directory.resolve("helper.exe"));
        List<String> programs = new ArrayList<>(List.of(copy.toString(), copy.toString().replace(".exe", "")));
        Path parent = Path.of("").toAbsolutePath();
        if (parent.getRoot().equals(copy.getRoot())) {
            String relative = parent.relativize(copy).toString();
            programs.add(".\\" + relative);
            programs.add(".\\" + relative.replace(".exe", ""));
            programs.add("./" + relative.replace('\\', '/'));
        }
        for (String program : programs) {
            ProcessBuilder direct = new ProcessBuilder(program, "exit", "3").directory(tempDir.toFile());
            assertEquals(3, collect(direct.start(), EMPTY).exit(), program);
            Result result = detached(new ProcessBuilder(program, "exit", "3").directory(tempDir.toFile()), EMPTY);
            assertEquals(3, result.exit(), program + ": " + result.stderrText());
        }
    }

    @Test
    void relativeProgramsAreNotSearchedInTheChildsWorkingDirectory() throws Exception {
        Files.copy(helper, tempDir.resolve("helper.exe"));
        ProcessBuilder builder = new ProcessBuilder(".\\helper.exe", "exit", "0").directory(tempDir.toFile());
        List<String> original = List.copyOf(builder.command());
        DetachedProcess.detach(builder, launcher);
        assertEquals(original, builder.command(), "Windows resolves program paths in the parent's cwd");
        assertThrows(IOException.class, builder::start);
    }

    @Test
    void resolvesBareNamesOnTheParentPathNotTheBuildersPath() throws Exception {
        // cmd is an actual EXE; only .bat/.cmd files must be bypassed.
        ProcessBuilder builder = new ProcessBuilder("cmd", "/d", "/c", "exit", "3");
        builder.environment().put("PATH", tempDir.toString());
        Result result = detached(builder, EMPTY);
        assertEquals(3, result.exit(), result.stderrText());
        assertTrue(Path.of(builder.command().get(1)).isAbsolute());
        assertTrue(builder.command().get(1).toLowerCase(java.util.Locale.ROOT).endsWith("cmd.exe"));
    }

    @Test
    void unresolvableProgramsRemainUnchangedAndStillFailOnStart() {
        for (String program : List.of("no-such-claudia-windows-test-program", tempDir.resolve("missing.exe").toString())) {
            ProcessBuilder builder = new ProcessBuilder(program, "argument");
            List<String> original = List.copyOf(builder.command());
            assertSame(builder, DetachedProcess.detach(builder, launcher));
            assertEquals(original, builder.command());
            assertThrows(IOException.class, builder::start);
        }
    }

    @Test
    void nativeNonExeExtensionsKeepTheirDirectQuotingMode() throws Exception {
        Path nativeCom = Files.copy(helper, tempDir.resolve("native helper.com"));
        List<String> original = List.of(nativeCom.toString(), "args", "\"C:\\path with spaces\\\"");
        Result direct = collect(new ProcessBuilder(original).start(), EMPTY);
        ProcessBuilder builder = new ProcessBuilder(original);
        DetachedProcess.detach(builder, launcher);
        boolean strict = "false".equalsIgnoreCase(System.getProperty("jdk.lang.Process.allowAmbiguousCommands", "true"));
        if (strict) assertEquals(original, builder.command(), "Keep ProcessImpl's non-EXE quote verification");
        else assertEquals(launcher.toString(), builder.command().getFirst());
        Result result = collect(builder.start(), EMPTY);
        assertEquals(direct.exit(), result.exit(), result.stderrText());
        assertArrayEquals(direct.stdout(), result.stdout());
    }

    @Test
    void batchFilesAreNeverRewrittenEvenWithAnExplicitPath() throws Exception {
        for (String extension : List.of(".bat", ".cmd", ".BAT", ".CMD")) {
            Path batch = Files.writeString(tempDir.resolve("test" + extension), "@exit /b 0\r\n");
            for (String program : List.of(batch.toString(), ".\\" + batch.getFileName(), batch.getFileName().toString())) {
                ProcessBuilder builder = new ProcessBuilder(program, "a&b", "%PATH%", "\"quoted\"")
                        .directory(tempDir.toFile());
                List<String> original = List.copyOf(builder.command());
                assertSame(builder, DetachedProcess.detach(builder, launcher));
                assertEquals(original, builder.command(), "JDK batch-specific quoting must remain in control");
            }
        }
    }

    @Test
    void withoutALauncherOrCommandTheBuilderIsUnchanged() {
        ProcessBuilder builder = command("exit", "0");
        List<String> original = List.copyOf(builder.command());
        assertSame(builder, DetachedProcess.detach(builder, null));
        assertEquals(original, builder.command());
        ProcessBuilder empty = new ProcessBuilder();
        assertSame(empty, DetachedProcess.detach(empty, launcher));
        assertTrue(empty.command().isEmpty());
    }

    @Test
    void findsOnlyTheWindowsLauncherInTheBuildCheckout() throws Exception {
        Path artifact = Files.createDirectory(tempDir.resolve("target")).resolve("claudia.jar");
        assertEquals(Optional.empty(), DetachedProcess.launcherFor(artifact, true));
        Path binary = Files.createDirectories(tempDir.resolve("spawn/build")).resolve("claudia-spawn.exe");
        Files.copy(launcher, binary, StandardCopyOption.COPY_ATTRIBUTES);
        assertEquals(Optional.of(binary), DetachedProcess.launcherFor(artifact, true));
        assertEquals(Optional.empty(), DetachedProcess.launcherFor(artifact, false));
        Files.delete(binary);
        assertEquals(Optional.empty(), DetachedProcess.launcherFor(artifact, true));
        ProcessBuilder builder = command("exit", "0");
        List<String> original = List.copyOf(builder.command());
        DetachedProcess.detach(builder, DetachedProcess.launcherFor(artifact, true).orElse(null));
        assertEquals(original, builder.command());
    }

    @Test
    void launcherReportsUsageAndLocalizedCreateProcessErrors() throws Exception {
        Result usage = collect(new ProcessBuilder(launcher.toString()).start(), EMPTY);
        assertEquals(127, usage.exit());
        assertTrue(usage.stderrText().toLowerCase(java.util.Locale.ROOT).contains("usage:"), usage.stderrText());
        Path missing = tempDir.resolve("missing ü.exe");
        Path missingDirectory = tempDir.resolve("absent/subdir/program.exe");
        Path invalid = Files.writeString(tempDir.resolve("invalid.exe"), "not a PE executable\n");
        for (Path program : List.of(missing, missingDirectory, invalid, tempDir)) {
            Result error = collect(new ProcessBuilder(launcher.toString(), program.toString()).start(), EMPTY);
            assertEquals(program.equals(missing) || program.equals(missingDirectory) ? 127 : 126,
                    error.exit(), error.stderrText());
            assertArrayEquals(EMPTY, error.stdout());
            // FormatMessage text depends on the OS language; require path and a nonempty explanation.
            assertTrue(error.stderrText().contains(program.getFileName() + ":"), error.stderrText());
            assertFalse(error.stderrText().substring(error.stderrText().indexOf(':', 2) + 1).isBlank(), error.stderrText());
        }
    }

    private static long readPid(Process process, java.io.BufferedReader reader) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> line = executor.submit(reader::readLine);
            try {
                String text = line.get(10, TimeUnit.SECONDS);
                assertNotNull(text, "Missing helper PID; launcher exited");
                return Long.parseLong(text);
            } finally {
                if (!line.isDone()) {
                    stopTree(process);
                    reader.close();
                    line.cancel(true);
                }
            }
        }
    }

    private static void awaitDead(long pid) {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) Thread.sleep(20);
        }, "Process survived launcher termination: " + pid);
    }

    @Test
    void forciblyDestroyingLauncherKillsTheRealChild() throws Exception {
        Process process = DetachedProcess.detach(command("wait"), launcher).start();
        long child = -1;
        try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
            child = readPid(process, reader);
            assertNotEquals(process.pid(), child, "Windows uses a waiting launcher, not exec");
            long expectedChild = child;
            assertTrue(process.descendants().anyMatch(handle -> handle.pid() == expectedChild));
            process.destroyForcibly(); // Deliberately do NOT destroy descendants: that is the job's responsibility.
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            awaitDead(child);
        } finally {
            stopTree(process);
            if (child > 0) ProcessHandle.of(child).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    @Test
    void forciblyDestroyingLauncherAlsoKillsGrandchildren() throws Exception {
        Process process = DetachedProcess.detach(command("tree"), launcher).start();
        long child = -1, grandchild = -1;
        try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
            grandchild = readPid(process, reader);
            child = readPid(process, reader);
            assertTrue(ProcessHandle.of(child).orElseThrow().isAlive());
            assertTrue(ProcessHandle.of(grandchild).orElseThrow().isAlive());
            process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            awaitDead(child);
            awaitDead(grandchild);
        } finally {
            stopTree(process);
            if (child > 0) ProcessHandle.of(child).ifPresent(ProcessHandle::destroyForcibly);
            if (grandchild > 0) ProcessHandle.of(grandchild).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    @Test
    void intentionalBackgroundGrandchildSurvivesNormalExit() throws Exception {
        long grandchild = -1;
        try {
            Result result = detached(command("background"), EMPTY);
            grandchild = Long.parseLong(result.stdoutText().strip());
            assertEquals(0, result.exit(), result.stderrText());
            Thread.sleep(300); // The launcher and its job handle have closed by now.
            assertTrue(ProcessHandle.of(grandchild).map(ProcessHandle::isAlive).orElse(false),
                    "Normal completion must clear kill-on-job-close");
        } finally {
            if (grandchild > 0) {
                ProcessHandle.of(grandchild).ifPresent(ProcessHandle::destroyForcibly);
                awaitDead(grandchild);
            }
        }
    }

    @Test
    void runsWithoutAVisibleConsoleEvenWhenSurefireHasNoConsole() throws Exception {
        Result result = detached(command("console-probe"), EMPTY);
        assertEquals(0, result.exit(), result.stderrText());
        assertTrue(result.stdoutText().contains("visible-window=0\n"), result.stdoutText());
        assertTrue(result.stdoutText().startsWith("pid="), result.stdoutText());
    }

    @Test
    void privateParentConsoleCannotBeReadReconfiguredOrSignalledThroughTheLauncher() throws Exception {
        // The native parent allocates its own hidden console. Never mutate the test JVM's console.
        Result result = collect(command("console-parent", launcher.toString(), helper.toString()).start(), EMPTY);
        assertEquals(0, result.exit(), result.stderrText() + result.stdoutText());
        for (String assertion : List.of("child-isolated=1", "child-window-hidden=1", "parent-isolated=1",
                "only-std-handles-inherited=1", "launcher-detached=1", "ctrl-c-sent=1", "launcher-survived-ctrl-c=1", "console-child-exit=0")) {
            assertTrue(result.stdoutText().lines().anyMatch(assertion::equals), assertion + " missing:\n" + result.stdoutText());
        }
    }
}
