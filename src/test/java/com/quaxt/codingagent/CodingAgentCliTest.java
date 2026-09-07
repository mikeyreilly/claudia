package com.quaxt.codingagent;

import com.quaxt.codingagent.ai.json.Json;
import com.quaxt.codingagent.ai.types.AssistantMessage;
import com.quaxt.codingagent.ai.types.Message;
import com.quaxt.codingagent.ai.types.Model;
import com.quaxt.codingagent.ai.types.ModelCost;
import com.quaxt.codingagent.ai.types.StopReason;
import com.quaxt.codingagent.ai.types.TextContent;
import com.quaxt.codingagent.ai.types.ThinkingContent;
import com.quaxt.codingagent.ai.types.ThinkingLevel;
import com.quaxt.codingagent.ai.types.ToolCall;
import com.quaxt.codingagent.ai.types.ToolResultMessage;
import com.quaxt.codingagent.cli.ActivityStatus;
import com.quaxt.codingagent.cli.TurnDetailsComponent;
import com.quaxt.codingagent.tui.FuzzySelector;
import com.quaxt.codingagent.tui.Keybindings;
import com.quaxt.codingagent.tui.SelectItem;
import com.quaxt.codingagent.tui.TerminalStyle;
import com.quaxt.codingagent.tui.TuiComponent;
import com.quaxt.codingagent.tui.TuiInput;
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
import java.util.stream.IntStream;
import org.jline.terminal.Attributes.LocalFlag;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.quaxt.codingagent.CodingAgentCli.activeActivity;
import static com.quaxt.codingagent.CodingAgentCli.activityAccent;
import static com.quaxt.codingagent.CodingAgentCli.activityLabel;
import static com.quaxt.codingagent.CodingAgentCli.isDynamicActivity;
import static com.quaxt.codingagent.CodingAgentCli.readyActivity;
import static com.quaxt.codingagent.CodingAgentCli.retryingActivity;
import static com.quaxt.codingagent.CodingAgentCli.sameActivity;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@code CodingAgentCli}, grouped by the behavior under test.
 */
class CodingAgentCliTest {

	// McpSelector

	@Test
	void startsAtTheFirstItemWhenTheServerListIsShorterThanTheViewport() {
		assertEquals(0, CodingAgentCli.mcpSelectorVisibleStart(1, 10, 0));
	}

	@Test
	void keepsTheSelectionCenteredWithinAFullViewport() {
		assertEquals(5, CodingAgentCli.mcpSelectorVisibleStart(20, 10, 10));
		assertEquals(10, CodingAgentCli.mcpSelectorVisibleStart(20, 10, 19));
	}

	// ActivityStatus

	private static final long SECOND = 1_000_000_000L;

