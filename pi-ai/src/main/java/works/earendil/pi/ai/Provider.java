package works.earendil.pi.ai;

import java.util.List;
import works.earendil.pi.ai.stream.AssistantMessageEventStream;
import works.earendil.pi.ai.types.Context;
import works.earendil.pi.ai.types.Model;

/**
 * An LLM provider: descriptive metadata, its model list, and a streaming
 * entry point. Mirrors the Provider shape in packages/ai/src/models.ts,
 * collapsed to the parts the Java port needs.
 *
 * Contract for stream(): once invoked, failures must be encoded in the
 * returned stream (Error event with stopReason ERROR/ABORTED), not thrown.
 */
public interface Provider {
	String id();

	String name();

	/** API implementation id, e.g. "anthropic-messages", "openai-completions". */
	String api();

	List<Model> models();

	/** Env var(s) that supply this provider's API key, in priority order. */
	default List<String> apiKeyEnvVars() {
		return List.of();
	}

	AssistantMessageEventStream stream(Model model, Context context, StreamOptions options);
}
