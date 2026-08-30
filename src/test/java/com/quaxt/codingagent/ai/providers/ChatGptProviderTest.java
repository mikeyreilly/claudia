package com.quaxt.codingagent.ai.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quaxt.codingagent.ai.StreamOptions;
import org.junit.jupiter.api.Test;
import com.quaxt.codingagent.CodingAgentOperations;

class ChatGptProviderTest {
	@Test
	void configuresCodexHeadersAndSessionAffinity() {
		StreamOptions options = new StreamOptions();
		options.sessionId = "session-1";

		CodingAgentOperations.INSTANCE.configureCodexRequest(
				options, new CodingAgentOperations.ChatGptToken("access-token", "account-1"));

		assertEquals("access-token", options.apiKey);
		assertEquals(CodingAgentOperations.CHATGPT_CODEX_API_BASE_URL.toString(), options.baseUrl);
		assertEquals("account-1", options.headers.get("ChatGPT-Account-Id"));
		assertEquals("pi-java", options.headers.get("originator"));
		assertTrue(options.headers.get("User-Agent").startsWith("pi-java ("));
		assertEquals("text/event-stream", options.headers.get("Accept"));
		assertEquals("responses=experimental", options.headers.get("OpenAI-Beta"));
		assertEquals("session-1", options.headers.get("session-id"));
		assertEquals("session-1", options.headers.get("x-client-request-id"));
	}
}
