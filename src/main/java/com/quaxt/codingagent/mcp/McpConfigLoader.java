package com.quaxt.codingagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.quaxt.codingagent.ai.json.Json;

/**
 * Loads the MCP section from codingagent's settings file. Server definitions
 * use OpenCode's MCP shape, including {@code {env:NAME}} and
 * {@code {file:path}} substitutions.
 */
public final class McpConfigLoader {
	private static final Pattern ENVIRONMENT = Pattern.compile("\\{env:([^}]+)}");
	private static final Pattern FILE = Pattern.compile("\\{file:([^}]+)}");

	private final Path settingsPath;
	private final Map<String, String> environment;

	public McpConfigLoader(Path settingsPath, Map<String, String> environment) {
		this.settingsPath = settingsPath.toAbsolutePath().normalize();
		this.environment = Map.copyOf(environment);
	}

	public static McpConfiguration loadDefault() throws IOException {
		Path settingsPath = Path.of(System.getProperty("user.home"), ".codingagent", "settings.json");
		return new McpConfigLoader(settingsPath, System.getenv()).load();
	}

	/** Loads configured MCP servers, or an empty configuration when settings do not exist. */
	public McpConfiguration load() throws IOException {
		if (!Files.exists(settingsPath)) return McpConfiguration.empty();
		if (!Files.isRegularFile(settingsPath)) {
			throw new IOException("Settings path is not a file: " + settingsPath);
		}

		String text;
		try {
			text = Files.readString(settingsPath, StandardCharsets.UTF_8);
		} catch (IOException error) {
			throw new IOException("Failed to read settings file " + settingsPath + ": " + error.getMessage(), error);
		}

		JsonNode root;
		try {
			root = Json.MAPPER.readTree(substitute(text, settingsPath.getParent()));
		} catch (IOException error) {
			throw new IOException("Failed to parse settings file " + settingsPath + ": " + error.getMessage(), error);
		}
		if (root == null || !root.isObject()) {
			throw new IOException("Invalid settings file " + settingsPath + ": expected a JSON object");
		}

		JsonNode mcp = root.get("mcp");
		if (mcp == null || mcp.isNull()) {
			return new McpConfiguration(Map.of(), List.of(settingsPath));
		}
		if (!mcp.isObject()) {
			throw new IOException("Invalid mcp in " + settingsPath + ": expected an object");
		}

		LinkedHashMap<String, McpServerConfig> servers = new LinkedHashMap<>();
		for (var entry : mcp.properties()) {
			if (!(entry.getValue() instanceof ObjectNode server)) {
				throw new IOException("Invalid MCP server \"" + entry.getKey() + "\": expected an object");
			}
			servers.put(entry.getKey(), parseServer(entry.getKey(), server));
		}
		return new McpConfiguration(servers, List.of(settingsPath));
	}

	private String substitute(String input, Path settingsDirectory) throws IOException {
		Matcher envMatcher = ENVIRONMENT.matcher(input);
		StringBuffer environmentExpanded = new StringBuffer();
		while (envMatcher.find()) {
			envMatcher.appendReplacement(
					environmentExpanded,
					Matcher.quoteReplacement(environment.getOrDefault(envMatcher.group(1), "")));
		}
		envMatcher.appendTail(environmentExpanded);
		String text = environmentExpanded.toString();

		Matcher fileMatcher = FILE.matcher(text);
		StringBuilder output = new StringBuilder();
		int cursor = 0;
		while (fileMatcher.find()) {
			output.append(text, cursor, fileMatcher.start());
			String token = fileMatcher.group();
			String configuredPath = fileMatcher.group(1);
			Path file;
			if (configuredPath.equals("~")) file = Path.of(System.getProperty("user.home"));
			else if (configuredPath.startsWith("~/")) {
				file = Path.of(System.getProperty("user.home")).resolve(configuredPath.substring(2));
			} else {
				file = resolve(settingsDirectory, configuredPath);
			}
			String value;
			try {
				value = Files.readString(file, StandardCharsets.UTF_8).trim();
			} catch (IOException error) {
				throw new IOException("Bad file reference " + token + " in " + settingsPath + ": " + file, error);
			}
			String quoted = Json.MAPPER.writeValueAsString(value);
			output.append(quoted, 1, quoted.length() - 1);
			cursor = fileMatcher.end();
		}
		return output.append(text, cursor, text.length()).toString();
	}

