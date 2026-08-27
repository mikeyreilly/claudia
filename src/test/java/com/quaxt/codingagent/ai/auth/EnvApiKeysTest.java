package com.quaxt.codingagent.ai.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.quaxt.codingagent.CodingAgentOperations;

class EnvApiKeysTest {
	@Test
	void resolvesCoreProviderApiKeys() {
		assertEquals("key", CodingAgentOperations.resolveApiKey("openai", Map.of("OPENAI_API_KEY", "key")).orElseThrow());
		assertEquals("gemini", CodingAgentOperations.resolveApiKey("google", Map.of("GEMINI_API_KEY", "gemini")).orElseThrow());
		assertEquals(
				"google",
				CodingAgentOperations.resolveApiKey("google", Map.of("GOOGLE_API_KEY", "google")).orElseThrow());
	}

	@Test
	void skipsAnthropicBearerAuthTokenForApiKeyResolution() {
		Map<String, String> environment = Map.of(
				"ANTHROPIC_AUTH_TOKEN", "bearer",
				"ANTHROPIC_API_KEY", "key");

		assertEquals(List.of("ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_API_KEY"), CodingAgentOperations.findApiKeyEnvVars("anthropic", environment));
		assertEquals("key", CodingAgentOperations.resolveApiKey("anthropic", environment).orElseThrow());
		assertTrue(CodingAgentOperations.resolveApiKey("unknown", environment).isEmpty());
	}
}
