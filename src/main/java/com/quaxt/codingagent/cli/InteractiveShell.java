package com.quaxt.codingagent.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import com.quaxt.codingagent.agent.Agent;
import com.quaxt.codingagent.agent.AgentEvent;
import com.quaxt.codingagent.agent.AgentTool;
import com.quaxt.codingagent.ai.CoreProviders;
import com.quaxt.codingagent.ai.Models;
import com.quaxt.codingagent.ai.Provider;
import com.quaxt.codingagent.ai.auth.Credential;
import com.quaxt.codingagent.ai.auth.ChatGptAuth;
import com.quaxt.codingagent.ai.auth.FileCredentialStore;
import com.quaxt.codingagent.ai.auth.GitHubCopilotAuth;
import com.quaxt.codingagent.ai.providers.GitHubCopilotProvider;
import com.quaxt.codingagent.ai.providers.ChatGptProvider;
import com.quaxt.codingagent.ai.types.AssistantContent;
import com.quaxt.codingagent.ai.types.AssistantMessage;
import com.quaxt.codingagent.ai.types.AssistantMessageEvent;
import com.quaxt.codingagent.ai.types.Message;
import com.quaxt.codingagent.ai.types.Model;
import com.quaxt.codingagent.ai.types.StopReason;
import com.quaxt.codingagent.ai.types.ThinkingLevel;
import com.quaxt.codingagent.ai.types.TextContent;
import com.quaxt.codingagent.ai.types.ThinkingContent;
import com.quaxt.codingagent.ai.types.ToolCall;
import com.quaxt.codingagent.ai.types.ToolResultMessage;
import com.quaxt.codingagent.ai.types.UserContent;
import com.quaxt.codingagent.ai.types.UserMessage;
import com.quaxt.codingagent.cli.session.SessionRecorder;
import com.quaxt.codingagent.cli.session.SessionSnapshot;
import com.quaxt.codingagent.cli.session.SessionStore;
import com.quaxt.codingagent.cli.settings.SettingsStore;
import com.quaxt.codingagent.cli.tools.BuiltInTools;
import com.quaxt.codingagent.mcp.McpAgentTool;
import com.quaxt.codingagent.mcp.McpManager;
import com.quaxt.codingagent.tui.InteractiveTerminal;
import com.quaxt.codingagent.tui.SelectItem;
import com.quaxt.codingagent.tui.Selector;
import com.quaxt.codingagent.tui.Theme;

/** Initial interactive shell backed by JLine and the shared agent runtime. */
final class InteractiveShell {
	private final CoreProviders providers;
	private final Cli.Arguments arguments;
	private final InteractiveTerminal terminal;
	private final SettingsStore settingsStore;
	private final McpManager mcp;
	private SettingsStore.Settings settings;
	private Agent agent;
	private SessionRecorder recorder;
	private Path cwd = Path.of(".").toAbsolutePath().normalize();
	private boolean emittedText;
	private boolean hideThinkingBlock;
	private StreamOutput streamOutput = StreamOutput.NONE;
	private int streamedThinkingCharacters;

	private InteractiveShell(
			CoreProviders providers,
			Cli.Arguments arguments,
			InteractiveTerminal terminal,
			SettingsStore settingsStore,
			SettingsStore.Settings settings,
			McpManager mcp) {
		this.providers = providers;
		this.arguments = arguments;
		this.terminal = terminal;
		this.settingsStore = settingsStore;
		this.mcp = mcp;
		this.settings = settings;
		this.hideThinkingBlock = settings.hideThinkingBlock();
		terminal.bindAppAction("expandTools", () -> showLatestTurnDetails(true));
		terminal.bindAppAction("toggleThinking", () -> toggleThinkingBlockVisibility(true));
	}

	static int run(CoreProviders providers, Cli.Arguments arguments) throws IOException, InterruptedException {
		SettingsStore settingsStore = SettingsStore.defaultStore();
		SettingsStore.Settings settings = settingsStore.load();
		Path workspace = Path.of(".").toAbsolutePath().normalize();
		try (McpManager mcp = McpManager.loadDefault(workspace);
				InteractiveTerminal terminal = new InteractiveTerminal(Cli.APP_NAME)) {
			InteractiveShell shell = new InteractiveShell(providers, arguments, terminal, settingsStore, settings, mcp);
			shell.applySavedTheme();
			if (arguments.model != null) {
				// An explicit CLI model overrides the saved default for this session only.
				shell.configure(Cli.resolveModel(providers, arguments.provider, arguments.model), false);
			} else if (arguments.provider != null) {
				Model model = selectModel(providers, arguments.provider, terminal);
				if (model == null) return 0;
				shell.configure(model, true);
			} else if (!shell.configureSavedModel() && !shell.configureSavedChatGpt()) {
				shell.configureSavedCopilot();
			}
			return shell.loop();
		}
	}

