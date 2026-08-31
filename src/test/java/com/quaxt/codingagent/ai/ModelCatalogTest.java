package com.quaxt.codingagent.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import com.quaxt.codingagent.CodingAgentOperations;
import com.quaxt.codingagent.ai.types.Compat;
import com.quaxt.codingagent.ai.types.ModelCost;
import com.quaxt.codingagent.ai.types.Model;

class ModelCatalogTest {
	@Test
	void loadsAllBundledCoreProviderCatalogs() {
		CodingAgentOperations catalog = loadBundledModelCatalog();

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
		CodingAgentOperations catalog = loadBundledModelCatalog();

		assertTrue(catalog.catalogModelsForProvider("github-copilot").size() > 25);
		assertEquals("openai-responses", catalog.requireCatalogModel("github-copilot", "gpt-5.6-terra").api);
		assertEquals("openai-completions", catalog.requireCatalogModel("github-copilot", "gemini-3.6-flash").api);
		assertEquals("anthropic-messages", catalog.requireCatalogModel("github-copilot", "claude-opus-5").api);
	}

	@Test
	void includesChatModelsForChatGptSubscriptions() {
		CodingAgentOperations catalog = loadBundledModelCatalog();

		List<Model> models = catalog.chatGptSubscriptionModels();
		List<String> ids = models.stream().map(model -> model.id).toList();
		assertTrue(ids.containsAll(List.of(
				"gpt-5-chat-latest",
				"gpt-5.2-chat-latest",
				"gpt-5.3-chat-latest")));
		assertTrue(models.stream().allMatch(model -> model.provider.equals("chatgpt")));
		assertTrue(models.stream().allMatch(model -> model.baseUrl.equals(
				CodingAgentOperations.CHATGPT_CODEX_API_BASE_URL.toString())));
		assertTrue(models.stream().allMatch(model -> model.cost == ModelCost.FREE));
	}

	@Test
	void coreProviderInitializationKeepsChatGptModelsIsolated() {
		CodingAgentOperations operations = CodingAgentOperations.INSTANCE;

		operations.initializeCoreProviders();

		List<String> chatGptIds = operations.coreProviderModels("chatgpt").stream()
				.map(model -> model.id)
				.toList();
		assertTrue(chatGptIds.contains("gpt-5.6-terra"));
		assertTrue(chatGptIds.contains("gpt-5.3-chat-latest"));
		assertTrue(operations.coreProviderModels("google").stream()
				.allMatch(model -> model.provider.equals("google")));
		assertTrue(operations.coreProviderModels("github-copilot").stream()
				.allMatch(model -> model.provider.equals("github-copilot")));
	}

	@Test
	void preservesAdaptiveThinkingCompatibility() {
		Model opus = loadBundledModelCatalog().requireCatalogModel("github-copilot", "claude-opus-5");

		Compat.AnthropicMessages compat = assertInstanceOf(Compat.AnthropicMessages.class, opus.compat);
		assertEquals(Boolean.TRUE, compat.forceAdaptiveThinking);
	}

	@Test
	void preservesGeneratedCatalogProperties() {
		CodingAgentOperations catalog = loadBundledModelCatalog();

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
		CodingAgentOperations catalog = loadBundledModelCatalog();

		assertEquals(null, catalog.findCatalogModel("openai", "does-not-exist"));
		IllegalArgumentException exception =
				assertThrows(IllegalArgumentException.class, () -> catalog.requireCatalogModel("openai", "does-not-exist"));
		assertEquals("Unknown model: openai/does-not-exist", exception.getMessage());
	}

	private static CodingAgentOperations loadBundledModelCatalog() {
		CodingAgentOperations.INSTANCE.loadBundledModelCatalog();
		return CodingAgentOperations.INSTANCE;
	}
}