	private static McpServerConfig parseServer(String name, ObjectNode value) throws IOException {
		if (name.isBlank()) throw invalid(name, "server name must not be blank");
		JsonNode typeNode = value.get("type");
		if (typeNode == null || !typeNode.isTextual()) throw invalid(name, "type must be a string");
		boolean enabled = optionalBoolean(name, value, "enabled", true);
		Long timeout = optionalPositiveLong(name, value, "timeout");
		return switch (typeNode.asText()) {
			case "local" -> {
				JsonNode commandNode = value.get("command");
				if (commandNode == null || !commandNode.isArray() || commandNode.isEmpty()) {
					throw invalid(name, "command must be a non-empty array of strings");
				}
				List<String> command = new ArrayList<>();
				for (JsonNode argument : commandNode) {
					if (!argument.isTextual() || argument.asText().isEmpty()) {
						throw invalid(name, "command must contain only non-empty strings");
					}
					command.add(argument.asText());
				}
				String cwd = optionalText(name, value, "cwd");
				Map<String, String> environment = stringMap(name, value, "environment");
				yield new McpServerConfig.Local(command, cwd, environment, enabled, timeout);
			}
			case "remote" -> {
				String rawUrl = requiredText(name, value, "url");
				URI url;
				try {
					url = new URI(rawUrl);
				} catch (URISyntaxException error) {
					throw invalid(name, "url is invalid: " + rawUrl);
				}
				if (!(url.getScheme() != null
						&& (url.getScheme().equalsIgnoreCase("http") || url.getScheme().equalsIgnoreCase("https"))
						&& url.getHost() != null
						&& url.getUserInfo() == null
						&& url.getFragment() == null)) {
					throw invalid(name, "url must be an absolute http or https URL without user info or a fragment");
				}
				JsonNode oauth = value.get("oauth");
				if (oauth != null && !oauth.isNull() && !oauth.isObject() && !oauth.isBoolean()) {
					throw invalid(name, "oauth must be an object or false");
				}
				if (oauth != null && oauth.isBoolean() && oauth.asBoolean()) {
					throw invalid(name, "oauth may be an object or false, not true");
				}
				if (oauth instanceof ObjectNode oauthObject) validateOAuth(name, oauthObject);
				yield new McpServerConfig.Remote(
						url, stringMap(name, value, "headers"), oauth, enabled, timeout);
			}
			default -> throw invalid(name, "type must be local or remote");
		};
	}

	private static void validateOAuth(String server, ObjectNode oauth) throws IOException {
		for (String field : List.of("clientId", "clientSecret", "scope")) {
			JsonNode value = oauth.get(field);
			if (value != null && !value.isNull() && (!value.isTextual() || value.asText().isBlank())) {
				throw invalid(server, "oauth." + field + " must be a non-empty string");
			}
		}
		JsonNode callbackPort = oauth.get("callbackPort");
		if (callbackPort != null && !callbackPort.isNull()
				&& (!callbackPort.isIntegralNumber()
						|| !callbackPort.canConvertToInt()
						|| callbackPort.asInt() < 1
						|| callbackPort.asInt() > 65_535)) {
			throw invalid(server, "oauth.callbackPort must be an integer from 1 to 65535");
		}
		JsonNode redirect = oauth.get("redirectUri");
		if (redirect != null && !redirect.isNull()) {
			if (!redirect.isTextual() || redirect.asText().isBlank()) {
				throw invalid(server, "oauth.redirectUri must be a non-empty string");
			}
			try {
				URI uri = new URI(redirect.asText());
				String host = uri.getHost();
				if (!uri.isAbsolute()
						|| host == null
						|| !uri.getScheme().equalsIgnoreCase("http")
						|| !(host.equalsIgnoreCase("localhost") || host.startsWith("127.") || host.equals("::1"))
						|| uri.getFragment() != null) {
					throw invalid(server, "oauth.redirectUri must be an HTTP loopback URL without a fragment");
				}
			} catch (URISyntaxException error) {
				throw invalid(server, "oauth.redirectUri is invalid: " + redirect.asText());
			}
		}
	}

	private static Map<String, String> stringMap(String server, ObjectNode value, String field) throws IOException {
		JsonNode node = value.get(field);
		if (node == null || node.isNull()) return Map.of();
		if (!node.isObject()) throw invalid(server, field + " must be an object of string values");
		LinkedHashMap<String, String> result = new LinkedHashMap<>();
		for (var entry : node.properties()) {
			if (!entry.getValue().isTextual()) {
				throw invalid(server, field + "." + entry.getKey() + " must be a string");
			}
			result.put(entry.getKey(), entry.getValue().asText());
		}
		return result;
	}

	private static String requiredText(String server, ObjectNode value, String field) throws IOException {
		String result = optionalText(server, value, field);
		if (result == null || result.isBlank()) throw invalid(server, field + " must be a non-empty string");
		return result;
	}

	private static String optionalText(String server, ObjectNode value, String field) throws IOException {
		JsonNode node = value.get(field);
		if (node == null || node.isNull()) return null;
		if (!node.isTextual()) throw invalid(server, field + " must be a string");
		return node.asText();
	}

	private static boolean optionalBoolean(String server, ObjectNode value, String field, boolean fallback)
			throws IOException {
		JsonNode node = value.get(field);
		if (node == null || node.isNull()) return fallback;
		if (!node.isBoolean()) throw invalid(server, field + " must be a boolean");
		return node.asBoolean();
	}

	private static Long optionalPositiveLong(String server, ObjectNode value, String field) throws IOException {
		JsonNode node = value.get(field);
		if (node == null || node.isNull()) return null;
		if (!node.isIntegralNumber() || !node.canConvertToLong() || node.asLong() <= 0) {
			throw invalid(server, field + " must be a positive integer");
		}
		return node.asLong();
	}

	private static Path resolve(Path base, String value) {
		Path path = Path.of(value);
		return (path.isAbsolute() ? path : base.resolve(path)).toAbsolutePath().normalize();
	}

	private static IOException invalid(String server, String message) {
		return new IOException("Invalid MCP server \"" + server + "\": " + message);
	}
}
