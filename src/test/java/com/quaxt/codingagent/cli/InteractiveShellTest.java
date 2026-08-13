package com.quaxt.codingagent.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.quaxt.codingagent.agent.AgentTool;
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
import com.quaxt.codingagent.ai.types.Usage;
import com.quaxt.codingagent.ai.types.UserMessage;
import com.quaxt.codingagent.tui.Theme;

class InteractiveShellTest {
	@Test
	void prefersGpt54AndFallsBackToFirstEnabledCopilotModel() {
		Model fallback = model("claude-sonnet-4.5");
		Model preferred = model("gpt-5.4");

		assertEquals(preferred, InteractiveShell.preferredCopilotModel(List.of(fallback, preferred)));
		assertEquals(fallback, InteractiveShell.preferredCopilotModel(List.of(fallback)));
	}

	@Test
	void prefersTerraWhenRestoringChatGptWithoutSavedSettings() {
		Model fallback = model("gpt-5.4");
		Model terra = model("gpt-5.6-terra");

		assertEquals(terra, InteractiveShell.preferredChatGptModel(List.of(fallback, terra)));
		assertEquals(fallback, InteractiveShell.preferredChatGptModel(List.of(fallback)));
	}

	@Test
	void defaultsReasoningModelsToMediumThinking() {
		Model reasoning = model("gpt-5.4", true);

		assertEquals(ThinkingLevel.MEDIUM, InteractiveShell.initialThinkingLevel(reasoning, null));
		assertEquals(ThinkingLevel.HIGH, InteractiveShell.initialThinkingLevel(reasoning, ThinkingLevel.HIGH));
		assertEquals(ThinkingLevel.OFF, InteractiveShell.initialThinkingLevel(model("gpt-4.1"), null));
	}

	@Test
	void cleansIncompleteTurnsWhenResuming() {
		AssistantMessage failed = new AssistantMessage("faux", "faux", "faux-1");
		failed.stopReason = StopReason.ERROR;
		AssistantMessage aborted = new AssistantMessage("faux", "faux", "faux-1");
		aborted.stopReason = StopReason.ABORTED;
		var user = UserMessage.of("hello");
		var laterUser = UserMessage.of("retry");
		var orphanedResult = ToolResultMessage.text("missing-call", "read", "result", false);
		AssistantMessage toolUse = new AssistantMessage("faux", "faux", "faux-1");
		toolUse.stopReason = StopReason.TOOL_USE;
		toolUse.content.add(new ToolCall("call-1", "read", Json.object().put("path", "README.md")));

		var restored = InteractiveShell.resumableMessages(
				List.of(user, failed, orphanedResult, toolUse, laterUser, aborted));

		assertEquals(4, restored.size());
		assertEquals(user, restored.get(0));
		assertEquals(toolUse, restored.get(1));
		assertEquals(laterUser, restored.get(3));
		ToolResultMessage synthetic = (ToolResultMessage) restored.get(2);
		assertEquals("call-1", synthetic.toolCallId());
		assertTrue(synthetic.isError());
	}

	@Test
	void rebuildsTheVisibleTranscriptWhenResumingASession() {
		Model model = model("gpt-5.4", true);
		AssistantMessage toolUse = new AssistantMessage("faux", "github-copilot", "gpt-5.4");
		toolUse.content.add(new ThinkingContent("Inspect the project"));
		toolUse.content.add(new ToolCall("call-1", "read", Json.object().put("path", "README.md")));
		AssistantMessage answer = new AssistantMessage("faux", "github-copilot", "gpt-5.4");
		answer.content.add(new TextContent("The project is ready."));
		List<Message> messages = List.of(
				UserMessage.of("Check the project"),
				toolUse,
				ToolResultMessage.text("call-1", "read", "line one\nline two", false),
				answer);

		String visible = InteractiveShell.renderSessionScreen(model, messages, false, Theme.PLAIN);
		String hidden = InteractiveShell.renderSessionScreen(model, messages, true, Theme.PLAIN);
		String dark = InteractiveShell.renderSessionScreen(model, messages, true, Theme.DARK);

		assertTrue(visible.startsWith("codingagent "));
		assertTrue(visible.contains("\n> Check the project\n"));
		assertTrue(visible.contains("Thinking:\nInspect the project"));
		assertTrue(visible.contains("[read] Reading README.md"));
		assertTrue(visible.contains("Done: Read 2 line(s)."));
		assertTrue(visible.contains("The project is ready."));
		assertFalse(hidden.contains("Inspect the project"));
		assertTrue(dark.contains("\u001b[48;5;236m\u001b[K> Check the project\u001b[0m"));
	}

	@Test
	void rendersAssistantErrorsAfterPartialContent() {
		Model model = model("gpt-5.4");
		AssistantMessage failed = new AssistantMessage("openai-completions", "github-copilot", "claude-fable-5");
		failed.stopReason = StopReason.ERROR;
		failed.errorMessage = "OpenAI tool call arguments must be a JSON object";
		failed.content.add(new TextContent("Checking the source."));
		failed.content.add(new ToolCall("call-1", "read", Json.object().put("path", "README.md")));

		String screen = InteractiveShell.renderSessionScreen(model, List.of(UserMessage.of("Check it"), failed), false, Theme.PLAIN);

		assertTrue(screen.contains("Checking the source."));
		assertTrue(screen.contains("[read] Reading README.md"));
		assertTrue(screen.contains("Error: OpenAI tool call arguments must be a JSON object"));
	}

