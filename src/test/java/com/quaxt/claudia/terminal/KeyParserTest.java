package com.quaxt.claudia.terminal;

import static com.quaxt.claudia.terminal.TerminalEvent.ALT;
import static com.quaxt.claudia.terminal.TerminalEvent.CTRL;
import static com.quaxt.claudia.terminal.TerminalEvent.SHIFT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.quaxt.claudia.terminal.TerminalEvent.Key;
import com.quaxt.claudia.terminal.TerminalEvent.KeyType;
import com.quaxt.claudia.terminal.TerminalEvent.Mouse;
import com.quaxt.claudia.terminal.TerminalEvent.MouseAction;
import com.quaxt.claudia.terminal.TerminalEvent.Paste;
import java.io.ByteArrayInputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class KeyParserTest {
    private static TerminalEvent parse(String sequence) {
        return KeyParser.parse(sequence);
    }

    private static Key key(KeyType type, int modifiers) {
        return new Key(type, 0, modifiers);
    }

    @Test
    void decodesLegacyControlBytesAsControlKeys() {
        assertEquals(Key.ctrl('a'), parse("\u0001"));
        assertEquals(Key.ctrl('c'), parse("\u0003"));
        assertEquals(Key.ctrl('h'), parse("\b"));
        assertEquals(Key.ctrl('j'), parse("\n"));
        assertEquals(Key.ctrl('z'), parse("\u001a"));
        assertEquals(Key.ctrl(' '), parse("\u0000"));
        assertEquals(Key.ctrl('\\'), parse("\u001c"));
        assertEquals(Key.ctrl('_'), parse("\u001f"));
        assertEquals(Key.of(KeyType.TAB), parse("\t"));
        assertEquals(Key.of(KeyType.ENTER), parse("\r"));
        assertEquals(Key.of(KeyType.BACKSPACE), parse("\u007f"));
        assertEquals(Key.of(KeyType.ESCAPE), parse("\u001b"));
        assertEquals(Key.character('é'), parse("é"));
        assertEquals(Key.character(0x1F600), parse("\uD83D\uDE00"));
    }

    @Test
    void kittyEncodingsMatchTheirLegacyForms() {
        for (char letter = 'a'; letter <= 'z'; letter++) {
            String legacy = Character.toString(letter - 'a' + 1);
            if (letter == 'i' || letter == 'm') continue; // Tab and Enter have their own keys
            assertEquals(parse(legacy), parse("\u001b[" + (int) letter + ";5u"), "Ctrl-" + letter);
        }
        for (int code : new int[] {9, 13, 27, 127}) {
            for (String modifiers : List.of("", ";1")) {
                assertEquals(parse(Character.toString(code)), parse("\u001b[" + code + modifiers + "u"));
            }
        }
        assertEquals(parse("\u001b[Z"), parse("\u001b[9;2u"));
        assertEquals(parse("\u001bb"), parse("\u001b[98;3u"));
        assertEquals(parse("\u001b\u007f"), parse("\u001b[127;3u"));
    }

    @Test
    void decodesModifiedKeysFromCsiUModifyOtherKeysAndXtermForms() {
        assertEquals(key(KeyType.ENTER, SHIFT), parse("\u001b[13;2u"));
        assertEquals(key(KeyType.ENTER, SHIFT), parse("\u001b[27;2;13~"));
        assertEquals(key(KeyType.ENTER, CTRL), parse("\u001b[13;5u"));
        assertEquals(key(KeyType.ENTER, CTRL), parse("\u001b[27;5;13~"));
        assertEquals(key(KeyType.TAB, SHIFT), parse("\u001b[Z"));
        assertEquals(key(KeyType.BACKSPACE, CTRL), parse("\u001b[127;5u"));
        assertEquals(key(KeyType.LEFT, CTRL), parse("\u001b[1;5D"));
        assertEquals(key(KeyType.RIGHT, ALT), parse("\u001b[1;3C"));
        assertEquals(key(KeyType.DELETE, 0), parse("\u001b[3~"));
        assertEquals(key(KeyType.DELETE, CTRL), parse("\u001b[3;5~"));
        assertEquals(key(KeyType.HOME, 0), parse("\u001b[H"));
        assertEquals(key(KeyType.END, 0), parse("\u001b[4~"));
        assertEquals(key(KeyType.PAGE_UP, 0), parse("\u001b[5~"));
        assertEquals(key(KeyType.UP, 0), parse("\u001bOA"));
        assertEquals(key(KeyType.ENTER, 0), parse("\u001bOM"));
        assertEquals(new Key(KeyType.FUNCTION, 5, 0), parse("\u001b[15~"));
        assertEquals(new Key(KeyType.FUNCTION, 1, SHIFT), parse("\u001b[1;2P"));
        assertEquals(new Key(KeyType.CHARACTER, 'a', CTRL | SHIFT), parse("\u001b[97;6u"));
        assertEquals(Key.character('A'), parse("\u001b[97;2u"));
        // Caps Lock (64) and Num Lock (128) do not change the key.
        assertEquals(Key.ctrl('c'), parse("\u001b[99;69u"));
        assertEquals(Key.of(KeyType.ENTER), parse("\u001b[57414u"));
        assertEquals(Key.character('7'), parse("\u001b[57406u"));
        assertEquals(Key.of(KeyType.ESCAPE), parse("\u001b[91;5u"));
    }

    private static String win32(int vk, int scan, int unicode, int down, int state, int repeat) {
        return "\u001b[" + vk + ";" + scan + ";" + unicode + ";" + down + ";" + state + ";" + repeat + "_";
    }

    private static String win32(int vk, int unicode, int state) {
        return win32(vk, 0, unicode, 1, state, 1);
    }

    private static InputReader bufferedReader(String input) {
        return new InputReader(new ByteArrayInputStream(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void win32RetainsShiftEnterAndDecodesSpecialKeysAndControlStateBits() {
        assertEquals(Key.of(KeyType.ENTER), parse(win32(13, 28, 13, 1, 0, 1)));
        assertEquals(key(KeyType.ENTER, SHIFT), parse(win32(13, 28, 13, 1, 16, 1)));
        assertEquals(key(KeyType.ENTER, CTRL), parse(win32(13, 10, 8)));
        assertEquals(key(KeyType.ENTER, SHIFT | ALT | CTRL), parse(win32(13, 13, 16 | 2 | 8)));
        assertEquals(key(KeyType.TAB, SHIFT), parse(win32(9, 9, 16)));
        assertEquals(key(KeyType.BACKSPACE, CTRL), parse(win32(8, 127, 4)));
        assertEquals(Key.of(KeyType.ESCAPE), parse(win32(27, 27, 0)));
        int[] virtualKeys = {33, 34, 35, 36, 37, 38, 39, 40, 45, 46};
        KeyType[] types = {KeyType.PAGE_UP, KeyType.PAGE_DOWN, KeyType.END, KeyType.HOME,
                KeyType.LEFT, KeyType.UP, KeyType.RIGHT, KeyType.DOWN, KeyType.INSERT, KeyType.DELETE};
        for (int index = 0; index < virtualKeys.length; index++) {
            assertEquals(key(types[index], CTRL), parse(win32(virtualKeys[index], 0, 4 | 256)));
        }
        assertEquals(key(KeyType.LEFT, ALT), parse(win32(37, 0, 1)));
        assertEquals(key(KeyType.RIGHT, ALT), parse(win32(39, 0, 2)));
        for (int number = 1; number <= 24; number++) {
            assertEquals(new Key(KeyType.FUNCTION, number, SHIFT), parse(win32(111 + number, 0, 16)));
        }
        assertEquals(Key.of(KeyType.ENTER), parse(win32(13, 13, 32 | 64 | 128 | 256)));
        assertEquals(Key.of(KeyType.ENTER), parse("\u001b[13;28;13;1;2147483648;1_"));
    }

    @Test
    void win32UsesTranslatedUnicodeAndDoesNotConfuseScanCodesWithModifiers() {
        assertEquals(Key.character('a'), parse(win32(65, 30, 97, 1, 0, 1)));
        assertEquals(Key.character('A'), parse(win32(65, 'A', 16)));
        assertEquals(Key.character('a'), parse(win32(65, 'a', 16 | 128))); // Shift + Caps Lock
        assertEquals(Key.character('!'), parse(win32(49, '!', 16)));
        assertEquals(Key.character('é'), parse(win32(0, 'é', 0)));
        assertEquals(Key.character(0xe000), parse(win32(0, 0xe000, 0))); // Not a kitty functional key
        assertEquals(new Key(KeyType.CHARACTER, 'b', ALT), parse(win32(66, 'b', 2)));
        assertEquals(Key.character('@'), parse(win32(81, '@', 1 | 8))); // AltGr text
        assertEquals(Key.character('€'), parse(win32(69, '€', 1 | 8 | 16)));
        assertEquals(new Key(KeyType.CHARACTER, 'q', CTRL | ALT), parse(win32(81, 'q', 2 | 8)));
        // parse(String) returns only the first event; the streaming reader retains repeats.
        assertEquals(Key.character('x'), parse(win32(88, 45, 'x', 1, 0, 3)));
    }

    @Test
    void win32ControlShortcutsMatchLegacyAndUseVirtualKeysWhenUnicodeIsMissing() {
        for (int letter = 'a'; letter <= 'z'; letter++) {
            assertEquals(Key.ctrl((char) letter), parse(win32(Character.toUpperCase(letter), letter - 'a' + 1, 8)));
            assertEquals(Key.ctrl((char) letter), parse(win32(Character.toUpperCase(letter), 0, 4)));
            assertEquals(Key.ctrl((char) letter), parse(win32(Character.toUpperCase(letter), Character.toUpperCase(letter), 8)));
        }
        assertEquals(new Key(KeyType.CHARACTER, 'c', CTRL | SHIFT), parse(win32(67, 3, 8 | 16)));
        assertEquals(Key.ctrl(' '), parse(win32(32, 0, 8)));
        assertEquals(Key.of(KeyType.ESCAPE), parse(win32(219, 27, 8)));
        assertEquals(Key.of(KeyType.ESCAPE), parse(win32(219, 0, 8)));
        assertEquals(Key.ctrl('\\'), parse(win32(220, 28, 8)));
        assertEquals(Key.ctrl(']'), parse(win32(221, 29, 8)));
        assertEquals(Key.ctrl('^'), parse(win32(54, 30, 8)));
        assertEquals(Key.ctrl('_'), parse(win32(189, 31, 8)));
        assertEquals(Key.ctrl('j'), parse(win32(0, 10, 0)));
    }

    @Test
    void win32DefaultsOmittedFieldsAndTrailingParameters() throws Exception {
        for (String parameters : List.of(";28;97;1;0;1", "65;;97;1;0;1",
                "65;28;97;1;;1", "65;28;97;1;0;")) {
            assertEquals(Key.character('a'), KeyParser.csi(parameters, '_'), parameters);
        }
        assertEquals(Key.ctrl('a'), KeyParser.csi("65;28;;1;8;1", '_'));
        assertNull(KeyParser.csi("65;28;97;;0;1", '_'));
        assertEquals(key(KeyType.ENTER, SHIFT), parse("\u001b[13;28;13;1;16_"));
        assertEquals(Key.of(KeyType.ENTER), parse("\u001b[13;;;1_"));
        assertEquals(Key.character('a'), parse("\u001b[;;97;1_"));
        assertEquals(Key.character('a'), parse("\u001b[;;97;1;;_"));
        InputReader defaults = bufferedReader("\u001b[;;97;1_\u001b[;;98;1;;_");
        assertEquals(Key.character('a'), defaults.readEvent(1000));
        assertEquals(Key.character('b'), defaults.readEvent(1000));
        assertInstanceOf(TerminalEvent.EndOfInput.class, defaults.readEvent(1000));
        for (String parameters : List.of("", ";;;;;", "13", "13;28", "13;28;13", "13;28;13;")) {
            assertNull(KeyParser.csi(parameters, '_'), parameters); // Omitted Kd means key-up.
            InputReader reader = bufferedReader("\u001b[" + parameters + "_x");
            assertEquals(Key.character('x'), reader.readEvent(1000));
            assertInstanceOf(TerminalEvent.EndOfInput.class, reader.readEvent(1000));
        }
        assertEquals(Key.character('a'), parse("\u001b[65535;65535;97;1;4294967295;1_"));
    }

    @Test
    void win32RepeatsTextSpecialKeysAndShortcutsInOrderWithoutLeakingBetweenSources() throws Exception {
        InputReader reader = bufferedReader(win32(88, 45, 'x', 1, 0, 3)
                + win32(13, 28, 13, 1, 16, 2) + win32(67, 46, 3, 1, 8, 2)
                + win32(112, 0, 0, 1, 0, 2) + "z");
        InputReader other = bufferedReader("q");
        for (Key expected : List.of(Key.character('x'), Key.character('x'), Key.character('x'),
                key(KeyType.ENTER, SHIFT), key(KeyType.ENTER, SHIFT), Key.ctrl('c'), Key.ctrl('c'),
                new Key(KeyType.FUNCTION, 1, 0), new Key(KeyType.FUNCTION, 1, 0), Key.character('z'))) {
            assertEquals(expected, reader.readEvent(1000));
        }
        assertInstanceOf(TerminalEvent.EndOfInput.class, reader.readEvent(1000));
        assertEquals(Key.character('q'), other.readEvent(1000));
        reader = bufferedReader(win32(0, 0, 'a', 1, 0, 65535) + "b");
        for (int index = 0; index < 65535; index++) assertEquals(Key.character('a'), reader.readEvent(1000));
        assertEquals(Key.character('b'), reader.readEvent(1000));
        assertInstanceOf(TerminalEvent.EndOfInput.class, reader.readEvent(1000));
    }

    @Test
    void win32RepeatsDoNotWaitForNewInputAndIgnoredRecordsDoNotRepeat() throws Exception {
        try (PipedInputStream in = new PipedInputStream(); PipedOutputStream out = new PipedOutputStream(in)) {
            InputReader reader = new InputReader(in);
            out.write((win32(65, 0, 'a', 0, 0, 65535) + win32(16, 0, 0, 1, 0, 65535)
                    + win32(0, 0, 0, 1, 0, 65535) + win32(65, 0, 'a', 1, 0, 3))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
            assertEquals(Key.character('a'), reader.readEvent(1000));
            assertEquals(Key.character('a'), reader.readEvent(0));
            assertEquals(Key.character('a'), reader.readEvent(0));
            assertNull(reader.readEvent(0));
        }
    }

    @Test
    void win32CtrlJAndMComeFromUnicodeOrVirtualKeyFallbackWithoutChangingBareCr() {
        for (int letter : new int[] {'j', 'm'}) {
            int control = letter - 'a' + 1;
            assertEquals(Key.ctrl((char) letter), parse(win32(0, control, 8)));
            assertEquals(Key.ctrl((char) letter), parse(win32(Character.toUpperCase(letter), 0, 8)));
            assertEquals(Key.ctrl((char) letter), parse(win32(Character.toUpperCase(letter), control, 8)));
        }
        assertEquals(Key.ctrl('j'), parse("\u001b[;;10;1_"));
        assertEquals(Key.of(KeyType.ENTER), parse("\u001b[;;13;1_"));
        assertEquals(Key.ctrl('m'), parse("\u001b[;;13;1;8_"));
        assertEquals(key(KeyType.ENTER, CTRL), parse(win32(13, 10, 8)));
    }

    @Test
    void win32ReaderSkipsReleasesModifiersAndDeadKeysWithoutUnknownEvents() throws Exception {
        StringBuilder input = new StringBuilder(win32(13, 28, 13, 0, 16, 1));
        for (int vk : new int[] {16, 17, 18, 20, 91, 92, 144, 145, 160, 161, 162, 163, 164, 165}) {
            assertEquals(Key.of(KeyType.UNKNOWN), parse(win32(vk, 0, 16)));
            input.append(win32(vk, 0, 16));
        }
        input.append(win32(0, 0, 0));
        input.append(win32(13, 13, 16)).append(win32(13, 28, 13, 0, 16, 1)).append('x');
        assertEquals(key(KeyType.ENTER, SHIFT), parse(input.toString()));
        InputReader reader = bufferedReader(input.toString());
        assertEquals(key(KeyType.ENTER, SHIFT), reader.readEvent(1000));
        assertEquals(Key.character('x'), reader.readEvent(1000));
        assertInstanceOf(TerminalEvent.EndOfInput.class, reader.readEvent(1000));
        assertEquals(Key.of(KeyType.UNKNOWN), parse(win32(13, 28, 13, 0, 16, 1)));
        assertInstanceOf(TerminalEvent.EndOfInput.class,
                bufferedReader(win32(16, 0, 16)).readEvent(1000));
    }

    @Test
    void win32IgnoredRecordsRespectPollingAndFiniteTimeouts() throws Exception {
        try (PipedInputStream in = new PipedInputStream(); PipedOutputStream out = new PipedOutputStream(in)) {
            InputReader reader = new InputReader(in);
            out.write(win32(16, 0, 16).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
            assertNull(reader.readEvent(20));
            assertNull(reader.readEvent(0));
            out.write((win32(13, 28, 13, 0, 16, 1) + "a").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
            assertEquals(Key.character('a'), reader.readEvent(1000));
        }
    }

    @Test
    void win32CombinesUtf16PairsAndReplacesUnpairedSurrogatesWithoutLosingInput() throws Exception {
        String high = win32(0, 0xd83d, 0), low = win32(0, 0xde00, 0);
        assertEquals(Key.character(0x1f600), parse(high + low));
        assertEquals(Key.character(0x1f600), parse(high + win32(0, 0, 0xde00, 0, 0, 1) + low));
        assertEquals(Key.character(0xfffd), parse(high));
        assertEquals(Key.character(0xfffd), parse(low));
        InputReader reader = bufferedReader(high + "x" + high + "\u001b[A" + high + low + low);
        assertEquals(Key.character(0xfffd), reader.readEvent(1000));
        assertEquals(Key.character('x'), reader.readEvent(1000));
        assertEquals(Key.character(0xfffd), reader.readEvent(1000));
        assertEquals(Key.of(KeyType.UP), reader.readEvent(1000));
        assertEquals(Key.character(0x1f600), reader.readEvent(1000));
        assertEquals(Key.character(0xfffd), reader.readEvent(1000));
        assertInstanceOf(TerminalEvent.EndOfInput.class, reader.readEvent(1000));
        reader = bufferedReader(high + win32(0, 0xde00, 2) + "\u001b[200~paste\u001b[201~");
        assertEquals(Key.character(0xfffd), reader.readEvent(1000));
        assertEquals(new Key(KeyType.CHARACTER, 0xfffd, ALT), reader.readEvent(1000));
        assertEquals(new Paste("paste"), reader.readEvent(1000));
    }

    @Test
    void win32SurrogateRepeatsPreservePairsUnpairedUnitsAndFollowingInput() throws Exception {
        InputReader reader = bufferedReader(win32(0, 0, 0xd83d, 1, 0, 3)
                + win32(0, 0, 0xde00, 1, 0, 3) + "x");
        for (int index = 0; index < 3; index++) assertEquals(Key.character(0x1f600), reader.readEvent(1000));
        assertEquals(Key.character('x'), reader.readEvent(1000));
        assertInstanceOf(TerminalEvent.EndOfInput.class, reader.readEvent(1000));
        for (int[] counts : new int[][] {{3, 1}, {1, 3}}) {
            reader = bufferedReader(win32(0, 0, 0xd83d, 1, 0, counts[0])
                    + win32(0, 0, 0xde00, 1, 0, counts[1]) + "x");
            assertEquals(Key.character(0x1f600), reader.readEvent(1000));
            assertEquals(Key.character(0xfffd), reader.readEvent(1000));
            assertEquals(Key.character(0xfffd), reader.readEvent(1000));
            assertEquals(Key.character('x'), reader.readEvent(1000));
            assertInstanceOf(TerminalEvent.EndOfInput.class, reader.readEvent(1000));
        }
        reader = bufferedReader(win32(0, 0, 0xd83d, 1, 0, 2) + "x"
                + win32(0, 0, 0xde00, 1, 0, 2) + "y");
        for (Key expected : List.of(Key.character(0xfffd), Key.character(0xfffd), Key.character('x'),
                Key.character(0xfffd), Key.character(0xfffd), Key.character('y'))) {
            assertEquals(expected, reader.readEvent(1000));
        }
        assertInstanceOf(TerminalEvent.EndOfInput.class, reader.readEvent(1000));
        assertEquals(Key.character(0x1f600), parse("\u001b[;;55357;1_\u001b[;;56832;1_"));
    }

    @Test
    void win32SurrogateLookaheadHandlesSplitInputAndDoesNotRetainTimedOutHalves() throws Exception {
        String high = win32(0, 0xd83d, 0), low = win32(0, 0xde00, 0);
        try (PipedInputStream in = new PipedInputStream(); PipedOutputStream out = new PipedOutputStream(in)) {
            InputReader reader = new InputReader(in);
            out.write((high + low.substring(0, low.length() - 4)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
            var event = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return reader.readEvent(1000);
                } catch (java.io.IOException error) {
                    throw new AssertionError(error);
                }
            });
            out.write(low.substring(low.length() - 4).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
            assertEquals(Key.character(0x1f600), event.get(2, java.util.concurrent.TimeUnit.SECONDS));
            out.write(high.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
            assertEquals(Key.character(0xfffd), reader.readEvent(1000));
            out.write((low + "z").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
            assertEquals(Key.character(0xfffd), reader.readEvent(1000));
            assertEquals(Key.character('z'), reader.readEvent(1000));
        }
    }

    @Test
    void win32RejectsMalformedOrOutOfRangeFields() {
        for (String parameters : List.of(";;;;;;", "13;28;13;1;16;1;0",
                "13;28;13;1;16;1;", "-13;28;13;1;16;1", "+13;28;13;1;16;1",
                "13;28;13;2;16;1", "13;28;13;1;16;0", "13:1;28;13;1;16;1",
                "13;28;1114112;1;16;1", "13;65536;13;1;16;1", "13;28;13;1;4294967296;1",
                "65536;28;13;1;16;1", "13;28;13;1;16;65536", "13;28;65536;1;16;1",
                "13;28;13;1;16;-1", "13;28;13;1;16;1:2", "13;28;13;1; 16;1",
                "13;28;13;1;16;999999999999999999999", "999999999999;28;13;1;16;1", "?13;28;13;1;16;1")) {
            assertEquals(Key.of(KeyType.UNKNOWN), KeyParser.csi(parameters, '_'), parameters);
            assertEquals(Key.of(KeyType.UNKNOWN), parse("\u001b[" + parameters + "_"), parameters);
        }
    }

    @Test
    void escapePrefixesAltUnlessItStartsASequence() {
        assertEquals(new Key(KeyType.CHARACTER, 'b', ALT), parse("\u001bb"));
        assertEquals(key(KeyType.ENTER, ALT), parse("\u001b\r"));
        assertEquals(new Key(KeyType.CHARACTER, 'd', CTRL | ALT), parse("\u001b\u0004"));
        assertEquals(new Key(KeyType.CHARACTER, '[', ALT), parse("\u001b["));
        assertEquals(new Key(KeyType.CHARACTER, 'O', ALT), parse("\u001bO"));
    }

    @Test
    void ignoresReportsAndUnknownSequences() {
        assertEquals(Key.of(KeyType.UNKNOWN), parse("\u001b[?1;2c"));
        assertEquals(Key.of(KeyType.UNKNOWN), parse("\u001b[I"));
        assertEquals(Key.of(KeyType.UNKNOWN), parse("\u001b[12;40R"));
        assertEquals(Key.of(KeyType.UNKNOWN), parse("\u001b[57441u"));
        assertEquals(Key.of(KeyType.UNKNOWN), parse("\u0085"));
    }

    @Test
    void decodesSgrMouseReportsAndBracketedPaste() {
        assertEquals(new Mouse(MouseAction.PRESS, 0, 12, 7), parse("\u001b[<0;12;7M"));
        assertEquals(new Mouse(MouseAction.RELEASE, 0, 12, 7), parse("\u001b[<0;12;7m"));
        assertEquals(new Mouse(MouseAction.DRAG, 0, 2, 3), parse("\u001b[<32;2;3M"));
        assertEquals(new Mouse(MouseAction.SCROLL_UP, 0, 4, 9), parse("\u001b[<64;4;9M"));
        assertEquals(new Mouse(MouseAction.SCROLL_DOWN, 1, 4, 9), parse("\u001b[<65;4;9M"));
        assertEquals(new Paste("a\u001b[2Jb\r\nc\u001b[20"), parse("\u001b[200~a\u001b[2Jb\r\nc\u001b[20\u001b[201~"));
        assertEquals(new Paste("unterminated"), parse("\u001b[200~unterminated"));
    }

    @Test
    void readerDecodesUtf8AcrossWritesAndKeepsEventOrder() throws Exception {
        PipedInputStream in = new PipedInputStream();
        PipedOutputStream out = new PipedOutputStream(in);
        InputReader reader = new InputReader(in);
        assertNull(reader.readEvent(20));

        out.write(new byte[] {'x', (byte) 0xc3});
        out.flush();
        assertEquals(Key.character('x'), reader.readEvent(1000));
        out.write(new byte[] {(byte) 0xa9, (byte) 0xff});
        out.write("\u001b[A\u001b[200~pa".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.flush();
        assertEquals(Key.character('é'), reader.readEvent(1000));
        assertEquals(Key.character(0xfffd), reader.readEvent(1000));
        assertEquals(key(KeyType.UP, 0), reader.readEvent(1000));
        // The paste waits for its terminator even when it arrives later. Write from this
        // thread: a piped stream treats input from a finished writer thread as broken.
        var paste = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return reader.readEvent(2000);
            } catch (java.io.IOException error) {
                throw new AssertionError(error);
            }
        });
        Thread.sleep(100);
        assertFalse(paste.isDone());
        out.write("ste\u001b[201~y".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.flush();
        assertEquals(new Paste("paste"), paste.get(2, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(Key.character('y'), reader.readEvent(1000));

        assertEquals('q', writeAndPeek(out, reader));
        assertEquals(Key.character('q'), reader.readEvent(1000));
        out.close();
        assertInstanceOf(TerminalEvent.EndOfInput.class, reader.readEvent(1000));
        assertInstanceOf(TerminalEvent.EndOfInput.class, reader.readEvent(1000));
    }

    private static int writeAndPeek(PipedOutputStream out, InputReader reader) throws Exception {
        out.write('q');
        out.flush();
        int peeked = reader.peek(1000);
        assertEquals(peeked, reader.peek(0));
        return peeked;
    }

    @Test
    void anEscapeSplitFromItsSequenceByMoreThanTheTimeoutIsAnEscapeKey() throws Exception {
        PipedInputStream in = new PipedInputStream();
        PipedOutputStream out = new PipedOutputStream(in);
        InputReader reader = new InputReader(in);
        out.write(0x1b);
        out.flush();
        List<TerminalEvent> events = new ArrayList<>();
        events.add(reader.readEvent(1000));
        out.write('b');
        out.flush();
        events.add(reader.readEvent(1000));
        assertEquals(List.of(Key.of(KeyType.ESCAPE), Key.character('b')), events);
        out.close();
    }
}
