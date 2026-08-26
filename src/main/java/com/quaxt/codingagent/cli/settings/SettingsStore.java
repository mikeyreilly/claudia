package com.quaxt.codingagent.cli.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.util.Set;
import com.quaxt.codingagent.ai.json.Json;
import com.quaxt.codingagent.ai.types.ThinkingLevel;

/**
 * Global settings for the Java CLI. Updates are serialized across processes,
 * preserve settings this version does not know about, and replace the file
 * atomically so an interrupted write cannot leave partial JSON.
 */
public final class SettingsStore {
	private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
			EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
	private static final Set<PosixFilePermission> FILE_PERMISSIONS =
			EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

	private final Path settingsPath;
	private final Path lockPath;

	public SettingsStore(Path settingsPath) {
		this.settingsPath = settingsPath.toAbsolutePath().normalize();
		this.lockPath = this.settingsPath.resolveSibling(this.settingsPath.getFileName() + ".lock");
	}

	public static SettingsStore defaultStore() {
		return new SettingsStore(Path.of(System.getProperty("user.home"), ".codingagent", "settings.json"));
	}

	/** Loads the current settings, or an empty settings object when no file exists. */
	public Settings load() throws IOException {
		ObjectNode root = readObject();
		String provider = optionalText(root, "defaultProvider");
		String model = optionalText(root, "defaultModel");
		String theme = optionalText(root, "theme");
		String thinking = optionalText(root, "defaultThinkingLevel");
		ThinkingLevel thinkingLevel = null;
		if (thinking != null) {
			try {
				thinkingLevel = ThinkingLevel.fromWire(thinking);
			} catch (IllegalArgumentException error) {
				throw new IOException("Invalid defaultThinkingLevel in " + settingsPath + ": " + thinking, error);
			}
		}
		return new Settings(provider, model, thinkingLevel, theme, optionalBoolean(root, "hideThinkingBlock", false));
	}

	public void setDefaultModelAndProvider(String provider, String model) throws IOException {
		requireValue(provider, "provider");
		requireValue(model, "model");
		modify(root -> {
			root.put("defaultProvider", provider);
			root.put("defaultModel", model);
		});
	}

	public void setDefaultThinkingLevel(ThinkingLevel level) throws IOException {
		if (level == null) throw new IllegalArgumentException("level must not be null");
		modify(root -> root.put("defaultThinkingLevel", level.wire()));
	}

	public void setTheme(String theme) throws IOException {
		requireValue(theme, "theme");
		modify(root -> root.put("theme", theme));
	}

	public void setHideThinkingBlock(boolean hide) throws IOException {
		modify(root -> root.put("hideThinkingBlock", hide));
	}

	/** Persists whether a configured MCP server should connect on future starts. */
	public void setMcpServerEnabled(String serverName, boolean enabled) throws IOException {
		requireValue(serverName, "serverName");
		modify(root -> mcpServer(root, serverName).put("enabled", enabled));
	}

	/**
	 * Persists a per-server tool override. Enabled tools are omitted from
	 * {@code disabledTools}, because enabled is the default.
	 */
	public void setMcpToolEnabled(String serverName, String toolName, boolean enabled) throws IOException {
		requireValue(serverName, "serverName");
		requireValue(toolName, "toolName");
		modify(root -> {
			ObjectNode server = mcpServer(root, serverName);
			ArrayNode disabledTools = disabledToolsArray(server, serverName);
			if (enabled) {
				if (disabledTools == null) return;
				ArrayNode retained = Json.MAPPER.createArrayNode();
				for (JsonNode tool : disabledTools) {
					String name = disabledToolName(serverName, tool);
					if (!name.equals(toolName)) retained.add(name);
				}
				if (retained.isEmpty()) server.remove("disabledTools");
				else server.set("disabledTools", retained);
				return;
			}
			if (disabledTools == null) disabledTools = server.putArray("disabledTools");
			for (JsonNode tool : disabledTools) {
				if (disabledToolName(serverName, tool).equals(toolName)) return;
			}
			disabledTools.add(toolName);
		});
	}

	@FunctionalInterface
	private interface ObjectUpdate {
		void apply(ObjectNode root) throws IOException;
	}

