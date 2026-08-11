package com.quaxt.codingagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import com.quaxt.codingagent.ai.json.Json;

/** Secure persistent storage for OAuth tokens and dynamically registered MCP clients. */
final class McpOAuthStore {
	private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
			EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
	private static final Set<PosixFilePermission> FILE_PERMISSIONS =
			EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

	private final Path path;
	private final Path lockPath;
	private final List<Path> importPaths;

	McpOAuthStore(Path path, List<Path> importPaths) {
		this.path = path.toAbsolutePath().normalize();
		this.lockPath = this.path.resolveSibling(this.path.getFileName() + ".lock");
		this.importPaths = importPaths.stream().map(value -> value.toAbsolutePath().normalize()).toList();
	}

	static McpOAuthStore defaultStore() {
		Path home = Path.of(System.getProperty("user.home"));
		String xdg = System.getenv("XDG_DATA_HOME");
		Path openCodeData = xdg == null || xdg.isBlank()
				? home.resolve(".local/share/opencode/mcp-auth.json")
				: Path.of(xdg).resolve("opencode/mcp-auth.json");
		return new McpOAuthStore(
				home.resolve(".codingagent/mcp-auth.json"),
				List.of(openCodeData, home.resolve("Library/Application Support/opencode/mcp-auth.json")));
	}

	/** Reads codingagent credentials first, then imports a matching OpenCode entry when available. */
	Entry read(String name, String serverUrl) throws IOException {
		Entry own = readEntry(path, name, serverUrl, false);
		if (own != null) return own;
		for (Path candidate : importPaths) {
			Entry imported = readEntry(candidate, name, serverUrl, true);
			if (imported != null) return imported;
		}
		return null;
	}

	void write(String name, String serverUrl, Entry entry) throws IOException {
		if (name == null || name.isBlank()) throw new IllegalArgumentException("MCP server name must not be blank");
		Files.createDirectories(path.getParent());
		setPermissions(path.getParent(), DIRECTORY_PERMISSIONS);
		try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
				FileLock ignored = channel.lock()) {
			ObjectNode root = readRoot(path, false);
			root.set(name, serialize(serverUrl, entry));
			writeRoot(root);
		}
	}

	private Entry readEntry(Path source, String name, String serverUrl, boolean lenient) throws IOException {
		if (!Files.isRegularFile(source)) return null;
		ObjectNode root;
		try {
			root = readRoot(source, lenient);
		} catch (IOException error) {
			if (lenient) return null;
			throw error;
		}
		JsonNode node = root.get(name);
		if (node == null || !node.isObject()) return null;
		if (!node.path("serverUrl").isTextual() || !node.path("serverUrl").asText().equals(serverUrl)) return null;
		try {
			return parse(node);
		} catch (RuntimeException error) {
			if (lenient) return null;
			throw new IOException("Invalid MCP OAuth credential for \"" + name + "\" in " + source, error);
		}
	}

	private static Entry parse(JsonNode node) {
		Tokens tokens = null;
		JsonNode tokenNode = node.get("tokens");
		if (tokenNode != null && tokenNode.isObject() && text(tokenNode, "accessToken") != null) {
			tokens = new Tokens(
					text(tokenNode, "accessToken"),
					text(tokenNode, "refreshToken"),
					number(tokenNode, "expiresAt"),
					text(tokenNode, "scope"));
		}
		ClientInfo client = null;
		JsonNode clientNode = node.get("clientInfo");
		if (clientNode != null && clientNode.isObject() && text(clientNode, "clientId") != null) {
			client = new ClientInfo(
					text(clientNode, "clientId"),
					text(clientNode, "clientSecret"),
					number(clientNode, "clientIdIssuedAt"),
					number(clientNode, "clientSecretExpiresAt"),
					text(clientNode, "tokenEndpointAuthMethod"),
					text(clientNode, "redirectUri"));
		}
		return new Entry(tokens, client);
	}

	private static ObjectNode serialize(String serverUrl, Entry entry) {
		ObjectNode node = Json.object().put("serverUrl", serverUrl);
		if (entry.tokens() != null) {
			Tokens value = entry.tokens();
			ObjectNode tokens = node.putObject("tokens").put("accessToken", value.accessToken());
			put(tokens, "refreshToken", value.refreshToken());
			if (value.expiresAt() != null) tokens.put("expiresAt", value.expiresAt());
			put(tokens, "scope", value.scope());
		}
		if (entry.clientInfo() != null) {
			ClientInfo value = entry.clientInfo();
			ObjectNode client = node.putObject("clientInfo").put("clientId", value.clientId());
			put(client, "clientSecret", value.clientSecret());
			if (value.clientIdIssuedAt() != null) client.put("clientIdIssuedAt", value.clientIdIssuedAt());
			if (value.clientSecretExpiresAt() != null) client.put("clientSecretExpiresAt", value.clientSecretExpiresAt());
			put(client, "tokenEndpointAuthMethod", value.tokenEndpointAuthMethod());
			put(client, "redirectUri", value.redirectUri());
		}
		return node;
	}

	private ObjectNode readRoot(Path source, boolean lenient) throws IOException {
		if (!Files.exists(source)) return Json.object();
		try {
			JsonNode parsed = Json.MAPPER.readTree(Files.readString(source, StandardCharsets.UTF_8));
			if (parsed instanceof ObjectNode object) return object.deepCopy();
			if (lenient) return Json.object();
			throw new IOException("Invalid MCP OAuth credential file " + source + ": expected a JSON object");
		} catch (IOException error) {
			if (lenient) return Json.object();
			throw new IOException("Failed to read MCP OAuth credential file " + source + ": " + error.getMessage(), error);
		}
	}

	private void writeRoot(ObjectNode root) throws IOException {
		Path temporary = Files.createTempFile(path.getParent(), "mcp-auth-", ".json");
		try {
			Files.writeString(temporary, Json.MAPPER.writeValueAsString(root) + "\n", StandardCharsets.UTF_8);
			setPermissions(temporary, FILE_PERMISSIONS);
			try {
				Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException error) {
				Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
			}
			setPermissions(path, FILE_PERMISSIONS);
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
	}

	private static Long number(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value != null && value.isNumber() ? (long) Math.floor(value.asDouble()) : null;
	}

	private static void put(ObjectNode node, String field, String value) {
		if (value != null && !value.isBlank()) node.put(field, value);
	}

	private static void setPermissions(Path target, Set<PosixFilePermission> permissions) throws IOException {
		try {
			Files.setPosixFilePermissions(target, permissions);
		} catch (UnsupportedOperationException ignored) {
			// Windows protects files through the user's profile ACL instead.
		}
	}

	record Entry(Tokens tokens, ClientInfo clientInfo) {
		Entry withTokens(Tokens value) {
			return new Entry(value, clientInfo);
		}

		Entry withClientInfo(ClientInfo value) {
			return new Entry(tokens, value);
		}
	}

	record Tokens(String accessToken, String refreshToken, Long expiresAt, String scope) {}

	record ClientInfo(
			String clientId,
			String clientSecret,
			Long clientIdIssuedAt,
			Long clientSecretExpiresAt,
			String tokenEndpointAuthMethod,
			String redirectUri) {}
}
