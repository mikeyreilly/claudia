package com.quaxt.claudia.ai.providers;

import java.util.List;
import com.quaxt.claudia.ai.Provider;
import com.quaxt.claudia.ai.types.Model;

/**
 * Native-image-safe carrier for Anthropic's streaming Messages API adapter.
 * The request building and SSE handling behavior lives in
 * ClaudiaOperations.
 */
public final class AnthropicProvider implements Provider {
	public static final String API_VERSION = "2023-06-01";
	public static final String API = "anthropic-messages";

	public String id;
	public String name;
	public List<String> apiKeyEnvVars;
	public boolean bearerAuthentication;
	public List<Model> models;

	public AnthropicProvider(
			String id, String name, List<Model> models, List<String> apiKeyEnvVars, boolean bearerAuthentication) {
		this.id = id;
		this.name = name;
		this.models = models;
		this.apiKeyEnvVars = apiKeyEnvVars;
		this.bearerAuthentication = bearerAuthentication;
	}

	/** Streaming accumulator for one Anthropic tool_use content block. */
	public static final class ToolCallAccumulator {
		public int index;
		public String id;
		public String name;
		public StringBuilder arguments = new StringBuilder();

		public ToolCallAccumulator(int index) {
			this.index = index;
		}
	}
}
