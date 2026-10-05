package com.quaxt.claudia.ai.http;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeUnit;

/**
 * Response body that fails a read once it has waited the idle timeout without
 * receiving any bytes. Time the consumer spends between reads never counts.
 *
 * <p>JDK 26 extended {@code HttpRequest.Builder.timeout} to cover consuming the
 * whole response body (JDK-8208693), which cut off long but still-active model
 * streams. The HTTP layer therefore bounds only the wait for response headers
 * and relies on this watchdog to detect stalled bodies.
 */
public final class IdleTimeoutInputStream extends FilterInputStream {
	private final long timeoutMs;
	private final String source;
	private final Thread watchdog;
	private volatile long readStartedNanos;
	private volatile boolean reading;
	private volatile boolean timedOut;
	private volatile boolean stopped;

	/**
	 * @param in the response body to guard
	 * @param timeoutMs how long a single read may wait for data; must be positive
	 * @param source host named in the timeout message
	 */
	public IdleTimeoutInputStream(InputStream in, long timeoutMs, String source) {
		super(in);
		if (timeoutMs <= 0) {
			throw new IllegalArgumentException("timeoutMs must be positive: " + timeoutMs);
		}
		this.timeoutMs = timeoutMs;
		this.source = source;
		this.watchdog = Thread.ofVirtual().name("http-idle-timeout").start(this::watch);
	}

	@Override
	public int read() throws IOException {
		beginRead();
		try {
			return endRead(in.read());
		} catch (IOException error) {
			throw translate(error);
		} finally {
			reading = false;
		}
	}

	@Override
	public int read(byte[] buffer, int offset, int length) throws IOException {
		beginRead();
		try {
			return endRead(in.read(buffer, offset, length));
		} catch (IOException error) {
			throw translate(error);
		} finally {
			reading = false;
		}
	}

	@Override
	public long skip(long count) throws IOException {
		beginRead();
		try {
			return in.skip(count);
		} catch (IOException error) {
			throw translate(error);
		} finally {
			reading = false;
		}
	}

	@Override
	public void close() throws IOException {
		stopWatchdog();
		in.close();
	}

	private void beginRead() throws HttpTimeoutException {
		if (timedOut) {
			throw timeoutException();
		}
		readStartedNanos = System.nanoTime();
		reading = true;
	}

	private int endRead(int result) throws HttpTimeoutException {
		if (result < 0) {
			if (timedOut) {
				throw timeoutException();
			}
			stopWatchdog();
		}
		return result;
	}

	private IOException translate(IOException error) {
		// After a timeout the failure is the watchdog's own close of the body.
		return timedOut ? timeoutException() : error;
	}

	private HttpTimeoutException timeoutException() {
		return new HttpTimeoutException(
				"Response from " + source + " timed out: no data received for " + timeoutMs + " ms");
	}

	private void stopWatchdog() {
		if (!stopped) {
			stopped = true;
			watchdog.interrupt();
		}
	}

	private void watch() {
		long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs);
		try {
			while (!stopped) {
				long remaining = timeoutNanos;
				if (reading) {
					remaining -= System.nanoTime() - readStartedNanos;
					if (remaining <= 0) {
						timedOut = true;
						// Closing the body wakes the blocked reader, which then
						// reports the timeout instead of the close.
						in.close();
						return;
					}
				}
				TimeUnit.NANOSECONDS.sleep(remaining);
			}
		} catch (InterruptedException ignored) {
			// The body was closed or fully read.
		} catch (IOException ignored) {
			// The reader still observes timedOut on its next read.
		}
	}
}
