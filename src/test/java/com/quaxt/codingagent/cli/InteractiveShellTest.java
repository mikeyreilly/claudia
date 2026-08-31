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
import com.quaxt.codingagent.CodingAgentOperations;
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

class InteractiveShellTest {
	@Test
	void prefersGpt54AndFallsBackToFirstEnabledCopilotModel() {
		Model fallback = model("claude-sonnet-4.5");
		Model preferred = model("gpt-5.4");

		assertEquals(preferred, CodingAgentOperations.preferredCopilotModel(List.of(fallback, preferred)));
		assertEquals(fallback, CodingAgentOperations.preferredCopilotModel(List.of(fallback)));
	}

	@Test
	void prefersTerraWhenRestoringChatGptWithoutSavedSettings() {
		Model fallback = model("gpt-5.4");
		Model terra = model("gpt-5.6-terra");

		assertEquals(terra, CodingAgentOperations.preferredChatGptModel(List.of(fallback, terra)));
		assertEquals(fallback, CodingAgentOperations.preferredChatGptModel(List.of(fallback)));
	}

	@Test
	void defaultsReasoningModelsToMediumThinking() {
		Model reasoning = model("gpt-5.4", true);

		assertEquals(ThinkingLevel.MEDIUM, CodingAgentOperations.initialThinkingLevel(reasoning, null));
		assertEquals(ThinkingLevel.HIGH, CodingAgentOperations.initialThinkingLevel(reasoning, ThinkingLevel.HIGH));
		assertEquals(ThinkingLevel.OFF, CodingAgentOperations.initialThinkingLevel(model("gpt-4.1"), null));
	}

	@Test
	void prepopulatesForkNamesFromTheCurrentSessionName() {
		assertEquals("fork", CodingAgentOperations.forkName(null));
		assertEquals("fork", CodingAgentOperations.forkName("  "));
		assertEquals("investigation fork", CodingAgentOperations.forkName("investigation"));
		assertEquals("investigation fork", CodingAgentOperations.forkName("  investigation  "));
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

		String visible = CodingAgentOperations.INSTANCE.renderSessionScreen(model, messages, false);
		String hidden = CodingAgentOperations.INSTANCE.renderSessionScreen(model, messages, true);
		String styled = CodingAgentOperations.INSTANCE.renderSessionScreen(model, messages, true);

		assertTrue(visible.startsWith("codingagent "));
		assertTrue(CodingAgentOperations.stripAnsi(visible).contains("\n> Check the project\n"));
		assertTrue(CodingAgentOperations.stripAnsi(visible).contains("Thinking:\nInspect the project"));
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

		assertEquals("Error: java.net.ConnectException", CodingAgentOperations.finalAssistantOutput(failed, true));
	}

	@Test
	void doesNotRepeatSuccessfulFinalOutputAfterItWasStreamed() {
		AssistantMessage response = new AssistantMessage("openai-completions", "github-copilot", "claude-fable-5");
		response.content.add(new TextContent("Done.", null));

		assertNull(CodingAgentOperations.finalAssistantOutput(response, true));
		assertEquals("Done.", CodingAgentOperations.finalAssistantOutput(response, false));
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

		String screen = CodingAgentOperations.INSTANCE.renderSessionScreen(model, List.of(CodingAgentOperations.userMessage("Check it"), failed), false);

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
				CodingAgentOperations.toolCallDescription("read", read));
		assertEquals("mvn test", CodingAgentOperations.toolCallDescription("shell", shell));
		assertEquals(
				"Read 2 line(s).",
				CodingAgentOperations.toolResultSummary(
						"read", CodingAgentOperations.toolResultText("package works.earendil;\npublic final class Main {}")));
		assertEquals(
				"Build completed successfully.",
				CodingAgentOperations.toolResultSummary("shell", CodingAgentOperations.toolResultText("Build completed successfully.")));
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

		assertEquals("GPT-5.6 Sol Max (0%)", CodingAgentOperations.modelStatus(model, ThinkingLevel.MAX, 0));
		assertEquals("GPT-5.6 Sol Medium (25%)", CodingAgentOperations.modelStatus(model, ThinkingLevel.MEDIUM, 250_000));
		assertEquals("GPT-5.6 Sol (100%)", CodingAgentOperations.modelStatus(model, ThinkingLevel.OFF, 1_000_000));
		assertEquals("GPT-5.6 Sol (0%)", CodingAgentOperations.modelStatus(model, null, 0));
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

		assertEquals(0, CodingAgentOperations.contextTokens(List.of(CodingAgentOperations.userMessage("hi"))));
		assertEquals(1_000, CodingAgentOperations.contextTokens(List.of(CodingAgentOperations.userMessage("hi"), first)));
		assertEquals(2_500, CodingAgentOperations.contextTokens(List.of(first, CodingAgentOperations.userMessage("more"), second)));
		assertEquals(2_500, CodingAgentOperations.contextTokens(List.of(first, second, failed)));
	}

	@Test
	void abbreviatesTheHomeDirectoryInTheStatusBarPath() {
		Path home = Path.of("/Users/dev");
		Path elsewhere = Path.of("/opt/elsewhere");
		String absoluteElsewhere = elsewhere.toAbsolutePath().normalize().toString();

		assertEquals("~/xa/coding-agent", CodingAgentOperations.displayPath(home, Path.of("/Users/dev/xa/coding-agent")));
		assertEquals("~", CodingAgentOperations.displayPath(home, Path.of("/Users/dev")));
		assertEquals(absoluteElsewhere, CodingAgentOperations.displayPath(home, elsewhere));
		assertEquals(absoluteElsewhere, CodingAgentOperations.displayPath(Path.of(""), elsewhere));
	}

	@Test
	void readsTheGitBranchFromHeadWithoutSpawningGit(@TempDir Path repo) throws Exception {
		assertNull(CodingAgentOperations.gitBranch(repo));

		Path gitDir = Files.createDirectories(repo.resolve(".git"));
		Files.writeString(gitDir.resolve("HEAD"), "ref: refs/heads/main\n");
		assertEquals("main", CodingAgentOperations.gitBranch(repo));

		Path nested = Files.createDirectories(repo.resolve("src/deep"));
		assertEquals("main", CodingAgentOperations.gitBranch(nested));

		Files.writeString(gitDir.resolve("HEAD"), "0123456789abcdef0123456789abcdef01234567\n");
		assertEquals("0123456", CodingAgentOperations.gitBranch(repo));
	}

	@Test
	void readsTheGitBranchThroughAWorktreeGitFile(@TempDir Path root) throws Exception {
		Path gitDir = Files.createDirectories(root.resolve("main-checkout/.git/worktrees/feature"));
		Files.writeString(gitDir.resolve("HEAD"), "ref: refs/heads/feature-branch\n");
		Path worktree = Files.createDirectories(root.resolve("feature"));
		Files.writeString(worktree.resolve(".git"), "gitdir: " + gitDir + "\n");

		assertEquals("feature-branch", CodingAgentOperations.gitBranch(worktree));
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
}
