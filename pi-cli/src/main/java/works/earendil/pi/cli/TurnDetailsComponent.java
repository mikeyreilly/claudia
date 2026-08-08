package works.earendil.pi.cli;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import works.earendil.pi.ai.types.AssistantContent;
import works.earendil.pi.ai.types.AssistantMessage;
import works.earendil.pi.ai.types.Message;
import works.earendil.pi.ai.types.ThinkingContent;
import works.earendil.pi.ai.types.ToolCall;
import works.earendil.pi.ai.types.ToolResultMessage;
import works.earendil.pi.ai.types.UserMessage;
import works.earendil.pi.tui.TerminalText;
import works.earendil.pi.tui.Theme;
import works.earendil.pi.tui.TuiComponent;
import works.earendil.pi.tui.TuiInput;

/** Full-screen inspector for the reasoning and tool steps in the latest user turn. */
final class TurnDetailsComponent implements TuiComponent<Boolean> {
	enum Kind {
		THINKING,
		TOOL
	}

	private static final int HEADER_LINES = 2;
	private static final int FOOTER_LINES = 1;

	private final List<Section> sections;
	private final Map<Integer, Integer> visibleSectionsByRow = new HashMap<>();
	private boolean thinkingHidden;
	private int selectedIndex;
	private int scrollTop;
	private int viewportHeight = 1;
	private boolean keepSelectionVisible = true;
	private boolean complete;

	private TurnDetailsComponent(List<Section> sections, boolean thinkingHidden) {
		this.sections = sections;
		this.thinkingHidden = thinkingHidden;
	}

	static TurnDetailsComponent forLatestTurn(List<Message> messages, boolean thinkingHidden) {
		int start = 0;
		for (int index = messages.size() - 1; index >= 0; index--) {
			if (messages.get(index) instanceof UserMessage) {
				start = index + 1;
				break;
			}
		}

		Map<String, ToolResultMessage> results = new LinkedHashMap<>();
		for (int index = start; index < messages.size(); index++) {
			if (messages.get(index) instanceof ToolResultMessage result) {
				results.put(result.toolCallId(), result);
			}
		}

		List<Section> sections = new ArrayList<>();
		int thinkingNumber = 0;
		for (int index = start; index < messages.size(); index++) {
			if (!(messages.get(index) instanceof AssistantMessage assistant)) continue;
			for (AssistantContent content : assistant.content) {
				if (content instanceof ThinkingContent thinking && !thinking.thinking().isBlank()) {
					thinkingNumber++;
					String body = safePlain(thinking.thinking().strip());
					sections.add(new Section(
							Kind.THINKING,
							"Thinking " + thinkingNumber,
							body,
							firstLine(body),
							!thinkingHidden));
				} else if (content instanceof ToolCall call) {
					ToolResultMessage result = results.get(call.id());
					String description = singleLine(InteractiveShell.toolDescription(call.name(), call.arguments()));
					String title = call.name() + (description.isBlank() ? "" : "  " + description);
					StringBuilder body = new StringBuilder("Arguments\n").append(call.arguments().toPrettyString());
					String summary = result == null ? "pending" : result.isError() ? "error" : resultSummary(result.text());
					body.append("\n\nResult");
					if (result != null && result.isError()) body.append(" (error)");
					body.append('\n').append(result == null ? "Pending" : result.text());
					sections.add(new Section(Kind.TOOL, title, safePlain(body.toString()), summary, false));
				}
			}
		}
		return sections.isEmpty() ? null : new TurnDetailsComponent(sections, thinkingHidden);
	}

	@Override
	public List<String> render(int width, int height, Theme theme) {
		int safeWidth = Math.max(20, width);
		viewportHeight = Math.max(1, height - HEADER_LINES - FOOTER_LINES);
		List<RenderedLine> content = renderContent(safeWidth, theme);
		int selectedRow = headingRow(content, selectedIndex);
		if (keepSelectionVisible) {
			if (selectedRow < scrollTop) {
				scrollTop = selectedRow;
			} else if (selectedRow >= scrollTop + viewportHeight) {
				scrollTop = selectedRow - viewportHeight + 1;
			}
		}
		scrollTop = Math.max(0, Math.min(scrollTop, Math.max(0, content.size() - viewportHeight)));

		List<String> lines = new ArrayList<>();
		lines.add(theme.heading() + "Turn details" + theme.reset());
		lines.add("");
		visibleSectionsByRow.clear();
		int visibleEnd = Math.min(content.size(), scrollTop + viewportHeight);
		for (int index = scrollTop; index < visibleEnd; index++) {
			RenderedLine line = content.get(index);
			if (line.heading()) visibleSectionsByRow.put(lines.size(), line.sectionIndex());
			lines.add(line.text());
		}
		String hint = "Up/Down select  Enter expand/collapse  PgUp/PgDn scroll  Ctrl-T thinking  Ctrl-O tools  Esc close";
		lines.add(theme.muted() + TerminalText.truncatePlain(hint, safeWidth) + theme.reset());
		return lines;
	}

	@Override
	public void handle(TuiInput input) {
		switch (input) {
			case TuiInput.Key key -> handleKey(key);
			case TuiInput.Mouse mouse -> handleMouse(mouse);
			case TuiInput.Resize ignored -> {
				// Rendering uses the current dimensions directly.
			}
		}
	}

	@Override
	public boolean isComplete() {
		return complete;
	}

	@Override
	public Boolean result() {
		return thinkingHidden;
	}

	int sectionCount() {
		return sections.size();
	}

