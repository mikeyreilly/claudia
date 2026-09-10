package com.quaxt.codingagent.cli.session;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import com.quaxt.codingagent.ai.types.Message;

/** Metadata, active conversation context, and complete transcript for one persisted session. */
public final class SessionSnapshot {
    public String parentSessionId;
    public String task;
    public com.quaxt.codingagent.ai.types.ThinkingLevel thinkingLevel;
    public String lifecycle;
	public String id;
	public String name;
	public Path path;
	public Path cwd;
	public String provider;
	public String model;
	public Instant created;
	public Instant modified;
	public int messageCount;
	public String firstMessage;
	public String allMessagesText;
	/** Compaction-aware message history to use when continuing the session. */
	public List<Message> messages;
	/** Complete append-only transcript, retained for session display and search. */
	public List<Message> transcriptMessages;

	public SessionSnapshot(
			String id,
			String name,
			Path path,
			Path cwd,
			String provider,
			String model,
			Instant created,
			Instant modified,
			int messageCount,
			String firstMessage,
			String allMessagesText,
			List<Message> messages,
			List<Message> transcriptMessages) {
		this.id = id;
		this.name = name;
		this.path = path;
		this.cwd = cwd;
		this.provider = provider;
		this.model = model;
		this.created = created;
		this.modified = modified;
		this.messageCount = messageCount;
		this.firstMessage = firstMessage;
		this.allMessagesText = allMessagesText;
		this.messages = messages;
		this.transcriptMessages = transcriptMessages;
	}
}
