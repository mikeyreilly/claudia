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
		ModelCatalog catalog = CodingAgentOperations.loadBundledModelCatalog();

		assertTrue(CodingAgentOperations.catalogModelsForProvider(catalog, "anthropic").size() > 0);
		assertTrue(CodingAgentOperations.catalogModelsForProvider(catalog, "openai").size() > 0);
		assertTrue(CodingAgentOperations.catalogModelsForProvider(catalog, "google").size() > 0);
		assertTrue(CodingAgentOperations.catalogModelsForProvider(catalog, "github-copilot").size() > 0);
		assertEquals(
				CodingAgentOperations.catalogModelsForProvider(catalog, "anthropic").size()
						+ CodingAgentOperations.catalogModelsForProvider(catalog, "openai").size()
						+ CodingAgentOperations.catalogModelsForProvider(catalog, "google").size(),
				CodingAgentOperations.allCatalogModels(catalog).size() - CodingAgentOperations.catalogModelsForProvider(catalog, "github-copilot").size());
	}

	@Test
	void loadsGitHubCopilotProtocolModels() {
		ModelCatalog catalog = CodingAgentOperations.loadBundledModelCatalog();

		assertTrue(CodingAgentOperations.catalogModelsForProvider(catalog, "github-copilot").size() > 25);
		assertEquals("openai-responses", CodingAgentOperations.requireCatalogModel(catalog, "github-copilot", "gpt-5.6-terra").api);
		assertEquals("openai-completions", CodingAgentOperations.requireCatalogModel(catalog, "github-copilot", "gemini-3.6-flash").api);
		assertEquals("anthropic-messages", CodingAgentOperations.requireCatalogModel(catalog, "github-copilot", "claude-opus-5").api);
	}

	@Test
	void preservesAdaptiveThinkingCompatibility() {
		Model opus = CodingAgentOperations.requireCatalogModel(
				CodingAgentOperations.loadBundledModelCatalog(), "github-copilot", "claude-opus-5");

		Compat.AnthropicMessages compat = assertInstanceOf(Compat.AnthropicMessages.class, opus.compat);
		assertEquals(Boolean.TRUE, compat.forceAdaptiveThinking);
	}

	@Test
	void preservesGeneratedCatalogProperties() {
		ModelCatalog catalog = CodingAgentOperations.loadBundledModelCatalog();

		Model model = CodingAgentOperations.requireCatalogModel(catalog, "anthropic", "claude-haiku-4-5");
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
		ModelCatalog catalog = CodingAgentOperations.loadBundledModelCatalog();

		assertEquals(null, CodingAgentOperations.findCatalogModel(catalog, "openai", "does-not-exist"));
		IllegalArgumentException exception =
				assertThrows(IllegalArgumentException.class, () -> CodingAgentOperations.requireCatalogModel(catalog, "openai", "does-not-exist"));
		assertEquals("Unknown model: openai/does-not-exist", exception.getMessage());
	}
}
