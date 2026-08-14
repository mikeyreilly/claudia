package com.quaxt.codingagent.cli.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.quaxt.codingagent.agent.AgentTool;
import com.quaxt.codingagent.ai.json.Json;
import com.quaxt.codingagent.ai.util.AbortSignal;

/** Built-in local filesystem and shell tools for the Java coding agent. */
public final class BuiltInTools {
	private static final int MAX_LINES = 2_000;
	private static final int MAX_BYTES = 50 * 1024;
	private static final int DEFAULT_FIND_LIMIT = 1_000;
	private static final int DEFAULT_GREP_LIMIT = 100;
	private static final int DEFAULT_LS_LIMIT = 500;

	private BuiltInTools() {}

	/** Returns tools scoped to the given working directory. */
	public static List<AgentTool> create(Path cwd) {
		Path resolvedCwd = cwd.toAbsolutePath().normalize();
		return List.of(
				new ReadTool(resolvedCwd),
				new WriteTool(resolvedCwd),
				new EditTool(resolvedCwd),
				new ShellTool(resolvedCwd),
				new GrepTool(resolvedCwd),
				new FindTool(resolvedCwd),
				new LsTool(resolvedCwd));
	}

	private abstract static class LocalTool implements AgentTool {
		final Path cwd;
		private final String name;
		private final String description;
		private final ObjectNode parameters;

		LocalTool(Path cwd, String name, String description, ObjectNode parameters) {
			this.cwd = cwd;
			this.name = name;
			this.description = description;
			this.parameters = parameters;
		}

		@Override
		public final String name() {
			return name;
		}

		@Override
		public final String description() {
			return description;
		}

		@Override
		public final ObjectNode parameters() {
			return parameters;
		}

		final Path path(String value) {
			if (value == null || value.isBlank()) {
				throw new IllegalArgumentException("path must be a non-empty string");
			}
			Path candidate = Path.of(value);
			return (candidate.isAbsolute() ? candidate : cwd.resolve(candidate)).normalize();
		}

		final void requireNotAborted(AbortSignal signal) {
			if (signal.isAborted()) {
				throw new IllegalStateException("Operation aborted");
			}
		}
	}

	private static final class ReadTool extends LocalTool {
		ReadTool(Path cwd) {
			super(
					cwd,
					"read",
					"Read a text file. Use offset and limit for large files; output is bounded to 2,000 lines or 50KB.",
					schema("path", string("Path to the file to read"), "offset", optional(integer("1-indexed starting line")), "limit", optional(integer("Maximum lines to read"))));
		}

		@Override
		public ToolResult execute(String id, ObjectNode arguments, AbortSignal signal, Consumer<ToolResult> update)
				throws IOException {
			Path file = path(requiredText(arguments, "path"));
			requireNotAborted(signal);
			if (!Files.isRegularFile(file)) {
				throw new IOException("Not a readable file: " + file);
			}
			List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			int offset = positiveOrDefault(arguments, "offset", 1);
			int start = offset - 1;
			if (start >= lines.size()) {
				throw new IllegalArgumentException("offset " + offset + " is beyond end of file (" + lines.size() + " lines)");
			}
			int limit = positiveOrDefault(arguments, "limit", Integer.MAX_VALUE);
			int end = Math.min(lines.size(), start + limit);
			String output = String.join("\n", lines.subList(start, end));
			output = truncate(output, "Use offset=" + (start + countLines(output) + 1) + " to continue.");
			if (end < lines.size() && !output.contains("Use offset=")) {
				output += "\n\n[" + (lines.size() - end) + " more lines. Use offset=" + (end + 1) + " to continue.]";
			}
			return ToolResult.text(output);
		}
	}

	private static final class WriteTool extends LocalTool {
		WriteTool(Path cwd) {
			super(cwd, "write", "Create or overwrite a text file, creating parent directories as needed.", schema("path", string("Path to write"), "content", string("File content")));
		}

		@Override
		public ToolResult execute(String id, ObjectNode arguments, AbortSignal signal, Consumer<ToolResult> update)
				throws IOException {
			Path file = path(requiredText(arguments, "path"));
			String content = requiredText(arguments, "content");
			requireNotAborted(signal);
			Path parent = file.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			requireNotAborted(signal);
			Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
			requireNotAborted(signal);
			return ToolResult.text("Successfully wrote " + content.getBytes(StandardCharsets.UTF_8).length + " bytes to " + file);
		}
	}

