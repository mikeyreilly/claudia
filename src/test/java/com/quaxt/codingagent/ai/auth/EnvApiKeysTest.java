package com.quaxt.codingagent.ai.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EnvApiKeysTest {
	@Test
	void resolvesCoreProviderApiKeys() {
		assertEquals("key", EnvApiKeys.resolve("openai", Map.of("OPENAI_API_KEY", "key")).orElseThrow());
		assertEquals("gemini", EnvApiKeys.resolve("google", Map.of("GEMINI_API_KEY", "gemini")).orElseThrow());
		assertEquals(
				"google",
				EnvApiKeys.resolve("google", Map.of("GOOGLE_API_KEY", "google")).orElseThrow());
	}

	@Test
	void skipsAnthropicBearerAuthTokenForApiKeyResolution() {
		Map<String, String> environment = Map.of(
				"ANTHROPIC_AUTH_TOKEN", "bearer",
				"ANTHROPIC_API_KEY", "key");

		assertEquals(List.of("ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_API_KEY"), EnvApiKeys.find("anthropic", environment));
		assertEquals("key", EnvApiKeys.resolve("anthropic", environment).orElseThrow());
		assertTrue(EnvApiKeys.resolve("unknown", environment).isEmpty());
	}
}
