package com.quaxt.claudia;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.quaxt.claudia.ai.json.Json;
import com.quaxt.claudia.agent.AgentEvent;
import com.quaxt.claudia.ai.types.AssistantMessage;
import com.quaxt.claudia.ai.types.AssistantMessageEvent;
import com.quaxt.claudia.ai.types.Message;
import com.quaxt.claudia.ai.types.Model;
import com.quaxt.claudia.ai.types.ModelCost;
import com.quaxt.claudia.ai.types.StopReason;
import com.quaxt.claudia.ai.types.TextContent;
import com.quaxt.claudia.ai.types.ThinkingContent;
import com.quaxt.claudia.ai.types.ThinkingLevel;
import com.quaxt.claudia.ai.types.ToolCall;
import com.quaxt.claudia.ai.types.ToolResultMessage;
import com.quaxt.claudia.cli.ActivityStatus;
import com.quaxt.claudia.cli.Transcript;
import com.quaxt.claudia.cli.TurnDetailsComponent;
import com.quaxt.claudia.tui.FuzzySelector;
import com.quaxt.claudia.tui.Keybindings;
import com.quaxt.claudia.tui.SelectItem;
import com.quaxt.claudia.tui.TerminalStyle;
import com.quaxt.claudia.tui.TuiComponent;
import com.quaxt.claudia.tui.TuiInput;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import com.quaxt.claudia.terminal.Ansi;
import com.quaxt.claudia.terminal.LineEditor;
import com.quaxt.claudia.terminal.ScreenEmulator;
import com.quaxt.claudia.terminal.Terminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.quaxt.claudia.ClaudiaCli.activeActivity;
import static com.quaxt.claudia.ClaudiaCli.activityAccent;
import static com.quaxt.claudia.ClaudiaCli.activityLabel;
import static com.quaxt.claudia.ClaudiaCli.isDynamicActivity;
import static com.quaxt.claudia.ClaudiaCli.readyActivity;
import static com.quaxt.claudia.ClaudiaCli.retryingActivity;
import static com.quaxt.claudia.ClaudiaCli.sameActivity;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@code ClaudiaCli}, grouped by the behavior under test.
 */
class ClaudiaCliTest {

	// Command line

	@Test
	void parsesPerRunCodeLensAliases() {
		try (ClaudiaOperations runtime = new ClaudiaOperations()) {
			ClaudiaCli cli = new ClaudiaCli(runtime);
			assertEquals(0, cli.cliRun(new String[] {"--aliases", ":dev:reporting", "--help"}));
			assertEquals(":dev:reporting", runtime.codeLensAliases());
		}
	}

	@Test
	void rejectsMalformedCodeLensAliases() {
		assertEquals(2, new ClaudiaCli().cliRun(new String[] {"--aliases", "dev", "--help"}));
		assertEquals(2, new ClaudiaCli().cliRun(new String[] {"--aliases", "--help"}));
	}

	// McpSelector

	@Test
	void startsAtTheFirstItemWhenTheServerListIsShorterThanTheViewport() {
		assertEquals(0, ClaudiaCli.mcpSelectorVisibleStart(1, 10, 0));
	}

	@Test
	void keepsTheSelectionCenteredWithinAFullViewport() {
		assertEquals(5, ClaudiaCli.mcpSelectorVisibleStart(20, 10, 10));
		assertEquals(10, ClaudiaCli.mcpSelectorVisibleStart(20, 10, 19));
	}

	// ActivityStatus

	private static final long SECOND = 1_000_000_000L;

	@Test
	void readyIsTheOnlyReadyAccentedPhase() {
		ActivityStatus ready = readyActivity(0);

		assertEquals("● Ready", activityLabel(ready, 20 * SECOND));
		assertEquals(ClaudiaCli.StatusAccent.READY, activityAccent(ready));
		assertFalse(isDynamicActivity(ready));

		for (ActivityStatus.Phase phase : ActivityStatus.Phase.values()) {
			if (phase == ActivityStatus.Phase.READY) continue;
			ActivityStatus status = phase == ActivityStatus.Phase.RETRYING
					? retryingActivity(1, 3, 2_000, 0)
					: activeActivity(phase, 0);
			assertFalse(activityAccent(status) == ClaudiaCli.StatusAccent.READY, phase.toString());
		}
	}

	@Test
	void describesQuietAndStreamingModelPhasesWithElapsedTime() {
		assertEquals(
				"◑ Command: /login · 2s",
				activityLabel(activeActivity(ActivityStatus.Phase.RUNNING_COMMAND, "/login", 0), 2 * SECOND));
		assertEquals(
				"◐ Waiting for model · 12s",
				activityLabel(activeActivity(ActivityStatus.Phase.WAITING_FOR_MODEL, 0), 12 * SECOND));
		assertEquals(
				"◓ Reasoning · 1m05s",
				activityLabel(activeActivity(ActivityStatus.Phase.REASONING, 0), 65 * SECOND));
		assertEquals(
				"◑ Responding · 2s",
				activityLabel(activeActivity(ActivityStatus.Phase.RESPONDING, 0), 2 * SECOND));
		assertEquals(
				"⚙ Tool: shell · 1h01m",
				activityLabel(activeActivity(ActivityStatus.Phase.RUNNING_TOOL, "shell", 0), 3_660 * SECOND));
	}

	@Test
	void retryUsesACountdownThenSaysItIsWaitingForTheModel() {
		ActivityStatus retry = retryingActivity(2, 3, 2_500, 10 * SECOND);

		assertEquals("↻ Retry 2/3 in 3s", activityLabel(retry, 10 * SECOND));
		assertEquals("↻ Retry 2/3 in 1s", activityLabel(retry, 12 * SECOND));
		assertEquals("↻ Retry 2/3 · waiting for model", activityLabel(retry, 13 * SECOND));
		assertEquals(ClaudiaCli.StatusAccent.WARNING, activityAccent(retry));
	}

	@Test
	void repeatedStreamEventsCanRetainTheOriginalPhaseTimer() {
		ActivityStatus first = activeActivity(ActivityStatus.Phase.RESPONDING, 1);
		ActivityStatus repeated = activeActivity(ActivityStatus.Phase.RESPONDING, 2);
		ActivityStatus next = activeActivity(ActivityStatus.Phase.RUNNING_TOOL, "read", 2);

		assertTrue(sameActivity(first, repeated));
		assertFalse(sameActivity(first, next));
	}

	// InteractiveShell

	@Test
	void prefersGpt54AndFallsBackToFirstEnabledCopilotModel() {
		Model fallback = model("claude-sonnet-4.5");
		Model preferred = model("gpt-5.4");

		assertEquals(preferred, ClaudiaCli.preferredCopilotModel(List.of(fallback, preferred)));
		assertEquals(fallback, ClaudiaCli.preferredCopilotModel(List.of(fallback)));
	}

	@Test
	void prefersTerraWhenRestoringChatGptWithoutSavedSettings() {
		Model fallback = model("gpt-5.4");
		Model terra = model("gpt-5.6-terra");

		assertEquals(terra, ClaudiaCli.preferredChatGptModel(List.of(fallback, terra)));
		assertEquals(fallback, ClaudiaCli.preferredChatGptModel(List.of(fallback)));
	}

	@Test
	void defaultsReasoningModelsToMediumThinking() {
		Model reasoning = model("gpt-5.4", true);

		assertEquals(ThinkingLevel.MEDIUM, ClaudiaCli.initialThinkingLevel(reasoning, null));
		assertEquals(ThinkingLevel.HIGH, ClaudiaCli.initialThinkingLevel(reasoning, ThinkingLevel.HIGH));
		assertEquals(ThinkingLevel.OFF, ClaudiaCli.initialThinkingLevel(model("gpt-4.1"), null));
	}

	@Test
	void prepopulatesForkNamesFromTheCurrentSessionName() {
		assertEquals("fork", ClaudiaCli.forkName(null));
		assertEquals("fork", ClaudiaCli.forkName("  "));
		assertEquals("investigation fork", ClaudiaCli.forkName("investigation"));
		assertEquals("investigation fork", ClaudiaCli.forkName("  investigation  "));
	}

	@Test
	void keepsSlashCommandCompletionAndHelpInSync() {
		assertEquals(List.of(
				"/build", "/cd", "/clear", "/compact", "/details", "/exit", "/fork", "/help", "/login", "/logout",
				"/mcp", "/models", "/plan", "/quit", "/resume", "/settings", "/subagents"), ClaudiaCli.slashCommands());

		String help = ClaudiaCli.slashCommandHelp();
		assertTrue(help.contains("/cd"));
		assertTrue(help.contains("/clear"));
		assertTrue(help.contains("/compact"));
		assertTrue(help.contains("/settings"));
		assertTrue(help.contains("Shift-Tab   toggles Plan/Build."));
		assertFalse(help.contains("/quit"));
		List<String> commandLines = help.lines().filter(line -> line.startsWith("/")).toList();
		assertEquals(ClaudiaCli.slashCommands().stream()
				.filter(command -> !command.equals("/quit")).toList(),
				commandLines.stream().map(line -> line.substring(0, 12).strip()).toList());
		for (String line : commandLines) {
			// The command is padded to a 12-character column and the description starts in column 12.
			assertTrue(line.substring(0, 12).matches("/\\w+ +"), line);
			assertTrue(line.substring(12).matches("\\S.*"), line);
		}
		assertTrue(help.contains("/cd         Change the working directory: /cd <directory>."));
	}

	@Test
	void resolvesCdPathsAgainstTheCurrentAgentWorkspace(@TempDir Path workspace) throws Exception {
		Path nested = Files.createDirectories(workspace.resolve("a directory/nested"));
		Path file = Files.writeString(workspace.resolve("not-a-directory"), "contents");

		assertEquals(nested, ClaudiaCli.resolveShellWorkingDirectory(workspace, "a directory/nested"));
		assertEquals(workspace, ClaudiaCli.resolveShellWorkingDirectory(nested, "../.."));
		assertEquals(nested, ClaudiaCli.resolveShellWorkingDirectory(workspace, nested.toString()));
		assertThrows(IllegalArgumentException.class,
				() -> ClaudiaCli.resolveShellWorkingDirectory(workspace, "missing"));
		assertThrows(IllegalArgumentException.class,
				() -> ClaudiaCli.resolveShellWorkingDirectory(workspace, file.getFileName().toString()));
		assertThrows(IllegalArgumentException.class,
				() -> ClaudiaCli.resolveShellWorkingDirectory(workspace, "  "));
	}

	@Test
	void cdPreservesAndMovesThePersistedMainSession(@TempDir Path workspace) throws Exception {
		Path origin = Files.createDirectories(workspace.resolve("origin"));
		Path destination = Files.createDirectories(workspace.resolve("destination"));
		TerminalFixture fixture = terminal();
		try (ClaudiaOperations runtime = new ClaudiaOperations()) {
			Model model = model("faux-1");
			model.api = "faux"; model.provider = "faux"; model.contextWindow = 100_000;
			runtime.applicationPaths(new ClaudiaPaths(workspace.resolve("home")));
			runtime.coreProviders(java.util.Map.of("faux",
					new com.quaxt.claudia.ai.providers.FauxProvider("faux", "faux", List.of(model))));
			ClaudiaCli cli = new ClaudiaCli(runtime);
			cli.newInteractiveTerminal(fixture.terminal(), () -> null, false);
			setCliField(cli, "settings", new ClaudiaOperations.Settings(null, null, ThinkingLevel.OFF, false));
			setCliField(cli, "activity", readyActivity(System.nanoTime()));
			runtime.defaultSessionStore();
			runtime.createSessionRecorder(origin, model.provider, model.id, "cross workspace");
			String sessionId = runtime.state().sessionId();
			var configure = ClaudiaCli.class.getDeclaredMethod(
					"configureShellAgent", Model.class, Path.class, boolean.class, String.class);
			configure.setAccessible(true); configure.invoke(cli, model, origin, true, "cross workspace");
			List<Message> messages = List.of(ClaudiaOperations.userMessage("keep this conversation"));
			runtime.restoreMessages(messages);
			runtime.appendSessionMessages(messages);
			var taskState = runtime.builtInTools(origin, ignored -> {}).stream()
					.filter(tool -> ClaudiaOperations.toolName(tool).equals("task_state")).findFirst().orElseThrow();
			runtime.executeTool(taskState, "add", ClaudiaOperations.jsonObject()
					.put("action", "add_task").put("description", "Preserve me"),
					new com.quaxt.claudia.ai.util.AbortSignal(), ignored -> {});
			try {
				invokeCommand(cli, "/cd " + destination);

				assertEquals(sessionId, runtime.state().sessionId());
				assertEquals(messages, runtime.state().messages());
				assertEquals(1, runtime.taskStateSnapshot().path("tasks").size());
				assertEquals(destination.toAbsolutePath().normalize(), getCliField(cli, "cwd"));
				assertTrue((Boolean) getCliField(cli, "recordingSession"));
				assertEquals(destination.toAbsolutePath().normalize(), runtime.sessionSnapshot(sessionId).cwd);
				assertEquals(List.of(sessionId), runtime.listSessions(destination).stream()
						.map(session -> session.id).toList());
				assertTrue(runtime.listSessions(origin).isEmpty());
				assertTrue(fixture.output().toString(StandardCharsets.UTF_8).contains("Continuing current session"));
			} finally { cli.closeTerminal(); }
		}
	}