	private static Model selectModel(CoreProviders providers, String providerId, InteractiveTerminal terminal)
			throws IOException {
		List<Model> models = providerId == null
				? providers.catalog().all()
				: providers.require(providerId).models();
		if (models.isEmpty()) {
			throw new IllegalArgumentException("No bundled models for provider: " + providerId);
		}
		List<SelectItem<Model>> items = models.stream().map(InteractiveShell::modelItem).toList();
		return Selector.select(terminal, "Select a model", items, -1, true);
	}

	private int loop() throws InterruptedException, IOException {
		terminal.print(screenHeader(agent == null ? null : agent.state().model));
		while (true) {
			String input = terminal.readLine("\n> ");
			if (input == null) {
				terminal.println("");
				return 0;
			}
			if (input.isBlank()) continue;
			if (input.startsWith("/")) {
				if (command(input)) return 0;
				continue;
			}
			if (agent == null) {
				terminal.println("No model configured. Run /login to choose a provider.");
				continue;
			}
			mcp.awaitReady();
			syncMcpTools();
			emittedText = false;
			streamOutput = StreamOutput.NONE;
			streamedThinkingCharacters = 0;
			AtomicBoolean interrupted = new AtomicBoolean();
			List<Message> messages = terminal.runInterruptibly(
					() -> agent.prompt(input),
					() -> {
						interrupted.set(true);
						agent.abort();
					});
			if (recorder != null) recorder.appendMessages(messages);
			if (interrupted.get()) {
				finishStreamOutput();
				terminal.println("Interrupted.");
			} else if (!emittedText && agent.state().messages.getLast() instanceof AssistantMessage response) {
				terminal.println(response.errorMessage == null ? response.text() : "Error: " + response.errorMessage);
			}
		}
	}

	private boolean command(String input) throws InterruptedException, IOException {
		switch (input.trim()) {
			case "/exit", "/quit" -> {
				return true;
			}
			case "/help" ->
					terminal.println("Commands: /help, /details, /resume, /login, /logout, /models, /mcp, /settings, /compact, /theme <dark|light|plain>, /exit\nShortcuts: Esc interrupt the active turn; Ctrl-O inspect reasoning/tool steps; Ctrl-T show or hide streamed thinking.");
			case "/details" -> showLatestTurnDetails(false);
			case "/resume" -> resumeSession();
			case "/login" -> login();
			case "/logout" -> logout();
			case "/models" -> selectInteractiveModel();
			case "/mcp" -> selectMcpServers();
			case "/settings" -> selectSettings();
			case "/compact" -> {
				if (agent == null) {
					terminal.println("No model is configured.");
					return false;
				}
				var result = agent.compact(null);
				terminal.println("Context compacted: " + result.tokensBefore() + " -> " + result.estimatedTokensAfter() + " tokens.");
			}
			default -> {
				if (input.startsWith("/theme ")) {
					Theme theme = Theme.named(input.substring("/theme ".length()).trim());
					terminal.setTheme(theme);
					settings = settings.withTheme(theme.name());
					try {
						settingsStore.setTheme(theme.name());
						terminal.println("Theme: " + terminal.theme().name());
					} catch (IOException error) {
						terminal.println("Theme changed for this session, but could not be saved: " + error.getMessage());
					}
				} else terminal.println("Unknown command: " + input);
			}
		}
		return false;
	}

	private void applySavedTheme() {
		if (settings.theme() != null) {
			terminal.setTheme(Theme.named(settings.theme()));
		}
	}

	/** Returns true when a complete, valid saved model selection was restored. */
	private boolean configureSavedModel() throws IOException {
		if (settings.defaultProvider() == null || settings.defaultModel() == null) {
			return false;
		}
		Model model;
		try {
			model = findModel(providers.require(settings.defaultProvider()).models(), settings.defaultProvider(), settings.defaultModel());
		} catch (IllegalArgumentException error) {
			model = null;
		}
		if (model == null) {
			terminal.println("Saved model " + settings.defaultProvider() + "/" + settings.defaultModel()
					+ " is unavailable; selecting a fallback.");
			return false;
		}
		if (model.provider.equals(GitHubCopilotAuth.PROVIDER_ID)) {
			GitHubCopilotProvider copilot = copilotProvider();
			try {
				if (!copilot.hasCredential()) return false;
				model = findModel(copilot.availableModels(), model.provider, model.id);
				if (model == null) {
					terminal.println("Saved GitHub Copilot model is not enabled for this account; selecting a fallback.");
					return false;
				}
			} catch (IOException error) {
				terminal.println("Could not restore the saved GitHub Copilot model: " + error.getMessage());
				return false;
			}
		}
		if (model.provider.equals(ChatGptAuth.PROVIDER_ID)) {
			try {
				if (!chatGptProvider().hasCredential()) return false;
			} catch (IOException error) {
				terminal.println("Could not restore the saved ChatGPT model: " + error.getMessage());
				return false;
			}
		}
		configure(model, false);
		return true;
	}

