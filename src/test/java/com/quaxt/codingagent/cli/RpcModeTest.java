package com.quaxt.codingagent.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import com.quaxt.codingagent.ai.json.Json;

class RpcModeTest {
	@Test
	synchronized void respondsToJsonlStateCommand() throws Exception {
		var originalInput = System.in;
		var originalOutput = System.out;
		var captured = new ByteArrayOutputStream();
		try {
			System.setIn(new ByteArrayInputStream("{\"id\":\"state-1\",\"type\":\"get_state\"}\n".getBytes(StandardCharsets.UTF_8)));
			System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));

			assertEquals(0, Cli.run(new String[] {"--mode", "rpc", "--model", "anthropic/claude-haiku-4-5", "--no-session"}));
		} finally {
			System.setIn(originalInput);
			System.setOut(originalOutput);
		}

		var response = Json.MAPPER.readTree(captured.toString(StandardCharsets.UTF_8));
		assertEquals("response", response.path("type").asText());
		assertEquals("state-1", response.path("id").asText());
		assertTrue(response.path("success").asBoolean());
		assertEquals("anthropic", response.path("data").path("model").path("provider").asText());
	}
}
