package com.quaxt.codingagent.ai.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.quaxt.codingagent.CodingAgentOperations;
import com.quaxt.codingagent.ai.types.AssistantMessage;
import com.quaxt.codingagent.ai.types.AssistantMessageEvent;
import com.quaxt.codingagent.ai.types.StopReason;
import com.quaxt.codingagent.ai.types.TextContent;

class EventStreamTest {
	@Test
	void deliversQueuedEventsThenTerminates() throws Exception {
		AssistantMessageEventStream stream = new AssistantMessageEventStream();
		AssistantMessage partial = new AssistantMessage("test-api", "test-provider", "test-model");

		CodingAgentOperations.push(stream, new AssistantMessageEvent.Start(partial));
		partial.content.add(CodingAgentOperations.textContent("hello"));
		CodingAgentOperations.push(stream, new AssistantMessageEvent.TextDelta(0, "hello", partial));
		partial.stopReason = StopReason.STOP;
		CodingAgentOperations.push(stream, new AssistantMessageEvent.Done(StopReason.STOP, partial));

		List<AssistantMessageEvent> events = new ArrayList<>();
		for (AssistantMessageEvent event : CodingAgentOperations.events(stream)) {
			events.add(event);
		}
		assertEquals(3, events.size());
		assertInstanceOf(AssistantMessageEvent.Start.class, events.get(0));
		assertInstanceOf(AssistantMessageEvent.TextDelta.class, events.get(1));
		assertInstanceOf(AssistantMessageEvent.Done.class, events.get(2));
		assertSame(partial, CodingAgentOperations.result(stream));
	}

	@Test
	void blocksUntilProducerPushes() throws Exception {
		AssistantMessageEventStream stream = new AssistantMessageEventStream();
		AssistantMessage message = new AssistantMessage("a", "p", "m");
		Thread producer = Thread.ofVirtual().start(() -> {
			try {
				Thread.sleep(50);
			} catch (InterruptedException ignored) {
			}
			CodingAgentOperations.push(stream, new AssistantMessageEvent.Start(message));
			CodingAgentOperations.push(stream, new AssistantMessageEvent.Done(StopReason.STOP, message));
		});

		int count = 0;
		for (AssistantMessageEvent ignored : CodingAgentOperations.events(stream)) {
			count++;
		}
		producer.join();
		assertEquals(2, count);
	}

	@Test
	void ignoresPushAfterTerminal() {
		AssistantMessageEventStream stream = new AssistantMessageEventStream();
		AssistantMessage message = new AssistantMessage("a", "p", "m");
		CodingAgentOperations.push(stream, new AssistantMessageEvent.Done(StopReason.STOP, message));
		CodingAgentOperations.push(stream, new AssistantMessageEvent.Start(message));

		Iterator<AssistantMessageEvent> it = CodingAgentOperations.iterator(stream);
		assertTrue(it.hasNext());
		it.next();
		assertFalse(it.hasNext());
	}

	@Test
	void errorEventYieldsErrorMessageAsResult() throws Exception {
		AssistantMessageEventStream stream = new AssistantMessageEventStream();
		AssistantMessage error = new AssistantMessage("a", "p", "m");
		error.stopReason = StopReason.ERROR;
		error.errorMessage = "boom";
		CodingAgentOperations.push(stream, new AssistantMessageEvent.Error(StopReason.ERROR, error));
		assertSame(error, CodingAgentOperations.result(stream));
		assertEquals("boom", CodingAgentOperations.result(stream).errorMessage);
	}
}
