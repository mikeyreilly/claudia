package com.quaxt.codingagent.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Repository-local instructions discovered from {@code AGENTS.md} files.
 *
 * <p>Instructions are ordinary prompt text. This class deliberately does not
 * interpret their contents or impose any filesystem restrictions based on
 * them.
 */
public final class AgentInstructions {
	private static final String AGENTS_FILE = "AGENTS.md";
	private static final String OVERRIDE_FILE = "AGENTS.override.md";

	private final Path repositoryRoot;
	private final String baseSystemPrompt;
	private Path currentDirectory;
	private String systemPrompt;
	private List<Path> sources = List.of();

	private AgentInstructions(Path repositoryRoot, Path currentDirectory, String baseSystemPrompt) {
		this.repositoryRoot = repositoryRoot;
		this.currentDirectory = currentDirectory;
		this.baseSystemPrompt = baseSystemPrompt == null ? "" : baseSystemPrompt;
		refresh(currentDirectory);
	}

	/**
	 * Creates an instruction set rooted at the nearest enclosing Git worktree.
	 * If the working directory is not in a Git worktree, the working directory
	 * itself is used as the root.
	 */
	public static AgentInstructions forWorkingDirectory(Path workingDirectory, String baseSystemPrompt) {
		Path directory = directory(workingDirectory);
		if (directory == null) {
			throw new IllegalArgumentException("workingDirectory must have a parent directory");
		}
		return new AgentInstructions(findRepositoryRoot(directory), directory, baseSystemPrompt);
	}

	/** Creates an instruction set for an explicit repository root. */
	public static AgentInstructions forRepository(
			Path repositoryRoot, Path workingDirectory, String baseSystemPrompt) {
		Path root = Objects.requireNonNull(repositoryRoot, "repositoryRoot").toAbsolutePath().normalize();
		Path directory = directory(workingDirectory);
		if (directory == null || !directory.startsWith(root)) {
			throw new IllegalArgumentException("workingDirectory must be inside repositoryRoot");
		}
		return new AgentInstructions(root, directory, baseSystemPrompt);
	}

	/** Returns the Git worktree root (or fallback workspace root) used for discovery. */
	public Path repositoryRoot() {
		return repositoryRoot;
	}

	/** Returns the composed system prompt: base prompt followed by applicable instructions. */
	public synchronized String systemPrompt() {
		return systemPrompt;
	}

	/** Returns the instruction files whose contents are currently included. */
	public synchronized List<Path> sources() {
		return sources;
	}

	/** Reloads the instruction files applicable to the current scope. */
	public synchronized boolean refresh() {
		return refresh(currentDirectory);
	}

	/**
	 * Observes a filesystem path used by the agent.
	 *
	 * <p>Only descendants of the current instruction scope are adopted. This
	 * makes a move from {@code repo/backend} to {@code repo/backend/database}
	 * pick up additional instructions without accidentally applying sibling
	 * directory instructions to the current task. The return value indicates
	 * whether the effective prompt changed.
	 */
	public synchronized boolean observe(Path path) {
		Path directory = directory(path);
		if (directory == null
				|| !directory.startsWith(repositoryRoot)
				|| !directory.startsWith(currentDirectory)) {
			return false;
		}
		return refresh(directory);
	}

	private boolean refresh(Path directory) {
		List<Path> nextSources = new ArrayList<>();
		List<String> promptParts = new ArrayList<>();
		if (!baseSystemPrompt.isBlank()) {
			promptParts.add(baseSystemPrompt);
		}
		for (Path scope : directoriesFromRoot(directory)) {
			Path instructions = instructionFile(scope);
			if (instructions == null) continue;
			try {
				String content = Files.readString(instructions, StandardCharsets.UTF_8);
				nextSources.add(instructions);
				if (!content.isBlank()) promptParts.add(content);
			} catch (IOException | SecurityException ignored) {
				// Repository instructions are best-effort. An unreadable file must
				// not stop the agent from handling the user's task.
			}
		}

		String nextPrompt = String.join("\n\n", promptParts);
		List<Path> immutableSources = List.copyOf(nextSources);
		boolean changed = !directory.equals(currentDirectory)
				|| !nextPrompt.equals(systemPrompt)
				|| !immutableSources.equals(sources);
		currentDirectory = directory;
		systemPrompt = nextPrompt;
		sources = immutableSources;
		return changed;
	}

	private List<Path> directoriesFromRoot(Path directory) {
		List<Path> directories = new ArrayList<>();
		for (Path current = directory; current != null; current = current.getParent()) {
			directories.add(current);
			if (current.equals(repositoryRoot)) {
				Collections.reverse(directories);
				return directories;
			}
		}
		return List.of();
	}

	private static Path findRepositoryRoot(Path workingDirectory) {
		for (Path current = workingDirectory; current != null; current = current.getParent()) {
			if (Files.exists(current.resolve(".git"))) return current;
		}
		return workingDirectory;
	}

	private static Path instructionFile(Path directory) {
		Path override = directory.resolve(OVERRIDE_FILE);
		if (isRegularFile(override)) return override;
		Path standard = directory.resolve(AGENTS_FILE);
		return isRegularFile(standard) ? standard : null;
	}

	private static boolean isRegularFile(Path path) {
		try {
			return Files.isRegularFile(path);
		} catch (SecurityException ignored) {
			return false;
		}
	}

	private static Path directory(Path path) {
		Path absolute = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
		return Files.isDirectory(absolute) ? absolute : absolute.getParent();
	}
}