	@Test
	void cdPreservesAnInMemoryMainSession(@TempDir Path workspace) throws Exception {
		Path origin = Files.createDirectories(workspace.resolve("origin"));
		Path destination = Files.createDirectories(workspace.resolve("destination"));
		TerminalFixture fixture = terminal();
		try (ClaudiaOperations runtime = new ClaudiaOperations()) {
			Model model = model("faux-1");
			model.api = "faux"; model.provider = "faux"; model.contextWindow = 100_000;
			runtime.applicationPaths(new ClaudiaPaths(workspace.resolve("home")));
			runtime.coreProviders(java.util.Map.of("faux",
					new com.quaxt.claudia.ai.providers.FauxProvider("faux", "faux", List.of(model))));
			ClaudiaCli cli = new ClaudiaCli(runtime);
			cli.newInteractiveTerminal(fixture.terminal(), () -> null, false);
			setCliField(cli, "settings", new ClaudiaOperations.Settings(null, null, ThinkingLevel.OFF, false));
			setCliField(cli, "activity", readyActivity(System.nanoTime()));
			setCliField(cli, "noSession", true);
			var configure = ClaudiaCli.class.getDeclaredMethod(
					"configureShellAgent", Model.class, Path.class, boolean.class, String.class);
			configure.setAccessible(true); configure.invoke(cli, model, origin, false, null);
			List<Message> messages = List.of(ClaudiaOperations.userMessage("keep this in memory"));
			runtime.restoreMessages(messages);
			try {
				invokeCommand(cli, "/cd " + destination);

				assertNull(runtime.state().sessionId());
				assertEquals(messages, runtime.state().messages());
				assertEquals(destination.toAbsolutePath().normalize(), getCliField(cli, "cwd"));
				assertFalse((Boolean) getCliField(cli, "recordingSession"));
			} finally { cli.closeTerminal(); }
		}
	}

	@Test
	void rejectsUnsupportedCdHomeSyntax(@TempDir Path workspace) {
		IllegalArgumentException error = assertThrows(
				IllegalArgumentException.class,
				() -> ClaudiaCli.resolveShellWorkingDirectory(workspace, "~another-user"));

		assertTrue(error.getMessage().contains("~user paths are not supported"));
	}

	@Test
	void cleansIncompleteTurnsWhenResuming() {
		AssistantMessage failed = new AssistantMessage("faux", "faux", "faux-1");
		failed.stopReason = StopReason.ERROR;
		AssistantMessage aborted = new AssistantMessage("faux", "faux", "faux-1");
		aborted.stopReason = StopReason.ABORTED;
		var user = ClaudiaOperations.userMessage("hello");
		var laterUser = ClaudiaOperations.userMessage("retry");
		var orphanedResult = new ToolResultMessage(
				"missing-call",
				"read",
				List.of(new TextContent("result", null)),
				null,
				false,
				System.currentTimeMillis());
		AssistantMessage toolUse = new AssistantMessage("faux", "faux", "faux-1");
		toolUse.stopReason = StopReason.TOOL_USE;
		toolUse.content.add(new ToolCall(
				"call-1", "read", ClaudiaOperations.jsonObject().put("path", "README.md"), null));

		var restored = new ClaudiaOperations().resumableMessages(
				List.of(user, failed, orphanedResult, toolUse, laterUser, aborted));

		assertEquals(4, restored.size());
		assertEquals(user, restored.get(0));
		assertEquals(toolUse, restored.get(1));
		assertEquals(laterUser, restored.get(3));
		ToolResultMessage synthetic = (ToolResultMessage) restored.get(2);
		assertEquals("call-1", synthetic.toolCallId);
		assertTrue(synthetic.isError);
	}

	@Test
	void rebuildsTheVisibleTranscriptWhenResumingASession() {
		Model model = model("gpt-5.4", true);
		AssistantMessage toolUse = new AssistantMessage("faux", "github-copilot", "gpt-5.4");
		toolUse.content.add(new ThinkingContent("Inspect the project\nstep 2\nstep 3\nstep 4\nstep 5", null, false));
		toolUse.content.add(new ToolCall(
				"call-1", "read", ClaudiaOperations.jsonObject().put("path", "README.md"), null));
		AssistantMessage answer = new AssistantMessage("faux", "github-copilot", "gpt-5.4");
		answer.content.add(new TextContent("The project is ready.", null));
		List<Message> messages = List.of(
				ClaudiaOperations.userMessage("Check the project"),
				toolUse,
				new ToolResultMessage(
						"call-1",
						"read",
						List.of(new TextContent("line one\nline two", null)),
						null,
						false,
						System.currentTimeMillis()),
				answer);

		Transcript expanded = new ClaudiaCli().renderSessionScreen(model, messages, false);
		Transcript collapsed = new ClaudiaCli().renderSessionScreen(model, messages, true);
		String visible = expanded.plainText();
		String styled = expanded.rows(200).stream().map(Transcript.Row::text).collect(Collectors.joining("\n"));

		assertTrue(visible.startsWith("Claudia "));
		assertTrue(visible.contains("/clear"));
		assertTrue(visible.contains("\n> Check the project\n"));
		assertTrue(visible.contains("\u25bc Thinking\n    Inspect the project\n    step 2\n    step 3\n    step 4\n    step 5\n"));
		assertTrue(visible.contains("\n\n\u25b6 [read] Reading README.md (from line 1)\n    Done: line one\n          line two\n"), visible);
		assertTrue(visible.contains("The project is ready."));
		assertTrue(collapsed.plainText().contains("\u25b6 Thinking\n    Inspect the project\n    step 2\n    step 3\n    \u2026 2 more lines\n"));
		assertTrue(styled.contains("\u001b[48;5;236m\u001b[K> Check the project\u001b[0m"));
		assertTrue(expanded.rows(200).stream().anyMatch(row -> "call-1".equals(row.toggle())));
	}

	@Test
	void displaysFinalAssistantErrorsEvenAfterEarlierStreamedText() {
		AssistantMessage failed = new AssistantMessage("openai-completions", "github-copilot", "claude-fable-5");
		failed.stopReason = StopReason.ERROR;
		failed.errorMessage = "java.net.ConnectException";

		assertEquals("Error: java.net.ConnectException", ClaudiaCli.finalAssistantOutput(failed, true));
	}

	@Test
	void doesNotRepeatSuccessfulFinalOutputAfterItWasStreamed() {
		AssistantMessage response = new AssistantMessage("openai-completions", "github-copilot", "claude-fable-5");
		response.content.add(new TextContent("Done.", null));

		assertNull(ClaudiaCli.finalAssistantOutput(response, true));
		assertEquals("Done.", ClaudiaCli.finalAssistantOutput(response, false));
	}

	@Test
	void rendersAssistantErrorsAfterPartialContent() {
		Model model = model("gpt-5.4");
		AssistantMessage failed = new AssistantMessage("openai-completions", "github-copilot", "claude-fable-5");
		failed.stopReason = StopReason.ERROR;
		failed.errorMessage = "OpenAI tool call arguments must be a JSON object";
		failed.content.add(new TextContent("Checking the source.", null));
		failed.content.add(new ToolCall(
				"call-1", "read", ClaudiaOperations.jsonObject().put("path", "README.md"), null));

		String screen = new ClaudiaCli().renderSessionScreen(model, List.of(ClaudiaOperations.userMessage("Check it"), failed), false).plainText();

		assertTrue(screen.contains("Checking the source."));
		assertTrue(screen.contains("\u25b6 [read] Reading README.md (from line 1)\n    Running\u2026\n"), screen);
		assertTrue(screen.contains("Error: OpenAI tool call arguments must be a JSON object"));
	}

	@Test
	void describesToolWorkWithoutShorteningIt() {
		var read = ClaudiaOperations.jsonObject()
				.put("path", "src/main/java/com.quaxt.claudia/cli/Main.java")
				.put("offset", 10)
				.put("limit", 20);
		var shell = ClaudiaOperations.jsonObject().put("command", "mvn test");
		String longCommand = "echo " + "x".repeat(400) + "\n  && true";
		var mcp = ClaudiaOperations.jsonObject().put("query", "q".repeat(400));

		assertEquals(
				"Reading src/main/java/com.quaxt.claudia/cli/Main.java (lines 10-29)",
				ClaudiaCli.toolCallDescription("read", read));
		assertEquals("mvn test", ClaudiaCli.toolCallDescription("shell", shell));
		assertEquals("echo " + "x".repeat(400) + " && true",
				ClaudiaCli.toolCallDescription("shell", ClaudiaOperations.jsonObject().put("command", longCommand)));
		assertEquals("{\"query\":\"" + "q".repeat(400) + "\"}", ClaudiaCli.toolCallDescription("remote_search", mcp));
		assertEquals(
				"package works.earendil;\npublic final class Main {}",
				ClaudiaCli.toolResultOutput(
						ClaudiaOperations.toolResultText("package works.earendil;\npublic final class Main {}")));
	}

	@Test
	void toolOutputCannotSendControlSequencesToTheTerminal() {
		// Binary output such as a Mach-O header contains SO (0x0E), which leaves the terminal in its
		// G1 character set so that later line drawing shows as q and x.
		String binary = "\u00cf\u00fa\u00ed\u00fe\u0000\u0007\u000e\u0001 \u000f/usr/lib/dyld\u0000";
		String escapes = "\u001b]0;pwned\u0007\u001b[2Jcleared\u001b(0q\r\u009bdone";
		var shell = ClaudiaOperations.jsonObject().put("command", "printf '\u000e' # \u001b[31mred");

		String binaryOutput = ClaudiaCli.terminalSafeText(
				ClaudiaCli.toolResultOutput(ClaudiaOperations.toolResultText(binary)));
		String escapeOutput = ClaudiaCli.terminalSafeText(
				ClaudiaCli.toolResultOutput(ClaudiaOperations.toolResultText(escapes)));
		String description = ClaudiaCli.toolCallDescription("shell", shell);

		assertEquals("\u00cf\u00fa\u00ed\u00fe /usr/lib/dyld", binaryOutput);
		assertEquals("cleared(0qdone", escapeOutput);
		assertEquals("printf '' # red", description);
		for (String text : List.of(binaryOutput, escapeOutput, description)) {
			assertTrue(text.codePoints().noneMatch(Character::isISOControl), text);
		}
	}

	@Test
	void resumedTranscriptDropsControlCharactersFromSavedMessages() {
		Model model = model("gpt-5.4");
		AssistantMessage toolUse = new AssistantMessage("faux", "github-copilot", "gpt-5.4");
		toolUse.content.add(new TextContent("Reading \u000ethe binary\u001b[0m.", null));
		toolUse.content.add(new ToolCall(
				"call-1", "shell", ClaudiaOperations.jsonObject().put("command", "cat a.out"), null));
		List<Message> messages = List.of(
				ClaudiaOperations.userMessage("Show \u000fit"),
				toolUse,
				new ToolResultMessage(
						"call-1",
						"shell",
						List.of(new TextContent("\u00cf\u00fa\u00ed\u00fe\u000e\u0001/usr/lib/dyld", null)),
						null,
						false,
						System.currentTimeMillis()));

		String plain = new ClaudiaCli().renderSessionScreen(model, messages, false).plainText();

		assertTrue(plain.contains("> Show it"));
		assertTrue(plain.contains("Reading the binary."));
		assertTrue(plain.contains("Done: \u00cf\u00fa\u00ed\u00fe/usr/lib/dyld"));
		assertTrue(plain.codePoints().noneMatch(c -> c != '\n' && Character.isISOControl(c)));
	}

	@Test
	void formatsModelThinkingLevelAndContextUseForTheStatusBar() {
		Model model = new Model();
		model.id = "gpt-5.6-sol";
		model.name = "GPT-5.6 Sol";
		model.api = "openai-responses";
		model.provider = "github-copilot";
		model.baseUrl = "https://example.test";
		model.reasoning = true;
		model.cost = ModelCost.FREE;
		model.contextWindow = 1_000_000;
		model.maxTokens = 1;

		assertEquals("GPT-5.6 Sol Max (0%)", ClaudiaCli.modelStatus(model, ThinkingLevel.MAX, 0));
		assertEquals("GPT-5.6 Sol Medium (25%)", ClaudiaCli.modelStatus(model, ThinkingLevel.MEDIUM, 250_000));
		assertEquals("GPT-5.6 Sol (100%)", ClaudiaCli.modelStatus(model, ThinkingLevel.OFF, 1_000_000));
		assertEquals("GPT-5.6 Sol (0%)", ClaudiaCli.modelStatus(model, null, 0));
	}

