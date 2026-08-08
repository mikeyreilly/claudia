package com.quaxt.codingagent.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Ordered-character fuzzy matching with gap, boundary, and consecutive-match scoring. */
public final class FuzzyMatcher {
	private static final Pattern ALPHA_NUMERIC = Pattern.compile("^([a-z]+)([0-9]+)$");
	private static final Pattern NUMERIC_ALPHA = Pattern.compile("^([0-9]+)([a-z]+)$");

	private FuzzyMatcher() {}

	public record Match(boolean matches, double score) {}

	public static Match match(String query, String text) {
		String normalizedQuery = query.toLowerCase(Locale.ROOT);
		String normalizedText = text.toLowerCase(Locale.ROOT);
		Match primary = matchNormalized(normalizedQuery, normalizedText);
		if (primary.matches()) {
			return primary;
		}

		Matcher alphaNumeric = ALPHA_NUMERIC.matcher(normalizedQuery);
		Matcher numericAlpha = NUMERIC_ALPHA.matcher(normalizedQuery);
		String swapped = alphaNumeric.matches()
				? alphaNumeric.group(2) + alphaNumeric.group(1)
				: numericAlpha.matches() ? numericAlpha.group(2) + numericAlpha.group(1) : "";
		if (swapped.isEmpty()) {
			return primary;
		}
		Match swappedMatch = matchNormalized(swapped, normalizedText);
		return swappedMatch.matches() ? new Match(true, swappedMatch.score() + 5) : primary;
	}

	public static <T> List<T> filter(List<T> items, String query, Function<T, String> text) {
		String trimmed = query.trim();
		if (trimmed.isEmpty()) {
			return List.copyOf(items);
		}
		String[] tokens = trimmed.split("[\\s/]+");
		List<Scored<T>> scored = new ArrayList<>();
		for (int index = 0; index < items.size(); index++) {
			T item = items.get(index);
			double total = 0;
			boolean matches = true;
			for (String token : tokens) {
				Match match = match(token, text.apply(item));
				if (!match.matches()) {
					matches = false;
					break;
				}
				total += match.score();
			}
			if (matches) {
				scored.add(new Scored<>(item, total, index));
			}
		}
		scored.sort((left, right) -> {
			int byScore = Double.compare(left.score(), right.score());
			return byScore != 0 ? byScore : Integer.compare(left.index(), right.index());
		});
		return scored.stream().map(Scored::item).toList();
	}

	private static Match matchNormalized(String query, String text) {
		if (query.isEmpty()) {
			return new Match(true, 0);
		}
		if (query.length() > text.length()) {
			return new Match(false, 0);
		}
		int queryIndex = 0;
		int lastMatchIndex = -1;
		int consecutiveMatches = 0;
		double score = 0;
		for (int index = 0; index < text.length() && queryIndex < query.length(); index++) {
			if (text.charAt(index) != query.charAt(queryIndex)) {
				continue;
			}
			boolean wordBoundary = index == 0 || isBoundary(text.charAt(index - 1));
			if (lastMatchIndex == index - 1) {
				consecutiveMatches++;
				score -= consecutiveMatches * 5;
			} else {
				consecutiveMatches = 0;
				if (lastMatchIndex >= 0) {
					score += (index - lastMatchIndex - 1) * 2;
				}
			}
			if (wordBoundary) {
				score -= 10;
			}
			score += index * 0.1;
			lastMatchIndex = index;
			queryIndex++;
		}
		if (queryIndex < query.length()) {
			return new Match(false, 0);
		}
		if (query.equals(text)) {
			score -= 100;
		}
		return new Match(true, score);
	}

	private static boolean isBoundary(char value) {
		return Character.isWhitespace(value) || value == '-' || value == '_' || value == '.' || value == '/' || value == ':';
	}

	private record Scored<T>(T item, double score, int index) {}
}
