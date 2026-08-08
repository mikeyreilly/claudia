package works.earendil.pi.agent;

import works.earendil.pi.ai.StreamOptions;
import works.earendil.pi.ai.stream.AssistantMessageEventStream;
import works.earendil.pi.ai.types.Context;
import works.earendil.pi.ai.types.Model;

/** Injectable provider call boundary; enables faux-provider agent tests. */
@FunctionalInterface
public interface StreamFunction {
	AssistantMessageEventStream stream(Model model, Context context, StreamOptions options);
}
