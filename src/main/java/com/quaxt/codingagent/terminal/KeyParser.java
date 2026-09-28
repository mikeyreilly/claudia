package com.quaxt.codingagent.terminal;

import com.quaxt.codingagent.terminal.TerminalEvent.Key;
import com.quaxt.codingagent.terminal.TerminalEvent.KeyType;
import com.quaxt.codingagent.terminal.TerminalEvent.Mouse;
import com.quaxt.codingagent.terminal.TerminalEvent.MouseAction;
import java.io.InterruptedIOException;

/**
 * Decodes the key sequences Ghostty sends: UTF-8 text, C0 controls, Alt as an
 * Escape prefix, xterm CSI/SS3 keys with modifier parameters, xterm
 * modifyOtherKeys, kitty keyboard-protocol CSI-u keys, SGR mouse reports, and
 * bracketed paste.
 */
public final class KeyParser {
    /** How long to wait for the rest of a sequence after Escape. */
    static final long ESCAPE_TIMEOUT_MILLIS = 50;

    /** A pull source of decoded code points. */
    interface Source {
        int read(long timeoutMillis) throws InterruptedIOException;

        void unread(int codePoint);
    }

    private KeyParser() {}

    /** Decodes the first event of an already-buffered sequence. */
    public static TerminalEvent parse(String sequence) {
        Source source = new Source() {
            int index;
            final java.util.ArrayDeque<Integer> pushback = new java.util.ArrayDeque<>();

            @Override
            public int read(long timeoutMillis) {
                if (!pushback.isEmpty()) return pushback.pop();
                if (index >= sequence.length()) return InputReader.TIMEOUT;
                int codePoint = sequence.codePointAt(index);
                index += Character.charCount(codePoint);
                return codePoint;
            }

            @Override
            public void unread(int codePoint) {
                if (codePoint >= 0) pushback.push(codePoint);
            }
        };
        try {
            TerminalEvent event = read(source, 0);
            return event == null ? Key.of(KeyType.UNKNOWN) : event;
        } catch (InterruptedIOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static TerminalEvent read(Source source, long timeoutMillis) throws InterruptedIOException {
        int first = source.read(timeoutMillis);
        if (first == InputReader.TIMEOUT) return null;
        if (first == InputReader.EOF) return new TerminalEvent.EndOfInput();
        if (first != 0x1b) return single(first);
        int next = source.read(ESCAPE_TIMEOUT_MILLIS);
        if (next < 0) return Key.of(KeyType.ESCAPE);
        return switch (next) {
            case '[' -> csi(source);
            case 'O' -> ss3(source);
            case 0x1b -> {
                // Escape pressed twice: report the first and decode the second on its own.
                source.unread(next);
                yield Key.of(KeyType.ESCAPE);
            }
            default -> single(next).withModifiers(TerminalEvent.ALT);
        };
    }

    /** One code point outside an escape sequence. */
    static Key single(int codePoint) {
        return switch (codePoint) {
            case 0 -> Key.ctrl(' ');
            case 9 -> Key.of(KeyType.TAB);
            case 13 -> Key.of(KeyType.ENTER);
            case 27 -> Key.of(KeyType.ESCAPE);
            case 127 -> Key.of(KeyType.BACKSPACE);
            default -> {
                if (codePoint < 27) yield Key.ctrl((char) ('a' + codePoint - 1));
                if (codePoint < 32) yield Key.ctrl("\\]^_".charAt(codePoint - 28));
                if (codePoint >= 0x80 && codePoint < 0xa0) yield Key.of(KeyType.UNKNOWN);
                yield Key.character(codePoint);
            }
        };
    }

    private static TerminalEvent csi(Source source) throws InterruptedIOException {
        StringBuilder parameters = new StringBuilder();
        int finalByte = -1;
        while (parameters.length() < 64) {
            int value = source.read(ESCAPE_TIMEOUT_MILLIS);
            if (value < 0) break;
            if (value >= 0x40 && value <= 0x7e) {
                finalByte = value;
                break;
            }
            if (value < 0x20 || value > 0x3f) {
                // Not part of a control sequence; leave it for the next event.
                source.unread(value);
                break;
            }
            parameters.append((char) value);
        }
        if (finalByte < 0) {
            return parameters.isEmpty() ? Key.character('[').withModifiers(TerminalEvent.ALT) : Key.of(KeyType.UNKNOWN);
        }
        if (finalByte == '~' && parameters.toString().equals("200")) {
            return new TerminalEvent.Paste(readPaste(source));
        }
        return csi(parameters.toString(), (char) finalByte);
    }

    private static String readPaste(Source source) throws InterruptedIOException {
        String end = Ansi.PASTE_END;
        StringBuilder content = new StringBuilder();
        int matched = 0;
        while (true) {
            int value = source.read(-1);
            if (value < 0) {
                content.append(end, 0, matched);
                return content.toString();
            }
            if (value == end.charAt(matched)) {
                if (++matched == end.length()) return content.toString();
                continue;
            }
            if (matched > 0) {
                content.append(end, 0, matched);
                matched = 0;
                if (value == end.charAt(0)) {
                    matched = 1;
                    continue;
                }
            }
            content.appendCodePoint(value);
        }
    }

    /** Decodes a CSI sequence given its parameter bytes and final byte. */
    static TerminalEvent csi(String parameters, char finalByte) {
        char marker = parameters.isEmpty() ? 0 : parameters.charAt(0);
        if (marker == '<') {
            return finalByte == 'M' || finalByte == 'm' ? mouse(parameters.substring(1), finalByte) : Key.of(KeyType.UNKNOWN);
        }
        if (marker == '?' || marker == '>' || marker == '=') return Key.of(KeyType.UNKNOWN);
        int[] values = numbers(parameters);
        int modifiers = values.length > 1 ? modifiers(values[1]) : 0;
        return switch (finalByte) {
            case 'A' -> key(KeyType.UP, modifiers);
            case 'B' -> key(KeyType.DOWN, modifiers);
            case 'C' -> key(KeyType.RIGHT, modifiers);
            case 'D' -> key(KeyType.LEFT, modifiers);
            case 'H' -> key(KeyType.HOME, modifiers);
            case 'F' -> key(KeyType.END, modifiers);
            case 'Z' -> key(KeyType.TAB, modifiers | TerminalEvent.SHIFT);
            case 'P' -> function(1, modifiers);
            case 'Q' -> function(2, modifiers);
            case 'S' -> function(4, modifiers);
            // Without a modifier, CSI R is a cursor-position report rather than F3.
            case 'R' -> values.length > 1 && values[0] == 1 ? function(3, modifiers) : Key.of(KeyType.UNKNOWN);
            case 'u' -> values.length == 0 || values[0] < 0 ? Key.of(KeyType.UNKNOWN) : codeKey(values[0], modifiers);
            case '~' -> tilde(values, modifiers);
            default -> Key.of(KeyType.UNKNOWN);
        };
    }

    private static Key tilde(int[] values, int modifiers) {
        int code = values.length == 0 ? -1 : values[0];
        return switch (code) {
            case 1, 7 -> key(KeyType.HOME, modifiers);
            case 2 -> key(KeyType.INSERT, modifiers);
            case 3 -> key(KeyType.DELETE, modifiers);
            case 4, 8 -> key(KeyType.END, modifiers);
            case 5 -> key(KeyType.PAGE_UP, modifiers);
            case 6 -> key(KeyType.PAGE_DOWN, modifiers);
            case 11, 12, 13, 14, 15 -> function(code - 10, modifiers);
            case 17, 18, 19, 20, 21 -> function(code - 11, modifiers);
            case 23, 24 -> function(code - 12, modifiers);
            // xterm modifyOtherKeys: CSI 27 ; modifiers ; code ~
            case 27 -> values.length > 2 && values[2] >= 0 ? codeKey(values[2], modifiers) : Key.of(KeyType.UNKNOWN);
            default -> Key.of(KeyType.UNKNOWN);
        };
    }

    /** A kitty/fixterms key code with its modifiers. */
    private static Key codeKey(int code, int modifiers) {
        Key key = switch (code) {
            case 8, 127 -> Key.of(KeyType.BACKSPACE);
            case 9 -> Key.of(KeyType.TAB);
            case 13, 57414 -> Key.of(KeyType.ENTER);
            case 27 -> Key.of(KeyType.ESCAPE);
            case 57409 -> Key.character('.');
            case 57410 -> Key.character('/');
            case 57411 -> Key.character('*');
            case 57412 -> Key.character('-');
            case 57413 -> Key.character('+');
            case 57415 -> Key.character('=');
            case 57416 -> Key.character(',');
            case 57417 -> Key.of(KeyType.LEFT);
            case 57418 -> Key.of(KeyType.RIGHT);
            case 57419 -> Key.of(KeyType.UP);
            case 57420 -> Key.of(KeyType.DOWN);
            case 57421 -> Key.of(KeyType.PAGE_UP);
            case 57422 -> Key.of(KeyType.PAGE_DOWN);
            case 57423 -> Key.of(KeyType.HOME);
            case 57424 -> Key.of(KeyType.END);
            case 57425 -> Key.of(KeyType.INSERT);
            case 57426 -> Key.of(KeyType.DELETE);
            default -> {
                if (code >= 57399 && code <= 57408) yield Key.character('0' + code - 57399);
                if (code >= 57376 && code <= 57398) yield new Key(KeyType.FUNCTION, code - 57376 + 13, 0);
                // Remaining kitty functional keys live in the private-use area.
                if (code >= 0xe000 && code <= 0xf8ff) yield Key.of(KeyType.UNKNOWN);
                if (code < 32) yield single(code);
                if (code > 0x10ffff) yield Key.of(KeyType.UNKNOWN);
                yield Key.character(code);
            }
        };
        if (key.type() == KeyType.CHARACTER && modifiers == TerminalEvent.SHIFT) {
            return Key.character(Character.toUpperCase(key.codePoint()));
        }
        // Ctrl-[ is Escape in every legacy encoding; keep it an Escape substitute.
        if (key.type() == KeyType.CHARACTER && key.codePoint() == '[' && (modifiers & TerminalEvent.CTRL) != 0) {
            return Key.of(KeyType.ESCAPE).withModifiers(modifiers & ~TerminalEvent.CTRL);
        }
        return key.withModifiers(modifiers);
    }

    private static TerminalEvent mouse(String parameters, char finalByte) {
        int[] values = numbers(parameters);
        if (values.length != 3 || values[0] < 0 || values[1] < 0 || values[2] < 0) return Key.of(KeyType.UNKNOWN);
        int code = values[0];
        MouseAction action;
        if ((code & 64) != 0) {
            action = (code & 1) == 0 ? MouseAction.SCROLL_UP : MouseAction.SCROLL_DOWN;
        } else if (finalByte == 'm' || (code & 3) == 3) {
            action = MouseAction.RELEASE;
        } else if ((code & 32) != 0) {
            action = MouseAction.DRAG;
        } else {
            action = MouseAction.PRESS;
        }
        return new Mouse(action, code & 3, values[1], values[2]);
    }

    private static TerminalEvent ss3(Source source) throws InterruptedIOException {
        int value = source.read(ESCAPE_TIMEOUT_MILLIS);
        if (value < 0) return Key.character('O').withModifiers(TerminalEvent.ALT);
        return switch (value) {
            case 'A' -> Key.of(KeyType.UP);
            case 'B' -> Key.of(KeyType.DOWN);
            case 'C' -> Key.of(KeyType.RIGHT);
            case 'D' -> Key.of(KeyType.LEFT);
            case 'H' -> Key.of(KeyType.HOME);
            case 'F' -> Key.of(KeyType.END);
            case 'M' -> Key.of(KeyType.ENTER);
            case 'P' -> function(1, 0);
            case 'Q' -> function(2, 0);
            case 'R' -> function(3, 0);
            case 'S' -> function(4, 0);
            default -> Key.of(KeyType.UNKNOWN);
        };
    }

    private static Key key(KeyType type, int modifiers) {
        return new Key(type, 0, modifiers);
    }

    private static Key function(int number, int modifiers) {
        return new Key(KeyType.FUNCTION, number, modifiers);
    }

    /** Converts an encoded modifier parameter (1 + bits) to modifier bits, ignoring lock keys. */
    private static int modifiers(int encoded) {
        return encoded > 1 ? (encoded - 1) & 0xf : 0;
    }

    /** The first sub-parameter of each field; missing or malformed fields are -1. */
    private static int[] numbers(String parameters) {
        if (parameters.isEmpty()) return new int[0];
        String[] fields = parameters.split(";", -1);
        int[] values = new int[fields.length];
        for (int index = 0; index < fields.length; index++) {
            String field = fields[index];
            int colon = field.indexOf(':');
            if (colon >= 0) field = field.substring(0, colon);
            try {
                values[index] = field.isEmpty() ? -1 : Integer.parseInt(field);
            } catch (NumberFormatException malformed) {
                values[index] = -1;
            }
        }
        return values;
    }
}
