package com.quaxt.codingagent.ai.types;

/**
 * API-specific compatibility overrides. Boolean fields use the wrapper type:
 * null means "use the adapter default / auto-detect from baseUrl".
 * Trimmed to the settings the core-provider adapters implement; the TS
 * originals in packages/ai/src/types.ts carry many more knobs for the
 * long tail of providers that are out of scope for the Java port.
 */
public sealed interface Compat
		permits Compat.OpenAICompletions, Compat.OpenAIResponses, Compat.AnthropicMessages, Compat.Google {

	/** Compatibility settings for OpenAI-compatible chat completions APIs. */
	record OpenAICompletions(
			Boolean supportsStore,
			Boolean supportsDeveloperRole,
			Boolean supportsReasoningEffort,
			Boolean supportsUsageInStreaming,
			Boolean supportsFinishReason,
			/** "max_completion_tokens" or "max_tokens"; null = auto-detect. */
			String maxTokensField,
			Boolean requiresToolResultName,
			Boolean requiresAssistantAfterToolResult,
			Boolean requiresThinkingAsText,
			/** Reasoning parameter format: "openai" or "openrouter"; null = "openai". */
			String thinkingFormat,
			Boolean supportsStrictMode)
			implements Compat {
		public static final OpenAICompletions DEFAULTS =
				new OpenAICompletions(null, null, null, null, null, null, null, null, null, null, null);
	}

	/** Compatibility settings for OpenAI Responses APIs. */
	record OpenAIResponses(Boolean supportsDeveloperRole, Boolean supportsStrictMode, Boolean supportsLongCacheRetention)
			implements Compat {
		public static final OpenAIResponses DEFAULTS = new OpenAIResponses(null, null, null);
	}

	/** Compatibility settings for Anthropic Messages-compatible APIs. */
	record AnthropicMessages(
			Boolean supportsLongCacheRetention,
			Boolean supportsCacheControlOnTools,
			Boolean supportsTemperature,
			Boolean allowEmptySignature)
			implements Compat {
		public static final AnthropicMessages DEFAULTS = new AnthropicMessages(null, null, null, null);
	}

	/** Compatibility settings for Google Generative Language API. */
	record Google(Boolean supportsThinkingBudget) implements Compat {
		public static final Google DEFAULTS = new Google((Boolean) null);
	}
}
