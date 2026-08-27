package com.quaxt.codingagent.ai.providers;

import java.util.List;
import com.quaxt.codingagent.ai.Provider;
import com.quaxt.codingagent.ai.auth.GitHubCopilotAuth;
import com.quaxt.codingagent.ai.types.Model;

/**
 * Carrier that routes GitHub Copilot models through their catalog-declared
 * streaming API. Construction, model-access, and streaming behavior lives in
 * CodingAgentOperations.
 */
public final class GitHubCopilotProvider implements Provider {
	public static final String NAME = "GitHub Copilot";
	public static final String API = "github-copilot";
	public static final String COMPLETIONS_BASE_URL = "https://api.individual.githubcopilot.com";

	public List<Model> models;
	public GitHubCopilotAuth auth;
	public AnthropicProvider anthropic;
	public OpenAiCompatibleProvider completions;
	public OpenAiResponsesProvider responses;

	public GitHubCopilotProvider(
			List<Model> models,
			GitHubCopilotAuth auth,
			AnthropicProvider anthropic,
			OpenAiCompatibleProvider completions,
			OpenAiResponsesProvider responses) {
		this.models = models;
		this.auth = auth;
		this.anthropic = anthropic;
		this.completions = completions;
		this.responses = responses;
	}

	/** Result of enabling model policies and refreshing the enabled-model list. */
	public static final class ModelAccess {
		public int policiesEnabled;
		public List<Model> models;

		public ModelAccess(int policiesEnabled, List<Model> models) {
			this.policiesEnabled = policiesEnabled;
			this.models = models;
		}
	}
}
