package com.quaxt.claudia.terminal;

/**
 * A one-row status line on the bottom terminal row. The rows above it form the
 * scroll region, so ordinary output scrolls without disturbing the status.
 * Suspend it before switching to the alternate screen or handing the terminal
 * to the shell.
 */
public final class StatusLine {
    private final Terminal terminal;
    private String content;
    private boolean suspended;
    private int reservedRows = -1;

    public StatusLine(Terminal terminal) {
        this.terminal = terminal;
    }

    /** Shows {@code line} (which may contain SGR styling); null or empty hides the status. */
    public synchronized void update(String line) {
        content = line == null || line.isEmpty() ? null : line;
        if (suspended) return;
        if (content == null) {
            release();
            return;
        }
        Terminal.Size size = terminal.size();
        if (size.rows() < 2) return;
        if (reservedRows != size.rows()) reserve(size.rows());
        draw(size);
    }

    /** Whether the status currently occupies the bottom row. */
    public synchronized boolean isShown() {
        return content != null && !suspended && reservedRows > 0;
    }

    /** Releases the bottom row and scroll region until {@link #restore()}. */
    public synchronized void suspend() {
        if (suspended) return;
        suspended = true;
        release();
    }

    public synchronized void restore() {
        if (!suspended) return;
        suspended = false;
        if (content != null) update(content);
    }

    /** Re-establishes the scroll region after the window size changed. */
    public synchronized void resize() {
        if (suspended || content == null) return;
        reservedRows = -1;
        update(content);
    }

    /** Hides the status for good, leaving the terminal with its full scroll region. */
    public synchronized void close() {
        release();
        content = null;
    }

    private void reserve(int rows) {
        // Scroll the cursor's row up first if it sits on the row being reserved.
        terminal.write(Ansi.INDEX + Ansi.cursorUp(1));
        terminal.scrollRegion(1, rows - 1);
        reservedRows = rows;
    }

    private void release() {
        if (reservedRows < 0) return;
        int rows = terminal.rows();
        terminal.resetScrollRegion();
        terminal.write(Ansi.SAVE_CURSOR + Ansi.moveTo(rows, 1) + Ansi.ERASE_LINE + Ansi.RESTORE_CURSOR);
        reservedRows = -1;
    }

    private void draw(Terminal.Size size) {
        terminal.write(Ansi.SAVE_CURSOR + Ansi.moveTo(size.rows(), 1) + Ansi.ERASE_LINE
                + content + Ansi.RESET + Ansi.RESTORE_CURSOR);
    }
}