	private static final class EditTool extends LocalTool {
		EditTool(Path cwd) {
			super(
					cwd,
					"edit",
					"Replace one or more unique, non-overlapping exact text blocks in a file.",
					schema("path", string("Path to edit"), "edits", array("Exact replacements with oldText and newText")));
		}

		@Override
		public ToolResult execute(String id, ObjectNode arguments, AbortSignal signal, Consumer<ToolResult> update)
				throws IOException {
			Path file = path(requiredText(arguments, "path"));
			JsonNode editsNode = arguments.get("edits");
			if (!(editsNode instanceof ArrayNode edits) || edits.isEmpty()) {
				throw new IllegalArgumentException("edits must be a non-empty array");
			}
			requireNotAborted(signal);
			String content = Files.readString(file, StandardCharsets.UTF_8);
			List<Replacement> replacements = new ArrayList<>();
			for (JsonNode edit : edits) {
				if (!edit.isObject()) {
					throw new IllegalArgumentException("each edit must be an object");
				}
				String oldText = requiredText((ObjectNode) edit, "oldText");
				String newText = requiredText((ObjectNode) edit, "newText");
				int first = content.indexOf(oldText);
				if (first < 0) {
					throw new IllegalArgumentException("oldText was not found in " + file);
				}
				if (content.indexOf(oldText, first + 1) >= 0) {
					throw new IllegalArgumentException("oldText must match exactly one location in " + file);
				}
				replacements.add(new Replacement(first, first + oldText.length(), newText));
			}
			replacements.sort(Comparator.comparingInt(Replacement::start));
			for (int index = 1; index < replacements.size(); index++) {
				if (replacements.get(index).start < replacements.get(index - 1).end) {
					throw new IllegalArgumentException("edits must not overlap");
				}
			}
			StringBuilder changed = new StringBuilder(content);
			for (int index = replacements.size() - 1; index >= 0; index--) {
				Replacement replacement = replacements.get(index);
				changed.replace(replacement.start, replacement.end, replacement.newText);
			}
			requireNotAborted(signal);
			Files.writeString(file, changed.toString(), StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
			return ToolResult.text("Successfully replaced " + replacements.size() + " block(s) in " + file);
		}

		private record Replacement(int start, int end, String newText) {}
	}

	private static final class ShellTool extends LocalTool {
		private final Shell shell;

		ShellTool(Path cwd) {
			this(cwd, Shell.current());
		}

		private ShellTool(Path cwd, Shell shell) {
			super(cwd, "shell", "Execute a " + shell.displayName + " command in the current working directory. Output is bounded to 2,000 lines or 50KB.", schema("command", string(shell.displayName + " command"), "timeout", optional(number("Optional timeout in seconds"))));
			this.shell = shell;
		}

		@Override
		public ToolResult execute(String id, ObjectNode arguments, AbortSignal signal, Consumer<ToolResult> update)
				throws Exception {
			String command = requiredText(arguments, "command");
			double timeoutSeconds = optionalPositiveNumber(arguments, "timeout", 0);
			Process process = new ProcessBuilder(shell.command(command)).directory(cwd.toFile()).redirectErrorStream(true).start();
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			Thread reader = Thread.ofVirtual().start(() -> {
				try (var input = process.getInputStream()) {
					input.transferTo(bytes);
				} catch (IOException ignored) {
					// The process exit status is reported below.
				}
			});
			long deadline = timeoutSeconds == 0 ? Long.MAX_VALUE : System.nanoTime() + Duration.ofMillis((long) (timeoutSeconds * 1_000)).toNanos();
			while (process.isAlive()) {
				if (signal.isAborted()) {
					process.destroyForcibly();
					throw new IllegalStateException("Command aborted");
				}
				if (System.nanoTime() >= deadline) {
					process.destroyForcibly();
					throw new IllegalStateException("Command timed out after " + timeoutSeconds + " seconds");
				}
				process.waitFor(50, TimeUnit.MILLISECONDS);
			}
			reader.join();
			String output = truncate(bytes.toString(StandardCharsets.UTF_8), null);
			if (process.exitValue() != 0) {
				throw new IllegalStateException((output.isBlank() ? "" : output + "\n\n") + "Command exited with code " + process.exitValue());
			}
			return ToolResult.text(output.isBlank() ? "(no output)" : output);
		}
	}

	private enum Shell {
		BASH("bash") {
			@Override
			List<String> command(String command) {
				return List.of("/bin/bash", "-lc", command);
			}
		},
		POWERSHELL("PowerShell") {
			@Override
			List<String> command(String command) {
				return List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command);
			}
		};

