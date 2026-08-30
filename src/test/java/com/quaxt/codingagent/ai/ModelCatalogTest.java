package com.quaxt.codingagent.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import com.quaxt.codingagent.CodingAgentOperations;
import com.quaxt.codingagent.ai.types.Compat;
import com.quaxt.codingagent.ai.types.Model;

class ModelCatalogTest {
	@Test
	void loadsAllBundledCoreProviderCatalogs() {
		CodingAgentOperations catalog = CodingAgentOperations.loadBundledModelCatalog();

		assertTrue(catalog.catalogModelsForProvider("anthropic").size() > 0);
		assertTrue(catalog.catalogModelsForProvider("openai").size() > 0);
		assertTrue(catalog.catalogModelsForProvider("google").size() > 0);
		assertTrue(catalog.catalogModelsForProvider("github-copilot").size() > 0);
		assertEquals(
				catalog.catalogModelsForProvider("anthropic").size()
						+ catalog.catalogModelsForProvider("openai").size()
						+ catalog.catalogModelsForProvider("google").size(),
				catalog.allCatalogModels().size() - catalog.catalogModelsForProvider("github-copilot").size());
	}

	@Test
	void loadsGitHubCopilotProtocolModels() {
		CodingAgentOperations catalog = CodingAgentOperations.loadBundledModelCatalog();

		assertTrue(catalog.catalogModelsForProvider("github-copilot").size() > 25);
		assertEquals("openai-responses", catalog.requireCatalogModel("github-copilot", "gpt-5.6-terra").api);
		assertEquals("openai-completions", catalog.requireCatalogModel("github-copilot", "gemini-3.6-flash").api);
		assertEquals("anthropic-messages", catalog.requireCatalogModel("github-copilot", "claude-opus-5").api);
	}

	@Test
	void preservesAdaptiveThinkingCompatibility() {
		Model opus = CodingAgentOperations.loadBundledModelCatalog().requireCatalogModel("github-copilot", "claude-opus-5");

		Compat.AnthropicMessages compat = assertInstanceOf(Compat.AnthropicMessages.class, opus.compat);
		assertEquals(Boolean.TRUE, compat.forceAdaptiveThinking);
	}

	@Test
	void preservesGeneratedCatalogProperties() {
		CodingAgentOperations catalog = CodingAgentOperations.loadBundledModelCatalog();

		Model model = catalog.requireCatalogModel("anthropic", "claude-haiku-4-5");
		assertEquals("anthropic-messages", model.api);
		assertEquals("https://api.anthropic.com", model.baseUrl);
		assertTrue(model.input.contains("image"));
		assertTrue(model.reasoning);
		assertTrue(model.contextWindow > 0);
		assertTrue(model.maxTokens > 0);
		assertTrue(model.cost.input > 0);
		assertNotNull(model.cost);
	}

	@Test
	void returnsNullOrClearErrorForUnknownModel() {
		CodingAgentOperations catalog = CodingAgentOperations.loadBundledModelCatalog();

		assertEquals(null, catalog.findCatalogModel("openai", "does-not-exist"));
		IllegalArgumentException exception =
				assertThrows(IllegalArgumentException.class, () -> catalog.requireCatalogModel("openai", "does-not-exist"));
		assertEquals("Unknown model: openai/does-not-exist", exception.getMessage());
	}
}