	private void configureSavedCopilot() {
		GitHubCopilotProvider copilot = copilotProvider();
		try {
			if (!copilot.hasCredential()) {
				return;
			}
			List<Model> models = copilot.availableModels();
			Model model = preferredCopilotModel(models);
			if (model != null) {
				configure(model, true);
			} else {
				terminal.println("GitHub Copilot has no enabled coding models. Run /login to refresh access.");
			}
		} catch (IOException error) {
			terminal.println("GitHub Copilot login needs attention: " + error.getMessage());
		}
	}

	/** Restores a valid ChatGPT login even when an earlier model selection was not persisted. */
	private boolean configureSavedChatGpt() {
		ChatGptProvider chatGpt = chatGptProvider();
		try {
			if (!chatGpt.hasCredential() || chatGpt.models().isEmpty()) {
				return false;
			}
			Model model = preferredChatGptModel(chatGpt.models());
			configure(model, true);
			return true;
		} catch (IOException error) {
			terminal.println("ChatGPT login needs attention: " + error.getMessage());
			return false;
		}
	}

	private void login() throws IOException, InterruptedException {
		terminal.println("Log in to a provider:");
		terminal.println("  1. GitHub Copilot — sign in through GitHub's device authorization flow");
		terminal.println("  2. OpenAI API key — use separately billed Platform API credits");
		terminal.println("  3. ChatGPT Plus/Pro — use your ChatGPT subscription through Codex");
		String choice = terminal.readLine("Select provider [1-3]: ");
		if (choice == null || choice.isBlank()) {
			terminal.println("Login cancelled.");
			return;
		}
		switch (choice.trim().toLowerCase(java.util.Locale.ROOT)) {
			case "1", "github", "github copilot", "copilot" -> loginCopilot();
			case "2", "openai", "open ai", "openai api", "openai api key" -> loginOpenAi();
			case "3", "chatgpt", "chatgpt plus", "chatgpt pro", "chatgpt plus/pro" -> loginChatGpt();
			default -> terminal.println("Unknown provider. Enter 1 for GitHub Copilot, 2 for an OpenAI API key, or 3 for ChatGPT Plus/Pro.");
		}
	}

	private void loginCopilot() throws IOException, InterruptedException {
		GitHubCopilotProvider copilot = copilotProvider();
		GitHubCopilotAuth.DeviceCode device = copilot.auth().beginLogin();
		terminal.println("Open " + device.verificationUri() + " and enter code " + device.userCode() + ".");
		terminal.println("Waiting for GitHub authorization...");
		copilot.auth().completeLogin(device);
		terminal.println("Enabling GitHub Copilot models...");
		var access = copilot.enableAndRefreshModels();
		if (access.policiesEnabled() < copilot.models().size()) {
			terminal.println("Some GitHub Copilot models are unavailable for this account.");
		}
		List<Model> models = access.models();
		Model model = savedModelIn(models);
		if (model == null) model = preferredCopilotModel(models);
		if (model == null) {
			terminal.println("GitHub Copilot login succeeded, but no enabled coding model was returned.");
			return;
		}
		configure(model, true);
		terminal.println("GitHub Copilot is ready with " + model + ".");
	}

	private void loginOpenAi() throws IOException {
		String apiKey = terminal.readPassword("OpenAI API key: ");
		if (apiKey == null || apiKey.isBlank()) {
			terminal.println("OpenAI login cancelled.");
			return;
		}
		FileCredentialStore.defaultStore().modify("openai", ignored -> new Credential.ApiKeyCredential(apiKey.trim()));
		List<Model> models = providers.require("openai").models();
		Model model = savedModelIn(models);
		if (model == null) {
			model = Selector.select(
					terminal,
					"Select an OpenAI model",
					models.stream().map(InteractiveShell::modelItem).toList(),
					-1,
					true);
		}
		if (model == null) {
			terminal.println("OpenAI API key saved. Run /models when you are ready to select a model.");
			return;
		}
		configure(model, true);
		terminal.println("OpenAI is ready with " + model + ".");
	}

	private void loginChatGpt() throws IOException, InterruptedException {
		ChatGptProvider chatGpt = chatGptProvider();
		ChatGptAuth.DeviceCode device = chatGpt.auth().beginLogin();
		terminal.println("Open " + device.verificationUri() + " and enter code " + device.userCode() + ".");
		terminal.println("Waiting for ChatGPT authorization...");
		chatGpt.auth().completeLogin(device);
		List<Model> models = chatGpt.models();
		Model model = savedModelIn(models);
		if (model == null) {
			model = Selector.select(
					terminal,
					"Select a ChatGPT model",
					models.stream().map(InteractiveShell::modelItem).toList(),
					-1,
					true);
		}
		if (model == null) {
			terminal.println("ChatGPT login saved. Run /models when you are ready to select a model.");
			return;
		}
		configure(model, true);
		terminal.println("ChatGPT Plus/Pro is ready with " + model + ".");
	}