		final String displayName;

		Shell(String displayName) {
			this.displayName = displayName;
		}

		abstract List<String> command(String command);

		static Shell current() {
			return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win") ? POWERSHELL : BASH;
		}
	}

	private static final class GrepTool extends LocalTool {
		GrepTool(Path cwd) {
			super(
					cwd,
					"grep",
					"Search text files beneath a literal file or directory. Use glob, not path, to filter file names. Returns paths and line numbers, respecting the result limit.",
					schema(
							"pattern", string("Regular expression to search for, or literal text when literal is true"),
							"path", optional(string("Literal file or directory to search (default: current directory); wildcards are not expanded")),
							"glob", optional(string("Glob file filter relative to path, for example '*.java' or 'src/**/*.java'; patterns without a slash match file names at any depth")),
							"ignoreCase", optional(bool("Case insensitive")),
							"literal", optional(bool("Treat pattern literally")),
							"context", optional(integer("Lines before and after matches")),
							"limit", optional(integer("Maximum matches"))));
		}

		@Override
		public ToolResult execute(String id, ObjectNode arguments, AbortSignal signal, Consumer<ToolResult> update)
				throws IOException {
			String patternText = requiredText(arguments, "pattern");
			boolean literal = optionalBoolean(arguments, "literal");
			int flags = optionalBoolean(arguments, "ignoreCase") ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
			Pattern pattern = Pattern.compile(literal ? Pattern.quote(patternText) : patternText, flags);
			String pathText = optionalText(arguments, "path", ".");
			Path root = grepRoot(pathText);
			int limit = positiveOrDefault(arguments, "limit", DEFAULT_GREP_LIMIT);
			String glob = optionalText(arguments, "glob", null);
			List<PathMatcher> fileMatchers = glob == null ? List.of() : globMatchers(glob);
			List<Path> files = filesUnder(root);
			boolean rootIsDirectory = Files.isDirectory(root);
			StringBuilder output = new StringBuilder();
			int matches = 0;
			for (Path file : files) {
				requireNotAborted(signal);
				Path relative = rootIsDirectory ? root.relativize(file) : file.getFileName();
				if (!fileMatchers.isEmpty() && !matchesGlob(fileMatchers, relative)) {
					continue;
				}
				List<String> lines;
				try {
					lines = Files.readAllLines(file, StandardCharsets.UTF_8);
				} catch (IOException ignored) {
					continue;
				}
				for (int line = 0; line < lines.size(); line++) {
					if (!pattern.matcher(lines.get(line)).find()) {
						continue;
					}
					matches++;
					output.append(display(root, file)).append(':').append(line + 1).append(": ").append(truncateLine(lines.get(line))).append('\n');
					if (matches >= limit) {
						return ToolResult.text(truncate(output.toString(), "[" + limit + " matches limit reached]"));
					}
				}
			}
			return ToolResult.text(matches == 0 ? "No matches found" : truncate(output.toString(), null));
		}

		private Path grepRoot(String pathText) {
			Path root;
			try {
				root = path(pathText);
			} catch (InvalidPathException error) {
				if (containsGlobMetacharacter(pathText)) {
					throw wildcardPathError(pathText);
				}
				throw error;
			}
			if (!Files.exists(root) && containsGlobMetacharacter(pathText)) {
				throw wildcardPathError(pathText);
			}
			return root;
		}
	}

	private static final class FindTool extends LocalTool {
		FindTool(Path cwd) {
			super(cwd, "find", "Find files by glob pattern. Hidden files are included; .git and node_modules are skipped.", schema("pattern", string("Glob pattern"), "path", optional(string("Directory to search")), "limit", optional(integer("Maximum results"))));
		}

		@Override
		public ToolResult execute(String id, ObjectNode arguments, AbortSignal signal, Consumer<ToolResult> update)
				throws IOException {
			String pattern = requiredText(arguments, "pattern");
			Path root = path(optionalText(arguments, "path", "."));
			if (!Files.isDirectory(root)) {
				throw new IllegalArgumentException("Not a directory: " + root);
			}
			int limit = positiveOrDefault(arguments, "limit", DEFAULT_FIND_LIMIT);
			var matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
			List<String> matches = new ArrayList<>();
			try (var paths = Files.walk(root)) {
				for (Path candidate : paths.filter(Files::isRegularFile).toList()) {
					requireNotAborted(signal);
					Path relative = root.relativize(candidate);
					if (ignored(relative)) {
						continue;
					}
					if (matcher.matches(relative) || matcher.matches(relative.getFileName())) {
						matches.add(relative.toString().replace('\\', '/'));
						if (matches.size() >= limit) {
							break;
						}
					}
				}
			}
			matches.sort(String::compareToIgnoreCase);
			if (matches.isEmpty()) {
				return ToolResult.text("No files found matching pattern");
			}
			String suffix = matches.size() >= limit ? "\n\n[" + limit + " results limit reached]" : "";
			return ToolResult.text(truncate(String.join("\n", matches) + suffix, null));
		}
	}

