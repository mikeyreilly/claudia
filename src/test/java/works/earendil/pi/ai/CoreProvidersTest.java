package works.earendil.pi.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import works.earendil.pi.ai.providers.OpenAiCompatibleProvider;

class CoreProvidersTest {
	@Test
	void exposesBundledCoreProviderSet() {
		CoreProviders providers = CoreProviders.loadBundled();

		assertEquals("anthropic-messages", providers.require("anthropic").api());
		assertEquals("openai-responses", providers.require("openai").api());
		assertEquals("google-generative-ai", providers.require("google").api());
		assertEquals("github-copilot", providers.require("github-copilot").api());
		assertEquals(4, providers.all().size());
		assertThrows(IllegalArgumentException.class, () -> providers.require("unknown"));
	}

	@Test
	void createsOpenAiCompatibleModel() {
		OpenAiCompatibleProvider provider =
				CoreProviders.openAiCompatible("local", "Local", "http://localhost:1234/v1", "qwen");

		assertEquals("openai-completions", provider.api());
		assertEquals("http://localhost:1234/v1", provider.models().getFirst().baseUrl);
		assertTrue(provider.models().getFirst().input.contains("image"));
	}
}
