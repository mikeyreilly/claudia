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
