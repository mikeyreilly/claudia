package com.quaxt.codingagent.cli.session;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import com.quaxt.codingagent.agent.CompactionResult;
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
		return create(store, cwd, provider, model, null);
	}

	/** Creates a session with an optional user-visible name. */
	public static SessionRecorder create(
			SessionStore store, Path cwd, String provider, String model, String sessionName) throws IOException {
		String id = store.create();
		ObjectNode start = Json.object();
		start.put("cwd", cwd.toAbsolutePath().normalize().toString());
		start.put("provider", provider);
		start.put("model", model);
		String normalizedName = normalizeName(sessionName);
		if (normalizedName != null) start.put("name", normalizedName);
		store.append(id, "session_start", start);
		return new SessionRecorder(store, id);
	}

	/** Creates a named child session containing a copy of the supplied conversation. */
	public static SessionRecorder fork(
			SessionStore store,
			Path cwd,
			String provider,
			String model,
			String sessionName,
			List<Message> messages)
			throws IOException {
		SessionRecorder fork = create(store, cwd, provider, model, sessionName);
		fork.appendMessages(messages);
		return fork;
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

	/**
	 * Appends a compaction boundary without rewriting the prior transcript.
	 * On resume, {@link SessionStore} uses the latest boundary to rebuild the
	 * active model context from this checkpoint and later messages only.
	 */
	public void appendCompaction(CompactionResult result) throws IOException {
		Objects.requireNonNull(result, "result");
		if (result.summary() == null || result.summary().isBlank()) {
			throw new IllegalArgumentException("Compaction summary must not be blank");
		}
		ObjectNode checkpoint = Json.object();
		checkpoint.put("summary", result.summary());
		checkpoint.put("tokensBefore", result.tokensBefore());
		checkpoint.put("estimatedTokensAfter", result.estimatedTokensAfter());
		store.append(sessionId, "compaction", checkpoint);
	}

	private static String normalizeName(String sessionName) {
		if (sessionName == null) return null;
		String normalized = sessionName.strip();
		return normalized.isEmpty() ? null : normalized;
	}
}
