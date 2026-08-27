package com.quaxt.codingagent.ai.stream;

import com.quaxt.codingagent.ai.types.AssistantMessage;
import com.quaxt.codingagent.ai.types.AssistantMessageEvent;

/**
 * Event stream state for one assistant response. Terminates on Done or Error;
 * the final AssistantMessage is produced in both cases.
 */
public final class AssistantMessageEventStream extends EventStream<AssistantMessageEvent, AssistantMessage> {
	public AssistantMessageEventStream() {}
}
