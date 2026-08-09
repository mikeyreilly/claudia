package com.quaxt.codingagent.cli.session;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import com.quaxt.codingagent.ai.json.Json;
import com.quaxt.codingagent.ai.types.AssistantMessage;
import com.quaxt.codingagent.ai.types.Message;
import com.quaxt.codingagent.ai.types.ToolResultMessage;
import com.quaxt.codingagent.ai.types.UserMessage;
import com.quaxt.codingagent.ai.util.Uuid;

/**
 * Fresh JSONL session persistence for the Java CLI. Each append is one
 * complete JSON object and never rewrites prior history, making sessions
 * inspectable and resilient to an interrupted process.
 */
public final class SessionStore {
	private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
			EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
	private static final Set<PosixFilePermission> FILE_PERMISSIONS =
			EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

	private final Path directory;
	private final List<Path> legacyDirectories;

	public SessionStore(Path directory) {
		this(directory, List.of());
	}

	private SessionStore(Path directory, List<Path> legacyDirectories) {
		this.directory = directory.toAbsolutePath().normalize();
		this.legacyDirectories = legacyDirectories.stream()
				.map(path -> path.toAbsolutePath().normalize())
				.filter(path -> !path.equals(this.directory))
				.toList();
	}

	public static SessionStore defaultStore() {
		return defaultStore(Path.of(System.getProperty("user.home")));
	}

	/** Keeps sessions written before the application-data directory was renamed resumable. */
	static SessionStore defaultStore(Path home) {
		return new SessionStore(
				home.resolve(".codingagent").resolve("sessions"),
				List.of(home.resolve(".pi-java").resolve("sessions")));
	}

	/** Creates an empty JSONL session and returns its time-sortable UUIDv7 id. */
	public String create() throws IOException {
		ensureDirectory();
		String id = Uuid.uuidv7();
		Path file = pathFor(id);
		Files.createFile(file);
		setPermissions(file, FILE_PERMISSIONS);
		return id;
	}