	private void logout() throws IOException {
		if (agent != null && agent.state().model.provider.equals(ChatGptAuth.PROVIDER_ID)) {
			chatGptProvider().logout();
			agent = null;
			terminal.println("ChatGPT credentials removed. Run /login or /resume to continue.");
			return;
		}
		if (agent != null && agent.state().model.provider.equals("openai")) {
			FileCredentialStore.defaultStore().delete("openai");
			agent = null;
			terminal.println("OpenAI API key removed. Run /login or /resume to continue.");
			return;
		}
		copilotProvider().logout();
		if (agent != null && agent.state().model.provider.equals(GitHubCopilotAuth.PROVIDER_ID)) {
			agent = null;
			terminal.println("GitHub Copilot credentials removed. Run /login or /resume to continue.");
		} else {
			terminal.println("GitHub Copilot credentials removed.");
		}
	}

	private void resumeSession() throws IOException {
		if (arguments.noSession) {
			terminal.println("Session persistence is disabled by --no-session.");
			return;
		}
		SessionStore store = SessionStore.defaultStore();
		List<SessionSnapshot> sessions;
		try {
			sessions = store.listSnapshots(cwd);
		} catch (IOException error) {
			terminal.println("Failed to list saved sessions: " + error.getMessage());
			return;
		}
		if (sessions.isEmpty()) {
			terminal.println("No saved sessions in " + cwd + ".");
			return;
		}
		String currentSessionId = recorder == null ? null : recorder.sessionId();
		List<SelectItem<SessionSnapshot>> items = sessions.stream()
				.filter(session -> session.messageCount() > 0 && !session.id().equals(currentSessionId))
				.map(InteractiveShell::sessionItem)
				.toList();
		if (items.isEmpty()) {
			terminal.println("No resumable sessions in " + cwd + ".");
			return;
		}
		SessionSnapshot selected = Selector.select(
				terminal, "Resume Session (Current Folder)", items, -1, true);
		if (selected == null) return;
		try {
			resume(store, selected);
		} catch (IOException | IllegalArgumentException error) {
			terminal.println("Failed to resume session: " + error.getMessage());
		}
	}

	private void resume(SessionStore store, SessionSnapshot session) throws IOException {
		if (!Files.isDirectory(session.cwd())) {
			terminal.println("Cannot resume session because its working directory is unavailable: " + session.cwd());
			return;
		}
		Model model;
		try {
			model = findModel(providers.require(session.provider()).models(), session.provider(), session.model());
		} catch (IllegalArgumentException error) {
			model = null;
		}
		if (model == null) {
			if (agent == null) {
				terminal.println("Cannot restore model " + session.provider() + "/" + session.model()
						+ "; configure an available model before resuming this session.");
				return;
			}
			model = agent.state().model;
			terminal.println("Could not restore model " + session.provider() + "/" + session.model()
					+ ". Using " + model + ".");
		}
		if (model.provider.equals(GitHubCopilotAuth.PROVIDER_ID)) {
			GitHubCopilotProvider copilot = copilotProvider();
			Model enabled = null;
			try {
				if (copilot.hasCredential()) {
					enabled = findModel(copilot.availableModels(), model.provider, model.id);
				}
			} catch (IOException error) {
				terminal.println("Could not refresh GitHub Copilot model access: " + error.getMessage());
			}
			if (enabled == null) {
				if (agent == null || agent.state().model.provider.equals(GitHubCopilotAuth.PROVIDER_ID)) {
					terminal.println("Cannot restore GitHub Copilot model " + model.id + "; log in or configure another model first.");
					return;
				}
				Model fallback = agent.state().model;
				terminal.println("Could not restore model " + model + ". Using " + fallback + ".");
				model = fallback;
			} else {
				model = enabled;
			}
		}
		SessionRecorder resumedRecorder = SessionRecorder.resume(store, session.id());
		List<Message> restored = resumableMessages(session.messages());
		configureAgent(model, session.cwd(), resumedRecorder);
		settings = settings.withDefaultModel(model.provider, model.id);
		agent.state().messages.addAll(restored);
		terminal.replaceScreen(renderSessionScreen(model, session.messages(), hideThinkingBlock, terminal.theme()));
		try {
			settingsStore.setDefaultModelAndProvider(model.provider, model.id);
		} catch (IOException error) {
			terminal.println("Resumed model could not be saved as the default: " + error.getMessage());
		}
		terminal.println("Resumed session " + session.id() + " with " + restored.size() + " message(s) using " + model + ".");
	}

