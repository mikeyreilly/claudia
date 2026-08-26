package com.quaxt.codingagent.cli.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.quaxt.codingagent.ai.json.Json;
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
		SessionStore store = new SessionStore(tempDir.resolve("sessions"));
		SessionRecorder recorder = SessionRecorder.create(store, tempDir, "faux", "faux-1");
		AssistantMessage assistant = new AssistantMessage("faux", "faux", "faux-1");
		assistant.content.add(new TextContent("I will use a tool."));
		assistant.stopReason = StopReason.TOOL_USE;
		recorder.appendMessages(List.of(
				UserMessage.of("read file"),
				assistant,
				ToolResultMessage.text("call-1", "read", "file contents", false)));

		var entries = store.read(recorder.sessionId());
		assertEquals(4, entries.size());
		assertEquals("session_start", entries.getFirst().type());
		assertEquals("user", entries.get(1).payload().path("role").asText());
		assertEquals("assistant", entries.get(2).payload().path("role").asText());
		assertTrue(entries.get(2).payload().path("content").get(0).path("text").asText().contains("tool"));
		assertEquals("read", entries.getLast().payload().path("toolName").asText());
	}

	@Test
	void forksTheTranscriptIntoANamedSession() throws Exception {
		SessionStore store = new SessionStore(tempDir.resolve("sessions"));
		SessionRecorder source = SessionRecorder.create(store, tempDir, "faux", "faux-1");
		List<Message> messages = List.of(UserMessage.of("first prompt"), UserMessage.of("second prompt"));
		source.appendMessages(messages);

		SessionRecorder fork = SessionRecorder.fork(
				store, tempDir, "faux", "faux-1", "  investigation fork  ", messages);
		SessionSnapshot snapshot = store.snapshot(fork.sessionId());

		assertNotEquals(source.sessionId(), fork.sessionId());
		assertEquals("investigation fork", snapshot.name());
		assertEquals(2, snapshot.messageCount());
		assertEquals("first prompt", snapshot.firstMessage());
		assertEquals(2, store.snapshot(source.sessionId()).messageCount());
	}

	@Test
	void restoresTypedMessagesAndContinuesTheSameSession() throws Exception {
		SessionStore store = new SessionStore(tempDir.resolve("sessions"));
		SessionRecorder recorder = SessionRecorder.create(store, tempDir, "faux", "faux-1");
		AssistantMessage assistant = new AssistantMessage("faux-api", "faux", "faux-1");
		assistant.content.add(new ThinkingContent("reasoning", "opaque", false));
		assistant.content.add(new TextContent("answer", "text-signature"));
		assistant.content.add(new ToolCall("call-1", "read", Json.object().put("path", "README.md"), "thought"));
		assistant.stopReason = StopReason.TOOL_USE;
		assistant.usage.input = 12;
		assistant.usage.output = 7;
		UserMessage user = new UserMessage(
				List.of(new TextContent("look"), new ImageContent("aW1hZ2U=", "image/png")), 1234);
		ToolResultMessage result = new ToolResultMessage(
				"call-1",
				"read",
				List.of(new TextContent("contents")),
				Map.of("path", "README.md"),
				false,
				5678);
		recorder.appendMessages(List.of(user, assistant, result));

		SessionSnapshot snapshot = store.snapshot(recorder.sessionId());
		assertEquals(tempDir.toAbsolutePath().normalize(), snapshot.cwd());
		assertEquals("faux", snapshot.provider());
		assertEquals("faux-1", snapshot.model());
		assertEquals(3, snapshot.messageCount());
		assertEquals("look", snapshot.firstMessage());
		assertEquals(1234, snapshot.messages().getFirst().timestamp());
		AssistantMessage restored = (AssistantMessage) snapshot.messages().get(1);
		assertEquals("reasoning", restored.thinking());
		assertEquals("answer", restored.text());
		assertEquals("read", restored.toolCalls().getFirst().name());
		assertEquals(12, restored.usage.input);
		ToolResultMessage restoredResult = (ToolResultMessage) snapshot.messages().getLast();
		assertEquals("README.md", ((Map<?, ?>) restoredResult.details()).get("path"));

		SessionRecorder.resume(store, recorder.sessionId()).appendMessages(List.of(UserMessage.of("continue")));
		assertEquals(4, store.snapshot(recorder.sessionId()).messageCount());
	}
}
