package works.earendil.pi.cli.session;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import works.earendil.pi.ai.types.Message;

/** Metadata and restored conversation context for one persisted Java pi session. */
public record SessionSnapshot(
		String id,
		Path path,
		Path cwd,
		String provider,
		String model,
		Instant created,
		Instant modified,
		int messageCount,
		String firstMessage,
		String allMessagesText,
		List<Message> messages) {
	public SessionSnapshot {
		id = Objects.requireNonNull(id, "id");
		path = Objects.requireNonNull(path, "path");
		cwd = Objects.requireNonNull(cwd, "cwd");
		provider = Objects.requireNonNull(provider, "provider");
		model = Objects.requireNonNull(model, "model");
		created = Objects.requireNonNull(created, "created");
		modified = Objects.requireNonNull(modified, "modified");
		firstMessage = Objects.requireNonNull(firstMessage, "firstMessage");
		allMessagesText = Objects.requireNonNull(allMessagesText, "allMessagesText");
		messages = List.copyOf(messages);
	}
}