	static String renderSessionScreen(
			Model model, List<Message> messages, boolean hideThinking, Theme theme) {
		StringBuilder screen = new StringBuilder(screenHeader(model));
		Map<String, ToolResultMessage> toolResults = new LinkedHashMap<>();
		for (Message message : messages) {
			if (message instanceof ToolResultMessage result) toolResults.put(result.toolCallId(), result);
		}
		Set<String> renderedToolResults = new HashSet<>();
		for (Message message : messages) {
			switch (message) {
				case UserMessage user -> screen.append("\n> ").append(user.text()).append('\n');
				case AssistantMessage assistant -> appendAssistant(
						screen, assistant, hideThinking, theme, toolResults, renderedToolResults);
				case ToolResultMessage result -> {
					if (renderedToolResults.add(result.toolCallId())) appendToolResult(screen, result);
				}
			}
		}
		return screen.toString();
	}

	private static String screenHeader(Model model) {
		StringBuilder header = new StringBuilder("codingagent ").append(Cli.VERSION);
		if (model != null) header.append("  ").append(model);
		header.append('\n');
		header.append(model == null
				? "Run /login to choose a provider. Commands: /help, /resume, /login, /mcp, /exit"
				: "Enter a prompt. Esc interrupts; Ctrl-O inspects reasoning/tool steps; Ctrl-T toggles thinking. Commands: /help, /resume, /models, /mcp, /settings, /compact, /logout, /theme <dark|light|plain>, /exit");
		header.append('\n');
		return header.toString();
	}

	private static void appendAssistant(
			StringBuilder screen,
			AssistantMessage assistant,
			boolean hideThinking,
			Theme theme,
			Map<String, ToolResultMessage> toolResults,
			Set<String> renderedToolResults) {
		boolean rendered = false;
		for (AssistantContent content : assistant.content) {
			if (content instanceof ThinkingContent thinking) {
				if (!hideThinking && !thinking.thinking().isBlank()) {
					screen.append("\n").append(theme.muted()).append("Thinking:").append(theme.reset()).append('\n');
					screen.append(theme.muted()).append(thinking.thinking()).append(theme.reset()).append('\n');
					rendered = true;
				}
			} else if (content instanceof TextContent text) {
				screen.append(text.text()).append('\n');
				rendered = true;
			} else if (content instanceof ToolCall call) {
				screen.append("\n[")
						.append(call.name())
						.append("] ")
						.append(toolDescription(call.name(), call.arguments()))
						.append('\n');
				ToolResultMessage result = toolResults.get(call.id());
				if (result != null) {
					renderedToolResults.add(result.toolCallId());
					appendToolResult(screen, result);
				}
				rendered = true;
			}
		}
		if (!rendered && assistant.errorMessage != null) {
			screen.append("Error: ").append(assistant.errorMessage).append('\n');
		}
	}

	private static void appendToolResult(StringBuilder screen, ToolResultMessage result) {
		screen.append("  ")
				.append(result.isError() ? "Error" : "Done")
				.append(": ")
				.append(toolResultSummary(result.toolName(), result.text(), result.isError()))
				.append('\n');
	}

	static List<Message> resumableMessages(List<Message> messages) {
		List<Message> result = new ArrayList<>();
		Map<String, String> pendingToolCalls = new LinkedHashMap<>();
		for (Message message : messages) {
			if (message instanceof AssistantMessage assistant) {
				appendMissingToolResults(result, pendingToolCalls);
				if (assistant.stopReason == StopReason.ERROR || assistant.stopReason == StopReason.ABORTED) continue;
				assistant.toolCalls().forEach(call -> pendingToolCalls.put(call.id(), call.name()));
				result.add(message);
			} else if (message instanceof ToolResultMessage toolResult) {
				if (pendingToolCalls.remove(toolResult.toolCallId()) != null) result.add(message);
			} else {
				appendMissingToolResults(result, pendingToolCalls);
				result.add(message);
			}
		}
		appendMissingToolResults(result, pendingToolCalls);
		return List.copyOf(result);
	}

	private static void appendMissingToolResults(List<Message> messages, Map<String, String> pendingToolCalls) {
		for (var toolCall : pendingToolCalls.entrySet()) {
			messages.add(ToolResultMessage.text(toolCall.getKey(), toolCall.getValue(), "No result provided", true));
		}
		pendingToolCalls.clear();
	}

	private static SelectItem<SessionSnapshot> sessionItem(SessionSnapshot session) {
		String message = abbreviate(session.firstMessage().replaceAll("[\\p{Cntrl}]", " "), 90);
		String description = session.messageCount() + " messages  " + formatAge(session.modified())
				+ "  [" + session.provider() + "/" + session.model() + "]";
		return new SelectItem<>(
				session,
				message,
				description,
				session.id() + " " + session.provider() + " " + session.model() + " "
						+ session.firstMessage() + " " + session.allMessagesText());
	}