	@Test
	void readyIsTheOnlyReadyAccentedPhase() {
		ActivityStatus ready = readyActivity(0);

		assertEquals("● Ready", activityLabel(ready, 20 * SECOND));
		assertEquals(CodingAgentCli.StatusAccent.READY, activityAccent(ready));
		assertFalse(isDynamicActivity(ready));

		for (ActivityStatus.Phase phase : ActivityStatus.Phase.values()) {
			if (phase == ActivityStatus.Phase.READY) continue;
			ActivityStatus status = phase == ActivityStatus.Phase.RETRYING
					? retryingActivity(1, 3, 2_000, 0)
					: activeActivity(phase, 0);
			assertFalse(activityAccent(status) == CodingAgentCli.StatusAccent.READY, phase.toString());
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
		assertEquals(CodingAgentCli.StatusAccent.WARNING, activityAccent(retry));
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

		assertEquals(preferred, CodingAgentCli.preferredCopilotModel(List.of(fallback, preferred)));
		assertEquals(fallback, CodingAgentCli.preferredCopilotModel(List.of(fallback)));
	}

	@Test
	void prefersTerraWhenRestoringChatGptWithoutSavedSettings() {
		Model fallback = model("gpt-5.4");
		Model terra = model("gpt-5.6-terra");

		assertEquals(terra, CodingAgentCli.preferredChatGptModel(List.of(fallback, terra)));
		assertEquals(fallback, CodingAgentCli.preferredChatGptModel(List.of(fallback)));
	}

	@Test
	void defaultsReasoningModelsToMediumThinking() {
		Model reasoning = model("gpt-5.4", true);

		assertEquals(ThinkingLevel.MEDIUM, CodingAgentCli.initialThinkingLevel(reasoning, null));
		assertEquals(ThinkingLevel.HIGH, CodingAgentCli.initialThinkingLevel(reasoning, ThinkingLevel.HIGH));
		assertEquals(ThinkingLevel.OFF, CodingAgentCli.initialThinkingLevel(model("gpt-4.1"), null));
	}

	@Test
	void prepopulatesForkNamesFromTheCurrentSessionName() {
		assertEquals("fork", CodingAgentCli.forkName(null));
		assertEquals("fork", CodingAgentCli.forkName("  "));
		assertEquals("investigation fork", CodingAgentCli.forkName("investigation"));
		assertEquals("investigation fork", CodingAgentCli.forkName("  investigation  "));
	}

	@Test
	void cleansIncompleteTurnsWhenResuming() {
		AssistantMessage failed = new AssistantMessage("faux", "faux", "faux-1");
		failed.stopReason = StopReason.ERROR;
		AssistantMessage aborted = new AssistantMessage("faux", "faux", "faux-1");
		aborted.stopReason = StopReason.ABORTED;
		var user = CodingAgentOperations.userMessage("hello");
		var laterUser = CodingAgentOperations.userMessage("retry");
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
				"call-1", "read", CodingAgentOperations.jsonObject().put("path", "README.md"), null));

		var restored = CodingAgentOperations.INSTANCE.resumableMessages(
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
		toolUse.content.add(new ThinkingContent("Inspect the project", null, false));
		toolUse.content.add(new ToolCall(
				"call-1", "read", CodingAgentOperations.jsonObject().put("path", "README.md"), null));
		AssistantMessage answer = new AssistantMessage("faux", "github-copilot", "gpt-5.4");
		answer.content.add(new TextContent("The project is ready.", null));
		List<Message> messages = List.of(
				CodingAgentOperations.userMessage("Check the project"),
				toolUse,
				new ToolResultMessage(
						"call-1",
						"read",
						List.of(new TextContent("line one\nline two", null)),
						null,
						false,
						System.currentTimeMillis()),
				answer);

		String visible = new CodingAgentCli().renderSessionScreen(model, messages, false);
		String hidden = new CodingAgentCli().renderSessionScreen(model, messages, true);
		String styled = new CodingAgentCli().renderSessionScreen(model, messages, true);

		assertTrue(visible.startsWith("codingagent "));
		assertTrue(CodingAgentCli.stripAnsi(visible).contains("\n> Check the project\n"));
		assertTrue(CodingAgentCli.stripAnsi(visible).contains("Thinking:\nInspect the project"));
		assertTrue(visible.contains("[read] Reading README.md"));
		assertTrue(visible.contains("Done: Read 2 line(s)."));
		assertTrue(visible.contains("The project is ready."));
		assertFalse(hidden.contains("Inspect the project"));
		assertTrue(styled.contains("\u001b[48;5;236m\u001b[K> Check the project\u001b[0m"));
	}

	@Test
	void displaysFinalAssistantErrorsEvenAfterEarlierStreamedText() {
		AssistantMessage failed = new AssistantMessage("openai-completions", "github-copilot", "claude-fable-5");
		failed.stopReason = StopReason.ERROR;
		failed.errorMessage = "java.net.ConnectException";

		assertEquals("Error: java.net.ConnectException", CodingAgentCli.finalAssistantOutput(failed, true));
	}

	@Test
	void doesNotRepeatSuccessfulFinalOutputAfterItWasStreamed() {
		AssistantMessage response = new AssistantMessage("openai-completions", "github-copilot", "claude-fable-5");
		response.content.add(new TextContent("Done.", null));

		assertNull(CodingAgentCli.finalAssistantOutput(response, true));
		assertEquals("Done.", CodingAgentCli.finalAssistantOutput(response, false));
	}

