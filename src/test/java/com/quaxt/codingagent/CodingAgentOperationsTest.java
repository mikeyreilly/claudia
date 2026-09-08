package com.quaxt.codingagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.quaxt.codingagent.agent.AgentEvent;
import com.quaxt.codingagent.agent.AgentTool;
import com.quaxt.codingagent.agent.CompactionResult;
import com.quaxt.codingagent.ai.Provider;
import com.quaxt.codingagent.ai.Retry;
import com.quaxt.codingagent.ai.StreamOptions;
import com.quaxt.codingagent.ai.auth.Credential;
import com.quaxt.codingagent.ai.auth.CredentialStore;
import com.quaxt.codingagent.ai.http.SseReader;
import com.quaxt.codingagent.ai.json.Json;
import com.quaxt.codingagent.ai.providers.FauxProvider;
import com.quaxt.codingagent.ai.providers.OpenAiResponsesProvider;
import com.quaxt.codingagent.ai.providers.ProviderState;
import com.quaxt.codingagent.ai.stream.AssistantMessageEventStream;
import com.quaxt.codingagent.ai.types.AssistantMessage;
import com.quaxt.codingagent.ai.types.AssistantMessageEvent;
import com.quaxt.codingagent.ai.types.Compat;
import com.quaxt.codingagent.ai.types.Context;
import com.quaxt.codingagent.ai.types.ImageContent;
import com.quaxt.codingagent.ai.types.Message;
import com.quaxt.codingagent.ai.types.Model;
import com.quaxt.codingagent.ai.types.ModelCost;
import com.quaxt.codingagent.ai.types.StopReason;
import com.quaxt.codingagent.ai.types.TextContent;
import com.quaxt.codingagent.ai.types.ThinkingContent;
import com.quaxt.codingagent.ai.types.ThinkingLevel;
import com.quaxt.codingagent.ai.types.ToolCall;
import com.quaxt.codingagent.ai.types.ToolResultMessage;
import com.quaxt.codingagent.ai.types.Usage;
import com.quaxt.codingagent.ai.types.UserMessage;
import com.quaxt.codingagent.ai.util.AbortSignal;
import com.quaxt.codingagent.cli.session.SessionSnapshot;
import com.quaxt.codingagent.mcp.McpConfigLoader;
import com.quaxt.codingagent.mcp.McpConfiguration;
import com.quaxt.codingagent.mcp.McpServerConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.quaxt.codingagent.CodingAgentOperations.jsonObject;
import static com.quaxt.codingagent.CodingAgentOperations.text;
import static com.quaxt.codingagent.CodingAgentOperations.userMessage;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@code CodingAgentOperations}, grouped by the behavior under test.
 */
class CodingAgentOperationsTest {

	@TempDir Path tempDir;

	// CodingAgentRuntime

    @TempDir Path workspace;
    private final CodingAgentOperations runtime = CodingAgentOperations.INSTANCE;

    @Test
    void executesToolsAndRecordsPromptsAndCheckpointsWithoutATerminal() throws Exception {
        FauxProvider provider = configure(100_000);
        AssistantMessage toolUse = answer("Writing the file");
        toolUse.stopReason = StopReason.TOOL_USE;
        toolUse.content.add(new ToolCall("write-1", "write",
                jsonObject().put("path", "result.txt").put("content", "done"), null));
        provider.pendingResponses.add(new FauxProvider.ResponseStep.Message(toolUse));
        enqueue(provider, "File written", "Created result.txt", "Continuing from the checkpoint");
        List<AgentEvent> events = new ArrayList<>();
        runtime.subscribe(events::add);
        recordSession();

        assertEquals(4, runtime.prompt("Create result.txt").size());
        assertEquals("done", Files.readString(workspace.resolve("result.txt")));
        assertTrue(events.stream().anyMatch(AgentEvent.ToolExecutionEnd.class::isInstance));
        assertFalse(runtime.state().streaming());

        runtime.compact(null);
        runtime.prompt("Continue");

        String sessionId = runtime.state().sessionId();
        var entries = runtime.readSession(sessionId);
        assertEquals(8, entries.size()); // Start, four messages, checkpoint, two more messages.
        assertEquals(1, entries.stream().filter(entry -> entry.type.equals("compaction")).count());
        var resumed = runtime.sessionSnapshot(sessionId);
        assertEquals(3, resumed.messages.size());
        assertTrue(text((com.quaxt.codingagent.ai.types.UserMessage) resumed.messages.getFirst())
                .contains("Created result.txt"));
        assertEquals("Continuing from the checkpoint", text((AssistantMessage) resumed.messages.getLast()));
    }

    @Test
    void automaticallyCompactsAndPersistsTheResumeBoundary() throws Exception {
        FauxProvider provider = configure(16_390);
        List<Message> history = List.of(userMessage("A long previous question ".repeat(10)), answer("A previous answer"));
        runtime.restoreMessages(history);
        recordSession();
        runtime.appendSessionMessages(history);
        enqueue(provider, "Earlier work summarized", "Next answer");

        runtime.prompt("Next question");

        var entries = runtime.readSession(runtime.state().sessionId());
        assertEquals(List.of("session_start", "message", "message", "compaction", "message", "message"),
                entries.stream().map(entry -> entry.type).toList());
        assertEquals(3, runtime.sessionSnapshot(runtime.state().sessionId()).messages.size());
        assertFalse(runtime.state().compacting());
    }

    @Test
    void reportsCheckpointPersistenceFailureAndKeepsTheCompactedConversation() throws Exception {
        FauxProvider provider = configure(100_000);
        runtime.restoreMessages(List.of(userMessage("Summarize the work"), answer("Work completed")));
        recordSession();
        List<IOException> failures = new ArrayList<>();
        List<AgentEvent> events = new ArrayList<>();
        runtime.setSessionRecording(true, failures::add);
        runtime.subscribe(events::add);
        Files.delete(workspace.resolve("sessions").resolve(runtime.state().sessionId() + ".jsonl"));
        enqueue(provider, "Checkpoint retained in memory");

        runtime.compact(null);

        assertEquals(1, failures.size());
        assertEquals(1, runtime.state().messages().size());
        assertTrue(events.stream().anyMatch(AgentEvent.CompactionEnd.class::isInstance));
        assertFalse(runtime.state().compacting());
    }

    @Test
    void exposesConversationSnapshotsAndDiscardsOldListenersWhenReconfigured() throws Exception {
        configure(100_000);
        List<Message> restored = new ArrayList<>(List.of(userMessage("Restored question")));
        runtime.restoreMessages(restored);
        restored.clear();
        var snapshot = runtime.state();
        assertEquals(1, snapshot.messages().size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.messages().clear());
        snapshot.model().id = "changed outside the runtime";
        assertEquals("faux-1", runtime.state().model().id);
        List<AgentEvent> previousEvents = new ArrayList<>();
        runtime.subscribe(previousEvents::add);

        FauxProvider provider = configure(100_000);
        enqueue(provider, "Fresh answer");
        runtime.prompt("Fresh question");

        assertTrue(previousEvents.isEmpty());
        assertEquals(2, runtime.state().messages().size());
        assertEquals(1, snapshot.messages().size());
    }

