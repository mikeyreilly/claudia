package com.quaxt.codingagent.cli.session;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import com.quaxt.codingagent.ai.json.Json;
import com.quaxt.codingagent.ai.types.Message;

/** Writes the agent transcript to the Java CLI's append-only JSONL session format. */
public final class SessionRecorder {
	private final SessionStore store;
	private final String sessionId;

	private SessionRecorder(SessionStore store, String sessionId) {
		this.store = store;
		this.sessionId = sessionId;
	}

	public static SessionRecorder create(SessionStore store, Path cwd, String provider, String model) throws IOException {
		String id = store.create();
		ObjectNode start = Json.object();
		start.put("cwd", cwd.toAbsolutePath().normalize().toString());
		start.put("provider", provider);
		start.put("model", model);
		store.append(id, "session_start", start);
		return new SessionRecorder(store, id);
	}

	/** Opens an existing session so future messages continue in the same JSONL file. */
	public static SessionRecorder resume(SessionStore store, String sessionId) throws IOException {
		store.snapshot(sessionId);
		return new SessionRecorder(store, sessionId);
	}

	public String sessionId() {
		return sessionId;
	}

	/** Appends finished agent messages in chronological order. */
	public void appendMessages(List<Message> messages) throws IOException {
		for (Message message : messages) {
			store.append(sessionId, "message", SessionCodec.encode(message));
		}
	}
}
