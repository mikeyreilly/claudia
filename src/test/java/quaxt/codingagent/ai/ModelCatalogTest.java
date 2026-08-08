package works.earendil.pi.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import works.earendil.pi.ai.types.Model;

class ModelCatalogTest {
	@Test
	void loadsAllBundledCoreProviderCatalogs() {
		ModelCatalog catalog = ModelCatalog.loadBundled();

		assertTrue(catalog.forProvider("anthropic").size() > 0);
		assertTrue(catalog.forProvider("openai").size() > 0);
		assertTrue(catalog.forProvider("google").size() > 0);
		assertTrue(catalog.forProvider("github-copilot").size() > 0);
		assertEquals(
				catalog.forProvider("anthropic").size()
						+ catalog.forProvider("openai").size()
						+ catalog.forProvider("google").size(),
				catalog.all().size() - catalog.forProvider("github-copilot").size());
	}

	@Test
	void loadsGitHubCopilotProtocolModels() {
		ModelCatalog catalog = ModelCatalog.loadBundled();

		assertTrue(catalog.forProvider("github-copilot").size() > 25);
		assertEquals("openai-responses", catalog.require("github-copilot", "gpt-5.6-terra").api);
		assertEquals("openai-completions", catalog.require("github-copilot", "gemini-3.6-flash").api);
		assertEquals("anthropic-messages", catalog.require("github-copilot", "claude-opus-5").api);
	}

	@Test
	void preservesGeneratedCatalogProperties() {
		ModelCatalog catalog = ModelCatalog.loadBundled();

		Model model = catalog.require("anthropic", "claude-haiku-4-5");
		assertEquals("anthropic-messages", model.api);
		assertEquals("https://api.anthropic.com", model.baseUrl);
		assertTrue(model.input.contains("image"));
		assertTrue(model.reasoning);
		assertTrue(model.contextWindow > 0);
		assertTrue(model.maxTokens > 0);
		assertTrue(model.cost.input() > 0);
		assertNotNull(model.cost);
	}

	@Test
	void returnsNullOrClearErrorForUnknownModel() {
		ModelCatalog catalog = ModelCatalog.loadBundled();

		assertEquals(null, catalog.find("openai", "does-not-exist"));
		IllegalArgumentException exception =
				assertThrows(IllegalArgumentException.class, () -> catalog.require("openai", "does-not-exist"));
		assertEquals("Unknown model: openai/does-not-exist", exception.getMessage());
	}
}
