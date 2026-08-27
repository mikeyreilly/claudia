package com.quaxt.codingagent.cli.session;

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
import com.quaxt.codingagent.CodingAgentOperations;

class SessionStoreTest {
	@TempDir Path tempDir;

	@Test
	void appendsAndReadsTypedJsonlEntries() throws Exception {
		SessionStore store = CodingAgentOperations.sessionStore(tempDir.resolve("sessions"));
		String id = CodingAgentOperations.createSession(store);

		CodingAgentOperations.appendSessionEntry(store, id, "session_start", CodingAgentOperations.jsonObject().put("cwd", "/repo"));
		CodingAgentOperations.appendSessionEntry(store, id, "message", CodingAgentOperations.jsonObject().put("role", "user").put("text", "hello"));

		var entries = CodingAgentOperations.readSession(store, id);
		assertEquals(2, entries.size());
		assertEquals("session_start", entries.getFirst().type);
		assertEquals("/repo", entries.getFirst().payload.path("cwd").asText());
		assertEquals("hello", entries.getLast().payload.path("text").asText());
		assertEquals(List.of(id), CodingAgentOperations.listSessions(store));
	}

	@Test
	void rejectsTraversalAndMissingSessions() throws Exception {
		SessionStore store = CodingAgentOperations.sessionStore(tempDir);
		assertThrows(IllegalArgumentException.class, () -> CodingAgentOperations.readSession(store, "../session"));
		assertThrows(IllegalArgumentException.class, () -> CodingAgentOperations.appendSessionEntry(store, "../session", "x", CodingAgentOperations.jsonObject()));
		assertThrows(java.io.IOException.class, () -> CodingAgentOperations.readSession(store, "00000000-0000-7000-8000-000000000000"));
	}

	@Test
	void idsAreTimeSortable() throws Exception {
		SessionStore store = CodingAgentOperations.sessionStore(tempDir);
		String first = CodingAgentOperations.createSession(store);
		String second = CodingAgentOperations.createSession(store);
		assertTrue(second.compareTo(first) > 0);
		assertEquals(List.of(second, first), CodingAgentOperations.listSessions(store));
	}

	@Test
	void defaultStoreContinuesToDiscoverAndResumeLegacySessions() throws Exception {
		Path cwd = tempDir.resolve("workspace");
		Files.createDirectories(cwd);
		SessionStore legacy = CodingAgentOperations.sessionStore(tempDir.resolve(".pi-java").resolve("sessions"));
		SessionRecorder oldRecorder = CodingAgentOperations.createSessionRecorder(legacy, cwd, "faux", "legacy");
		CodingAgentOperations.appendSessionMessages(oldRecorder, List.of(CodingAgentOperations.userMessage("saved before rename")));

		SessionStore store = CodingAgentOperations.defaultSessionStore(tempDir);
		var snapshots = CodingAgentOperations.listSessionSnapshots(store, cwd);

		assertEquals(1, snapshots.size());
		assertEquals(oldRecorder.sessionId, snapshots.getFirst().id);
		assertEquals("saved before rename", snapshots.getFirst().firstMessage);
		CodingAgentOperations.appendSessionMessages(
				CodingAgentOperations.resumeSessionRecorder(store, oldRecorder.sessionId),
				List.of(CodingAgentOperations.userMessage("continued after rename")));
		assertEquals(2, CodingAgentOperations.sessionSnapshot(store, oldRecorder.sessionId).messageCount);

		String newId = CodingAgentOperations.createSession(store);
		assertTrue(Files.isRegularFile(tempDir.resolve(".codingagent").resolve("sessions").resolve(newId + ".jsonl")));
	}

	@Test
	void listsSnapshotsForTheCurrentFolderAndSkipsCorruptSessions() throws Exception {
		SessionStore store = CodingAgentOperations.sessionStore(tempDir.resolve("sessions"));
		Path firstCwd = tempDir.resolve("first");
		Path otherCwd = tempDir.resolve("other");
		Files.createDirectories(firstCwd);
		Files.createDirectories(otherCwd);
		SessionRecorder first = CodingAgentOperations.createSessionRecorder(store, firstCwd, "faux", "one");
		CodingAgentOperations.appendSessionMessages(first, List.of(CodingAgentOperations.userMessage("first prompt")));
		CodingAgentOperations.appendSessionMessages(
				CodingAgentOperations.createSessionRecorder(store, otherCwd, "faux", "two"),
				List.of(CodingAgentOperations.userMessage("other prompt")));
		String legacy = CodingAgentOperations.createSession(store);
		CodingAgentOperations.appendSessionEntry(store, legacy, "session_start", CodingAgentOperations.jsonObject()
				.put("cwd", firstCwd.toAbsolutePath().normalize().toString())
				.put("provider", "faux")
				.put("model", "legacy"));
		ObjectNode legacyMessage = CodingAgentOperations.jsonObject()
				.put("role", "assistant")
				.put("timestamp", 123)
				.put("api", "faux")
				.put("provider", "faux")
				.put("model", "legacy")
				.put("stopReason", "STOP");
		legacyMessage.putArray("content").addObject().put("type", "text").put("text", "legacy answer");
		CodingAgentOperations.appendSessionEntry(store, legacy, "message", legacyMessage);
		String corrupt = CodingAgentOperations.createSession(store);
		Files.writeString(
				tempDir.resolve("sessions").resolve(corrupt + ".jsonl"),
				"not-json\n",
				StandardOpenOption.APPEND);

		var snapshots = CodingAgentOperations.listSessionSnapshots(store, firstCwd);

		assertEquals(2, snapshots.size());
		assertEquals(
				"first prompt",
				snapshots.stream().filter(snapshot -> snapshot.id.equals(first.sessionId)).findFirst().orElseThrow().firstMessage);
		assertTrue(snapshots.stream().anyMatch(snapshot -> snapshot.id.equals(legacy)));
	}
}