	private void modify(ObjectUpdate operation) throws IOException {
		ensureParentDirectory();
		try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
				FileLock ignored = channel.lock()) {
			setPermissions(lockPath, FILE_PERMISSIONS);
			ObjectNode root = readObject();
			operation.apply(root);
			writeObject(root);
		}
	}

	private ObjectNode readObject() throws IOException {
		if (!Files.exists(settingsPath)) {
			return Json.object();
		}
		JsonNode root;
		try {
			root = Json.MAPPER.readTree(Files.readString(settingsPath, StandardCharsets.UTF_8));
		} catch (IOException error) {
			throw new IOException("Failed to read settings file " + settingsPath + ": " + error.getMessage(), error);
		}
		if (!(root instanceof ObjectNode object)) {
			throw new IOException("Invalid settings file " + settingsPath + ": expected a JSON object");
		}
		return object;
	}

	private void writeObject(ObjectNode root) throws IOException {
		Path temp = Files.createTempFile(settingsPath.getParent(), "settings-", ".json");
		try {
			Files.writeString(
					temp,
					Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n",
					StandardCharsets.UTF_8,
					StandardOpenOption.WRITE,
					StandardOpenOption.TRUNCATE_EXISTING);
			setPermissions(temp, FILE_PERMISSIONS);
			try {
				Files.move(temp, settingsPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException error) {
				Files.move(temp, settingsPath, StandardCopyOption.REPLACE_EXISTING);
			}
			setPermissions(settingsPath, FILE_PERMISSIONS);
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	private void ensureParentDirectory() throws IOException {
		Path parent = settingsPath.getParent();
		if (parent == null) {
			throw new IOException("Settings path has no parent directory: " + settingsPath);
		}
		Files.createDirectories(parent);
		setPermissions(parent, DIRECTORY_PERMISSIONS);
	}

	private static ObjectNode mcpServer(ObjectNode root, String serverName) throws IOException {
		JsonNode mcp = root.get("mcp");
		if (!(mcp instanceof ObjectNode servers)) {
			throw new IOException("Cannot update MCP server \"" + serverName + "\": mcp must be an object");
		}
		JsonNode server = servers.get(serverName);
		if (!(server instanceof ObjectNode object)) {
			throw new IOException("Cannot update MCP server \"" + serverName + "\": server is not configured");
		}
		return object;
	}

	private static ArrayNode disabledToolsArray(ObjectNode server, String serverName) throws IOException {
		JsonNode value = server.get("disabledTools");
		if (value == null || value.isNull()) return null;
		if (!(value instanceof ArrayNode tools)) {
			throw new IOException("Invalid MCP server \"" + serverName + "\": disabledTools must be an array");
		}
		for (JsonNode tool : tools) {
			disabledToolName(serverName, tool);
		}
		return tools;
	}

	private static String disabledToolName(String serverName, JsonNode tool) throws IOException {
		if (!tool.isTextual() || tool.asText().isBlank()) {
			throw new IOException("Invalid MCP server \"" + serverName + "\": disabledTools must contain non-empty strings");
		}
		return tool.asText();
	}

	private static String optionalText(ObjectNode root, String field) throws IOException {
		JsonNode value = root.get(field);
		if (value == null || value.isNull()) return null;
		if (!value.isTextual() || value.asText().isBlank()) {
			throw new IOException("Invalid setting " + field + ": expected a non-empty string");
		}
		return value.asText();
	}

	private static boolean optionalBoolean(ObjectNode root, String field, boolean defaultValue) throws IOException {
		JsonNode value = root.get(field);
		if (value == null || value.isNull()) return defaultValue;
		if (!value.isBoolean()) {
			throw new IOException("Invalid setting " + field + ": expected a boolean");
		}
		return value.asBoolean();
	}

	private static void requireValue(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
	}

	private static void setPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
		try {
			Files.setPosixFilePermissions(path, permissions);
		} catch (UnsupportedOperationException ignored) {
			// Windows does not expose POSIX mode bits.
		}
	}

	/** The settings currently understood by the Java CLI. */
	public record Settings(
			String defaultProvider,
			String defaultModel,
			ThinkingLevel defaultThinkingLevel,
			String theme,
			boolean hideThinkingBlock) {
		public static Settings empty() {
			return new Settings(null, null, null, null, false);
		}

		public Settings withDefaultModel(String provider, String model) {
			return new Settings(provider, model, defaultThinkingLevel, theme, hideThinkingBlock);
		}

		public Settings withDefaultThinkingLevel(ThinkingLevel level) {
			return new Settings(defaultProvider, defaultModel, level, theme, hideThinkingBlock);
		}

		public Settings withTheme(String value) {
			return new Settings(defaultProvider, defaultModel, defaultThinkingLevel, value, hideThinkingBlock);
		}

		public Settings withHideThinkingBlock(boolean hide) {
			return new Settings(defaultProvider, defaultModel, defaultThinkingLevel, theme, hide);
		}
	}
}
