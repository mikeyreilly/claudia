package com.quaxt.codingagent.cli;

import java.nio.file.Path;
import java.util.List;

/**
 * Repository-local instructions discovered from {@code AGENTS.md} files.
 *
 * <p>Instructions are ordinary prompt text. Nothing here interprets their
 * contents or imposes filesystem restrictions based on them. Discovery,
 * composition, and scope tracking live in CodingAgentOperations.
 */
public final class AgentInstructions {
	public static final String AGENTS_FILE = "AGENTS.md";
	public static final String OVERRIDE_FILE = "AGENTS.override.md";

	/** Git worktree root (or fallback workspace root) used for discovery. */
	public Path repositoryRoot;
	public String baseSystemPrompt;
	public Path currentDirectory;
	/** Composed prompt: base prompt followed by the applicable instructions. */
	public String systemPrompt = "";
	/** Instruction files whose contents are currently included. */
	public List<Path> sources = List.of();

	public AgentInstructions(Path repositoryRoot, Path currentDirectory, String baseSystemPrompt) {
		this.repositoryRoot = repositoryRoot;
		this.currentDirectory = currentDirectory;
		this.baseSystemPrompt = baseSystemPrompt;
	}
}
