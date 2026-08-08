package com.quaxt.codingagent.ai.auth;

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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import com.quaxt.codingagent.ai.json.Json;

/**
 * File credential storage for the Java CLI. It uses an adjacent lock file for
 * cross-process serialization, writes a temporary file before atomically
 * replacing {@code auth.json}, and applies 0600 permissions on POSIX systems.
 */
public final class FileCredentialStore implements CredentialStore {
	private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
			EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
	private static final Set<PosixFilePermission> FILE_PERMISSIONS =
			EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

	private final Path authPath;
	private final Path lockPath;

	public FileCredentialStore(Path authPath) {
		this.authPath = authPath.toAbsolutePath().normalize();
		this.lockPath = this.authPath.resolveSibling(this.authPath.getFileName() + ".lock");
	}

	public static FileCredentialStore defaultStore() {
		Path home = Path.of(System.getProperty("user.home"));
		return new FileCredentialStore(home.resolve(".pi-java").resolve("auth.json"));
	}

	@Override
	public Optional<Credential> read(String providerId) throws IOException {
		validateProviderId(providerId);
		return Optional.ofNullable(readAll().get(providerId));
	}

	@Override
	public List<CredentialInfo> list() throws IOException {
		List<CredentialInfo> result = new ArrayList<>();
		for (Map.Entry<String, Credential> entry : readAll().entrySet()) {
			result.add(new CredentialInfo(entry.getKey(), entry.getValue().type()));
		}
		return List.copyOf(result);
	}

	@Override
	public Optional<Credential> modify(String providerId, UnaryOperator<Credential> operation) throws IOException {
		validateProviderId(providerId);
		if (operation == null) {
			throw new IllegalArgumentException("operation must not be null");
		}
		ensureParentDirectory();
		try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
				FileLock ignored = channel.lock()) {
			Map<String, Credential> credentials = readAll();
			Credential next = operation.apply(credentials.get(providerId));
			if (next == null) {
				credentials.remove(providerId);
			} else {
				credentials.put(providerId, next);
			}
			writeAll(credentials);
			return Optional.ofNullable(next);
		}
	}

	private Map<String, Credential> readAll() throws IOException {
		if (!Files.exists(authPath)) {
			return new LinkedHashMap<>();
		}
		JsonNode root;
		try {
			root = Json.MAPPER.readTree(Files.readString(authPath, StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new IOException("Failed to read credential file " + authPath + ": " + e.getMessage(), e);
		}
		if (root == null || !root.isObject()) {
			throw new IOException("Invalid credential file " + authPath + ": expected a JSON object");
		}
		Map<String, Credential> result = new LinkedHashMap<>();
		for (Map.Entry<String, JsonNode> entry : root.properties()) {
			validateProviderId(entry.getKey());
			result.put(entry.getKey(), parseCredential(entry.getKey(), entry.getValue()));
		}
		return result;
	}

	private void writeAll(Map<String, Credential> credentials) throws IOException {
		ObjectNode root = Json.object();
		for (Map.Entry<String, Credential> entry : credentials.entrySet()) {
			root.set(entry.getKey(), serializeCredential(entry.getValue()));
		}
		Path temp = Files.createTempFile(authPath.getParent(), "auth-", ".json");
		try {
			Files.writeString(temp, Json.MAPPER.writeValueAsString(root) + "\n", StandardCharsets.UTF_8);
			setPermissions(temp, FILE_PERMISSIONS);
			try {
				Files.move(temp, authPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temp, authPath, StandardCopyOption.REPLACE_EXISTING);
			}
			setPermissions(authPath, FILE_PERMISSIONS);
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	private Credential parseCredential(String providerId, JsonNode node) throws IOException {
		if (!node.isObject() || !node.path("type").isTextual()) {
			throw invalidCredential(providerId);
		}
		return switch (node.path("type").asText()) {
			case "api_key" -> new Credential.ApiKeyCredential(
					optionalText(node, "key"),
					parseEnv(providerId, node.get("env")));
			case "oauth" -> {
				if (!node.path("access").isTextual()
						|| !node.path("refresh").isTextual()
						|| !node.path("expires").isIntegralNumber()) {
					throw invalidCredential(providerId);
				}
				yield new Credential.OAuthCredential(
						node.path("access").asText(),
						node.path("refresh").asText(),
						node.path("expires").asLong(),
						parseAvailableModelIds(providerId, node.get("availableModelIds")));
			}
			default -> throw invalidCredential(providerId);
		};
	}

	private static ObjectNode serializeCredential(Credential credential) {
		ObjectNode node = Json.object();
		switch (credential) {
			case Credential.ApiKeyCredential apiKey -> {
				node.put("type", apiKey.type());
				if (apiKey.key() != null) {
					node.put("key", apiKey.key());
				}
				if (!apiKey.env().isEmpty()) {
					ObjectNode env = node.putObject("env");
					apiKey.env().forEach(env::put);
				}
			}
			case Credential.OAuthCredential oauth -> {
				node.put("type", oauth.type());
				node.put("access", oauth.access());
				node.put("refresh", oauth.refresh());
				node.put("expires", oauth.expires());
				if (oauth.availableModelIds() != null) {
					var ids = node.putArray("availableModelIds");
					oauth.availableModelIds().forEach(ids::add);
				}
			}
		}
		return node;
	}

	private static Map<String, String> parseEnv(String providerId, JsonNode node) throws IOException {
		if (node == null) {
			return Map.of();
		}
		if (!node.isObject()) {
			throw invalidCredential(providerId);
		}
		Map<String, String> values = new LinkedHashMap<>();
		for (Map.Entry<String, JsonNode> entry : node.properties()) {
			if (!entry.getValue().isTextual()) {
				throw invalidCredential(providerId);
			}
			values.put(entry.getKey(), entry.getValue().asText());
		}
		return values;
	}

	private static List<String> parseAvailableModelIds(String providerId, JsonNode node) throws IOException {
		if (node == null) {
			return null;
		}
		if (!node.isArray()) {
			throw invalidCredential(providerId);
		}
		List<String> values = new ArrayList<>();
		for (JsonNode value : node) {
			if (!value.isTextual()) {
				throw invalidCredential(providerId);
			}
			values.add(value.asText());
		}
		return values;
	}

	private static String optionalText(JsonNode node, String field) throws IOException {
		JsonNode value = node.get(field);
		if (value == null) {
			return null;
		}
		if (!value.isTextual()) {
			throw new IOException("Invalid credential field: " + field);
		}
		return value.asText();
	}

	private static IOException invalidCredential(String providerId) {
		return new IOException("Invalid credential for provider \"" + providerId + "\"");
	}

	private void ensureParentDirectory() throws IOException {
		Files.createDirectories(authPath.getParent());
		setPermissions(authPath.getParent(), DIRECTORY_PERMISSIONS);
	}

	private static void setPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
		try {
			Files.setPosixFilePermissions(path, permissions);
		} catch (UnsupportedOperationException ignored) {
			// Windows lacks POSIX permissions; its ACLs still protect the user profile.
		}
	}

	private static void validateProviderId(String providerId) {
		if (providerId == null || providerId.isBlank() || providerId.indexOf('/') >= 0 || providerId.indexOf('\\') >= 0) {
			throw new IllegalArgumentException("Invalid provider id: " + providerId);
		}
	}
}
