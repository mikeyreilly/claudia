package com.quaxt.codingagent.cli.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.quaxt.codingagent.CodingAgentOperations;
import com.quaxt.codingagent.agent.CompactionResult;
import com.quaxt.codingagent.ai.types.AssistantMessage;
import com.quaxt.codingagent.ai.types.ImageContent;
import com.quaxt.codingagent.ai.types.Message;
import com.quaxt.codingagent.ai.types.StopReason;
import com.quaxt.codingagent.ai.types.TextContent;
import com.quaxt.codingagent.ai.types.ThinkingContent;
import com.quaxt.codingagent.ai.types.ToolCall;
import com.quaxt.codingagent.ai.types.ToolResultMessage;
import com.quaxt.codingagent.ai.types.UserMessage;

class SessionRecorderTest {
	@TempDir Path tempDir;

	@Test
	void recordsACompleteAgentTranscript() throws Exception {
		SessionStore store = CodingAgentOperations.sessionStore(tempDir.resolve("sessions"));
		SessionRecorder recorder = CodingAgentOperations.createSessionRecorder(store, tempDir, "faux", "faux-1");
		AssistantMessage assistant = new AssistantMessage("faux", "faux", "faux-1");
		assistant.content.add(CodingAgentOperations.textContent("I will use a tool."));
		assistant.stopReason = StopReason.TOOL_USE;
		CodingAgentOperations.appendSessionMessages(recorder, List.of(
				CodingAgentOperations.userMessage("read file"),
				assistant,
				CodingAgentOperations.toolResultMessage("call-1", "read", "file contents", false)));

		var entries = CodingAgentOperations.readSession(store, recorder.sessionId);
		assertEquals(4, entries.size());
		assertEquals("session_start", entries.getFirst().type);
		assertEquals("user", entries.get(1).payload.path("role").asText());
		assertEquals("assistant", entries.get(2).payload.path("role").asText());
		assertTrue(entries.get(2).payload.path("content").get(0).path("text").asText().contains("tool"));
		assertEquals("read", entries.getLast().payload.path("toolName").asText());
	}

	@Test
	void forksTheTranscriptIntoANamedSession() throws Exception {
		SessionStore store = CodingAgentOperations.sessionStore(tempDir.resolve("sessions"));
		SessionRecorder source = CodingAgentOperations.createSessionRecorder(store, tempDir, "faux", "faux-1");
		List<Message> messages = List.of(CodingAgentOperations.userMessage("first prompt"), CodingAgentOperations.userMessage("second prompt"));
		CodingAgentOperations.appendSessionMessages(source, messages);

		SessionRecorder fork = CodingAgentOperations.forkSessionRecorder(
				store, tempDir, "faux", "faux-1", "  investigation fork  ", messages);
		SessionSnapshot snapshot = CodingAgentOperations.sessionSnapshot(store, fork.sessionId);

		assertNotEquals(source.sessionId, fork.sessionId);
		assertEquals("investigation fork", snapshot.name);
		assertEquals(2, snapshot.messageCount);
		assertEquals("first prompt", snapshot.firstMessage);
		assertEquals(2, CodingAgentOperations.sessionSnapshot(store, source.sessionId).messageCount);
	}

	@Test
	void restoresCompactedSessionsUsingOnlyTheCheckpointAndLaterMessagesAsContext() throws Exception {
		SessionStore store = CodingAgentOperations.sessionStore(tempDir.resolve("sessions"));
		SessionRecorder recorder = CodingAgentOperations.createSessionRecorder(store, tempDir, "faux", "faux-1");
		CodingAgentOperations.appendSessionMessages(recorder, List.of(
				CodingAgentOperations.userMessage("PRE-COMPACTION-SENTINEL"),
				CodingAgentOperations.userMessage("another message to compact")));
		CodingAgentOperations.appendSessionCompaction(recorder, new CompactionResult("Saved checkpoint.", 123, 12));
		CodingAgentOperations.appendSessionMessages(recorder, List.of(CodingAgentOperations.userMessage("POST-COMPACTION-SENTINEL")));

		SessionSnapshot snapshot = CodingAgentOperations.sessionSnapshot(store, recorder.sessionId);

		// The append-only transcript remains available to render or inspect.
		assertEquals(3, snapshot.messageCount);
		assertEquals(3, snapshot.transcriptMessages.size());
		assertEquals("PRE-COMPACTION-SENTINEL", CodingAgentOperations.text(((UserMessage) snapshot.transcriptMessages.getFirst())));
		assertEquals("compaction", CodingAgentOperations.readSession(store, recorder.sessionId).get(3).type);

		// Resuming must use the compaction-aware projection, not the old transcript.
		assertEquals(2, snapshot.messages.size());
		UserMessage checkpoint = (UserMessage) snapshot.messages.getFirst();
		assertEquals("[Conversation checkpoint]\nSaved checkpoint.", CodingAgentOperations.text(checkpoint));
		UserMessage later = (UserMessage) snapshot.messages.getLast();
		assertEquals("POST-COMPACTION-SENTINEL", CodingAgentOperations.text(later));
		assertTrue(snapshot.messages.stream()
				.noneMatch(message -> message instanceof UserMessage user
						&& CodingAgentOperations.text(user).contains("PRE-COMPACTION-SENTINEL")));
	}

