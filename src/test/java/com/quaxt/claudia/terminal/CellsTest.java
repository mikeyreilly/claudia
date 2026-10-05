package com.quaxt.claudia.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class CellsTest {
    @Test
    void measuresNarrowWideZeroWidthAndControlCharacters() {
        assertEquals(1, Cells.width('a'));
        assertEquals(1, Cells.width('é'));
        assertEquals(2, Cells.width('中'));
        assertEquals(2, Cells.width(0xac00)); // Hangul syllable
        assertEquals(2, Cells.width(0x3000)); // ideographic space
        assertEquals(1, Cells.width(0x303f));
        assertEquals(2, Cells.width(0x1f600)); // emoji
        assertEquals(2, Cells.width(0x2705));
        assertEquals(0, Cells.width(0x0301)); // combining acute accent
        assertEquals(0, Cells.width(0x200b)); // zero-width space
        assertEquals(0, Cells.width(0xfe0f)); // variation selector
        assertEquals(0, Cells.width(0x1160)); // Hangul medial vowel
        assertEquals(0, Cells.width(0));
        assertEquals(-1, Cells.width(0x07));
        assertEquals(-1, Cells.width(0x9b));
        // Status and panel glyphs are single cells.
        for (int glyph : new int[] {'●', '◐', '│', '─', '╭', '•', '✓', '✗', '▶', '⋯'}) {
            assertEquals(1, Cells.width(glyph), Character.toString(glyph));
        }
        assertEquals(6, Cells.width("ab中\uD83D\uDE00"));
        assertEquals(1, Cells.width("e\u0301"));
        assertEquals(2, Cells.width("a\u0007b"));
    }

    @Test
    void truncatesAndWrapsByCells() {
        assertEquals("ab中", Cells.truncate("ab中文", 5));
        assertEquals("", Cells.truncate("abc", 0));
        assertEquals(List.of("ab中", "文x"), Cells.wrap("ab中文x", 4));
        assertEquals(List.of("e\u0301f"), Cells.wrap("e\u0301f", 2));
        assertEquals(List.of("中", "文"), Cells.wrap("中文", 1));
        assertEquals(List.of(""), Cells.wrap("", 3));
    }

    @Test
    void stripsEscapeSequencesBeforeMeasuring() {
        assertEquals("hi", Ansi.strip("\u001b[1;31mhi\u001b[0m"));
        assertEquals("link", Ansi.strip("\u001b]8;;https://example.com\u0007link\u001b]8;;\u001b\\"));
        assertEquals(2, Ansi.visibleWidth("\u001b(B\u001b7\u001b[2mab\u001b[0m\u001b8"));
    }
}
