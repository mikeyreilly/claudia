package com.quaxt.codingagent.ai.http;

import java.io.BufferedReader;
import java.util.Objects;

/**
 * Server-sent events reader state. The parsing behavior (`event:`/`data:`/`id:`
 * fields dispatched on blank lines per the SSE spec) lives in
 * CodingAgentOperations.
 */
public final class SseReader {
	public BufferedReader reader;

	public SseReader(BufferedReader reader) {
		this.reader = reader;
	}

	/** One dispatched server-sent event. */
	public static final class SseEvent {
		public String event;
		public String data;
		public String id;

		public SseEvent(String event, String data, String id) {
			this.event = event;
			this.data = data;
			this.id = id;
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof SseEvent that
					&& Objects.equals(event, that.event)
					&& Objects.equals(data, that.data)
					&& Objects.equals(id, that.id);
		}

		@Override
		public int hashCode() {
			return Objects.hash(event, data, id);
		}

		@Override
		public String toString() {
			return "SseEvent[event=" + event + ", data=" + data + ", id=" + id + "]";
		}
	}
}
