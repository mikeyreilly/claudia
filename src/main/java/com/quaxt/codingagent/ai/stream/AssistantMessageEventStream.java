package com.quaxt.codingagent.ai.stream;

import com.quaxt.codingagent.ai.types.AssistantMessage;
import com.quaxt.codingagent.ai.types.AssistantMessageEvent;

/**
 * Event stream for one assistant response. Terminates on Done or Error;
 * result() yields the final AssistantMessage in both cases.
 */
public final class AssistantMessageEventStream extends EventStream<AssistantMessageEvent, AssistantMessage> {
	public AssistantMessageEventStream() {
		super(
				event -> event instanceof AssistantMessageEvent.Done || event instanceof AssistantMessageEvent.Error,
				event -> switch (event) {
					case AssistantMessageEvent.Done done -> done.message();
					case AssistantMessageEvent.Error error -> error.error();
					default -> throw new IllegalStateException("Unexpected event type for final result");
				});
	}
}
