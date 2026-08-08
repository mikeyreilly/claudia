package com.quaxt.codingagent.mcp;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.quaxt.codingagent.ai.json.Json;

/**
 * Loads the MCP section from the same config files and shape used by OpenCode.
 * JSON comments, trailing commas, {@code {env:NAME}}, and {@code {file:path}}
 * substitutions are supported.
 */
public final class McpConfigLoader {
	private static final Pattern ENVIRONMENT = Pattern.compile("\\{env:([^}]+)}");
	private static final Pattern FILE = Pattern.compile("\\{file:([^}]+)}");
	private static final ObjectMapper JSONC = new ObjectMapper(JsonFactory.builder()
			.enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
			.enable(JsonReadFeature.ALLOW_YAML_COMMENTS)
			.enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
			.build());

	private final Path home;
	private final Map<String, String> environment;

	public McpConfigLoader(Path home, Map<String, String> environment) {
		this.home = home.toAbsolutePath().normalize();
		this.environment = Map.copyOf(environment);
	}

	public static McpConfiguration loadDefault(Path workspace) throws IOException {
		return new McpConfigLoader(Path.of(System.getProperty("user.home")), System.getenv()).load(workspace);
	}

	/** Loads global configuration first and increasingly local configuration last. */
	public McpConfiguration load(Path workspace) throws IOException {
		Path base = workspace.toAbsolutePath().normalize();
		List<Path> candidates = discover(base);
		LinkedHashMap<String, ObjectNode> merged = new LinkedHashMap<>();
		List<Path> sources = new ArrayList<>();
		Set<Path> loaded = new HashSet<>();

		for (Path candidate : candidates) {
			Path normalized = candidate.toAbsolutePath().normalize();
			if (!loaded.add(normalized) || !Files.isRegularFile(normalized)) continue;
			mergeFile(normalized, merged);
			sources.add(normalized);
		}

		String content = environment.get("OPENCODE_CONFIG_CONTENT");
		if (content != null && !content.isBlank()) {
			mergeText(content, base, "OPENCODE_CONFIG_CONTENT", merged);
		}

		LinkedHashMap<String, McpServerConfig> servers = new LinkedHashMap<>();
		for (var entry : merged.entrySet()) {
			McpServerConfig parsed = parseServer(entry.getKey(), entry.getValue());
			// OpenCode permits an enabled-only override for an organization-provided
			// definition. With no underlying definition there is nothing to connect.
			if (parsed != null) servers.put(entry.getKey(), parsed);
		}
		return new McpConfiguration(servers, sources);
	}

	private List<Path> discover(Path workspace) {
		List<Path> result = new ArrayList<>();
		Path xdg = valuePath("XDG_CONFIG_HOME", home.resolve(".config"));
		Path global = xdg.resolve("opencode");
		result.add(global.resolve("config.json"));
		result.add(global.resolve("opencode.json"));
		result.add(global.resolve("opencode.jsonc"));

		String explicit = environment.get("OPENCODE_CONFIG");
		if (explicit != null && !explicit.isBlank()) {
			result.add(resolve(workspace, explicit));
		}

		if (!truthy(environment.get("OPENCODE_DISABLE_PROJECT_CONFIG"))) {
			List<Path> ancestors = new ArrayList<>();
			for (Path current = workspace; current != null; current = current.getParent()) ancestors.add(current);
			Collections.reverse(ancestors);
			for (Path directory : ancestors) {
				// OpenCode's JSONC file has precedence when both forms exist.
				result.add(directory.resolve("opencode.json"));
				result.add(directory.resolve("opencode.jsonc"));
				result.add(directory.resolve(".opencode").resolve("opencode.json"));
				result.add(directory.resolve(".opencode").resolve("opencode.jsonc"));
			}
		}

		String configDirectory = environment.get("OPENCODE_CONFIG_DIR");
		if (configDirectory != null && !configDirectory.isBlank()) {
			Path directory = resolve(workspace, configDirectory);
			result.add(directory.resolve("opencode.json"));
			result.add(directory.resolve("opencode.jsonc"));
		}
		return result;
	}

	private void mergeFile(Path source, LinkedHashMap<String, ObjectNode> merged) throws IOException {
		String text;
		try {
			text = Files.readString(source, StandardCharsets.UTF_8);
		} catch (IOException error) {
			throw new IOException("Failed to read OpenCode config " + source + ": " + error.getMessage(), error);
		}
		mergeText(text, source.getParent(), source.toString(), merged);
	}

