package com.quaxt.codingagent.terminal;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

/**
 * The single input path for a terminal. A daemon thread decodes UTF-8 from the
 * input stream into a queue of code points; the line editor, full-screen
 * components, and interrupt listeners all consume events from that queue, so
 * switching between them cannot drop keys that are already buffered.
 */
public final class InputReader implements KeyParser.Source {
    public static final int EOF = -1;
    public static final int TIMEOUT = -2;

    private final InputStream in;
    private final LinkedBlockingDeque<Integer> queue = new LinkedBlockingDeque<>();
    private Thread pump;

    public InputReader(InputStream in) {
        this.in = in;
    }

    /**
     * Reads one code point, waiting at most {@code timeoutMillis} (a negative
     * timeout waits indefinitely). Returns {@link #TIMEOUT} or {@link #EOF};
     * end of input is sticky.
     */
    @Override
    public int read(long timeoutMillis) throws InterruptedIOException {
        start();
        Integer value;
        try {
            value = timeoutMillis < 0 ? queue.take() : queue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while reading terminal input");
        }
        if (value == null) return TIMEOUT;
        if (value == EOF) queue.offerFirst(EOF);
        return value;
    }

    /** Returns the next code point without consuming it, or {@link #TIMEOUT}/{@link #EOF}. */
    public int peek(long timeoutMillis) throws InterruptedIOException {
        int value = read(timeoutMillis);
        if (value != TIMEOUT && value != EOF) queue.offerFirst(value);
        return value;
    }

    /** Returns a code point to the front of the queue; end of input is already sticky. */
    @Override
    public void unread(int codePoint) {
        if (codePoint >= 0) queue.offerFirst(codePoint);
    }

    /** Reads and decodes one event, or returns null when the timeout expires first. */
    public TerminalEvent readEvent(long timeoutMillis) throws InterruptedIOException {
        return KeyParser.read(this, timeoutMillis);
    }

    private synchronized void start() {
        if (pump != null) return;
        pump = Thread.ofPlatform().daemon(true).name("codingagent-terminal-input").start(this::pump);
    }

    private void pump() {
        byte[] buffer = new byte[4096];
        int needed = 0;
        int codePoint = 0;
        int minimum = 0;
        try {
            while (true) {
                int count = in.read(buffer);
                if (count < 0) break;
                for (int index = 0; index < count; index++) {
                    int value = buffer[index] & 0xff;
                    if (needed > 0) {
                        if ((value & 0xc0) == 0x80) {
                            codePoint = (codePoint << 6) | (value & 0x3f);
                            if (--needed == 0) queue.offer(valid(codePoint, minimum));
                            continue;
                        }
                        // A truncated sequence: report it, then treat this byte as a new lead byte.
                        queue.offer(0xfffd);
                        needed = 0;
                    }
                    if (value < 0x80) {
                        queue.offer(value);
                    } else if ((value & 0xe0) == 0xc0) {
                        codePoint = value & 0x1f;
                        needed = 1;
                        minimum = 0x80;
                    } else if ((value & 0xf0) == 0xe0) {
                        codePoint = value & 0x0f;
                        needed = 2;
                        minimum = 0x800;
                    } else if ((value & 0xf8) == 0xf0) {
                        codePoint = value & 0x07;
                        needed = 3;
                        minimum = 0x10000;
                    } else {
                        queue.offer(0xfffd);
                    }
                }
            }
        } catch (IOException closed) {
            // A closed or failed stream ends input like EOF.
        } finally {
            if (needed > 0) queue.offer(0xfffd);
            queue.offer(EOF);
        }
    }

    private static int valid(int codePoint, int minimum) {
        boolean surrogate = codePoint >= 0xd800 && codePoint <= 0xdfff;
        return codePoint < minimum || codePoint > 0x10ffff || surrogate ? 0xfffd : codePoint;
    }
}
