package works.earendil.pi.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import works.earendil.pi.ai.json.Json;
import works.earendil.pi.ai.types.AssistantMessage;
import works.earendil.pi.ai.types.Message;
import works.earendil.pi.ai.types.ThinkingContent;
import works.earendil.pi.ai.types.ToolCall;
import works.earendil.pi.ai.types.ToolResultMessage;
import works.earendil.pi.ai.types.UserMessage;
import works.earendil.pi.tui.TerminalText;
import works.earendil.pi.tui.Theme;
import works.earendil.pi.tui.TuiInput;

class TurnDetailsComponentTest {
	@Test
	void rendersThinkingAndKeepsToolResultsCollapsedInitially() {
		TurnDetailsComponent component = details(false);

		String rendered = plain(component.render(100, 30, Theme.PLAIN));

		assertEquals(3, component.sectionCount());
		assertEquals(TurnDetailsComponent.Kind.THINKING, component.sectionKind(0));
		assertTrue(component.sectionExpanded(0));
		assertFalse(component.sectionExpanded(1));
		assertTrue(rendered.contains("Inspect the repository"));
		assertTrue(rendered.contains("read  Reading README.md"));
		assertFalse(rendered.contains("secret read output"));
	}

	@Test
	void expandsOneSelectedStepAndTogglesKindsGlobally() {
		TurnDetailsComponent component = details(false);
		component.render(100, 30, Theme.PLAIN);

		component.handle(new TuiInput.Key(TuiInput.KeyType.DOWN));
		component.handle(new TuiInput.Key(TuiInput.KeyType.ENTER));
		assertTrue(component.sectionExpanded(1));
		assertFalse(component.sectionExpanded(2));
		assertTrue(plain(component.render(100, 30, Theme.PLAIN)).contains("secret read output"));

		component.handle(new TuiInput.Key(TuiInput.KeyType.EXPAND_TOOLS));
		assertTrue(component.sectionExpanded(1));
		assertTrue(component.sectionExpanded(2));
		component.handle(new TuiInput.Key(TuiInput.KeyType.EXPAND_TOOLS));
		assertFalse(component.sectionExpanded(1));
		assertFalse(component.sectionExpanded(2));

		component.handle(new TuiInput.Key(TuiInput.KeyType.TOGGLE_THINKING));
		assertFalse(component.sectionExpanded(0));
		assertTrue(component.result());
	}

	@Test
	void scrollsWithinAnExpandedLongStep() {
		AssistantMessage assistant = new AssistantMessage("faux", "faux", "faux-1");
		assistant.content.add(new ThinkingContent(String.join("\n", IntStream.range(0, 30)
				.mapToObj(index -> "reasoning line " + index)
				.toList())));
		TurnDetailsComponent component = TurnDetailsComponent.forLatestTurn(
				List.of(UserMessage.of("inspect"), assistant), false);

		String firstPage = plain(component.render(60, 8, Theme.PLAIN));
		component.handle(new TuiInput.Key(TuiInput.KeyType.PAGE_DOWN));
		String secondPage = plain(component.render(60, 8, Theme.PLAIN));

		assertTrue(firstPage.contains("reasoning line 0"));
		assertFalse(secondPage.contains("reasoning line 0"));
		assertTrue(secondPage.contains("reasoning line"));
	}

	@Test
	void onlyInspectsTheLatestUserTurnAndSanitizesTerminalControls() {
		AssistantMessage old = new AssistantMessage("faux", "faux", "old");
		old.content.add(new ThinkingContent("old thought"));
		AssistantMessage latest = new AssistantMessage("faux", "faux", "new");
		latest.content.add(new ThinkingContent("new \u001b[31mthought\u001b[0m"));
		List<Message> messages = List.of(UserMessage.of("old"), old, UserMessage.of("new"), latest);

		TurnDetailsComponent component = TurnDetailsComponent.forLatestTurn(messages, false);
		String rendered = plain(component.render(80, 20, Theme.PLAIN));

		assertEquals(1, component.sectionCount());
		assertTrue(rendered.contains("new thought"));
		assertFalse(rendered.contains("old thought"));
		assertFalse(rendered.contains("\u001b"));
	}

	private static TurnDetailsComponent details(boolean hideThinking) {
		AssistantMessage assistant = new AssistantMessage("faux", "faux", "faux-1");
		assistant.content.add(new ThinkingContent("Inspect the repository\nThen choose files"));
		assistant.content.add(new ToolCall("read-1", "read", Json.object().put("path", "README.md")));
		assistant.content.add(new ToolCall("bash-1", "bash", Json.object().put("command", "mvn test")));
		return TurnDetailsComponent.forLatestTurn(
				List.of(
						UserMessage.of("inspect"),
						assistant,
						ToolResultMessage.text("read-1", "read", "secret read output", false),
						ToolResultMessage.text("bash-1", "bash", "tests passed", false)),
				hideThinking);
	}

	private static String plain(List<String> lines) {
		return TerminalText.stripAnsi(String.join("\n", lines));
	}
}
