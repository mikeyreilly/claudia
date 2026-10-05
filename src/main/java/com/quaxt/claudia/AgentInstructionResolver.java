package com.quaxt.claudia;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Resolves the composed system instructions and the files that supplied them.
 *
 * <p>The resolver is deliberately independent of the agent runtime: callers
 * provide the workspace, user instruction file, and per-run text, then retain
 * the returned snapshot until it needs refreshing.
 */
final class AgentInstructionResolver {
    private static final String AGENTS_FILE = "AGENTS.md";
    private static final String AGENTS_OVERRIDE_FILE = "AGENTS.override.md";

    enum SourceScope {
        USER,
        REPOSITORY
    }

    /** One readable instruction file included in the resolved instruction context. */
    record InstructionSource(Path path, SourceScope scope, String content) {
        InstructionSource {
            path = normalize(Objects.requireNonNull(path, "path"));
            scope = Objects.requireNonNull(scope, "scope");
            content = Objects.requireNonNull(content, "content");
        }
    }

    /** An immutable prompt snapshot and its contributing instruction files, in prompt order. */
    record ResolvedInstructions(String systemPrompt, List<InstructionSource> sources) {
        ResolvedInstructions {
            systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt");
            sources = List.copyOf(sources);
        }
    }

    private final Path repositoryRoot;
    private final Path workingDirectory;
    private final Path userInstructionsFile;
    private final String baseSystemPrompt;

    AgentInstructionResolver(
            Path repositoryRoot, Path workingDirectory, Path userInstructionsFile, String baseSystemPrompt) {
        this.repositoryRoot = normalize(Objects.requireNonNull(repositoryRoot, "repositoryRoot"));
        this.workingDirectory = normalize(Objects.requireNonNull(workingDirectory, "workingDirectory"));
        this.userInstructionsFile = normalize(Objects.requireNonNull(userInstructionsFile, "userInstructionsFile"));
        this.baseSystemPrompt = baseSystemPrompt == null ? "" : baseSystemPrompt;
    }

    /** Creates a resolver for a configured workspace, locating its enclosing Git worktree when present. */
    static AgentInstructionResolver forWorkspace(
            Path workspace, Path userInstructionsFile, String baseSystemPrompt) {
        Path workingDirectory = instructionDirectory(workspace);
        if (workingDirectory == null) {
            throw new IllegalArgumentException("workingDirectory must have a parent directory");
        }
        return new AgentInstructionResolver(
                repositoryRoot(workingDirectory), workingDirectory, userInstructionsFile, baseSystemPrompt);
    }

    Path repositoryRoot() {
        return repositoryRoot;
    }

    Path workingDirectory() {
        return workingDirectory;
    }

    /** Resolves the prompt applicable while operating in {@code directory}. */
    ResolvedInstructions resolve(Path directory) {
        Path currentDirectory = instructionDirectory(directory);
        if (currentDirectory == null) {
            throw new IllegalArgumentException("currentDirectory must have a parent directory");
        }
        List<InstructionSource> sources = new ArrayList<>();
        List<String> promptParts = new ArrayList<>();

        // The environment block leads because it is stable for the whole
        // session, which keeps the cacheable prefix intact as scope changes.
        promptParts.add(environmentPrompt());
        appendInstructionFile(userInstructionsFile, SourceScope.USER, sources, promptParts);
        if (!baseSystemPrompt.isBlank()) {
            promptParts.add(baseSystemPrompt);
        }
        for (Path scope : repositoryInstructionDirectories(currentDirectory)) {
            appendInstructionFile(repositoryInstructionFile(scope), SourceScope.REPOSITORY, sources, promptParts);
        }
        return new ResolvedInstructions(String.join("\n\n", promptParts), sources);
    }

    private String environmentPrompt() {
        StringBuilder text = new StringBuilder("Working directory: ").append(workingDirectory);
        if (!repositoryRoot.equals(workingDirectory)) {
            text.append("\nRepository root: ").append(repositoryRoot);
        }
        return text.append("\nRelative paths in tool calls resolve against the working directory. ")
                .append("Use an absolute path or a leading ~/ to reach anything outside it.")
                .toString();
    }

    private List<Path> repositoryInstructionDirectories(Path currentDirectory) {
        List<Path> directories = new ArrayList<>();
        for (Path current = currentDirectory; current != null; current = current.getParent()) {
            directories.add(current);
            if (current.equals(repositoryRoot)) {
                Collections.reverse(directories);
                return directories;
            }
        }
        return List.of();
    }

    private static Path repositoryRoot(Path directory) {
        for (Path current = directory; current != null; current = current.getParent()) {
            if (Files.exists(current.resolve(".git"))) {
                return current;
            }
        }
        return directory;
    }

    private static Path repositoryInstructionFile(Path directory) {
        Path override = directory.resolve(AGENTS_OVERRIDE_FILE);
        if (isReadableRegularFile(override)) {
            return override;
        }
        Path standard = directory.resolve(AGENTS_FILE);
        return isReadableRegularFile(standard) ? standard : null;
    }

    /** Retains readable empty files as sources even though they contribute no prompt text. */
    private static void appendInstructionFile(
            Path file,
            SourceScope scope,
            List<InstructionSource> sources,
            List<String> promptParts) {
        if (file == null || !isReadableRegularFile(file)) return;
        try {
            InstructionSource source = new InstructionSource(file, scope, Files.readString(file, StandardCharsets.UTF_8));
            sources.add(source);
            if (!source.content().isBlank()) {
                promptParts.add(source.content());
            }
        } catch (IOException | SecurityException ignored) {
            // Instructions are best-effort. An unreadable file must not stop the
            // agent from handling the user's task.
        }
    }

    private static boolean isReadableRegularFile(Path path) {
        try {
            return Files.isRegularFile(path);
        } catch (SecurityException ignored) {
            return false;
        }
    }

    static Path instructionDirectory(Path path) {
        Path absolute = normalize(Objects.requireNonNull(path, "path"));
        return Files.isDirectory(absolute) ? absolute : absolute.getParent();
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
