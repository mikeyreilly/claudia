package com.quaxt.codingagent.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.quaxt.codingagent.ai.json.Json;
import com.quaxt.codingagent.ai.providers.FauxProvider;
import com.quaxt.codingagent.ai.types.Message;
import com.quaxt.codingagent.ai.types.TextContent;
import com.quaxt.codingagent.ai.types.ToolResultMessage;
import com.quaxt.codingagent.ai.types.UserMessage;

class AgentTest {
	@Test
	void runsToolCallsThenContinuesUntilFinalAnswer() throws Exception {
		FauxProvider provider = new FauxProvider();
		ObjectNode arguments = Json.object().put("path", "README.md");
		provider.setResponses(List.of(
				new FauxProvider.ResponseStep.Message(FauxProvider.toolCall("read", arguments)),
				new FauxProvider.ResponseStep.Factory(request -> {
					assertEquals(3, request.context().messages.size());
					assertInstanceOf(ToolResultMessage.class, request.context().messages.getLast());
					return FauxProvider.text("The README was read.");
				})));
		Agent agent = new Agent("system", provider.models().getFirst(), provider::stream);
		List<AgentEvent> events = new ArrayList<>();
		agent.subscribe(events::add);
		agent.state().tools.add(new AgentTool() {
			@Override
			public String name() {
				return "read";
			}

			@Override
			public String description() {
				return "Read a file";
			}

			@Override
			public ObjectNode parameters() {
				return Json.object().put("type", "object");
			}

			@Override
			public ToolResult execute(String toolCallId, ObjectNode args, com.quaxt.codingagent.ai.util.AbortSignal signal,
					java.util.function.Consumer<ToolResult> onUpdate) {
				assertEquals("README.md", args.path("path").asText());
				onUpdate.accept(ToolResult.text("partial"));
				return ToolResult.text("contents");
			}
		});

		List<Message> created = agent.prompt("Please read the README");

		assertEquals(4, created.size());
		assertEquals("The README was read.", agent.state().messages.getLast() instanceof com.quaxt.codingagent.ai.types.AssistantMessage a
				? a.text()
				: "");
		assertTrue(events.stream().anyMatch(AgentEvent.ToolExecutionStart.class::isInstance));
		assertTrue(events.stream().anyMatch(AgentEvent.ToolExecutionUpdate.class::isInstance));
		assertTrue(events.stream().anyMatch(AgentEvent.ToolExecutionEnd.class::isInstance));
		assertInstanceOf(AgentEvent.AgentEnd.class, events.getLast());
	}

	@Test
	void reportsUnknownToolWithoutThrowing() throws Exception {
		FauxProvider provider = new FauxProvider();
		provider.setResponses(List.of(
				new FauxProvider.ResponseStep.Message(FauxProvider.toolCall("missing", Json.object())),
				new FauxProvider.ResponseStep.Message(FauxProvider.text("recovered"))));
		Agent agent = new Agent("", provider.models().getFirst(), provider::stream);

		agent.prompt("run missing tool");

		ToolResultMessage result = assertInstanceOf(
				ToolResultMessage.class,
				agent.state().messages.stream().filter(ToolResultMessage.class::isInstance).findFirst().orElseThrow());
		assertTrue(result.isError());
		assertEquals("Unknown tool: missing", ((TextContent) result.content().getFirst()).text());
	}

	@Test
	void compactsActiveContextIntoASummaryCheckpoint() throws Exception {
		FauxProvider provider = new FauxProvider();
		provider.setResponses(List.of(
				new FauxProvider.ResponseStep.Message(FauxProvider.text("Initial response.")),
				new FauxProvider.ResponseStep.Message(FauxProvider.text("## Goal\nPreserve the important context."))));
		Agent agent = new Agent("system", provider.models().getFirst(), provider::stream);
		agent.prompt("Explain the current project. ".repeat(100));

		CompactionResult result = agent.compact("Preserve the goal");

		assertEquals(2, provider.state().callCount());
		assertEquals("## Goal\nPreserve the important context.", result.summary());
		assertEquals(1, agent.state().messages.size());
		UserMessage checkpoint = assertInstanceOf(UserMessage.class, agent.state().messages.getFirst());
		assertTrue(checkpoint.text().startsWith("[Conversation checkpoint]\n## Goal"));
		assertTrue(result.estimatedTokensAfter() < result.tokensBefore());
	}
}