	private static String formatAge(Instant instant) {
		long minutes = Math.max(0, Duration.between(instant, Instant.now()).toMinutes());
		if (minutes < 1) return "now";
		if (minutes < 60) return minutes + "m";
		long hours = minutes / 60;
		if (hours < 24) return hours + "h";
		long days = hours / 24;
		if (days < 7) return days + "d";
		if (days < 30) return days / 7 + "w";
		if (days < 365) return days / 30 + "mo";
		return days / 365 + "y";
	}

	private void selectInteractiveModel() throws IOException {
		List<Model> models = selectableModels();
		List<SelectItem<Model>> items = models.stream().map(InteractiveShell::modelItem).toList();
		int currentIndex = agent == null ? -1 : modelIndex(models, agent.state().model);
		Model model = Selector.select(terminal, "Select a model", items, currentIndex, true);
		if (model == null) {
			return;
		}
		configure(model, true);
		terminal.println("Using " + model + " in a new agent session.");
	}

	private void selectMcpServers() throws IOException {
		if (mcp.isEmpty()) {
			terminal.println("No MCP servers configured in ~/.codingagent/settings.json.");
			return;
		}
		terminal.run(new McpSelector(mcp, this::syncMcpTools));
	}

	private void selectSettings() throws IOException {
		if (agent == null) {
			terminal.println("No model is configured.");
			return;
		}
		String selected = Selector.select(
				terminal,
				"Settings",
				List.of(new SelectItem<>(
						"thinking",
						"Thinking level",
						agent.state().thinkingLevel.wire())),
				0,
				false);
		if (selected != null) {
			selectThinkingLevel();
		}
	}

	private void selectThinkingLevel() throws IOException {
		List<ThinkingLevel> levels = Models.getSupportedThinkingLevels(agent.state().model);
		List<SelectItem<ThinkingLevel>> items = levels.stream()
				.map(level -> new SelectItem<>(level, level.wire(), thinkingDescription(level)))
				.toList();
		int currentIndex = Math.max(0, levels.indexOf(agent.state().thinkingLevel));
		ThinkingLevel level = Selector.select(terminal, "Thinking level", items, currentIndex, false);
		if (level == null) {
			return;
		}
		agent.state().thinkingLevel = level;
		settings = settings.withDefaultThinkingLevel(level);
		try {
			settingsStore.setDefaultThinkingLevel(level);
			terminal.println("Thinking level: " + level.wire());
		} catch (IOException error) {
			terminal.println("Thinking level changed for this session, but could not be saved: " + error.getMessage());
		}
	}

	private static String thinkingDescription(ThinkingLevel level) {
		return switch (level) {
			case OFF -> "No reasoning";
			case MINIMAL -> "Very brief reasoning";
			case LOW -> "Light reasoning";
			case MEDIUM -> "Moderate reasoning";
			case HIGH -> "Deep reasoning";
			case XHIGH -> "Extra-high reasoning";
			case MAX -> "Maximum reasoning";
		};
	}

	private List<Model> selectableModels() {
		List<Model> models = new ArrayList<>();
		for (Model model : providers.catalog().all()) {
			if (!model.provider.equals(GitHubCopilotAuth.PROVIDER_ID)) {
				models.add(model);
			}
		}
		GitHubCopilotProvider copilot = copilotProvider();
		try {
			if (copilot.hasCredential()) {
				terminal.println("Refreshing GitHub Copilot models...");
				models.addAll(copilot.enableAndRefreshModels().models());
			} else {
				models.addAll(copilot.models());
			}
		} catch (IOException error) {
			terminal.println("Could not refresh GitHub Copilot model access: " + error.getMessage());
			models.addAll(copilot.models());
		}
		models.addAll(chatGptProvider().models());
		return List.copyOf(models);
	}

	private void configure(Model model, boolean persistModel) throws IOException {
		Path configuredCwd = Path.of(".").toAbsolutePath().normalize();
		SessionRecorder nextRecorder = null;
		if (!arguments.noSession) {
			try {
				nextRecorder = SessionRecorder.create(SessionStore.defaultStore(), configuredCwd, model.provider, model.id);
			} catch (IOException error) {
				terminal.println("Model configured, but session persistence is unavailable: " + error.getMessage());
			}
		}
		configureAgent(model, configuredCwd, nextRecorder);
		if (persistModel) {
			settings = settings.withDefaultModel(model.provider, model.id);
			try {
				settingsStore.setDefaultModelAndProvider(model.provider, model.id);
			} catch (IOException error) {
				terminal.println("Model changed for this session, but could not be saved: " + error.getMessage());
			}
		}
	}

