package com.quaxt.claudia.ai.auth;

import java.util.List;
import java.util.Map;

/**
 * Provider API key environment-variable configuration for the Java core set.
 * The lookup behavior lives in ClaudiaOperations.
 */
public final class EnvApiKeys {
	public static final String ANTHROPIC_AUTH_TOKEN_ENV = "ANTHROPIC_AUTH_TOKEN";
	public static final String ANTHROPIC_OAUTH_TOKEN_ENV = "ANTHROPIC_OAUTH_TOKEN";
	public static final String ANTHROPIC_API_KEY_ENV = "ANTHROPIC_API_KEY";

	public static final Map<String, List<String>> API_KEY_ENV_VARS = Map.of(
			"anthropic", List.of(ANTHROPIC_AUTH_TOKEN_ENV, ANTHROPIC_OAUTH_TOKEN_ENV, ANTHROPIC_API_KEY_ENV),
			"openai", List.of("OPENAI_API_KEY"),
			"google", List.of("GEMINI_API_KEY", "GOOGLE_API_KEY"),
			"openai-compatible", List.of("OPENAI_API_KEY"));

	public EnvApiKeys() {}
}
