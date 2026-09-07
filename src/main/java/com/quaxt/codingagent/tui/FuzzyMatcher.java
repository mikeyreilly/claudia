package com.quaxt.codingagent.tui;

import java.util.regex.Pattern;

/**
 * Data for ordered-character fuzzy matching: the digit/letter swap patterns and
 * the score carriers. The gap, boundary, and consecutive-match scoring lives in
 * CodingAgentCli.
 */
public final class FuzzyMatcher {
	public static final Pattern ALPHA_NUMERIC = Pattern.compile("^([a-z]+)([0-9]+)$");
	public static final Pattern NUMERIC_ALPHA = Pattern.compile("^([0-9]+)([a-z]+)$");

	public FuzzyMatcher() {}

	/** One match outcome: whether the query matched and its (lower is better) score. */
	public static final class Match {
		public boolean matches;
		public double score;

		public Match(boolean matches, double score) {
			this.matches = matches;
			this.score = score;
		}
	}

	/** One scored candidate, retaining its input position for stable ordering. */
	public static final class Scored<T> {
		public T item;
		public double score;
		public int index;

		public Scored(T item, double score, int index) {
			this.item = item;
			this.score = score;
			this.index = index;
		}
	}
}
