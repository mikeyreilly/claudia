package com.quaxt.codingagent.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import com.quaxt.codingagent.CodingAgentOperations;
import com.quaxt.codingagent.ai.providers.OpenAiCompatibleProvider;

class CoreProvidersTest {
	private static String api(CoreProviders providers, String id) {
		return CodingAgentOperations.providerApi(CodingAgentOperations.requireCoreProvider(providers, id));
	}

	@Test
	void exposesBundledCoreProviderSet() {
		CoreProviders providers = CodingAgentOperations.loadBundledCoreProviders();

		assertEquals("anthropic-messages", api(providers, "anthropic"));
		assertEquals("openai-responses", api(providers, "openai"));
		assertEquals("openai-responses", api(providers, "chatgpt"));
		assertTrue(CodingAgentOperations.providerModels(CodingAgentOperations.requireCoreProvider(providers, "chatgpt"))
				.stream()
				.anyMatch(model -> model.id.equals("gpt-5.6-terra")));
		assertEquals("google-generative-ai", api(providers, "google"));
		assertEquals("github-copilot", api(providers, "github-copilot"));
		assertEquals(5, CodingAgentOperations.allCoreProviders(providers).size());
		assertThrows(IllegalArgumentException.class, () -> CodingAgentOperations.requireCoreProvider(providers, "unknown"));
	}

	@Test
	void createsOpenAiCompatibleModel() {
		OpenAiCompatibleProvider provider =
				CodingAgentOperations.openAiCompatibleProvider("local", "Local", "http://localhost:1234/v1", "qwen");

		assertEquals("openai-completions", CodingAgentOperations.providerApi(provider));
		assertEquals("http://localhost:1234/v1", provider.models.getFirst().baseUrl);
		assertTrue(provider.models.getFirst().input.contains("image"));
	}
}
