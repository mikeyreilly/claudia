package com.quaxt.codingagent.ai;

import java.util.List;
import java.util.Map;
import com.quaxt.codingagent.ai.types.Model;

/**
 * Loaded snapshot of the embedded model catalogs for the Java port's supported
 * providers. The loading and lookup behavior lives in CodingAgentOperations.
 *
 * <p>The JSON files are copied from {@code packages/ai/src/providers/data/}.
 * They retain the source catalog's group-by-API layout and the loader flattens
 * the groups just as {@code flattenModelCatalog()} does in the TypeScript code.
 */
public final class ModelCatalog {
	public static final String RESOURCE_ROOT = "/quaxt/codingagent/ai/models/";
	public static final List<String> RESOURCE_NAMES =
			List.of("anthropic.json", "openai.json", "google.json", "github-copilot.json");

	public Map<String, Model> byProviderAndId;
	public Map<String, List<Model>> byProvider;

	public ModelCatalog(Map<String, Model> byProviderAndId, Map<String, List<Model>> byProvider) {
		this.byProviderAndId = byProviderAndId;
		this.byProvider = byProvider;
	}
}
