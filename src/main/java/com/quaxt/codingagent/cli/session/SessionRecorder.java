package com.quaxt.codingagent.cli.session;

/**
 * Identifies the append-only JSONL session that agent messages and compaction
 * boundaries are written to. Recording lives in CodingAgentOperations.
 */
public final class SessionRecorder {
	public SessionStore store;
	public String sessionId;

	public SessionRecorder(SessionStore store, String sessionId) {
		this.store = store;
		this.sessionId = sessionId;
	}
}