	private void mergeText(
			String text, Path sourceDirectory, String source, LinkedHashMap<String, ObjectNode> merged)
			throws IOException {
		String expanded = substitute(text, sourceDirectory, source);
		JsonNode root;
		try {
			root = JSONC.readTree(expanded);
		} catch (IOException error) {
			throw new IOException("Failed to parse OpenCode config " + source + ": " + error.getMessage(), error);
		}
		if (root == null || !root.isObject()) {
			throw new IOException("Invalid OpenCode config " + source + ": expected a JSON object");
		}
		JsonNode mcp = root.get("mcp");
		if (mcp == null || mcp.isNull()) return;
		if (!mcp.isObject()) throw new IOException("Invalid mcp in " + source + ": expected an object");
		var entries = mcp.properties().iterator();
		while (entries.hasNext()) {
			var entry = entries.next();
			if (!entry.getValue().isObject()) {
				throw new IOException("Invalid MCP server \"" + entry.getKey() + "\" in " + source + ": expected an object");
			}
			ObjectNode incoming = (ObjectNode) entry.getValue();
			ObjectNode current = merged.get(entry.getKey());
			if (current == null) merged.put(entry.getKey(), incoming.deepCopy());
			else mergeObject(current, incoming);
		}
	}

	private String substitute(String input, Path sourceDirectory, String source) throws IOException {
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
			int lineStart = text.lastIndexOf('\n', fileMatcher.start() - 1) + 1;
			String prefix = text.substring(lineStart, fileMatcher.start()).stripLeading();
			if (prefix.startsWith("//")) {
				output.append(token);
				cursor = fileMatcher.end();
				continue;
			}
			String configuredPath = fileMatcher.group(1);
			Path file;
			if (configuredPath.equals("~")) file = home;
			else if (configuredPath.startsWith("~/")) file = home.resolve(configuredPath.substring(2));
			else file = resolve(sourceDirectory, configuredPath);
			String value;
			try {
				value = Files.readString(file, StandardCharsets.UTF_8).trim();
			} catch (IOException error) {
				throw new IOException("Bad file reference " + token + " in " + source + ": " + file, error);
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
		if (typeNode == null) return null;
		if (!typeNode.isTextual()) throw invalid(name, "type must be a string");
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
						&& url.getHost() != null)) {
					throw invalid(name, "url must be an absolute http or https URL");
				}
				JsonNode oauth = value.get("oauth");
				if (oauth != null && !oauth.isNull() && !oauth.isObject() && !oauth.isBoolean()) {
					throw invalid(name, "oauth must be an object or false");
				}
				if (oauth != null && oauth.isBoolean() && oauth.asBoolean()) {
					throw invalid(name, "oauth may be an object or false, not true");
				}
				yield new McpServerConfig.Remote(
						url, stringMap(name, value, "headers"), oauth, enabled, timeout);
			}
			default -> throw invalid(name, "type must be local or remote");
		};
	}

	private static void mergeObject(ObjectNode target, ObjectNode incoming) {
		incoming.properties().forEach(entry -> {
			JsonNode old = target.get(entry.getKey());
			if (old instanceof ObjectNode oldObject && entry.getValue() instanceof ObjectNode newObject) {
				mergeObject(oldObject, newObject);
			} else {
				target.set(entry.getKey(), entry.getValue().deepCopy());
			}
		});
	}

	private static Map<String, String> stringMap(String server, ObjectNode value, String field) throws IOException {
		JsonNode node = value.get(field);
		if (node == null || node.isNull()) return Map.of();
		if (!node.isObject()) throw invalid(server, field + " must be an object of string values");
		LinkedHashMap<String, String> result = new LinkedHashMap<>();
		var fields = node.properties().iterator();
		while (fields.hasNext()) {
			var entry = fields.next();
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

	private Path valuePath(String name, Path fallback) {
		String value = environment.get(name);
		return value == null || value.isBlank() ? fallback : resolve(home, value);
	}

	private static Path resolve(Path base, String value) {
		Path path = Path.of(value);
		return (path.isAbsolute() ? path : base.resolve(path)).toAbsolutePath().normalize();
	}

	private static boolean truthy(String value) {
		return value != null
				&& (value.equals("1") || value.equalsIgnoreCase("true") || value.equalsIgnoreCase("yes"));
	}

	private static IOException invalid(String server, String message) {
		return new IOException("Invalid MCP server \"" + server + "\": " + message);
	}
}
