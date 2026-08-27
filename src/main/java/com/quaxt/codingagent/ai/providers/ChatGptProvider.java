package com.quaxt.codingagent.ai.providers;

import java.util.List;
import java.util.Set;
import com.quaxt.codingagent.ai.Provider;
import com.quaxt.codingagent.ai.auth.ChatGptAuth;
import com.quaxt.codingagent.ai.types.Model;

/**
 * Carrier for the OpenAI Responses provider authenticated with a ChatGPT
 * Plus/Pro subscription. Construction and streaming behavior lives in
 * CodingAgentOperations.
 */
public final class ChatGptProvider implements Provider {
	public static final String NAME = "ChatGPT Plus/Pro";
	public static final String API = "openai-responses";
	public static final Set<String> CODEX_MODEL_IDS = Set.of(
			"gpt-5.3-codex",
			"gpt-5.3-codex-spark",
			"gpt-5.4",
			"gpt-5.5",
			"gpt-5.6-luna",
			"gpt-5.6-sol",
			"gpt-5.6-terra");

	public List<Model> models;
	public ChatGptAuth auth;
	public OpenAiResponsesProvider responses;

	public ChatGptProvider(List<Model> models, ChatGptAuth auth, OpenAiResponsesProvider responses) {
		this.models = models;
		this.auth = auth;
		this.responses = responses;
	}
}
