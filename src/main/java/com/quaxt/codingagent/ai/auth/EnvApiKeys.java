package com.quaxt.codingagent.ai.auth;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Provider API key environment-variable resolution for the Java core set. */
public final class EnvApiKeys {
	public static final String ANTHROPIC_AUTH_TOKEN_ENV = "ANTHROPIC_AUTH_TOKEN";
	public static final String ANTHROPIC_OAUTH_TOKEN_ENV = "ANTHROPIC_OAUTH_TOKEN";
	public static final String ANTHROPIC_API_KEY_ENV = "ANTHROPIC_API_KEY";

	private static final Map<String, List<String>> API_KEY_ENV_VARS = Map.of(
			"anthropic", List.of(ANTHROPIC_AUTH_TOKEN_ENV, ANTHROPIC_OAUTH_TOKEN_ENV, ANTHROPIC_API_KEY_ENV),
			"openai", List.of("OPENAI_API_KEY"),
			"google", List.of("GEMINI_API_KEY", "GOOGLE_API_KEY"),
			"openai-compatible", List.of("OPENAI_API_KEY"));

	private EnvApiKeys() {}

	/** Returns configured key environment-variable names in provider priority order. */
	public static List<String> find(String provider, Map<String, String> environment) {
		List<String> names = API_KEY_ENV_VARS.get(provider);
		if (names == null) {
			return List.of();
		}
		return names.stream().filter(name -> nonBlank(environment.get(name))).toList();
	}

	/**
	 * Resolves a key value from the given environment. Anthropic's
	 * ANTHROPIC_AUTH_TOKEN is intentionally omitted because it requires bearer
	 * authorization rather than x-api-key; its provider adapter handles it.
	 */
	public static Optional<String> resolve(String provider, Map<String, String> environment) {
		for (String name : find(provider, environment)) {
			if (provider.equals("anthropic") && name.equals(ANTHROPIC_AUTH_TOKEN_ENV)) {
				continue;
			}
			return Optional.of(environment.get(name));
		}
		return Optional.empty();
	}

	public static Optional<String> resolveSystem(String provider) {
		return resolve(provider, System.getenv());
	}

	private static boolean nonBlank(String value) {
		return value != null && !value.isBlank();
	}
}