	@Test
	void contextTokensComeFromTheLatestSuccessfulAssistantResponse() {
		AssistantMessage first = new AssistantMessage("faux", "faux", "faux-1");
		first.stopReason = StopReason.STOP;
		first.usage.totalTokens = 1_000;
		AssistantMessage second = new AssistantMessage("faux", "faux", "faux-1");
		second.stopReason = StopReason.STOP;
		second.usage.input = 2_000;
		second.usage.output = 500;
		AssistantMessage failed = new AssistantMessage("faux", "faux", "faux-1");
		failed.stopReason = StopReason.ERROR;
		failed.usage.totalTokens = 9_999;

		assertEquals(0, ClaudiaCli.contextTokens(List.of(ClaudiaOperations.userMessage("hi"))));
		assertEquals(1_000, ClaudiaCli.contextTokens(List.of(ClaudiaOperations.userMessage("hi"), first)));
		assertEquals(2_500, ClaudiaCli.contextTokens(List.of(first, ClaudiaOperations.userMessage("more"), second)));
		assertEquals(2_500, ClaudiaCli.contextTokens(List.of(first, second, failed)));
	}

	@Test
	void abbreviatesTheHomeDirectoryInTheStatusBarPath() {
		Path home = Path.of("/Users/dev");
		Path elsewhere = Path.of("/opt/elsewhere");
		String absoluteElsewhere = elsewhere.toAbsolutePath().normalize().toString();

		assertEquals("~/xa/claudia", ClaudiaCli.displayPath(home, Path.of("/Users/dev/xa/claudia")));
		assertEquals("~", ClaudiaCli.displayPath(home, Path.of("/Users/dev")));
		assertEquals(absoluteElsewhere, ClaudiaCli.displayPath(home, elsewhere));
		assertEquals(absoluteElsewhere, ClaudiaCli.displayPath(Path.of(""), elsewhere));
	}

	@Test
	void readsTheGitBranchFromHeadWithoutSpawningGit(@TempDir Path repo) throws Exception {
		assertNull(ClaudiaCli.gitBranch(repo));

		Path gitDir = Files.createDirectories(repo.resolve(".git"));
		Files.writeString(gitDir.resolve("HEAD"), "ref: refs/heads/main\n");
		assertEquals("main", ClaudiaCli.gitBranch(repo));

		Path nested = Files.createDirectories(repo.resolve("src/deep"));
		assertEquals("main", ClaudiaCli.gitBranch(nested));

		Files.writeString(gitDir.resolve("HEAD"), "0123456789abcdef0123456789abcdef01234567\n");
		assertEquals("0123456", ClaudiaCli.gitBranch(repo));
	}

	@Test
	void readsTheGitBranchThroughAWorktreeGitFile(@TempDir Path root) throws Exception {
		Path gitDir = Files.createDirectories(root.resolve("main-checkout/.git/worktrees/feature"));
		Files.writeString(gitDir.resolve("HEAD"), "ref: refs/heads/feature-branch\n");
		Path worktree = Files.createDirectories(root.resolve("feature"));
		Files.writeString(worktree.resolve(".git"), "gitdir: " + gitDir + "\n");

		assertEquals("feature-branch", ClaudiaCli.gitBranch(worktree));
	}

	private static Model model(String id) {
		return model(id, false);
	}

	private static Model model(String id, boolean reasoning) {
		Model model = new Model();
		model.id = id;
		model.name = id;
		model.api = "openai-responses";
		model.provider = "github-copilot";
		model.baseUrl = "https://example.test";
		model.reasoning = reasoning;
		model.cost = ModelCost.FREE;
		model.contextWindow = 1;
		model.maxTokens = 1;
		return model;
	}

	// RpcMode

	@Test
	synchronized void respondsToJsonlStateCommand() throws Exception {
		var originalInput = System.in;
		var originalOutput = System.out;
		var captured = new ByteArrayOutputStream();
		try {
			System.setIn(new ByteArrayInputStream("{\"id\":\"state-1\",\"type\":\"get_state\"}\n".getBytes(StandardCharsets.UTF_8)));
			System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));

