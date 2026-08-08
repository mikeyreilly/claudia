package com.quaxt.codingagent.agent;

import com.quaxt.codingagent.ai.StreamOptions;
import com.quaxt.codingagent.ai.stream.AssistantMessageEventStream;
import com.quaxt.codingagent.ai.types.Context;
import com.quaxt.codingagent.ai.types.Model;

/** Injectable provider call boundary; enables faux-provider agent tests. */
@FunctionalInterface
public interface StreamFunction {
	AssistantMessageEventStream stream(Model model, Context context, StreamOptions options);
}