	private void configureAgent(Model model, Path configuredCwd, SessionRecorder nextRecorder) {
		Provider provider = providers.require(model.provider);
		Agent configured = new Agent(arguments.systemPrompt, model, provider::stream);
		configured.setApiKey(arguments.apiKey);
		configured.state().thinkingLevel = initialThinkingLevel(model, settings.defaultThinkingLevel());
		configured.state().tools.addAll(BuiltInTools.create(configuredCwd));
		configured.state().tools.addAll(mcp.tools());
		configured.subscribe(this::onEvent);
		cwd = configuredCwd.toAbsolutePath().normalize();
		agent = configured;
		recorder = nextRecorder;
	}

	private void syncMcpTools() {
		if (agent == null || agent.state().isStreaming) return;
		agent.state().tools.removeIf(McpAgentTool.class::isInstance);
		agent.state().tools.addAll(mcp.tools());
	}

	private GitHubCopilotProvider copilotProvider() {
		return (GitHubCopilotProvider) providers.require(GitHubCopilotAuth.PROVIDER_ID);
	}

	private ChatGptProvider chatGptProvider() {
		return (ChatGptProvider) providers.require(ChatGptAuth.PROVIDER_ID);
	}

	private Model savedModelIn(List<Model> models) {
		if (settings.defaultProvider() == null || settings.defaultModel() == null) return null;
		return findModel(models, settings.defaultProvider(), settings.defaultModel());
	}

	static ThinkingLevel initialThinkingLevel(Model model, ThinkingLevel configuredLevel) {
		return Models.clampThinkingLevel(
				model, configuredLevel == null ? ThinkingLevel.MEDIUM : configuredLevel);
	}

	static Model preferredCopilotModel(List<Model> models) {
		for (Model model : models) {
			if (model.id.equals("gpt-5.4")) {
				return model;
			}
		}
		return models.isEmpty() ? null : models.getFirst();
	}

	static Model preferredChatGptModel(List<Model> models) {
		for (String preferred : List.of("gpt-5.6-terra", "gpt-5.6-sol", "gpt-5.4")) {
			for (Model model : models) {
				if (model.id.equals(preferred)) return model;
			}
		}
		return models.isEmpty() ? null : models.getFirst();
	}

	private static Model findModel(List<Model> models, String provider, String id) {
		for (Model model : models) {
			if (model.provider.equals(provider) && model.id.equals(id)) return model;
		}
		return null;
	}

	private static SelectItem<Model> modelItem(Model model) {
		String description = "[" + model.provider + "] " + model.name;
		return new SelectItem<>(
				model,
				model.id,
				description,
				model.provider + " " + model.id + " " + model.name);
	}

	private static int modelIndex(List<Model> models, Model selected) {
		for (int index = 0; index < models.size(); index++) {
			Model model = models.get(index);
			if (model.provider.equals(selected.provider) && model.id.equals(selected.id)) {
				return index;
			}
		}
		return -1;
	}

	private void showLatestTurnDetails(boolean lineEditorActive) {
		if (agent == null) {
			showShortcutStatus("No model is configured.", lineEditorActive);
			return;
		}
		TurnDetailsComponent details = TurnDetailsComponent.forLatestTurn(
				agent.state().messages, hideThinkingBlock);
		if (details == null) {
			showShortcutStatus("The latest turn has no reasoning or tool details.", lineEditorActive);
			return;
		}
		try {
			boolean hiddenAfter = terminal.run(details);
			if (hiddenAfter != hideThinkingBlock) {
				setHideThinkingBlock(hiddenAfter, lineEditorActive);
			}
		} catch (IOException error) {
			showShortcutStatus("Could not open turn details: " + error.getMessage(), lineEditorActive);
		}
	}

	private void toggleThinkingBlockVisibility(boolean lineEditorActive) {
		setHideThinkingBlock(!hideThinkingBlock, lineEditorActive);
	}

	private void setHideThinkingBlock(boolean hidden, boolean lineEditorActive) {
		hideThinkingBlock = hidden;
		settings = settings.withHideThinkingBlock(hidden);
		String status = "Thinking blocks: " + (hidden ? "hidden" : "visible");
		try {
			settingsStore.setHideThinkingBlock(hidden);
		} catch (IOException error) {
			status += " (could not save: " + error.getMessage() + ")";
		}
		showShortcutStatus(status, lineEditorActive);
	}

	private void showShortcutStatus(String status, boolean lineEditorActive) {
		if (lineEditorActive) terminal.printAbove(status);
		else terminal.println(status);
	}

	private void onEvent(AgentEvent event) {
		switch (event) {
			case AgentEvent.MessageUpdate update -> onMessageUpdate(update.providerEvent());
			case AgentEvent.MessageEnd end -> {
				if (end.message() instanceof AssistantMessage) finishStreamOutput();
			}
			case AgentEvent.ToolExecutionStart start -> {
				finishStreamOutput();
				terminal.println("\n[" + start.toolName() + "] " + toolDescription(start.toolName(), start.arguments()));
			}
			case AgentEvent.ToolExecutionEnd end -> {
				String label = end.result().isError() ? "Error" : "Done";
				terminal.println("  " + label + ": " + toolResultSummary(end.toolName(), end.result()));
			}
			default -> {
				// Other lifecycle events have no interactive presentation yet.
			}
		}
	}

