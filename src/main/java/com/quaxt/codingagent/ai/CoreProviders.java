package com.quaxt.codingagent.ai;

import java.util.Map;

/**
 * The provider set selected for the Java port: Anthropic, OpenAI Responses,
 * ChatGPT subscription, Google Generative AI, and GitHub Copilot. The
 * construction and lookup behavior lives in CodingAgentOperations.
 */
public final class CoreProviders {
	public ModelCatalog catalog;
	public Map<String, Provider> providers;

	public CoreProviders(ModelCatalog catalog, Map<String, Provider> providers) {
		this.catalog = catalog;
		this.providers = providers;
	}
}
