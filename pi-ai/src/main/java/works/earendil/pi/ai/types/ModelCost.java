package works.earendil.pi.ai.types;

import java.util.List;

/**
 * Model pricing in $/million tokens, with optional request-wide tiers.
 * Mirrors ModelCost in packages/ai/src/types.ts.
 */
public record ModelCost(double input, double output, double cacheRead, double cacheWrite, List<Tier> tiers) {
	public ModelCost {
		tiers = tiers == null ? List.of() : List.copyOf(tiers);
	}

	public ModelCost(double input, double output, double cacheRead, double cacheWrite) {
		this(input, output, cacheRead, cacheWrite, List.of());
	}

	public static final ModelCost FREE = new ModelCost(0, 0, 0, 0);

	/** Tier applies when total input tokens exceed inputTokensAbove. */
	public record Tier(long inputTokensAbove, double input, double output, double cacheRead, double cacheWrite) {}
}