	@Test
	void rendersAssistantErrorsAfterPartialContent() {
		Model model = model("gpt-5.4");
		AssistantMessage failed = new AssistantMessage("openai-completions", "github-copilot", "claude-fable-5");
		failed.stopReason = StopReason.ERROR;
		failed.errorMessage = "OpenAI tool call arguments must be a JSON object";
		failed.content.add(new TextContent("Checking the source.", null));
		failed.content.add(new ToolCall(
				"call-1", "read", CodingAgentOperations.jsonObject().put("path", "README.md"), null));

		String screen = new CodingAgentCli().renderSessionScreen(model, List.of(CodingAgentOperations.userMessage("Check it"), failed), false);

		assertTrue(screen.contains("Checking the source."));
		assertTrue(screen.contains("[read] Reading README.md"));
		assertTrue(screen.contains("Error: OpenAI tool call arguments must be a JSON object"));
	}

	@Test
	void describesToolWorkAndSummarizesResults() {
		var read = CodingAgentOperations.jsonObject()
				.put("path", "src/main/java/com.quaxt.codingagent/cli/Main.java")
				.put("offset", 10)
				.put("limit", 20);
		var shell = CodingAgentOperations.jsonObject().put("command", "mvn test");

		assertEquals(
				"Reading src/main/java/com.quaxt.codingagent/cli/Main.java (lines 10-29)",
				CodingAgentCli.toolCallDescription("read", read));
		assertEquals("mvn test", CodingAgentCli.toolCallDescription("shell", shell));
		assertEquals(
				"Read 2 line(s).",
				CodingAgentCli.toolResultSummary(
						"read", CodingAgentOperations.toolResultText("package works.earendil;\npublic final class Main {}")));
		assertEquals(
				"Build completed successfully.",
				CodingAgentCli.toolResultSummary("shell", CodingAgentOperations.toolResultText("Build completed successfully.")));
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

		assertEquals("GPT-5.6 Sol Max (0%)", CodingAgentCli.modelStatus(model, ThinkingLevel.MAX, 0));
		assertEquals("GPT-5.6 Sol Medium (25%)", CodingAgentCli.modelStatus(model, ThinkingLevel.MEDIUM, 250_000));
		assertEquals("GPT-5.6 Sol (100%)", CodingAgentCli.modelStatus(model, ThinkingLevel.OFF, 1_000_000));
		assertEquals("GPT-5.6 Sol (0%)", CodingAgentCli.modelStatus(model, null, 0));
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

		assertEquals(0, CodingAgentCli.contextTokens(List.of(CodingAgentOperations.userMessage("hi"))));
		assertEquals(1_000, CodingAgentCli.contextTokens(List.of(CodingAgentOperations.userMessage("hi"), first)));
		assertEquals(2_500, CodingAgentCli.contextTokens(List.of(first, CodingAgentOperations.userMessage("more"), second)));
		assertEquals(2_500, CodingAgentCli.contextTokens(List.of(first, second, failed)));
	}

	@Test
	void abbreviatesTheHomeDirectoryInTheStatusBarPath() {
		Path home = Path.of("/Users/dev");
		Path elsewhere = Path.of("/opt/elsewhere");
		String absoluteElsewhere = elsewhere.toAbsolutePath().normalize().toString();

		assertEquals("~/xa/coding-agent", CodingAgentCli.displayPath(home, Path.of("/Users/dev/xa/coding-agent")));
		assertEquals("~", CodingAgentCli.displayPath(home, Path.of("/Users/dev")));
		assertEquals(absoluteElsewhere, CodingAgentCli.displayPath(home, elsewhere));
		assertEquals(absoluteElsewhere, CodingAgentCli.displayPath(Path.of(""), elsewhere));
	}

