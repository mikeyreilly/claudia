package com.quaxt.claudia.terminal;

import java.util.ArrayList;
import java.util.List;

/**
 * A minimal VT screen for tests: printable text with autowrap and pending wrap,
 * CR/LF, index, cursor movement and addressing, erase, scroll margins, and
 * DECSC/DECRC. Other control sequences are ignored. Scrolled-off rows are kept
 * in {@link #scrollback()} unless cleared with CSI 3 J.
 */
public final class ScreenEmulator {
    private final int columns;
    private final int rows;
    private final List<String> scrollback = new ArrayList<>();
    private StringBuilder[] screen;
    private int row;
    private int column;
    private boolean pendingWrap;
    private int top;
    private int bottom;
    private int savedRow;
    private int savedColumn;
    private boolean alternate;
    private StringBuilder[] mainScreen;
    private int mainRow;
    private int mainColumn;

    public ScreenEmulator(int columns, int rows) {
        this.columns = columns;
        this.rows = rows;
        screen = blank();
        bottom = rows - 1;
    }

    private StringBuilder[] blank() {
        StringBuilder[] lines = new StringBuilder[rows];
        for (int index = 0; index < rows; index++) lines[index] = new StringBuilder();
        return lines;
    }

    public static ScreenEmulator render(String output, int columns, int rows) {
        ScreenEmulator emulator = new ScreenEmulator(columns, rows);
        emulator.feed(output);
        return emulator;
    }

    public void feed(String output) {
        for (int index = 0; index < output.length(); ) {
            int codePoint = output.codePointAt(index);
            index += Character.charCount(codePoint);
            if (codePoint == 0x1b) {
                index = escape(output, index);
            } else if (codePoint == '\r') {
                column = 0;
                pendingWrap = false;
            } else if (codePoint == '\n') {
                // Output post-processing (ONLCR) returns the carriage with every newline.
                column = 0;
                lineFeed();
            } else if (codePoint == '\b') {
                column = Math.max(0, column - 1);
                pendingWrap = false;
            } else if (codePoint >= 32 && codePoint != 127) {
                print(codePoint);
            }
        }
    }

    private int escape(String output, int index) {
        if (index >= output.length()) return index;
        char next = output.charAt(index++);
        switch (next) {
            case '[' -> {
                int start = index;
                while (index < output.length() && (output.charAt(index) < 0x40 || output.charAt(index) > 0x7e)) index++;
                String parameters = output.substring(start, index);
                char finalByte = output.charAt(index++);
                csi(parameters, finalByte);
            }
            case ']' -> {
                while (index < output.length() && output.charAt(index) != 0x07
                        && !(output.charAt(index) == 0x1b && index + 1 < output.length() && output.charAt(index + 1) == '\\')) {
                    index++;
                }
                index += index < output.length() && output.charAt(index) == 0x07 ? 1 : 2;
            }
            case '(', ')' -> index++;
            case '7' -> {
                savedRow = row;
                savedColumn = column;
            }
            case '8' -> {
                row = savedRow;
                column = savedColumn;
                pendingWrap = false;
            }
            case 'D' -> lineFeed();
            default -> {
                // Ignored.
            }
        }
        return index;
    }

