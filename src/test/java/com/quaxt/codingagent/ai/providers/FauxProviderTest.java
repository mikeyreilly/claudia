package com.quaxt.codingagent.ai.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.quaxt.codingagent.CodingAgentOperations;
import com.quaxt.codingagent.ai.stream.AssistantMessageEventStream;
import com.quaxt.codingagent.ai.types.AssistantMessage;
import com.quaxt.codingagent.ai.types.AssistantMessageEvent;
import com.quaxt.codingagent.ai.types.Context;
import com.quaxt.codingagent.ai.types.StopReason;

class FauxProviderTest {
	@Test
	void streamsTextInProtocolOrder() throws Exception {
		FauxProvider provider = CodingAgentOperations.newFauxProvider();
		CodingAgentOperations.setFauxResponses(provider, List.of(new FauxProvider.ResponseStep.Message(CodingAgentOperations.fauxText("hello world"))));

		AssistantMessageEventStream stream = CodingAgentOperations.stream(provider, provider.models.getFirst(), new Context(), null);
		List<AssistantMessageEvent> events = new ArrayList<>();
		for (AssistantMessageEvent event : CodingAgentOperations.events(stream)) {
			events.add(event);
		}

		assertInstanceOf(AssistantMessageEvent.Start.class, events.get(0));
		assertInstanceOf(AssistantMessageEvent.TextStart.class, events.get(1));
		assertInstanceOf(AssistantMessageEvent.TextDelta.class, events.get(2));
		assertInstanceOf(AssistantMessageEvent.TextEnd.class, events.get(events.size() - 2));
		AssistantMessageEvent.Done done =
				assertInstanceOf(AssistantMessageEvent.Done.class, events.getLast());
		assertEquals(StopReason.STOP, done.reason);
		assertEquals("hello world", CodingAgentOperations.text(done.message));
		assertSame(done.message, CodingAgentOperations.result(stream));
		assertEquals(1, CodingAgentOperations.fauxCallCount(provider));
	}

	@Test
	void streamsToolCallAndRunsFactoryAgainstRequest() throws Exception {
		FauxProvider provider = CodingAgentOperations.newFauxProvider();
		ObjectNode arguments = CodingAgentOperations.jsonObject().put("path", "README.md");
		CodingAgentOperations.setFauxResponses(provider, List.of(new FauxProvider.ResponseStep.Factory(request -> {
			assertEquals("system", request.context.systemPrompt);
			return CodingAgentOperations.fauxToolCall("read", arguments);
		})));
		Context context = new Context("system");

		AssistantMessageEventStream stream = CodingAgentOperations.stream(provider, provider.models.getFirst(), context, null);
		AssistantMessage finalMessage = CodingAgentOperations.result(stream);
		assertEquals(StopReason.TOOL_USE, finalMessage.stopReason);
		assertEquals("read", CodingAgentOperations.toolCalls(finalMessage).getFirst().name);
		assertEquals("README.md", CodingAgentOperations.toolCalls(finalMessage).getFirst().arguments.path("path").asText());
	}

	@Test
	void reportsAProtocolErrorWhenScriptIsExhausted() throws Exception {
		FauxProvider provider = CodingAgentOperations.newFauxProvider();
		AssistantMessage result = CodingAgentOperations.result(CodingAgentOperations.stream(provider, provider.models.getFirst(), new Context(), null));

		assertEquals(StopReason.ERROR, result.stopReason);
		assertEquals("No more faux responses queued", result.errorMessage);
	}
}