	@Test
	void readsTheGitBranchFromHeadWithoutSpawningGit(@TempDir Path repo) throws Exception {
		assertNull(CodingAgentCli.gitBranch(repo));

		Path gitDir = Files.createDirectories(repo.resolve(".git"));
		Files.writeString(gitDir.resolve("HEAD"), "ref: refs/heads/main\n");
		assertEquals("main", CodingAgentCli.gitBranch(repo));

		Path nested = Files.createDirectories(repo.resolve("src/deep"));
		assertEquals("main", CodingAgentCli.gitBranch(nested));

		Files.writeString(gitDir.resolve("HEAD"), "0123456789abcdef0123456789abcdef01234567\n");
		assertEquals("0123456", CodingAgentCli.gitBranch(repo));
	}

	@Test
	void readsTheGitBranchThroughAWorktreeGitFile(@TempDir Path root) throws Exception {
		Path gitDir = Files.createDirectories(root.resolve("main-checkout/.git/worktrees/feature"));
		Files.writeString(gitDir.resolve("HEAD"), "ref: refs/heads/feature-branch\n");
		Path worktree = Files.createDirectories(root.resolve("feature"));
		Files.writeString(worktree.resolve(".git"), "gitdir: " + gitDir + "\n");

		assertEquals("feature-branch", CodingAgentCli.gitBranch(worktree));
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

			assertEquals(0, new CodingAgentCli().cliRun(new String[] {"--mode", "rpc", "--model", "anthropic/claude-haiku-4-5", "--no-session"}));
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

		String rendered = plain(CodingAgentCli.renderTurnDetails(component, 100, 30));

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
		CodingAgentCli.renderTurnDetails(component, 100, 30);

		new CodingAgentCli().handleTurnDetailsInput(component, CodingAgentCli.key(TuiInput.KeyType.DOWN));
		new CodingAgentCli().handleTurnDetailsInput(component, CodingAgentCli.key(TuiInput.KeyType.ENTER));
		assertTrue(component.sections.get(1).expanded);
		assertFalse(component.sections.get(2).expanded);
		assertTrue(plain(CodingAgentCli.renderTurnDetails(component, 100, 30)).contains("secret read output"));

		new CodingAgentCli().handleTurnDetailsInput(component, CodingAgentCli.key(TuiInput.KeyType.EXPAND_TOOLS));
		assertTrue(component.sections.get(1).expanded);
		assertTrue(component.sections.get(2).expanded);
		new CodingAgentCli().handleTurnDetailsInput(component, CodingAgentCli.key(TuiInput.KeyType.EXPAND_TOOLS));
		assertFalse(component.sections.get(1).expanded);
		assertFalse(component.sections.get(2).expanded);

		new CodingAgentCli().handleTurnDetailsInput(component, CodingAgentCli.key(TuiInput.KeyType.TOGGLE_THINKING));
		assertFalse(component.sections.get(0).expanded);
		assertTrue(component.thinkingHidden);
	}

	@Test
	void scrollsWithinAnExpandedLongStep() {
		AssistantMessage assistant = new AssistantMessage("faux", "faux", "faux-1");
		assistant.content.add(new ThinkingContent(String.join("\n", IntStream.range(0, 30)
				.mapToObj(index -> "reasoning line " + index)
				.toList()), null, false));
		TurnDetailsComponent component = CodingAgentCli.turnDetailsForLatestTurn(
				List.of(CodingAgentOperations.userMessage("inspect"), assistant), false);

		String firstPage = plain(CodingAgentCli.renderTurnDetails(component, 60, 8));
		new CodingAgentCli().handleTurnDetailsInput(component, CodingAgentCli.key(TuiInput.KeyType.PAGE_DOWN));
		String secondPage = plain(CodingAgentCli.renderTurnDetails(component, 60, 8));

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
		List<Message> messages = List.of(CodingAgentOperations.userMessage("old"), old, CodingAgentOperations.userMessage("new"), latest);

		TurnDetailsComponent component = CodingAgentCli.turnDetailsForLatestTurn(messages, false);
		String rendered = plain(CodingAgentCli.renderTurnDetails(component, 80, 20));

		assertEquals(1, component.sections.size());
		assertTrue(rendered.contains("new thought"));
		assertFalse(rendered.contains("old thought"));
		assertFalse(rendered.contains("\u001b"));
	}