    @Test
    void tellsTheModelItsWorkingDirectoryAheadOfRepositoryInstructions() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "Repository rule: prefer tabs.");
        FauxProvider provider = configure(100_000);

        assertEquals(
                "Working directory: " + workspace.toAbsolutePath().normalize() + "\n"
                        + "Relative paths in tool calls resolve against the working directory. "
                        + "Use an absolute path or a leading ~/ to reach anything outside it.\n\n"
                        + "Repository rule: prefer tabs.",
                promptedSystemPrompt(provider));
    }

    @Test
    void namesTheRepositoryRootWhenTheWorkingDirectoryIsBelowIt() throws Exception {
        Files.createDirectory(workspace.resolve(".git"));
        Path module = Files.createDirectories(workspace.resolve("module"));
        FauxProvider provider = configure(100_000, module);

        assertEquals(
                "Working directory: " + module.toAbsolutePath().normalize() + "\n"
                        + "Repository root: " + workspace.toAbsolutePath().normalize() + "\n"
                        + "Relative paths in tool calls resolve against the working directory. "
                        + "Use an absolute path or a leading ~/ to reach anything outside it.",
                promptedSystemPrompt(provider));
    }

    /** Runs one turn and returns the system prompt the provider was asked with. */
    private String promptedSystemPrompt(FauxProvider provider) throws Exception {
        List<String> prompts = new ArrayList<>();
        provider.pendingResponses.add(new FauxProvider.ResponseStep.Factory(request -> {
            prompts.add(request.context.systemPrompt);
            return answer("Noted");
        }));
        runtime.prompt("Where are you working?");
        assertEquals(1, prompts.size());
        return prompts.getFirst();
    }

    private FauxProvider configure(long contextWindow) {
        return configure(contextWindow, workspace);
    }

    private FauxProvider configure(long contextWindow, Path cwd) {
        Model model = new Model();
        model.id = "faux-1";
        model.name = "Faux";
        model.api = "faux";
        model.provider = "faux";
        model.contextWindow = contextWindow;
        model.maxTokens = 4_096;
        FauxProvider provider = new FauxProvider("faux", "faux", List.of(model));
        runtime.configureAgent(provider, model, cwd, "", null, ThinkingLevel.OFF);
        runtime.setAutoCompaction(true);
        return provider;
    }

    private void recordSession() throws IOException {
        runtime.sessionStore(workspace.resolve("sessions"), List.of());
        runtime.createSessionRecorder(workspace, "faux", "faux-1");
        runtime.setSessionRecording(true, error -> fail("Could not record checkpoint", error));
    }

    private static AssistantMessage answer(String text) {
        AssistantMessage message = new AssistantMessage("faux", "faux", "faux-1");
        message.content.add(new TextContent(text, null));
        message.stopReason = StopReason.STOP;
        return message;
    }

    private static void enqueue(FauxProvider provider, String... answers) {
        for (String text : answers) provider.pendingResponses.add(new FauxProvider.ResponseStep.Message(answer(text)));
    }

	// DataCarrierArchitecture

    @Test
    void reconfiguringTheFoldedAgentDiscardsPriorEventListeners() throws Exception {
        CodingAgentOperations operations = CodingAgentOperations.INSTANCE;
        Method configureAgent = CodingAgentOperations.class.getDeclaredMethod("agent", Provider.class);
        configureAgent.setAccessible(true);
        Field listenersField = CodingAgentOperations.class.getDeclaredField("listeners");
        listenersField.setAccessible(true);

        configureAgent.invoke(operations, new Object[] {null});
        @SuppressWarnings("unchecked")
        List<Object> previousListeners = (List<Object>) listenersField.get(operations);
        previousListeners.add(new Object());

        configureAgent.invoke(operations, new Object[] {null});
        List<?> configuredListeners = (List<?>) listenersField.get(operations);

        assertNotSame(previousListeners, configuredListeners);
        assertTrue(configuredListeners.isEmpty());
    }

    @Test
    void separatesApplicationOperationsFromTheFourProviderRoles() throws Exception {
        assertArrayEquals(
                new CodingAgentOperations[] {CodingAgentOperations.INSTANCE},
                CodingAgentOperations.values());
        assertFalse(Provider.class.isAssignableFrom(CodingAgentOperations.class));
        assertArrayEquals(
                new ProviderState[] {
                    ProviderState.CHATGPT_OPERATIONS,
                    ProviderState.GOOGLE_OPERATIONS,
                    ProviderState.GITHUB_COPILOT_OPERATIONS,
                    ProviderState.OPENAI_COMPATIBLE_OPERATIONS
                },
                ProviderState.values());
        assertTrue(Provider.class.isAssignableFrom(ProviderState.class));

        for (String name : Set.of(
                "id",
                "models",
                "credentials",
                "authBaseUrl",
                "clientId",
                "githubBaseUrl",
                "copilotTokenUrl",
                "defaultCopilotBaseUrl",
                "anthropic",
                "responses")) {
            Field field = ProviderState.class.getDeclaredField(name);
            assertTrue(Modifier.isPublic(field.getModifiers()), name);
            assertFalse(Modifier.isFinal(field.getModifiers()), name);
        }
    }

    @Test
    void coreInitializationRegistersDistinctStateAndRetainsEveryCopilotProtocol() throws Exception {
        CodingAgentOperations operations = CodingAgentOperations.INSTANCE;
        operations.initializeCoreProviders();

        Field field = CodingAgentOperations.class.getDeclaredField("coreProviders");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Provider> providers = (Map<String, Provider>) field.get(operations);

        assertSame(ProviderState.CHATGPT_OPERATIONS, providers.get("chatgpt"));
        assertSame(ProviderState.GOOGLE_OPERATIONS, providers.get("google"));
        assertSame(ProviderState.GITHUB_COPILOT_OPERATIONS, providers.get("github-copilot"));
        assertNotSame(providers.get("chatgpt"), providers.get("google"));
        assertNotSame(providers.get("google"), providers.get("github-copilot"));

        assertTrue(ProviderState.CHATGPT_OPERATIONS.models.stream()
                .allMatch(model -> model.provider.equals("chatgpt")));
        assertTrue(ProviderState.GOOGLE_OPERATIONS.models.stream()
                .allMatch(model -> model.provider.equals("google")));
        assertTrue(ProviderState.GITHUB_COPILOT_OPERATIONS.models.stream()
                .allMatch(model -> model.provider.equals("github-copilot")));
        assertEquals(
                Set.of("anthropic-messages", "openai-completions", "openai-responses"),
                ProviderState.GITHUB_COPILOT_OPERATIONS.models.stream()
                        .map(model -> model.api)
                        .collect(Collectors.toSet()));
        assertEquals(
                operations.catalogModelsForProvider("github-copilot").stream()
                        .map(model -> model.id)
                        .toList(),
                ProviderState.GITHUB_COPILOT_OPERATIONS.models.stream().map(model -> model.id).toList());
    }

    @Test
    void authConfigurationDoesNotBleedBetweenProviderStates() {
        CodingAgentOperations operations = CodingAgentOperations.INSTANCE;
        CredentialStore chatStore = new CredentialStore() {};
        CredentialStore copilotStore = new CredentialStore() {};
        URI chatBase = URI.create("https://chat-auth.example");
        URI githubBase = URI.create("https://github-auth.example");
        URI tokenBase = URI.create("https://copilot-token.example");
        URI apiBase = URI.create("https://copilot-api.example");

        operations.chatGptAuth(
                ProviderState.CHATGPT_OPERATIONS, chatStore, chatBase, "chat-client");
        operations.gitHubCopilotAuth(
                ProviderState.GITHUB_COPILOT_OPERATIONS,
                copilotStore,
                githubBase,
                tokenBase,
                apiBase);

        assertSame(chatStore, ProviderState.CHATGPT_OPERATIONS.credentials);
        assertEquals(chatBase, ProviderState.CHATGPT_OPERATIONS.authBaseUrl);
        assertEquals("chat-client", ProviderState.CHATGPT_OPERATIONS.clientId);
        assertSame(copilotStore, ProviderState.GITHUB_COPILOT_OPERATIONS.credentials);
        assertEquals(githubBase, ProviderState.GITHUB_COPILOT_OPERATIONS.githubBaseUrl);
        assertEquals(tokenBase, ProviderState.GITHUB_COPILOT_OPERATIONS.copilotTokenUrl);
        assertEquals(apiBase, ProviderState.GITHUB_COPILOT_OPERATIONS.defaultCopilotBaseUrl);
        assertNotSame(
                ProviderState.CHATGPT_OPERATIONS.credentials,
                ProviderState.GITHUB_COPILOT_OPERATIONS.credentials);
    }

	// ModelCatalog

	@Test
	void loadsAllBundledCoreProviderCatalogs() {
		CodingAgentOperations catalog = loadBundledModelCatalog();

		assertTrue(catalog.catalogModelsForProvider("anthropic").size() > 0);
		assertTrue(catalog.catalogModelsForProvider("openai").size() > 0);
		assertTrue(catalog.catalogModelsForProvider("google").size() > 0);
		assertTrue(catalog.catalogModelsForProvider("github-copilot").size() > 0);
		assertEquals(
				catalog.catalogModelsForProvider("anthropic").size()
						+ catalog.catalogModelsForProvider("openai").size()
						+ catalog.catalogModelsForProvider("google").size(),
				catalog.allCatalogModels().size() - catalog.catalogModelsForProvider("github-copilot").size());
	}

	@Test
	void loadsGitHubCopilotProtocolModels() {
		CodingAgentOperations catalog = loadBundledModelCatalog();

		assertTrue(catalog.catalogModelsForProvider("github-copilot").size() > 25);
		assertEquals("openai-responses", catalog.requireCatalogModel("github-copilot", "gpt-5.6-terra").api);
		assertEquals("openai-completions", catalog.requireCatalogModel("github-copilot", "gemini-3.6-flash").api);
		assertEquals("anthropic-messages", catalog.requireCatalogModel("github-copilot", "claude-opus-5").api);
	}

	@Test
	void includesChatModelsForChatGptSubscriptions() {
		CodingAgentOperations catalog = loadBundledModelCatalog();

		List<Model> models = catalog.chatGptSubscriptionModels();
		List<String> ids = models.stream().map(model -> model.id).toList();
		assertTrue(ids.containsAll(List.of(
				"gpt-5-chat-latest",
				"gpt-5.2-chat-latest",
				"gpt-5.3-chat-latest")));
		assertTrue(models.stream().allMatch(model -> model.provider.equals("chatgpt")));
		assertTrue(models.stream().allMatch(model -> model.baseUrl.equals(
				CodingAgentOperations.CHATGPT_CODEX_API_BASE_URL.toString())));
		assertTrue(models.stream().allMatch(model -> model.cost == ModelCost.FREE));
	}

	@Test
	void coreProviderInitializationKeepsChatGptModelsIsolated() {
		CodingAgentOperations operations = CodingAgentOperations.INSTANCE;

		operations.initializeCoreProviders();

		List<String> chatGptIds = operations.coreProviderModels("chatgpt").stream()
				.map(model -> model.id)
				.toList();
		assertTrue(chatGptIds.contains("gpt-5.6-terra"));
		assertTrue(chatGptIds.contains("gpt-5.3-chat-latest"));
		assertTrue(operations.coreProviderModels("google").stream()
				.allMatch(model -> model.provider.equals("google")));
		assertTrue(operations.coreProviderModels("github-copilot").stream()
				.allMatch(model -> model.provider.equals("github-copilot")));
	}

	@Test
	void preservesAdaptiveThinkingCompatibility() {
		Model opus = loadBundledModelCatalog().requireCatalogModel("github-copilot", "claude-opus-5");

		Compat.AnthropicMessages compat = assertInstanceOf(Compat.AnthropicMessages.class, opus.compat);
		assertEquals(Boolean.TRUE, compat.forceAdaptiveThinking);
	}

	@Test
	void preservesGeneratedCatalogProperties() {
		CodingAgentOperations catalog = loadBundledModelCatalog();

		Model model = catalog.requireCatalogModel("anthropic", "claude-haiku-4-5");
		assertEquals("anthropic-messages", model.api);
		assertEquals("https://api.anthropic.com", model.baseUrl);
		assertTrue(model.input.contains("image"));
		assertTrue(model.reasoning);
		assertTrue(model.contextWindow > 0);
		assertTrue(model.maxTokens > 0);
		assertTrue(model.cost.input > 0);
		assertNotNull(model.cost);
	}

	@Test
	void returnsNullOrClearErrorForUnknownModel() {
		CodingAgentOperations catalog = loadBundledModelCatalog();

		assertEquals(null, catalog.findCatalogModel("openai", "does-not-exist"));
		IllegalArgumentException exception =
				assertThrows(IllegalArgumentException.class, () -> catalog.requireCatalogModel("openai", "does-not-exist"));
		assertEquals("Unknown model: openai/does-not-exist", exception.getMessage());
	}

	private static CodingAgentOperations loadBundledModelCatalog() {
		CodingAgentOperations.INSTANCE.loadBundledModelCatalog();
		return CodingAgentOperations.INSTANCE;
	}

	// Models

	private static Model costModel(ModelCost cost) {
		Model model = new Model();
		model.id = "m";
		model.name = "m";
		model.api = "test";
		model.provider = "p";
		model.baseUrl = "https://example.com";
		model.cost = cost;
		return model;
	}

	private static Model thinkingLevelsModel(Map<ThinkingLevel, String> thinkingLevelMap) {
		Model model = costModel(ModelCost.FREE);
		model.id = "r";
		model.name = "r";
		model.reasoning = true;
		model.thinkingLevelMap = thinkingLevelMap;
		return model;
	}

	@Test
	void calculatesBaseCost() {
		Model m = costModel(new ModelCost(3, 15, 0.3, 3.75, List.of()));
		Usage usage = new Usage();
		usage.input = 1_000_000;
		usage.output = 2_000_000;
		usage.cacheRead = 1_000_000;
		usage.cacheWrite = 1_000_000;
		CodingAgentOperations.calculateCost(m, usage);
		assertEquals(3.0, usage.cost.input, 1e-9);
		assertEquals(30.0, usage.cost.output, 1e-9);
		assertEquals(0.3, usage.cost.cacheRead, 1e-9);
		assertEquals(3.75, usage.cost.cacheWrite, 1e-9);
		assertEquals(37.05, usage.cost.total, 1e-9);
	}

	@Test
	void appliesTierPricingWhenInputExceedsThreshold() {
		Model m = costModel(new ModelCost(1, 2, 0.1, 0.2,
				List.of(new ModelCost.Tier(200_000, 2, 4, 0.2, 0.4))));
		Usage usage = new Usage();
		usage.input = 300_000;
		CodingAgentOperations.calculateCost(m, usage);
		assertEquals(0.6, usage.cost.input, 1e-9); // 2 $/M * 0.3M
	}

	@Test
	void oneHourCacheWritesCostDoubleInputRate() {
		Model m = costModel(new ModelCost(3, 15, 0.3, 3.75, List.of()));
		Usage usage = new Usage();
		usage.cacheWrite = 1_000_000;
		usage.cacheWrite1h = 1_000_000L;
		CodingAgentOperations.calculateCost(m, usage);
		assertEquals(6.0, usage.cost.cacheWrite, 1e-9); // 2x input rate
	}

	@Test
	void nonReasoningModelSupportsOnlyOff() {
		Model m = costModel(ModelCost.FREE);
		assertEquals(List.of(ThinkingLevel.OFF), CodingAgentOperations.getSupportedThinkingLevels(m));
		assertEquals(ThinkingLevel.OFF, CodingAgentOperations.clampThinkingLevel(m, ThinkingLevel.HIGH));
	}

	@Test
	void xhighAndMaxRequireExplicitMapping() {
		Model m = thinkingLevelsModel(null);
		List<ThinkingLevel> levels = CodingAgentOperations.getSupportedThinkingLevels(m);
		assertTrue(levels.contains(ThinkingLevel.HIGH));
		assertFalse(levels.contains(ThinkingLevel.XHIGH));
		assertFalse(levels.contains(ThinkingLevel.MAX));
		assertEquals(ThinkingLevel.HIGH, CodingAgentOperations.clampThinkingLevel(m, ThinkingLevel.XHIGH));
	}

	@Test
	void nullMappingDisablesLevelAndClampsUp() {
		Map<ThinkingLevel, String> map = new EnumMap<>(ThinkingLevel.class);
		map.put(ThinkingLevel.MEDIUM, null);
		Model m = thinkingLevelsModel(map);
		List<ThinkingLevel> levels = CodingAgentOperations.getSupportedThinkingLevels(m);
		assertFalse(levels.contains(ThinkingLevel.MEDIUM));
		assertEquals(ThinkingLevel.HIGH, CodingAgentOperations.clampThinkingLevel(m, ThinkingLevel.MEDIUM));
	}

	@Test
	void resolvesMappedThinkingLevelsForProviderRequests() {
		Map<ThinkingLevel, String> map = new EnumMap<>(ThinkingLevel.class);
		map.put(ThinkingLevel.XHIGH, "high");
		map.put(ThinkingLevel.MAX, "max");
		Model m = thinkingLevelsModel(map);

		assertEquals("max", CodingAgentOperations.providerThinkingLevel(m, ThinkingLevel.MAX));
		assertEquals("high", CodingAgentOperations.providerThinkingLevel(m, ThinkingLevel.XHIGH));
		assertEquals(null, CodingAgentOperations.providerThinkingLevel(m, ThinkingLevel.OFF));
	}

	// Retry

	private static AssistantMessage message(StopReason reason, String error) {
		AssistantMessage m = new AssistantMessage("a", "p", "m");
		m.stopReason = reason;
		m.errorMessage = error;
		return m;
	}

	@Test
	void classifiesRetryableErrors() {
		assertTrue(CodingAgentOperations.isRetryableAssistantError(message(StopReason.ERROR, "429 Too Many Requests")));
		assertTrue(CodingAgentOperations.isRetryableAssistantError(message(StopReason.ERROR, "socket hang up")));
		assertTrue(CodingAgentOperations.isRetryableAssistantError(message(StopReason.ERROR, "java.net.ConnectException")));
		assertTrue(CodingAgentOperations.isRetryableAssistantError(message(StopReason.ERROR, "Overloaded")));
		assertTrue(CodingAgentOperations.isRetryableAssistantError(message(
				StopReason.ERROR,
				"503: upstream connect error or disconnect/reset before headers. reset reason: connection termination")));
	}

	@Test
	void classifiesNonRetryableErrors() {
		assertFalse(CodingAgentOperations.isRetryableAssistantError(message(StopReason.ERROR, "insufficient_quota: add credits")));
		assertFalse(CodingAgentOperations.isRetryableAssistantError(message(StopReason.ERROR, "invalid_request_error")));
		assertFalse(CodingAgentOperations.isRetryableAssistantError(message(StopReason.STOP, null)));
		// quota patterns take precedence even when a retryable token (429) is present
		assertFalse(CodingAgentOperations.isRetryableAssistantError(message(StopReason.ERROR, "429 quota exceeded")));
	}

	@Test
	void retriesUntilSuccess() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		AssistantMessage result = CodingAgentOperations.INSTANCE.retryAssistantCall(
				() -> calls.incrementAndGet() < 3
						? message(StopReason.ERROR, "503 service unavailable")
						: message(StopReason.STOP, null),
				new Retry.Policy(true, 5, 1),
				null,
				null);
		assertEquals(3, calls.get());
		assertEquals(StopReason.STOP, result.stopReason);
	}

	@Test
	void returnsErrorAfterExhaustingRetries() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		AssistantMessage result = CodingAgentOperations.INSTANCE.retryAssistantCall(
				() -> {
					calls.incrementAndGet();
					return message(StopReason.ERROR, "500 internal error");
				},
				new Retry.Policy(true, 2, 1),
				null,
				null);
		assertEquals(3, calls.get()); // initial + 2 retries
		assertEquals(StopReason.ERROR, result.stopReason);
	}

	@Test
	void doesNotRetryNonRetryable() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		AssistantMessage result = CodingAgentOperations.INSTANCE.retryAssistantCall(
				() -> {
					calls.incrementAndGet();
					return message(StopReason.ERROR, "billing problem");
				},
				new Retry.Policy(true, 5, 1),
				null,
				null);
		assertEquals(1, calls.get());
		assertEquals(StopReason.ERROR, result.stopReason);
	}

	@Test
	void abortDuringBackoffNormalizesToAborted() throws Exception {
		AbortSignal signal = new AbortSignal();
		Thread aborter = Thread.ofVirtual().start(() -> {
			try {
				Thread.sleep(30);
			} catch (InterruptedException ignored) {
			}
			CodingAgentOperations.INSTANCE.abort(signal);
		});
		AssistantMessage result = CodingAgentOperations.INSTANCE.retryAssistantCall(
				() -> message(StopReason.ERROR, "503 service unavailable"),
				new Retry.Policy(true, 3, 10_000),
				signal,
				null);
		aborter.join();
		assertEquals(StopReason.ABORTED, result.stopReason);
		assertNull(result.errorMessage);
	}

	@Test
	void neverRetriesAbortedResponses() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		AssistantMessage result = CodingAgentOperations.INSTANCE.retryAssistantCall(
				() -> {
					calls.incrementAndGet();
					return message(StopReason.ABORTED, null);
				},
				new Retry.Policy(true, 5, 1),
				null,
				null);
		assertEquals(1, calls.get());
		assertEquals(StopReason.ABORTED, result.stopReason);
	}

	// ChatGptAuth

	@Test
	void deviceLoginPersistsAccountAndRefreshesExpiredToken() throws Exception {
		AtomicInteger tokenExchanges = new AtomicInteger();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		try {
			server.createContext("/api/accounts/deviceauth/usercode", exchange -> respond(exchange, 200,
					"{\"device_auth_id\":\"device-1\",\"user_code\":\"ABCD-EFGH\",\"interval\":\"0\"}"));
			server.createContext("/api/accounts/deviceauth/token", exchange -> respond(exchange, 200,
					"{\"authorization_code\":\"auth-code\",\"code_verifier\":\"verifier\"}"));
			server.createContext("/oauth/token", exchange -> {
				exchange.getRequestBody().readAllBytes();
				int request = tokenExchanges.incrementAndGet();
				String access = request == 1 ? "access-1" : "access-2";
				respond(exchange, 200, "{\"access_token\":\"" + access
						+ "\",\"refresh_token\":\"refresh-1\",\"expires_in\":1,\"id_token\":\""
						+ jwt("account-123") + "\"}");
			});
			server.start();

            CodingAgentOperations.INSTANCE.fileCredentialStore(tempDir.resolve("auth.json"), null);
			URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
			ProviderState state = ProviderState.CHATGPT_OPERATIONS;
			CodingAgentOperations.INSTANCE.chatGptAuth(
					state, CodingAgentOperations.INSTANCE, base, "test-client");
			CodingAgentOperations.ChatGptDeviceCode device =
					CodingAgentOperations.INSTANCE.chatGptBeginLogin(state);
			assertEquals("ABCD-EFGH", device.userCode);
			CodingAgentOperations.INSTANCE.chatGptCompleteLogin(state, device);
			assertTrue(CodingAgentOperations.INSTANCE.chatGptHasCredential(state));

			CodingAgentOperations.ChatGptToken refreshed =
					CodingAgentOperations.INSTANCE.chatGptResolveToken(state);
			assertEquals("access-2", refreshed.accessToken);
			assertEquals("account-123", refreshed.accountId);
			assertEquals(2, tokenExchanges.get());

			CodingAgentOperations.INSTANCE.chatGptLogout(state);
			assertFalse(CodingAgentOperations.INSTANCE.chatGptHasCredential(state));
		} finally {
			server.stop(0);
		}
	}

	private static String jwt(String accountId) throws IOException {
		String header = Base64.getUrlEncoder().withoutPadding().encodeToString("{}".getBytes(StandardCharsets.UTF_8));
		String claims = CodingAgentOperations.jsonObject()
				.set("https://api.openai.com/auth", CodingAgentOperations.jsonObject().put("chatgpt_account_id", accountId))
				.toString();
		return header + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + ".signature";
	}

	private static void respond(HttpExchange exchange, int status, String body) throws IOException {
		exchange.getRequestBody().readAllBytes();
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

	// EnvApiKeys

	@Test
	void resolvesCoreProviderApiKeys() {
		assertEquals("key", CodingAgentOperations.resolveApiKey("openai", Map.of("OPENAI_API_KEY", "key")).orElseThrow());
		assertEquals("gemini", CodingAgentOperations.resolveApiKey("google", Map.of("GEMINI_API_KEY", "gemini")).orElseThrow());
		assertEquals(
				"google",
				CodingAgentOperations.resolveApiKey("google", Map.of("GOOGLE_API_KEY", "google")).orElseThrow());
	}

	@Test
	void skipsAnthropicBearerAuthTokenForApiKeyResolution() {
		Map<String, String> environment = Map.of(
				"ANTHROPIC_AUTH_TOKEN", "bearer",
				"ANTHROPIC_API_KEY", "key");

		assertEquals(List.of("ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_API_KEY"), CodingAgentOperations.findApiKeyEnvVars("anthropic", environment));
		assertEquals("key", CodingAgentOperations.resolveApiKey("anthropic", environment).orElseThrow());
		assertTrue(CodingAgentOperations.resolveApiKey("unknown", environment).isEmpty());
	}

	@Test
	void acceptsAnthropicProxyBaseUrl() {
		assertEquals(
				"http://127.0.0.1:8787",
				CodingAgentOperations.configuredAnthropicBaseUrl(
						Map.of("ANTHROPIC_BASE_URL", "http://127.0.0.1:8787/")));
		assertEquals(null, CodingAgentOperations.configuredAnthropicBaseUrl(Map.of()));
	}

	@Test
	void rejectsInvalidAnthropicProxyBaseUrl() {
		org.junit.jupiter.api.Assertions.assertThrows(
				IllegalArgumentException.class,
				() -> CodingAgentOperations.configuredAnthropicBaseUrl(
						Map.of("ANTHROPIC_BASE_URL", "not a URL")));
	}

	// GitHubCopilotAuth

	@Test
	void completesDeviceLoginMintsCopilotTokenAndPersistsEnabledModels() throws Exception {
		AtomicInteger tokenRequests = new AtomicInteger();
		AtomicInteger policyRequests = new AtomicInteger();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/login/device/code", exchange -> writeJson(exchange, """
				{"device_code":"device","user_code":"ABCD-EFGH","verification_uri":"https://github.com/login/device","expires_in":900,"interval":0}
				"""));
		server.createContext("/login/oauth/access_token", exchange -> {
			assertTrue(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).contains("device_code=device"));
			writeJson(exchange, "{\"access_token\":\"github-token\"}");
		});
		server.createContext("/copilot_internal/v2/token", exchange -> {
			assertEquals("token github-token", exchange.getRequestHeaders().getFirst("Authorization"));
			tokenRequests.incrementAndGet();
			writeJson(exchange, "{\"token\":\"copilot-token\",\"expires_at\":4102444800}");
		});
		server.createContext("/models", exchange -> {
			assertEquals("Bearer copilot-token", exchange.getRequestHeaders().getFirst("Authorization"));
			writeJson(exchange, """
					{"data":[
					  {"id":"gpt-5.4","model_picker_enabled":true,"policy":{"state":"enabled"},"capabilities":{"supports":{"tool_calls":true}}},
					  {"id":"disabled","model_picker_enabled":false,"policy":{"state":"disabled"},"capabilities":{"supports":{"tool_calls":true}}},
					  {"id":"no-tools","model_picker_enabled":true,"policy":{"state":"enabled"},"capabilities":{"supports":{"tool_calls":false}}}
					]}
					""");
		});
		server.createContext("/models/gpt-5.4/policy", exchange -> {
			assertEquals("Bearer copilot-token", exchange.getRequestHeaders().getFirst("Authorization"));
			assertEquals("chat-policy", exchange.getRequestHeaders().getFirst("openai-intent"));
			assertEquals("{\"state\":\"enabled\"}", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			policyRequests.incrementAndGet();
			writeJson(exchange, "{}");
		});
		server.start();
		try {
			URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
			CodingAgentOperations store = CodingAgentOperations.INSTANCE;
			store.fileCredentialStore(tempDir.resolve("auth.json"), null);
			ProviderState state = ProviderState.GITHUB_COPILOT_OPERATIONS;
			CodingAgentOperations auth = CodingAgentOperations.INSTANCE;
			auth.gitHubCopilotAuth(
					state, store, base, base.resolve("/copilot_internal/v2/token"), base);

			CodingAgentOperations.GitHubCopilotDeviceCode device = auth.gitHubCopilotBeginLogin(state);
			assertEquals("ABCD-EFGH", device.userCode);
			Credential.OAuthCredential credential = auth.gitHubCopilotCompleteLogin(state, device);

			assertEquals("copilot-token", credential.access);
			assertEquals(java.util.List.of("gpt-5.4"), credential.availableModelIds);
			assertEquals(1, auth.gitHubCopilotEnableModels(state, java.util.List.of("gpt-5.4")));
			assertEquals(
					java.util.List.of("gpt-5.4"),
					auth.gitHubCopilotRefreshAvailableModels(state).availableModelIds);
			assertEquals("copilot-token", auth.gitHubCopilotResolveToken(state).accessToken);
			assertEquals(1, tokenRequests.get());
			assertEquals(1, policyRequests.get());
		} finally {
			server.stop(0);
		}
	}

	@Test
	void reportsCopilotTokenHttpFailuresAsIoErrors() throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/copilot_internal/v2/token", exchange -> {
			byte[] body = "<html><title>Unicorn!</title></html>".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("content-type", "text/html");
			exchange.sendResponseHeaders(502, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		try {
			URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
			CodingAgentOperations store = CodingAgentOperations.INSTANCE;
			store.fileCredentialStore(tempDir.resolve("failing-auth.json"), null);
			CodingAgentOperations.INSTANCE.modifyCredential(
					store,
					CodingAgentOperations.GITHUB_COPILOT_PROVIDER_ID,
					ignored -> new Credential.OAuthCredential(
							"expired-token", "github-token", 0, null, Map.of()));
			ProviderState state = ProviderState.GITHUB_COPILOT_OPERATIONS;
			CodingAgentOperations auth = CodingAgentOperations.INSTANCE;
			auth.gitHubCopilotAuth(
					state, store, base, base.resolve("/copilot_internal/v2/token"), base);

			IOException error = assertThrows(IOException.class, () -> auth.gitHubCopilotResolveToken(state));

			assertTrue(error.getMessage().startsWith("502 from "));
		} finally {
			server.stop(0);
		}
	}

	private static void writeJson(com.sun.net.httpserver.HttpExchange exchange, String value) throws java.io.IOException {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("content-type", "application/json");
		exchange.sendResponseHeaders(200, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

	// SseReader

	private static SseReader reader(String input) {
		return CodingAgentOperations.sseReader(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
	}

	private static SseReader.SseEvent next(SseReader reader) throws Exception {
		return CodingAgentOperations.nextSseEvent(reader);
	}

	@Test
	void parsesEventAndData() throws Exception {
		SseReader r = reader("event: message_start\ndata: {\"a\":1}\n\n");
		try {
			SseReader.SseEvent event = next(r);
			assertEquals("message_start", event.event);
			assertEquals("{\"a\":1}", event.data);
			assertNull(next(r));
		} finally {
			CodingAgentOperations.INSTANCE.closeSseReader(r);
		}
	}

	@Test
	void joinsMultipleDataLines() throws Exception {
		SseReader r = reader("data: line1\ndata: line2\n\n");
		try {
			assertEquals("line1\nline2", next(r).data);
		} finally {
			CodingAgentOperations.INSTANCE.closeSseReader(r);
		}
	}

	@Test
	void ignoresCommentsAndRetry() throws Exception {
		SseReader r = reader(": keepalive\nretry: 3000\ndata: x\n\n");
		try {
			assertEquals("x", next(r).data);
		} finally {
			CodingAgentOperations.INSTANCE.closeSseReader(r);
		}
	}

	@Test
	void handlesMultipleEvents() throws Exception {
		SseReader r = reader("data: one\n\ndata: two\n\ndata: [DONE]\n\n");
		try {
			assertEquals("one", next(r).data);
			assertEquals("two", next(r).data);
			assertEquals("[DONE]", next(r).data);
			assertNull(next(r));
		} finally {
			CodingAgentOperations.INSTANCE.closeSseReader(r);
		}
	}

	@Test
	void flushesTrailingEventWithoutBlankLine() throws Exception {
		SseReader r = reader("data: tail");
		try {
			assertEquals("tail", next(r).data);
			assertNull(next(r));
		} finally {
			CodingAgentOperations.INSTANCE.closeSseReader(r);
		}
	}

	@Test
	void stripsSingleLeadingSpaceOnly() throws Exception {
		SseReader r = reader("data:  two spaces\n\n");
		try {
			assertEquals(" two spaces", next(r).data);
		} finally {
			CodingAgentOperations.INSTANCE.closeSseReader(r);
		}
	}

	// ChatGptProvider

	@Test
	void configuresCodexHeadersAndSessionAffinity() {
		StreamOptions options = new StreamOptions();
		options.sessionId = "session-1";

		CodingAgentOperations.INSTANCE.configureCodexRequest(
				options, new CodingAgentOperations.ChatGptToken("access-token", "account-1"));

		assertEquals("access-token", options.apiKey);
		assertEquals(CodingAgentOperations.CHATGPT_CODEX_API_BASE_URL.toString(), options.baseUrl);
		assertEquals("account-1", options.headers.get("ChatGPT-Account-Id"));
		assertEquals("pi-java", options.headers.get("originator"));
		assertTrue(options.headers.get("User-Agent").startsWith("pi-java ("));
		assertEquals("text/event-stream", options.headers.get("Accept"));
		assertEquals("responses=experimental", options.headers.get("OpenAI-Beta"));
		assertEquals("session-1", options.headers.get("session-id"));
		assertEquals("session-1", options.headers.get("x-client-request-id"));
	}

	// GitHubCopilotProvider

	private static StreamOptions copilotOptions(String apiKey) {
		StreamOptions options = new StreamOptions();
		options.apiKey = apiKey;
		return options;
	}

	private static StreamOptions copilotOptions(String apiKey, ThinkingLevel reasoning) {
		StreamOptions options = copilotOptions(apiKey);
		options.reasoning = reasoning;
		return options;
	}

	@Test
	void routesAllCopilotWireProtocolsWithBearerAuthentication() throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/messages", exchange -> {
			assertBearer(exchange);
			writeCopilotSse(exchange, """
					event: message_start
					data: {"message":{"id":"a","usage":{"input_tokens":1}}}

					event: content_block_start
					data: {"index":0,"content_block":{"type":"text"}}

					event: content_block_delta
					data: {"index":0,"delta":{"type":"text_delta","text":"anthropic"}}

					event: message_stop
					data: {}

					""");
		});
		server.createContext("/chat/completions", exchange -> {
			assertBearer(exchange);
			writeCopilotSse(exchange, """
					data: {"choices":[{"delta":{"content":"completions"},"finish_reason":"stop"}]}

					data: [DONE]

					""");
		});
		server.createContext("/responses", exchange -> {
			assertBearer(exchange);
			writeCopilotSse(exchange, """
					data: {"type":"response.output_item.added","item":{"id":"m","type":"message"}}

					data: {"type":"response.output_text.delta","item_id":"m","delta":"responses"}

					data: {"type":"response.completed","response":{"id":"r","status":"completed","usage":{"input_tokens":1,"output_tokens":1}}}

					""");
		});
		server.start();
		try {
			String base = "http://127.0.0.1:" + server.getAddress().getPort();
			List<Model> models = List.of(
					copilotModel("anthropic", "anthropic-messages", base),
					copilotModel("completions", "openai-completions", base),
					copilotModel("responses", "openai-responses", base));
			CodingAgentOperations store = CodingAgentOperations.INSTANCE;
			store.fileCredentialStore(
					Files.createTempDirectory("copilot-auth").resolve("auth.json"), null);
			CodingAgentOperations.INSTANCE.modifyCredential(
					store,
					CodingAgentOperations.GITHUB_COPILOT_PROVIDER_ID,
					ignored -> new Credential.OAuthCredential(
							"copilot-token", "github-token", Long.MAX_VALUE, null, Map.of()));
			ProviderState provider = ProviderState.GITHUB_COPILOT_OPERATIONS;
			CodingAgentOperations.INSTANCE.gitHubCopilotAuth(
					provider, store, URI.create(base), URI.create(base + "/token"), URI.create(base));
			CodingAgentOperations.INSTANCE.newGitHubCopilotProvider(provider, models);
			assertEquals(models, provider.models);

			assertEquals("anthropic", CodingAgentOperations.text(
					CodingAgentOperations.result(CodingAgentOperations.INSTANCE.stream(provider, models.get(0), new Context(), new StreamOptions()))));
			assertEquals("completions", CodingAgentOperations.text(
					CodingAgentOperations.result(CodingAgentOperations.INSTANCE.stream(provider, models.get(1), new Context(), new StreamOptions()))));
			assertEquals("responses", CodingAgentOperations.text(
					CodingAgentOperations.result(CodingAgentOperations.INSTANCE.stream(provider, models.get(2), new Context(), new StreamOptions()))));
		} finally {
			server.stop(0);
		}
	}

	@Test
	void sendsAdaptiveThinkingForCopilotOpus5() throws Exception {
		AtomicReference<JsonNode> request = new AtomicReference<>();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/messages", exchange -> {
			assertBearer(exchange);
			request.set(Json.MAPPER.readTree(exchange.getRequestBody()));
			writeCopilotSse(exchange, """
					event: message_start
					data: {"message":{"id":"opus","usage":{"input_tokens":1}}}

					event: content_block_start
					data: {"index":0,"content_block":{"type":"text"}}

					event: content_block_delta
					data: {"index":0,"delta":{"type":"text_delta","text":"adaptive"}}

					event: message_stop
					data: {}

					""");
		});
		server.start();
		try {
			String base = "http://127.0.0.1:" + server.getAddress().getPort();
			CodingAgentOperations.INSTANCE.loadBundledModelCatalog();
			Model opus = CodingAgentOperations.copyModel(CodingAgentOperations.INSTANCE.requireCatalogModel(
					CodingAgentOperations.GITHUB_COPILOT_PROVIDER_ID, "claude-opus-5"));
			opus.baseUrl = base;

			CodingAgentOperations store = CodingAgentOperations.INSTANCE;
			store.fileCredentialStore(
					Files.createTempDirectory("copilot-auth").resolve("auth.json"), null);
			CodingAgentOperations.INSTANCE.modifyCredential(
					store,
					CodingAgentOperations.GITHUB_COPILOT_PROVIDER_ID,
					ignored -> new Credential.OAuthCredential(
							"copilot-token", "github-token", Long.MAX_VALUE, null, Map.of()));
			ProviderState provider = ProviderState.GITHUB_COPILOT_OPERATIONS;
			CodingAgentOperations.INSTANCE.gitHubCopilotAuth(
					provider, store, URI.create(base), URI.create(base + "/token"), URI.create(base));
			CodingAgentOperations.INSTANCE.newGitHubCopilotProvider(provider, List.of(opus));
			Context context = new Context();
			context.messages.add(CodingAgentOperations.userMessage("Use adaptive thinking"));

			assertEquals(
					"adaptive",
					CodingAgentOperations.text(CodingAgentOperations.result(
							CodingAgentOperations.INSTANCE.stream(provider, opus, context, copilotOptions(null, ThinkingLevel.MEDIUM)))));

			JsonNode payload = request.get();
			assertNotNull(payload);
			assertEquals("adaptive", payload.path("thinking").path("type").asText());
			assertEquals("summarized", payload.path("thinking").path("display").asText());
			assertFalse(payload.path("thinking").has("budget_tokens"));
			assertEquals("medium", payload.path("output_config").path("effort").asText());
		} finally {
			server.stop(0);
		}
	}

	private static Model copilotModel(String id, String api, String baseUrl) {
		Model model = new Model();
		model.id = id;
		model.name = id;
		model.api = api;
		model.provider = CodingAgentOperations.GITHUB_COPILOT_PROVIDER_ID;
		model.baseUrl = baseUrl;
		model.cost = ModelCost.FREE;
		model.contextWindow = 1000;
		model.maxTokens = 100;
		model.headers = new java.util.LinkedHashMap<>(java.util.Map.of("Copilot-Integration-Id", "vscode-chat"));
		return model;
	}

	private static void assertBearer(HttpExchange exchange) {
		assertEquals("Bearer copilot-token", exchange.getRequestHeaders().getFirst("Authorization"));
	}

	private static void writeCopilotSse(HttpExchange exchange, String body) throws java.io.IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("content-type", "text/event-stream");
		exchange.sendResponseHeaders(200, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

	// GoogleProvider

	private static StreamOptions googleOptions(String apiKey) {
		StreamOptions options = new StreamOptions();
		options.apiKey = apiKey;
		return options;
	}

	private static StreamOptions googleOptions(String apiKey, ThinkingLevel reasoning) {
		StreamOptions options = googleOptions(apiKey);
		options.reasoning = reasoning;
		return options;
	}

	@Test
	void sendsGeminiRequestAndStreamsTextAndUsage() throws Exception {
		AtomicReference<String> request = new AtomicReference<>();
		HttpServer server = googleServer(exchange -> {
			assertTrue(exchange.getRequestURI().getQuery().contains("key=test-key"));
			request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			writeGoogleSse(
					exchange,
					"""
					data: {"responseId":"resp_1","candidates":[{"content":{"parts":[{"text":"hello "}]}}]}

					data: {"responseId":"resp_1","candidates":[{"content":{"parts":[{"text":"world"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":5,"cachedContentTokenCount":1,"candidatesTokenCount":2,"totalTokenCount":7}}

					""");
		});
		try {
			Model model = googleModel(googleUrl(server));
			ProviderState provider = CodingAgentOperations.INSTANCE.googleProvider(
					ProviderState.GOOGLE_OPERATIONS, List.of(model));
			Context context = new Context("system");
			context.messages.add(CodingAgentOperations.userMessage("hi"));

			AssistantMessage result =
					CodingAgentOperations.result(CodingAgentOperations.INSTANCE.stream(provider, model, context, googleOptions("test-key")));

			assertEquals("hello world", CodingAgentOperations.text(result));
			assertEquals("resp_1", result.responseId);
			assertEquals(StopReason.STOP, result.stopReason);
			assertEquals(4, result.usage.input);
			assertEquals(1, result.usage.cacheRead);
			assertEquals(2, result.usage.output);
			assertTrue(request.get().contains("\"system\""));
			assertTrue(request.get().contains("\"contents\""));
		} finally {
			server.stop(0);
		}
	}

	private static Model googleModel(String baseUrl) {
		Model model = new Model();
		model.id = "gemini-test";
		model.name = "gemini-test";
		model.api = "google-generative-ai";
		model.provider = "google";
		model.baseUrl = baseUrl;
		model.cost = ModelCost.FREE;
		model.contextWindow = 1000;
		model.maxTokens = 100;
		return model;
	}

	private static HttpServer googleServer(GoogleExchangeHandler handler) throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1beta/models/gemini-test:streamGenerateContent", exchange -> {
			try {
				handler.handle(exchange);
			} catch (Exception e) {
				throw new java.io.IOException(e);
			} finally {
				exchange.close();
			}
		});
		server.start();
		return server;
	}

	private static String googleUrl(HttpServer server) {
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta";
	}

	private static void writeGoogleSse(HttpExchange exchange, String body) throws Exception {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("content-type", "text/event-stream");
		exchange.sendResponseHeaders(200, bytes.length);
		exchange.getResponseBody().write(bytes);
	}

	@FunctionalInterface
	private interface GoogleExchangeHandler {
		void handle(HttpExchange exchange) throws Exception;
	}

	// OpenAiCompatibleProvider

	private static StreamOptions compatibleOptions(String apiKey) {
		StreamOptions options = new StreamOptions();
		options.apiKey = apiKey;
		return options;
	}

	private static StreamOptions compatibleOptions(String apiKey, ThinkingLevel reasoning) {
		StreamOptions options = compatibleOptions(apiKey);
		options.reasoning = reasoning;
		return options;
	}

	private static ProviderState openAiCompatibleProvider(
			String id, String name, String baseUrl, List<Model> models) {
		return CodingAgentOperations.INSTANCE.openAiCompatibleProvider(
				ProviderState.OPENAI_COMPATIBLE_OPERATIONS, id, models);
	}

	@Test
	void sendsChatCompletionRequestAndStreamsText() throws Exception {
		AtomicReference<String> requestBody = new AtomicReference<>();
		HttpServer server = compatibleServer(exchange -> {
			requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			assertEquals("Bearer test-key", exchange.getRequestHeaders().getFirst("Authorization"));
			writeCompatibleSse(
					exchange,
					"""
					data: {"choices":[{"delta":{"content":"hello "}}]}

					data: {"choices":[{"delta":{"content":"world"},"finish_reason":"stop"}],"usage":{"prompt_tokens":4,"completion_tokens":2,"total_tokens":6}}

					data: [DONE]

					""");
		});
		try {
			Model model = compatibleModel(compatibleUrl(server));
			ProviderState provider = openAiCompatibleProvider("custom", "Custom", compatibleUrl(server), List.of(model));
			Context context = new Context("system instructions");
			context.messages.add(CodingAgentOperations.userMessage("hello"));

			AssistantMessageEventStream stream =
					CodingAgentOperations.INSTANCE.stream(provider, model, context, compatibleOptions("test-key"));
			List<AssistantMessageEvent> events = new ArrayList<>();
			for (AssistantMessageEvent event : CodingAgentOperations.events(stream)) {
				events.add(event);
			}

			AssistantMessage response = CodingAgentOperations.result(stream);
			assertEquals("hello world", CodingAgentOperations.text(response));
			assertEquals(StopReason.STOP, response.stopReason);
			assertEquals(4, response.usage.input);
			assertEquals(2, response.usage.output);
			assertTrue(requestBody.get().contains("\"stream\":true"));
			assertTrue(requestBody.get().contains("\"model\":\"test-model\""));
			assertTrue(requestBody.get().contains("\"system instructions\""));
			assertInstanceOf(AssistantMessageEvent.Start.class, events.getFirst());
			assertInstanceOf(AssistantMessageEvent.TextStart.class, events.get(1));
			assertInstanceOf(AssistantMessageEvent.TextDelta.class, events.get(2));
			assertInstanceOf(AssistantMessageEvent.Done.class, events.getLast());
		} finally {
			server.stop(0);
		}
	}

	@Test
	void assemblesStreamedToolCallArguments() throws Exception {
		HttpServer server = compatibleServer(exchange -> writeCompatibleSse(
				exchange,
				"""
				data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"read","arguments":"{\\"path\\":"}}]}}]}

				data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\"README.md\\"}"}}]},"finish_reason":"tool_calls"}]}

				data: [DONE]

				"""));
		try {
			Model model = compatibleModel(compatibleUrl(server));
			ProviderState provider = openAiCompatibleProvider("custom", "Custom", compatibleUrl(server), List.of(model));

			AssistantMessage result =
					CodingAgentOperations.result(CodingAgentOperations.INSTANCE.stream(provider, model, new Context(), compatibleOptions("test-key")));

			assertEquals(StopReason.TOOL_USE, result.stopReason);
			assertEquals("read", CodingAgentOperations.toolCalls(result).getFirst().name);
			assertEquals("README.md", CodingAgentOperations.toolCalls(result).getFirst().arguments.path("path").asText());
		} finally {
			server.stop(0);
		}
	}

	@Test
	void treatsBlankToolArgumentsAsAnEmptyObject() throws Exception {
		HttpServer server = compatibleServer(exchange -> writeCompatibleSse(
				exchange,
				"""
				data: {"choices":[{"delta":{"tool_calls":[{"index":1,"id":"toolu_1","function":{"name":"parameterless_tool","arguments":""}}]},"finish_reason":"tool_calls"}]}

				data: [DONE]

				"""));
		try {
			Model model = compatibleModel(compatibleUrl(server));
			ProviderState provider = openAiCompatibleProvider("custom", "Custom", compatibleUrl(server), List.of(model));

			AssistantMessage result =
					CodingAgentOperations.result(CodingAgentOperations.INSTANCE.stream(provider, model, new Context(), compatibleOptions("test-key")));

			assertEquals(StopReason.TOOL_USE, result.stopReason);
			assertEquals(1, CodingAgentOperations.toolCalls(result).size());
			assertEquals("toolu_1", CodingAgentOperations.toolCalls(result).getFirst().id);
			assertEquals("parameterless_tool", CodingAgentOperations.toolCalls(result).getFirst().name);
			assertTrue(CodingAgentOperations.toolCalls(result).getFirst().arguments.isEmpty());
		} finally {
			server.stop(0);
		}
	}

	@Test
	void ignoresCompletelyEmptyToolCallAfterAValidCall() throws Exception {
		HttpServer server = compatibleServer(exchange -> writeCompatibleSse(
				exchange,
				"""
				data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"read","arguments":"{\\"path\\":\\"README.md\\"}"}},{"index":1,"function":{"name":"","arguments":""}}]},"finish_reason":"tool_calls"}]}

				data: [DONE]

				"""));
		try {
			Model model = compatibleModel(compatibleUrl(server));
			ProviderState provider = openAiCompatibleProvider("custom", "Custom", compatibleUrl(server), List.of(model));

			AssistantMessageEventStream stream =
					CodingAgentOperations.INSTANCE.stream(provider, model, new Context(), compatibleOptions("test-key"));
			List<AssistantMessageEvent> events = new ArrayList<>();
			for (AssistantMessageEvent event : CodingAgentOperations.events(stream)) {
				events.add(event);
			}
			AssistantMessage result = CodingAgentOperations.result(stream);

			assertEquals(StopReason.TOOL_USE, result.stopReason);
			assertEquals(1, CodingAgentOperations.toolCalls(result).size());
			assertEquals("read", CodingAgentOperations.toolCalls(result).getFirst().name);
			assertEquals(
					1L,
					events.stream().filter(AssistantMessageEvent.ToolCallStart.class::isInstance).count());
		} finally {
			server.stop(0);
		}
	}

	@Test
	void rejectsArrayToolArgumentsAndPreservesRawStreamFragments() throws Exception {
		HttpServer server = compatibleServer(exchange -> writeCompatibleSse(
				exchange,
				"""
				data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"read","arguments":"{\\"path\\":\\"README.md\\"}"}},{"index":1,"id":"call_2","function":{"name":"broken_","arguments":"["}}]}}]}

				data: {"choices":[{"delta":{"tool_calls":[{"index":1,"function":{"name":"tool","arguments":"]"}}]},"finish_reason":"tool_calls"}]}

				data: [DONE]

				"""));
		try {
			Model model = compatibleModel(compatibleUrl(server));
			ProviderState provider = openAiCompatibleProvider("custom", "Custom", compatibleUrl(server), List.of(model));

			AssistantMessage result =
					CodingAgentOperations.result(CodingAgentOperations.INSTANCE.stream(provider, model, new Context(), compatibleOptions("test-key")));

			assertEquals(StopReason.ERROR, result.stopReason);
			assertTrue(result.errorMessage.startsWith("OpenAI tool call arguments must be a JSON object"));
			assertTrue(result.errorMessage.contains("\"index\":1"));
			assertTrue(result.errorMessage.contains("\"id\":\"call_2\""));
			assertTrue(result.errorMessage.contains("\"name\":\"broken_tool\""));
			assertTrue(result.errorMessage.contains("\"arguments\":\"[]\""));
			assertTrue(result.errorMessage.contains("\"name\":\"broken_\""));
			assertTrue(result.errorMessage.contains("\"arguments\":\"[\""));
			assertTrue(result.errorMessage.contains("\"name\":\"tool\""));
			assertTrue(result.errorMessage.contains("\"arguments\":\"]\""));
			assertEquals("read", CodingAgentOperations.toolCalls(result).getFirst().name);
		} finally {
			server.stop(0);
		}
	}

	private static Model compatibleModel(String baseUrl) {
		Model model = new Model();
		model.id = "test-model";
		model.name = "test-model";
		model.api = "openai-completions";
		model.provider = "custom";
		model.baseUrl = baseUrl;
		model.cost = ModelCost.FREE;
		model.contextWindow = 1000;
		model.maxTokens = 100;
		return model;
	}

	private static HttpServer compatibleServer(CompatibleExchangeHandler handler) throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/chat/completions", exchange -> {
			try {
				handler.handle(exchange);
			} catch (Exception e) {
				throw new java.io.IOException(e);
			} finally {
				exchange.close();
			}
		});
		server.start();
		return server;
	}

	private static String compatibleUrl(HttpServer server) {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	private static void writeCompatibleSse(HttpExchange exchange, String body) throws Exception {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("content-type", "text/event-stream");
		exchange.sendResponseHeaders(200, bytes.length);
		exchange.getResponseBody().write(bytes);
	}

	@FunctionalInterface
	private interface CompatibleExchangeHandler {
		void handle(HttpExchange exchange) throws Exception;
	}

	// OpenAiResponsesProvider

	private static StreamOptions responsesOptions(String apiKey) {
		StreamOptions options = new StreamOptions();
		options.apiKey = apiKey;
		return options;
	}

	private static StreamOptions sessionOptions(
			String apiKey, ThinkingLevel reasoning, String sessionId, int maxTokens) {
		StreamOptions options = responsesOptions(apiKey, reasoning);
		options.sessionId = sessionId;
		options.maxTokens = maxTokens;
		return options;
	}

	private static StreamOptions responsesOptions(String apiKey, ThinkingLevel reasoning) {
		StreamOptions options = responsesOptions(apiKey);
		options.reasoning = reasoning;
		return options;
	}
	@Test
	void usesSavedOpenAiApiKeyWhenNoRequestKeyIsSupplied() throws Exception {
		HttpServer server = responsesServer(exchange -> {
			assertEquals("Bearer saved-key", exchange.getRequestHeaders().getFirst("Authorization"));
			writeResponsesSse(
					exchange,
					"""
					data: {"type":"response.completed","response":{"id":"resp_1","model":"gpt-test","status":"completed","usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2}}}

					""");
		});
		try {

			CodingAgentOperations.INSTANCE.fileCredentialStore(tempDir.resolve("auth.json"), null);
			CodingAgentOperations credentials = CodingAgentOperations.INSTANCE;
					CodingAgentOperations.INSTANCE.modifyCredential(
					credentials,
					"openai",
					ignored -> new Credential.ApiKeyCredential("saved-key", Map.of()));
			Model model = responsesModel(responsesUrl(server));
			OpenAiResponsesProvider provider = new OpenAiResponsesProvider(
					"openai", "OpenAI", List.of(model), List.of("OPENAI_API_KEY"), credentials,
					OpenAiResponsesProvider.RequestProfile.STANDARD);
			Context context = new Context();
			context.messages.add(CodingAgentOperations.userMessage("hi"));

			AssistantMessage result = CodingAgentOperations.result(CodingAgentOperations.INSTANCE.stream(provider, model, context, new StreamOptions()));

			assertEquals(StopReason.STOP, result.stopReason);
		} finally {
			server.stop(0);
		}
	}

	@Test
	void streamsResponsesTextAndUsage() throws Exception {
		AtomicReference<String> request = new AtomicReference<>();
		HttpServer server = responsesServer(exchange -> {
			assertEquals("Bearer test-key", exchange.getRequestHeaders().getFirst("Authorization"));
			request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			writeResponsesSse(
					exchange,
					"""
					data: {"type":"response.created","response":{"id":"resp_1"}}

					data: {"type":"response.output_item.added","output_index":0,"item":{"id":"msg_1","type":"message"}}

					data: {"type":"response.output_text.delta","output_index":0,"delta":"hello "}

					data: {"type":"response.output_text.delta","output_index":0,"delta":"world"}

					data: {"type":"response.output_item.done","output_index":0,"item":{"id":"msg_1","type":"message"}}

					data: {"type":"response.completed","response":{"id":"resp_1","model":"gpt-test","status":"completed","usage":{"input_tokens":5,"output_tokens":2,"total_tokens":7,"input_tokens_details":{"cached_tokens":1}}}}

					""");
		});
		try {
			Model model = responsesModel(responsesUrl(server));
			OpenAiResponsesProvider provider = new OpenAiResponsesProvider(
					"openai", "OpenAI", List.of(model), List.of("OPENAI_API_KEY"), null,
					OpenAiResponsesProvider.RequestProfile.STANDARD);
			Context context = new Context("system");
			context.messages.add(CodingAgentOperations.userMessage("hi"));

			AssistantMessage result =
					CodingAgentOperations.result(CodingAgentOperations.INSTANCE.stream(provider, model, context, responsesOptions("test-key")));

			assertEquals("hello world", CodingAgentOperations.text(result));
			assertEquals("resp_1", result.responseId);
			assertEquals(StopReason.STOP, result.stopReason);
			assertEquals(4, result.usage.input);
			assertEquals(1, result.usage.cacheRead);
			assertEquals(2, result.usage.output);
			assertTrue(request.get().contains("\"instructions\":\"system\""));
			assertTrue(request.get().contains("\"input_text\""));
		} finally {
			server.stop(0);
		}
	}

	@Test
	void requestsAndStreamsReasoningSummaries() throws Exception {
		AtomicReference<String> request = new AtomicReference<>();
		HttpServer server = responsesServer(exchange -> {
			request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			writeResponsesSse(
					exchange,
					"""
					data: {"type":"response.output_item.added","output_index":0,"item":{"id":"rs_1","type":"reasoning"}}

					data: {"type":"response.reasoning_summary_text.delta","output_index":0,"delta":"Inspecting files"}

					data: {"type":"response.reasoning_summary_part.done","output_index":0}

					data: {"type":"response.reasoning_summary_text.delta","output_index":0,"delta":"Checking tests"}

					data: {"type":"response.output_item.done","output_index":0,"item":{"id":"rs_1","type":"reasoning","summary":[{"type":"summary_text","text":"Inspecting files"},{"type":"summary_text","text":"Checking tests"}]}}

					data: {"type":"response.completed","response":{"id":"resp_1","model":"gpt-test","status":"completed","output":[{"id":"rs_1","type":"reasoning","summary":[{"type":"summary_text","text":"Inspecting files"},{"type":"summary_text","text":"Checking tests"}],"encrypted_content":"opaque"}],"usage":{"input_tokens":3,"output_tokens":8,"total_tokens":11,"output_tokens_details":{"reasoning_tokens":6}}}}

					""");
		});
		try {
			Model model = responsesReasoningModel(responsesUrl(server));
			OpenAiResponsesProvider provider = new OpenAiResponsesProvider(
					"openai", "OpenAI", List.of(model), List.of("OPENAI_API_KEY"), null,
					OpenAiResponsesProvider.RequestProfile.STANDARD);
			Context context = new Context();
			context.messages.add(CodingAgentOperations.userMessage("inspect"));

			AssistantMessage result = CodingAgentOperations.result(CodingAgentOperations.INSTANCE.stream(provider,
					model, context, responsesOptions("test-key", ThinkingLevel.MEDIUM)));

			JsonNode body = Json.MAPPER.readTree(request.get());
			assertEquals("medium", body.path("reasoning").path("effort").asText());
			assertEquals("auto", body.path("reasoning").path("summary").asText());
			assertEquals("reasoning.encrypted_content", body.path("include").get(0).asText());
			assertFalse(body.path("store").asBoolean(true));
			assertEquals("Inspecting files\n\nChecking tests", CodingAgentOperations.thinking(result));
			assertTrue(((ThinkingContent) result.content.getFirst()).thinkingSignature.contains("opaque"));
			assertEquals(6, result.usage.reasoning);
		} finally {
			server.stop(0);
		}
	}

	@Test
	void usesCodexRequestContractForChatGptResponses() throws Exception {
		AtomicReference<String> request = new AtomicReference<>();
		HttpServer server = responsesServer(exchange -> {
			request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			writeResponsesSse(
					exchange,
					"""
					data: {"type":"response.completed","response":{"id":"resp_1","model":"gpt-test","status":"completed","usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2}}}

					""");
		});
		try {
			Model model = responsesReasoningModel(responsesUrl(server));
			OpenAiResponsesProvider provider = new OpenAiResponsesProvider(
					"chatgpt", "ChatGPT", List.of(model), List.of(), null,
					OpenAiResponsesProvider.RequestProfile.CODEX);
			Context context = new Context();
			context.messages.add(CodingAgentOperations.userMessage("inspect"));

			CodingAgentOperations.result(CodingAgentOperations.INSTANCE.stream(provider,
					model,
					context,
					sessionOptions("test-key", ThinkingLevel.MEDIUM, "session-1", 4_096)));

			JsonNode body = Json.MAPPER.readTree(request.get());
			assertEquals("You are a helpful assistant.", body.path("instructions").asText());
			assertEquals("low", body.path("text").path("verbosity").asText());
			assertEquals("auto", body.path("tool_choice").asText());
			assertTrue(body.path("parallel_tool_calls").asBoolean());
			assertEquals("session-1", body.path("prompt_cache_key").asText());
			assertEquals("detailed", body.path("reasoning").path("summary").asText());
			assertFalse(body.has("max_output_tokens"));
		} finally {
			server.stop(0);
		}
	}

	@Test
	void serializesPriorToolCallsAsTopLevelResponsesItems() throws Exception {
		AtomicReference<String> request = new AtomicReference<>();
		HttpServer server = responsesServer(exchange -> {
			request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			writeResponsesSse(
					exchange,
					"""
					data: {"type":"response.completed","response":{"id":"resp_1","model":"gpt-test","status":"completed","usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2}}}

					""");
		});
		try {
			Model model = responsesModel(responsesUrl(server));
			OpenAiResponsesProvider provider = new OpenAiResponsesProvider(
					"openai", "OpenAI", List.of(model), List.of("OPENAI_API_KEY"), null,
					OpenAiResponsesProvider.RequestProfile.STANDARD);
			Context context = new Context();
			AssistantMessage assistant = new AssistantMessage(model.api, model.provider, model.id);
			assistant.content.add(new ThinkingContent(
					"inspected files",
					"{\"type\":\"reasoning\",\"id\":\"rs_1\",\"summary\":[],\"encrypted_content\":\"opaque\"}",
					false));
			assistant.content.add(new ToolCall(
					"call_1", "list_files", CodingAgentOperations.jsonObject().put("path", "."), null));
			context.messages.add(assistant);
			context.messages.add(new ToolResultMessage(
					"call_1",
					"list_files",
					List.of(new TextContent("file.txt", null)),
					null,
					false,
					System.currentTimeMillis()));

			CodingAgentOperations.result(CodingAgentOperations.INSTANCE.stream(provider, model, context, responsesOptions("test-key")));

			JsonNode input = Json.MAPPER.readTree(request.get()).path("input");
			assertEquals("reasoning", input.get(0).path("type").asText());
			assertEquals("opaque", input.get(0).path("encrypted_content").asText());
			assertEquals("function_call", input.get(1).path("type").asText());
			assertEquals("call_1", input.get(1).path("call_id").asText());
			assertEquals("function_call_output", input.get(2).path("type").asText());
			assertEquals("call_1", input.get(2).path("call_id").asText());
			assertTrue(!input.get(1).has("content"));
		} finally {
			server.stop(0);
		}
	}

	private static Model responsesModel(String baseUrl) {
		Model model = new Model();
		model.id = "gpt-test";
		model.name = "gpt-test";
		model.api = "openai-responses";
		model.provider = "openai";
		model.baseUrl = baseUrl;
		model.cost = ModelCost.FREE;
		model.contextWindow = 1000;
		model.maxTokens = 100;
		return model;
	}

	private static Model responsesReasoningModel(String baseUrl) {
		Model model = responsesModel(baseUrl);
		model.reasoning = true;
		return model;
	}

	private static HttpServer responsesServer(ResponsesExchangeHandler handler) throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/responses", exchange -> {
			try {
				handler.handle(exchange);
			} catch (Exception e) {
				throw new java.io.IOException(e);
			} finally {
				exchange.close();
			}
		});
		server.start();
		return server;
	}

	private static String responsesUrl(HttpServer server) {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	private static void writeResponsesSse(HttpExchange exchange, String body) throws Exception {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("content-type", "text/event-stream");
		exchange.sendResponseHeaders(200, bytes.length);
		exchange.getResponseBody().write(bytes);
	}

	@FunctionalInterface
	private interface ResponsesExchangeHandler {
		void handle(HttpExchange exchange) throws Exception;
	}

	// EventStream

	@Test
	void deliversQueuedEventsThenTerminates() throws Exception {
		AssistantMessageEventStream stream = new AssistantMessageEventStream();
		AssistantMessage partial = new AssistantMessage("test-api", "test-provider", "test-model");

		CodingAgentOperations.push(stream, new AssistantMessageEvent.Start(partial));
		partial.content.add(new TextContent("hello", null));
		CodingAgentOperations.push(stream, new AssistantMessageEvent.TextDelta(0, "hello", partial));
		partial.stopReason = StopReason.STOP;
		CodingAgentOperations.push(stream, new AssistantMessageEvent.Done(StopReason.STOP, partial));

		List<AssistantMessageEvent> events = new ArrayList<>();
		for (AssistantMessageEvent event : CodingAgentOperations.events(stream)) {
			events.add(event);
		}
		assertEquals(3, events.size());
		assertInstanceOf(AssistantMessageEvent.Start.class, events.get(0));
		assertInstanceOf(AssistantMessageEvent.TextDelta.class, events.get(1));
		assertInstanceOf(AssistantMessageEvent.Done.class, events.get(2));
		assertSame(partial, CodingAgentOperations.result(stream));
	}

	@Test
	void blocksUntilProducerPushes() throws Exception {
		AssistantMessageEventStream stream = new AssistantMessageEventStream();
		AssistantMessage message = new AssistantMessage("a", "p", "m");
		Thread producer = Thread.ofVirtual().start(() -> {
			try {
				Thread.sleep(50);
			} catch (InterruptedException ignored) {
			}
			CodingAgentOperations.push(stream, new AssistantMessageEvent.Start(message));
			CodingAgentOperations.push(stream, new AssistantMessageEvent.Done(StopReason.STOP, message));
		});

		int count = 0;
		for (AssistantMessageEvent ignored : CodingAgentOperations.events(stream)) {
			count++;
		}
		producer.join();
		assertEquals(2, count);
	}

	@Test
	void ignoresPushAfterTerminal() {
		AssistantMessageEventStream stream = new AssistantMessageEventStream();
		AssistantMessage message = new AssistantMessage("a", "p", "m");
		CodingAgentOperations.push(stream, new AssistantMessageEvent.Done(StopReason.STOP, message));
		CodingAgentOperations.push(stream, new AssistantMessageEvent.Start(message));

		Iterator<AssistantMessageEvent> it = CodingAgentOperations.iterator(stream);
		assertTrue(it.hasNext());
		it.next();
		assertFalse(it.hasNext());
	}

	@Test
	void errorEventYieldsErrorMessageAsResult() throws Exception {
		AssistantMessageEventStream stream = new AssistantMessageEventStream();
		AssistantMessage error = new AssistantMessage("a", "p", "m");
		error.stopReason = StopReason.ERROR;
		error.errorMessage = "boom";
		CodingAgentOperations.push(stream, new AssistantMessageEvent.Error(StopReason.ERROR, error));
		assertSame(error, CodingAgentOperations.result(stream));
		assertEquals("boom", CodingAgentOperations.result(stream).errorMessage);
	}

	// Uuid

	@Test
	void generatesValidV7Format() {
		String id = CodingAgentOperations.uuidv7();
		assertTrue(id.matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"), id);
	}

	@Test
	void generatesUniqueMonotonicIds() {
		Set<String> seen = new HashSet<>();
		String previous = "";
		for (int i = 0; i < 10_000; i++) {
			String id = CodingAgentOperations.uuidv7();
			assertTrue(seen.add(id), "duplicate: " + id);
			assertTrue(id.compareTo(previous) > 0, "not monotonic: " + previous + " -> " + id);
			previous = id;
		}
		assertEquals(10_000, seen.size());
	}

	// SessionRecorder

	@Test
	void recordsACompleteAgentTranscript() throws Exception {
		CodingAgentOperations store = sessionStore(tempDir.resolve("sessions"));
		store.createSessionRecorder(tempDir, "faux", "faux-1");
		CodingAgentOperations recorder = CodingAgentOperations.INSTANCE;
		AssistantMessage assistant = new AssistantMessage("faux", "faux", "faux-1");
		assistant.content.add(new TextContent("I will use a tool.", null));
		assistant.stopReason = StopReason.TOOL_USE;
		recorder.appendSessionMessages(List.of(
				CodingAgentOperations.userMessage("read file"),
				assistant,
				new ToolResultMessage(
						"call-1",
						"read",
						List.of(new TextContent("file contents", null)),
						null,
						false,
						System.currentTimeMillis())));

		var entries = store.readSession(recorder.state().sessionId());
		assertEquals(4, entries.size());
		assertEquals("session_start", entries.getFirst().type);
		assertEquals("user", entries.get(1).payload.path("role").asText());
		assertEquals("assistant", entries.get(2).payload.path("role").asText());
		assertTrue(entries.get(2).payload.path("content").get(0).path("text").asText().contains("tool"));
		assertEquals("read", entries.getLast().payload.path("toolName").asText());
	}

	@Test
	void forksTheTranscriptIntoANamedSession() throws Exception {
		CodingAgentOperations store=CodingAgentOperations.INSTANCE;
		CodingAgentOperations source=CodingAgentOperations.INSTANCE;
		sessionStore(tempDir.resolve("sessions"));
		store.createSessionRecorder(tempDir, "faux", "faux-1");
		String sourceSessionId = source.state().sessionId();
		List<Message> messages = List.of(CodingAgentOperations.userMessage("first prompt"), CodingAgentOperations.userMessage("second prompt"));
		source.appendSessionMessages(messages);

		store.forkSessionRecorder(tempDir, "faux", "faux-1", "  investigation fork  ", messages);
		CodingAgentOperations fork = CodingAgentOperations.INSTANCE;
		SessionSnapshot snapshot = store.sessionSnapshot(fork.state().sessionId());

		assertNotEquals(sourceSessionId, fork.state().sessionId());
		assertEquals("investigation fork", snapshot.name);
		assertEquals(2, snapshot.messageCount);
		assertEquals("first prompt", snapshot.firstMessage);
		assertEquals(2, store.sessionSnapshot(sourceSessionId).messageCount);
	}

	@Test
	void restoresCompactedSessionsUsingOnlyTheCheckpointAndLaterMessagesAsContext() throws Exception {
		CodingAgentOperations store = sessionStore(tempDir.resolve("sessions"));
		store.createSessionRecorder(tempDir, "faux", "faux-1");
		CodingAgentOperations recorder = CodingAgentOperations.INSTANCE;
		recorder.appendSessionMessages(List.of(
				CodingAgentOperations.userMessage("PRE-COMPACTION-SENTINEL"),
				CodingAgentOperations.userMessage("another message to compact")));
		recorder.appendSessionCompaction(new CompactionResult("Saved checkpoint.", 123, 12));
		recorder.appendSessionMessages(List.of(CodingAgentOperations.userMessage("POST-COMPACTION-SENTINEL")));

		SessionSnapshot snapshot = store.sessionSnapshot(recorder.state().sessionId());

		// The append-only transcript remains available to render or inspect.
		assertEquals(3, snapshot.messageCount);
		assertEquals(3, snapshot.transcriptMessages.size());
		assertEquals("PRE-COMPACTION-SENTINEL", CodingAgentOperations.text(((UserMessage) snapshot.transcriptMessages.getFirst())));
		assertEquals("compaction", store.readSession(recorder.state().sessionId()).get(3).type);

		// Resuming must use the compaction-aware projection, not the old transcript.
		assertEquals(2, snapshot.messages.size());
		UserMessage checkpoint = (UserMessage) snapshot.messages.getFirst();
		assertEquals("[Conversation checkpoint]\nSaved checkpoint.", CodingAgentOperations.text(checkpoint));
		UserMessage later = (UserMessage) snapshot.messages.getLast();
		assertEquals("POST-COMPACTION-SENTINEL", CodingAgentOperations.text(later));
		assertTrue(snapshot.messages.stream()
				.noneMatch(message -> message instanceof UserMessage user
						&& CodingAgentOperations.text(user).contains("PRE-COMPACTION-SENTINEL")));
	}

	@Test
	void usesTheLatestCompactionBoundaryWhenASessionIsCompactedAgain() throws Exception {
		CodingAgentOperations store = sessionStore(tempDir.resolve("sessions"));
		store.createSessionRecorder(tempDir, "faux", "faux-1");
		CodingAgentOperations recorder = CodingAgentOperations.INSTANCE;
		recorder.appendSessionMessages(List.of(CodingAgentOperations.userMessage("first history")));
		recorder.appendSessionCompaction(new CompactionResult("first checkpoint", 100, 10));
		recorder.appendSessionMessages(List.of(CodingAgentOperations.userMessage("between compactions")));
		recorder.appendSessionCompaction(new CompactionResult("second checkpoint", 100, 10));
		recorder.appendSessionMessages(List.of(CodingAgentOperations.userMessage("after latest compaction")));

		SessionSnapshot snapshot = store.sessionSnapshot(recorder.state().sessionId());

		assertEquals(2, snapshot.messages.size());
		assertEquals("[Conversation checkpoint]\nsecond checkpoint", CodingAgentOperations.text(((UserMessage) snapshot.messages.getFirst())));
		assertEquals("after latest compaction", CodingAgentOperations.text(((UserMessage) snapshot.messages.getLast())));
	}

	@Test
	void restoresTypedMessagesAndContinuesTheSameSession() throws Exception {
		CodingAgentOperations store = sessionStore(tempDir.resolve("sessions"));
		store.createSessionRecorder(tempDir, "faux", "faux-1");
		CodingAgentOperations recorder = CodingAgentOperations.INSTANCE;
		AssistantMessage assistant = new AssistantMessage("faux-api", "faux", "faux-1");
		assistant.content.add(new ThinkingContent("reasoning", "opaque", false));
		assistant.content.add(new TextContent("answer", "text-signature"));
		assistant.content.add(new ToolCall(
				"call-1", "read", CodingAgentOperations.jsonObject().put("path", "README.md"), "thought"));
		assistant.stopReason = StopReason.TOOL_USE;
		assistant.usage.input = 12;
		assistant.usage.output = 7;
		UserMessage user = new UserMessage(
				List.of(new TextContent("look", null), new ImageContent("aW1hZ2U=", "image/png")), 1234);
		ToolResultMessage result = new ToolResultMessage(
				"call-1",
				"read",
				List.of(new TextContent("contents", null)),
				Map.of("path", "README.md"),
				false,
				5678);
		recorder.appendSessionMessages(List.of(user, assistant, result));

		SessionSnapshot snapshot = store.sessionSnapshot(recorder.state().sessionId());
		assertEquals(tempDir.toAbsolutePath().normalize(), snapshot.cwd);
		assertEquals("faux", snapshot.provider);
		assertEquals("faux-1", snapshot.model);
		assertEquals(3, snapshot.messageCount);
		assertEquals("look", snapshot.firstMessage);
		assertEquals(1234, CodingAgentOperations.timestamp(snapshot.messages.getFirst()));
		AssistantMessage restored = (AssistantMessage) snapshot.messages.get(1);
		assertEquals("reasoning", CodingAgentOperations.thinking(restored));
		assertEquals("answer", CodingAgentOperations.text(restored));
		assertEquals("read", CodingAgentOperations.toolCalls(restored).getFirst().name);
		assertEquals(12, restored.usage.input);
		ToolResultMessage restoredResult = (ToolResultMessage) snapshot.messages.getLast();
		assertEquals("README.md", ((Map<?, ?>) restoredResult.details).get("path"));

		store.resumeSessionRecorder(recorder.state().sessionId());
		CodingAgentOperations.INSTANCE.appendSessionMessages(List.of(CodingAgentOperations.userMessage("continue")));
		assertEquals(4, store.sessionSnapshot(recorder.state().sessionId()).messageCount);
	}

	private static CodingAgentOperations sessionStore(Path directory) {
		CodingAgentOperations.INSTANCE.sessionStore(directory, List.of());
		return CodingAgentOperations.INSTANCE;
	}

	// BuiltInTools

	@Test
	void readsWritesAndEditsFiles() throws Exception {
		List<AgentTool> tools = builtInTools("git", tempDir);
		ObjectNode write = CodingAgentOperations.jsonObject().put("path", "nested/example.txt").put("content", "before\nsecond\n");
		assertTrue(run(tools, "write", write).contains("Successfully wrote"));

		ObjectNode edit = CodingAgentOperations.jsonObject().put("path", "nested/example.txt");
		edit.putArray("edits").addObject().put("oldText", "before").put("newText", "after");
		assertTrue(run(tools, "edit", edit).contains("Successfully replaced"));

		ObjectNode read = CodingAgentOperations.jsonObject().put("path", "nested/example.txt").put("offset", 1).put("limit", 1);
		assertEquals("after\n\n[1 more lines. Use offset=2 to continue.]", run(tools, "read", read));
		assertEquals("after\nsecond\n", Files.readString(tempDir.resolve("nested/example.txt")));
	}

	@Test
	void rejectsAmbiguousEdits() throws Exception {
		Files.writeString(tempDir.resolve("example.txt"), "duplicate duplicate");
		ObjectNode edit = CodingAgentOperations.jsonObject().put("path", "example.txt");
		edit.putArray("edits").addObject().put("oldText", "duplicate").put("newText", "changed");
		assertThrows(IllegalArgumentException.class, () -> CodingAgentOperations.INSTANCE.executeTool(
				tool(builtInTools("git", tempDir), "edit"), "id", edit, new AbortSignal(), ignored -> {}));
	}

	@Test
	void findsGrepsListsAndExecutesCommands() throws Exception {
		Files.createDirectories(tempDir.resolve("src"));
		Files.writeString(tempDir.resolve("src/example.java"), "class Example {\n  String value = \"needle\";\n}\n");
		Files.writeString(tempDir.resolve("README.md"), "documentation");

		List<AgentTool> tools = builtInTools("git", tempDir);
		assertEquals("src/example.java", run(tools, "find", CodingAgentOperations.jsonObject().put("pattern", "**/*.java")));
		assertTrue(run(tools, "grep", CodingAgentOperations.jsonObject().put("pattern", "needle")).contains("src/example.java:2:"));
		String listing = run(tools, "ls", CodingAgentOperations.jsonObject());
		assertTrue(listing.contains("README.md"));
		assertTrue(listing.contains("src/"));
		assertEquals("ok", run(tools, "shell", CodingAgentOperations.jsonObject().put("command", shellCommandThatPrintsOk())));
	}

	@Test
	void grepFiltersFilesWithAGlobSeparateFromItsLiteralPath() throws Exception {
		Files.createDirectories(tempDir.resolve("src/nested"));
		Files.writeString(tempDir.resolve("src/Root.java"), "needle");
		Files.writeString(tempDir.resolve("src/nested/Nested.java"), "needle");
		Files.writeString(tempDir.resolve("src/nested/notes.txt"), "needle");

		List<AgentTool> tools = builtInTools("git", tempDir);
		String byFileName = run(
				tools,
				"grep",
				CodingAgentOperations.jsonObject().put("pattern", "needle").put("path", "src").put("glob", "*.java"));
		assertTrue(byFileName.contains("Root.java:1:"));
		assertTrue(byFileName.contains("nested/Nested.java:1:"));
		assertFalse(byFileName.contains("notes.txt"));

		String recursive = run(
				tools,
				"grep",
				CodingAgentOperations.jsonObject().put("pattern", "needle").put("path", "src").put("glob", "**/*.java"));
		assertTrue(recursive.contains("Root.java:1:"));
		assertTrue(recursive.contains("nested/Nested.java:1:"));
	}

	@Test
	void grepRejectsWildcardsInLiteralPathWithActionableError() {
		ObjectNode arguments = CodingAgentOperations.jsonObject().put("pattern", "needle").put("path", "src/**/*.java");

		IllegalArgumentException error = assertThrows(
				IllegalArgumentException.class,
				() -> CodingAgentOperations.INSTANCE.executeTool(
						tool(builtInTools("git", tempDir), "grep"),
						"id",
						arguments,
						new AbortSignal(),
						ignored -> {}));

		assertTrue(error.getMessage().contains("path is literal"));
		assertTrue(error.getMessage().contains("glob"));
	}

	@Test
	void expandsHomePathsAndLeavesDotSlashTildeLiteral() throws Exception {
		Path home = tempDir.resolve("home");
		Path cwd = tempDir.resolve("cwd");
		Files.createDirectories(home);
		Files.createDirectories(cwd);
		Files.writeString(home.resolve("from-home.txt"), "home content");
		Files.writeString(cwd.resolve("~"), "literal tilde");
		String originalHome = System.getProperty("user.home");
		try {
			System.setProperty("user.home", home.toString());
			List<AgentTool> tools = builtInTools("git", cwd);
			assertEquals("home content", run(tools, "read", CodingAgentOperations.jsonObject().put("path", "~/from-home.txt")));
			assertTrue(run(tools, "ls", CodingAgentOperations.jsonObject().put("path", "~")).contains("from-home.txt"));
			assertEquals("literal tilde", run(tools, "read", CodingAgentOperations.jsonObject().put("path", "./~")));

			IllegalArgumentException error = assertThrows(
					IllegalArgumentException.class,
					() -> run(tools, "read", CodingAgentOperations.jsonObject().put("path", "~someuser/file.txt")));
			assertEquals("~user paths are not supported; use an absolute path", error.getMessage());
		} finally {
			if (originalHome == null) System.clearProperty("user.home");
			else System.setProperty("user.home", originalHome);
		}
	}

	@Test
	void grepExplainsWhetherTheGlobSelectedFiles() throws Exception {
		Files.createDirectories(tempDir.resolve("bases/example/src"));
		Files.writeString(tempDir.resolve("bases/example/src/core.clj"), "(ns example.core)");
		Files.writeString(tempDir.resolve("README.md"), "documentation");
		List<AgentTool> tools = builtInTools("git", tempDir);

		String wrongPrefix = run(
				tools,
				"grep",
				CodingAgentOperations.jsonObject().put("pattern", "missing").put("glob", "src/**/*.clj"));
		assertTrue(wrongPrefix.contains("No files matched glob 'src/**/*.clj'"));
		assertTrue(wrongPrefix.contains("2 files under"));
		assertTrue(wrongPrefix.contains("relative to path"));

		String noContentMatch = run(
				tools,
				"grep",
				CodingAgentOperations.jsonObject().put("pattern", "missing").put("path", "bases").put("glob", "**/*.clj"));
		assertEquals("No matches found in 1 files matching glob '**/*.clj'", noContentMatch);
		assertEquals(
				"No matches found in 2 files",
				run(tools, "grep", CodingAgentOperations.jsonObject().put("pattern", "missing")));
	}

	@Test
	void readsAndListsArchiveEntriesAndRejectsArchiveEdits() throws Exception {
		Path archive = tempDir.resolve("sample.jar");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
			addZipEntry(zip, "pkg/a.clj", "first\nsecond\nthird");
			addZipEntry(zip, "pkg/b.txt", "other");
		}
		List<AgentTool> tools = builtInTools("git", tempDir);
		String archivePath = archive + "!";

		assertEquals("first\nsecond\nthird", run(
				tools, "read", CodingAgentOperations.jsonObject().put("path", archivePath + "pkg/a.clj")));
		assertEquals(
				"second\n\n[1 more lines. Use offset=3 to continue.]",
				run(tools, "read", CodingAgentOperations.jsonObject().put("path", archivePath + "pkg/a.clj").put("offset", 2).put("limit", 1)));
		assertTrue(run(tools, "read", CodingAgentOperations.jsonObject().put("path", archivePath)).contains("pkg/"));
		String packageListing = run(tools, "read", CodingAgentOperations.jsonObject().put("path", archivePath + "pkg/"));
		assertTrue(packageListing.contains("a.clj (18 bytes)"));
		assertTrue(packageListing.contains("b.txt (5 bytes)"));

		IOException missing = assertThrows(
				IOException.class,
				() -> run(tools, "read", CodingAgentOperations.jsonObject().put("path", archivePath + "pkg/missing.clj")));
		assertTrue(missing.getMessage().contains("pkg/a.clj"));
		assertTrue(missing.getMessage().contains("pkg/b.txt"));

		ObjectNode edit = CodingAgentOperations.jsonObject().put("path", archivePath + "pkg/a.clj");
		edit.putArray("edits").addObject().put("oldText", "first").put("newText", "changed");
		IllegalArgumentException readOnly = assertThrows(
				IllegalArgumentException.class, () -> run(tools, "edit", edit));
		assertEquals("archives are read-only through this tool", readOnly.getMessage());
		IllegalArgumentException writeReadOnly = assertThrows(
				IllegalArgumentException.class,
				() -> run(tools, "write", CodingAgentOperations.jsonObject().put("path", archivePath + "new.txt").put("content", "new")));
		assertEquals("archives are read-only through this tool", writeReadOnly.getMessage());

		Path notZip = tempDir.resolve("not-zip.bin");
		Files.writeString(notZip, "not an archive");
		IOException badArchive = assertThrows(
				IOException.class,
				() -> run(tools, "read", CodingAgentOperations.jsonObject().put("path", notZip + "!entry.txt")));
		assertTrue(badArchive.getMessage().contains(notZip.toString()));

		Path bangFile = tempDir.resolve("plain!name.txt");
		Files.writeString(bangFile, "plain bang file");
		assertEquals("plain bang file", run(tools, "read", CodingAgentOperations.jsonObject().put("path", bangFile.toString())));
	}

	@Test
	void grepAndFindSkipGitIgnoredFilesUnlessRequested() throws Exception {
		initializeGitRepository(tempDir);
		Files.writeString(tempDir.resolve(".gitignore"), "logs/\n");
		Files.createDirectories(tempDir.resolve("logs"));
		Files.writeString(tempDir.resolve("logs/ignored.log"), "secret needle");
		List<AgentTool> tools = builtInTools("git", tempDir);

		assertFalse(run(tools, "grep", CodingAgentOperations.jsonObject().put("pattern", "needle")).contains("ignored.log"));
		assertTrue(run(
				tools,
				"grep",
				CodingAgentOperations.jsonObject().put("pattern", "needle").put("includeIgnored", true)).contains("logs/ignored.log"));
		assertEquals("No files found matching pattern", run(
				tools, "find", CodingAgentOperations.jsonObject().put("pattern", "**/*.log")));
		assertEquals("logs/ignored.log", run(
				tools,
				"find",
				CodingAgentOperations.jsonObject().put("pattern", "**/*.log").put("includeIgnored", true)));
	}

	@Test
	void gitIgnoreFilteringFallsBackWhenGitIsUnavailable() throws Exception {
		initializeGitRepository(tempDir);
		Files.writeString(tempDir.resolve(".gitignore"), "ignored.txt\n");
		Files.writeString(tempDir.resolve("ignored.txt"), "fallback needle");
		List<AgentTool> tools = builtInTools(tempDir.resolve("missing-git-executable").toString(), tempDir);

		assertTrue(run(tools, "grep", CodingAgentOperations.jsonObject().put("pattern", "needle")).contains("ignored.txt"));
	}

	@Test
	void schemasDescribeRequiredInputs() {
		List<AgentTool> tools = builtInTools("git", tempDir);
		ObjectNode write = CodingAgentOperations.toolParameters(tool(tools, "write"));
		assertTrue(write.path("required").toString().contains("\"path\""));
		assertTrue(write.path("required").toString().contains("\"content\""));
		assertFalse(CodingAgentOperations.toolParameters(tool(tools, "ls"))
				.path("required").toString().contains("\"path\""));

		ObjectNode grep = CodingAgentOperations.toolParameters(tool(tools, "grep"));
		assertTrue(grep.path("properties").has("path"));
		assertTrue(grep.path("properties").has("glob"));
		assertTrue(grep.path("properties").has("includeIgnored"));
		assertTrue(grep.path("properties").path("path").path("description").asText().contains("Literal"));
		for (String name : List.of("read", "write", "edit", "grep", "find", "ls")) {
			assertTrue(CodingAgentOperations.toolParameters(tool(tools, name)).path("properties").path("path")
					.path("description").asText().contains("leading ~/"));
		}
		assertTrue(CodingAgentOperations.toolDescription(tool(tools, "read")).contains("archive.jar!"));
		assertTrue(CodingAgentOperations.toolDescription(tool(tools, "grep")).contains("code-lens"));
	}

	private static List<AgentTool> builtInTools(String gitExecutable, Path cwd) {
		CodingAgentOperations.INSTANCE.executable=gitExecutable;
		return CodingAgentOperations.INSTANCE.builtInTools(cwd, ignored -> {});
	}

	private static void addZipEntry(ZipOutputStream zip, String name, String content) throws IOException {
		zip.putNextEntry(new ZipEntry(name));
		zip.write(content.getBytes(StandardCharsets.UTF_8));
		zip.closeEntry();
	}

	private static void initializeGitRepository(Path directory) throws Exception {
		Process process = new ProcessBuilder("git", "init", "-q")
				.directory(directory.toFile())
				.redirectErrorStream(true)
				.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertEquals(0, process.waitFor(), output);
	}

	private static AgentTool tool(List<AgentTool> tools, String name) {
		return tools.stream()
				.filter(tool -> CodingAgentOperations.toolName(tool).equals(name))
				.findFirst()
				.orElseThrow();
	}

	private static String run(List<AgentTool> tools, String name, ObjectNode arguments) throws Exception {
		AgentTool.ToolResult result = CodingAgentOperations.INSTANCE.executeTool(
				tool(tools, name), "id", arguments, new AbortSignal(), ignored -> {});
		return ((TextContent) result.content.getFirst()).text;
	}

	private static String shellCommandThatPrintsOk() {
		return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win") ? "[Console]::Write('ok')" : "printf ok";
	}

	// McpConfigLoader

	@Test
	void loadsMcpServersFromCodingAgentSettings() throws Exception {
		Path settingsDirectory = tempDir.resolve("home/.codingagent");
		Path settingsPath = settingsDirectory.resolve("settings.json");
		Files.createDirectories(settingsDirectory);
		Files.writeString(settingsDirectory.resolve("secret.txt"), "secret-value\n");
		Files.writeString(settingsPath, """
				{
				  "defaultModel": "some-model",
				  "mcp": {
				    "local": {
				      "type": "local",
				      "command": ["tool", "--token", "{env:TOOL_TOKEN}"],
				      "environment": { "SECRET": "{file:secret.txt}" },
				      "enabled": false
				    },
				    "remote": {
				      "type": "remote",
				      "url": "https://example.test/mcp",
				      "headers": { "Authorization": "Bearer {env:TOOL_TOKEN}" },
				      "oauth": {
				        "clientId": "configured-client",
				        "scope": "read write",
				        "callbackPort": 23456
				      },
				      "resultFilters": [
				        { "tool": "get*", "dropKeys": ["avatarUrls", "self"] }
				      ],
				      "disabledTools": ["deleteIssue"]
				    }
				  }
				}
				""");

		McpConfiguration config = CodingAgentOperations.mcpLoadConfiguration(
				new McpConfigLoader(
						settingsPath.toAbsolutePath().normalize(), Map.of("TOOL_TOKEN", "abc123")));

		assertEquals(2, config.servers.size());
		McpServerConfig.Local local = assertInstanceOf(McpServerConfig.Local.class, config.servers.get("local"));
		assertEquals(java.util.List.of("tool", "--token", "abc123"), local.command);
		assertEquals("secret-value", local.environment.get("SECRET"));
		assertFalse(local.enabled);
		McpServerConfig.Remote remote = assertInstanceOf(McpServerConfig.Remote.class, config.servers.get("remote"));
		assertEquals("Bearer abc123", remote.headers.get("Authorization"));
		assertEquals("configured-client", remote.oauth.path("clientId").asText());
		assertEquals("read write", remote.oauth.path("scope").asText());
		assertEquals(23456, remote.oauth.path("callbackPort").asInt());
		assertEquals("get*", remote.resultFilters.getFirst().tool);
		assertEquals(java.util.List.of("avatarUrls", "self"), remote.resultFilters.getFirst().dropKeys);
		assertEquals(java.util.List.of("deleteIssue"), remote.disabledTools);
		assertEquals(java.util.List.of(settingsPath), config.sources);
	}

	@Test
	void rejectsInvalidDisabledTools() throws Exception {
		Path settingsPath = tempDir.resolve("home/.codingagent/settings.json");
		Files.createDirectories(settingsPath.getParent());
		Files.writeString(settingsPath, """
				{"mcp":{"local":{"type":"local","command":["tool"],"disabledTools":[42]}}}
				""");

		Exception error = assertThrows(
				java.io.IOException.class,
			() -> CodingAgentOperations.mcpLoadConfiguration(
					new McpConfigLoader(settingsPath.toAbsolutePath().normalize(), Map.of())));
		assertTrue(error.getMessage().contains("disabledTools"));
	}

	@Test
	void rejectsANonLoopbackOAuthRedirect() throws Exception {
		Path settingsPath = tempDir.resolve("home/.codingagent/settings.json");
		Files.createDirectories(settingsPath.getParent());
		Files.writeString(settingsPath, """
				{"mcp":{"remote":{"type":"remote","url":"https://example.test/mcp",
				"oauth":{"redirectUri":"https://attacker.test/callback"}}}}
				""");

		Exception error = assertThrows(
				java.io.IOException.class,
			() -> CodingAgentOperations.mcpLoadConfiguration(
					new McpConfigLoader(settingsPath.toAbsolutePath().normalize(), Map.of())));
		assertTrue(error.getMessage().contains("HTTP loopback URL"));
	}

	@Test
	void doesNotDiscoverOpenCodeOrProjectConfiguration() throws Exception {
		Path settingsPath = tempDir.resolve("home/.codingagent/settings.json");
		Path projectConfig = tempDir.resolve("workspace/.opencode/opencode.json");
		Files.createDirectories(projectConfig.getParent());
		Files.writeString(projectConfig, """
				{"mcp":{"ignored":{"type":"local","command":["ignored"]}}}
				""");

		McpConfiguration config = CodingAgentOperations.mcpLoadConfiguration(
				new McpConfigLoader(settingsPath.toAbsolutePath().normalize(), Map.of()));

		assertTrue(config.servers.isEmpty());
		assertTrue(config.sources.isEmpty());
	}

	// McpRemote

	@Test
	void connectsToAStreamableHttpServer() throws Exception {
		AtomicBoolean sawSession = new AtomicBoolean();
		AtomicBoolean sawProtocol = new AtomicBoolean();
		HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		http.createContext("/mcp", exchange -> handle(exchange, sawSession, sawProtocol));
		http.start();
		try {
			var remote = new McpServerConfig.Remote(
					java.net.URI.create("http://127.0.0.1:" + http.getAddress().getPort() + "/mcp"),
					Map.of("X-Test", "yes"),
					null,
					true,
					5_000L,
					List.of(),
					List.of());
			CodingAgentOperations.INSTANCE.mcpCreateManager(
					new McpConfiguration(Map.of("remote", remote), List.of()), tempDir);
			CodingAgentOperations manager = CodingAgentOperations.INSTANCE;
			try {
				manager.mcpAwaitReady();
				assertEquals(CodingAgentOperations.McpState.CONNECTED, manager.mcpStatus("remote").state);
				AgentTool tool = manager.mcpTools().getFirst();
				AgentTool.ToolResult result = CodingAgentOperations.INSTANCE.executeTool(
						tool, "id", CodingAgentOperations.jsonObject().put("value", "over http"), new AbortSignal(), ignored -> {});
				assertEquals("over http", ((TextContent) result.content.getFirst()).text);
				assertEquals("remote", manager.mcpStatuses().getFirst().name);
				assertTrue(manager.mcpToolStatuses("remote").getFirst().enabled);
				assertFalse(manager.toggleMcpTool("remote", "echo").enabled);
				assertTrue(manager.mcpTools().isEmpty());
				assertTrue(manager.toggleMcpTool("remote", "echo").enabled);
				assertEquals(1, manager.mcpTools().size());
				assertFalse(manager.toggleMcpServer("remote"));
				assertEquals(CodingAgentOperations.McpState.DISABLED, manager.mcpStatus("remote").state);
				assertTrue(manager.mcpTools().isEmpty());
				assertTrue(manager.toggleMcpServer("remote"));
				manager.mcpAwaitReady();
				assertEquals(CodingAgentOperations.McpState.CONNECTED, manager.mcpStatus("remote").state);
				assertEquals(1, manager.mcpTools().size());
			} finally {
				manager.mcpCloseManager();
			}
			assertTrue(sawSession.get());
			assertTrue(sawProtocol.get());
		} finally {
			http.stop(0);
		}
	}

	private static void handle(HttpExchange exchange, AtomicBoolean sawSession, AtomicBoolean sawProtocol)
			throws IOException {
		try (exchange) {
			if (exchange.getRequestMethod().equals("DELETE")) {
				exchange.sendResponseHeaders(204, -1);
				return;
			}
			JsonNode request = Json.MAPPER.readTree(exchange.getRequestBody());
			String method = request.path("method").asText();
			if (!method.equals("initialize")) {
				sawSession.set("session-1".equals(exchange.getRequestHeaders().getFirst("Mcp-Session-Id")));
				sawProtocol.set("2025-11-25".equals(exchange.getRequestHeaders().getFirst("MCP-Protocol-Version")));
			}
			if (!request.has("id")) {
				exchange.sendResponseHeaders(202, -1);
				return;
			}
			ObjectNode response = CodingAgentOperations.jsonObject().put("jsonrpc", "2.0");
			response.set("id", request.get("id"));
			switch (method) {
				case "initialize" -> {
					ObjectNode result = response.putObject("result");
					result.put("protocolVersion", "2025-11-25");
					result.putObject("capabilities").putObject("tools");
					exchange.getResponseHeaders().set("Mcp-Session-Id", "session-1");
				}
				case "tools/list" -> {
					ObjectNode tool = response.putObject("result").putArray("tools").addObject();
					tool.put("name", "echo");
					tool.putObject("inputSchema").put("type", "object").putObject("properties");
				}
				case "tools/call" -> response.putObject("result")
						.putArray("content")
						.addObject()
						.put("type", "text")
						.put("text", request.path("params").path("arguments").path("value").asText());
				default -> response.putObject("error").put("code", -32601).put("message", "not found");
			}
			byte[] body = Json.MAPPER.writeValueAsBytes(response);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
		}
	}
}
