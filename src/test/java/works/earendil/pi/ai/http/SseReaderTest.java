package works.earendil.pi.ai.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SseReaderTest {
	private static SseReader reader(String input) {
		return new SseReader(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	void parsesEventAndData() throws Exception {
		try (SseReader r = reader("event: message_start\ndata: {\"a\":1}\n\n")) {
			SseReader.SseEvent event = r.next();
			assertEquals("message_start", event.event());
			assertEquals("{\"a\":1}", event.data());
			assertNull(r.next());
		}
	}

	@Test
	void joinsMultipleDataLines() throws Exception {
		try (SseReader r = reader("data: line1\ndata: line2\n\n")) {
			assertEquals("line1\nline2", r.next().data());
		}
	}

	@Test
	void ignoresCommentsAndRetry() throws Exception {
		try (SseReader r = reader(": keepalive\nretry: 3000\ndata: x\n\n")) {
			assertEquals("x", r.next().data());
		}
	}

	@Test
	void handlesMultipleEvents() throws Exception {
		try (SseReader r = reader("data: one\n\ndata: two\n\ndata: [DONE]\n\n")) {
			assertEquals("one", r.next().data());
			assertEquals("two", r.next().data());
			assertEquals("[DONE]", r.next().data());
			assertNull(r.next());
		}
	}

	@Test
	void flushesTrailingEventWithoutBlankLine() throws Exception {
		try (SseReader r = reader("data: tail")) {
			assertEquals("tail", r.next().data());
			assertNull(r.next());
		}
	}

	@Test
	void stripsSingleLeadingSpaceOnly() throws Exception {
		try (SseReader r = reader("data:  two spaces\n\n")) {
			assertEquals(" two spaces", r.next().data());
		}
	}
}
