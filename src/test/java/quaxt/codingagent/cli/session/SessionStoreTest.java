package works.earendil.pi.cli.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import works.earendil.pi.ai.json.Json;
import works.earendil.pi.ai.types.UserMessage;

class SessionStoreTest {
	@TempDir Path tempDir;

	@Test
	void appendsAndReadsTypedJsonlEntries() throws Exception {
		SessionStore store = new SessionStore(tempDir.resolve("sessions"));
		String id = store.create();

		store.append(id, "session_start", Json.object().put("cwd", "/repo"));
		store.append(id, "message", Json.object().put("role", "user").put("text", "hello"));

		var entries = store.read(id);
		assertEquals(2, entries.size());
		assertEquals("session_start", entries.getFirst().type());
		assertEquals("/repo", entries.getFirst().payload().path("cwd").asText());
		assertEquals("hello", entries.getLast().payload().path("text").asText());
		assertEquals(List.of(id), store.list());
	}

	@Test
	void rejectsTraversalAndMissingSessions() throws Exception {
		SessionStore store = new SessionStore(tempDir);
		assertThrows(IllegalArgumentException.class, () -> store.read("../session"));
		assertThrows(IllegalArgumentException.class, () -> store.append("../session", "x", Json.object()));
		assertThrows(java.io.IOException.class, () -> store.read("00000000-0000-7000-8000-000000000000"));
	}

	@Test
	void idsAreTimeSortable() throws Exception {
		SessionStore store = new SessionStore(tempDir);
		String first = store.create();
		String second = store.create();
		assertTrue(second.compareTo(first) > 0);
		assertEquals(List.of(second, first), store.list());
	}

	@Test
	void listsSnapshotsForTheCurrentFolderAndSkipsCorruptSessions() throws Exception {
		SessionStore store = new SessionStore(tempDir.resolve("sessions"));
		Path firstCwd = tempDir.resolve("first");
		Path otherCwd = tempDir.resolve("other");
		Files.createDirectories(firstCwd);
		Files.createDirectories(otherCwd);
		SessionRecorder first = SessionRecorder.create(store, firstCwd, "faux", "one");
		first.appendMessages(List.of(UserMessage.of("first prompt")));
		SessionRecorder.create(store, otherCwd, "faux", "two")
				.appendMessages(List.of(UserMessage.of("other prompt")));
		String legacy = store.create();
		store.append(legacy, "session_start", Json.object()
				.put("cwd", firstCwd.toAbsolutePath().normalize().toString())
				.put("provider", "faux")
				.put("model", "legacy"));
		ObjectNode legacyMessage = Json.object()
				.put("role", "assistant")
				.put("timestamp", 123)
				.put("api", "faux")
				.put("provider", "faux")
				.put("model", "legacy")
				.put("stopReason", "STOP");
		legacyMessage.putArray("content").addObject().put("type", "text").put("text", "legacy answer");
		store.append(legacy, "message", legacyMessage);
		String corrupt = store.create();
		Files.writeString(
				tempDir.resolve("sessions").resolve(corrupt + ".jsonl"),
				"not-json\n",
				StandardOpenOption.APPEND);

		var snapshots = store.listSnapshots(firstCwd);

		assertEquals(2, snapshots.size());
		assertEquals(
				"first prompt",
				snapshots.stream().filter(snapshot -> snapshot.id().equals(first.sessionId())).findFirst().orElseThrow().firstMessage());
		assertTrue(snapshots.stream().anyMatch(snapshot -> snapshot.id().equals(legacy)));
	}
}