	private static final class LsTool extends LocalTool {
		LsTool(Path cwd) {
			super(cwd, "ls", "List a directory's contents, with a slash suffix on directories.", schema("path", optional(string("Directory to list")), "limit", optional(integer("Maximum entries"))));
		}

		@Override
		public ToolResult execute(String id, ObjectNode arguments, AbortSignal signal, Consumer<ToolResult> update)
				throws IOException {
			Path directory = path(optionalText(arguments, "path", "."));
			if (!Files.isDirectory(directory)) {
				throw new IllegalArgumentException("Not a directory: " + directory);
			}
			int limit = positiveOrDefault(arguments, "limit", DEFAULT_LS_LIMIT);
			List<String> entries;
			try (var paths = Files.list(directory)) {
				entries = paths.map(entry -> entry.getFileName() + (Files.isDirectory(entry) ? "/" : ""))
						.sorted(String.CASE_INSENSITIVE_ORDER)
						.limit(limit)
						.toList();
			}
			if (entries.isEmpty()) {
				return ToolResult.text("(empty directory)");
			}
			return ToolResult.text(truncate(String.join("\n", entries), null));
		}
	}

	private static IllegalArgumentException wildcardPathError(String path) {
		return new IllegalArgumentException(
				"path is literal and does not expand wildcards: " + path
						+ ". Put the search root in path and the file pattern in glob, for example path=\"src\" and glob=\"**/*.java\".");
	}

	private static boolean containsGlobMetacharacter(String value) {
		for (int index = 0; index < value.length(); index++) {
			if (switch (value.charAt(index)) {
				case '*', '?', '[', '{' -> true;
				default -> false;
			}) {
				return true;
			}
		}
		return false;
	}

	private static List<PathMatcher> globMatchers(String glob) {
		if (glob.isBlank()) {
			throw new IllegalArgumentException("glob must be a non-empty string");
		}
		try {
			return globVariants(glob).stream()
					.map(variant -> FileSystems.getDefault().getPathMatcher("glob:" + variant))
					.toList();
		} catch (java.util.regex.PatternSyntaxException error) {
			throw new IllegalArgumentException("Invalid glob '" + glob + "': " + error.getDescription(), error);
		}
	}

	// Java's recursive-directory glob requires at least one directory; ripgrep-style globs allow zero.
	private static List<String> globVariants(String glob) {
		LinkedHashSet<String> variants = new LinkedHashSet<>();
		List<String> pending = new ArrayList<>();
		variants.add(glob);
		pending.add(glob);
		for (int pendingIndex = 0; pendingIndex < pending.size(); pendingIndex++) {
			String variant = pending.get(pendingIndex);
			for (int index = variant.indexOf("**/"); index >= 0; index = variant.indexOf("**/", index + 3)) {
				String withoutDirectoryWildcard = variant.substring(0, index) + variant.substring(index + 3);
				if (variants.add(withoutDirectoryWildcard)) {
					pending.add(withoutDirectoryWildcard);
				}
			}
		}
		return List.copyOf(variants);
	}

	private static boolean matchesGlob(List<PathMatcher> matchers, Path relative) {
		for (PathMatcher matcher : matchers) {
			if (matcher.matches(relative)) {
				return true;
			}
		}
		Path fileName = relative.getFileName();
		if (fileName != null && !fileName.equals(relative)) {
			for (PathMatcher matcher : matchers) {
				if (matcher.matches(fileName)) {
					return true;
				}
			}
		}
		return false;
	}

	private static List<Path> filesUnder(Path root) throws IOException {
		if (Files.isRegularFile(root)) {
			return List.of(root);
		}
		if (!Files.isDirectory(root)) {
			throw new IllegalArgumentException("Path not found: " + root);
		}
		try (var paths = Files.walk(root)) {
			return paths.filter(Files::isRegularFile).filter(path -> !ignored(root.relativize(path))).toList();
		}
	}