    private void csi(String parameters, char finalByte) {
        if (parameters.startsWith("?")) {
            if (parameters.equals("?1049")) {
                if (finalByte == 'h' && !alternate) {
                    alternate = true;
                    mainScreen = screen;
                    mainRow = row;
                    mainColumn = column;
                    screen = blank();
                } else if (finalByte == 'l' && alternate) {
                    alternate = false;
                    screen = mainScreen;
                    row = mainRow;
                    column = mainColumn;
                }
            }
            return;
        }
        if (parameters.startsWith(">") || parameters.startsWith("<") || parameters.startsWith("=")) return;
        String[] fields = parameters.isEmpty() ? new String[0] : parameters.split(";", -1);
        int first = fields.length > 0 && !fields[0].isEmpty() ? Integer.parseInt(fields[0]) : 0;
        int count = Math.max(1, first);
        switch (finalByte) {
            case 'A' -> {
                row = Math.max(row >= top ? top : 0, row - count);
                pendingWrap = false;
            }
            case 'B' -> {
                row = Math.min(row <= bottom ? bottom : rows - 1, row + count);
                pendingWrap = false;
            }
            case 'C' -> {
                column = Math.min(columns - 1, column + count);
                pendingWrap = false;
            }
            case 'D' -> {
                column = Math.max(0, column - count);
                pendingWrap = false;
            }
            case 'H' -> {
                row = Math.clamp(count - 1, 0, rows - 1);
                int second = fields.length > 1 && !fields[1].isEmpty() ? Integer.parseInt(fields[1]) : 1;
                column = Math.clamp(second - 1, 0, columns - 1);
                pendingWrap = false;
            }
            case 'K' -> {
                if (first == 2) screen[row].setLength(0);
                else if (first == 0) truncate(screen[row], column);
            }
            case 'J' -> {
                if (first == 2) {
                    screen = blank();
                } else if (first == 3) {
                    scrollback.clear();
                } else if (first == 0) {
                    truncate(screen[row], column);
                    for (int index = row + 1; index < rows; index++) screen[index].setLength(0);
                }
            }
            case 'r' -> {
                if (fields.length >= 2) {
                    top = Integer.parseInt(fields[0]) - 1;
                    bottom = Integer.parseInt(fields[1]) - 1;
                } else {
                    top = 0;
                    bottom = rows - 1;
                }
                row = 0;
                column = 0;
                pendingWrap = false;
            }
            default -> {
                // SGR and other sequences do not change the text.
            }
        }
    }

    private static void truncate(StringBuilder line, int cells) {
        int offset = offsetForCells(line, cells);
        if (offset < line.length()) line.setLength(offset);
    }

    private static int offsetForCells(CharSequence line, int cells) {
        int width = 0;
        int index = 0;
        while (index < line.length() && width < cells) {
            int codePoint = Character.codePointAt(line, index);
            width += Math.max(0, Cells.width(codePoint));
            index += Character.charCount(codePoint);
        }
        return index;
    }

    private void print(int codePoint) {
        int width = Math.max(0, Cells.width(codePoint));
        if (pendingWrap || column + width > columns) {
            pendingWrap = false;
            column = 0;
            lineFeed();
        }
        StringBuilder line = screen[row];
        while (Cells.width(line) < column) line.append(' ');
        int start = offsetForCells(line, column);
        int end = offsetForCells(line, column + width);
        line.replace(start, end, Character.toString(codePoint));
        column += width;
        if (column >= columns) {
            column = columns - 1;
            pendingWrap = true;
        }
    }

    private void lineFeed() {
        pendingWrap = false;
        if (row == bottom) {
            if (top == 0 && !alternate) scrollback.add(screen[top].toString());
            for (int index = top; index < bottom; index++) screen[index] = screen[index + 1];
            screen[bottom] = new StringBuilder();
        } else if (row < rows - 1) {
            row++;
        }
    }

    /** Visible rows with trailing spaces removed. */
    public List<String> lines() {
        List<String> lines = new ArrayList<>();
        for (StringBuilder line : screen) lines.add(line.toString().stripTrailing());
        return lines;
    }

    /** Scrollback followed by the visible rows, without trailing blank rows. */
    public List<String> allLines() {
        List<String> lines = new ArrayList<>(scrollback);
        lines.addAll(lines());
        while (!lines.isEmpty() && lines.getLast().isEmpty()) lines.removeLast();
        return lines;
    }

    public List<String> scrollback() {
        return List.copyOf(scrollback);
    }

    public int cursorRow() {
        return row;
    }

    public int cursorColumn() {
        return pendingWrap ? columns : column;
    }

    public String line(int index) {
        return lines().get(index);
    }
}
