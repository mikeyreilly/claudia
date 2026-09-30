package com.quaxt.codingagent.ai.http;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IdleTimeoutInputStreamTest {

	/** Body whose reads block until a byte is offered; close wakes a blocked read with EOF. */
	private static final class BlockingBody extends InputStream {
		private static final int EOF = -1;
		final LinkedBlockingQueue<Integer> bytes = new LinkedBlockingQueue<>();
		volatile boolean closed;

		@Override
		public int read() throws IOException {
			try {
				int next = bytes.take();
				if (next == EOF) {
					bytes.offer(EOF);
				}
				return next;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException(e);
			}
		}

		@Override
		public void close() {
			closed = true;
			bytes.offer(EOF);
		}
	}

	@Test
	void timeSpentBetweenReadsDoesNotCountAsIdle() throws Exception {
		BlockingBody body = new BlockingBody();
		body.bytes.add((int) 'a');
		body.bytes.add((int) 'b');
		try (IdleTimeoutInputStream stream = new IdleTimeoutInputStream(body, 100, "example.test")) {
			assertEquals('a', stream.read());
			Thread.sleep(400);
			assertEquals('b', stream.read());
			assertFalse(body.closed);
		}
	}

	@Test
	void readsKeepWorkingWhileEachWaitIsShorterThanTheTimeout() throws Exception {
		BlockingBody body = new BlockingBody();
		Thread.ofVirtual().start(() -> {
			try {
				for (int i = 0; i < 6; i++) {
					Thread.sleep(50);
					body.bytes.add('0' + i);
				}
			} catch (InterruptedException ignored) {
			}
		});
		try (IdleTimeoutInputStream stream = new IdleTimeoutInputStream(body, 200, "example.test")) {
			StringBuilder text = new StringBuilder();
			for (int i = 0; i < 6; i++) {
				text.append((char) stream.read());
			}
			assertEquals("012345", text.toString());
		}
	}

	@Test
	void stalledReadFailsWithTimeoutAndClosesTheBody() throws Exception {
		BlockingBody body = new BlockingBody();
		try (IdleTimeoutInputStream stream = new IdleTimeoutInputStream(body, 150, "example.test")) {
			long start = System.nanoTime();
			HttpTimeoutException error = assertThrows(HttpTimeoutException.class, () -> stream.read(new byte[8]));
			long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

			assertEquals("Response from example.test timed out: no data received for 150 ms", error.getMessage());
			assertTrue(elapsedMs >= 140 && elapsedMs < 5_000, "timed out after " + elapsedMs + " ms");
			assertTrue(body.closed);
			assertThrows(HttpTimeoutException.class, stream::read);
		}
	}

	@Test
	void rejectsNonPositiveTimeouts() {
		assertThrows(IllegalArgumentException.class, () -> new IdleTimeoutInputStream(new BlockingBody(), 0, "x"));
	}
}
