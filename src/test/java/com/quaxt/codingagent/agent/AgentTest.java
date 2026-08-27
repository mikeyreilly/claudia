package com.quaxt.codingagent.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import com.quaxt.codingagent.CodingAgentOperations;
import com.quaxt.codingagent.ai.Retry;
import com.quaxt.codingagent.ai.providers.FauxProvider;
import com.quaxt.codingagent.ai.types.AssistantMessage;
import com.quaxt.codingagent.ai.types.Message;
import com.quaxt.codingagent.ai.types.StopReason;
import com.quaxt.codingagent.ai.types.TextContent;
import com.quaxt.codingagent.ai.types.ToolResultMessage;
import com.quaxt.codingagent.ai.types.UserMessage;

class AgentTest {
	@Test
	void runsToolCallsThenContinuesUntilFinalAnswer() throws Exception {
		FauxProvider provider = CodingAgentOperations.newFauxProvider();
		ObjectNode arguments = CodingAgentOperations.jsonObject().put("path", "README.md");
		CodingAgentOperations.setFauxResponses(provider, List.of(
				new FauxProvider.ResponseStep.Message(CodingAgentOperations.fauxToolCall("read", arguments)),
				new FauxProvider.ResponseStep.Factory(request -> {
					assertEquals(3, request.context.messages.size());
					assertInstanceOf(ToolResultMessage.class, request.context.messages.getLast());
					return CodingAgentOperations.fauxText("The README was read.");
				})));
		Agent agent = CodingAgentOperations.newAgent("system", provider.models.getFirst(), provider);
		List<AgentEvent> events = new ArrayList<>();
		CodingAgentOperations.subscribe(agent, events::add);
		agent.state.tools.add(new FunctionTool(
				"read",
				"Read a file",
				CodingAgentOperations.jsonObject().put("type", "object"),
				invocation -> {
					assertEquals("README.md", invocation.arguments.path("path").asText());
					invocation.onUpdate.accept(CodingAgentOperations.toolResultText("partial"));
					return CodingAgentOperations.toolResultText("contents");
				}));

		List<Message> created = CodingAgentOperations.prompt(agent, "Please read the README");

		assertEquals(4, created.size());
		assertEquals("The README was read.", agent.state.messages.getLast() instanceof com.quaxt.codingagent.ai.types.AssistantMessage a
				? CodingAgentOperations.text(a)
				: "");
		assertTrue(events.stream().anyMatch(AgentEvent.ToolExecutionStart.class::isInstance));
		assertTrue(events.stream().anyMatch(AgentEvent.ToolExecutionUpdate.class::isInstance));
		assertTrue(events.stream().anyMatch(AgentEvent.ToolExecutionEnd.class::isInstance));
		assertInstanceOf(AgentEvent.AgentEnd.class, events.getLast());
	}

	@Test
	void retriesATransientFailureWithoutRepeatingCompletedTools() throws Exception {
		FauxProvider provider = CodingAgentOperations.newFauxProvider();
		AssistantMessage transientFailure = new AssistantMessage("faux", "faux", "faux-1");
		transientFailure.stopReason = StopReason.ERROR;
		transientFailure.errorMessage =
				"503: upstream connect error or disconnect/reset before headers. reset reason: connection termination";
		CodingAgentOperations.setFauxResponses(provider, List.of(
				new FauxProvider.ResponseStep.Message(CodingAgentOperations.fauxToolCall("read", CodingAgentOperations.jsonObject().put("path", "README.md"))),
				new FauxProvider.ResponseStep.Message(transientFailure),
				new FauxProvider.ResponseStep.Factory(request -> {
					assertEquals(3, request.context.messages.size());
					assertInstanceOf(ToolResultMessage.class, request.context.messages.getLast());
					return CodingAgentOperations.fauxText("Recovered and finished.");
				})));
		Agent agent = CodingAgentOperations.newAgent("", provider.models.getFirst(), provider);
		agent.retryPolicy = new Retry.Policy(true, 2, 0);
		AtomicInteger toolCalls = new AtomicInteger();
		agent.state.tools.add(new FunctionTool(
				"read",
				"Read a file",
				CodingAgentOperations.jsonObject().put("type", "object"),
				invocation -> {
					toolCalls.incrementAndGet();
					return CodingAgentOperations.toolResultText("contents");
				}));
		List<AgentEvent> events = new ArrayList<>();
		CodingAgentOperations.subscribe(agent, events::add);

		List<Message> created = CodingAgentOperations.prompt(agent, "Finish the task");

		assertEquals(3, CodingAgentOperations.fauxCallCount(provider));
		assertEquals(1, toolCalls.get());
		assertEquals(4, created.size());
		assertEquals(4, agent.state.messages.size());
		assertEquals("Recovered and finished.", CodingAgentOperations.text(((AssistantMessage) agent.state.messages.getLast())));
		AgentEvent.AutoRetryStart retry = assertInstanceOf(
				AgentEvent.AutoRetryStart.class,
				events.stream().filter(AgentEvent.AutoRetryStart.class::isInstance).findFirst().orElseThrow());
		assertEquals(1, retry.attempt);
		assertEquals(2, retry.maxAttempts);
		assertTrue(events.stream()
				.filter(AgentEvent.AutoRetryEnd.class::isInstance)
				.map(AgentEvent.AutoRetryEnd.class::cast)
				.anyMatch(end -> end.success));
	}