	private static TurnDetailsComponent details(boolean hideThinking) {
		AssistantMessage assistant = new AssistantMessage("faux", "faux", "faux-1");
		assistant.content.add(new ThinkingContent("Inspect the repository\nThen choose files", null, false));
		assistant.content.add(new ToolCall(
				"read-1", "read", CodingAgentOperations.jsonObject().put("path", "README.md"), null));
		assistant.content.add(new ToolCall(
				"bash-1", "bash", CodingAgentOperations.jsonObject().put("command", "mvn test"), null));
		return CodingAgentCli.turnDetailsForLatestTurn(
				List.of(
						CodingAgentOperations.userMessage("inspect"),
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
		return CodingAgentCli.stripAnsi(String.join("\n", lines));
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
		FuzzySelector<String> selector = CodingAgentCli.fuzzySelector("Models", items, 2, true);

		CodingAgentCli.handleFuzzySelectorInput(
				selector, new TuiInput.Key(TuiInput.KeyType.PASTE, "terra copilot"));

		assertEquals("terra copilot", selector.query.toString());
		assertEquals(List.of("terra"), selector.filteredItems.stream().map(item -> item.value).toList());
		CodingAgentCli.handleFuzzySelectorInput(selector, CodingAgentCli.key(TuiInput.KeyType.ENTER));
		assertTrue(selector.complete);
		assertEquals("terra", selector.result);
	}

	@Test
	void restoresCurrentSelectionWhenSearchIsClearedAndSupportsCancel() {
		List<SelectItem<String>> items = List.of(
				new SelectItem<>("one", "One", "", "One"),
				new SelectItem<>("two", "Two", "", "Two"),
				new SelectItem<>("three", "Three", "", "Three"));
		FuzzySelector<String> selector = CodingAgentCli.fuzzySelector("Options", items, 1, true);

		CodingAgentCli.handleFuzzySelectorInput(
				selector, new TuiInput.Key(TuiInput.KeyType.CHARACTER, "t"));
		assertEquals(0, selector.selectedIndex);
		CodingAgentCli.handleFuzzySelectorInput(selector, CodingAgentCli.key(TuiInput.KeyType.CLEAR));
		assertEquals(1, selector.selectedIndex);
		CodingAgentCli.handleFuzzySelectorInput(selector, CodingAgentCli.key(TuiInput.KeyType.ESCAPE));

		assertTrue(selector.complete);
		assertNull(selector.result);
	}

	@Test
	void restoresFirstSelectionWhenBackspaceClearsSearchWithoutCurrentItem() {
		List<SelectItem<String>> items = List.of(
				new SelectItem<>("one", "One", "", "One"),
				new SelectItem<>("two", "Two", "", "Two"));
		FuzzySelector<String> selector = CodingAgentCli.fuzzySelector("Options", items, -1, true);

		CodingAgentCli.handleFuzzySelectorInput(
				selector, new TuiInput.Key(TuiInput.KeyType.CHARACTER, "o"));
		CodingAgentCli.handleFuzzySelectorInput(
				selector, CodingAgentCli.key(TuiInput.KeyType.BACKSPACE));

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
		FuzzySelector<Integer> selector = CodingAgentCli.fuzzySelector("Models", items, 12, true);

		String frame = String.join("\n", CodingAgentCli.renderFuzzySelector(
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
		setCanonicalAttributes(terminal);
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch interrupted = new CountDownLatch(1);
		CountDownLatch inputMayFinish = new CountDownLatch(1);

		CodingAgentCli interactive = newInteractiveTerminal(
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
									assertFalse(terminal.getAttributes().getLocalFlag(LocalFlag.ICANON));
									started.countDown();
									assertTrue(interrupted.await(5, TimeUnit.SECONDS));
									return "stopped";
								}, interrupted::countDown));
			} finally {
				inputMayFinish.countDown();
			}
			input.join();

			assertEquals("stopped", result);
			assertTrue(terminal.getAttributes().getLocalFlag(LocalFlag.ICANON));
			assertTrue(terminal.getAttributes().getLocalFlag(LocalFlag.ECHO));
		} finally {
			interactive.closeTerminal();
		}
	}

