package com.quaxt.claudia.terminal;

/** One decoded unit of terminal input. */
public sealed interface TerminalEvent {
    int SHIFT = 1;
    int ALT = 2;
    int CTRL = 4;
    int SUPER = 8;

    enum KeyType {
        CHARACTER,
        ENTER,
        TAB,
        BACKSPACE,
        ESCAPE,
        UP,
        DOWN,
        LEFT,
        RIGHT,
        HOME,
        END,
        PAGE_UP,
        PAGE_DOWN,
        INSERT,
        DELETE,
        FUNCTION,
        UNKNOWN
    }

    /**
     * A key press. For {@link KeyType#CHARACTER} the code point is the character;
     * control combinations use the unshifted base key, so Ctrl-C is {@code 'c'}
     * with {@link #CTRL}. For {@link KeyType#FUNCTION} it is the function-key number.
     */
    record Key(KeyType type, int codePoint, int modifiers) implements TerminalEvent {
        public static Key of(KeyType type) {
            return new Key(type, 0, 0);
        }

        public static Key character(int codePoint) {
            return new Key(KeyType.CHARACTER, codePoint, 0);
        }

        public static Key ctrl(char base) {
            return new Key(KeyType.CHARACTER, base, CTRL);
        }

        public Key withModifiers(int extra) {
            return new Key(type, codePoint, modifiers | extra);
        }

        /** This key with no modifiers. */
        public boolean is(KeyType expected) {
            return type == expected && modifiers == 0;
        }

        /** Exactly Ctrl plus the given base character. */
        public boolean isCtrl(char base) {
            return type == KeyType.CHARACTER && modifiers == CTRL && codePoint == base;
        }

        /** Exactly Alt plus the given character. */
        public boolean isAlt(char base) {
            return type == KeyType.CHARACTER && modifiers == ALT && codePoint == base;
        }
    }

    /** Text delivered by bracketed paste. */
    record Paste(String text) implements TerminalEvent {}

    enum MouseAction {
        PRESS,
        RELEASE,
        DRAG,
        SCROLL_UP,
        SCROLL_DOWN
    }

    /** An SGR mouse report with one-based coordinates. */
    record Mouse(MouseAction action, int button, int x, int y) implements TerminalEvent {}

    /** The input stream closed. */
    record EndOfInput() implements TerminalEvent {}
}
