package com.quaxt.codingagent.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.quaxt.codingagent.agent.Agent;
import com.quaxt.codingagent.agent.AgentEvent;
import com.quaxt.codingagent.ai.json.Json;
import com.quaxt.codingagent.ai.providers.FauxProvider;

class AgentInstructionsTest {
	@TempDir Path tempDir;

	@Test
	void loadsRootInstructionsForANestedWorkingDirectory() throws Exception {
		Path repository = repository();
		Path workingDirectory = Files.createDirectories(repository.resolve("src/main"));
		Path rootInstructions = repository.resolve("AGENTS.md");
		Files.writeString(rootInstructions, "Use the repository build.");

		AgentInstructions instructions = AgentInstructions.forWorkingDirectory(workingDirectory, "Base agent prompt.");

		assertEquals("Base agent prompt.\n\nUse the repository build.", instructions.systemPrompt());
		assertEquals(List.of(rootInstructions), instructions.sources());
	}

	@Test
	void leavesTheBasePromptUntouchedWhenNoInstructionFilesExist() throws Exception {
		Path repository = repository();
		Path workingDirectory = Files.createDirectories(repository.resolve("src"));

		AgentInstructions instructions = AgentInstructions.forWorkingDirectory(workingDirectory, "Base agent prompt.");

		assertEquals("Base agent prompt.", instructions.systemPrompt());
		assertTrue(instructions.sources().isEmpty());
	}

	@Test
	void ordersNestedInstructionsFromLeastToMostSpecific() throws Exception {
		Path repository = repository();
		Path backend = Files.createDirectories(repository.resolve("backend"));
		Path workingDirectory = Files.createDirectories(backend.resolve("src"));
		Path rootInstructions = repository.resolve("AGENTS.md");
		Path backendInstructions = backend.resolve("AGENTS.md");
		Files.writeString(rootInstructions, "Repository instructions.");
		Files.writeString(backendInstructions, "Backend instructions.");

		AgentInstructions instructions = AgentInstructions.forWorkingDirectory(workingDirectory, "");

		assertEquals("Repository instructions.\n\nBackend instructions.", instructions.systemPrompt());
		assertEquals(List.of(rootInstructions, backendInstructions), instructions.sources());
	}

	@Test
	void loadsAllApplicableInstructionLevels() throws Exception {
		Path repository = repository();
		Path services = Files.createDirectories(repository.resolve("services"));
		Path payments = Files.createDirectories(services.resolve("payments"));
		Path workingDirectory = Files.createDirectories(payments.resolve("src"));
		Files.writeString(repository.resolve("AGENTS.md"), "Root.");
		Files.writeString(services.resolve("AGENTS.md"), "Services.");
		Files.writeString(payments.resolve("AGENTS.md"), "Payments.");

		AgentInstructions instructions = AgentInstructions.forWorkingDirectory(workingDirectory, "Base.");

		assertEquals("Base.\n\nRoot.\n\nServices.\n\nPayments.", instructions.systemPrompt());
	}

	@Test
	void prefersAnOverrideFileInTheSameDirectory() throws Exception {
		Path repository = repository();
		Files.writeString(repository.resolve("AGENTS.md"), "Standard instructions.");
		Path override = repository.resolve("AGENTS.override.md");
		Files.writeString(override, "Override instructions.");

		AgentInstructions instructions = AgentInstructions.forWorkingDirectory(repository, "");

		assertEquals("Override instructions.", instructions.systemPrompt());
		assertEquals(List.of(override), instructions.sources());
	}

	@Test
	void discoversAdditionalInstructionsWhenWorkMovesIntoADescendant() throws Exception {
		Path repository = repository();
		Path backend = Files.createDirectories(repository.resolve("backend"));
		Path database = Files.createDirectories(backend.resolve("database"));
		Files.writeString(repository.resolve("AGENTS.md"), "Root.");
		Files.writeString(backend.resolve("AGENTS.md"), "Backend.");
		Files.writeString(database.resolve("AGENTS.md"), "Database.");
		Path databaseFile = Files.writeString(database.resolve("schema.sql"), "select 1;");

		AgentInstructions instructions = AgentInstructions.forWorkingDirectory(backend, "Base.");

		assertEquals("Base.\n\nRoot.\n\nBackend.", instructions.systemPrompt());
		assertTrue(instructions.observe(databaseFile));
		assertEquals("Base.\n\nRoot.\n\nBackend.\n\nDatabase.", instructions.systemPrompt());
		assertFalse(instructions.observe(repository.resolve("frontend/app.js")));
	}

	@Test
	void suppliesNewlyDiscoveredInstructionsToTheNextModelRequest() throws Exception {
		Path repository = repository();
		Path nested = Files.createDirectories(repository.resolve("backend/database"));
		Files.writeString(repository.resolve("AGENTS.md"), "Repository rule.");
		Files.writeString(nested.resolve("AGENTS.md"), "Database rule.");
		Files.writeString(nested.resolve("schema.sql"), "select 1;");

		FauxProvider provider = new FauxProvider();
		List<String> prompts = new CopyOnWriteArrayList<>();
		provider.setResponses(List.of(
				new FauxProvider.ResponseStep.Factory(request -> {
					prompts.add(request.context().systemPrompt);
					return FauxProvider.toolCall("read", Json.object().put("path", "backend/database/schema.sql"));
				}),
				new FauxProvider.ResponseStep.Factory(request -> {
					prompts.add(request.context().systemPrompt);
					return FauxProvider.text("Done.");
				})));
		Agent agent = new Agent("Base prompt.", provider.models().getFirst(), provider::stream);
		Cli.configureBuiltInTools(agent, repository, "Base prompt.");
		List<Path> loadedSources = new CopyOnWriteArrayList<>();
		agent.subscribe(event -> {
			if (event instanceof AgentEvent.InstructionLoaded loaded) {
				loadedSources.add(loaded.path());
			}
		});

		agent.prompt("Inspect the schema.");

		assertEquals(
				List.of(
						"Base prompt.\n\nRepository rule.",
						"Base prompt.\n\nRepository rule.\n\nDatabase rule."),
				prompts);
		assertEquals(List.of(repository.resolve("AGENTS.md"), nested.resolve("AGENTS.md")), loadedSources);
	}

	@Test
	void formatsLoadedInstructionFilesForTheUser() {
		Path source = Path.of("/Users/Michael.Reilly/xa/coding-agent/code-lens/AGENTS.md");

		assertEquals("Found /Users/Michael.Reilly/xa/coding-agent/code-lens/AGENTS.md", Cli.instructionLoadedMessage(source));
	}

	private Path repository() throws Exception {
		Path repository = Files.createDirectories(tempDir.resolve("repository"));
		Files.createDirectory(repository.resolve(".git"));
		return repository;
	}
}
