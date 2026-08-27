package com.quaxt.codingagent.cli;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * State of the full-screen inspector for the reasoning and tool steps in the
 * latest user turn. Section building, rendering, and input handling live in
 * CodingAgentOperations.
 */
public final class TurnDetailsComponent {
	public static final int HEADER_LINES = 2;
	public static final int FOOTER_LINES = 1;

	public enum Kind {
		THINKING,
		TOOL
	}

	/** One collapsible step: a reasoning block or a tool call with its result. */
	public static final class Section {
		public Kind kind;
		public String title;
		public String body;
		public String summary;
		public boolean expanded;

		public Section(Kind kind, String title, String body, String summary, boolean expanded) {
			this.kind = kind;
			this.title = title;
			this.body = body;
			this.summary = summary;
			this.expanded = expanded;
		}
	}

	/** One laid-out line, tagged with the section it belongs to. */
	public static final class RenderedLine {
		public String text;
		public int sectionIndex;
		public boolean heading;

		public RenderedLine(String text, int sectionIndex, boolean heading) {
			this.text = text;
			this.sectionIndex = sectionIndex;
			this.heading = heading;
		}
	}

	public List<Section> sections = List.of();
	public Map<Integer, Integer> visibleSectionsByRow = new HashMap<>();
	public boolean thinkingHidden;
	public int selectedIndex;
	public int scrollTop;
	public int viewportHeight = 1;
	public boolean keepSelectionVisible = true;
	public boolean complete;

	public TurnDetailsComponent() {}
}
