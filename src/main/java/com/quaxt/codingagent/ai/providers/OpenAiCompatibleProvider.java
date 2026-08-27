package com.quaxt.codingagent.ai.providers;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import com.quaxt.codingagent.ai.Provider;
import com.quaxt.codingagent.ai.types.Model;

/**
 * OpenAI Chat Completions-compatible provider carrier. It supports OpenAI
 * compatible services such as local llama.cpp/vLLM deployments without an SDK,
 * using the JDK HTTP/SSE transport so it remains native-image friendly.
 *
 * <p>First-party OpenAI's bundled catalog primarily uses the Responses API;
 * {@link OpenAiResponsesProvider} handles those models. This adapter remains
 * useful for generic endpoints and models explicitly configured with
 * {@code api = "openai-completions"}. All behavior lives in
 * CodingAgentOperations.
 */
public final class OpenAiCompatibleProvider implements Provider {
	public static final String API = "openai-completions";

	public String id;
	public String name;
	public String baseUrl;
	public List<Model> models;

	public OpenAiCompatibleProvider(String id, String name, String baseUrl, List<Model> models) {
		this.id = id;
		this.name = name;
		this.baseUrl = baseUrl;
		this.models = models;
	}

	/** Streaming accumulator for one Chat Completions tool call index. */
	public static final class ToolCallAccumulator {
		public int wireIndex;
		public List<JsonNode> rawDeltas = new ArrayList<>();
		public int contentIndex = -1;
		public String id = "";
		public String name = "";
		public StringBuilder arguments = new StringBuilder();
		public boolean hasMeaningfulData;

		public ToolCallAccumulator(int wireIndex) {
			this.wireIndex = wireIndex;
		}
	}
}
