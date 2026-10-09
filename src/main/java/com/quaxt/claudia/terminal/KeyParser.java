package com.quaxt.claudia.terminal;

import com.quaxt.claudia.terminal.TerminalEvent.Key;
import com.quaxt.claudia.terminal.TerminalEvent.KeyType;
import com.quaxt.claudia.terminal.TerminalEvent.Mouse;
import com.quaxt.claudia.terminal.TerminalEvent.MouseAction;
import java.io.InterruptedIOException;

/**
 * Decodes the key sequences Ghostty sends: UTF-8 text, C0 controls, Alt as an
 * Escape prefix, xterm CSI/SS3 keys with modifier parameters, xterm
 * modifyOtherKeys, kitty keyboard-protocol CSI-u keys, Windows Terminal
 * win32-input-mode records, SGR mouse reports, and bracketed paste.
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
        long started = System.nanoTime();
        long remaining = timeoutMillis;
        while (true) {
            int first = source.read(remaining);
            if (first == InputReader.TIMEOUT) return null;
            if (first == InputReader.EOF) return new TerminalEvent.EndOfInput();
            TerminalEvent event = readOne(source, first);
            if (event != null) return event;
            // Skip ignored Win32 records without restarting the caller's timeout.
            if (timeoutMillis >= 0) {
                remaining = Math.max(0, timeoutMillis - (System.nanoTime() - started) / 1_000_000);
            }
        }
    }

    private static TerminalEvent readOne(Source source, int first) throws InterruptedIOException {
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
        if (finalByte == '_') {
            Win32Record record = win32Record(parameters.toString());
            Key key = record.key();
            if (key == null) return null;
            if (key.type() == KeyType.CHARACTER && Character.isHighSurrogate((char) key.codePoint())) {
                return win32Surrogate(source, record);
            }
            replayRepeat(source, record);
            if (key.type() == KeyType.CHARACTER && Character.isLowSurrogate((char) key.codePoint())) {
                return new Key(KeyType.CHARACTER, 0xfffd, key.modifiers());
            }
            return key;
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

    /** Decodes CSI parameters/final byte; null means an ignored Win32 record. */
    static TerminalEvent csi(String parameters, char finalByte) {
        if (finalByte == '_') {
            Key key = win32Record(parameters).key();
            // This helper cannot combine records; never expose a UTF-16 code unit as text.
            if (key != null && key.type() == KeyType.CHARACTER && Character.isSurrogate((char) key.codePoint())) {
                return new Key(KeyType.CHARACTER, 0xfffd, key.modifiers());
            }
            return key;
        }
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

    private record Win32Record(Key key, int[] values) {}

    private static Win32Record invalidWin32Record() {
        return new Win32Record(Key.of(KeyType.UNKNOWN), null);
    }

    /** CSI Vk;Sc;Uc;Kd;Cs;Rc_: one Win32 KEY_EVENT_RECORD, not xterm modifiers. */
    private static Win32Record win32Record(String parameters) {
        // WT terminalInput.cpp defaults omitted Vk/Sc/Uc/Kd/Cs to 0, Rc to 1.
        // Vk/Sc/Uc/Rc are WORDs, Kd is a BOOL, and Cs is an unsigned DWORD.
        // Empty fields (including trailing ones) are defaults, not malformed input.
        if (!parameters.matches("[0-9]*(;[0-9]*){0,5}")) return invalidWin32Record();
        String[] fields = parameters.split(";", -1);
        int[] values = new int[6];
        values[5] = 1;
        try {
            for (int index = 0; index < fields.length; index++) {
                if (fields[index].isEmpty()) continue;
                long value = Long.parseLong(fields[index]);
                if (value > (index == 4 ? 0xffffffffL : 0xffffL)) return invalidWin32Record();
                values[index] = (int) value;
            }
        } catch (NumberFormatException malformed) {
            return invalidWin32Record();
        }
        if (values[3] > 1 || values[5] == 0) return invalidWin32Record();
        return new Win32Record(win32Key(values), values);
    }

    /** Replay only the remaining count, keeping pushback bounded even for Rc=65535. */
    private static void replayRepeat(Source source, Win32Record record) {
        int[] values = record.values();
        if (values == null || values[5] <= 1) return;
        StringBuilder packet = new StringBuilder("\u001b[");
        for (int index = 0; index < 5; index++) {
            packet.append(Integer.toUnsignedLong(values[index])).append(';');
        }
        packet.append(values[5] - 1).append('_');
        for (int index = packet.length() - 1; index >= 0; index--) source.unread(packet.charAt(index));
    }

    private static Key win32Key(int[] values) {
        int vk = values[0], unicode = values[2], down = values[3], state = values[4];
        if (down == 0) return null;
        // Modifier/lock presses do not represent editor events, even if Uc is nonzero.
        if (vk == 0x10 || vk == 0x11 || vk == 0x12 || vk == 0x14
                || vk == 0x5b || vk == 0x5c || vk == 0x90 || vk == 0x91
                || (vk >= 0xa0 && vk <= 0xa5)) return null;
        int modifiers = ((state & 0x10) != 0 ? TerminalEvent.SHIFT : 0)
                | ((state & 0x03) != 0 ? TerminalEvent.ALT : 0) // right/left Alt
                | ((state & 0x0c) != 0 ? TerminalEvent.CTRL : 0); // right/left Ctrl
        // Lock states and ENHANCED_KEY are deliberately not modifiers.
        KeyType type = switch (vk) {
            case 0x08 -> KeyType.BACKSPACE;
            case 0x09 -> KeyType.TAB;
            case 0x0d -> KeyType.ENTER;
            case 0x1b -> KeyType.ESCAPE;
            case 0x21 -> KeyType.PAGE_UP;
            case 0x22 -> KeyType.PAGE_DOWN;
            case 0x23 -> KeyType.END;
            case 0x24 -> KeyType.HOME;
            case 0x25 -> KeyType.LEFT;
            case 0x26 -> KeyType.UP;
            case 0x27 -> KeyType.RIGHT;
            case 0x28 -> KeyType.DOWN;
            case 0x2d -> KeyType.INSERT;
            case 0x2e -> KeyType.DELETE;
            default -> null;
        };
        if (type != null) return key(type, modifiers);
        if (vk >= 0x70 && vk <= 0x87) return function(vk - 0x70 + 1, modifiers);
        // AltGr is reported as right Alt + left Ctrl. Its Unicode text is already
        // layout-translated and must not turn into an editor shortcut.
        if ((state & 0x09) == 0x09 && unicode >= 32) modifiers = 0;
        if ((modifiers & TerminalEvent.CTRL) != 0) {
            // Prefer layout-translated Uc, including control bytes. Vk is a fallback
            // for shortcuts with no character (notably Ctrl-Space).
            int base = unicode == 0 ? win32ControlBase(vk)
                    : unicode < 27 ? 'a' + unicode - 1
                    : unicode < 32 ? "[\\]^_".charAt(unicode - 27)
                    : Character.toLowerCase(unicode);
            if (base >= 0) {
                return base < 128 ? codeKey(base, modifiers) : new Key(KeyType.CHARACTER, base, modifiers);
            }
        }
        if (unicode == 0) return null; // Dead keys and non-text keys, not Ctrl-Space.
        if (unicode < 32 || unicode == 127 || (unicode >= 0x80 && unicode < 0xa0)) {
            return single(unicode).withModifiers(modifiers);
        }
        // Uc already includes Shift/Caps Lock/layout translation; never uppercase it.
        if (modifiers == TerminalEvent.SHIFT) modifiers = 0;
        return new Key(KeyType.CHARACTER, unicode, modifiers);
    }

    private static int win32ControlBase(int vk) {
        if (vk >= 'A' && vk <= 'Z') return 'a' + vk - 'A';
        if (vk >= '0' && vk <= '9') return vk;
        return switch (vk) {
            case 0x20 -> ' ';
            case 0xba -> ';';
            case 0xbb -> '=';
            case 0xbc -> ',';
            case 0xbd -> '-';
            case 0xbe -> '.';
            case 0xbf -> '/';
            case 0xc0 -> '`';
            case 0xdb -> '[';
            case 0xdc, 0xe2 -> '\\';
            case 0xdd -> ']';
            case 0xde -> '\'';
            default -> -1;
        };
    }

    /** Bounded lookahead, replaying all input on mismatch so no unrelated key is lost. */
    private static Key win32Surrogate(Source source, Win32Record highRecord) throws InterruptedIOException {
        Key high = highRecord.key();
        var consumed = new java.util.ArrayList<Integer>();
        long started = System.nanoTime();
        try {
            while ((System.nanoTime() - started) / 1_000_000 < ESCAPE_TIMEOUT_MILLIS) {
                StringBuilder packet = new StringBuilder();
                boolean complete = false;
                while (packet.length() < 67) {
                    long remaining = Math.max(0, ESCAPE_TIMEOUT_MILLIS - (System.nanoTime() - started) / 1_000_000);
                    int value = source.read(remaining);
                    if (value < 0) break;
                    consumed.add(value);
                    packet.appendCodePoint(value);
                    int length = packet.length();
                    if ((length == 1 && value != 0x1b) || (length == 2 && value != '[')) break;
                    if (length > 2) {
                        if (value >= 0x40 && value <= 0x7e) {
                            complete = value == '_';
                            break;
                        }
                        if (value < 0x20 || value > 0x3f) break;
                    }
                }
                if (!complete) break;
                Win32Record lowRecord = win32Record(packet.substring(2, packet.length() - 1));
                Key low = lowRecord.key();
                if (low == null) continue; // Key-up/modifier records may separate the halves.
                if (low.type() == KeyType.CHARACTER && Character.isLowSurrogate((char) low.codePoint())
                        && low.modifiers() == high.modifiers()) {
                    consumed.clear();
                    // Replay each remaining half. Equal counts yield repeated code points;
                    // unequal counts leave replacement characters for the unpaired units.
                    replayRepeat(source, lowRecord);
                    replayRepeat(source, highRecord);
                    return new Key(KeyType.CHARACTER,
                            Character.toCodePoint((char) high.codePoint(), (char) low.codePoint()), high.modifiers());
                }
                break;
            }
        } finally {
            for (int index = consumed.size() - 1; index >= 0; index--) source.unread(consumed.get(index));
        }
        replayRepeat(source, highRecord);
        return new Key(KeyType.CHARACTER, 0xfffd, high.modifiers());
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