	Kind sectionKind(int index) {
		return sections.get(index).kind;
	}

	boolean sectionExpanded(int index) {
		return sections.get(index).expanded;
	}

	private List<RenderedLine> renderContent(int width, Theme theme) {
		List<RenderedLine> lines = new ArrayList<>();
		for (int index = 0; index < sections.size(); index++) {
			Section section = sections.get(index);
			String marker = section.expanded ? "▼ " : "▶ ";
			String suffix = section.expanded || section.summary.isBlank() ? "" : " — " + section.summary;
			String heading = TerminalText.truncatePlain(marker + section.title + suffix, width);
			if (index == selectedIndex) heading = theme.heading() + heading + theme.reset();
			else heading = theme.strong() + heading + theme.reset();
			lines.add(new RenderedLine(heading, index, true));
			if (section.expanded) {
				for (String bodyLine : TerminalText.wrapPlain(section.body, Math.max(1, width - 3))) {
					String rendered = "   " + bodyLine;
					if (section.kind == Kind.THINKING) rendered = theme.muted() + rendered + theme.reset();
					lines.add(new RenderedLine(rendered, index, false));
				}
			}
			if (index + 1 < sections.size()) lines.add(new RenderedLine("", index, false));
		}
		return lines;
	}

	private void handleKey(TuiInput.Key key) {
		switch (key.type()) {
			case UP -> move(-1);
			case DOWN -> move(1);
			case PAGE_UP -> scrollBy(-Math.max(1, viewportHeight - 1));
			case PAGE_DOWN -> scrollBy(Math.max(1, viewportHeight - 1));
			case HOME -> {
				selectedIndex = 0;
				keepSelectionVisible = true;
			}
			case END -> {
				selectedIndex = sections.size() - 1;
				keepSelectionVisible = true;
			}
			case ENTER -> toggleSelected();
			case TOGGLE_THINKING -> toggleAll(Kind.THINKING);
			case EXPAND_TOOLS -> toggleAll(Kind.TOOL);
			case ESCAPE, CANCEL, EXIT -> complete = true;
			case CHARACTER -> {
				if (key.text().equals(" ")) toggleSelected();
				else if (key.text().equalsIgnoreCase("q")) complete = true;
			}
			default -> {
				// Other keys do not affect the inspector.
			}
		}
	}

	private void handleMouse(TuiInput.Mouse mouse) {
		switch (mouse.action()) {
			case SCROLL_UP -> scrollBy(-3);
			case SCROLL_DOWN -> scrollBy(3);
			case PRESS -> {
				if (mouse.button() != 0) return;
				Integer section = visibleSectionsByRow.get(mouse.y() - 1);
				if (section != null) {
					selectedIndex = section;
					toggleSelected();
				}
			}
			default -> {
				// Release and drag do not affect expansion.
			}
		}
	}

	private void move(int delta) {
		selectedIndex = Math.floorMod(selectedIndex + delta, sections.size());
		keepSelectionVisible = true;
	}

	private void scrollBy(int delta) {
		scrollTop = Math.max(0, scrollTop + delta);
		keepSelectionVisible = false;
	}

	private void toggleSelected() {
		Section section = sections.get(selectedIndex);
		section.expanded = !section.expanded;
		keepSelectionVisible = true;
	}

	private void toggleAll(Kind kind) {
		if (sections.stream().noneMatch(section -> section.kind == kind)) return;
		boolean expand = sections.stream().anyMatch(section -> section.kind == kind && !section.expanded);
		for (Section section : sections) {
			if (section.kind == kind) section.expanded = expand;
		}
		if (kind == Kind.THINKING) thinkingHidden = !expand;
		keepSelectionVisible = true;
	}

	private static int headingRow(List<RenderedLine> lines, int sectionIndex) {
		for (int index = 0; index < lines.size(); index++) {
			RenderedLine line = lines.get(index);
			if (line.heading && line.sectionIndex == sectionIndex) return index;
		}
		return 0;
	}

	private static String resultSummary(String text) {
		if (text == null || text.isBlank()) return "done";
		long lines = text.lines().count();
		return lines == 1 ? "done" : lines + " lines";
	}

	private static String firstLine(String text) {
		if (text == null || text.isBlank()) return "";
		String line = text.lines().filter(value -> !value.isBlank()).findFirst().orElse("").strip();
		return line.length() <= 100 ? line : line.substring(0, 100) + "...";
	}

	private static String singleLine(String value) {
		return safePlain(value).replaceAll("\\s+", " ").strip();
	}

	private static String safePlain(String value) {
		String stripped = TerminalText.stripAnsi(value == null ? "" : value);
		StringBuilder safe = new StringBuilder(stripped.length());
		for (int index = 0; index < stripped.length(); ) {
			int codePoint = stripped.codePointAt(index);
			if (codePoint == '\n' || codePoint == '\t' || (!Character.isISOControl(codePoint) && codePoint != 0x1b)) {
				safe.appendCodePoint(codePoint);
			}
			index += Character.charCount(codePoint);
		}
		return safe.toString();
	}

	private static final class Section {
		private final Kind kind;
		private final String title;
		private final String body;
		private final String summary;
		private boolean expanded;

		private Section(Kind kind, String title, String body, String summary, boolean expanded) {
			this.kind = kind;
			this.title = title;
			this.body = body;
			this.summary = summary;
			this.expanded = expanded;
		}
	}

	private record RenderedLine(String text, int sectionIndex, boolean heading) {}
}
