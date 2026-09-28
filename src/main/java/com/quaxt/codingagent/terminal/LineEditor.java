package com.quaxt.codingagent.terminal;

import com.quaxt.codingagent.terminal.TerminalEvent.Key;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * A multiline prompt editor drawn below the terminal's current output.
 *
 * <p>The editable region (prompt, wrapped buffer, and optional lines below it)
 * is redrawn relative to the cursor, so output scrolled above it is never
 * touched. Other threads may print while the editor waits for input: they take
 * {@link #lock()}, write, and call {@link #screenReset()} and
 * {@link #redisplay()}. The editor holds the lock while it handles a key and
 * releases it while it waits.
 *
 * <p>Built-in bindings follow Emacs conventions: Ctrl-A/E and Home/End move to
 * the line start/end, Ctrl-B/F and arrows move by character, Alt-B/F and
 * Ctrl/Alt-arrows move by word, Ctrl-K/U kill to the line end/start, Ctrl-W
 * and Alt/Ctrl-Backspace kill the previous word, Alt-D kills the next word,
 * Ctrl-Y yanks, Up/Down (Ctrl-P/N) move between lines and then through history,
 * Ctrl-R searches history backwards (repeat to find older matches, Escape to
 * edit the match, Ctrl-G to restore the draft, Enter to submit the match),
 * Shift/Ctrl/Alt-Enter and Ctrl-J insert a newline, Ctrl-L repaints, Ctrl-C
 * interrupts, and Ctrl-D deletes forward or ends input on an empty buffer.
 */
public final class LineEditor {
    /** How often the idle hook runs while no input arrives. */
    public static final long IDLE_POLL_MILLIS = 100;
    /** Input queued this soon after Enter is treated as pasted text. */
    public static final long PASTE_LOOKAHEAD_MILLIS = 10;
    private static final String TAB_DISPLAY = "    ";

    /** Handles a key before the built-in bindings; returns true when it consumed the key. */
    @FunctionalInterface
    public interface KeyHandler {
        boolean handle(Key key);
    }

    /** Per-read configuration. */
    public static final class Options {
        /** Written once above the editable region, for example a separating newline. */
        public String header = "";
        /** Plain text starting the first editable row. */
        public String prompt = "";
        /** Plain text starting each later line of a multiline buffer. */
        public String continuationPrompt = "";
        /** SGR and erase sequences that start every prompt row, for example a background fill. */
        public String rowStyle = "";
        public String initialBuffer;
        /** Displays this character for each input character; {@code '\0'} displays nothing. */
        public Character mask;
        public KeyHandler keys;
        /** Consulted when Enter would submit; returns true when it consumed the key instead. */
        public BooleanSupplier beforeAccept;
        /** Styled lines drawn below the buffer, each expected to fit the terminal width. */
        public Supplier<List<String>> below;
        /** Runs on the editor thread whenever the input poll times out. */
        public Runnable idle;
        /**
         * Repaints the whole screen after a resize or Ctrl-L. It must leave the
         * cursor below the repainted output and call {@link #screenReset()};
         * without it the editor only redraws its own region.
         */
        public Runnable repaint;
    }

    public enum Outcome {
        ACCEPTED,
        INTERRUPTED,
        END_OF_INPUT
    }

    public record Result(Outcome outcome, String line) {}

    private record Frame(List<String> rows, int terminalRows, int cursorRow, int cursorColumn) {}

    private final Terminal terminal;
    private final ReentrantLock lock = new ReentrantLock();
    private final List<String> history = new ArrayList<>();
    private StringBuilder buffer = new StringBuilder();
    private int cursor;
    private Options options;
    private volatile boolean reading;
    private volatile boolean redrawRequested;
    private volatile boolean resetPending;
    private boolean displayed;
    private int displayedRows;
    private int displayedCursorRow;
    private Frame displayedFrame;
    private Terminal.Size lastSize;
    private int historyIndex;
    private String historyDraft;
    private StringBuilder searchQuery;
    private int searchIndex;
    private String searchDraft;
    private String killed = "";

    public LineEditor(Terminal terminal) {
        this.terminal = Objects.requireNonNull(terminal, "terminal");
    }

    /** Guards the editor state and its region of the screen. */
    public ReentrantLock lock() {
        return lock;
    }

    public boolean isReading() {
        return reading;
    }

    public String buffer() {
        lock.lock();
        try {
            return buffer.toString();
        } finally {
            lock.unlock();
        }
    }

    public int cursor() {
        lock.lock();
        try {
            return cursor;
        } finally {
            lock.unlock();
        }
    }

    public List<String> history() {
        lock.lock();
        try {
            return List.copyOf(history);
        } finally {
            lock.unlock();
        }
    }

    /** Replaces the buffer and places the cursor, clamped to the buffer. */
    public void setBuffer(String text, int cursorIndex) {
        lock.lock();
        try {
            buffer = new StringBuilder(text == null ? "" : text);
            cursor = Math.clamp(cursorIndex, 0, buffer.length());
        } finally {
            lock.unlock();
        }
    }

    /** Asks the editor thread to redraw its region at the next opportunity. */
    public void requestRedraw() {
        redrawRequested = true;
    }

    /**
     * Records that the screen was cleared or overwritten, so the region is no
     * longer shown; the next draw starts at the cursor with the header.
     */
    public void screenReset() {
        resetPending = true;
        redrawRequested = true;
    }

    /**
     * Draws the region now when the calling thread holds {@link #lock()} and the
     * editor is reading; otherwise the editor thread redraws it shortly.
     */
    public void redisplay() {
        if (lock.isHeldByCurrentThread() && reading && options != null) render(true);
    }

    /**
     * Leaves the region showing the buffer without the lines below it and moves
     * the cursor to a fresh line, for example before the process is suspended.
     * Hold the lock; call {@link #screenReset()} and {@link #redisplay()} to resume.
     */
    public void detach() {
        if (!reading || options == null) return;
        takeReset();
        draw(layout(false, true));
        terminal.write("\r\n");
        displayed = false;
    }

    /** Reads one line. Must not be called while this editor is already reading. */
    public Result readLine(Options readOptions) throws IOException {
        Objects.requireNonNull(readOptions, "options");
        lock.lock();
        try {
            if (reading) throw new IllegalStateException("The line editor is already reading");
            options = readOptions;
            buffer = new StringBuilder(readOptions.initialBuffer == null ? "" : readOptions.initialBuffer);
            cursor = buffer.length();
            historyIndex = history.size();
            historyDraft = null;
            searchQuery = null;
            displayed = false;
            displayedFrame = null;
            redrawRequested = false;
            resetPending = false;
            lastSize = terminal.size();
            boolean pasteWasEnabled = terminal.bracketedPaste();
            reading = true;
            try {
                terminal.bracketedPaste(true);
                render(true);
                while (true) {
                    Terminal.Size size = terminal.size();
                    if (!size.equals(lastSize)) {
                        lastSize = size;
                        resized();
                    }
                    if (redrawRequested) render(true);
                    TerminalEvent event = readUnlocked();
                    if (event == null) {
                        if (options.idle != null) options.idle.run();
                        render(false);
                        continue;
                    }
                    Result result = dispatch(event);
                    if (result != null) {
                        finish();
                        return result;
                    }
                    render(false);
                }
            } finally {
                reading = false;
                displayed = false;
                searchQuery = null;
                options = null;
                terminal.bracketedPaste(pasteWasEnabled);
            }
        } finally {
            lock.unlock();
        }
    }

    private TerminalEvent readUnlocked() throws IOException {
        int holds = lock.getHoldCount();
        // Release one level so printers can take the lock; callers that hold it
        // themselves (for example while running a command) keep other threads out.
        lock.unlock();
        try {
            return terminal.input().readEvent(IDLE_POLL_MILLIS);
        } finally {
            lock.lock();
            assert lock.getHoldCount() == holds;
        }
    }

    private void resized() {
        if (options.repaint != null) {
            options.repaint.run();
            if (!displayed) render(true);
        } else {
            render(true);
        }
    }

    private void finish() {
        takeReset();
        draw(layout(false, true));
        terminal.write("\r\n");
        displayed = false;
    }

    private void takeReset() {
        if (resetPending) {
            resetPending = false;
            displayed = false;
        }
    }

    // ------------------------------------------------------------ key handling

    private Result dispatch(TerminalEvent event) throws IOException {
        switch (event) {
            case TerminalEvent.EndOfInput ignored -> {
                searchQuery = null;
                return new Result(Outcome.END_OF_INPUT, null);
            }
            case TerminalEvent.Paste paste -> {
                String text = paste.text().replace("\r\n", "\n").replace('\r', '\n');
                if (searchQuery != null) search(text);
                else insert(text);
            }
            case TerminalEvent.Mouse ignored -> {
                // The prompt does not track the mouse.
            }
            case Key key -> {
                // Search owns its keys before application shortcuts (including Escape)
                // or the slash-command panel can consume them.
                if (searchQuery != null) return searchKey(key);
                if (options.keys != null && options.keys.handle(key)) return null;
                return key(key);
            }
        }
        return null;
    }

    private Result key(Key key) throws IOException {
        int modifiers = key.modifiers();
        boolean word = (modifiers & (TerminalEvent.ALT | TerminalEvent.CTRL)) != 0;
        switch (key.type()) {
            case ENTER -> {
                if (modifiers == 0) return enter();
                insert("\n");
            }
            case CHARACTER -> {
                if (modifiers == 0 || modifiers == TerminalEvent.SHIFT) insert(Character.toString(key.codePoint()));
                else if (modifiers == TerminalEvent.CTRL) return control(key.codePoint());
                else if (modifiers == TerminalEvent.ALT) alt(key.codePoint());
            }
            case BACKSPACE -> {
                if (word) kill(wordStart(cursor), cursor);
                else deleteBackward();
            }
            case DELETE -> {
                if (word) kill(cursor, wordEnd(cursor));
                else deleteForward();
            }
            case LEFT -> cursor = word ? wordStart(cursor) : previous(cursor);
            case RIGHT -> cursor = word ? wordEnd(cursor) : next(cursor);
            case HOME -> cursor = (modifiers & TerminalEvent.CTRL) != 0 ? 0 : lineStart(cursor);
            case END -> cursor = (modifiers & TerminalEvent.CTRL) != 0 ? buffer.length() : lineEnd(cursor);
            case UP -> up();
            case DOWN -> down();
            default -> {
                // Tab, Escape, paging, and function keys have no built-in meaning.
            }
        }
        return null;
    }

    private Result control(int base) throws IOException {
        switch (base) {
            case 'a' -> cursor = lineStart(cursor);
            case 'b' -> cursor = previous(cursor);
            case 'c' -> {
                return new Result(Outcome.INTERRUPTED, buffer.toString());
            }
            case 'd' -> {
                if (buffer.isEmpty()) return new Result(Outcome.END_OF_INPUT, null);
                deleteForward();
            }
            case 'e' -> cursor = lineEnd(cursor);
            case 'f' -> cursor = next(cursor);
            case 'h' -> deleteBackward();
            case 'j' -> insert("\n");
            case 'k' -> {
                int end = lineEnd(cursor);
                kill(cursor, end == cursor && end < buffer.length() ? end + 1 : end);
            }
            case 'l' -> {
                if (options.repaint != null) options.repaint.run();
                render(true);
            }
            case 'm' -> {
                return enter();
            }
            case 'n' -> down();
            case 'p' -> up();
            case 'r' -> startSearch();
            case 'u' -> kill(lineStart(cursor), cursor);
            case 'w' -> {
                int start = cursor;
                while (start > 0 && Character.isWhitespace(buffer.codePointBefore(start))) start = previous(start);
                while (start > 0 && !Character.isWhitespace(buffer.codePointBefore(start))) start = previous(start);
                kill(start, cursor);
            }
            case 'y' -> insert(killed);
            default -> {
                // Unbound control keys are ignored rather than inserted.
            }
        }
        return null;
    }

    private void startSearch() {
        if (options.mask != null) return; // Never reveal history in a masked prompt.
        searchDraft = buffer.toString();
        searchQuery = new StringBuilder();
        findSearch(history.size() - 1);
    }

    /** Finds the newest matching entry at or before start, with no wraparound. */
    private void findSearch(int start) {
        searchIndex = -1;
        for (int index = start; index >= 0; index--) {
            if (history.get(index).contains(searchQuery)) {
                searchIndex = index;
                break;
            }
        }
    }

    private void search(String text) {
        if (text.isEmpty()) return;
        int start = searchIndex < 0 ? history.size() - 1 : searchIndex;
        searchQuery.append(text);
        findSearch(start);
    }

    private void endSearch(boolean accept) {
        if (accept && searchIndex >= 0) {
            historyIndex = searchIndex;
            historyDraft = searchDraft;
            replaceWith(history.get(searchIndex));
        }
        searchQuery = null;
        searchDraft = null;
    }

    private Result searchKey(Key key) throws IOException {
        if (key.isCtrl('r')) {
            if (searchIndex >= 0) findSearch(searchIndex - 1);
            return null;
        }
        if (key.isCtrl('g')) {
            endSearch(false);
            return null;
        }
        if (key.isCtrl('c')) {
            endSearch(false);
            return new Result(Outcome.INTERRUPTED, buffer.toString());
        }
        if (key.isCtrl('l')) {
            if (options.repaint != null) options.repaint.run();
            render(true);
            return null;
        }
        if (key.isCtrl('u')) {
            searchQuery.setLength(0);
            findSearch(history.size() - 1);
            return null;
        }
        if (key.isCtrl('h') || key.is(TerminalEvent.KeyType.BACKSPACE)) {
            if (!searchQuery.isEmpty()) {
                searchQuery.setLength(searchQuery.offsetByCodePoints(searchQuery.length(), -1));
            }
            findSearch(history.size() - 1);
            return null;
        }
        if (key.is(TerminalEvent.KeyType.ENTER) || key.isCtrl('m')) {
            boolean matched = searchIndex >= 0;
            endSearch(true);
            return matched ? enter(true) : null;
        }
        if (key.is(TerminalEvent.KeyType.ESCAPE)) {
            endSearch(true);
            return null;
        }
        if (key.type() == TerminalEvent.KeyType.CHARACTER
                && (key.modifiers() == 0 || key.modifiers() == TerminalEvent.SHIFT)) {
            search(Character.toString(key.codePoint()));
            return null;
        }
        // Navigation accepts the match for editing; don't send it to the
        // application's command panel until search has ended.
        if (switch (key.type()) {
            case LEFT, RIGHT, HOME, END, UP, DOWN -> true;
            default -> false;
        }) {
            endSearch(true);
            return key(key);
        }
        return null;
    }

    private void alt(int base) {
        switch (base) {
            case 'b' -> cursor = wordStart(cursor);
            case 'f' -> cursor = wordEnd(cursor);
            case 'd' -> kill(cursor, wordEnd(cursor));
            default -> {
                // Unbound Alt combinations are ignored.
            }
        }
    }

    private Result enter() throws IOException {
        return enter(false);
    }

    private Result enter(boolean fromSearch) throws IOException {
        if (!fromSearch) {
            InputReader input = terminal.input();
            int next = input.peek(PASTE_LOOKAHEAD_MILLIS);
            // Queued text right after Enter is an unbracketed paste: keep its line break.
            if (next >= 0 && next != '\r') {
                if (next == '\n') input.read(0);
                insert("\n");
                return null;
            }
            if (options.beforeAccept != null && options.beforeAccept.getAsBoolean()) return null;
            if (next >= 0) {
                insert("\n");
                return null;
            }
        }
        String line = buffer.toString();
        if (options.mask == null && !line.isBlank() && (history.isEmpty() || !history.getLast().equals(line))) {
            history.add(line);
        }
        return new Result(Outcome.ACCEPTED, line);
    }

    private void insert(String text) {
        if (text.isEmpty()) return;
        buffer.insert(cursor, text);
        cursor += text.length();
    }

    private void deleteBackward() {
        if (cursor == 0) return;
        int start = previous(cursor);
        buffer.delete(start, cursor);
        cursor = start;
    }

    private void deleteForward() {
        if (cursor < buffer.length()) buffer.delete(cursor, next(cursor));
    }

    private void kill(int start, int end) {
        if (start >= end) return;
        killed = buffer.substring(start, end);
        buffer.delete(start, end);
        cursor = start;
    }

    private void up() {
        int start = lineStart(cursor);
        if (start > 0) {
            int column = buffer.codePointCount(start, cursor);
            int previousStart = lineStart(start - 1);
            cursor = offsetWithin(previousStart, start - 1, column);
            return;
        }
        if (historyIndex == 0) return;
        if (historyIndex == history.size()) historyDraft = buffer.toString();
        historyIndex--;
        replaceWith(history.get(historyIndex));
    }

    private void down() {
        int end = lineEnd(cursor);
        if (end < buffer.length()) {
            int column = buffer.codePointCount(lineStart(cursor), cursor);
            cursor = offsetWithin(end + 1, lineEnd(end + 1), column);
            return;
        }
        if (historyIndex >= history.size()) return;
        historyIndex++;
        replaceWith(historyIndex == history.size() ? historyDraft : history.get(historyIndex));
    }

    private void replaceWith(String text) {
        buffer = new StringBuilder(text == null ? "" : text);
        cursor = buffer.length();
    }

    private int offsetWithin(int start, int end, int codePoints) {
        int offset = start;
        for (int count = 0; count < codePoints && offset < end; count++) offset = next(offset);
        return offset;
    }

    private int previous(int index) {
        return index <= 0 ? 0 : buffer.offsetByCodePoints(index, -1);
    }

    private int next(int index) {
        return index >= buffer.length() ? buffer.length() : buffer.offsetByCodePoints(index, 1);
    }

    private int lineStart(int index) {
        return buffer.lastIndexOf("\n", index - 1) + 1;
    }

    private int lineEnd(int index) {
        int end = buffer.indexOf("\n", index);
        return end < 0 ? buffer.length() : end;
    }

    private static boolean isWordCharacter(int codePoint) {
        return Character.isLetterOrDigit(codePoint) || codePoint == '_';
    }

    private int wordStart(int index) {
        int position = index;
        while (position > 0 && !isWordCharacter(buffer.codePointBefore(position))) position = previous(position);
        while (position > 0 && isWordCharacter(buffer.codePointBefore(position))) position = previous(position);
        return position;
    }

    private int wordEnd(int index) {
        int position = index;
        while (position < buffer.length() && !isWordCharacter(buffer.codePointAt(position))) position = next(position);
        while (position < buffer.length() && isWordCharacter(buffer.codePointAt(position))) position = next(position);
        return position;
    }

    // --------------------------------------------------------------- display

    private void render(boolean force) {
        takeReset();
        Frame frame = layout(true, false);
        if (displayed && !force && frame.equals(displayedFrame)) return;
        draw(frame);
    }

    private void draw(Frame frame) {
        StringBuilder out = new StringBuilder();
        if (!displayed) {
            out.append(options.header == null ? "" : options.header);
        } else {
            // Return to the region's first row and clear the rows it used.
            out.append('\r').append(Ansi.cursorUp(displayedCursorRow));
            for (int row = 0; row < displayedRows; row++) {
                out.append(Ansi.ERASE_LINE);
                if (row + 1 < displayedRows) out.append(Ansi.cursorDown(1));
            }
            out.append(Ansi.cursorUp(displayedRows - 1));
        }
        for (int row = 0; row < frame.rows().size(); row++) {
            if (row > 0) out.append("\r\n");
            out.append(frame.rows().get(row));
        }
        out.append(Ansi.cursorUp(frame.terminalRows() - 1 - frame.cursorRow()))
                .append('\r')
                .append(Ansi.cursorRight(frame.cursorColumn()));
        terminal.beginUpdate();
        try {
            terminal.write(out);
        } finally {
            terminal.endUpdate();
        }
        displayed = true;
        displayedRows = frame.terminalRows();
        displayedCursorRow = frame.cursorRow();
        displayedFrame = frame;
        redrawRequested = false;
    }

    /** Lays out the prompt rows, wrapping at the terminal width, then the lines below. */
    private Frame layout(boolean withBelow, boolean cursorAtEnd) {
        boolean searching = searchQuery != null;
        String text = searching ? searchQuery + "': " + (searchIndex < 0 ? "" : history.get(searchIndex))
                : buffer.toString();
        String prompt = searching
                ? (searchIndex < 0 ? "(failed reverse-i-search)`" : "(reverse-i-search)`")
                : options.prompt;
        int target = cursorAtEnd ? text.length() : searching ? searchQuery.length() : cursor;
        int width = Math.max(1, terminal.columns());
        String style = options.rowStyle == null ? "" : options.rowStyle;
        String rowEnd = style.isEmpty() ? "" : Ansi.RESET;
        List<String> rows = new ArrayList<>();
        StringBuilder row = new StringBuilder(style);
        int column = 0;
        int cursorRow = -1;
        int cursorColumn = 0;

        for (String piece : plainPieces(prompt)) {
            int cells = Cells.width(piece);
            if (column + cells > width && column > 0) {
                rows.add(row.append(rowEnd).toString());
                row = new StringBuilder(style);
                column = 0;
            }
            row.append(piece);
            column += cells;
        }
        for (int index = 0; index <= text.length(); ) {
            boolean atCursor = index == target && cursorRow < 0;
            int codePoint = index < text.length() ? text.codePointAt(index) : -1;
            String display = codePoint < 0 || codePoint == '\n' ? "" : display(codePoint);
            int cells = Cells.width(display);
            if (column + cells > width && column > 0 || (atCursor && column >= width)) {
                rows.add(row.append(rowEnd).toString());
                row = new StringBuilder(style);
                column = 0;
            }
            if (atCursor) {
                cursorRow = rows.size();
                cursorColumn = column;
            }
            if (codePoint < 0) break;
            if (codePoint == '\n') {
                rows.add(row.append(rowEnd).toString());
                row = new StringBuilder(style);
                column = 0;
                for (String piece : plainPieces(options.continuationPrompt)) {
                    row.append(piece);
                    column += Cells.width(piece);
                }
                column = Math.min(column, width);
            } else {
                row.append(display);
                column += cells;
            }
            index += Character.charCount(codePoint);
        }
        rows.add(row.append(rowEnd).toString());
        int terminalRows = rows.size();
        if (withBelow && !searching && options.below != null) {
            List<String> below = options.below.get();
            if (below != null) {
                for (String line : below) {
                    rows.add(line);
                    int cells = Ansi.visibleWidth(line);
                    terminalRows += Math.max(1, (cells + width - 1) / width);
                }
            }
        }
        return new Frame(List.copyOf(rows), terminalRows, Math.max(0, cursorRow), cursorColumn);
    }

    private static List<String> plainPieces(String text) {
        List<String> pieces = new ArrayList<>();
        String plain = Ansi.strip(text == null ? "" : text);
        for (int index = 0; index < plain.length(); ) {
            int codePoint = plain.codePointAt(index);
            if (codePoint != '\n' && codePoint != '\r') pieces.add(safe(codePoint));
            index += Character.charCount(codePoint);
        }
        return pieces;
    }

    private String display(int codePoint) {
        if (options.mask != null) return options.mask == 0 ? "" : options.mask.toString();
        if (codePoint == '\t') return TAB_DISPLAY;
        return safe(codePoint);
    }

    /** Printable form of a code point: controls appear in caret notation. */
    private static String safe(int codePoint) {
        if (codePoint < 32) return "^" + (char) (codePoint + 64);
        if (codePoint == 127) return "^?";
        if (codePoint >= 0x80 && codePoint < 0xa0) return "\ufffd";
        return Character.toString(codePoint);
    }
}
