package com.quaxt.claudia.cli;

import com.quaxt.claudia.terminal.Ansi;
import com.quaxt.claudia.terminal.Cells;
import com.quaxt.claudia.tui.TerminalStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The interactive conversation as laid-out terminal rows for an app-managed
 * viewport: styled text plus collapsible containers for reasoning blocks and
 * tool calls. A container's top-left cell holds its toggle, {@value #EXPANDED}
 * when expanded and {@value #COLLAPSED} when collapsed.
 *
 * <p>Text blocks behave like text written to a terminal: rows wrap at the
 * width, and SGR styling and erase-to-end-of-line fills carry onto wrapped
 * rows. A container behaves like a line break, its rows, and another line
 * break, so it starts on a fresh row and later text starts below it.
 *
 * <p>The viewport follows the end of the document until it is scrolled away
 * from it. Not thread-safe; the CLI guards it with its screen lock.
 */
public final class Transcript {
    public static final String EXPANDED = "\u25bc";
    public static final String COLLAPSED = "\u25b6";
    /** Body lines a collapsed container previews. */
    public static final int PREVIEW_LINES = 3;
    /** Columns of the toggle that respond to a click: the glyph and the space after it. */
    public static final int TOGGLE_COLUMNS = 2;
    /** Containers are laid out at least this wide; narrower terminals are unsupported. */
    private static final int MINIMUM_CONTAINER_WIDTH = 20;
    private static final String INDENT = "    ";
    private static final int TAB_STOP = 8;
    private static final String ELLIPSIS = "\u2026";
    private static final String ERASE_TO_END_OF_LINE = "\u001b[K";
    private static final Row BLANK = new Row("", null);

    public enum Kind {
        THINKING,
        TOOL
    }

    /** One terminal row. {@code toggle} names the container whose toggle starts this row, if any. */
    public record Row(String text, String toggle) {
        public String plain() {
            return Ansi.strip(text);
        }
    }

    private abstract static sealed class Block permits Text, Container {
        private List<Row> rows;
        private int rowsWidth = -1;

        void changed() {
            rows = null;
        }

        List<Row> rows(int width) {
            if (rows == null || rowsWidth != width) {
                rows = List.copyOf(layout(width));
                rowsWidth = width;
            }
            return rows;
        }

        abstract List<Row> layout(int width);
    }

    /** Styled text, wrapped incrementally: completed lines keep their rows while the last line grows. */
    private static final class Text extends Block {
        final StringBuilder styled = new StringBuilder();
        private final List<Row> committed = new ArrayList<>();
        private int committedEnd;
        private String committedStyle = "";
        private int committedWidth = -1;

        @Override
        List<Row> layout(int width) {
            if (width != committedWidth) {
                committed.clear();
                committedEnd = 0;
                committedStyle = "";
                committedWidth = width;
            }
            int lineEnd = styled.lastIndexOf("\n") + 1;
            if (lineEnd > committedEnd) {
                Wrapper wrapper = new Wrapper(width, committedStyle);
                wrapper.wrap(styled, committedEnd, lineEnd, committed, false);
                committedStyle = wrapper.style.toString();
                committedEnd = lineEnd;
            }
            List<Row> rows = new ArrayList<>(committed);
            new Wrapper(width, committedStyle).wrap(styled, committedEnd, styled.length(), rows, true);
            return rows;
        }
    }

    private static final class Container extends Block {
        final String key;
        final Kind kind;
        final StringBuilder thinking = new StringBuilder();
        String title = "";
        String arguments = "";
        /** Null while the tool is running. */
        String result;
        boolean error;
        boolean streaming;
        boolean expanded;

        Container(String key, Kind kind) {
            this.key = key;
            this.kind = kind;
        }

        @Override
        List<Row> layout(int terminalWidth) {
            int width = Math.max(MINIMUM_CONTAINER_WIDTH, terminalWidth);
            return kind == Kind.THINKING ? thinkingRows(width) : toolRows(width);
        }

        private List<Row> thinkingRows(int width) {
            List<String> lines = bodyLines(thinking.toString());
            int available = width - INDENT.length();
            boolean expandable = lines.size() > PREVIEW_LINES
                    || lines.stream().anyMatch(line -> Cells.width(expandTabs(line)) > available);
            List<Row> rows = new ArrayList<>();
            String title = TerminalStyle.MUTED + "Thinking" + TerminalStyle.RESET;
            rows.add(expandable ? new Row(glyph() + " " + title, key) : new Row("  " + title, null));
            if (expanded || !expandable) {
                for (String line : lines) {
                    for (String part : Cells.wrap(expandTabs(line), available)) rows.add(muted(INDENT + part));
                }
            } else {
                for (int index = 0; index < Math.min(PREVIEW_LINES, lines.size()); index++) {
                    rows.add(muted(INDENT + clip(lines.get(index), available)));
                }
                if (lines.size() > PREVIEW_LINES) rows.add(muted(INDENT + moreLines(lines.size() - PREVIEW_LINES)));
            }
            return rows;
        }

        private List<Row> toolRows(int width) {
            List<Row> rows = new ArrayList<>();
            int titleWidth = width - 2;
            if (expanded) {
                List<String> parts = Cells.wrap(expandTabs(title), titleWidth);
                rows.add(new Row(glyph() + " " + parts.getFirst(), key));
                for (String part : parts.subList(1, parts.size())) rows.add(new Row("  " + part, null));
                int available = width - INDENT.length();
                rows.add(muted(INDENT + "Arguments"));
                for (String line : bodyLines(arguments)) wrapInto(rows, line, available);
                rows.add(muted(INDENT + (error ? "Error" : "Result")));
                if (result == null) {
                    rows.add(new Row(INDENT + "Running" + ELLIPSIS, null));
                } else {
                    List<String> lines = bodyLines(result);
                    if (lines.isEmpty()) rows.add(muted(INDENT + "(no output)"));
                    for (String line : lines) wrapInto(rows, line, available);
                }
                return rows;
            }
            rows.add(new Row(glyph() + " " + clip(title, titleWidth), key));
            if (result == null) {
                rows.add(new Row(INDENT + "Running" + ELLIPSIS, null));
                return rows;
            }
            List<String> lines = bodyLines(result);
            String label = error ? "Error: " : "Done: ";
            if (lines.isEmpty()) {
                rows.add(new Row(INDENT + label + (error ? "Tool failed without an error message." : "Completed."), null));
                return rows;
            }
            int available = width - INDENT.length() - label.length();
            for (int index = 0; index < Math.min(PREVIEW_LINES, lines.size()); index++) {
                String lead = index == 0 ? label : " ".repeat(label.length());
                rows.add(new Row(INDENT + lead + clip(lines.get(index), available), null));
            }
            if (lines.size() > PREVIEW_LINES) rows.add(muted(INDENT + moreLines(lines.size() - PREVIEW_LINES)));
            return rows;
        }

        private String glyph() {
            return expanded ? EXPANDED : COLLAPSED;
        }

        private static void wrapInto(List<Row> rows, String line, int available) {
            for (String part : Cells.wrap(expandTabs(line), available)) rows.add(new Row(INDENT + part, null));
        }
    }

    private final List<Block> blocks = new ArrayList<>();
    private final Map<String, Container> containers = new HashMap<>();
    private List<Row> layout;
    private int[] blockStarts = new int[0];
    private int layoutWidth = -1;
    private boolean dirty = true;
    private volatile boolean following = true;
    private int top;
    private int lastWidth = -1;
    private int lastHeight = 1;
    private int lastTop;
    private List<Row> lastFrame = List.of();

    // ------------------------------------------------------------- document

    /** Appends styled text, continuing the current line like terminal output. */
    public void append(String styled) {
        if (styled == null || styled.isEmpty()) return;
        Text text = blocks.isEmpty() || !(blocks.getLast() instanceof Text last) ? add(new Text()) : last;
        text.styled.append(styled);
        text.changed();
        dirty = true;
    }

    public boolean isEmpty() {
        return blocks.isEmpty();
    }

    /** Removes everything and returns the viewport to the end of the (empty) document. */
    public void clear() {
        blocks.clear();
        containers.clear();
        dirty = true;
        following = true;
        top = 0;
        lastFrame = List.of();
    }

    /** Starts a streaming reasoning container. */
    public void addThinking(String key, boolean expanded) {
        Container container = container(key, Kind.THINKING);
        container.streaming = true;
        container.expanded = expanded;
        changed(container);
    }

    public void appendThinking(String key, String text) {
        Container container = containers.get(key);
        if (container == null || text == null || text.isEmpty()) return;
        container.thinking.append(text);
        changed(container);
    }

    /** Ends a reasoning container's stream; one without visible text is removed. */
    public void finishThinking(String key, boolean expanded) {
        Container container = containers.get(key);
        if (container == null) return;
        if (container.thinking.toString().isBlank()) {
            remove(key);
            return;
        }
        container.streaming = false;
        container.expanded = expanded;
        changed(container);
    }

    /** Adds a tool-call container; its result is pending until {@link #finishTool}. */
    public void addTool(String key, String name, String description, String arguments, boolean expanded) {
        Container container = container(key, Kind.TOOL);
        container.title = "[" + name + "]" + (description == null || description.isBlank() ? "" : " " + description);
        container.arguments = arguments == null ? "" : arguments;
        container.expanded = expanded;
        changed(container);
    }

    public void finishTool(String key, String result, boolean error) {
        Container container = containers.get(key);
        if (container == null) return;
        container.result = result == null ? "" : result;
        container.error = error;
        changed(container);
    }

    public boolean contains(String key) {
        return containers.containsKey(key);
    }

    public boolean isExpanded(String key) {
        Container container = containers.get(key);
        return container != null && container.expanded;
    }

    public boolean isStreaming(String key) {
        Container container = containers.get(key);
        return container != null && container.streaming;
    }

    /** Expands or collapses every reasoning container. */
    public void expandThinking(boolean expanded) {
        for (Container container : containers.values()) {
            if (container.kind == Kind.THINKING && container.expanded != expanded) {
                container.expanded = expanded;
                changed(container);
            }
        }
    }

    public void remove(String key) {
        Container container = containers.remove(key);
        if (container == null) return;
        int index = blocks.indexOf(container);
        blocks.remove(index);
        // Adjacent text continues the same line, exactly as if it had been printed together.
        if (index > 0 && index < blocks.size()
                && blocks.get(index - 1) instanceof Text before && blocks.get(index) instanceof Text after) {
            before.styled.append(after.styled);
            before.changed();
            blocks.remove(index);
        }
        dirty = true;
    }

    /**
     * Toggles a container, keeping its header on the same screen row when it is
     * visible in the last frame. Returns whether it is now expanded.
     */
    public boolean toggle(String key) {
        Container container = containers.get(key);
        if (container == null) return false;
        int before = lastWidth > 0 ? headerRow(container, lastWidth) : -1;
        int screenRow = before - lastTop;
        container.expanded = !container.expanded;
        changed(container);
        if (before >= 0 && screenRow >= 0 && screenRow < lastHeight) {
            List<Row> rows = layout(lastWidth);
            int max = Math.max(0, rows.size() - lastHeight);
            top = Math.clamp((long) headerRow(container, lastWidth) - screenRow, 0, max);
            following = top >= max;
        }
        return container.expanded;
    }

    // --------------------------------------------------------------- layout

    /** Every row of the document at a width. */
    public List<Row> rows(int width) {
        return layout(Math.max(1, width));
    }

    /** The document's plain text at an effectively unlimited width, for logs and tests. */
    public String plainText() {
        StringBuilder text = new StringBuilder();
        for (Row row : rows(100_000)) text.append(row.plain().stripTrailing()).append('\n');
        return text.toString();
    }

    @Override
    public String toString() {
        return plainText();
    }

    private List<Row> layout(int width) {
        if (!dirty && layout != null && layoutWidth == width) return layout;
        List<Row> rows = new ArrayList<>();
        int[] starts = new int[blocks.size()];
        boolean afterContainer = false;
        for (int index = 0; index < blocks.size(); index++) {
            Block block = blocks.get(index);
            // A container starts with a line break: after a container or at the top that is a blank row.
            if (block instanceof Container && (rows.isEmpty() || afterContainer)) rows.add(BLANK);
            starts[index] = rows.size();
            rows.addAll(block.rows(width));
            afterContainer = block instanceof Container;
        }
        // The line after a trailing container is where later output (or the prompt) continues.
        if (afterContainer || rows.isEmpty()) rows.add(BLANK);
        layout = rows;
        blockStarts = starts;
        layoutWidth = width;
        dirty = false;
        return rows;
    }

    private int headerRow(Container container, int width) {
        layout(width);
        int index = blocks.indexOf(container);
        return index < 0 ? -1 : blockStarts[index];
    }

    // ------------------------------------------------------------- viewport

    /**
     * Lays out the document and returns the rows visible in a viewport of the
     * given size, remembering them for {@link #toggleAt}. While following, the
     * viewport shows the end of the document.
     */
    public List<Row> frame(int width, int height) {
        int safeWidth = Math.max(1, width);
        int safeHeight = Math.max(1, height);
        int anchorBlock = -1;
        int anchorOffset = 0;
        if (!following && lastWidth > 0 && lastWidth != safeWidth && !blocks.isEmpty()) {
            // Keep the block at the top of the viewport in view across a width change.
            layout(lastWidth);
            anchorBlock = 0;
            for (int index = 0; index < blockStarts.length; index++) {
                if (blockStarts[index] <= top) anchorBlock = index;
            }
            anchorOffset = Math.max(0, top - blockStarts[anchorBlock]);
        }
        List<Row> rows = layout(safeWidth);
        if (anchorBlock >= 0) {
            int blockRows = blocks.get(anchorBlock).rows(safeWidth).size();
            top = blockStarts[anchorBlock] + Math.min(anchorOffset, Math.max(0, blockRows - 1));
        }
        int max = Math.max(0, rows.size() - safeHeight);
        if (following) {
            top = max;
        } else {
            top = Math.clamp(top, 0, max);
            if (top == max) following = true;
        }
        lastWidth = safeWidth;
        lastHeight = safeHeight;
        lastTop = top;
        lastFrame = List.copyOf(rows.subList(top, Math.min(rows.size(), top + safeHeight)));
        return lastFrame;
    }

    /** The container whose toggle is at a zero-based row and column of the last frame, or null. */
    public String toggleAt(int row, int column) {
        if (row < 0 || row >= lastFrame.size() || column < 0 || column >= TOGGLE_COLUMNS) return null;
        return lastFrame.get(row).toggle();
    }

    /** Scrolls by rows relative to the last frame; negative values move towards the start. */
    public void scroll(int delta) {
        if (lastWidth <= 0) return;
        int max = Math.max(0, layout(lastWidth).size() - lastHeight);
        int current = following ? max : Math.clamp(top, 0, max);
        top = Math.clamp((long) current + delta, 0, max);
        following = top >= max;
    }

    /** Rows a page scroll moves, leaving one row of context. */
    public int pageRows() {
        return Math.max(1, lastHeight - 1);
    }

    public void scrollToEnd() {
        following = true;
    }

    /** Continues another transcript's scroll position, for a rebuilt copy of the same conversation. */
    public void keepViewportOf(Transcript previous) {
        following = previous.following;
        top = previous.top;
        lastWidth = previous.lastWidth;
        lastHeight = previous.lastHeight;
        lastTop = previous.lastTop;
    }

    /** Whether the viewport shows, and keeps showing, the end of the document. */
    public boolean following() {
        return following;
    }

    // -------------------------------------------------------------- helpers

    private <T extends Block> T add(T block) {
        blocks.add(block);
        dirty = true;
        return block;
    }

    private Container container(String key, Kind kind) {
        Container existing = containers.get(key);
        if (existing != null && existing.kind == kind) return existing;
        if (existing != null) remove(key);
        Container container = add(new Container(key, kind));
        containers.put(key, container);
        return container;
    }

    private void changed(Container container) {
        container.changed();
        dirty = true;
    }

    private static Row muted(String text) {
        return new Row(TerminalStyle.MUTED + text + TerminalStyle.RESET, null);
    }

    private static String moreLines(int count) {
        return ELLIPSIS + " " + count + " more line" + (count == 1 ? "" : "s");
    }

    /** Lines of plain text without leading or trailing blank lines. */
    static List<String> bodyLines(String text) {
        String[] split = (text == null ? "" : text).replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int start = 0;
        int end = split.length;
        while (start < end && split[start].isBlank()) start++;
        while (end > start && split[end - 1].isBlank()) end--;
        return List.of(split).subList(start, end);
    }

    /** Plain text cut to one row, ending in an ellipsis when it continues. */
    static String clip(String plain, int width) {
        String expanded = expandTabs(plain);
        if (Cells.width(expanded) <= width) return expanded;
        return Cells.truncate(expanded, Math.max(0, width - 1)) + ELLIPSIS;
    }

    static String expandTabs(String plain) {
        if (plain.indexOf('\t') < 0) return plain;
        StringBuilder expanded = new StringBuilder(plain.length() + 8);
        int column = 0;
        for (int index = 0; index < plain.length(); ) {
            int codePoint = plain.codePointAt(index);
            index += Character.charCount(codePoint);
            if (codePoint == '\t') {
                int spaces = TAB_STOP - column % TAB_STOP;
                expanded.append(" ".repeat(spaces));
                column += spaces;
            } else {
                expanded.appendCodePoint(codePoint);
                column += Math.max(0, Cells.width(codePoint));
            }
        }
        return expanded.toString();
    }

    /**
     * Wraps styled text at a width. Each row starts with the SGR state in effect
     * (and an erase-to-end-of-line when its line requested a fill) and ends
     * with a reset when styled, so any row can be drawn on its own.
     */
    static List<String> wrapStyled(String styled, int width) {
        List<Row> rows = new ArrayList<>();
        new Wrapper(Math.max(1, width), "").wrap(styled, 0, styled.length(), rows, true);
        return rows.stream().map(Row::text).toList();
    }

    private static final class Wrapper {
        private final int width;
        final StringBuilder style;
        private StringBuilder row;
        private boolean fill;
        private boolean styledRow;
        private int column;

        Wrapper(int width, String initialStyle) {
            this.width = width;
            this.style = new StringBuilder(initialStyle);
        }

        /**
         * Appends the rows of {@code text[from, to)}. The segment after the last
         * line break becomes a row only when {@code emitTail} is set.
         */
        void wrap(CharSequence text, int from, int to, List<Row> out, boolean emitTail) {
            fill = false;
            startRow();
            for (int index = from; index < to; ) {
                char character = text.charAt(index);
                if (character == '\u001b') {
                    index = escape(text, index, to);
                    continue;
                }
                if (character == '\n') {
                    finishRow(out);
                    fill = false;
                    startRow();
                    index++;
                    continue;
                }
                int codePoint = Character.codePointAt(text, index);
                index += Character.charCount(codePoint);
                if (codePoint == '\t') {
                    int spaces = Math.min(TAB_STOP - column % TAB_STOP, Math.max(0, width - column));
                    row.append(" ".repeat(spaces));
                    column += spaces;
                    continue;
                }
                int cells = Cells.width(codePoint);
                if (cells < 0) continue;
                if (cells > 0 && column > 0 && column + cells > width) {
                    finishRow(out);
                    startRow();
                }
                row.appendCodePoint(codePoint);
                column += cells;
            }
            if (emitTail) finishRow(out);
        }

        private int escape(CharSequence text, int start, int end) {
            int index = start + 1;
            if (index >= end) return end;
            char kind = text.charAt(index++);
            if (kind == '[') {
                int parameters = index;
                while (index < end && (text.charAt(index) < 0x40 || text.charAt(index) > 0x7e)) index++;
                if (index >= end) return end;
                char finalByte = text.charAt(index++);
                String params = text.subSequence(parameters, index - 1).toString();
                String sequence = text.subSequence(start, index).toString();
                if (finalByte == 'm') {
                    if (params.isEmpty() || params.equals("0") || params.startsWith("0;")) style.setLength(0);
                    if (!params.isEmpty() && !params.equals("0")) style.append(sequence);
                    row.append(sequence);
                    styledRow = true;
                } else if (finalByte == 'K' && (params.isEmpty() || params.equals("0"))) {
                    fill = true;
                    row.append(sequence);
                    styledRow = true;
                }
                return index;
            }
            if (kind == ']') {
                while (index < end) {
                    char next = text.charAt(index);
                    if (next == 0x07) return index + 1;
                    if (next == 0x1b && index + 1 < end && text.charAt(index + 1) == '\\') return index + 2;
                    index++;
                }
                return end;
            }
            if (kind == '(' || kind == ')') return Math.min(end, index + 1);
            return index;
        }

        private void startRow() {
            row = new StringBuilder();
            column = 0;
            styledRow = !style.isEmpty() || fill;
            row.append(style);
            if (fill) row.append(ERASE_TO_END_OF_LINE);
        }

        private void finishRow(List<Row> out) {
            if (styledRow) row.append(TerminalStyle.RESET);
            out.add(new Row(row.toString(), null));
        }
    }
}
