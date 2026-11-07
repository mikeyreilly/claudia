package com.quaxt.claudia.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quaxt.claudia.terminal.ScreenEmulator;
import com.quaxt.claudia.tui.TerminalStyle;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TranscriptTest {
    private static final String PROMPT_BACKGROUND = TerminalStyle.PROMPT_BACKGROUND;
    private static final String FILL = TerminalStyle.CLEAR_TO_END_OF_LINE;

    private static List<String> plain(Transcript transcript, int width) {
        return transcript.rows(width).stream().map(row -> row.plain().stripTrailing()).toList();
    }

    private static String lines(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(index -> "line " + index).collect(Collectors.joining("\n"));
    }

    @Test
    void textWrapsLikeTerminalOutputAndKeepsTheCursorLine() {
        Transcript transcript = new Transcript();
        transcript.append("abcdefghij\nxy");
        assertEquals(List.of("abcd", "efgh", "ij", "xy"), plain(transcript, 4));
        transcript.append("z\n");
        assertEquals(List.of("abcd", "efgh", "ij", "xyz", ""), plain(transcript, 4),
                "A completed line leaves an empty row where later output continues");
        assertEquals(List.of("ab", "界c"), plain(textOf("ab界c"), 3), "Wide characters move to the next row whole");
        assertEquals(List.of("a       b"), plain(textOf("a\tb"), 20));
    }

    @Test
    void incrementalAppendsWrapExactlyLikeOneAppend() {
        String text = TerminalStyle.MUTED + "first line that wraps\nsecond" + TerminalStyle.RESET + " tail\nthird";
        Transcript whole = textOf(text);
        Transcript pieces = new Transcript();
        for (int index = 0; index < text.length(); index += 3) {
            pieces.append(text.substring(index, Math.min(text.length(), index + 3)));
            pieces.rows(7);
        }
        assertEquals(whole.rows(7), pieces.rows(7));
        assertEquals(whole.rows(30), pieces.rows(30));
    }

    @Test
    void stylingAndBackgroundFillsCarryOntoWrappedRows() {
        List<String> rows = Transcript.wrapStyled(
                "\n" + PROMPT_BACKGROUND + FILL + "> abcdefgh" + TerminalStyle.RESET + "\nafter", 6);
        assertEquals("", rows.get(0));
        assertEquals(PROMPT_BACKGROUND + FILL + "> abcd" + TerminalStyle.RESET, rows.get(1));
        assertEquals(PROMPT_BACKGROUND + FILL + "efgh" + TerminalStyle.RESET + TerminalStyle.RESET, rows.get(2));
        assertEquals("after", rows.get(3));
        // Each row draws correctly on its own.
        ScreenEmulator screen = ScreenEmulator.render(String.join("\r\n", rows), 6, 5);
        assertEquals(List.of("", "> abcd", "efgh", "after", ""), screen.lines());
    }

    @Test
    void containersStartOnAFreshRowLikeALineBreak() {
        Transcript transcript = new Transcript();
        transcript.append("answer\n");
        transcript.addTool("a", "read", "Reading A", "{}", false);
        transcript.finishTool("a", "one", false);
        transcript.addTool("b", "read", "Reading B", "{}", false);
        transcript.finishTool("b", "two", false);
        assertEquals(List.of(
                "answer",
                "",
                "▶ [read] Reading A",
                "    Done: one",
                "",
                "▶ [read] Reading B",
                "    Done: two",
                ""), plain(transcript, 40));
        transcript.append("next");
        assertEquals("next", plain(transcript, 40).getLast(), "Later text starts below the container");
        Transcript partial = textOf("streamed");
        partial.addTool("c", "ls", "", "{}", false);
        assertEquals(List.of("streamed", "▶ [ls]", "    Running…", ""), plain(partial, 40));
    }

    @Test
    void collapsedToolsPreviewThreeResultLinesAndExpandToEverything() {
        Transcript transcript = new Transcript();
        transcript.addTool("call-1", "shell", "mvn -B test 2>&1 | tail -50 && echo finished", "{\n  \"command\" : \"mvn\"\n}", false);
        transcript.finishTool("call-1", "\n" + lines(5) + "\n", false);
        List<String> collapsed = plain(transcript, 30);
        assertEquals(List.of(
                "",
                "▶ [shell] mvn -B test 2>&1 | …",
                "    Done: line 1",
                "          line 2",
                "          line 3",
                "    … 2 more lines",
                ""), collapsed);
        assertEquals("call-1", transcript.rows(30).get(1).toggle());

        transcript.toggle("call-1");
        assertTrue(transcript.isExpanded("call-1"));
        assertEquals(List.of(
                "",
                "▼ [shell] mvn -B test 2>&1 | t",
                "  ail -50 && echo finished",
                "    Arguments",
                "    {",
                "      \"command\" : \"mvn\"",
                "    }",
                "    Result",
                "    line 1",
                "    line 2",
                "    line 3",
                "    line 4",
                "    line 5",
                ""), plain(transcript, 30));
    }

    @Test
    void collapsedRowsAreCutAtTheEdgeAndErrorsAndEmptyResultsAreLabelled() {
        Transcript transcript = new Transcript();
        transcript.addTool("e", "shell", "false", "{}", false);
        transcript.finishTool("e", "x".repeat(40), true);
        transcript.addTool("d", "write", "Writing a", "{}", false);
        transcript.finishTool("d", "  ", false);
        List<String> rows = plain(transcript, 24);
        assertEquals("", rows.get(0), "A container at the top starts with a line break");
        assertEquals("    Error: xxxxxxxxxxxx…", rows.get(2));
        assertEquals(24, com.quaxt.claudia.terminal.Cells.width(rows.get(2)));
        assertEquals("    Done: Completed.", rows.get(5));
        transcript.toggle("e");
        assertTrue(plain(transcript, 24).contains("    Error"));
    }

    @Test
    void reasoningIsExpandableOnlyWhenThePreviewHidesSomething() {
        Transcript transcript = new Transcript();
        transcript.addThinking("t1", true);
        transcript.appendThinking("t1", "\nshort thought\n");
        assertTrue(transcript.isStreaming("t1"));
        transcript.finishThinking("t1", false);
        assertEquals(List.of("", "  Thinking", "    short thought", ""), plain(transcript, 40));
        assertNull(transcript.rows(40).get(1).toggle(), "Nothing to reveal");

        transcript.addThinking("t2", true);
        transcript.appendThinking("t2", lines(6));
        assertEquals("▼ Thinking", plain(transcript, 40).get(4));
        transcript.finishThinking("t2", false);
        List<String> rows = plain(transcript, 40);
        assertEquals(List.of("▶ Thinking", "    line 1", "    line 2", "    line 3", "    … 3 more lines"),
                rows.subList(4, 9));
        assertEquals("t2", transcript.rows(40).get(4).toggle());
        transcript.expandThinking(true);
        assertTrue(plain(transcript, 40).contains("    line 6"));
    }

    @Test
    void blankReasoningIsRemovedAndTheSurroundingTextRejoins() {
        Transcript transcript = new Transcript();
        transcript.append("before ");
        transcript.addThinking("t", true);
        transcript.append("after\n");
        transcript.finishThinking("t", true);
        assertFalse(transcript.contains("t"));
        assertEquals(List.of("before after", ""), plain(transcript, 40));
    }

    @Test
    void theViewportFollowsTheEndUntilScrolledAway() {
        Transcript transcript = textOf(lines(10) + "\n");
        assertEquals(List.of("line 8", "line 9", "line 10", ""), plainFrame(transcript.frame(20, 4)));
        assertTrue(transcript.following());
        transcript.scroll(-3);
        assertFalse(transcript.following());
        assertEquals(List.of("line 5", "line 6", "line 7", "line 8"), plainFrame(transcript.frame(20, 4)));
        transcript.append("line 11\n");
        assertEquals("line 5", plainFrame(transcript.frame(20, 4)).getFirst(), "New output does not move a scrolled view");
        transcript.scroll(-100);
        assertEquals("line 1", plainFrame(transcript.frame(20, 4)).getFirst());
        assertEquals(3, transcript.pageRows());
        transcript.scroll(100);
        assertTrue(transcript.following(), "Reaching the end follows again");
        transcript.scroll(-2);
        transcript.scrollToEnd();
        assertEquals("", plainFrame(transcript.frame(20, 4)).getLast());
    }

    @Test
    void togglingKeepsTheClickedHeaderOnItsRowAndOnlyTheToggleCellsRespond() {
        Transcript transcript = textOf(lines(6) + "\n");
        transcript.addTool("call", "shell", "ls", "{}", false);
        transcript.finishTool("call", lines(8), false);
        transcript.append("after\n");
        List<String> frame = plainFrame(transcript.frame(30, 8));
        int header = frame.indexOf("▶ [shell] ls");
        assertTrue(header >= 0, frame.toString());
        assertEquals("call", transcript.toggleAt(header, 0));
        assertEquals("call", transcript.toggleAt(header, 1));
        assertNull(transcript.toggleAt(header, 2));
        assertNull(transcript.toggleAt(header + 1, 0));
        assertNull(transcript.toggleAt(99, 0));

        assertTrue(transcript.toggle("call"));
        List<String> expanded = plainFrame(transcript.frame(30, 8));
        assertEquals("▼ [shell] ls", expanded.get(header), "The header stays where it was clicked");
        assertFalse(transcript.following(), "Expanding pushed the end out of view");

        assertFalse(transcript.toggle("call"));
        List<String> collapsed = plainFrame(transcript.frame(30, 8));
        assertEquals(frame, collapsed);
        assertTrue(transcript.following());
    }

    @Test
    void aScrolledViewportKeepsItsTopBlockAcrossAWidthChange() {
        Transcript transcript = textOf("intro\n");
        transcript.addTool("call", "shell", "ls", "{}", true);
        transcript.finishTool("call", lines(30), false);
        transcript.append(lines(30) + "\n");
        transcript.frame(40, 5);
        transcript.scroll(-1000);
        transcript.scroll(2);
        assertEquals("▼ [shell] ls", plainFrame(transcript.frame(40, 5)).getFirst());
        assertEquals("▼ [shell] ls", plainFrame(transcript.frame(25, 5)).getFirst());
    }

    @Test
    void aRebuiltCopyCanKeepTheScrollPosition() {
        Transcript original = textOf(lines(30) + "\n");
        original.frame(20, 5);
        original.scroll(-10);
        List<String> shown = plainFrame(original.frame(20, 5));
        Transcript rebuilt = textOf(lines(31) + "\n");
        rebuilt.keepViewportOf(original);
        assertFalse(rebuilt.following());
        assertEquals(shown, plainFrame(rebuilt.frame(20, 5)));
    }

    @Test
    void clearingReturnsToAnEmptyFollowingDocument() {
        Transcript transcript = textOf(lines(20));
        transcript.addTool("x", "ls", "", "{}", false);
        transcript.frame(20, 3);
        transcript.scroll(-5);
        transcript.clear();
        assertTrue(transcript.isEmpty());
        assertFalse(transcript.contains("x"));
        assertTrue(transcript.following());
        assertEquals(List.of(""), plainFrame(transcript.frame(20, 3)));
        assertTrue(transcript.plainText().equals("\n"));
    }

    @Test
    void selectionCopiesWrappedLinesWholeWithoutContainerDecoration() {
        Transcript transcript = textOf("abcdefghijklmnopqrstuvwxyz\nsecond line\n");
        transcript.addTool("t", "read", "Reading", "{}", true);
        transcript.finishTool("t", "0123456789012345678901", false);
        transcript.append("after");
        List<Transcript.Row> frame = transcript.frame(20, 30);
        assertEquals(List.of("abcdefghijklmnopqrst", "uvwxyz", "second line", "", "▼ [read] Reading",
                "    Arguments", "    {}", "    Result", "    0123456789012345", "    678901", "after"), plainFrame(frame));

        // From "c" to the end of the first two lines: the wrap is not a line break.
        assertTrue(transcript.startSelection(0, 2));
        transcript.extendSelection(2, 30);
        assertEquals("cdefghijklmnopqrstuvwxyz\nsecond line", transcript.selectedText());

        // Dragging backwards selects the same way; the toggle and indents are left out.
        transcript.startSelection(9, 9);
        transcript.extendSelection(4, 0);
        assertTrue(transcript.selectionExtendsUp());
        assertEquals("[read] Reading\nArguments\n{}\nResult\n0123456789012345678901", transcript.selectedText());

        // Selecting past the end of the document stops at its last row.
        transcript.startSelection(10, 1);
        transcript.extendSelection(40, 0);
        assertEquals("fter", transcript.selectedText());

        // A press that never moves selects nothing; outside the frame it starts nothing.
        transcript.startSelection(0, 0);
        assertFalse(transcript.hasSelection());
        assertEquals("", transcript.selectedText());
        assertFalse(transcript.startSelection(11, 0));
        transcript.extendSelection(0, 3);
        assertFalse(transcript.hasSelection());
    }

    @Test
    void selectedCellsAreDrawnInReverseVideoThatSurvivesStyleResets() {
        Transcript transcript = textOf("plain " + TerminalStyle.MUTED + "muted" + TerminalStyle.RESET + " end\nnext\n");
        transcript.frame(20, 5);
        transcript.startSelection(0, 3);
        transcript.extendSelection(1, 1);
        List<Transcript.Row> frame = transcript.frame(20, 5);
        assertEquals("   ^^^^^^^^^^^^^", reversedCells(frame.get(0).text()),
                "The selected line break shows as one selected cell after the text");
        assertEquals("^^", reversedCells(frame.get(1).text()));
        assertEquals("", reversedCells(frame.get(2).text()));
        assertTrue(frame.get(1).text().endsWith("xt"));
        assertEquals(List.of("plain muted end", "next", ""), plainFrame(frame));
        assertEquals("in muted end\nne", transcript.selectedText());
        ScreenEmulator screen = ScreenEmulator.render(
                frame.stream().map(Transcript.Row::text).collect(Collectors.joining("\r\n")), 20, 3);
        assertEquals(List.of("plain muted end", "next", ""), screen.lines().stream().map(String::stripTrailing).toList());
    }

    @Test
    void theSelectionFollowsScrollingAndEndsWhenRowsAboveItMove() {
        Transcript transcript = textOf(lines(20) + "\n");
        transcript.frame(20, 5);
        // The viewport shows lines 17 to 20 and the empty row after them.
        transcript.startSelection(3, 0);
        transcript.scroll(-3);
        // The same screen row now shows an earlier line.
        transcript.extendSelection(3, 3);
        assertTrue(transcript.selectionExtendsUp());
        assertEquals("e 17\nline 18\nline 19\nl", transcript.selectedText());
        // A row above the viewport reaches its top row's start.
        transcript.extendSelection(-1, 9);
        assertEquals("line 14\nline 15\nline 16\nline 17\nline 18\nline 19\nl", transcript.selectedText());

        // Output appended below keeps it.
        transcript.frame(20, 5);
        transcript.append("more\n");
        transcript.frame(20, 5);
        assertTrue(transcript.hasSelection());
        // A width change wraps the rows again.
        transcript.frame(10, 5);
        assertFalse(transcript.hasSelection());

        transcript.scrollToEnd();
        transcript.addTool("t", "read", "Reading", "{}", false);
        transcript.frame(20, 5);
        transcript.startSelection(0, 0);
        transcript.extendSelection(1, 3);
        transcript.finishTool("t", "done", false);
        assertTrue(transcript.hasSelection(), "A container below the selection changes nothing above it");
        transcript.frame(20, 5);
        transcript.startSelection(4, 0);
        transcript.extendSelection(4, 3);
        transcript.finishTool("t", "two\nlines", false);
        assertFalse(transcript.hasSelection(), "A container that changes under the selection ends it");
    }

    /** Marks each printed cell {@code ^} when drawn in reverse video and a space otherwise. */
    private static String reversedCells(String styled) {
        StringBuilder marks = new StringBuilder();
        boolean reverse = false;
        for (int index = 0; index < styled.length(); index++) {
            char character = styled.charAt(index);
            if (character == '\u001b' && index + 1 < styled.length() && styled.charAt(index + 1) == '[') {
                int end = index + 2;
                while (styled.charAt(end) < 0x40 || styled.charAt(end) > 0x7e) end++;
                if (styled.charAt(end) == 'm') {
                    for (String parameter : styled.substring(index + 2, end).split(";", -1)) {
                        if (parameter.isEmpty() || parameter.equals("0") || parameter.equals("27")) reverse = false;
                        else if (parameter.equals("7")) reverse = true;
                    }
                }
                index = end;
                continue;
            }
            marks.append(reverse ? '^' : ' ');
        }
        return marks.toString().stripTrailing();
    }

    private static Transcript textOf(String text) {
        Transcript transcript = new Transcript();
        transcript.append(text);
        return transcript;
    }

    private static List<String> plainFrame(List<Transcript.Row> rows) {
        return rows.stream().map(row -> row.plain().stripTrailing()).toList();
    }
}
