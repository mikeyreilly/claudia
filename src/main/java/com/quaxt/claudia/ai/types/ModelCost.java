package com.quaxt.claudia.ai.types;

import java.util.List;
import java.util.Objects;

/**
 * Model pricing in $/million tokens, with optional request-wide tiers.
 * Mirrors ModelCost in packages/ai/src/types.ts.
 */
public final class ModelCost {
	public static final ModelCost FREE = new ModelCost(0, 0, 0, 0, List.of());

	public double input;
	public double output;
	public double cacheRead;
	public double cacheWrite;
	public List<Tier> tiers;

	public ModelCost(double input, double output, double cacheRead, double cacheWrite, List<Tier> tiers) {
		this.input = input;
		this.output = output;
		this.cacheRead = cacheRead;
		this.cacheWrite = cacheWrite;
		this.tiers = tiers;
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof ModelCost that
				&& Double.compare(input, that.input) == 0
				&& Double.compare(output, that.output) == 0
				&& Double.compare(cacheRead, that.cacheRead) == 0
				&& Double.compare(cacheWrite, that.cacheWrite) == 0
				&& Objects.equals(tiers, that.tiers);
	}

	@Override
	public int hashCode() {
		return Objects.hash(input, output, cacheRead, cacheWrite, tiers);
	}

	@Override
	public String toString() {
		return "ModelCost[input=" + input + ", output=" + output
				+ ", cacheRead=" + cacheRead + ", cacheWrite=" + cacheWrite
				+ ", tiers=" + tiers + "]";
	}

	/** Tier applies when total input tokens exceed inputTokensAbove. */
	public static final class Tier {
		public long inputTokensAbove;
		public double input;
		public double output;
		public double cacheRead;
		public double cacheWrite;

		public Tier(long inputTokensAbove, double input, double output, double cacheRead, double cacheWrite) {
			this.inputTokensAbove = inputTokensAbove;
			this.input = input;
			this.output = output;
			this.cacheRead = cacheRead;
			this.cacheWrite = cacheWrite;
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof Tier that
					&& inputTokensAbove == that.inputTokensAbove
					&& Double.compare(input, that.input) == 0
					&& Double.compare(output, that.output) == 0
					&& Double.compare(cacheRead, that.cacheRead) == 0
					&& Double.compare(cacheWrite, that.cacheWrite) == 0;
		}

		@Override
		public int hashCode() {
			return Objects.hash(inputTokensAbove, input, output, cacheRead, cacheWrite);
		}

		@Override
		public String toString() {
			return "Tier[inputTokensAbove=" + inputTokensAbove + ", input=" + input
					+ ", output=" + output + ", cacheRead=" + cacheRead
					+ ", cacheWrite=" + cacheWrite + "]";
		}
	}
}
