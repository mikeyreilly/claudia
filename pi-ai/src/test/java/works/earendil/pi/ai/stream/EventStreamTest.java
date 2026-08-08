package works.earendil.pi.ai.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;
import works.earendil.pi.ai.types.AssistantMessage;
import works.earendil.pi.ai.types.AssistantMessageEvent;
import works.earendil.pi.ai.types.StopReason;
import works.earendil.pi.ai.types.TextContent;

class EventStreamTest {
	@Test
	void deliversQueuedEventsThenTerminates() throws Exception {
		AssistantMessageEventStream stream = new AssistantMessageEventStream();
		AssistantMessage partial = new AssistantMessage("test-api", "test-provider", "test-model");

		stream.push(new AssistantMessageEvent.Start(partial));
		partial.content.add(new TextContent("hello"));
		stream.push(new AssistantMessageEvent.TextDelta(0, "hello", partial));
		partial.stopReason = StopReason.STOP;
		stream.push(new AssistantMessageEvent.Done(StopReason.STOP, partial));

		List<AssistantMessageEvent> events = new ArrayList<>();
		for (AssistantMessageEvent event : stream) {
			events.add(event);
		}
		assertEquals(3, events.size());
		assertInstanceOf(AssistantMessageEvent.Start.class, events.get(0));
		assertInstanceOf(AssistantMessageEvent.TextDelta.class, events.get(1));
		assertInstanceOf(AssistantMessageEvent.Done.class, events.get(2));
		assertSame(partial, stream.result());
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
			stream.push(new AssistantMessageEvent.Start(message));
			stream.push(new AssistantMessageEvent.Done(StopReason.STOP, message));
		});

		int count = 0;
		for (AssistantMessageEvent ignored : stream) {
			count++;
		}
		producer.join();
		assertEquals(2, count);
	}

	@Test
	void ignoresPushAfterTerminal() {
		AssistantMessageEventStream stream = new AssistantMessageEventStream();
		AssistantMessage message = new AssistantMessage("a", "p", "m");
		stream.push(new AssistantMessageEvent.Done(StopReason.STOP, message));
		stream.push(new AssistantMessageEvent.Start(message));

		Iterator<AssistantMessageEvent> it = stream.iterator();
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
		stream.push(new AssistantMessageEvent.Error(StopReason.ERROR, error));
		assertSame(error, stream.result());
		assertEquals("boom", stream.result().errorMessage);
	}
}
