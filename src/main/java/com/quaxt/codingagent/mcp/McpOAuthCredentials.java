package com.quaxt.codingagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.quaxt.codingagent.ai.json.Json;

/** Reads reusable OAuth bearer tokens from OpenCode's MCP credential store. */
final class McpOAuthCredentials {
	private McpOAuthCredentials() {}

	static McpServerConfig.Remote apply(String name, McpServerConfig.Remote config) {
		if (oauthDisabled(config.oauth()) || hasAuthorization(config.headers())) return config;
		String accessToken = find(name, config.url().toString());
		if (accessToken == null) return config;
		LinkedHashMap<String, String> headers = new LinkedHashMap<>(config.headers());
		headers.put("Authorization", "Bearer " + accessToken);
		return new McpServerConfig.Remote(
				config.url(), headers, config.oauth(), config.enabled(), config.timeoutMillis());
	}

	private static String find(String name, String serverUrl) {
		Path home = Path.of(System.getProperty("user.home"));
		String xdg = System.getenv("XDG_DATA_HOME");
		List<Path> candidates = List.of(
				xdg == null || xdg.isBlank()
						? home.resolve(".local/share/opencode/mcp-auth.json")
						: Path.of(xdg).resolve("opencode/mcp-auth.json"),
				home.resolve("Library/Application Support/opencode/mcp-auth.json"));
		for (Path candidate : candidates) {
			String token = read(candidate, name, serverUrl);
			if (token != null) return token;
		}
		return null;
	}

	private static String read(Path path, String name, String serverUrl) {
		if (!Files.isRegularFile(path)) return null;
		try {
			JsonNode entry = Json.MAPPER.readTree(path.toFile()).path(name);
			if (!entry.isObject()) return null;
			// Current OpenCode binds stored credentials to the exact configured URL
			// so a renamed server cannot receive another endpoint's bearer token.
			if (!entry.path("serverUrl").isTextual() || !entry.path("serverUrl").asText().equals(serverUrl)) return null;
			JsonNode tokens = entry.path("tokens");
			if (!tokens.path("accessToken").isTextual() || tokens.path("accessToken").asText().isBlank()) return null;
			if (tokens.path("expiresAt").isNumber()
					&& tokens.path("expiresAt").asDouble() <= Instant.now().toEpochMilli() / 1_000.0) {
				return null;
			}
			return tokens.path("accessToken").asText();
		} catch (IOException | RuntimeException ignored) {
			return null;
		}
	}

	private static boolean oauthDisabled(JsonNode oauth) {
		return oauth != null && oauth.isBoolean() && !oauth.asBoolean();
	}

	private static boolean hasAuthorization(Map<String, String> headers) {
		return headers.keySet().stream().anyMatch(name -> name.toLowerCase(Locale.ROOT).equals("authorization"));
	}
}