	@Test
	void suppliesFallbackDimensionsWhenTheTerminalReportsZeroSize() throws Exception {
		TerminalFixture fixture = terminal();
		fixture.terminal().setSize(org.jline.terminal.Size.of(0, 0));

		CodingAgentCli ignored = newInteractiveTerminal(
				fixture.terminal(), () -> null, false);
		try {
			assertEquals(80, fixture.terminal().getColumns());
			assertEquals(24, fixture.terminal().getRows());
		} finally {
			ignored.closeTerminal();
		}
	}

	@Test
	void fillsThePromptLineWithADarkGreyBackground() throws Exception {
		TerminalFixture fixture = terminal();

		CodingAgentCli interactive = newInteractiveTerminal(
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

		CodingAgentCli interactive = newInteractiveTerminal(
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

		CodingAgentCli interactive = newInteractiveTerminal(
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
		} finally {
			interactive.closeTerminal();
		}
	}

	@Test
	void shiftEnterVariantsInsertNewlinesAndEnterSubmitsThePrompt() throws Exception {
		TerminalFixture fixture = terminal();

		CodingAgentCli interactive = newInteractiveTerminal(
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

	@Test
	void bracketedPasteKeepsMultilineTextUntilEnter() throws Exception {
		TerminalFixture fixture = terminal();

		CodingAgentCli interactive = newInteractiveTerminal(
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

		CodingAgentCli interactive = newInteractiveTerminal(
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

		CodingAgentCli interactive = newInteractiveTerminal(
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
		setCanonicalAttributes(terminal);
		AtomicBoolean suspended = new AtomicBoolean();

		CodingAgentCli interactive = newInteractiveTerminal(
				terminal,
				() -> {
					assertTrue(terminal.getAttributes().getLocalFlag(LocalFlag.ICANON));
					assertTrue(terminal.getAttributes().getLocalFlag(LocalFlag.ECHO));
					suspended.set(true);
					fixture.output().writeBytes("shell activity\n".getBytes(StandardCharsets.UTF_8));
					fixture.input().write("d\r".getBytes(StandardCharsets.UTF_8));
					fixture.input().flush();
					return null;
				},
				true);
		try {
			interactive.println("conversation before suspend");
			fixture.input().write("abc\u001a".getBytes(StandardCharsets.UTF_8));
			fixture.input().flush();
			String line = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> interactive.readLine("> "));

			assertEquals("abcd", line);
			assertTrue(suspended.get());
			String written = fixture.output().toString(StandardCharsets.UTF_8);
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

		CodingAgentCli interactive = newInteractiveTerminal(
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

		CodingAgentCli interactive = newInteractiveTerminal(
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

		CodingAgentCli interactive = newInteractiveTerminal(
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
		setCanonicalAttributes(terminal);
		AtomicBoolean suspended = new AtomicBoolean();

		CodingAgentCli interactive = newInteractiveTerminal(
				terminal,
				() -> {
					assertTrue(terminal.getAttributes().getLocalFlag(LocalFlag.ICANON));
					assertTrue(terminal.getAttributes().getLocalFlag(LocalFlag.ECHO));
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
					interactive.runComponent(CodingAgentCli.fuzzySelectorComponent(CodingAgentCli.fuzzySelector(
									"Details", List.of(new SelectItem<>("done", "Done", "", "Done")), 0, false)));
				} catch (java.io.IOException error) {
					throw new AssertionError(error);
				}
			});
			fixture.input().write("ab\u000f\u001a".getBytes(StandardCharsets.UTF_8));
			fixture.input().flush();

			String line = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> interactive.readLine("> "));

			assertEquals("abcd", line);
			assertTrue(suspended.get());
			String written = fixture.output().toString(StandardCharsets.UTF_8);
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
		setCanonicalAttributes(terminal);
		AtomicBoolean suspended = new AtomicBoolean();

		CodingAgentCli interactive = newInteractiveTerminal(
				terminal,
				() -> {
					assertTrue(terminal.getAttributes().getLocalFlag(LocalFlag.ICANON));
					assertTrue(terminal.getAttributes().getLocalFlag(LocalFlag.ECHO));
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
					() -> interactive.runComponent(CodingAgentCli.fuzzySelectorComponent(CodingAgentCli.fuzzySelector(
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
		String line = CodingAgentCli.statusBarLine(
				"", CodingAgentCli.StatusAccent.NONE,
				"~/xa/coding-agent [main]", "GPT-5.6 Sol Max (0%)", 60);

		assertEquals(60, CodingAgentCli.visibleWidth(line));
		assertTrue(CodingAgentCli.stripAnsi(line).startsWith("~/xa/coding-agent [main]"));
		assertTrue(CodingAgentCli.stripAnsi(line).endsWith("GPT-5.6 Sol Max (0%)"));

		String styled = CodingAgentCli.statusBarLine(
				"", CodingAgentCli.StatusAccent.NONE, "left", "right", 20);
		assertTrue(styled.startsWith(TerminalStyle.MUTED));
		assertTrue(styled.endsWith(TerminalStyle.RESET));

		String narrow = CodingAgentCli.statusBarLine(
				"", CodingAgentCli.StatusAccent.NONE,
				"~/a/very/long/working/directory", "GPT-5.6 Sol Max (0%)", 30);
		assertTrue(CodingAgentCli.visibleWidth(narrow) <= 30);
		assertTrue(CodingAgentCli.stripAnsi(narrow).endsWith("GPT-5.6 Sol Max (0%)"));

		assertEquals("left only", CodingAgentCli.stripAnsi(CodingAgentCli.statusBarLine(
				"", CodingAgentCli.StatusAccent.NONE, "left only", "", 20)));
		assertEquals("", CodingAgentCli.statusBarLine(
				"", CodingAgentCli.StatusAccent.NONE, "", "", 20));
	}

	@Test
	void activityHasPriorityAndOnlyReadyUsesTheGreenAccent() {
		String ready = CodingAgentCli.statusBarLine(
				"● Ready",
				CodingAgentCli.StatusAccent.READY,
				"~/xa/coding-agent [main]",
				"GPT-5.6 Sol Max (24%)",
				80);
		String waiting = CodingAgentCli.statusBarLine(
				"◐ Waiting for model · 12s",
				CodingAgentCli.StatusAccent.ACTIVE,
				"~/xa/coding-agent [main]",
				"GPT-5.6 Sol Max (24%)",
				80);

		assertTrue(ready.startsWith(CodingAgentCli.readyStatus() + "● Ready"));
		assertTrue(ready.contains(TerminalStyle.RESET + TerminalStyle.MUTED + " │ "));
		assertTrue(waiting.startsWith(CodingAgentCli.activeStatus() + "◐ Waiting for model"));
		assertFalse(waiting.contains(CodingAgentCli.readyStatus()));
		assertEquals(80, CodingAgentCli.visibleWidth(ready));
		assertEquals(80, CodingAgentCli.visibleWidth(waiting));

		for (CodingAgentCli.StatusAccent accent : List.of(
				CodingAgentCli.StatusAccent.NONE,
				CodingAgentCli.StatusAccent.ACTIVE,
				CodingAgentCli.StatusAccent.TOOL,
				CodingAgentCli.StatusAccent.WARNING)) {
			String line = CodingAgentCli.statusBarLine(
					"Busy", accent, "path", "model", 30);
			assertFalse(line.contains(CodingAgentCli.readyStatus()), accent.toString());
		}
	}

	@Test
	void narrowStatusBarsKeepActivityBeforeMetadata() {
		String line = CodingAgentCli.statusBarLine(
				"◐ Waiting for model · 12s",
				CodingAgentCli.StatusAccent.ACTIVE,
				"~/a/very/long/working/directory",
				"GPT-5.6 Sol Max (24%)",
				20);

		assertEquals("◐ Waiting for mod...", CodingAgentCli.stripAnsi(line));
		assertFalse(line.contains("GPT"));
		assertFalse(line.contains("~/"));
		assertEquals(20, CodingAgentCli.visibleWidth(line));
	}

	@Test
	void statusBarUpdatesAreIgnoredOnTerminalsWithoutCursorAddressing() throws Exception {
		TerminalFixture fixture = terminal();

		CodingAgentCli interactive = newInteractiveTerminal(
				fixture.terminal(), () -> null, false);
		try {
			interactive.setStatus("", CodingAgentCli.StatusAccent.NONE, "~/xa/coding-agent [main]", "GPT-5.6 Sol Max (0%)");
			interactive.println("conversation output");

			assertTrue(fixture.output().toString(StandardCharsets.UTF_8).contains("conversation output"));
		} finally {
			interactive.closeTerminal();
		}
	}

	private static CodingAgentCli newInteractiveTerminal(
			Terminal terminal, java.util.concurrent.Callable<Void> suspendAction, boolean supportsSuspend) {
		CodingAgentCli cli = new CodingAgentCli();
		cli.newInteractiveTerminal(terminal, suspendAction, supportsSuspend);
		return cli;
	}

	private static TuiComponent<Void> immediateComponent() {
		return new TuiComponent<>(frame -> List.of("details"), input -> {}, () -> true, () -> null);
	}

	private static TerminalFixture terminal() throws Exception {
		PipedInputStream input = new PipedInputStream();
		PipedOutputStream inputWriter = new PipedOutputStream(input);
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		Terminal terminal = new DumbTerminal("test", "xterm-256color", input, output, StandardCharsets.UTF_8);
		terminal.setSize(org.jline.terminal.Size.of(80, 24));
		return new TerminalFixture(terminal, inputWriter, output);
	}

	private static void setCanonicalAttributes(Terminal terminal) {
		var attributes = terminal.getAttributes();
		attributes.setLocalFlag(LocalFlag.ICANON, true);
		attributes.setLocalFlag(LocalFlag.ECHO, true);
		terminal.setAttributes(attributes);
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
		assertEquals(
				CodingAgentCli.key(TuiInput.KeyType.ESCAPE),
				CodingAgentCli.parseInputSequence("\u001b"));
		assertEquals(
				CodingAgentCli.key(TuiInput.KeyType.CANCEL),
				CodingAgentCli.parseInputSequence("\u0003"));
		assertEquals(
				CodingAgentCli.key(TuiInput.KeyType.UP),
				CodingAgentCli.parseInputSequence("\u001b[A"));
		assertEquals(
				CodingAgentCli.key(TuiInput.KeyType.PAGE_DOWN),
				CodingAgentCli.parseInputSequence("\u001b[6~"));
		assertEquals(
				CodingAgentCli.key(TuiInput.KeyType.SUSPEND),
				CodingAgentCli.parseInputSequence("\u001a"));
		assertEquals(
				CodingAgentCli.key(TuiInput.KeyType.EXPAND_TOOLS),
				CodingAgentCli.parseInputSequence("\u000f"));
		assertEquals(
				CodingAgentCli.key(TuiInput.KeyType.TOGGLE_THINKING),
				CodingAgentCli.parseInputSequence("\u0014"));
		assertEquals(
				CodingAgentCli.key(TuiInput.KeyType.EXIT),
				CodingAgentCli.parseInputSequence("\u0004"));
		assertEquals(
				new TuiInput.Mouse(TuiInput.MouseAction.PRESS, 0, 12, 7),
				CodingAgentCli.parseInputSequence("\u001b[<0;12;7M"));
		assertEquals(
				new TuiInput.Mouse(TuiInput.MouseAction.SCROLL_DOWN, 1, 4, 9),
				CodingAgentCli.parseInputSequence("\u001b[<65;4;9M"));
	}
}
