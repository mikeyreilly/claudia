package com.quaxt.codingagent.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.quaxt.codingagent.ai.providers.AnthropicProvider;
import com.quaxt.codingagent.ai.providers.GoogleProvider;
import com.quaxt.codingagent.ai.providers.GitHubCopilotProvider;
import com.quaxt.codingagent.ai.providers.OpenAiCompatibleProvider;
import com.quaxt.codingagent.ai.providers.OpenAiResponsesProvider;
import com.quaxt.codingagent.ai.types.Model;
import com.quaxt.codingagent.ai.types.ModelCost;

/**
 * The provider set selected for the Java port: Anthropic, OpenAI Responses,
 * Google Generative AI, and caller-configured OpenAI-compatible endpoints.
 */
public final class CoreProviders {
	private final ModelCatalog catalog;
	private final Map<String, Provider> providers;

	private CoreProviders(ModelCatalog catalog, Map<String, Provider> providers) {
		this.catalog = catalog;
		this.providers = Map.copyOf(providers);
	}

	public static CoreProviders loadBundled() {
		ModelCatalog catalog = ModelCatalog.loadBundled();
		Map<String, Provider> providers = new LinkedHashMap<>();
		providers.put(
				"anthropic",
				new AnthropicProvider(catalog.forProvider("anthropic").stream()
						.filter(model -> model.api.equals("anthropic-messages"))
						.toList()));
		providers.put(
				"openai",
				new OpenAiResponsesProvider(catalog.forProvider("openai").stream()
						.filter(model -> model.api.equals("openai-responses"))
						.toList()));
		providers.put(
				"google",
				new GoogleProvider(catalog.forProvider("google").stream()
						.filter(model -> model.api.equals("google-generative-ai"))
						.toList()));
		providers.put(
				"github-copilot",
				new GitHubCopilotProvider(
						catalog.forProvider("github-copilot"),
						new com.quaxt.codingagent.ai.auth.GitHubCopilotAuth(
								com.quaxt.codingagent.ai.auth.FileCredentialStore.defaultStore())));
		return new CoreProviders(catalog, providers);
	}

	public ModelCatalog catalog() {
		return catalog;
	}

	public Provider require(String id) {
		Provider provider = providers.get(id);
		if (provider == null) {
			throw new IllegalArgumentException("Unknown core provider: " + id);
		}
		return provider;
	}

	public List<Provider> all() {
		return List.copyOf(providers.values());
	}

	/**
	 * Creates a one-model provider for a user-configured Chat
	 * Completions-compatible service (including local models).
	 */
	public static OpenAiCompatibleProvider openAiCompatible(
			String providerId, String providerName, String baseUrl, String modelId) {
		Model model = Model.builder()
				.id(modelId)
				.name(modelId)
				.api("openai-completions")
				.provider(providerId)
				.baseUrl(baseUrl)
				.input(List.of("text", "image"))
				.cost(ModelCost.FREE)
				.contextWindow(128_000)
				.maxTokens(16_384)
				.build();
		return new OpenAiCompatibleProvider(providerId, providerName, baseUrl, List.of(model));
	}
}
