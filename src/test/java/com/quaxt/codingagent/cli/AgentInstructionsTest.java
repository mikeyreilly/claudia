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
import com.quaxt.codingagent.CodingAgentOperations;
import com.quaxt.codingagent.agent.Agent;
import com.quaxt.codingagent.agent.AgentEvent;
import com.quaxt.codingagent.ai.providers.FauxProvider;

class AgentInstructionsTest {
	@TempDir Path tempDir;

	@Test
	void loadsRootInstructionsForANestedWorkingDirectory() throws Exception {
		Path repository = repository();
		Path workingDirectory = Files.createDirectories(repository.resolve("src/main"));
		Path rootInstructions = repository.resolve("AGENTS.md");
		Files.writeString(rootInstructions, "Use the repository build.");

		AgentInstructions instructions = CodingAgentOperations.agentInstructionsForWorkingDirectory(workingDirectory, "Base agent prompt.");

		assertEquals("Base agent prompt.\n\nUse the repository build.", instructions.systemPrompt);
		assertEquals(List.of(rootInstructions), instructions.sources);
	}

	@Test
	void leavesTheBasePromptUntouchedWhenNoInstructionFilesExist() throws Exception {
		Path repository = repository();
		Path workingDirectory = Files.createDirectories(repository.resolve("src"));

		AgentInstructions instructions = CodingAgentOperations.agentInstructionsForWorkingDirectory(workingDirectory, "Base agent prompt.");

		assertEquals("Base agent prompt.", instructions.systemPrompt);
		assertTrue(instructions.sources.isEmpty());
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

		AgentInstructions instructions = CodingAgentOperations.agentInstructionsForWorkingDirectory(workingDirectory, "");

		assertEquals("Repository instructions.\n\nBackend instructions.", instructions.systemPrompt);
		assertEquals(List.of(rootInstructions, backendInstructions), instructions.sources);
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

		AgentInstructions instructions = CodingAgentOperations.agentInstructionsForWorkingDirectory(workingDirectory, "Base.");

		assertEquals("Base.\n\nRoot.\n\nServices.\n\nPayments.", instructions.systemPrompt);
	}

	@Test
	void prefersAnOverrideFileInTheSameDirectory() throws Exception {
		Path repository = repository();
		Files.writeString(repository.resolve("AGENTS.md"), "Standard instructions.");
		Path override = repository.resolve("AGENTS.override.md");
		Files.writeString(override, "Override instructions.");

		AgentInstructions instructions = CodingAgentOperations.agentInstructionsForWorkingDirectory(repository, "");

		assertEquals("Override instructions.", instructions.systemPrompt);
		assertEquals(List.of(override), instructions.sources);
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

		AgentInstructions instructions = CodingAgentOperations.agentInstructionsForWorkingDirectory(backend, "Base.");

		assertEquals("Base.\n\nRoot.\n\nBackend.", instructions.systemPrompt);
		assertTrue(CodingAgentOperations.observeAgentInstructions(instructions, databaseFile));
		assertEquals("Base.\n\nRoot.\n\nBackend.\n\nDatabase.", instructions.systemPrompt);
		assertFalse(CodingAgentOperations.observeAgentInstructions(instructions, repository.resolve("frontend/app.js")));
	}

	@Test
	void suppliesNewlyDiscoveredInstructionsToTheNextModelRequest() throws Exception {
		Path repository = repository();
		Path nested = Files.createDirectories(repository.resolve("backend/database"));
		Files.writeString(repository.resolve("AGENTS.md"), "Repository rule.");
		Files.writeString(nested.resolve("AGENTS.md"), "Database rule.");
		Files.writeString(nested.resolve("schema.sql"), "select 1;");

		FauxProvider provider = CodingAgentOperations.newFauxProvider();
		List<String> prompts = new CopyOnWriteArrayList<>();
		CodingAgentOperations.setFauxResponses(provider, List.of(
				new FauxProvider.ResponseStep.Factory(request -> {
					prompts.add(request.context.systemPrompt);
					return CodingAgentOperations.fauxToolCall("read", CodingAgentOperations.jsonObject().put("path", "backend/database/schema.sql"));
				}),
				new FauxProvider.ResponseStep.Factory(request -> {
					prompts.add(request.context.systemPrompt);
					return CodingAgentOperations.fauxText("Done.");
				})));
		Agent agent = CodingAgentOperations.newAgent("Base prompt.", provider.models.getFirst(), provider);
		CodingAgentOperations.configureBuiltInTools(agent, repository, "Base prompt.");
		List<Path> loadedSources = new CopyOnWriteArrayList<>();
		CodingAgentOperations.subscribe(agent, event -> {
			if (event instanceof AgentEvent.InstructionLoaded loaded) {
				loadedSources.add(loaded.path);
			}
		});

		CodingAgentOperations.prompt(agent, "Inspect the schema.");

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

		assertEquals("Found /Users/Michael.Reilly/xa/coding-agent/code-lens/AGENTS.md", CodingAgentOperations.instructionLoadedMessage(source));
	}

	private Path repository() throws Exception {
		Path repository = Files.createDirectories(tempDir.resolve("repository"));
		Files.createDirectory(repository.resolve(".git"));
		return repository;
	}
}