	@Test
	void usesTheLatestCompactionBoundaryWhenASessionIsCompactedAgain() throws Exception {
		SessionStore store = CodingAgentOperations.sessionStore(tempDir.resolve("sessions"));
		SessionRecorder recorder = CodingAgentOperations.createSessionRecorder(store, tempDir, "faux", "faux-1");
		CodingAgentOperations.appendSessionMessages(recorder, List.of(CodingAgentOperations.userMessage("first history")));
		CodingAgentOperations.appendSessionCompaction(recorder, new CompactionResult("first checkpoint", 100, 10));
		CodingAgentOperations.appendSessionMessages(recorder, List.of(CodingAgentOperations.userMessage("between compactions")));
		CodingAgentOperations.appendSessionCompaction(recorder, new CompactionResult("second checkpoint", 100, 10));
		CodingAgentOperations.appendSessionMessages(recorder, List.of(CodingAgentOperations.userMessage("after latest compaction")));

		SessionSnapshot snapshot = CodingAgentOperations.sessionSnapshot(store, recorder.sessionId);

		assertEquals(2, snapshot.messages.size());
		assertEquals("[Conversation checkpoint]\nsecond checkpoint", CodingAgentOperations.text(((UserMessage) snapshot.messages.getFirst())));
		assertEquals("after latest compaction", CodingAgentOperations.text(((UserMessage) snapshot.messages.getLast())));
	}

	@Test
	void restoresTypedMessagesAndContinuesTheSameSession() throws Exception {
		SessionStore store = CodingAgentOperations.sessionStore(tempDir.resolve("sessions"));
		SessionRecorder recorder = CodingAgentOperations.createSessionRecorder(store, tempDir, "faux", "faux-1");
		AssistantMessage assistant = new AssistantMessage("faux-api", "faux", "faux-1");
		assistant.content.add(CodingAgentOperations.thinkingContent("reasoning", "opaque", false));
		assistant.content.add(CodingAgentOperations.textContent("answer", "text-signature"));
		assistant.content.add(CodingAgentOperations.toolCall("call-1", "read", CodingAgentOperations.jsonObject().put("path", "README.md"), "thought"));
		assistant.stopReason = StopReason.TOOL_USE;
		assistant.usage.input = 12;
		assistant.usage.output = 7;
		UserMessage user = CodingAgentOperations.userMessage(
				List.of(CodingAgentOperations.textContent("look"), CodingAgentOperations.imageContent("aW1hZ2U=", "image/png")), 1234);
		ToolResultMessage result = CodingAgentOperations.toolResultMessage(
				"call-1",
				"read",
				List.of(CodingAgentOperations.textContent("contents")),
				Map.of("path", "README.md"),
				false,
				5678);
		CodingAgentOperations.appendSessionMessages(recorder, List.of(user, assistant, result));

		SessionSnapshot snapshot = CodingAgentOperations.sessionSnapshot(store, recorder.sessionId);
		assertEquals(tempDir.toAbsolutePath().normalize(), snapshot.cwd);
		assertEquals("faux", snapshot.provider);
		assertEquals("faux-1", snapshot.model);
		assertEquals(3, snapshot.messageCount);
		assertEquals("look", snapshot.firstMessage);
		assertEquals(1234, CodingAgentOperations.timestamp(snapshot.messages.getFirst()));
		AssistantMessage restored = (AssistantMessage) snapshot.messages.get(1);
		assertEquals("reasoning", CodingAgentOperations.thinking(restored));
		assertEquals("answer", CodingAgentOperations.text(restored));
		assertEquals("read", CodingAgentOperations.toolCalls(restored).getFirst().name);
		assertEquals(12, restored.usage.input);
		ToolResultMessage restoredResult = (ToolResultMessage) snapshot.messages.getLast();
		assertEquals("README.md", ((Map<?, ?>) restoredResult.details).get("path"));

		CodingAgentOperations.appendSessionMessages(
				CodingAgentOperations.resumeSessionRecorder(store, recorder.sessionId),
				List.of(CodingAgentOperations.userMessage("continue")));
		assertEquals(4, CodingAgentOperations.sessionSnapshot(store, recorder.sessionId).messageCount);
	}
}
