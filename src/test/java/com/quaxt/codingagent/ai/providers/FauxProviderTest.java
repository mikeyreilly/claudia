package com.quaxt.codingagent.ai.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.quaxt.codingagent.ai.json.Json;
import com.quaxt.codingagent.ai.stream.AssistantMessageEventStream;
import com.quaxt.codingagent.ai.types.AssistantMessage;
import com.quaxt.codingagent.ai.types.AssistantMessageEvent;
import com.quaxt.codingagent.ai.types.Context;
import com.quaxt.codingagent.ai.types.StopReason;

class FauxProviderTest {
	@Test
	void streamsTextInProtocolOrder() throws Exception {
		FauxProvider provider = new FauxProvider();
		provider.setResponses(List.of(new FauxProvider.ResponseStep.Message(FauxProvider.text("hello world"))));

		AssistantMessageEventStream stream = provider.stream(provider.models().getFirst(), new Context(), null);
		List<AssistantMessageEvent> events = new ArrayList<>();
		for (AssistantMessageEvent event : stream) {
			events.add(event);
		}

		assertInstanceOf(AssistantMessageEvent.Start.class, events.get(0));
		assertInstanceOf(AssistantMessageEvent.TextStart.class, events.get(1));
		assertInstanceOf(AssistantMessageEvent.TextDelta.class, events.get(2));
		assertInstanceOf(AssistantMessageEvent.TextEnd.class, events.get(events.size() - 2));
		AssistantMessageEvent.Done done =
				assertInstanceOf(AssistantMessageEvent.Done.class, events.getLast());
		assertEquals(StopReason.STOP, done.reason());
		assertEquals("hello world", done.message().text());
		assertSame(done.message(), stream.result());
		assertEquals(1, provider.state().callCount());
	}

	@Test
	void streamsToolCallAndRunsFactoryAgainstRequest() throws Exception {
		FauxProvider provider = new FauxProvider();
		ObjectNode arguments = Json.object().put("path", "README.md");
		provider.setResponses(List.of(new FauxProvider.ResponseStep.Factory(request -> {
			assertEquals("system", request.context().systemPrompt);
			return FauxProvider.toolCall("read", arguments);
		})));
		Context context = new Context("system");

		AssistantMessageEventStream stream = provider.stream(provider.models().getFirst(), context, null);
		AssistantMessage finalMessage = stream.result();
		assertEquals(StopReason.TOOL_USE, finalMessage.stopReason);
		assertEquals("read", finalMessage.toolCalls().getFirst().name());
		assertEquals("README.md", finalMessage.toolCalls().getFirst().arguments().path("path").asText());
	}

	@Test
	void reportsAProtocolErrorWhenScriptIsExhausted() throws Exception {
		FauxProvider provider = new FauxProvider();
		AssistantMessage result = provider.stream(provider.models().getFirst(), new Context(), null).result();

		assertEquals(StopReason.ERROR, result.stopReason);
		assertEquals("No more faux responses queued", result.errorMessage);
	}
}
