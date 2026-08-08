package works.earendil.pi.ai;

import java.util.ArrayList;
import java.util.List;
import works.earendil.pi.ai.types.Model;
import works.earendil.pi.ai.types.ModelCost;
import works.earendil.pi.ai.types.ThinkingLevel;
import works.earendil.pi.ai.types.Usage;

/**
 * Model helpers: cost calculation and thinking level clamping.
 * Ports calculateCost/getSupportedThinkingLevels/clampThinkingLevel from
 * packages/ai/src/models.ts.
 */
public final class Models {
	private static final ThinkingLevel[] EXTENDED_THINKING_LEVELS = ThinkingLevel.values();

	private Models() {}

	/** Computes and stores cost on usage.cost, returning it. */
	public static Usage.Cost calculateCost(Model model, Usage usage) {
		long inputTokens = usage.input + usage.cacheRead + usage.cacheWrite;
		double rateInput = model.cost.input();
		double rateOutput = model.cost.output();
		double rateCacheRead = model.cost.cacheRead();
		double rateCacheWrite = model.cost.cacheWrite();
		long matchedThreshold = -1;
		for (ModelCost.Tier tier : model.cost.tiers()) {
			if (inputTokens > tier.inputTokensAbove() && tier.inputTokensAbove() > matchedThreshold) {
				rateInput = tier.input();
				rateOutput = tier.output();
				rateCacheRead = tier.cacheRead();
				rateCacheWrite = tier.cacheWrite();
				matchedThreshold = tier.inputTokensAbove();
			}
		}

		// Anthropic charges 2x base input for 1h cache writes.
		long longWrite = usage.cacheWrite1h != null ? usage.cacheWrite1h : 0;
		long shortWrite = usage.cacheWrite - longWrite;
		usage.cost.input = (rateInput / 1_000_000) * usage.input;
		usage.cost.output = (rateOutput / 1_000_000) * usage.output;
		usage.cost.cacheRead = (rateCacheRead / 1_000_000) * usage.cacheRead;
		usage.cost.cacheWrite = (rateCacheWrite * shortWrite + rateInput * 2 * longWrite) / 1_000_000;
		usage.cost.total = usage.cost.input + usage.cost.output + usage.cost.cacheRead + usage.cost.cacheWrite;
		return usage.cost;
	}

	public static List<ThinkingLevel> getSupportedThinkingLevels(Model model) {
		if (!model.reasoning) {
			return List.of(ThinkingLevel.OFF);
		}
		List<ThinkingLevel> supported = new ArrayList<>();
		for (ThinkingLevel level : EXTENDED_THINKING_LEVELS) {
			boolean hasKey = model.thinkingLevelMap != null && model.thinkingLevelMap.containsKey(level);
			String mapped = hasKey ? model.thinkingLevelMap.get(level) : null;
			if (hasKey && mapped == null) {
				continue; // explicit null marks the level unsupported
			}
			if (level == ThinkingLevel.XHIGH || level == ThinkingLevel.MAX) {
				if (!hasKey) {
					continue; // xhigh/max require an explicit mapping
				}
			}
			supported.add(level);
		}
		return supported;
	}

	public static ThinkingLevel clampThinkingLevel(Model model, ThinkingLevel level) {
		List<ThinkingLevel> available = getSupportedThinkingLevels(model);
		if (available.contains(level)) {
			return level;
		}
		int requestedIndex = level.ordinal();
		for (int i = requestedIndex; i < EXTENDED_THINKING_LEVELS.length; i++) {
			if (available.contains(EXTENDED_THINKING_LEVELS[i])) {
				return EXTENDED_THINKING_LEVELS[i];
			}
		}
		for (int i = requestedIndex - 1; i >= 0; i--) {
			if (available.contains(EXTENDED_THINKING_LEVELS[i])) {
				return EXTENDED_THINKING_LEVELS[i];
			}
		}
		return available.isEmpty() ? ThinkingLevel.OFF : available.getFirst();
	}

	/**
	 * Resolves a requested thinking level to the provider's wire value. Null
	 * disables reasoning after model-specific clamping.
	 */
	public static String providerThinkingLevel(Model model, ThinkingLevel level) {
		if (level == null || level == ThinkingLevel.OFF) {
			return null;
		}
		ThinkingLevel clamped = clampThinkingLevel(model, level);
		if (clamped == ThinkingLevel.OFF) {
			return null;
		}
		if (model.thinkingLevelMap != null && model.thinkingLevelMap.containsKey(clamped)) {
			return model.thinkingLevelMap.get(clamped);
		}
		return clamped.wire();
	}

	public static boolean modelsAreEqual(Model a, Model b) {
		if (a == null || b == null) {
			return false;
		}
		return a.id.equals(b.id) && a.provider.equals(b.provider);
	}
}
