package com.quaxt.codingagent.ai.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import com.quaxt.codingagent.CodingAgentOperations;

class SseReaderTest {
	private static SseReader reader(String input) {
		return CodingAgentOperations.sseReader(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
	}

	private static SseReader.SseEvent next(SseReader reader) throws Exception {
		return CodingAgentOperations.nextSseEvent(reader);
	}

	@Test
	void parsesEventAndData() throws Exception {
		SseReader r = reader("event: message_start\ndata: {\"a\":1}\n\n");
		try {
			SseReader.SseEvent event = next(r);
			assertEquals("message_start", event.event);
			assertEquals("{\"a\":1}", event.data);
			assertNull(next(r));
		} finally {
			CodingAgentOperations.INSTANCE.closeSseReader(r);
		}
	}

	@Test
	void joinsMultipleDataLines() throws Exception {
		SseReader r = reader("data: line1\ndata: line2\n\n");
		try {
			assertEquals("line1\nline2", next(r).data);
		} finally {
			CodingAgentOperations.INSTANCE.closeSseReader(r);
		}
	}

	@Test
	void ignoresCommentsAndRetry() throws Exception {
		SseReader r = reader(": keepalive\nretry: 3000\ndata: x\n\n");
		try {
			assertEquals("x", next(r).data);
		} finally {
			CodingAgentOperations.INSTANCE.closeSseReader(r);
		}
	}

	@Test
	void handlesMultipleEvents() throws Exception {
		SseReader r = reader("data: one\n\ndata: two\n\ndata: [DONE]\n\n");
		try {
			assertEquals("one", next(r).data);
			assertEquals("two", next(r).data);
			assertEquals("[DONE]", next(r).data);
			assertNull(next(r));
		} finally {
			CodingAgentOperations.INSTANCE.closeSseReader(r);
		}
	}

	@Test
	void flushesTrailingEventWithoutBlankLine() throws Exception {
		SseReader r = reader("data: tail");
		try {
			assertEquals("tail", next(r).data);
			assertNull(next(r));
		} finally {
			CodingAgentOperations.INSTANCE.closeSseReader(r);
		}
	}

	@Test
	void stripsSingleLeadingSpaceOnly() throws Exception {
		SseReader r = reader("data:  two spaces\n\n");
		try {
			assertEquals(" two spaces", next(r).data);
		} finally {
			CodingAgentOperations.INSTANCE.closeSseReader(r);
		}
	}
}
