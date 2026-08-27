package com.quaxt.codingagent.ai.types;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A model served by a provider. Mirrors Model in packages/ai/src/types.ts.
 * `api` and `provider` are open string ids (e.g. "anthropic-messages", "openai").
 * thinkingLevelMap maps a level to a provider-specific value; a mapping to null
 * marks the level unsupported. Missing keys use provider defaults.
 */
public final class Model {
	public String id;
	public String name;
	public String api;
	public String provider;
	public String baseUrl;
	public boolean reasoning;
	public Map<ThinkingLevel, String> thinkingLevelMap;
	/** Accepted input modalities: "text", "image". */
	public List<String> input = new ArrayList<>(List.of("text"));
	public ModelCost cost = ModelCost.FREE;
	public long contextWindow;
	public long maxTokens;
	public Map<String, String> headers = new LinkedHashMap<>();
	/** API-specific compatibility overrides; null means auto-detect/defaults. */
	public Compat compat;

	public Model() {}

	@Override
	public String toString() {
		return provider + "/" + id;
	}
}