	@Test
	void reportsUnknownToolWithoutThrowing() throws Exception {
		FauxProvider provider = CodingAgentOperations.newFauxProvider();
		CodingAgentOperations.setFauxResponses(provider, List.of(
				new FauxProvider.ResponseStep.Message(CodingAgentOperations.fauxToolCall("missing", CodingAgentOperations.jsonObject())),
				new FauxProvider.ResponseStep.Message(CodingAgentOperations.fauxText("recovered"))));
		Agent agent = CodingAgentOperations.newAgent("", provider.models.getFirst(), provider);

		CodingAgentOperations.prompt(agent, "run missing tool");

		ToolResultMessage result = assertInstanceOf(
				ToolResultMessage.class,
				agent.state.messages.stream().filter(ToolResultMessage.class::isInstance).findFirst().orElseThrow());
		assertTrue(result.isError);
		assertEquals("Unknown tool: missing", ((TextContent) result.content.getFirst()).text);
	}

	@Test
	void compactsActiveContextIntoASummaryCheckpoint() throws Exception {
		FauxProvider provider = CodingAgentOperations.newFauxProvider();
		CodingAgentOperations.setFauxResponses(provider, List.of(
				new FauxProvider.ResponseStep.Message(CodingAgentOperations.fauxText("Initial response.")),
				new FauxProvider.ResponseStep.Message(CodingAgentOperations.fauxText("## Goal\nPreserve the important context."))));
		Agent agent = CodingAgentOperations.newAgent("system", provider.models.getFirst(), provider);
		CodingAgentOperations.prompt(agent, "Explain the current project. ".repeat(100));

		CompactionResult result = CodingAgentOperations.compact(agent, "Preserve the goal");

		assertEquals(2, CodingAgentOperations.fauxCallCount(provider));
		assertEquals("## Goal\nPreserve the important context.", result.summary);
		assertEquals(1, agent.state.messages.size());
		UserMessage checkpoint = assertInstanceOf(UserMessage.class, agent.state.messages.getFirst());
		assertTrue(CodingAgentOperations.text(checkpoint).startsWith("[Conversation checkpoint]\n## Goal"));
		assertTrue(result.estimatedTokensAfter < result.tokensBefore);
	}

	@Test
	void rejectsPromptsTriggeredWhileCompactionIsInProgress() throws Exception {
		FauxProvider provider = CodingAgentOperations.newFauxProvider();
		CodingAgentOperations.setFauxResponses(provider, List.of(
				new FauxProvider.ResponseStep.Message(CodingAgentOperations.fauxText("Initial response.")),
				new FauxProvider.ResponseStep.Message(CodingAgentOperations.fauxText("Checkpoint summary."))));
		Agent agent = CodingAgentOperations.newAgent("system", provider.models.getFirst(), provider);
		CodingAgentOperations.prompt(agent, "initial prompt");
		AtomicReference<IllegalStateException> rejection = new AtomicReference<>();
		CodingAgentOperations.subscribe(agent, event -> {
			if (event instanceof AgentEvent.CompactionStart) {
				try {
					CodingAgentOperations.prompt(agent, "queued during compaction");
				} catch (IllegalStateException error) {
					rejection.set(error);
				} catch (InterruptedException error) {
					Thread.currentThread().interrupt();
					throw new AssertionError(error);
				}
			}
		});

		CodingAgentOperations.compact(agent, null);

		assertNotNull(rejection.get());
		assertEquals(2, CodingAgentOperations.fauxCallCount(provider));
	}

	@Test
	void sendsOnlyTheCheckpointAndNewPromptAfterManualCompaction() throws Exception {
		FauxProvider provider = CodingAgentOperations.newFauxProvider();
		AtomicReference<List<Message>> followUpRequest = new AtomicReference<>();
		CodingAgentOperations.setFauxResponses(provider, List.of(
				new FauxProvider.ResponseStep.Message(CodingAgentOperations.fauxText("Initial response.")),
				new FauxProvider.ResponseStep.Message(CodingAgentOperations.fauxText("Checkpoint summary.")),
				new FauxProvider.ResponseStep.Factory(request -> {
					followUpRequest.set(List.copyOf(request.context.messages));
					return CodingAgentOperations.fauxText("Follow-up response.");
				})));
		Agent agent = CodingAgentOperations.newAgent("system", provider.models.getFirst(), provider);

		CodingAgentOperations.prompt(agent, "PRE-COMPACTION-SENTINEL");
		CodingAgentOperations.compact(agent, null);
		CodingAgentOperations.prompt(agent, "POST-COMPACTION-SENTINEL");

		List<Message> messages = followUpRequest.get();
		assertEquals(2, messages.size());
		UserMessage checkpoint = assertInstanceOf(UserMessage.class, messages.getFirst());
		assertEquals("[Conversation checkpoint]\nCheckpoint summary.", CodingAgentOperations.text(checkpoint));
		UserMessage prompt = assertInstanceOf(UserMessage.class, messages.getLast());
		assertEquals("POST-COMPACTION-SENTINEL", CodingAgentOperations.text(prompt));
		assertFalse(messages.stream()
				.filter(UserMessage.class::isInstance)
				.map(UserMessage.class::cast)
				.map(CodingAgentOperations::text)
				.anyMatch(text -> text.contains("PRE-COMPACTION-SENTINEL")));
	}
}
