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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
import com.quaxt.codingagent.ai.types.Usage;
import com.quaxt.codingagent.ai.types.UserContent;
import com.quaxt.codingagent.ai.types.UserMessage;
import com.quaxt.codingagent.cli.session.SessionRecorder;
import com.quaxt.codingagent.cli.session.SessionSnapshot;
import com.quaxt.codingagent.cli.session.SessionStore;
import com.quaxt.codingagent.cli.settings.SettingsStore;
import com.quaxt.codingagent.mcp.McpAgentTool;
import com.quaxt.codingagent.mcp.McpManager;
import com.quaxt.codingagent.tui.InteractiveTerminal;
import com.quaxt.codingagent.tui.SelectItem;
import com.quaxt.codingagent.tui.Selector;
import com.quaxt.codingagent.tui.Theme;

/** Initial interactive shell backed by JLine and the shared agent runtime. */
final class InteractiveShell implements AutoCloseable {
	static final List<String> SLASH_COMMANDS = List.of(
			"/compact",
			"/details",
			"/exit",
			"/help",
			"/login",
			"/logout",
			"/mcp",
			"/models",
			"/quit",
			"/resume",
			"/settings",
			"/theme");

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
	private final Object activityLock = new Object();
	private final ScheduledExecutorService statusTicker;
	private volatile ActivityStatus activity = ActivityStatus.noModel(System.nanoTime());
	private volatile String statusLocation = "";
	private volatile String statusModel = "";

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
		statusTicker = Executors.newSingleThreadScheduledExecutor(
				Thread.ofPlatform().daemon(true).name("codingagent-status").factory());
		terminal.bindAppAction("expandTools", () -> showLatestTurnDetails(true));
		terminal.bindAppAction("toggleThinking", () -> toggleThinkingBlockVisibility(true));
	}

	static int run(CoreProviders providers, Cli.Arguments arguments) throws IOException, InterruptedException {
		SettingsStore settingsStore = SettingsStore.defaultStore();
		SettingsStore.Settings settings = settingsStore.load();
		Path workspace = Path.of(".").toAbsolutePath().normalize();
		try (McpManager mcp = McpManager.loadDefault(workspace);
				InteractiveTerminal terminal = new InteractiveTerminal(Cli.APP_NAME);
				InteractiveShell shell = new InteractiveShell(
						providers, arguments, terminal, settingsStore, settings, mcp)) {
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
		refreshStatus();
		statusTicker.scheduleWithFixedDelay(this::tickStatus, 1, 1, TimeUnit.SECONDS);
		while (true) {
			String input = terminal.readLine("\n> ", SLASH_COMMANDS);
			if (input == null) {
				terminal.println("");
				return 0;
			}
			if (input.isBlank()) continue;
			if (input.startsWith("/")) {
				boolean exit;
				setActivity(ActivityStatus.active(
						ActivityStatus.Phase.RUNNING_COMMAND, commandName(input), System.nanoTime()));
				try {
					exit = command(input);
				} finally {
					setIdleActivity();
					refreshStatus();
				}
				if (exit) return 0;
				continue;
			}
			if (agent == null) {
				terminal.println("No model configured. Run /login to choose a provider.");
				continue;
			}
			setActivity(ActivityStatus.active(ActivityStatus.Phase.PREPARING_TOOLS, System.nanoTime()));
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
						setActivity(ActivityStatus.active(ActivityStatus.Phase.STOPPING, System.nanoTime()));
						agent.abort();
					});
			if (recorder != null) recorder.appendMessages(messages);
			if (interrupted.get()) {
				finishStreamOutput();
				terminal.println("Interrupted.");
			} else if (agent.state().messages.getLast() instanceof AssistantMessage response) {
				String finalOutput = finalAssistantOutput(response, emittedText);
				if (finalOutput != null) {
					finishStreamOutput();
					terminal.println(finalOutput);
				}
			}
			// AgentEnd normally performs this transition. Reassert it here to close
			// the small race where Escape arrives after AgentEnd but before the task returns.
			setActivity(ActivityStatus.ready(System.nanoTime()));
			refreshStatus();
		}
	}

	private boolean command(String input) throws InterruptedException, IOException {
		switch (input.trim()) {
			case "/exit", "/quit" -> {
				return true;
			}
			case "/help" ->
					terminal.println("Commands: /help, /details, /resume, /login, /logout, /models, /mcp, /settings, /compact, /theme <dark|light|plain>, /exit\nShortcuts: Shift-Enter inserts a newline; Esc interrupts the active turn; Ctrl-O inspects reasoning/tool steps; Ctrl-T shows or hides streamed thinking.");
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
				try {
					var result = agent.compact(null);
					terminal.println("Context compacted: " + result.tokensBefore() + " -> " + result.estimatedTokensAfter() + " tokens.");
					refreshStatus();
				} catch (IllegalStateException error) {
					terminal.println("Error: " + error.getMessage());
				}
			}
			default -> {
				if (input.startsWith("/theme ")) {
					Theme theme = Theme.named(input.substring("/theme ".length()).trim());
					terminal.setTheme(theme);
					refreshStatus();
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
		try {
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
		} finally {
			refreshStatus();
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
		refreshStatus();
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
				case UserMessage user -> screen.append('\n').append(theme.promptArea("> " + user.text())).append('\n');
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
				: "Enter submits; Shift-Enter adds a newline; Esc interrupts. Ctrl-O inspects steps; Ctrl-T toggles thinking. Commands: /help, /resume, /models, /mcp, /settings, /compact, /logout, /theme <dark|light|plain>, /exit");
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
		for (AssistantContent content : assistant.content) {
			if (content instanceof ThinkingContent thinking) {
				if (!hideThinking && !thinking.thinking().isBlank()) {
					screen.append("\n").append(theme.muted()).append("Thinking:").append(theme.reset()).append('\n');
					screen.append(theme.muted()).append(thinking.thinking()).append(theme.reset()).append('\n');
				}
			} else if (content instanceof TextContent text) {
				screen.append(text.text()).append('\n');
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
			}
		}
		if (assistant.errorMessage != null) {
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

	static String finalAssistantOutput(AssistantMessage response, boolean emittedText) {
		if (response.errorMessage != null) return "Error: " + response.errorMessage;
		return emittedText ? null : response.text();
	}

	private static String formatRetryDelay(long delayMs) {
		if (delayMs < 1_000) return delayMs + "ms";
		if (delayMs % 1_000 == 0) return delayMs / 1_000 + "s";
		return String.format(java.util.Locale.ROOT, "%.1fs", delayMs / 1_000.0);
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
		// An OAuth connection may finish asynchronously while the selector is open.
		syncMcpTools();
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
		refreshStatus();
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
		Cli.configureBuiltInTools(configured, configuredCwd, arguments.systemPrompt);
		configured.state().tools.addAll(mcp.tools());
		configured.subscribe(this::onEvent);
		cwd = configuredCwd.toAbsolutePath().normalize();
		agent = configured;
		recorder = nextRecorder;
		if (activity.phase() != ActivityStatus.Phase.RUNNING_COMMAND) {
			setActivity(ActivityStatus.ready(System.nanoTime()));
		}
		refreshStatus();
	}

	/** Updates cached workspace/model details, then redraws the live activity status. */
	private void refreshStatus() {
		String branch = gitBranch(cwd);
		statusLocation = displayPath(Path.of(System.getProperty("user.home", "")), cwd)
				+ (branch == null ? "" : " [" + branch + "]");
		statusModel = agent == null
				? ""
				: modelStatus(agent.state().model, agent.state().thinkingLevel, contextTokens(agent.state().messages));
		renderStatus();
	}

	private void tickStatus() {
		if (!activity.isDynamic()) return;
		try {
			renderStatus();
		} catch (RuntimeException ignored) {
			// A best-effort repaint must not terminate the shell's status ticker.
		}
	}

	private void renderStatus() {
		ActivityStatus current = activity;
		terminal.setStatus(
				current.label(System.nanoTime()),
				current.accent(),
				statusLocation,
				statusModel);
	}

	private void setIdleActivity() {
		setActivity(agent == null
				? ActivityStatus.noModel(System.nanoTime())
				: ActivityStatus.ready(System.nanoTime()));
	}

	private void setActivity(ActivityStatus next) {
		boolean changed;
		synchronized (activityLock) {
			ActivityStatus current = activity;
			if (current.phase() == ActivityStatus.Phase.STOPPING
					&& next.phase() != ActivityStatus.Phase.READY
					&& next.phase() != ActivityStatus.Phase.NO_MODEL) {
				return;
			}
			changed = !current.sameActivity(next);
			if (changed) activity = next;
		}
		if (changed) renderStatus();
	}

	private static String commandName(String input) {
		String trimmed = input.trim();
		for (int index = 0; index < trimmed.length(); index++) {
			if (Character.isWhitespace(trimmed.charAt(index))) return trimmed.substring(0, index);
		}
		return trimmed;
	}

	/** Formats the model segment, e.g. {@code GPT-5.6 Sol Max (0%)}. */
	static String modelStatus(Model model, ThinkingLevel level, long contextTokens) {
		StringBuilder status = new StringBuilder(model.name);
		if (level != null && level != ThinkingLevel.OFF) {
			status.append(' ').append(thinkingLabel(level));
		}
		long percent = model.contextWindow > 0
				? Math.max(0, Math.round(100.0 * contextTokens / model.contextWindow))
				: 0;
		return status.append(" (").append(percent).append("%)").toString();
	}

	private static String thinkingLabel(ThinkingLevel level) {
		return switch (level) {
			case OFF -> "Off";
			case MINIMAL -> "Minimal";
			case LOW -> "Low";
			case MEDIUM -> "Medium";
			case HIGH -> "High";
			case XHIGH -> "XHigh";
			case MAX -> "Max";
		};
	}

	/** Context tokens consumed by the most recent successful assistant response. */
	static long contextTokens(List<Message> messages) {
		for (int index = messages.size() - 1; index >= 0; index--) {
			if (messages.get(index) instanceof AssistantMessage assistant
					&& assistant.stopReason != StopReason.ERROR
					&& assistant.stopReason != StopReason.ABORTED) {
				Usage usage = assistant.usage;
				long total = usage.totalTokens > 0
						? usage.totalTokens
						: usage.input + usage.output + usage.cacheRead + usage.cacheWrite;
				if (total > 0) return total;
			}
		}
		return 0;
	}

	/** Abbreviates the home directory to {@code ~}, e.g. {@code ~/xa/coding-agent}. */
	static String displayPath(Path home, Path cwd) {
		Path absolute = cwd.toAbsolutePath().normalize();
		if (home != null && !home.toString().isEmpty()) {
			Path absoluteHome = home.toAbsolutePath().normalize();
			if (absolute.startsWith(absoluteHome)) {
				String relative = absoluteHome.relativize(absolute).toString().replace('\\', '/');
				return relative.isEmpty() ? "~" : "~/" + relative;
			}
		}
		return absolute.toString();
	}

	/** Reads the checked-out branch (or short detached commit) without spawning git. */
	static String gitBranch(Path directory) {
		try {
			for (Path current = directory.toAbsolutePath().normalize(); current != null; current = current.getParent()) {
				Path gitPath = current.resolve(".git");
				if (Files.isRegularFile(gitPath)) {
					// Worktree or submodule: .git is a file pointing at the real git dir.
					String content = Files.readString(gitPath).trim();
					if (!content.startsWith("gitdir:")) return null;
					return readGitHead(current.resolve(content.substring("gitdir:".length()).trim()).normalize());
				}
				if (Files.isDirectory(gitPath)) return readGitHead(gitPath);
			}
		} catch (IOException | java.nio.file.InvalidPathException ignored) {
			// A missing or unreadable repository simply hides the branch segment.
		}
		return null;
	}

	private static String readGitHead(Path gitDir) throws IOException {
		Path head = gitDir.resolve("HEAD");
		if (!Files.isRegularFile(head)) return null;
		String content = Files.readString(head).trim();
		if (content.startsWith("ref:")) {
			String ref = content.substring("ref:".length()).trim();
			return ref.startsWith("refs/heads/") ? ref.substring("refs/heads/".length()) : ref;
		}
		if (content.isEmpty()) return null;
		return content.length() > 7 ? content.substring(0, 7) : content;
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
			case AgentEvent.AgentStart ignored ->
					setActivity(ActivityStatus.active(ActivityStatus.Phase.WAITING_FOR_MODEL, System.nanoTime()));
			case AgentEvent.AgentEnd ignored ->
					setActivity(ActivityStatus.ready(System.nanoTime()));
			case AgentEvent.CompactionStart ignored ->
					setActivity(ActivityStatus.active(ActivityStatus.Phase.COMPACTING, System.nanoTime()));
			case AgentEvent.CompactionEnd ignored -> {
				if (agent != null && agent.state().isStreaming) {
					setActivity(ActivityStatus.active(ActivityStatus.Phase.WAITING_FOR_MODEL, System.nanoTime()));
				} else {
					setActivity(ActivityStatus.ready(System.nanoTime()));
				}
				refreshStatus();
			}
			case AgentEvent.TurnStart ignored ->
					setActivity(ActivityStatus.active(ActivityStatus.Phase.WAITING_FOR_MODEL, System.nanoTime()));
			case AgentEvent.MessageUpdate update -> onMessageUpdate(update.providerEvent());
			case AgentEvent.MessageEnd end -> {
				if (end.message() instanceof AssistantMessage) {
					finishStreamOutput();
					refreshStatus();
				}
			}
			case AgentEvent.AutoRetryStart retry -> {
				setActivity(ActivityStatus.retrying(
						retry.attempt(), retry.maxAttempts(), retry.delayMs(), System.nanoTime()));
				finishStreamOutput();
				terminal.println("\nTransient provider error; retrying in "
						+ formatRetryDelay(retry.delayMs())
						+ " (" + retry.attempt() + "/" + retry.maxAttempts() + "): "
						+ retry.errorMessage());
			}
			case AgentEvent.ToolExecutionStart start -> {
				setActivity(ActivityStatus.active(
						ActivityStatus.Phase.RUNNING_TOOL, start.toolName(), System.nanoTime()));
				finishStreamOutput();
				terminal.println("\n[" + start.toolName() + "] " + toolDescription(start.toolName(), start.arguments()));
			}
			case AgentEvent.ToolExecutionEnd end -> {
				String label = end.result().isError() ? "Error" : "Done";
				terminal.println("  " + label + ": " + toolResultSummary(end.toolName(), end.result()));
			}
			default -> {
				// Turn-end and low-level update events do not change the presentation phase.
			}
		}
	}

	private void onMessageUpdate(AssistantMessageEvent event) {
		updateStreamingActivity(event);
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

	private void updateStreamingActivity(AssistantMessageEvent event) {
		long now = System.nanoTime();
		switch (event) {
			case AssistantMessageEvent.ThinkingStart ignored ->
					setActivity(ActivityStatus.active(ActivityStatus.Phase.REASONING, now));
			case AssistantMessageEvent.TextStart ignored ->
					setActivity(ActivityStatus.active(ActivityStatus.Phase.RESPONDING, now));
			case AssistantMessageEvent.ToolCallStart start -> setActivity(ActivityStatus.active(
					ActivityStatus.Phase.PREPARING_TOOL,
					streamedToolName(start.contentIndex(), start.partial()),
					now));
			case AssistantMessageEvent.ToolCallEnd end -> setActivity(ActivityStatus.active(
					ActivityStatus.Phase.PREPARING_TOOL,
					end.toolCall().name(),
					now));
			default -> {
				// End events retain the current phase until another block or AgentEnd.
			}
		}
	}

	private static String streamedToolName(int contentIndex, AssistantMessage message) {
		if (contentIndex >= 0
				&& contentIndex < message.content.size()
				&& message.content.get(contentIndex) instanceof ToolCall call) {
			return call.name();
		}
		return "";
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

	@Override
	public void close() {
		statusTicker.shutdownNow();
		try {
			statusTicker.awaitTermination(1, TimeUnit.SECONDS);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
	}

	private enum StreamOutput {
		NONE,
		THINKING,
		TEXT
	}

}