	/** Appends a typed payload to an existing session. */
	public void append(String sessionId, String type, JsonNode payload) throws IOException {
		validateId(sessionId);
		if (type == null || type.isBlank()) {
			throw new IllegalArgumentException("Session entry type must not be blank");
		}
		if (payload == null) {
			throw new IllegalArgumentException("Session entry payload must not be null");
		}
		Path file = existingPathFor(sessionId);
		if (file == null) {
			throw new IOException("Unknown session: " + sessionId);
		}
		var entry = Json.object();
		entry.put("timestamp", System.currentTimeMillis());
		entry.put("type", type);
		entry.set("payload", payload);
		try (BufferedWriter writer =
				Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.APPEND)) {
			writer.write(Json.MAPPER.writeValueAsString(entry));
			writer.newLine();
		}
	}

	/** Reads and validates all complete entries in file order. */
	public List<Entry> read(String sessionId) throws IOException {
		validateId(sessionId);
		Path file = existingPathFor(sessionId);
		if (file == null) {
			throw new IOException("Unknown session: " + sessionId);
		}
		List<Entry> entries = new ArrayList<>();
		try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			int index = 0;
			String line;
			while ((line = reader.readLine()) != null) {
				index++;
				if (line.isBlank()) {
					continue;
				}
				JsonNode node;
				try {
					node = Json.MAPPER.readTree(line);
				} catch (IOException e) {
					throw new IOException("Invalid JSONL entry at " + file + ":" + index, e);
				}
				if (!node.isObject() || !node.path("timestamp").isIntegralNumber() || !node.path("type").isTextual()
						|| !node.has("payload")) {
					throw new IOException("Invalid JSONL entry at " + file + ":" + index);
				}
				entries.add(new Entry(node.path("timestamp").asLong(), node.path("type").asText(), node.path("payload")));
			}
		}
		return List.copyOf(entries);
	}

	/** Lists session ids newest first. */
	public List<String> list() throws IOException {
		return sessionFiles().stream()
				.map(path -> idFor(path.getFileName()))
				.sorted(Comparator.reverseOrder())
				.toList();
	}

	/** Lists resumable sessions newest first, optionally limited to one working directory. */
	public List<SessionSnapshot> listSnapshots(Path cwd) throws IOException {
		Path normalizedCwd = cwd == null ? null : cwd.toAbsolutePath().normalize();
		List<SessionSnapshot> snapshots = new ArrayList<>();
		for (Path file : sessionFiles()) {
			try {
				SessionSnapshot snapshot = snapshot(idFor(file.getFileName()));
				if (normalizedCwd == null || sameCwd(snapshot.cwd(), normalizedCwd)) {
					snapshots.add(snapshot);
				}
			} catch (IOException | IllegalArgumentException ignored) {
				// Discovery is best effort: one corrupt session must not hide the rest.
			}
		}
		snapshots.sort(Comparator.comparing(SessionSnapshot::modified).reversed());
		return List.copyOf(snapshots);
	}

	/** Loads metadata and the complete message history needed to resume a session. */
	public SessionSnapshot snapshot(String sessionId) throws IOException {
		List<Entry> entries = read(sessionId);
		if (entries.isEmpty() || !entries.getFirst().type().equals("session_start")) {
			throw new IOException("Session has no session_start entry: " + sessionId);
		}
		Entry start = entries.getFirst();
		JsonNode payload = start.payload();
		String cwdText = requiredText(payload, "cwd", sessionId);
		String provider = requiredText(payload, "provider", sessionId);
		String model = requiredText(payload, "model", sessionId);
		List<Message> messages = new ArrayList<>();
		String firstMessage = "";
		StringBuilder allMessages = new StringBuilder();
		long modified = start.timestamp();
		for (Entry entry : entries) {
			modified = Math.max(modified, entry.timestamp());
			if (!entry.type().equals("message")) continue;
			Message message;
			try {
				message = SessionCodec.decode(entry.payload());
			} catch (IOException | RuntimeException error) {
				throw new IOException("Invalid message in session " + sessionId, error);
			}
			messages.add(message);
			String text = messageText(message);
			if (!text.isBlank()) {
				if (!allMessages.isEmpty()) allMessages.append(' ');
				allMessages.append(text);
				if (firstMessage.isEmpty() && message instanceof UserMessage) firstMessage = text;
			}
		}
		Path file = existingPathFor(sessionId);
		if (file == null) throw new IOException("Unknown session: " + sessionId);
		Path sessionCwd;
		try {
			sessionCwd = Path.of(cwdText).toAbsolutePath().normalize();
		} catch (RuntimeException error) {
			throw new IOException("Session " + sessionId + " has an invalid cwd", error);
		}
		return new SessionSnapshot(
				sessionId,
				file,
				sessionCwd,
				provider,
				model,
				Instant.ofEpochMilli(start.timestamp()),
				Instant.ofEpochMilli(modified),
				messages.size(),
				firstMessage.isEmpty() ? "(no messages)" : firstMessage,
				allMessages.toString(),
				messages);
	}

	private List<Path> sessionFiles() throws IOException {
		Map<String, Path> filesById = new LinkedHashMap<>();
		for (Path candidateDirectory : allDirectories()) {
			if (!Files.isDirectory(candidateDirectory)) continue;
			try (Stream<Path> files = Files.list(candidateDirectory)) {
				files.filter(Files::isRegularFile)
						.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
						.forEach(path -> filesById.putIfAbsent(idFor(path.getFileName()), path));
			}
		}
		return List.copyOf(filesById.values());
	}

	private List<Path> allDirectories() {
		List<Path> directories = new ArrayList<>(legacyDirectories.size() + 1);
		directories.add(directory);
		directories.addAll(legacyDirectories);
		return directories;
	}

	private Path pathFor(String sessionId) {
		return directory.resolve(sessionId + ".jsonl");
	}

	private Path existingPathFor(String sessionId) {
		Path current = pathFor(sessionId);
		if (Files.isRegularFile(current)) return current;
		for (Path legacyDirectory : legacyDirectories) {
			Path legacy = legacyDirectory.resolve(sessionId + ".jsonl");
			if (Files.isRegularFile(legacy)) return legacy;
		}
		return null;
	}

	private void ensureDirectory() throws IOException {
		Files.createDirectories(directory);
		setPermissions(directory, DIRECTORY_PERMISSIONS);
	}

	private static String idFor(Path fileName) {
		return fileName.toString().replaceFirst("\\.jsonl$", "");
	}

	private static boolean sameCwd(Path left, Path right) {
		try {
			return left.toRealPath().equals(right.toRealPath());
		} catch (IOException ignored) {
			return left.equals(right);
		}
	}

	private static String requiredText(JsonNode payload, String field, String sessionId) throws IOException {
		JsonNode value = payload.get(field);
		if (value == null || !value.isTextual() || value.asText().isBlank()) {
			throw new IOException("Session " + sessionId + " has no valid " + field);
		}
		return value.asText();
	}

	private static String messageText(Message message) {
		return switch (message) {
			case UserMessage user -> user.text();
			case AssistantMessage assistant -> assistant.text();
			case ToolResultMessage result -> result.text();
		};
	}

	private static void validateId(String sessionId) {
		if (sessionId == null || !sessionId.matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) {
			throw new IllegalArgumentException("Invalid session id: " + sessionId);
		}
	}

	private static void setPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
		try {
			Files.setPosixFilePermissions(path, permissions);
		} catch (UnsupportedOperationException ignored) {
			// Windows does not expose POSIX mode bits.
		}
	}

	public record Entry(long timestamp, String type, JsonNode payload) {}
}
