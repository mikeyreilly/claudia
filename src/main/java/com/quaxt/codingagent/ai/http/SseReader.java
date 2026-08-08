package com.quaxt.codingagent.ai.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Server-sent events reader. Parses `event:`/`data:`/`id:` fields and
 * dispatches on blank lines per the SSE spec. Multiple data lines are joined
 * with newlines. Comment lines (leading ':') are ignored.
 */
public final class SseReader implements AutoCloseable {
	private final BufferedReader reader;

	public SseReader(InputStream stream) {
		this.reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
	}

	public record SseEvent(String event, String data, String id) {}

	/** Returns the next event, or null at end of stream. */
	public SseEvent next() throws IOException {
		String event = null;
		StringBuilder data = null;
		String id = null;
		String line;
		while ((line = reader.readLine()) != null) {
			if (line.isEmpty()) {
				if (data != null || event != null || id != null) {
					return new SseEvent(event, data != null ? data.toString() : "", id);
				}
				continue;
			}
			if (line.startsWith(":")) {
				continue;
			}
			int colon = line.indexOf(':');
			String field = colon == -1 ? line : line.substring(0, colon);
			String value = colon == -1 ? "" : line.substring(colon + 1);
			if (value.startsWith(" ")) {
				value = value.substring(1);
			}
			switch (field) {
				case "event" -> event = value;
				case "data" -> {
					if (data == null) {
						data = new StringBuilder(value);
					} else {
						data.append('\n').append(value);
					}
				}
				case "id" -> id = value;
				default -> {
					// ignore unknown fields (incl. "retry")
				}
			}
		}
		if (data != null || event != null || id != null) {
			return new SseEvent(event, data != null ? data.toString() : "", id);
		}
		return null;
	}

	@Override
	public void close() throws IOException {
		reader.close();
	}
}
