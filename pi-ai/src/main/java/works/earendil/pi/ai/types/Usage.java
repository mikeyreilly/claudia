package works.earendil.pi.ai.types;

/**
 * Token usage and cost for one assistant message.
 * Mutable: providers accumulate usage while streaming.
 * `reasoning` is a subset of `output`; null when the provider reports no breakdown.
 */
public final class Usage {
	public long input;
	public long output;
	public long cacheRead;
	public long cacheWrite;
	/** Subset of cacheWrite written with 1h retention (Anthropic only); null if unreported. */
	public Long cacheWrite1h;
	public Long reasoning;
	public long totalTokens;
	public final Cost cost = new Cost();

	public static final class Cost {
		public double input;
		public double output;
		public double cacheRead;
		public double cacheWrite;
		public double total;
	}
}
