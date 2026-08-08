package works.earendil.pi.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import works.earendil.pi.agent.AgentTool;
import works.earendil.pi.ai.json.Json;
import works.earendil.pi.ai.types.AssistantMessage;
import works.earendil.pi.ai.types.Message;
import works.earendil.pi.ai.types.Model;
import works.earendil.pi.ai.types.ModelCost;
import works.earendil.pi.ai.types.StopReason;
import works.earendil.pi.ai.types.TextContent;
import works.earendil.pi.ai.types.ThinkingContent;
import works.earendil.pi.ai.types.ThinkingLevel;
import works.earendil.pi.ai.types.ToolCall;
import works.earendil.pi.ai.types.ToolResultMessage;
import works.earendil.pi.ai.types.UserMessage;
import works.earendil.pi.tui.Theme;

class InteractiveShellTest {
	@Test
	void prefersGpt54AndFallsBackToFirstEnabledCopilotModel() {
		Model fallback = model("claude-sonnet-4.5");
		Model preferred = model("gpt-5.4");

		assertEquals(preferred, InteractiveShell.preferredCopilotModel(List.of(fallback, preferred)));
		assertEquals(fallback, InteractiveShell.preferredCopilotModel(List.of(fallback)));
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

		assertTrue(visible.startsWith("pi Java "));
		assertTrue(visible.contains("\n> Check the project\n"));
		assertTrue(visible.contains("Thinking:\nInspect the project"));
		assertTrue(visible.contains("[read] Reading README.md"));
		assertTrue(visible.contains("Done: Read 2 line(s)."));
		assertTrue(visible.contains("The project is ready."));
		assertFalse(hidden.contains("Inspect the project"));
	}

	@Test
	void describesToolWorkAndSummarizesResults() {
		var read = Json.object().put("path", "java/pi-cli/src/Main.java").put("offset", 10).put("limit", 20);
		var bash = Json.object().put("command", "mvn -pl pi-cli test");

		assertEquals(
				"Reading java/pi-cli/src/Main.java (lines 10-29)",
				InteractiveShell.toolDescription("read", read));
		assertEquals("mvn -pl pi-cli test", InteractiveShell.toolDescription("bash", bash));
		assertEquals(
				"Read 2 line(s).",
				InteractiveShell.toolResultSummary(
						"read", AgentTool.ToolResult.text("package works.earendil;\npublic final class Main {}")));
		assertEquals(
				"Build completed successfully.",
				InteractiveShell.toolResultSummary("bash", AgentTool.ToolResult.text("Build completed successfully.")));
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
