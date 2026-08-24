package com.quaxt.codingagent.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.quaxt.codingagent.ai.json.Json;
import com.quaxt.codingagent.ai.types.Compat;
import com.quaxt.codingagent.ai.types.Model;
import com.quaxt.codingagent.ai.types.ModelCost;
import com.quaxt.codingagent.ai.types.ThinkingLevel;

/**
 * Loads the embedded model snapshots for the Java port's supported providers.
 *
 * <p>The JSON files are copied from {@code packages/ai/src/providers/data/}.
 * They retain the source catalog's group-by-API layout and this loader flattens
 * the groups just as {@code flattenModelCatalog()} does in the TypeScript code.
 */
public final class ModelCatalog {
	private static final String RESOURCE_ROOT = "/quaxt/codingagent/ai/models/";
	private static final List<String> RESOURCE_NAMES =
			List.of("anthropic.json", "openai.json", "google.json", "github-copilot.json");

	private final Map<String, Model> byProviderAndId;
	private final Map<String, List<Model>> byProvider;

	private ModelCatalog(Map<String, Model> byProviderAndId, Map<String, List<Model>> byProvider) {
		this.byProviderAndId = Map.copyOf(byProviderAndId);
		Map<String, List<Model>> immutableByProvider = new LinkedHashMap<>();
		for (Map.Entry<String, List<Model>> entry : byProvider.entrySet()) {
			immutableByProvider.put(entry.getKey(), List.copyOf(entry.getValue()));
		}
		this.byProvider = Map.copyOf(immutableByProvider);
	}

	/** Loads the bundled provider model snapshots. */
	public static ModelCatalog loadBundled() {
		Map<String, Model> models = new LinkedHashMap<>();
		Map<String, List<Model>> providers = new LinkedHashMap<>();
		for (String resourceName : RESOURCE_NAMES) {
			loadResource(resourceName, models, providers);
		}
		return new ModelCatalog(models, providers);
	}

	/**
	 * Finds a model by its provider and id, returning null if it is absent.
	 * Use {@link #require(String, String)} when absence is a user-facing error.
	 */
	public Model find(String provider, String id) {
		return byProviderAndId.get(key(provider, id));
	}

	/** Finds a model or throws a clear error that includes the provider/id pair. */
	public Model require(String provider, String id) {
		Model model = find(provider, id);
		if (model == null) {
			throw new IllegalArgumentException("Unknown model: " + provider + "/" + id);
		}
		return model;
	}

	/** Returns every bundled model from a provider, preserving source-file order. */
	public List<Model> forProvider(String provider) {
		return byProvider.getOrDefault(provider, List.of());
	}

	/** Returns every bundled model, preserving resource and source-file order. */
	public List<Model> all() {
		return List.copyOf(byProviderAndId.values());
	}

	private static void loadResource(
			String resourceName, Map<String, Model> models, Map<String, List<Model>> providers) {
		try (InputStream input = ModelCatalog.class.getResourceAsStream(RESOURCE_ROOT + resourceName)) {
			if (input == null) {
				throw new IllegalStateException("Missing bundled model catalog resource: " + resourceName);
			}
			JsonNode root = Json.MAPPER.readTree(input);
			for (Iterator<JsonNode> groups = root.elements(); groups.hasNext();) {
				JsonNode group = groups.next();
				for (Iterator<JsonNode> entries = group.elements(); entries.hasNext();) {
					Model model = parse(entries.next());
					String modelKey = key(model.provider, model.id);
					if (models.putIfAbsent(modelKey, model) != null) {
						throw new IllegalStateException("Duplicate bundled model: " + modelKey);
					}
					providers.computeIfAbsent(model.provider, ignored -> new ArrayList<>()).add(model);
				}
			}
		} catch (IOException e) {
			throw new IllegalStateException("Unable to load bundled model catalog: " + resourceName, e);
		}
	}

	private static Model parse(JsonNode node) {
		Model.Builder builder = Model.builder()
				.id(requiredText(node, "id"))
				.name(requiredText(node, "name"))
				.api(requiredText(node, "api"))
				.provider(requiredText(node, "provider"))
				.baseUrl(requiredText(node, "baseUrl"))
				.reasoning(node.path("reasoning").asBoolean())
				.input(textList(node.path("input")))
				.cost(parseCost(node.path("cost")))
				.contextWindow(node.path("contextWindow").asLong())
				.maxTokens(node.path("maxTokens").asLong());

		JsonNode thinkingLevelMap = node.get("thinkingLevelMap");
		if (thinkingLevelMap != null && thinkingLevelMap.isObject()) {
			Map<ThinkingLevel, String> levels = new EnumMap<>(ThinkingLevel.class);
			for (Map.Entry<String, JsonNode> field : thinkingLevelMap.properties()) {
				levels.put(ThinkingLevel.fromWire(field.getKey()), field.getValue().isNull() ? null : field.getValue().asText());
			}
			builder.thinkingLevelMap(levels);
		}

		JsonNode headers = node.get("headers");
		if (headers != null && headers.isObject()) {
			Map<String, String> parsedHeaders = new LinkedHashMap<>();
			for (Map.Entry<String, JsonNode> field : headers.properties()) {
				parsedHeaders.put(field.getKey(), field.getValue().asText());
			}
			builder.headers(parsedHeaders);
		}

		Compat compat = parseCompat(node);
		if (compat != null) {
			builder.compat(compat);
		}
		return builder.build();
	}

	private static Compat parseCompat(JsonNode model) {
		JsonNode compat = model.get("compat");
		if (compat == null || !compat.isObject()) {
			return null;
		}
		if (!model.path("api").asText().equals("anthropic-messages")) {
			return null;
		}
		JsonNode adaptiveThinking = compat.get("forceAdaptiveThinking");
		if (adaptiveThinking == null || !adaptiveThinking.isBoolean()) {
			return null;
		}
		return new Compat.AnthropicMessages(null, null, null, null, adaptiveThinking.booleanValue());
	}

	private static ModelCost parseCost(JsonNode node) {
		List<ModelCost.Tier> tiers = new ArrayList<>();
		JsonNode tierNodes = node.path("tiers");
		if (tierNodes.isArray()) {
			for (JsonNode tier : tierNodes) {
				tiers.add(new ModelCost.Tier(
						tier.path("inputTokensAbove").asLong(),
						tier.path("input").asDouble(),
						tier.path("output").asDouble(),
						tier.path("cacheRead").asDouble(),
						tier.path("cacheWrite").asDouble()));
			}
		}
		return new ModelCost(
				node.path("input").asDouble(),
				node.path("output").asDouble(),
				node.path("cacheRead").asDouble(),
				node.path("cacheWrite").asDouble(),
				tiers);
	}

	private static List<String> textList(JsonNode node) {
		List<String> values = new ArrayList<>();
		for (JsonNode value : node) {
			values.add(value.asText());
		}
		return values;
	}

	private static String requiredText(JsonNode node, String field) {
		JsonNode value = node.get(field);
		if (value == null || !value.isTextual()) {
			throw new IllegalStateException("Bundled model is missing text field: " + field);
		}
		return value.asText();
	}

	private static String key(String provider, String id) {
		return Objects.requireNonNull(provider) + "\u0000" + Objects.requireNonNull(id);
	}
}