	@Test
	void describesToolWorkAndSummarizesResults() {
		var read = Json.object()
				.put("path", "src/main/java/com.quaxt.codingagent/cli/Main.java")
				.put("offset", 10)
				.put("limit", 20);
		var shell = Json.object().put("command", "mvn test");

		assertEquals(
				"Reading src/main/java/com.quaxt.codingagent/cli/Main.java (lines 10-29)",
				InteractiveShell.toolDescription("read", read));
		assertEquals("mvn test", InteractiveShell.toolDescription("shell", shell));
		assertEquals(
				"Read 2 line(s).",
				InteractiveShell.toolResultSummary(
						"read", AgentTool.ToolResult.text("package works.earendil;\npublic final class Main {}")));
		assertEquals(
				"Build completed successfully.",
				InteractiveShell.toolResultSummary("shell", AgentTool.ToolResult.text("Build completed successfully.")));
	}

	@Test
	void formatsModelThinkingLevelAndContextUseForTheStatusBar() {
		Model model = Model.builder()
				.id("gpt-5.6-sol")
				.name("GPT-5.6 Sol")
				.api("openai-responses")
				.provider("github-copilot")
				.baseUrl("https://example.test")
				.reasoning(true)
				.cost(ModelCost.FREE)
				.contextWindow(1_000_000)
				.maxTokens(1)
				.build();

		assertEquals("GPT-5.6 Sol Max (0%)", InteractiveShell.modelStatus(model, ThinkingLevel.MAX, 0));
		assertEquals("GPT-5.6 Sol Medium (25%)", InteractiveShell.modelStatus(model, ThinkingLevel.MEDIUM, 250_000));
		assertEquals("GPT-5.6 Sol (100%)", InteractiveShell.modelStatus(model, ThinkingLevel.OFF, 1_000_000));
		assertEquals("GPT-5.6 Sol (0%)", InteractiveShell.modelStatus(model, null, 0));
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

		assertEquals(0, InteractiveShell.contextTokens(List.of(UserMessage.of("hi"))));
		assertEquals(1_000, InteractiveShell.contextTokens(List.of(UserMessage.of("hi"), first)));
		assertEquals(2_500, InteractiveShell.contextTokens(List.of(first, UserMessage.of("more"), second)));
		assertEquals(2_500, InteractiveShell.contextTokens(List.of(first, second, failed)));
	}

	@Test
	void abbreviatesTheHomeDirectoryInTheStatusBarPath() {
		Path home = Path.of("/Users/dev");

		assertEquals("~/xa/coding-agent", InteractiveShell.displayPath(home, Path.of("/Users/dev/xa/coding-agent")));
		assertEquals("~", InteractiveShell.displayPath(home, Path.of("/Users/dev")));
		assertEquals("/opt/elsewhere", InteractiveShell.displayPath(home, Path.of("/opt/elsewhere")));
		assertEquals("/opt/elsewhere", InteractiveShell.displayPath(Path.of(""), Path.of("/opt/elsewhere")));
	}

	@Test
	void readsTheGitBranchFromHeadWithoutSpawningGit(@TempDir Path repo) throws Exception {
		assertNull(InteractiveShell.gitBranch(repo));

		Path gitDir = Files.createDirectories(repo.resolve(".git"));
		Files.writeString(gitDir.resolve("HEAD"), "ref: refs/heads/main\n");
		assertEquals("main", InteractiveShell.gitBranch(repo));

		Path nested = Files.createDirectories(repo.resolve("src/deep"));
		assertEquals("main", InteractiveShell.gitBranch(nested));

		Files.writeString(gitDir.resolve("HEAD"), "0123456789abcdef0123456789abcdef01234567\n");
		assertEquals("0123456", InteractiveShell.gitBranch(repo));
	}

	@Test
	void readsTheGitBranchThroughAWorktreeGitFile(@TempDir Path root) throws Exception {
		Path gitDir = Files.createDirectories(root.resolve("main-checkout/.git/worktrees/feature"));
		Files.writeString(gitDir.resolve("HEAD"), "ref: refs/heads/feature-branch\n");
		Path worktree = Files.createDirectories(root.resolve("feature"));
		Files.writeString(worktree.resolve(".git"), "gitdir: " + gitDir + "\n");

		assertEquals("feature-branch", InteractiveShell.gitBranch(worktree));
	}

	private static Model model(String id) {
		return model(id, false);
	}

	private static Model model(String id, boolean reasoning) {
		return Model.builder()
				.id(id)
				.api("openai-responses")
				.provider("github-copilot")
				.baseUrl("https://example.test")
				.reasoning(reasoning)
				.cost(ModelCost.FREE)
				.contextWindow(1)
				.maxTokens(1)
				.build();
	}
}
