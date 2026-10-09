package com.quaxt.claudia.terminal;

/** ANSI/xterm control sequences understood by Ghostty. */
public final class Ansi {
    public static final String ESC = "\u001b";
    public static final String CSI = "\u001b[";
    public static final String RESET = "\u001b[0m";
    public static final String ERASE_LINE = "\u001b[2K";
    public static final String ERASE_TO_END_OF_LINE = "\u001b[K";
    public static final String SAVE_CURSOR = "\u001b7";
    public static final String RESTORE_CURSOR = "\u001b8";
    /** Moves down one row, scrolling when the cursor is on the bottom margin. */
    public static final String INDEX = "\u001bD";
    public static final String RESET_SCROLL_REGION = "\u001b[r";
    public static final String ALTERNATE_SCREEN_ON = "\u001b[?1049h";
    public static final String ALTERNATE_SCREEN_OFF = "\u001b[?1049l";
    public static final String CURSOR_HIDE = "\u001b[?25l";
    public static final String CURSOR_SHOW = "\u001b[?25h";
    /** Button-event tracking reported in SGR form. */
    public static final String MOUSE_ON = "\u001b[?1002h\u001b[?1006h";
    public static final String MOUSE_OFF = "\u001b[?1006l\u001b[?1002l";
    public static final String BRACKETED_PASTE_ON = "\u001b[?2004h";
    public static final String BRACKETED_PASTE_OFF = "\u001b[?2004l";
    public static final String BEGIN_SYNCHRONIZED_UPDATE = "\u001b[?2026h";
    public static final String END_SYNCHRONIZED_UPDATE = "\u001b[?2026l";
    /** Windows Terminal/ConPTY key records retain modifiers, including Shift-Enter over WSL. */
    public static final String WIN32_INPUT_ON = "\u001b[?9001h";
    public static final String WIN32_INPUT_OFF = "\u001b[?9001l";
    /** Pops one entry from the kitty keyboard-protocol flag stack. */
    public static final String POP_KEYBOARD_FLAGS = "\u001b[<1u";
    public static final String PASTE_START = "\u001b[200~";
    public static final String PASTE_END = "\u001b[201~";

    private Ansi() {}

    private static final java.util.regex.Pattern SEQUENCE = java.util.regex.Pattern.compile(
            "\u001b(?:\\[[0-?]*[ -/]*[@-~]|\\][^\u0007\u001b]*(?:\u0007|\u001b\\\\)|[()][0-9A-Za-z]|[78DEHMc=>])");

    /** Removes CSI, OSC, character-set, and simple escape sequences. */
    public static String strip(String text) {
        return text.indexOf('\u001b') < 0 ? text : SEQUENCE.matcher(text).replaceAll("");
    }

    /** Cells occupied by text once escape sequences are removed. */
    public static int visibleWidth(String text) {
        return Cells.width(strip(text));
    }

    /** Pushes kitty keyboard-protocol flags; flag 1 disambiguates control keys. */
    public static String pushKeyboardFlags(int flags) {
        return CSI + ">" + flags + "u";
    }

    public static String cursorUp(int rows) {
        return rows <= 0 ? "" : CSI + rows + "A";
    }

    public static String cursorDown(int rows) {
        return rows <= 0 ? "" : CSI + rows + "B";
    }

    public static String cursorRight(int columns) {
        return columns <= 0 ? "" : CSI + columns + "C";
    }

    /** One-based cursor addressing. */
    public static String moveTo(int row, int column) {
        return CSI + row + ";" + column + "H";
    }

    /** One-based, inclusive scroll margins. */
    public static String scrollRegion(int top, int bottom) {
        return CSI + top + ";" + bottom + "r";
    }
}