	private void onMessageUpdate(AssistantMessageEvent event) {
		switch (event) {
			case AssistantMessageEvent.ThinkingStart ignored -> {
				if (!hideThinkingBlock) {
					finishStreamOutput();
					terminal.print("\n" + terminal.theme().muted() + "Thinking:" + terminal.theme().reset() + "\n");
					streamOutput = StreamOutput.THINKING;
					streamedThinkingCharacters = 0;
				}
			}
			case AssistantMessageEvent.ThinkingDelta delta -> {
				if (!hideThinkingBlock) {
					if (streamOutput != StreamOutput.THINKING) {
						terminal.print("\n" + terminal.theme().muted() + "Thinking:" + terminal.theme().reset() + "\n");
						streamOutput = StreamOutput.THINKING;
						streamedThinkingCharacters = 0;
					}
					terminal.print(terminal.theme().muted() + delta.delta() + terminal.theme().reset());
					streamedThinkingCharacters += delta.delta().length();
				}
			}
			case AssistantMessageEvent.ThinkingEnd end -> {
				if (!hideThinkingBlock && streamOutput == StreamOutput.THINKING) {
					if (streamedThinkingCharacters == 0 && !end.content().isBlank()) {
						terminal.print(terminal.theme().muted() + end.content() + terminal.theme().reset());
					}
					finishStreamOutput();
				}
			}
			case AssistantMessageEvent.TextStart ignored -> {
				finishStreamOutput();
				streamOutput = StreamOutput.TEXT;
			}
			case AssistantMessageEvent.TextDelta delta -> {
				if (streamOutput != StreamOutput.TEXT) {
					finishStreamOutput();
					streamOutput = StreamOutput.TEXT;
				}
				terminal.print(delta.delta());
				emittedText = true;
			}
			case AssistantMessageEvent.TextEnd ignored -> finishStreamOutput();
			default -> {
				// Tool-call argument streaming is rendered once execution starts.
			}
		}
	}

	private void finishStreamOutput() {
		if (streamOutput != StreamOutput.NONE) {
			terminal.println("");
			streamOutput = StreamOutput.NONE;
		}
	}

	static String toolDescription(String toolName, ObjectNode arguments) {
		return switch (toolName) {
			case "read" -> {
				int offset = arguments.path("offset").asInt(1);
				int limit = arguments.path("limit").asInt();
				yield "Reading " + textArgument(arguments, "path", ".")
						+ (limit > 0 ? " (lines " + offset + "-" + (offset + limit - 1) + ")" : " (from line " + offset + ")");
			}
			case "write" -> "Writing " + textArgument(arguments, "path", ".") + " (" + textArgument(arguments, "content", "").length() + " characters)";
			case "edit" -> "Editing " + textArgument(arguments, "path", ".") + " (" + arguments.path("edits").size() + " replacement(s))";
			case "shell" -> abbreviate(textArgument(arguments, "command", ""), 240);
			case "grep" -> "Searching for " + textArgument(arguments, "pattern", "") + " in " + textArgument(arguments, "path", ".");
			case "find" -> "Finding " + textArgument(arguments, "pattern", "") + " in " + textArgument(arguments, "path", ".");
			case "ls" -> "Listing " + textArgument(arguments, "path", ".");
			default -> abbreviate(arguments.toString(), 240);
		};
	}

	static String toolResultSummary(String toolName, AgentTool.ToolResult result) {
		return toolResultSummary(toolName, textResult(result.content()), result.isError());
	}

	private static String toolResultSummary(String toolName, String output, boolean error) {
		if (output.isBlank()) {
			return error ? "Tool failed without an error message." : "Completed.";
		}
		if (toolName.equals("read") && !error) {
			return "Read " + output.lines().count() + " line(s).";
		}
		return abbreviate(output, error ? 480 : 320);
	}

	private static String textResult(List<UserContent> content) {
		StringBuilder text = new StringBuilder();
		for (UserContent block : content) {
			if (block instanceof TextContent value) {
				if (!text.isEmpty()) {
					text.append('\n');
				}
				text.append(value.text());
			}
		}
		return text.toString();
	}

	private static String textArgument(ObjectNode arguments, String name, String fallback) {
		JsonNode value = arguments.get(name);
		return value != null && value.isTextual() ? value.asText() : fallback;
	}

	private static String abbreviate(String value, int maximumLength) {
		String normalized = value.replaceAll("\\s+", " ").trim();
		return normalized.length() <= maximumLength ? normalized : normalized.substring(0, maximumLength) + "...";
	}

	private enum StreamOutput {
		NONE,
		THINKING,
		TEXT
	}

}
