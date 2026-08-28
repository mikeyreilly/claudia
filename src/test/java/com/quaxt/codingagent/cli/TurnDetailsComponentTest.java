package com.quaxt.codingagent.cli;

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
import com.quaxt.codingagent.tui.Theme;
import com.quaxt.codingagent.tui.TuiInput;

class TurnDetailsComponentTest {
	@Test
	void rendersThinkingAndKeepsToolResultsCollapsedInitially() {
		TurnDetailsComponent component = details(false);

		String rendered = plain(CodingAgentOperations.renderTurnDetails(component, 100, 30, Theme.PLAIN));

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
		CodingAgentOperations.renderTurnDetails(component, 100, 30, Theme.PLAIN);

		CodingAgentOperations.handleTurnDetailsInput(component, CodingAgentOperations.key(TuiInput.KeyType.DOWN));
		CodingAgentOperations.handleTurnDetailsInput(component, CodingAgentOperations.key(TuiInput.KeyType.ENTER));
		assertTrue(component.sections.get(1).expanded);
		assertFalse(component.sections.get(2).expanded);
		assertTrue(plain(CodingAgentOperations.renderTurnDetails(component, 100, 30, Theme.PLAIN)).contains("secret read output"));

		CodingAgentOperations.handleTurnDetailsInput(component, CodingAgentOperations.key(TuiInput.KeyType.EXPAND_TOOLS));
		assertTrue(component.sections.get(1).expanded);
		assertTrue(component.sections.get(2).expanded);
		CodingAgentOperations.handleTurnDetailsInput(component, CodingAgentOperations.key(TuiInput.KeyType.EXPAND_TOOLS));
		assertFalse(component.sections.get(1).expanded);
		assertFalse(component.sections.get(2).expanded);

		CodingAgentOperations.handleTurnDetailsInput(component, CodingAgentOperations.key(TuiInput.KeyType.TOGGLE_THINKING));
		assertFalse(component.sections.get(0).expanded);
		assertTrue(component.thinkingHidden);
	}

	@Test
	void scrollsWithinAnExpandedLongStep() {
		AssistantMessage assistant = new AssistantMessage("faux", "faux", "faux-1");
		assistant.content.add(new ThinkingContent(String.join("\n", IntStream.range(0, 30)
				.mapToObj(index -> "reasoning line " + index)
				.toList()), null, false));
		TurnDetailsComponent component = CodingAgentOperations.turnDetailsForLatestTurn(
				List.of(CodingAgentOperations.userMessage("inspect"), assistant), false);

		String firstPage = plain(CodingAgentOperations.renderTurnDetails(component, 60, 8, Theme.PLAIN));
		CodingAgentOperations.handleTurnDetailsInput(component, CodingAgentOperations.key(TuiInput.KeyType.PAGE_DOWN));
		String secondPage = plain(CodingAgentOperations.renderTurnDetails(component, 60, 8, Theme.PLAIN));

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

		TurnDetailsComponent component = CodingAgentOperations.turnDetailsForLatestTurn(messages, false);
		String rendered = plain(CodingAgentOperations.renderTurnDetails(component, 80, 20, Theme.PLAIN));

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
		return CodingAgentOperations.turnDetailsForLatestTurn(
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
		return CodingAgentOperations.stripAnsi(String.join("\n", lines));
	}
}