			assertEquals(0, new ClaudiaCli().cliRun(new String[] {"--mode", "rpc", "--model", "anthropic/claude-haiku-4-5", "--no-session"}));
		} finally {
			System.setIn(originalInput);
			System.setOut(originalOutput);
		}

		var response = Json.MAPPER.readTree(captured.toString(StandardCharsets.UTF_8));
		assertEquals("response", response.path("type").asText());
		assertEquals("state-1", response.path("id").asText());
		assertTrue(response.path("success").asBoolean());
		assertEquals("anthropic", response.path("data").path("model").path("provider").asText());
	}

	// TurnDetailsComponent

	@Test
	void rendersThinkingAndKeepsToolResultsCollapsedInitially() {
		TurnDetailsComponent component = details(false);

		String rendered = plain(ClaudiaCli.renderTurnDetails(component, 100, 30));

		assertEquals(3, component.sections.size());
		assertEquals(TurnDetailsComponent.Kind.THINKING, component.sections.get(0).kind);
		assertTrue(component.sections.get(0).expanded);
		assertFalse(component.sections.get(1).expanded);
		assertTrue(rendered.contains("Inspect the repository"));
		assertTrue(rendered.contains("read  Reading README.md"));
		assertFalse(rendered.contains("secret read output"));
	}

	@Test
	void expandsOneSelectedStepAndTogglesKindsGlobally() {
		TurnDetailsComponent component = details(false);
		ClaudiaCli.renderTurnDetails(component, 100, 30);

		new ClaudiaCli().handleTurnDetailsInput(component, ClaudiaCli.key(TuiInput.KeyType.DOWN));
		new ClaudiaCli().handleTurnDetailsInput(component, ClaudiaCli.key(TuiInput.KeyType.ENTER));
		assertTrue(component.sections.get(1).expanded);
		assertFalse(component.sections.get(2).expanded);
		assertTrue(plain(ClaudiaCli.renderTurnDetails(component, 100, 30)).contains("secret read output"));

		new ClaudiaCli().handleTurnDetailsInput(component, ClaudiaCli.key(TuiInput.KeyType.EXPAND_TOOLS));
		assertTrue(component.sections.get(1).expanded);
		assertTrue(component.sections.get(2).expanded);
		new ClaudiaCli().handleTurnDetailsInput(component, ClaudiaCli.key(TuiInput.KeyType.EXPAND_TOOLS));
		assertFalse(component.sections.get(1).expanded);
		assertFalse(component.sections.get(2).expanded);

		new ClaudiaCli().handleTurnDetailsInput(component, ClaudiaCli.key(TuiInput.KeyType.TOGGLE_THINKING));
		assertFalse(component.sections.get(0).expanded);
		assertTrue(component.thinkingHidden);
	}

	@Test
	void scrollsWithinAnExpandedLongStep() {
		AssistantMessage assistant = new AssistantMessage("faux", "faux", "faux-1");
		assistant.content.add(new ThinkingContent(String.join("\n", IntStream.range(0, 30)
				.mapToObj(index -> "reasoning line " + index)
				.toList()), null, false));
		TurnDetailsComponent component = ClaudiaCli.turnDetailsForLatestTurn(
				List.of(ClaudiaOperations.userMessage("inspect"), assistant), false);

		String firstPage = plain(ClaudiaCli.renderTurnDetails(component, 60, 8));
		new ClaudiaCli().handleTurnDetailsInput(component, ClaudiaCli.key(TuiInput.KeyType.PAGE_DOWN));
		String secondPage = plain(ClaudiaCli.renderTurnDetails(component, 60, 8));

		assertTrue(firstPage.contains("reasoning line 0"));
		assertFalse(secondPage.contains("reasoning line 0"));
		assertTrue(secondPage.contains("reasoning line"));
	}

	@Test
	void onlyInspectsTheLatestUserTurnAndSanitizesTerminalControls() {
		AssistantMessage old = new AssistantMessage("faux", "faux", "old");
		old.content.add(new ThinkingContent("old thought", null, false));
		AssistantMessage latest = new AssistantMessage("faux", "faux", "new");
		latest.content.add(new ThinkingContent("new \u001b[31mthought\u001b[0m", null, false));
		List<Message> messages = List.of(ClaudiaOperations.userMessage("old"), old, ClaudiaOperations.userMessage("new"), latest);

		TurnDetailsComponent component = ClaudiaCli.turnDetailsForLatestTurn(messages, false);
		String rendered = plain(ClaudiaCli.renderTurnDetails(component, 80, 20));

		assertEquals(1, component.sections.size());
		assertTrue(rendered.contains("new thought"));
		assertFalse(rendered.contains("old thought"));
		assertFalse(rendered.contains("\u001b"));
	}

	private static TurnDetailsComponent details(boolean hideThinking) {
		AssistantMessage assistant = new AssistantMessage("faux", "faux", "faux-1");
		assistant.content.add(new ThinkingContent("Inspect the repository\nThen choose files", null, false));
		assistant.content.add(new ToolCall(
				"read-1", "read", ClaudiaOperations.jsonObject().put("path", "README.md"), null));
		assistant.content.add(new ToolCall(
				"bash-1", "bash", ClaudiaOperations.jsonObject().put("command", "mvn test"), null));
		return ClaudiaCli.turnDetailsForLatestTurn(
				List.of(
						ClaudiaOperations.userMessage("inspect"),
						assistant,
						new ToolResultMessage(
								"read-1", "read", List.of(new TextContent("secret read output", null)), null, false,
								System.currentTimeMillis()),
						new ToolResultMessage(
								"bash-1", "bash", List.of(new TextContent("tests passed", null)), null, false,
								System.currentTimeMillis())),
				hideThinking);
	}

	private static String plain(List<String> lines) {
		return ClaudiaCli.stripAnsi(String.join("\n", lines));
	}

	// FuzzySelector

	@Test
	void filtersAcrossMultipleTokensAndSelectsTheBestMatch() {
		List<SelectItem<String>> items = List.of(
				new SelectItem<>("sonnet", "claude-sonnet-4.5", "[github-copilot] Claude Sonnet",
						"claude-sonnet-4.5 [github-copilot] Claude Sonnet"),
				new SelectItem<>("terra", "gpt-5.6-terra", "[github-copilot] GPT 5.6 Terra",
						"gpt-5.6-terra [github-copilot] GPT 5.6 Terra"),
				new SelectItem<>("gpt54", "gpt-5.4", "[github-copilot] GPT 5.4",
						"gpt-5.4 [github-copilot] GPT 5.4"));
		FuzzySelector<String> selector = ClaudiaCli.fuzzySelector("Models", items, 2, true);

		ClaudiaCli.handleFuzzySelectorInput(
				selector, new TuiInput.Key(TuiInput.KeyType.PASTE, "terra copilot"));

		assertEquals("terra copilot", selector.query.toString());
		assertEquals(List.of("terra"), selector.filteredItems.stream().map(item -> item.value).toList());
		ClaudiaCli.handleFuzzySelectorInput(selector, ClaudiaCli.key(TuiInput.KeyType.ENTER));
		assertTrue(selector.complete);
		assertEquals("terra", selector.result);
	}

	@Test
	void restoresCurrentSelectionWhenSearchIsClearedAndSupportsCancel() {
		List<SelectItem<String>> items = List.of(
				new SelectItem<>("one", "One", "", "One"),
				new SelectItem<>("two", "Two", "", "Two"),
				new SelectItem<>("three", "Three", "", "Three"));
		FuzzySelector<String> selector = ClaudiaCli.fuzzySelector("Options", items, 1, true);

		ClaudiaCli.handleFuzzySelectorInput(
				selector, new TuiInput.Key(TuiInput.KeyType.CHARACTER, "t"));
		assertEquals(0, selector.selectedIndex);
		ClaudiaCli.handleFuzzySelectorInput(selector, ClaudiaCli.key(TuiInput.KeyType.CLEAR));
		assertEquals(1, selector.selectedIndex);
		ClaudiaCli.handleFuzzySelectorInput(selector, ClaudiaCli.key(TuiInput.KeyType.ESCAPE));

		assertTrue(selector.complete);
		assertNull(selector.result);
	}

	@Test
	void restoresFirstSelectionWhenBackspaceClearsSearchWithoutCurrentItem() {
		List<SelectItem<String>> items = List.of(
				new SelectItem<>("one", "One", "", "One"),
				new SelectItem<>("two", "Two", "", "Two"));
		FuzzySelector<String> selector = ClaudiaCli.fuzzySelector("Options", items, -1, true);

		ClaudiaCli.handleFuzzySelectorInput(
				selector, new TuiInput.Key(TuiInput.KeyType.CHARACTER, "o"));
		ClaudiaCli.handleFuzzySelectorInput(
				selector, ClaudiaCli.key(TuiInput.KeyType.BACKSPACE));

		assertEquals("", selector.query.toString());
		assertEquals(items, selector.filteredItems);
		assertEquals(0, selector.selectedIndex);
		assertNull(selector.currentItem);
	}

	@Test
	void rendersAWindowedListWithCurrentAndNavigationHints() {
		List<SelectItem<Integer>> items = java.util.stream.IntStream.range(0, 20)
				.mapToObj(index -> new SelectItem<>(index, "Model " + index, "Description " + index,
						"Model " + index + " Description " + index))
				.toList();
		FuzzySelector<Integer> selector = ClaudiaCli.fuzzySelector("Models", items, 12, true);

		String frame = String.join("\n", ClaudiaCli.renderFuzzySelector(
				selector, 80, 12));

		assertTrue(frame.contains("Models"));
		assertTrue(frame.contains("Model 12 *"));
		assertTrue(frame.contains("13/20"));
		assertTrue(frame.contains("Type to filter"));
		assertFalse(frame.contains("Model 0  Description 0"));
	}

	// InteractiveTerminal

	@Test
	void escapeInterruptsARunningOperationAndRestoresTerminalMode() throws Exception {
		TerminalFixture fixture = terminal();
		Terminal terminal = fixture.terminal();
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch interrupted = new CountDownLatch(1);
		CountDownLatch inputMayFinish = new CountDownLatch(1);

		ClaudiaCli interactive = newInteractiveTerminal(
				terminal, () -> null, false);
		try {
			Thread input = Thread.ofVirtual().start(() -> {
				try {
					started.await();
					fixture.input().write(0x1b);
					fixture.input().flush();
					inputMayFinish.await();
				} catch (Exception error) {
					throw new AssertionError(error);
				}
			});

			String result;
			try {
				result = assertTimeoutPreemptively(
						Duration.ofSeconds(5),
						() -> interactive.runInterruptibly(() -> {
									assertTrue(terminal.mode().raw());
									started.countDown();
									assertTrue(interrupted.await(5, TimeUnit.SECONDS));
									return "stopped";
								}, interrupted::countDown));
			} finally {
				inputMayFinish.countDown();
			}
			input.join();

			assertEquals("stopped", result);
			assertFalse(terminal.mode().raw());
		} finally {
			interactive.closeTerminal();
		}
	}

	@Test
	void suppliesFallbackDimensionsWhenTheTerminalReportsZeroSize() throws Exception {
		TerminalFixture fixture = terminal();
		fixture.terminal().setSize(0, 0);

		ClaudiaCli ignored = newInteractiveTerminal(
				fixture.terminal(), () -> null, false);
		try {
			assertEquals(80, fixture.terminal().columns());
			assertEquals(24, fixture.terminal().rows());
		} finally {
			ignored.closeTerminal();
		}
	}

	@Test
	void fillsThePromptLineWithADarkGreyBackground() throws Exception {
		TerminalFixture fixture = terminal();

		ClaudiaCli interactive = newInteractiveTerminal(
				fixture.terminal(), () -> null, false);
		try {
			fixture.input().write("hello\r".getBytes(StandardCharsets.UTF_8));
			fixture.input().flush();

			String line = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> interactive.readLine("\n> "));

			assertEquals("hello", line);
			assertTrue(fixture.output().toString(StandardCharsets.UTF_8)
					.contains("\u001b[48;5;236m\u001b[K> hello"));
		} finally {
			interactive.closeTerminal();
		}
	}

	@Test
	void submitsAPrepopulatedInputBuffer() throws Exception {
		TerminalFixture fixture = terminal();

		ClaudiaCli interactive = newInteractiveTerminal(
				fixture.terminal(), () -> null, false);
		try {
			fixture.input().write("\r".getBytes(StandardCharsets.UTF_8));
			fixture.input().flush();

			String line = assertTimeoutPreemptively(
					Duration.ofSeconds(5), () -> interactive.readLine("> ", "current session fork"));

			assertEquals("current session fork", line);
			assertTrue(fixture.output().toString(StandardCharsets.UTF_8).contains("> current session fork"));
		} finally {
			interactive.closeTerminal();
		}
	}

	@Test
	void slashCommandPanelFiltersNavigatesAndInsertsWithoutSubmitting() throws Exception {
		TerminalFixture fixture = terminal();

		ClaudiaCli interactive = newInteractiveTerminal(
				fixture.terminal(), () -> null, false);
		try {
			fixture.input().write(
					"/\u001b[B\r\r".getBytes(StandardCharsets.UTF_8));
			fixture.input().flush();

			String line = assertTimeoutPreemptively(
					Duration.ofSeconds(5),
					() -> interactive.readLine("> ", List.of("/models", "/help", "/compact", "/details", "/exit")));

			assertEquals("/details", line);
			String written = fixture.output().toString(StandardCharsets.UTF_8);
			assertTrue(written.contains("╭"));
			assertTrue(written.contains("/compact"));
			assertTrue(written.contains("/details"));
			// A terminal left shifted to G1 or DEC line drawing by earlier output must be
			// returned to G0/ASCII before the panel is drawn.
			int reset = written.indexOf(ClaudiaCli.RESET_CHARACTER_SET);
			assertTrue(reset >= 0);
			assertTrue(reset < written.indexOf("╭"));
		} finally {
			interactive.closeTerminal();
		}
	}

	@Test
	void shiftEnterVariantsInsertNewlinesAndEnterSubmitsThePrompt() throws Exception {
		TerminalFixture fixture = terminal();

		ClaudiaCli interactive = newInteractiveTerminal(
				fixture.terminal(), () -> null, false);
		try {
			fixture.input().write(
					"first\u001b[13;2usecond\u001b[27;2;13~third\nfourth\r".getBytes(StandardCharsets.UTF_8));
			fixture.input().flush();

			String line = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> interactive.readLine("> "));

			assertEquals("first\nsecond\nthird\nfourth", line);
		} finally {
			interactive.closeTerminal();
		}
	}

    private static final String KEYBOARD_PUSH = "\u001b[>1u";
    private static final String KEYBOARD_POP = "\u001b[<1u";

    @Test
    void scopesEnhancedKeyboardReportingToEveryReadAndCleansUpAllExitPaths() throws Exception {
        TerminalFixture fixture = terminal();
        ClaudiaCli cli = newInteractiveTerminal(fixture.terminal(), () -> null, false);
        try {
            assertFalse(fixture.output().toString(StandardCharsets.UTF_8).contains(KEYBOARD_PUSH));
            String[] inputs = {"first\u001b[13;2usecond\r", "\u001b[99;5u", "\u001b[100;5u", "\u001b[111;5u"};
            cli.bindAppAction("expandTools", () -> { throw new IllegalStateException("widget failed"); });
            for (int i = 0; i < inputs.length; i++) {
                fixture.output().reset();
                fixture.input().write(inputs[i].getBytes(StandardCharsets.UTF_8));
                fixture.input().flush();
                int exit = i;
                assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                    switch (exit) {
                        case 0 -> assertEquals("first\nsecond", cli.readLine("> "));
                        case 1 -> assertEquals("", cli.readLine("> "));
                        case 2 -> assertNull(cli.readLine("> "));
                        case 3 -> assertThrows(IllegalStateException.class, () -> cli.readLine("> "));
                    }
                });
                String written = fixture.output().toString(StandardCharsets.UTF_8);
                assertEquals(1, count(written, KEYBOARD_PUSH));
                assertEquals(1, count(written, KEYBOARD_POP));
                assertTrue(written.indexOf(KEYBOARD_PUSH) < written.indexOf("> "));
                assertTrue(written.indexOf(KEYBOARD_PUSH) < written.indexOf(KEYBOARD_POP));
            }
        } finally { cli.closeTerminal(); }
    }

    @Test
    void doesNotEnableEnhancedReportingOnDumbTerminals() throws Exception {
        for (String type : List.of(Terminal.TYPE_DUMB, Terminal.TYPE_DUMB_COLOR)) {
            TerminalFixture fixture = terminal(type);
            ClaudiaCli cli = newInteractiveTerminal(fixture.terminal(), () -> null, false);
            try {
                fixture.input().write("hello\r".getBytes(StandardCharsets.UTF_8));
                fixture.input().flush();
                assertEquals("hello", assertTimeoutPreemptively(Duration.ofSeconds(5), () -> cli.readLine("> ")));
                String written = fixture.output().toString(StandardCharsets.UTF_8);
                assertFalse(written.contains(KEYBOARD_PUSH));
                assertFalse(written.contains(KEYBOARD_POP));
            } finally { cli.closeTerminal(); }
        }
    }

    @Test
    void enhancedControlsUseEditorAndApplicationBindings() throws Exception {
        TerminalFixture fixture = terminal();
        ClaudiaCli cli = newInteractiveTerminal(fixture.terminal(), () -> null, false);
        var actions = new java.util.ArrayList<String>();
        try {
            cli.bindAppAction("expandTools", () -> actions.add("details"));
            cli.bindAppAction("toggleThinking", () -> actions.add("thinking"));
            cli.bindAppAction("interrupt", () -> actions.add("escape"));
            for (String submit : List.of("\r", "\u001b[13u", "\u001b[13;1u", "\u001b[109;5u")) {
                fixture.input().write(("discard\u001b[97;5u\u001b[107;5u" // Ctrl-A, Ctrl-K
                        + "abX\u001b[127uY\u001b[127;1u" // Backspace, both forms
                        + "\u001b[111;5u\u001b[116;5u\u001b[27u\u001b[27;1u"
                        + "\u001b[9u\u001b[9;1u" // Tab has no prompt binding and inserts nothing
                        + "\u001b[13;2ucd\u001b[13;5uef" + submit).getBytes(StandardCharsets.UTF_8));
                fixture.input().flush();
                assertEquals("ab\ncd\nef", assertTimeoutPreemptively(Duration.ofSeconds(5), () -> cli.readLine("> ")));
                assertEquals(List.of("details", "thinking", "escape", "escape"), actions);
                actions.clear();
            }
        } finally { cli.closeTerminal(); }
    }

    @Test
    void enhancedAltShortcutsStillMoveByWord() throws Exception {
        TerminalFixture fixture = terminal();
        ClaudiaCli cli = newInteractiveTerminal(fixture.terminal(), () -> null, false);
        try {
            fixture.input().write("one two\u001b[98;3uX\u001b[102;3uY\r".getBytes(StandardCharsets.UTF_8));
            fixture.input().flush();
            assertEquals("one XtwoY", assertTimeoutPreemptively(Duration.ofSeconds(5), () -> cli.readLine("> ")));
        } finally { cli.closeTerminal(); }
    }

    @Test
    void nestedSelectorFailureRestoresThenPopsEditorReporting() throws Exception {
        TerminalFixture fixture = terminal();
        ClaudiaCli cli = newInteractiveTerminal(fixture.terminal(), () -> null, false);
        try {
            cli.bindAppAction("expandTools", () -> {
                try {
                    cli.runComponent(new TuiComponent<>(frame -> {
                        String written = fixture.output().toString(StandardCharsets.UTF_8);
                        assertEquals(1, count(written, KEYBOARD_PUSH));
                        assertEquals(1, count(written, KEYBOARD_POP));
                        throw new IllegalStateException("render failed");
                    }, input -> {}, () -> false, () -> null));
                } catch (java.io.IOException error) { throw new AssertionError(error); }
            });
            fixture.input().write("draft\u001b[111;5u".getBytes(StandardCharsets.UTF_8));
            fixture.input().flush();
            assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                    assertThrows(IllegalStateException.class, () -> cli.readLine("> ")));
            String written = fixture.output().toString(StandardCharsets.UTF_8);
            assertEquals(2, count(written, KEYBOARD_PUSH));
            assertEquals(2, count(written, KEYBOARD_POP));
            assertTrue(written.indexOf(KEYBOARD_POP) < written.indexOf("\u001b[?1049h"));
            assertTrue(written.lastIndexOf(KEYBOARD_PUSH) > written.lastIndexOf("\u001b[?1049l"));
            assertTrue(written.lastIndexOf(KEYBOARD_POP) > written.lastIndexOf(KEYBOARD_PUSH));
        } finally { cli.closeTerminal(); }
    }

    @Test
    void selectorsNormalizeQueuedEnhancedControls() {
        for (String legacy : List.of("\u0001", "\u0003", "\u0004", "\u000e", "\u000f", "\u0010", "\u0014", "\u0015", "\u001a")) {
            int letter = legacy.charAt(0) + 96;
            assertEquals(ClaudiaCli.parseInputSequence(legacy),
                    ClaudiaCli.parseInputSequence("\u001b[" + letter + ";5u"));
        }
        for (int code : new int[] {9, 13, 27, 127}) {
            for (String modifier : List.of("", ";1")) {
                assertEquals(ClaudiaCli.parseInputSequence(Character.toString(code)),
                        ClaudiaCli.parseInputSequence("\u001b[" + code + modifier + "u"));
            }
        }
    }

	@Test
	void bracketedPasteKeepsMultilineTextUntilEnter() throws Exception {
		TerminalFixture fixture = terminal();

		ClaudiaCli interactive = newInteractiveTerminal(
				fixture.terminal(), () -> null, false);
		try {
			fixture.input().write(
					"\u001b[200~first\r\nsecond\u001b[201~\r".getBytes(StandardCharsets.UTF_8));
			fixture.input().flush();

			String line = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> interactive.readLine("> "));

			assertEquals("first\nsecond", line);
		} finally {
			interactive.closeTerminal();
		}
	}

	@Test
	void unbracketedCrLfPasteDoesNotSubmitAtTheLineBreak() throws Exception {
		TerminalFixture fixture = terminal();

		ClaudiaCli interactive = newInteractiveTerminal(
				fixture.terminal(), () -> null, false);
		try {
			fixture.input().write("first\r\nsecond\r".getBytes(StandardCharsets.UTF_8));
			fixture.input().flush();

			String line = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> interactive.readLine("> "));

			assertEquals("first\nsecond", line);
		} finally {
			interactive.closeTerminal();
		}
	}

	@Test
	void unbracketedMultilinePasteIsNotMistakenForACommandSelection() throws Exception {
		TerminalFixture fixture = terminal();

		ClaudiaCli interactive = newInteractiveTerminal(
				fixture.terminal(), () -> null, false);
		try {
			fixture.input().write("/help\rsecond\r".getBytes(StandardCharsets.UTF_8));
			fixture.input().flush();

			String line = assertTimeoutPreemptively(
					Duration.ofSeconds(5),
					() -> interactive.readLine("> ", List.of("/help", "/models")));

			assertEquals("/help\nsecond", line);
		} finally {
			interactive.closeTerminal();
		}
	}

	@Test
	void restoresLineEditorScreenAndInputBufferAfterSuspend() throws Exception {
		TerminalFixture fixture = terminal();
		Terminal terminal = fixture.terminal();
		AtomicBoolean suspended = new AtomicBoolean();

		ClaudiaCli interactive = newInteractiveTerminal(
				terminal,
				() -> {
					assertFalse(terminal.mode().raw());
					assertFalse(terminal.mouseTracking(), "The shell never receives mouse reports");
                    String written = fixture.output().toString(StandardCharsets.UTF_8);
                    assertEquals(1, count(written, KEYBOARD_PUSH));
                    assertEquals(1, count(written, KEYBOARD_POP));
					suspended.set(true);
					fixture.output().writeBytes("shell activity\n".getBytes(StandardCharsets.UTF_8));
					fixture.input().write("d\r".getBytes(StandardCharsets.UTF_8));
					fixture.input().flush();
					return null;
				},
				true);
		try {
			interactive.println("conversation before suspend");
			fixture.input().write("abc\u001b[122;5u".getBytes(StandardCharsets.UTF_8));
			fixture.input().flush();
			String line = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> interactive.readLine("> "));

			assertEquals("abcd", line);
			assertTrue(suspended.get());
			String resumed = fixture.output().toString(StandardCharsets.UTF_8);
			assertTrue(resumed.lastIndexOf(Ansi.MOUSE_ON) > resumed.indexOf("shell activity"),
					"Mouse reporting resumes with the prompt");
			String written = fixture.output().toString(StandardCharsets.UTF_8);
            assertEquals(2, count(written, KEYBOARD_PUSH));
            assertEquals(2, count(written, KEYBOARD_POP));
			int shellActivity = written.indexOf("shell activity");
			int redraw = written.indexOf("\u001b[2J\u001b[H\u001b[3J", shellActivity);
			assertTrue(redraw > shellActivity);
			assertTrue(written.indexOf("conversation before suspend", redraw) > redraw);
			assertTrue(written.indexOf("abc", redraw) > redraw);
		} finally {
			interactive.closeTerminal();
		}
	}

	@Test
	void redrawsTheTrackedScreenAfterAnExternalContinueSignal() throws Exception {
		TerminalFixture fixture = terminal();

		ClaudiaCli interactive = newInteractiveTerminal(
				fixture.terminal(), () -> null, true);
		try {
			interactive.println("conversation before external suspend");
			fixture.output().writeBytes("shell output after external suspend\n".getBytes(StandardCharsets.UTF_8));

			fixture.terminal().raise(Terminal.Signal.CONT);

			String written = fixture.output().toString(StandardCharsets.UTF_8);
			int shellOutput = written.indexOf("shell output after external suspend");
			int redraw = written.indexOf("\u001b[2J\u001b[H\u001b[3J", shellOutput);
			assertTrue(redraw > shellOutput);
			assertTrue(written.indexOf("conversation before external suspend", redraw) > redraw);
		} finally {
			interactive.closeTerminal();
		}
	}

	@Test
	void replacesTheTrackedMainScreenDocument() throws Exception {
		TerminalFixture fixture = terminal();

		ClaudiaCli interactive = newInteractiveTerminal(
				fixture.terminal(),
				() -> {
					fixture.input().write('\r');
					fixture.input().flush();
					return null;
				},
				true);
		try {
			interactive.println("discarded session");
			interactive.replaceScreen("restored session\n");
			fixture.input().write(0x1a);
			fixture.input().flush();

			assertTimeoutPreemptively(Duration.ofSeconds(5), () -> interactive.readLine("> "));

			String written = fixture.output().toString(StandardCharsets.UTF_8);
			String finalFrame = written.substring(written.lastIndexOf("\u001b[2J\u001b[H\u001b[3J"));
			assertTrue(finalFrame.contains("restored session"));
			assertFalse(finalFrame.contains("discarded session"));
		} finally {
			interactive.closeTerminal();
		}
	}

	@Test
	void invokesConfiguredApplicationActionsWithoutLosingInput() throws Exception {
		TerminalFixture fixture = terminal();
		AtomicBoolean invoked = new AtomicBoolean();

		ClaudiaCli interactive = newInteractiveTerminal(
				fixture.terminal(), () -> null, false);
		try {
			interactive.bindAppAction("expandTools", () -> {
				invoked.set(true);
				try {
					interactive.runComponent(immediateComponent());
				} catch (java.io.IOException error) {
					throw new AssertionError(error);
				}
				interactive.printAbove("details opened");
			});
			fixture.input().write("ab\u000fcd\r".getBytes(StandardCharsets.UTF_8));
			fixture.input().flush();

			String line = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> interactive.readLine("> "));

			assertEquals("abcd", line);
			assertTrue(invoked.get());
			assertTrue(fixture.output().toString(StandardCharsets.UTF_8).contains("details opened"));
		} finally {
			interactive.closeTerminal();
		}
	}

	@Test
	void restoresShellModeWhenNestedFullScreenIsSuspendedFromTheLineEditor() throws Exception {
		TerminalFixture fixture = terminal();
		Terminal terminal = fixture.terminal();
		AtomicBoolean suspended = new AtomicBoolean();

		ClaudiaCli interactive = newInteractiveTerminal(
				terminal,
				() -> {
					assertFalse(terminal.mode().raw());
                    String written = fixture.output().toString(StandardCharsets.UTF_8);
                    assertEquals(1, count(written, KEYBOARD_PUSH));
                    assertEquals(1, count(written, KEYBOARD_POP));
					suspended.set(true);
					fixture.output().writeBytes("nested shell activity\n".getBytes(StandardCharsets.UTF_8));
					fixture.input().write("\rcd\r".getBytes(StandardCharsets.UTF_8));
					fixture.input().flush();
					return null;
				},
				true);
		try {
			interactive.println("conversation behind nested selector");
			interactive.bindAppAction("expandTools", () -> {
				try {
					interactive.runComponent(ClaudiaCli.fuzzySelectorComponent(ClaudiaCli.fuzzySelector(
									"Details", List.of(new SelectItem<>("done", "Done", "", "Done")), 0, false)));
				} catch (java.io.IOException error) {
					throw new AssertionError(error);
				}
			});
			fixture.input().write("ab\u001b[111;5u\u001b[122;5u".getBytes(StandardCharsets.UTF_8));
			fixture.input().flush();

			String line = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> interactive.readLine("> "));

			assertEquals("abcd", line);
			assertTrue(suspended.get());
			String written = fixture.output().toString(StandardCharsets.UTF_8);
            assertEquals(2, count(written, KEYBOARD_PUSH));
            assertEquals(2, count(written, KEYBOARD_POP));
            assertTrue(written.indexOf(KEYBOARD_POP) < written.indexOf("\u001b[?1049h"));
            assertTrue(written.lastIndexOf(KEYBOARD_PUSH) > written.lastIndexOf("\u001b[?1049l"));
			int shellActivity = written.indexOf("nested shell activity");
			int redraw = written.indexOf("\u001b[2J\u001b[H\u001b[3J", shellActivity);
			assertTrue(redraw > shellActivity);
			assertTrue(written.indexOf("conversation behind nested selector", redraw) > redraw);
		} finally {
			interactive.closeTerminal();
		}
	}

	@Test
	void restoresFullScreenStateAroundSuspendAndContinuesTheSelector() throws Exception {
		TerminalFixture fixture = terminal();
		Terminal terminal = fixture.terminal();
		AtomicBoolean suspended = new AtomicBoolean();

		ClaudiaCli interactive = newInteractiveTerminal(
				terminal,
				() -> {
					assertFalse(terminal.mode().raw());
					suspended.set(true);
					fixture.output().writeBytes("shell activity in full screen\n".getBytes(StandardCharsets.UTF_8));
					fixture.input().write('\r');
					fixture.input().flush();
					return null;
				},
				true);
		try {
			interactive.println("conversation behind selector");
			fixture.input().write(0x1a);
			fixture.input().flush();
			String selected = assertTimeoutPreemptively(
					Duration.ofSeconds(5),
					() -> interactive.runComponent(ClaudiaCli.fuzzySelectorComponent(ClaudiaCli.fuzzySelector(
									"Models",
									List.of(new SelectItem<>("gpt", "gpt-5.6-terra", "", "gpt-5.6-terra")),
									0,
									true))));

			assertEquals("gpt", selected);
			assertTrue(suspended.get());
			String written = fixture.output().toString(StandardCharsets.UTF_8);
			assertTrue(count(written, "\u001b[?1049h") >= 2);
			assertTrue(count(written, "\u001b[?1049l") >= 2);
			int shellActivity = written.indexOf("shell activity in full screen");
			int mainScreenRedraw = written.indexOf("\u001b[2J\u001b[H\u001b[3J", shellActivity);
			assertTrue(mainScreenRedraw > shellActivity);
			assertTrue(written.indexOf("conversation behind selector", mainScreenRedraw) > mainScreenRedraw);
		} finally {
			interactive.closeTerminal();
		}
	}

	@Test
	void alignsStatusBarSegmentsToTheFullTerminalWidth() {
		String line = ClaudiaCli.statusBarLine(
				"", ClaudiaCli.StatusAccent.NONE,
				"~/xa/claudia [main]", "GPT-5.6 Sol Max (0%)", 60);

		assertEquals(60, ClaudiaCli.visibleWidth(line));
		assertTrue(ClaudiaCli.stripAnsi(line).startsWith("~/xa/claudia [main]"));
		assertTrue(ClaudiaCli.stripAnsi(line).endsWith("GPT-5.6 Sol Max (0%)"));

		String styled = ClaudiaCli.statusBarLine(
				"", ClaudiaCli.StatusAccent.NONE, "left", "right", 20);
		assertTrue(styled.startsWith(TerminalStyle.MUTED));
		assertTrue(styled.endsWith(TerminalStyle.RESET));

		String narrow = ClaudiaCli.statusBarLine(
				"", ClaudiaCli.StatusAccent.NONE,
				"~/a/very/long/working/directory", "GPT-5.6 Sol Max (0%)", 30);
		assertTrue(ClaudiaCli.visibleWidth(narrow) <= 30);
		assertTrue(ClaudiaCli.stripAnsi(narrow).endsWith("GPT-5.6 Sol Max (0%)"));

		assertEquals("left only", ClaudiaCli.stripAnsi(ClaudiaCli.statusBarLine(
				"", ClaudiaCli.StatusAccent.NONE, "left only", "", 20)));
		assertEquals("", ClaudiaCli.statusBarLine(
				"", ClaudiaCli.StatusAccent.NONE, "", "", 20));
	}

	@Test
	void activityHasPriorityAndOnlyReadyUsesTheGreenAccent() {
		String ready = ClaudiaCli.statusBarLine(
				"● Ready",
				ClaudiaCli.StatusAccent.READY,
				"~/xa/claudia [main]",
				"GPT-5.6 Sol Max (24%)",
				80);
		String waiting = ClaudiaCli.statusBarLine(
				"◐ Waiting for model · 12s",
				ClaudiaCli.StatusAccent.ACTIVE,
				"~/xa/claudia [main]",
				"GPT-5.6 Sol Max (24%)",
				80);

		assertTrue(ready.startsWith(ClaudiaCli.readyStatus() + "● Ready"));
		assertTrue(ready.contains(TerminalStyle.RESET + TerminalStyle.MUTED + " │ "));
		assertTrue(waiting.startsWith(ClaudiaCli.activeStatus() + "◐ Waiting for model"));
		assertFalse(waiting.contains(ClaudiaCli.readyStatus()));
		assertEquals(80, ClaudiaCli.visibleWidth(ready));
		assertEquals(80, ClaudiaCli.visibleWidth(waiting));

		for (ClaudiaCli.StatusAccent accent : List.of(
				ClaudiaCli.StatusAccent.NONE,
				ClaudiaCli.StatusAccent.ACTIVE,
				ClaudiaCli.StatusAccent.TOOL,
				ClaudiaCli.StatusAccent.WARNING)) {
			String line = ClaudiaCli.statusBarLine(
					"Busy", accent, "path", "model", 30);
			assertFalse(line.contains(ClaudiaCli.readyStatus()), accent.toString());
		}
	}

	@Test
	void narrowStatusBarsKeepActivityBeforeMetadata() {
		String line = ClaudiaCli.statusBarLine(
				"◐ Waiting for model · 12s",
				ClaudiaCli.StatusAccent.ACTIVE,
				"~/a/very/long/working/directory",
				"GPT-5.6 Sol Max (24%)",
				20);

		assertEquals("◐ Waiting for mod...", ClaudiaCli.stripAnsi(line));
		assertFalse(line.contains("GPT"));
		assertFalse(line.contains("~/"));
		assertEquals(20, ClaudiaCli.visibleWidth(line));
	}

	@Test
	void statusBarUpdatesDoNotDisturbConversationOutput() throws Exception {
		TerminalFixture fixture = terminal();

		ClaudiaCli interactive = newInteractiveTerminal(
				fixture.terminal(), () -> null, false);
		try {
			interactive.setStatus("", ClaudiaCli.StatusAccent.NONE, "~/xa/claudia [main]", "GPT-5.6 Sol Max (0%)");
			interactive.println("conversation output");

			assertTrue(fixture.output().toString(StandardCharsets.UTF_8).contains("conversation output"));
		} finally {
			interactive.closeTerminal();
		}
	}

    private static ClaudiaCli newInteractiveTerminal(
            Terminal terminal, java.util.concurrent.Callable<Void> suspendAction, boolean supportsSuspend) {
        ClaudiaCli cli = new ClaudiaCli();
        cli.newInteractiveTerminal(terminal, suspendAction, supportsSuspend);
        return cli;
    }

    @Test
    void resizeWhileEditingRepaintsTheConversationAndKeepsTheDraft() throws Exception {
        TerminalFixture fixture = terminal();
        ClaudiaCli cli = newInteractiveTerminal(fixture.terminal(), () -> null, false);
        cli.println("conversation line");
        var line = java.util.concurrent.CompletableFuture.supplyAsync(() -> cli.readLine("\n> "));
        try {
            fixture.input().write("draft".getBytes(StandardCharsets.UTF_8)); fixture.input().flush();
            waitUntil(() -> ((LineEditor) getCliField(cli, "editor")).buffer().equals("draft"));
            fixture.terminal().setSize(40, 12);
            waitUntil(() -> ScreenEmulator.render(fixture.output().toString(StandardCharsets.UTF_8), 40, 12)
                    .lines().subList(0, 3).equals(List.of("conversation line", "", "> draft")));
            fixture.input().write(" more\r".getBytes(StandardCharsets.UTF_8)); fixture.input().flush();
            assertEquals("draft more", line.get(3, TimeUnit.SECONDS));
        } finally { fixture.input().close(); cli.closeTerminal(); }
    }

    @Test
    void releasesTheStatusRowForSelectorsAndReservesItAgain() throws Exception {
        TerminalFixture fixture = terminal();
        ClaudiaCli cli = newInteractiveTerminal(fixture.terminal(), () -> null, false);
        try {
            cli.setStatus("● Ready", ClaudiaCli.StatusAccent.READY, "~/project", "Model (0%)");
            cli.println("conversation");
            String before = fixture.output().toString(StandardCharsets.UTF_8);
            assertTrue(ScreenEmulator.render(before, 80, 24).line(23).startsWith("● Ready"));

            cli.runComponent(immediateComponent());

            String all = fixture.output().toString(StandardCharsets.UTF_8);
            String written = all.substring(before.length());
            int enter = written.indexOf("\u001b[?1049h");
            int exit = written.indexOf("\u001b[?1049l");
            assertTrue(written.indexOf(Ansi.RESET_SCROLL_REGION) < enter, "The selector gets the whole screen");
            assertTrue(written.lastIndexOf(Ansi.scrollRegion(1, 23)) > exit);
            ScreenEmulator screen = ScreenEmulator.render(all, 80, 24);
            assertEquals("conversation", screen.line(0));
            assertTrue(screen.line(23).startsWith("● Ready"));
        } finally { cli.closeTerminal(); }
        ScreenEmulator closed = ScreenEmulator.render(fixture.output().toString(StandardCharsets.UTF_8), 80, 24);
        assertEquals("", closed.line(23), "Closing hands the bottom row back to the shell");
        assertFalse(fixture.terminal().mode().raw());
    }

    @Test
    void selectorsOpenedFromTheEditorConsumeOnlyTheirKeysFromTheSharedInput() throws Exception {
        TerminalFixture fixture = terminal();
        ClaudiaCli cli = newInteractiveTerminal(fixture.terminal(), () -> null, false);
        var chosen = new java.util.ArrayList<String>();
        try {
            cli.bindAppAction("expandTools", () -> {
                try {
                    chosen.add(cli.runComponent(ClaudiaCli.fuzzySelectorComponent(ClaudiaCli.fuzzySelector("Pick",
                            List.of(new SelectItem<>("alpha", "alpha", "", "alpha"), new SelectItem<>("beta", "beta", "", "beta")),
                            0, true))));
                } catch (java.io.IOException error) {
                    throw new java.io.UncheckedIOException(error);
                }
            });
            // Everything is queued before the editor starts: legacy and enhanced Ctrl-O both open
            // the selector, which takes its filter text and Enter/Escape and leaves the rest.
            fixture.input().write("draft\u000fbe\r tail\u001b[111;5u\u001b[27u\r".getBytes(StandardCharsets.UTF_8));
            fixture.input().flush();
            assertEquals("draft tail", assertTimeoutPreemptively(Duration.ofSeconds(5), () -> cli.readLine("\n> ")));
            assertEquals(java.util.Arrays.asList("beta", null), chosen);
            ScreenEmulator screen = ScreenEmulator.render(fixture.output().toString(StandardCharsets.UTF_8), 80, 24);
            assertEquals(List.of("", "> draft tail"), screen.allLines());
        } finally { cli.closeTerminal(); }
    }

    @Test
    void noSessionForkKeepsAnIndependentCopyOfTaskState(@TempDir Path workspace) throws Exception {
        TerminalFixture fixture = terminal();
        try (ClaudiaOperations runtime = new ClaudiaOperations()) {
            Model model = model("faux-1");
            model.api = "faux"; model.provider = "faux"; model.contextWindow = 100_000;
            runtime.applicationPaths(new ClaudiaPaths(workspace.resolve("home")));
            runtime.coreProviders(java.util.Map.of("faux",
                    new com.quaxt.claudia.ai.providers.FauxProvider("faux", "faux", List.of(model))));
            ClaudiaCli cli = new ClaudiaCli(runtime);
            cli.newInteractiveTerminal(fixture.terminal(), () -> null, false);
            setCliField(cli, "settings", new ClaudiaOperations.Settings(null, null, ThinkingLevel.OFF, false));
            setCliField(cli, "activity", readyActivity(System.nanoTime()));
            setCliField(cli, "noSession", true);
            var configure = ClaudiaCli.class.getDeclaredMethod("configureShellAgent", Model.class, Path.class, boolean.class, String.class);
            configure.setAccessible(true); configure.invoke(cli, model, workspace, false, null);
            var stateTool = runtime.builtInTools(workspace, ignored -> {}).stream()
                    .filter(tool -> ClaudiaOperations.toolName(tool).equals("task_state")).findFirst().orElseThrow();
            runtime.executeTool(stateTool, "add", ClaudiaOperations.jsonObject().put("action", "add_task")
                    .put("description", "Source task"), new com.quaxt.claudia.ai.util.AbortSignal(), ignored -> {});
            ObjectNode source = runtime.taskStateSnapshot();
            var fork = java.util.concurrent.CompletableFuture.runAsync(() -> {
                try { invokeCommand(cli, "/fork"); }
                catch (Exception error) { throw new RuntimeException(error); }
            });
            try {
                waitUntil(() -> Boolean.TRUE.equals(getCliField(cli, "lineEditorReading")));
                fixture.input().write("Branch\r".getBytes(StandardCharsets.UTF_8)); fixture.input().flush();
                fork.get(3, TimeUnit.SECONDS);
                assertEquals(source, runtime.taskStateSnapshot());
                assertNull(runtime.state().sessionId());
                stateTool = runtime.builtInTools(workspace, ignored -> {}).stream()
                        .filter(tool -> ClaudiaOperations.toolName(tool).equals("task_state")).findFirst().orElseThrow();
                runtime.executeTool(stateTool, "add2", ClaudiaOperations.jsonObject().put("action", "add_task")
                        .put("description", "Branch task"), new com.quaxt.claudia.ai.util.AbortSignal(), ignored -> {});
                assertEquals(1, source.path("tasks").size());
                assertEquals(2, runtime.taskStateSnapshot().path("tasks").size());
            } finally { fixture.input().close(); cli.closeTerminal(); }
        }
    }

    @Test
    void aLiveTurnShowsReasoningAndToolContainersThatClicksExpand(@TempDir Path workspace) throws Exception {
        for (int index = 1; index <= 6; index++) Files.writeString(workspace.resolve("file" + index + ".txt"), "x");
        TerminalFixture fixture = terminal();
        try (ClaudiaOperations runtime = new ClaudiaOperations()) {
            var model = model("faux-1");
            model.api = "faux"; model.provider = "faux"; model.contextWindow = 100_000;
            var provider = new com.quaxt.claudia.ai.providers.FauxProvider("faux", "faux", List.of(model));
            runtime.applicationPaths(new ClaudiaPaths(workspace.resolve("home")));
            runtime.coreProviders(java.util.Map.of("faux", provider));
            ClaudiaCli cli = new ClaudiaCli(runtime);
            cli.newInteractiveTerminal(fixture.terminal(), () -> null, false);
            setCliField(cli, "settings", new ClaudiaOperations.Settings(null, null, ThinkingLevel.OFF, false));
            setCliField(cli, "activity", readyActivity(System.nanoTime()));
            var configure = ClaudiaCli.class.getDeclaredMethod("configureShellAgent", Model.class, Path.class, boolean.class, String.class);
            configure.setAccessible(true); configure.invoke(cli, model, workspace, false, null);
            provider.pendingResponses.add(new com.quaxt.claudia.ai.providers.FauxProvider.ResponseStep.Factory(request -> {
                AssistantMessage response = SubagentManagerTest.answer("");
                response.content.clear();
                response.content.add(new ThinkingContent("plan 1\nplan 2\nplan 3\nplan 4", null, false));
                response.content.add(new ToolCall("list-1", "ls", ClaudiaOperations.jsonObject(), null));
                response.stopReason = StopReason.TOOL_USE;
                return response;
            }));
            provider.pendingResponses.add(new com.quaxt.claudia.ai.providers.FauxProvider.ResponseStep.Message(
                    SubagentManagerTest.answer("All six files are listed.")));
            var reader = java.util.concurrent.CompletableFuture.supplyAsync(() -> cli.readLine("\n> "));
            try {
                waitUntil(() -> fixture.terminal().mouseTracking());
                runtime.subagents().submit(SubagentManager.MAIN, "list the files").get(3, TimeUnit.SECONDS);
                invokeCli(cli, "drainShellEvents");
                ScreenEmulator screen = screen(fixture);
                List<String> rows = screen.lines();
                int thinking = rows.indexOf("\u25bc Thinking");
                int tool = rows.indexOf("\u25b6 [ls] Listing .");
                assertTrue(thinking >= 0 && tool > thinking, rows.toString());
                assertEquals("    plan 4", rows.get(thinking + 4));
                assertTrue(rows.get(tool + 1).startsWith("    Done: "), rows.toString());
                assertTrue(rows.get(tool + 4).startsWith("    \u2026 "), rows.toString());
                assertTrue(rows.contains("All six files are listed."));

                fixture.input().write(("\u001b[<0;1;" + (thinking + 1) + "M").getBytes(StandardCharsets.UTF_8));
                fixture.input().flush();
                waitUntil(() -> screen(fixture).lines().contains("\u25b6 Thinking"));
                int collapsedTool = screen(fixture).lines().indexOf("\u25b6 [ls] Listing .");
                assertEquals("    \u2026 1 more line", screen(fixture).line(thinking + 4));
                fixture.input().write(("\u001b[<0;2;" + (collapsedTool + 1) + "M").getBytes(StandardCharsets.UTF_8));
                fixture.input().flush();
                waitUntil(() -> screen(fixture).lines().contains("\u25bc [ls] Listing ."));
                List<String> expanded = screen(fixture).lines();
                assertTrue(expanded.contains("    file6.txt"), expanded.toString());

                // Switching away and back rebuilds the conversation with the states chosen by clicking.
                var redraw = ClaudiaCli.class.getDeclaredMethod("redrawSelectedConversation", boolean.class);
                redraw.setAccessible(true);
                var lock = (java.util.concurrent.locks.ReentrantLock) getCliField(cli, "editorLock");
                lock.lock();
                try {
                    synchronized (cli) {
                        redraw.invoke(cli, false);
                    }
                } finally {
                    lock.unlock();
                }
                Transcript rebuilt = (Transcript) getCliField(cli, "transcript");
                assertTrue(rebuilt.isExpanded("list-1"));
                assertTrue(rebuilt.plainText().contains("\u25bc [ls] Listing .\n    Arguments\n"), rebuilt.plainText());
                fixture.input().write("\r".getBytes(StandardCharsets.UTF_8));
                fixture.input().flush();
                assertEquals("", reader.get(3, TimeUnit.SECONDS));
            } finally {
                fixture.input().close();
                invokeCli(cli, "closeShellSubscription");
                cli.closeTerminal();
            }
        }
    }

    @Test
    void navigatesLiveAgentsAndBuffersSelectorOutputWithoutLosingTypedInput(@TempDir Path workspace) throws Exception {
        TerminalFixture fixture = terminal();
        try (ClaudiaOperations runtime = new ClaudiaOperations()) {
            var model = model("faux-1");
            model.api = "faux"; model.provider = "faux"; model.contextWindow = 100_000;
            var provider = new com.quaxt.claudia.ai.providers.FauxProvider("faux", "faux", List.of(model));
            runtime.applicationPaths(new ClaudiaPaths(workspace.resolve("home")));
            runtime.coreProviders(java.util.Map.of("faux", provider));
            ClaudiaCli cli = new ClaudiaCli(runtime);
            cli.newInteractiveTerminal(fixture.terminal(), () -> null, false);
            setCliField(cli, "settings", new ClaudiaOperations.Settings(null, null, ThinkingLevel.OFF, false));
            setCliField(cli, "activity", readyActivity(System.nanoTime()));
            var configure = ClaudiaCli.class.getDeclaredMethod("configureShellAgent", Model.class, Path.class, boolean.class, String.class);
            configure.setAccessible(true); configure.invoke(cli, model, workspace, false, null);
            String id = runtime.subagents().create("investigate files", "Worker");
            CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
            provider.pendingResponses.add(new com.quaxt.claudia.ai.providers.FauxProvider.ResponseStep.Factory(request -> {
                started.countDown(); SubagentManagerTest.await(release); return SubagentManagerTest.answer("child secret answer");
            }));
            var child = runtime.subagents().submit(id, "child task");
            assertTrue(started.await(3, TimeUnit.SECONDS));
            invokeCommand(cli, "/clear");
            assertEquals(2, runtime.subagents().list().size(), "Group changes must be rejected while a child runs");
            cli.bindAppAction("expandTools", () -> invokeCli(cli, "showSubagents"));
            var reader = java.util.concurrent.CompletableFuture.supplyAsync(() -> cli.readLine("> "));
            try {
                fixture.input().write("draft\u000f".getBytes(StandardCharsets.UTF_8)); fixture.input().flush();
                waitUntil(() -> Boolean.TRUE.equals(getCliField(cli, "componentOpen")));
                release.countDown(); child.get(3, TimeUnit.SECONDS);
                invokeCli(cli, "drainShellEvents");
                assertFalse(fixture.output().toString(StandardCharsets.UTF_8).contains("child secret answer"));
                fixture.input().write("Worker\r".getBytes(StandardCharsets.UTF_8)); fixture.input().flush();
                waitUntil(() -> id.equals(getCliField(cli, "selectedAgent")));
                assertTrue(getCliField(cli, "transcript").toString().contains("child secret answer"));
                invokeCommand(cli, "/clear");
                assertEquals(2, runtime.subagents().list().size(), "Group changes require Main to be selected");
                fixture.input().write(" suffix\r".getBytes(StandardCharsets.UTF_8)); fixture.input().flush();
                assertEquals("draft suffix", reader.get(3, TimeUnit.SECONDS));

                provider.pendingResponses.add(new com.quaxt.claudia.ai.providers.FauxProvider.ResponseStep.Message(SubagentManagerTest.answer("parent background answer")));
                runtime.subagents().submit(SubagentManager.MAIN, "parent task").get(3, TimeUnit.SECONDS);
                invokeCli(cli, "drainShellEvents");
                assertFalse(getCliField(cli, "transcript").toString().contains("parent background answer"));
                fixture.input().write("Main\r".getBytes(StandardCharsets.UTF_8)); fixture.input().flush();
                invokeCli(cli, "showSubagents");
                assertTrue(getCliField(cli, "transcript").toString().contains("parent background answer"));
                assertFalse(getCliField(cli, "transcript").toString().contains("child secret answer"));
            } finally {
                release.countDown();
                fixture.input().close();
                invokeCli(cli, "closeShellSubscription");
                cli.closeTerminal();
            }
        }
    }

    @Test
    void streamedOutputRedrawPreservesTheActiveEditorBuffer() throws Exception {
        TerminalFixture fixture = terminal();
        ClaudiaCli cli = newInteractiveTerminal(fixture.terminal(), () -> null, false);
        var reader = java.util.concurrent.CompletableFuture.supplyAsync(() -> cli.readLine("> "));
        try {
            fixture.input().write("unfinished".getBytes(StandardCharsets.UTF_8)); fixture.input().flush();
            waitUntil(() -> ((LineEditor) getCliField(cli, "editor")).buffer().equals("unfinished"));
            // The UI event pump takes this lock before rendering on a background tick.
            var lock = (java.util.concurrent.locks.ReentrantLock) getCliField(cli, "editorLock");
            lock.lock();
            try { cli.println("streamed output"); } finally { lock.unlock(); }
            fixture.terminal().setSize(60, 20);
            fixture.input().write(" prompt\r".getBytes(StandardCharsets.UTF_8)); fixture.input().flush();
            assertEquals("unfinished prompt", reader.get(3, TimeUnit.SECONDS));
            assertTrue(fixture.output().toString(StandardCharsets.UTF_8).contains("streamed output"));
        } finally { fixture.input().close(); cli.closeTerminal(); }
    }

    @Test
    void clickingAToolToggleExpandsItWhereItWasShown() throws Exception {
        TerminalFixture fixture = terminal();
        ClaudiaCli cli = newInteractiveTerminal(fixture.terminal(), () -> null, false);
        setCliField(cli, "activity", readyActivity(System.nanoTime()));
        var reader = java.util.concurrent.CompletableFuture.supplyAsync(() -> cli.readLine("\n> "));
        try {
            waitUntil(() -> fixture.terminal().mouseTracking());
            assertTrue(fixture.output().toString(StandardCharsets.UTF_8).contains(Ansi.MOUSE_ON));
            cli.println("conversation");
            deliver(cli, new AgentEvent.ToolExecutionStart(
                    "call-1", "shell", ClaudiaOperations.jsonObject().put("command", "mvn test")));
            deliver(cli, new AgentEvent.ToolExecutionEnd("call-1", "shell", ClaudiaOperations.toolResultText(
                    IntStream.rangeClosed(1, 8).mapToObj(index -> "line " + index).collect(Collectors.joining("\n")))));
            ScreenEmulator before = screen(fixture);
            int header = before.lines().indexOf("\u25b6 [shell] mvn test");
            assertTrue(header >= 0, before.lines().toString());
            assertEquals(List.of("    Done: line 1", "          line 2", "          line 3", "    \u2026 5 more lines"),
                    before.lines().subList(header + 1, header + 5));
            assertFalse(before.lines().contains("    line 8"));

            String row = String.valueOf(header + 1);
            // A click beside the toggle does nothing; a click on it expands the container in place.
            fixture.input().write(("\u001b[<0;5;" + row + "M\u001b[<0;5;" + row + "m"
                    + "\u001b[<0;1;" + row + "M\u001b[<0;1;" + row + "m").getBytes(StandardCharsets.UTF_8));
            fixture.input().flush();
            waitUntil(() -> screen(fixture).line(header).equals("\u25bc [shell] mvn test"));
            ScreenEmulator after = screen(fixture);
            assertEquals("    Arguments", after.line(header + 1));
            assertTrue(after.lines().contains("    line 8"), after.lines().toString());

            fixture.input().write("go\r".getBytes(StandardCharsets.UTF_8));
            fixture.input().flush();
            assertEquals("go", reader.get(3, TimeUnit.SECONDS));
            assertFalse(fixture.terminal().mouseTracking(), "Mouse reports stop when the prompt stops reading");
            Transcript transcript = (Transcript) getCliField(cli, "transcript");
            assertTrue(transcript.isExpanded("call-1"));
        } finally {
            fixture.input().close();
            cli.closeTerminal();
        }
    }

    @Test
    void wheelAndPageKeysScrollTheConversationAndTheStatusShowsMoreBelow() throws Exception {
        TerminalFixture fixture = terminal();
        ClaudiaCli cli = newInteractiveTerminal(fixture.terminal(), () -> null, false);
        setCliField(cli, "activity", readyActivity(System.nanoTime()));
        cli.setStatus("\u25cf Ready", ClaudiaCli.StatusAccent.READY, "~/project", "Model (0%)");
        for (int index = 1; index <= 60; index++) cli.println("line " + index);
        var reader = java.util.concurrent.CompletableFuture.supplyAsync(() -> cli.readLine("\n> "));
        try {
            waitUntil(() -> fixture.terminal().mouseTracking() && screen(fixture).lines().contains("> ".strip()));
            ScreenEmulator bottom = screen(fixture);
            assertTrue(bottom.lines().contains("line 60"));
            int first = lineNumber(bottom.line(0));

            fixture.input().write("\u001b[<64;10;5M".getBytes(StandardCharsets.UTF_8));
            fixture.input().flush();
            waitUntil(() -> screen(fixture).line(0).equals("line " + (first - 3)));
            waitUntil(() -> screen(fixture).line(23).contains("\u2193 more below"));
            assertFalse(screen(fixture).lines().contains("line 60"));

            fixture.input().write("\u001b[5~".getBytes(StandardCharsets.UTF_8));
            fixture.input().flush();
            waitUntil(() -> lineNumber(screen(fixture).line(0)) < first - 3);

            fixture.input().write("\u001b[6~\u001b[6~\u001b[6~\u001b[<65;10;5M".getBytes(StandardCharsets.UTF_8));
            fixture.input().flush();
            waitUntil(() -> screen(fixture).lines().contains("line 60") && !screen(fixture).line(23).contains("\u2193"));
            assertEquals(first, lineNumber(screen(fixture).line(0)));

            fixture.input().write("\r".getBytes(StandardCharsets.UTF_8));
            fixture.input().flush();
            assertEquals("", reader.get(3, TimeUnit.SECONDS));
        } finally {
            fixture.input().close();
            cli.closeTerminal();
        }
    }

    @Test
    void conversationMouseReportingSurvivesAFullScreenComponent() throws Exception {
        TerminalFixture fixture = terminal();
        ClaudiaCli cli = newInteractiveTerminal(fixture.terminal(), () -> null, false);
        AtomicBoolean afterComponent = new AtomicBoolean();
        try {
            cli.bindAppAction("expandTools", () -> {
                try {
                    cli.runComponent(immediateComponent());
                } catch (java.io.IOException error) {
                    throw new java.io.UncheckedIOException(error);
                }
                afterComponent.set(fixture.terminal().mouseTracking());
            });
            fixture.input().write("\u000fok\r".getBytes(StandardCharsets.UTF_8));
            fixture.input().flush();
            assertEquals("ok", assertTimeoutPreemptively(Duration.ofSeconds(5), () -> cli.readLine("> ")));
            assertTrue(afterComponent.get(), "Closing a selector keeps the conversation's mouse reporting");
            assertFalse(fixture.terminal().mouseTracking());
        } finally {
            cli.closeTerminal();
        }
    }

    @Test
    void exitLeavesTheWholeConversationInTheScrollback() throws Exception {
        TerminalFixture fixture = terminal();
        ClaudiaCli cli = newInteractiveTerminal(fixture.terminal(), () -> null, false);
        cli.setStatus("\u25cf Ready", ClaudiaCli.StatusAccent.READY, "~/project", "Model (0%)");
        List<String> lines = IntStream.rangeClosed(1, 40).mapToObj(index -> "line " + index).toList();
        for (String line : lines) cli.println(line);
        assertFalse(ScreenEmulator.render(fixture.output().toString(StandardCharsets.UTF_8), 80, 24)
                .allLines().contains("line 1"), "The viewport shows only the end while running");
        cli.closeTerminal();
        assertEquals(lines, ScreenEmulator.render(fixture.output().toString(StandardCharsets.UTF_8), 80, 24).allLines());
    }

    @Test
    void reasoningStreamsExpandedAndCtrlTCollapsesEveryReasoningContainer(@TempDir Path workspace) throws Exception {
        TerminalFixture fixture = terminal();
        try (ClaudiaOperations runtime = new ClaudiaOperations()) {
            runtime.applicationPaths(new ClaudiaPaths(workspace.resolve("home")));
            ClaudiaCli cli = new ClaudiaCli(runtime);
            cli.newInteractiveTerminal(fixture.terminal(), () -> null, false);
            try {
                setCliField(cli, "settings", runtime.loadSettings());
                setCliField(cli, "activity", readyActivity(System.nanoTime()));
                String steps = "step 1\nstep 2\nstep 3\nstep 4\nstep 5";
                AssistantMessage first = new AssistantMessage("faux", "faux", "faux-1");
                streamThinking(cli, first, steps);
                Transcript transcript = (Transcript) getCliField(cli, "transcript");
                String firstKey = ClaudiaCli.thinkingKey(first, 0);
                assertTrue(transcript.isExpanded(firstKey), "Visible reasoning stays expanded");
                assertTrue(transcript.plainText().contains("\u25bc Thinking\n    step 1\n"));

                var toggleThinking = ClaudiaCli.class.getDeclaredMethod("setShellHideThinkingBlock", boolean.class, boolean.class);
                toggleThinking.setAccessible(true);
                toggleThinking.invoke(cli, true, false);
                assertFalse(transcript.isExpanded(firstKey));
                assertTrue(transcript.plainText().contains(
                        "\u25b6 Thinking\n    step 1\n    step 2\n    step 3\n    \u2026 2 more lines\n"), transcript.plainText());
                assertTrue(transcript.plainText().contains("Thinking blocks: collapsed"));
                assertTrue(runtime.loadSettings().hideThinkingBlock, "The choice persists");

                AssistantMessage second = new AssistantMessage("faux", "faux", "faux-1");
                second.timestamp = first.timestamp + 1;
                deliver(cli, new AgentEvent.MessageUpdate(new AssistantMessageEvent.ThinkingStart(0, second)));
                deliver(cli, new AgentEvent.MessageUpdate(new AssistantMessageEvent.ThinkingDelta(0, steps, second)));
                String secondKey = ClaudiaCli.thinkingKey(second, 0);
                assertTrue(transcript.isStreaming(secondKey));
                assertTrue(transcript.isExpanded(secondKey), "Reasoning is shown while it streams");
                deliver(cli, new AgentEvent.MessageUpdate(new AssistantMessageEvent.ThinkingEnd(0, steps, second)));
                assertFalse(transcript.isExpanded(secondKey), "Finished reasoning takes the Ctrl-T state");

                toggleThinking.invoke(cli, false, false);
                assertTrue(transcript.isExpanded(firstKey));
                assertTrue(transcript.isExpanded(secondKey));
            } finally {
                cli.closeTerminal();
            }
        }
    }

    private static void streamThinking(ClaudiaCli cli, AssistantMessage message, String text) throws Exception {
        deliver(cli, new AgentEvent.MessageUpdate(new AssistantMessageEvent.ThinkingStart(0, message)));
        deliver(cli, new AgentEvent.MessageUpdate(new AssistantMessageEvent.ThinkingDelta(0, text, message)));
        deliver(cli, new AgentEvent.MessageUpdate(new AssistantMessageEvent.ThinkingEnd(0, text, message)));
    }

    /** Delivers an agent event the way the UI event pump does, holding the editor and screen locks. */
    private static void deliver(ClaudiaCli cli, AgentEvent event) throws Exception {
        var handle = ClaudiaCli.class.getDeclaredMethod("handleShellAgentEvent", AgentEvent.class);
        handle.setAccessible(true);
        var lock = (java.util.concurrent.locks.ReentrantLock) getCliField(cli, "editorLock");
        lock.lock();
        try {
            synchronized (cli) {
                handle.invoke(cli, event);
            }
        } finally {
            lock.unlock();
        }
    }

    private static ScreenEmulator screen(TerminalFixture fixture) {
        return ScreenEmulator.render(fixture.output().toString(StandardCharsets.UTF_8), 80, 24);
    }

    private static int lineNumber(String row) {
        return row.startsWith("line ") ? Integer.parseInt(row.substring(5)) : -1;
    }

    @Test
    void answersAnArrivingQuestionAndTogglesModeWithoutLosingDraftOrCursor() throws Exception {
        TerminalFixture fixture = terminal();
        try (var runtime = new ClaudiaOperations()) {
            ClaudiaCli cli = new ClaudiaCli(runtime);
            cli.newInteractiveTerminal(fixture.terminal(), () -> null, false);
            setCliField(cli, "activity", readyActivity(System.nanoTime()));
            runtime.questions().setInteractive(true);
            var line = java.util.concurrent.CompletableFuture.supplyAsync(() -> cli.readLine("> ", ClaudiaCli.slashCommands()));
            try {
                fixture.input().write(("draft tail" + "\u0002".repeat(5)).getBytes(StandardCharsets.UTF_8)); fixture.input().flush();
                var editor = (LineEditor) getCliField(cli, "editor");
                waitUntil(() -> editor.buffer().equals("draft tail") && editor.cursor() == 5);
                var answer = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try { return runtime.questions().ask("main", "Which scope?",
                            List.of(new com.quaxt.claudia.agent.QuestionBroker.Option("Small", "Less work")),
                            new com.quaxt.claudia.ai.util.AbortSignal()); }
                    catch (InterruptedException error) { throw new RuntimeException(error); }
                });
                waitUntil(() -> Boolean.TRUE.equals(getCliField(cli, "componentOpen")));
                assertFalse(answer.isDone(), "A highlighted choice is not an answer");
                fixture.input().write("Custom scope\r".getBytes(StandardCharsets.UTF_8)); fixture.input().flush();
                assertEquals("Custom scope", answer.get(3, TimeUnit.SECONDS).answer());
                waitUntil(() -> Boolean.FALSE.equals(getCliField(cli, "componentOpen")));
                assertEquals("draft tail", editor.buffer());
                assertEquals(5, editor.cursor());
                fixture.input().write("\u001b[9;2u".getBytes(StandardCharsets.UTF_8)); fixture.input().flush();
                waitUntil(() -> runtime.agentMode() == com.quaxt.claudia.agent.AgentMode.PLAN);
                assertEquals(5, editor.cursor());
                assertTrue(getCliField(cli, "statusActivity").toString().contains("[Plan]"));
                fixture.input().write(" new\r".getBytes(StandardCharsets.UTF_8)); fixture.input().flush();
                assertEquals("draft new tail", line.get(3, TimeUnit.SECONDS));
                invokeCommand(cli, "/build");
                assertEquals(com.quaxt.claudia.agent.AgentMode.BUILD, runtime.agentMode());
                assertTrue(runtime.state().messages().isEmpty());
            } finally { fixture.input().close(); cli.closeTerminal(); }
        }
    }

    @Test
    void auxiliaryPromptsDeferQuestionsAndEscapeDeclinesWithoutAnswering() throws Exception {
        TerminalFixture fixture = terminal();
        try (var runtime = new ClaudiaOperations()) {
            ClaudiaCli cli = new ClaudiaCli(runtime);
            cli.newInteractiveTerminal(fixture.terminal(), () -> null, false);
            setCliField(cli, "activity", readyActivity(System.nanoTime()));
            runtime.questions().setInteractive(true);
            var auxiliary = java.util.concurrent.CompletableFuture.supplyAsync(() -> cli.readLine("Name: "));
            try {
                waitUntil(() -> Boolean.TRUE.equals(getCliField(cli, "lineEditorReading")));
                var answer = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try { return runtime.questions().ask("main", "Which scope?", List.of(), new com.quaxt.claudia.ai.util.AbortSignal()); }
                    catch (InterruptedException error) { throw new RuntimeException(error); }
                });
                waitUntil(() -> !runtime.questions().pending().isEmpty());
                assertFalse(Boolean.TRUE.equals(getCliField(cli, "componentOpen")));
                fixture.input().write("Session name\r".getBytes(StandardCharsets.UTF_8)); fixture.input().flush();
                assertEquals("Session name", auxiliary.get(3, TimeUnit.SECONDS));
                assertFalse(answer.isDone());
                var main = java.util.concurrent.CompletableFuture.supplyAsync(() -> cli.readLine("> ", ClaudiaCli.slashCommands()));
                waitUntil(() -> Boolean.TRUE.equals(getCliField(cli, "componentOpen")));
                fixture.input().write(27); fixture.input().flush();
                assertEquals("declined", answer.get(3, TimeUnit.SECONDS).status());
                waitUntil(() -> Boolean.FALSE.equals(getCliField(cli, "componentOpen")));
                fixture.input().write("Continue\r".getBytes(StandardCharsets.UTF_8)); fixture.input().flush();
                assertEquals("Continue", main.get(3, TimeUnit.SECONDS));
            } finally { fixture.input().close(); cli.closeTerminal(); }
        }
    }

    @Test
    void logoutReleasesTheSessionGroup(@TempDir Path workspace) throws Exception {
        TerminalFixture fixture = terminal();
        try (ClaudiaOperations runtime = new ClaudiaOperations()) {
            var model = model("fixture"); model.provider = "openai"; model.api = "faux";
            var provider = new com.quaxt.claudia.ai.providers.FauxProvider("faux", "openai", List.of(model));
            runtime.applicationPaths(new ClaudiaPaths(workspace.resolve("home")));
            runtime.coreProviders(java.util.Map.of("openai", provider));
            ClaudiaCli cli = new ClaudiaCli(runtime);
            cli.newInteractiveTerminal(fixture.terminal(), () -> null, false);
            setCliField(cli, "settings", new ClaudiaOperations.Settings(null, null, ThinkingLevel.OFF, false));
            setCliField(cli, "activity", readyActivity(System.nanoTime()));
            var configure = ClaudiaCli.class.getDeclaredMethod("configureShellAgent", Model.class, Path.class, boolean.class, String.class);
            configure.setAccessible(true); configure.invoke(cli, model, workspace, false, null);
            var child = runtime.subagents().runtime(runtime.subagents().create("task", "Worker"));
            try {
                invokeCommand(cli, "/logout");
                assertEquals(false, getCliField(cli, "agentConfigured"));
                assertThrows(IllegalStateException.class, () -> child.prompt("closed"));
            } finally { cli.closeTerminal(); }
        }
    }

    private static void invokeCommand(ClaudiaCli cli, String command) throws Exception {
        var method = ClaudiaCli.class.getDeclaredMethod("dispatchSlashCommand", String.class);
        method.setAccessible(true); method.invoke(cli, command);
    }

    private static void setCliField(ClaudiaCli cli, String name, Object value) throws Exception {
        var field = ClaudiaCli.class.getDeclaredField(name); field.setAccessible(true); field.set(cli, value);
    }

    private static Object getCliField(ClaudiaCli cli, String name) {
        try {
            var field = ClaudiaCli.class.getDeclaredField(name); field.setAccessible(true); return field.get(cli);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void invokeCli(ClaudiaCli cli, String name) {
        try {
            var method = ClaudiaCli.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(cli);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void waitUntil(java.util.function.BooleanSupplier ready) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!ready.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(ready.getAsBoolean(), "Timed out waiting for terminal state");
    }

	private static TuiComponent<Void> immediateComponent() {
		return new TuiComponent<>(frame -> List.of("details"), input -> {}, () -> true, () -> null);
	}

	private static TerminalFixture terminal() throws Exception {
        return terminal("xterm-256color");
    }

    private static TerminalFixture terminal(String type) throws Exception {
		PipedInputStream input = new PipedInputStream(1 << 16);
		PipedOutputStream inputWriter = new PipedOutputStream(input);
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		Terminal terminal = Terminal.streams(type, input, output, 80, 24);
		return new TerminalFixture(terminal, inputWriter, output);
	}

	private static int count(String value, String target) {
		int count = 0;
		int offset = 0;
		while ((offset = value.indexOf(target, offset)) >= 0) {
			count++;
			offset += target.length();
		}
		return count;
	}

	private record TerminalFixture(Terminal terminal, PipedOutputStream input, ByteArrayOutputStream output) {}

	// TuiInputReader

	@Test
	void parsesNavigationControlAndMouseSequences() {
		assertEquals("escape", Keybindings.DEFAULT_APP_KEYBINDINGS.get("interrupt"));
		assertEquals("shift-tab", Keybindings.DEFAULT_APP_KEYBINDINGS.get("toggleAgentMode"));
		assertEquals(
				ClaudiaCli.key(TuiInput.KeyType.ESCAPE),
				ClaudiaCli.parseInputSequence("\u001b"));
		assertEquals(
				ClaudiaCli.key(TuiInput.KeyType.CANCEL),
				ClaudiaCli.parseInputSequence("\u0003"));
		assertEquals(
				ClaudiaCli.key(TuiInput.KeyType.UP),
				ClaudiaCli.parseInputSequence("\u001b[A"));
		assertEquals(
				ClaudiaCli.key(TuiInput.KeyType.PAGE_DOWN),
				ClaudiaCli.parseInputSequence("\u001b[6~"));
		assertEquals(
				ClaudiaCli.key(TuiInput.KeyType.SUSPEND),
				ClaudiaCli.parseInputSequence("\u001a"));
		assertEquals(
				ClaudiaCli.key(TuiInput.KeyType.EXPAND_TOOLS),
				ClaudiaCli.parseInputSequence("\u000f"));
		assertEquals(
				ClaudiaCli.key(TuiInput.KeyType.TOGGLE_THINKING),
				ClaudiaCli.parseInputSequence("\u0014"));
		assertEquals(
				ClaudiaCli.key(TuiInput.KeyType.EXIT),
				ClaudiaCli.parseInputSequence("\u0004"));
		assertEquals(
				new TuiInput.Mouse(TuiInput.MouseAction.PRESS, 0, 12, 7),
				ClaudiaCli.parseInputSequence("\u001b[<0;12;7M"));
		assertEquals(
				new TuiInput.Mouse(TuiInput.MouseAction.SCROLL_DOWN, 1, 4, 9),
				ClaudiaCli.parseInputSequence("\u001b[<65;4;9M"));
	}
}
