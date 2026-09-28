package com.quaxt.codingagent.terminal;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quaxt.codingagent.terminal.LineEditor.Outcome;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LineEditorTest {
    private static final int COLUMNS = 20;
    private static final int ROWS = 8;

    private PipedOutputStream keys;
    private ByteArrayOutputStream output;
    private Terminal terminal;
    private LineEditor editor;

    @BeforeEach
    void setUp() throws IOException {
        PipedInputStream in = new PipedInputStream(1 << 16);
        keys = new PipedOutputStream(in);
        output = new ByteArrayOutputStream();
        terminal = Terminal.streams("xterm-ghostty", in, output, COLUMNS, ROWS);
        editor = new LineEditor(terminal);
    }

    private static LineEditor.Options prompt() {
        LineEditor.Options options = new LineEditor.Options();
        options.prompt = "> ";
        options.continuationPrompt = ". ";
        return options;
    }

    private void type(String input) throws IOException {
        keys.write(input.getBytes(UTF_8));
        keys.flush();
    }

    private LineEditor.Result read(LineEditor.Options options, String input) throws IOException {
        type(input);
        return assertTimeoutPreemptively(Duration.ofSeconds(5), () -> editor.readLine(options));
    }

    private String line(String input) throws IOException {
        LineEditor.Result result = read(prompt(), input);
        assertEquals(Outcome.ACCEPTED, result.outcome());
        return result.line();
    }

    private CompletableFuture<LineEditor.Result> readAsync(LineEditor.Options options) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return editor.readLine(options);
            } catch (IOException error) {
                throw new AssertionError(error);
            }
        });
    }

    private ScreenEmulator screen(int columns) {
        return ScreenEmulator.render(output.toString(UTF_8), columns, ROWS);
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), "Timed out waiting for the editor");
    }

    @Test
    void editsWithEmacsStyleBindings() throws Exception {
        assertEquals("one ", line("one two three\u001bb\u001bb\u000b\r"));
        assertEquals("one two three", line("one two three\u001bb\u001bb\u000b\u0019\r"));
        assertEquals(" two", line("one two\u0001\u001bd\r"));
        assertEquals("Xdef", line("abc def\u001b[D\u001b[D\u001b[D\u0015X\r"));
        assertEquals("b", line("ab\u0001\u0004\r"));
        assertEquals("x", line("hello world\u0017\u0017x\r"));
        assertEquals("path/to/", line("path/to/file\u001b\u007f\r"));
        assertEquals("path/to/", line("path/to/file\u001b[127;5u\r"));
        assertEquals("XaY b", line("a b\u001b[1;5D\u001b[1;5DX\u001b[1;5CY\r"));
        assertEquals("x", line("xy\u001b[D\u001b[3~\r"));
        assertEquals("aZb", line("ab\u0002\u0002\u0006Z\r"));
        assertEquals("AbcD", line("bc\u001b[HA\u001b[FD\r"));
        assertEquals("a😀", line("a😀b\u007f\r"));
        // Unbound controls, Tab, and function keys insert nothing.
        assertEquals("ab", line("a\u0007\t\u001b[15~\u001b[5~b\r"));
    }

    @Test
    void movesBetweenLinesBeforeHistoryAndPreservesTheDraft() throws Exception {
        assertEquals("first", line("first\r"));
        assertEquals("abX\ncd", line("ab\u001b[13;2ucd\u001b[AX\r"));
        assertEquals("draft", line("draft\u001b[A\u001b[A\u001b[A\u001b[B\u001b[B\u001b[B\r"));
        // Ctrl-P: to "draft", to "abX\ncd", up within it, then to "first".
        assertEquals("first", line("\u0010\u0010\u0010\u0010\r"));
        assertEquals(List.of("first", "abX\ncd", "draft", "first"), editor.history());
        assertEquals("", line("\r"));
        assertEquals(4, editor.history().size(), "Blank lines are not remembered");
    }

    @Test
    void reverseSearchFindsOlderSubstringMatchesAndSubmitsOnEnter() throws Exception {
        assertEquals("deploy api", line("deploy api\r"));
        assertEquals("status", line("status\r"));
        assertEquals("deploy web", line("deploy web\r"));
        assertEquals("deploy api", line("draft\u0012deploy\u0012\r"));
        assertEquals(List.of("deploy api", "status", "deploy web", "deploy api"), editor.history());
    }

    @Test
    void reverseSearchRefinesTheSelectedOlderMatchAndReturnsToDraftFromHistory() throws Exception {
        assertEquals("build one older", line("build one older\r"));
        assertEquals("build one newer", line("build one newer\r"));
        assertEquals("build one older", line("draft\u0012build\u0012 one\r"));
        assertEquals("draft", line("draft\u0012older\u001b[27u\u000e\r"));
    }

    @Test
    void reverseSearchShowsMatchesAndRestoresTheDraftOnCtrlG() throws Exception {
        terminal.setSize(80, ROWS);
        assertEquals("alpha", line("alpha\r"));
        assertEquals("beta", line("beta\r"));
        CompletableFuture<LineEditor.Result> result = readAsync(prompt());
        type("draft");
        waitUntil(() -> editor.buffer().equals("draft"));
        type("\u0012alp");
        waitUntil(() -> screen(80).allLines().getLast().contains("(reverse-i-search)`alp': alpha"));
        assertEquals("draft", editor.buffer(), "Searching must not alter the draft");
        type("z");
        waitUntil(() -> screen(80).allLines().getLast().contains("(failed reverse-i-search)`alpz':"));
        type("\u007f");
        waitUntil(() -> screen(80).allLines().getLast().contains("(reverse-i-search)`alp': alpha"));
        type("\u0007"); // Ctrl-G cancels, retaining the original draft and cursor.
        waitUntil(() -> screen(80).allLines().getLast().equals("> draft"));
        type("!\r");
        assertEquals("draft!", result.get(5, TimeUnit.SECONDS).line());
    }

    @Test
    void reverseSearchEscapeAcceptsForEditingAndBypassesApplicationKeysAndSuggestions() throws Exception {
        assertEquals("first", line("first\r"));
        assertEquals("second", line("second\r"));
        AtomicInteger appKeys = new AtomicInteger();
        LineEditor.Options options = prompt();
        options.keys = key -> {
            if (key.is(TerminalEvent.KeyType.ESCAPE)) {
                appKeys.incrementAndGet();
                return true;
            }
            return false;
        };
        options.below = () -> List.of("suggestions");
        options.beforeAccept = () -> {
            throw new AssertionError("Search Enter must not insert a slash-command suggestion");
        };
        CompletableFuture<LineEditor.Result> result = readAsync(options);
        type("draft\u0012fir");
        waitUntil(() -> output.toString(UTF_8).contains("(reverse-i-search)"));
        assertFalse(screen(COLUMNS).allLines().contains("suggestions"), "Suggestions must be hidden during search");
        type("\u001b");
        waitUntil(() -> editor.buffer().equals("first"));
        assertEquals(0, appKeys.get(), "Search keys must bypass the application Escape binding");
        type("!\u0003"); // Ctrl-C still interrupts normally.
        assertEquals(new LineEditor.Result(Outcome.INTERRUPTED, "first!"), result.get(5, TimeUnit.SECONDS));
    }

    @Test
    void reverseSearchNoMatchDoesNotSubmitTheDraftAndCanBeRevised() throws Exception {
        assertEquals("😀 item", line("😀 item\r"));
        assertEquals("😀 item", line("draft\u0012😀z\u007f\r"));
        // An unmatched query must not submit a different draft.
        terminal.setSize(80, ROWS);
        CompletableFuture<LineEditor.Result> result = readAsync(prompt());
        type("pending\u0012missing");
        waitUntil(() -> screen(80).allLines().getLast().contains("(failed reverse-i-search)`missing':"));
        type("\r");
        waitUntil(() -> screen(80).allLines().getLast().equals("> pending"));
        type("!\r");
        assertEquals("pending!", result.get(5, TimeUnit.SECONDS).line());
    }

    @Test
    void reverseSearchEnterBypassesTheCommandSuggestionAcceptHook() throws Exception {
        assertEquals("/help", line("/help\r"));
        LineEditor.Options options = prompt();
        options.below = () -> List.of("suggestions");
        options.beforeAccept = () -> {
            throw new AssertionError("Search Enter must not insert a slash-command suggestion");
        };
        assertEquals("/help", read(options, "\u0012help\r").line());
    }

    @Test
    void reverseSearchIgnoresMaskedPromptsAndResetsOnNextRead() throws Exception {
        assertEquals("public", line("public\r"));
        LineEditor.Options masked = prompt();
        masked.mask = '*';
        assertEquals("secret", read(masked, "\u0012secret\r").line());
        assertEquals("", line("\u0012\u0007\r"));
        assertEquals(List.of("public"), editor.history());
    }

    @Test
    void insertsNewlinesFromEveryModifiedEnterForm() throws Exception {
        assertEquals("a\nb\nc\nd\ne\nf", line("a\u001b[13;2ub\u001b[27;2;13~c\u001b[13;5ud\ne\u001b\rf\r"));
        assertEquals("go", line("go\u001b[13u"));
        assertEquals("go", line("go\u001b[109;5u"));
    }

    @Test
    void interruptsEndsInputAndDeletesForwardWithCtrlD() throws Exception {
        LineEditor.Result interrupted = read(prompt(), "abc\u0003");
        assertEquals(new LineEditor.Result(Outcome.INTERRUPTED, "abc"), interrupted);
        assertEquals(Outcome.END_OF_INPUT, read(prompt(), "\u0004").outcome());
        assertEquals("x", line("x\u0004\r"));
        keys.close();
        assertEquals(Outcome.END_OF_INPUT, assertTimeoutPreemptively(
                Duration.ofSeconds(5), () -> editor.readLine(prompt())).outcome());
    }

    @Test
    void pastesVerbatimWithNormalizedLineBreaksAndShowsControlsSafely() throws Exception {
        assertEquals("a\nb\nc\td\u0007", line("\u001b[200~a\r\nb\rc\td\u0007\u001b[201~\r"));
        String written = output.toString(UTF_8);
        assertFalse(written.contains("\u0007"));
        assertTrue(written.startsWith(Ansi.BRACKETED_PASTE_ON));
        assertTrue(written.endsWith(Ansi.BRACKETED_PASTE_OFF));
        assertFalse(terminal.bracketedPaste());
        assertEquals(List.of("> a", ". b", ". c    d^G"), screen(COLUMNS).allLines());
    }

    @Test
    void keepsQueuedTextAfterEnterAsAnUnbracketedPaste() throws Exception {
        assertEquals("first\nsecond", line("first\r\nsecond\r"));
        assertEquals("one\ntwo", line("one\rtwo\r"));
    }

    @Test
    void masksInputAndKeepsItOutOfHistory() throws Exception {
        LineEditor.Options options = prompt();
        options.mask = '*';
        assertEquals("secret", read(options, "secret\r").line());
        options = prompt();
        options.mask = '\0';
        assertEquals("hidden", read(options, "hidden\r").line());
        assertFalse(output.toString(UTF_8).contains("secret"));
        assertFalse(output.toString(UTF_8).contains("hidden"));
        assertEquals(List.of("> ******", ">"), screen(COLUMNS).allLines());
        assertTrue(editor.history().isEmpty());
    }

    @Test
    void wrapsAtTheTerminalWidthAndRedrawsOnlyItsRegion() throws Exception {
        terminal.write("before\r\n");
        LineEditor.Options options = prompt();
        options.header = "\n";
        options.rowStyle = "\u001b[48;5;236m\u001b[K";
        options.below = () -> editor.buffer().endsWith("!") ? List.of("[panel]") : List.of();
        CompletableFuture<LineEditor.Result> result = readAsync(options);
        type("abcdefghijklmnopqrstuvwxyz!");
        waitUntil(() -> editor.buffer().endsWith("!"));
        assertEquals(List.of("before", "", "> abcdefghijklmnopqr", "stuvwxyz!", "[panel]"), screen(COLUMNS).allLines());
        type("\u007f");
        waitUntil(() -> !editor.buffer().endsWith("!"));
        assertEquals(List.of("before", "", "> abcdefghijklmnopqr", "stuvwxyz"), screen(COLUMNS).allLines());
        ScreenEmulator waiting = screen(COLUMNS);
        assertEquals(3, waiting.cursorRow());
        assertEquals(8, waiting.cursorColumn());
        type("\r");
        assertEquals("abcdefghijklmnopqrstuvwxyz", result.get(5, TimeUnit.SECONDS).line());
        ScreenEmulator done = screen(COLUMNS);
        assertEquals(List.of("before", "", "> abcdefghijklmnopqr", "stuvwxyz"), done.allLines());
        assertEquals(4, done.cursorRow());
        assertEquals(0, done.cursorColumn());
    }

    @Test
    void aFullRowPlacesTheCursorOnTheNextRow() throws Exception {
        CompletableFuture<LineEditor.Result> result = readAsync(prompt());
        type("x".repeat(COLUMNS - 2));
        waitUntil(() -> editor.buffer().length() == COLUMNS - 2);
        ScreenEmulator waiting = screen(COLUMNS);
        assertEquals(1, waiting.cursorRow());
        assertEquals(0, waiting.cursorColumn());
        type("\u0002");
        waitUntil(() -> editor.cursor() == COLUMNS - 3);
        waiting = screen(COLUMNS);
        assertEquals(0, waiting.cursorRow());
        assertEquals(COLUMNS - 1, waiting.cursorColumn());
        type("\r");
        assertEquals("x".repeat(COLUMNS - 2), result.get(5, TimeUnit.SECONDS).line());
    }

    @Test
    void otherThreadsRepaintAboveTheEditorWithoutLosingTheDraft() throws Exception {
        CompletableFuture<LineEditor.Result> result = readAsync(prompt());
        type("draft");
        waitUntil(() -> editor.buffer().equals("draft"));
        editor.lock().lock();
        try {
            terminal.write("\u001b[2J\u001b[Hdocument line\r\n");
            editor.screenReset();
            editor.redisplay();
        } finally {
            editor.lock().unlock();
        }
        assertEquals(List.of("document line", "> draft"), screen(COLUMNS).lines().subList(0, 2));
        // Without the lock the editor thread redraws on its next poll.
        terminal.write("\u001b[2J\u001b[Hsecond document\r\n");
        editor.screenReset();
        waitUntil(() -> screen(COLUMNS).lines().subList(0, 2).equals(List.of("second document", "> draft")));
        type(" more\r");
        assertEquals("draft more", result.get(5, TimeUnit.SECONDS).line());
        assertEquals(List.of("second document", "> draft more"), screen(COLUMNS).allLines());
    }

    @Test
    void detachLeavesTheDraftAndResumesOnAFreshLine() throws Exception {
        CompletableFuture<LineEditor.Result> result = readAsync(prompt());
        type("draft");
        waitUntil(() -> editor.buffer().equals("draft"));
        editor.lock().lock();
        try {
            editor.detach();
            terminal.write("$ fg\r\n");
            editor.screenReset();
            editor.redisplay();
        } finally {
            editor.lock().unlock();
        }
        type("\r");
        assertEquals("draft", result.get(5, TimeUnit.SECONDS).line());
        assertEquals(List.of("> draft", "$ fg", "> draft"), screen(COLUMNS).allLines());
    }

    @Test
    void resizeRepaintsThroughTheHook() throws Exception {
        AtomicInteger repaints = new AtomicInteger();
        LineEditor.Options options = prompt();
        options.repaint = () -> {
            terminal.write("\u001b[2J\u001b[H[repainted]\r\n");
            editor.screenReset();
            editor.redisplay();
            repaints.incrementAndGet();
        };
        CompletableFuture<LineEditor.Result> result = readAsync(options);
        type("draft");
        waitUntil(() -> editor.buffer().equals("draft"));
        terminal.setSize(30, ROWS);
        waitUntil(() -> repaints.get() == 1);
        assertEquals(List.of("[repainted]", "> draft"), screen(30).lines().subList(0, 2));
        type("\u000c\r"); // Ctrl-L repaints on request
        assertEquals("draft", result.get(5, TimeUnit.SECONDS).line());
        assertEquals(2, repaints.get());
    }

    @Test
    void handlersRunBeforeBuiltInsAndIdleHooksRunWhileWaiting() throws Exception {
        List<String> seen = new ArrayList<>();
        AtomicInteger idle = new AtomicInteger();
        AtomicInteger accepts = new AtomicInteger();
        LineEditor.Options options = prompt();
        options.keys = key -> {
            if (!key.isCtrl('a')) return false;
            seen.add("ctrl-a");
            return true;
        };
        options.beforeAccept = () -> {
            if (accepts.getAndIncrement() > 0) return false;
            editor.setBuffer("/details", 8);
            return true;
        };
        options.idle = idle::incrementAndGet;
        CompletableFuture<LineEditor.Result> result = readAsync(options);
        waitUntil(() -> idle.get() >= 2);
        type("/d\u0001\r\r");
        assertEquals("/details", result.get(5, TimeUnit.SECONDS).line());
        assertEquals(List.of("ctrl-a"), seen);
    }
}
