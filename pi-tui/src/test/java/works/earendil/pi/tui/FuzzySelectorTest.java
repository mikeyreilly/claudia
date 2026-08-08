package works.earendil.pi.tui;

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
				new SelectItem<>("sonnet", "claude-sonnet-4.5", "[github-copilot] Claude Sonnet"),
				new SelectItem<>("terra", "gpt-5.6-terra", "[github-copilot] GPT 5.6 Terra"),
				new SelectItem<>("gpt54", "gpt-5.4", "[github-copilot] GPT 5.4"));
		FuzzySelector<String> selector = new FuzzySelector<>("Models", items, 2, true);

		selector.handle(new TuiInput.Key(TuiInput.KeyType.PASTE, "terra copilot"));

		assertEquals("terra copilot", selector.query());
		assertEquals(List.of("terra"), selector.filteredItems().stream().map(SelectItem::value).toList());
		selector.handle(new TuiInput.Key(TuiInput.KeyType.ENTER));
		assertTrue(selector.isComplete());
		assertEquals("terra", selector.result());
	}

	@Test
	void restoresCurrentSelectionWhenSearchIsClearedAndSupportsCancel() {
		List<SelectItem<String>> items = List.of(
				new SelectItem<>("one", "One"),
				new SelectItem<>("two", "Two"),
				new SelectItem<>("three", "Three"));
		FuzzySelector<String> selector = new FuzzySelector<>("Options", items, 1, true);

		selector.handle(new TuiInput.Key(TuiInput.KeyType.CHARACTER, "t"));
		assertEquals(0, selector.selectedIndex());
		selector.handle(new TuiInput.Key(TuiInput.KeyType.CLEAR));
		assertEquals(1, selector.selectedIndex());
		selector.handle(new TuiInput.Key(TuiInput.KeyType.ESCAPE));

		assertTrue(selector.isComplete());
		assertNull(selector.result());
	}

	@Test
	void rendersAWindowedListWithCurrentAndNavigationHints() {
		List<SelectItem<Integer>> items = java.util.stream.IntStream.range(0, 20)
				.mapToObj(index -> new SelectItem<>(index, "Model " + index, "Description " + index))
				.toList();
		FuzzySelector<Integer> selector = new FuzzySelector<>("Models", items, 12, true);

		String frame = String.join("\n", selector.render(80, 12, Theme.PLAIN));

		assertTrue(frame.contains("Models"));
		assertTrue(frame.contains("Model 12 *"));
		assertTrue(frame.contains("13/20"));
		assertTrue(frame.contains("Type to filter"));
		assertFalse(frame.contains("Model 0  Description 0"));
	}
}