	private static boolean ignored(Path relative) {
		for (Path part : relative) {
			if (part.toString().equals(".git") || part.toString().equals("node_modules")) {
				return true;
			}
		}
		return false;
	}

	private static String display(Path root, Path file) {
		return Files.isDirectory(root) ? root.relativize(file).toString().replace('\\', '/') : file.getFileName().toString();
	}

	private static String requiredText(ObjectNode arguments, String name) {
		JsonNode value = arguments.get(name);
		if (value == null || !value.isTextual()) {
			throw new IllegalArgumentException(name + " must be a string");
		}
		return value.asText();
	}

	private static String optionalText(ObjectNode arguments, String name, String defaultValue) {
		JsonNode value = arguments.get(name);
		if (value == null || value.isNull()) {
			return defaultValue;
		}
		if (!value.isTextual()) {
			throw new IllegalArgumentException(name + " must be a string");
		}
		return value.asText();
	}

	private static int positiveOrDefault(ObjectNode arguments, String name, int defaultValue) {
		JsonNode value = arguments.get(name);
		if (value == null || value.isNull()) {
			return defaultValue;
		}
		if (!value.canConvertToInt() || value.asInt() <= 0) {
			throw new IllegalArgumentException(name + " must be a positive integer");
		}
		return value.asInt();
	}

	private static double optionalPositiveNumber(ObjectNode arguments, String name, double defaultValue) {
		JsonNode value = arguments.get(name);
		if (value == null || value.isNull()) {
			return defaultValue;
		}
		if (!value.isNumber() || value.asDouble() <= 0 || !Double.isFinite(value.asDouble())) {
			throw new IllegalArgumentException(name + " must be a positive finite number");
		}
		return value.asDouble();
	}

	private static boolean optionalBoolean(ObjectNode arguments, String name) {
		JsonNode value = arguments.get(name);
		if (value == null || value.isNull()) {
			return false;
		}
		if (!value.isBoolean()) {
			throw new IllegalArgumentException(name + " must be a boolean");
		}
		return value.asBoolean();
	}

	private static ObjectNode schema(Object... fields) {
		ObjectNode schema = Json.object();
		schema.put("type", "object");
		ObjectNode properties = schema.putObject("properties");
		ArrayNode required = schema.putArray("required");
		for (int index = 0; index < fields.length; index += 2) {
			String name = (String) fields[index];
			ObjectNode definition = (ObjectNode) fields[index + 1];
			boolean optional = definition.remove("x-java-optional") != null;
			properties.set(name, definition);
			if (!optional) {
				required.add(name);
			}
		}
		schema.put("additionalProperties", false);
		return schema;
	}

	private static ObjectNode string(String description) {
		return Json.object().put("type", "string").put("description", description);
	}

	private static ObjectNode integer(String description) {
		return Json.object().put("type", "integer").put("minimum", 1).put("description", description);
	}

	private static ObjectNode number(String description) {
		return Json.object().put("type", "number").put("exclusiveMinimum", 0).put("description", description);
	}

	private static ObjectNode bool(String description) {
		return Json.object().put("type", "boolean").put("description", description);
	}

	private static ObjectNode optional(ObjectNode definition) {
		return definition.put("x-java-optional", true);
	}

	private static ObjectNode array(String description) {
		return Json.object().put("type", "array").put("description", description);
	}

	private static String truncate(String input, String notice) {
		String[] lines = input.split("\\R", -1);
		StringBuilder output = new StringBuilder();
		int count = 0;
		for (String line : lines) {
			if (count >= MAX_LINES) {
				return appendNotice(output, notice == null ? "[Output truncated at 2,000 lines]" : notice);
			}
			byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
			int separator = output.isEmpty() ? 0 : 1;
			if (output.length() > 0 && output.toString().getBytes(StandardCharsets.UTF_8).length + separator + bytes.length > MAX_BYTES) {
				return appendNotice(output, notice == null ? "[Output truncated at 50KB]" : notice);
			}
			if (!output.isEmpty()) {
				output.append('\n');
			}
			output.append(line);
			count++;
		}
		return output.toString();
	}

	private static String appendNotice(StringBuilder output, String notice) {
		if (!output.isEmpty()) {
			output.append("\n\n");
		}
		return output.append(notice).toString();
	}

	private static int countLines(String text) {
		return text.isBlank() ? 0 : (int) text.lines().count();
	}

	private static String truncateLine(String line) {
		return line.length() <= 500 ? line : line.substring(0, 500) + "... [truncated]";
	}
}
