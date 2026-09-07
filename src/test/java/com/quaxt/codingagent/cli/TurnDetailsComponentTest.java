package com.quaxt.codingagent.cli;

import com.quaxt.codingagent.CodingAgentCli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import com.quaxt.codingagent.CodingAgentOperations;
import com.quaxt.codingagent.ai.types.AssistantMessage;
import com.quaxt.codingagent.ai.types.Message;
import com.quaxt.codingagent.ai.types.TextContent;
import com.quaxt.codingagent.ai.types.ThinkingContent;
import com.quaxt.codingagent.ai.types.ToolCall;
import com.quaxt.codingagent.ai.types.ToolResultMessage;
import com.quaxt.codingagent.tui.TuiInput;

class TurnDetailsComponentTest {
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
}
