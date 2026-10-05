package com.quaxt.claudia.ai.types;

import java.util.Objects;

/**
 * API-specific compatibility overrides. Boolean fields use the wrapper type:
 * null means "use the adapter default / auto-detect from baseUrl".
 * Trimmed to the settings the core-provider adapters implement; the TS
 * originals in packages/ai/src/types.ts carry many more knobs for the
 * long tail of providers that are out of scope for the Java port.
 *
 * <p>Marker interface; the sealed hierarchy is retained so adapters can pattern
 * match on the API flavour.
 */
public sealed interface Compat
		permits Compat.OpenAICompletions, Compat.OpenAIResponses, Compat.AnthropicMessages, Compat.Google {

	/** Compatibility settings for OpenAI-compatible chat completions APIs. */
	final class OpenAICompletions implements Compat {
		public static final OpenAICompletions DEFAULTS =
				new OpenAICompletions(null, null, null, null, null, null, null, null, null, null, null);

		public Boolean supportsStore;
		public Boolean supportsDeveloperRole;
		public Boolean supportsReasoningEffort;
		public Boolean supportsUsageInStreaming;
		public Boolean supportsFinishReason;
		/** "max_completion_tokens" or "max_tokens"; null = auto-detect. */
		public String maxTokensField;
		public Boolean requiresToolResultName;
		public Boolean requiresAssistantAfterToolResult;
		public Boolean requiresThinkingAsText;
		/** Reasoning parameter format: "openai" or "openrouter"; null = "openai". */
		public String thinkingFormat;
		public Boolean supportsStrictMode;

		public OpenAICompletions(
				Boolean supportsStore,
				Boolean supportsDeveloperRole,
				Boolean supportsReasoningEffort,
				Boolean supportsUsageInStreaming,
				Boolean supportsFinishReason,
				String maxTokensField,
				Boolean requiresToolResultName,
				Boolean requiresAssistantAfterToolResult,
				Boolean requiresThinkingAsText,
				String thinkingFormat,
				Boolean supportsStrictMode) {
			this.supportsStore = supportsStore;
			this.supportsDeveloperRole = supportsDeveloperRole;
			this.supportsReasoningEffort = supportsReasoningEffort;
			this.supportsUsageInStreaming = supportsUsageInStreaming;
			this.supportsFinishReason = supportsFinishReason;
			this.maxTokensField = maxTokensField;
			this.requiresToolResultName = requiresToolResultName;
			this.requiresAssistantAfterToolResult = requiresAssistantAfterToolResult;
			this.requiresThinkingAsText = requiresThinkingAsText;
			this.thinkingFormat = thinkingFormat;
			this.supportsStrictMode = supportsStrictMode;
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof OpenAICompletions that
					&& Objects.equals(supportsStore, that.supportsStore)
					&& Objects.equals(supportsDeveloperRole, that.supportsDeveloperRole)
					&& Objects.equals(supportsReasoningEffort, that.supportsReasoningEffort)
					&& Objects.equals(supportsUsageInStreaming, that.supportsUsageInStreaming)
					&& Objects.equals(supportsFinishReason, that.supportsFinishReason)
					&& Objects.equals(maxTokensField, that.maxTokensField)
					&& Objects.equals(requiresToolResultName, that.requiresToolResultName)
					&& Objects.equals(requiresAssistantAfterToolResult, that.requiresAssistantAfterToolResult)
					&& Objects.equals(requiresThinkingAsText, that.requiresThinkingAsText)
					&& Objects.equals(thinkingFormat, that.thinkingFormat)
					&& Objects.equals(supportsStrictMode, that.supportsStrictMode);
		}

		@Override
		public int hashCode() {
			return Objects.hash(
					supportsStore,
					supportsDeveloperRole,
					supportsReasoningEffort,
					supportsUsageInStreaming,
					supportsFinishReason,
					maxTokensField,
					requiresToolResultName,
					requiresAssistantAfterToolResult,
					requiresThinkingAsText,
					thinkingFormat,
					supportsStrictMode);
		}

		@Override
		public String toString() {
			return "OpenAICompletions[supportsStore=" + supportsStore
					+ ", supportsDeveloperRole=" + supportsDeveloperRole
					+ ", supportsReasoningEffort=" + supportsReasoningEffort
					+ ", supportsUsageInStreaming=" + supportsUsageInStreaming
					+ ", supportsFinishReason=" + supportsFinishReason
					+ ", maxTokensField=" + maxTokensField
					+ ", requiresToolResultName=" + requiresToolResultName
					+ ", requiresAssistantAfterToolResult=" + requiresAssistantAfterToolResult
					+ ", requiresThinkingAsText=" + requiresThinkingAsText
					+ ", thinkingFormat=" + thinkingFormat
					+ ", supportsStrictMode=" + supportsStrictMode + "]";
		}
	}

	/** Compatibility settings for OpenAI Responses APIs. */
	final class OpenAIResponses implements Compat {
		public static final OpenAIResponses DEFAULTS = new OpenAIResponses(null, null, null);

		public Boolean supportsDeveloperRole;
		public Boolean supportsStrictMode;
		public Boolean supportsLongCacheRetention;

		public OpenAIResponses(
				Boolean supportsDeveloperRole, Boolean supportsStrictMode, Boolean supportsLongCacheRetention) {
			this.supportsDeveloperRole = supportsDeveloperRole;
			this.supportsStrictMode = supportsStrictMode;
			this.supportsLongCacheRetention = supportsLongCacheRetention;
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof OpenAIResponses that
					&& Objects.equals(supportsDeveloperRole, that.supportsDeveloperRole)
					&& Objects.equals(supportsStrictMode, that.supportsStrictMode)
					&& Objects.equals(supportsLongCacheRetention, that.supportsLongCacheRetention);
		}

		@Override
		public int hashCode() {
			return Objects.hash(supportsDeveloperRole, supportsStrictMode, supportsLongCacheRetention);
		}

		@Override
		public String toString() {
			return "OpenAIResponses[supportsDeveloperRole=" + supportsDeveloperRole
					+ ", supportsStrictMode=" + supportsStrictMode
					+ ", supportsLongCacheRetention=" + supportsLongCacheRetention + "]";
		}
	}

	/** Compatibility settings for Anthropic Messages-compatible APIs. */
	final class AnthropicMessages implements Compat {
		public static final AnthropicMessages DEFAULTS = new AnthropicMessages(null, null, null, null, null);

		public Boolean supportsLongCacheRetention;
		public Boolean supportsCacheControlOnTools;
		public Boolean supportsTemperature;
		public Boolean allowEmptySignature;
		/** Use adaptive thinking plus output_config.effort instead of a token budget. */
		public Boolean forceAdaptiveThinking;

		public AnthropicMessages(
				Boolean supportsLongCacheRetention,
				Boolean supportsCacheControlOnTools,
				Boolean supportsTemperature,
				Boolean allowEmptySignature,
				Boolean forceAdaptiveThinking) {
			this.supportsLongCacheRetention = supportsLongCacheRetention;
			this.supportsCacheControlOnTools = supportsCacheControlOnTools;
			this.supportsTemperature = supportsTemperature;
			this.allowEmptySignature = allowEmptySignature;
			this.forceAdaptiveThinking = forceAdaptiveThinking;
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof AnthropicMessages that
					&& Objects.equals(supportsLongCacheRetention, that.supportsLongCacheRetention)
					&& Objects.equals(supportsCacheControlOnTools, that.supportsCacheControlOnTools)
					&& Objects.equals(supportsTemperature, that.supportsTemperature)
					&& Objects.equals(allowEmptySignature, that.allowEmptySignature)
					&& Objects.equals(forceAdaptiveThinking, that.forceAdaptiveThinking);
		}

		@Override
		public int hashCode() {
			return Objects.hash(
					supportsLongCacheRetention,
					supportsCacheControlOnTools,
					supportsTemperature,
					allowEmptySignature,
					forceAdaptiveThinking);
		}

		@Override
		public String toString() {
			return "AnthropicMessages[supportsLongCacheRetention=" + supportsLongCacheRetention
					+ ", supportsCacheControlOnTools=" + supportsCacheControlOnTools
					+ ", supportsTemperature=" + supportsTemperature
					+ ", allowEmptySignature=" + allowEmptySignature
					+ ", forceAdaptiveThinking=" + forceAdaptiveThinking + "]";
		}
	}

	/** Compatibility settings for Google Generative Language API. */
	final class Google implements Compat {
		public static final Google DEFAULTS = new Google(null);

		public Boolean supportsThinkingBudget;

		public Google(Boolean supportsThinkingBudget) {
			this.supportsThinkingBudget = supportsThinkingBudget;
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof Google that
					&& Objects.equals(supportsThinkingBudget, that.supportsThinkingBudget);
		}

		@Override
		public int hashCode() {
			return Objects.hash(supportsThinkingBudget);
		}

		@Override
		public String toString() {
			return "Google[supportsThinkingBudget=" + supportsThinkingBudget + "]";
		}
	}
}
