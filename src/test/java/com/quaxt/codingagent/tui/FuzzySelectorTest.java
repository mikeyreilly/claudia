package com.quaxt.codingagent.tui;

import com.quaxt.codingagent.CodingAgentCli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class FuzzySelectorTest {
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
}
