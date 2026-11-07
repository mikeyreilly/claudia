package com.quaxt.claudia.ai.providers;

import java.util.List;
import com.quaxt.claudia.ai.Provider;
import com.quaxt.claudia.ai.auth.CredentialStore;
import com.quaxt.claudia.ai.types.Model;

/**
 * Streaming carrier for OpenAI's Responses API adapter. It covers the generated
 * first-party OpenAI catalog; the compatible-provider operations handle the
 * separate Chat Completions wire protocol. All behavior lives in
 * ClaudiaOperations.
 */
public final class OpenAiResponsesProvider implements Provider {
	public static final String API = "openai-responses";

	public String id;
	public String name;
	public List<String> apiKeyEnvVars;
	public List<Model> models;
	public CredentialStore credentials;
	public RequestProfile requestProfile;

	public OpenAiResponsesProvider(
			String id,
			String name,
			List<Model> models,
			List<String> apiKeyEnvVars,
			CredentialStore credentials,
			RequestProfile requestProfile) {
		this.id = id;
		this.name = name;
		this.models = models;
		this.apiKeyEnvVars = apiKeyEnvVars;
		this.credentials = credentials;
		this.requestProfile = requestProfile;
	}

	/** Request shaping flavour: the public API or the ChatGPT Codex backend. */
	public enum RequestProfile {
		STANDARD,
		CODEX
	}

	/** Streaming state for one Responses API output item. */
	public static final class OutputItem {
		public int contentIndex;
		public String type;
		public String callId;
		public String name;
		public StringBuilder arguments = new StringBuilder();

		public OutputItem(int contentIndex, String type) {
			this.contentIndex = contentIndex;
			this.type = type;
		}
	}
}
