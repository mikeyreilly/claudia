package com.quaxt.codingagent.cli.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.quaxt.codingagent.agent.AgentTool;
import com.quaxt.codingagent.ai.json.Json;
import com.quaxt.codingagent.ai.types.TextContent;
import com.quaxt.codingagent.ai.util.AbortSignal;

class BuiltInToolsTest {
	@TempDir Path tempDir;

	@Test
	void readsWritesAndEditsFiles() throws Exception {
		List<AgentTool> tools = BuiltInTools.create(tempDir);
		ObjectNode write = Json.object().put("path", "nested/example.txt").put("content", "before\nsecond\n");
		assertTrue(run(tools, "write", write).contains("Successfully wrote"));

		ObjectNode edit = Json.object().put("path", "nested/example.txt");
		edit.putArray("edits").addObject().put("oldText", "before").put("newText", "after");
		assertTrue(run(tools, "edit", edit).contains("Successfully replaced"));

		ObjectNode read = Json.object().put("path", "nested/example.txt").put("offset", 1).put("limit", 1);
		assertEquals("after\n\n[1 more lines. Use offset=2 to continue.]", run(tools, "read", read));
		assertEquals("after\nsecond\n", Files.readString(tempDir.resolve("nested/example.txt")));
	}

	@Test
	void rejectsAmbiguousEdits() throws Exception {
		Files.writeString(tempDir.resolve("example.txt"), "duplicate duplicate");
		ObjectNode edit = Json.object().put("path", "example.txt");
		edit.putArray("edits").addObject().put("oldText", "duplicate").put("newText", "changed");
		assertThrows(IllegalArgumentException.class, () -> tool(BuiltInTools.create(tempDir), "edit").execute("id", edit, new AbortSignal(), ignored -> {}));
	}

	@Test
	void findsGrepsListsAndExecutesCommands() throws Exception {
		Files.createDirectories(tempDir.resolve("src"));
		Files.writeString(tempDir.resolve("src/example.java"), "class Example {\n  String value = \"needle\";\n}\n");
		Files.writeString(tempDir.resolve("README.md"), "documentation");

		List<AgentTool> tools = BuiltInTools.create(tempDir);
		assertEquals("src/example.java", run(tools, "find", Json.object().put("pattern", "**/*.java")));
		assertTrue(run(tools, "grep", Json.object().put("pattern", "needle")).contains("src/example.java:2:"));
		String listing = run(tools, "ls", Json.object());
		assertTrue(listing.contains("README.md"));
		assertTrue(listing.contains("src/"));
		assertEquals("ok", run(tools, "shell", Json.object().put("command", shellCommandThatPrintsOk())));
	}

	@Test
	void grepFiltersFilesWithAGlobSeparateFromItsLiteralPath() throws Exception {
		Files.createDirectories(tempDir.resolve("src/nested"));
		Files.writeString(tempDir.resolve("src/Root.java"), "needle");
		Files.writeString(tempDir.resolve("src/nested/Nested.java"), "needle");
		Files.writeString(tempDir.resolve("src/nested/notes.txt"), "needle");

		List<AgentTool> tools = BuiltInTools.create(tempDir);
		String byFileName = run(
				tools,
				"grep",
				Json.object().put("pattern", "needle").put("path", "src").put("glob", "*.java"));
		assertTrue(byFileName.contains("Root.java:1:"));
		assertTrue(byFileName.contains("nested/Nested.java:1:"));
		assertFalse(byFileName.contains("notes.txt"));

		String recursive = run(
				tools,
				"grep",
				Json.object().put("pattern", "needle").put("path", "src").put("glob", "**/*.java"));
		assertTrue(recursive.contains("Root.java:1:"));
		assertTrue(recursive.contains("nested/Nested.java:1:"));
	}

	@Test
	void grepRejectsWildcardsInLiteralPathWithActionableError() {
		ObjectNode arguments = Json.object().put("pattern", "needle").put("path", "src/**/*.java");

		IllegalArgumentException error = assertThrows(
				IllegalArgumentException.class,
				() -> tool(BuiltInTools.create(tempDir), "grep")
						.execute("id", arguments, new AbortSignal(), ignored -> {}));

		assertTrue(error.getMessage().contains("path is literal"));
		assertTrue(error.getMessage().contains("glob"));
	}

	@Test
	void expandsHomePathsAndLeavesDotSlashTildeLiteral() throws Exception {
		Path home = tempDir.resolve("home");
		Path cwd = tempDir.resolve("cwd");
		Files.createDirectories(home);
		Files.createDirectories(cwd);
		Files.writeString(home.resolve("from-home.txt"), "home content");
		Files.writeString(cwd.resolve("~"), "literal tilde");
		String originalHome = System.getProperty("user.home");
		try {
			System.setProperty("user.home", home.toString());
			List<AgentTool> tools = BuiltInTools.create(cwd);
			assertEquals("home content", run(tools, "read", Json.object().put("path", "~/from-home.txt")));
			assertTrue(run(tools, "ls", Json.object().put("path", "~")).contains("from-home.txt"));
			assertEquals("literal tilde", run(tools, "read", Json.object().put("path", "./~")));

			IllegalArgumentException error = assertThrows(
					IllegalArgumentException.class,
					() -> run(tools, "read", Json.object().put("path", "~someuser/file.txt")));
			assertEquals("~user paths are not supported; use an absolute path", error.getMessage());
		} finally {
			if (originalHome == null) System.clearProperty("user.home");
			else System.setProperty("user.home", originalHome);
		}
	}

	@Test
	void grepExplainsWhetherTheGlobSelectedFiles() throws Exception {
		Files.createDirectories(tempDir.resolve("bases/example/src"));
		Files.writeString(tempDir.resolve("bases/example/src/core.clj"), "(ns example.core)");
		Files.writeString(tempDir.resolve("README.md"), "documentation");
		List<AgentTool> tools = BuiltInTools.create(tempDir);

		String wrongPrefix = run(
				tools,
				"grep",
				Json.object().put("pattern", "missing").put("glob", "src/**/*.clj"));
		assertTrue(wrongPrefix.contains("No files matched glob 'src/**/*.clj'"));
		assertTrue(wrongPrefix.contains("2 files under"));
		assertTrue(wrongPrefix.contains("relative to path"));

		String noContentMatch = run(
				tools,
				"grep",
				Json.object().put("pattern", "missing").put("path", "bases").put("glob", "**/*.clj"));
		assertEquals("No matches found in 1 files matching glob '**/*.clj'", noContentMatch);
		assertEquals(
				"No matches found in 2 files",
				run(tools, "grep", Json.object().put("pattern", "missing")));
	}

	@Test
	void readsAndListsArchiveEntriesAndRejectsArchiveEdits() throws Exception {
		Path archive = tempDir.resolve("sample.jar");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
			addZipEntry(zip, "pkg/a.clj", "first\nsecond\nthird");
			addZipEntry(zip, "pkg/b.txt", "other");
		}
		List<AgentTool> tools = BuiltInTools.create(tempDir);
		String archivePath = archive + "!";

		assertEquals("first\nsecond\nthird", run(
				tools, "read", Json.object().put("path", archivePath + "pkg/a.clj")));
		assertEquals(
				"second\n\n[1 more lines. Use offset=3 to continue.]",
				run(tools, "read", Json.object().put("path", archivePath + "pkg/a.clj").put("offset", 2).put("limit", 1)));
		assertTrue(run(tools, "read", Json.object().put("path", archivePath)).contains("pkg/"));
		String packageListing = run(tools, "read", Json.object().put("path", archivePath + "pkg/"));
		assertTrue(packageListing.contains("a.clj (18 bytes)"));
		assertTrue(packageListing.contains("b.txt (5 bytes)"));

		IOException missing = assertThrows(
				IOException.class,
				() -> run(tools, "read", Json.object().put("path", archivePath + "pkg/missing.clj")));
		assertTrue(missing.getMessage().contains("pkg/a.clj"));
		assertTrue(missing.getMessage().contains("pkg/b.txt"));

		ObjectNode edit = Json.object().put("path", archivePath + "pkg/a.clj");
		edit.putArray("edits").addObject().put("oldText", "first").put("newText", "changed");
		IllegalArgumentException readOnly = assertThrows(
				IllegalArgumentException.class, () -> run(tools, "edit", edit));
		assertEquals("archives are read-only through this tool", readOnly.getMessage());
		IllegalArgumentException writeReadOnly = assertThrows(
				IllegalArgumentException.class,
				() -> run(tools, "write", Json.object().put("path", archivePath + "new.txt").put("content", "new")));
		assertEquals("archives are read-only through this tool", writeReadOnly.getMessage());

		Path notZip = tempDir.resolve("not-zip.bin");
		Files.writeString(notZip, "not an archive");
		IOException badArchive = assertThrows(
				IOException.class,
				() -> run(tools, "read", Json.object().put("path", notZip + "!entry.txt")));
		assertTrue(badArchive.getMessage().contains(notZip.toString()));

		Path bangFile = tempDir.resolve("plain!name.txt");
		Files.writeString(bangFile, "plain bang file");
		assertEquals("plain bang file", run(tools, "read", Json.object().put("path", bangFile.toString())));
	}

	@Test
	void grepAndFindSkipGitIgnoredFilesUnlessRequested() throws Exception {
		initializeGitRepository(tempDir);
		Files.writeString(tempDir.resolve(".gitignore"), "logs/\n");
		Files.createDirectories(tempDir.resolve("logs"));
		Files.writeString(tempDir.resolve("logs/ignored.log"), "secret needle");
		List<AgentTool> tools = BuiltInTools.create(tempDir);

		assertFalse(run(tools, "grep", Json.object().put("pattern", "needle")).contains("ignored.log"));
		assertTrue(run(
				tools,
				"grep",
				Json.object().put("pattern", "needle").put("includeIgnored", true)).contains("logs/ignored.log"));
		assertEquals("No files found matching pattern", run(
				tools, "find", Json.object().put("pattern", "**/*.log")));
		assertEquals("logs/ignored.log", run(
				tools,
				"find",
				Json.object().put("pattern", "**/*.log").put("includeIgnored", true)));
	}

	@Test
	void gitIgnoreFilteringFallsBackWhenGitIsUnavailable() throws Exception {
		initializeGitRepository(tempDir);
		Files.writeString(tempDir.resolve(".gitignore"), "ignored.txt\n");
		Files.writeString(tempDir.resolve("ignored.txt"), "fallback needle");
		List<AgentTool> tools = BuiltInTools.create(
				tempDir, new GitIgnore(tempDir.resolve("missing-git-executable").toString()));

		assertTrue(run(tools, "grep", Json.object().put("pattern", "needle")).contains("ignored.txt"));
	}

	@Test
	void schemasDescribeRequiredInputs() {
		List<AgentTool> tools = BuiltInTools.create(tempDir);
		AgentTool write = tool(tools, "write");
		assertTrue(write.parameters().path("required").toString().contains("\"path\""));
		assertTrue(write.parameters().path("required").toString().contains("\"content\""));
		assertFalse(tool(tools, "ls").parameters().path("required").toString().contains("\"path\""));

		AgentTool grep = tool(tools, "grep");
		assertTrue(grep.parameters().path("properties").has("path"));
		assertTrue(grep.parameters().path("properties").has("glob"));
		assertTrue(grep.parameters().path("properties").has("includeIgnored"));
		assertTrue(grep.parameters().path("properties").path("path").path("description").asText().contains("Literal"));
		for (String name : List.of("read", "write", "edit", "grep", "find", "ls")) {
			assertTrue(tool(tools, name).parameters().path("properties").path("path")
					.path("description").asText().contains("leading ~/"));
		}
		assertTrue(tool(tools, "read").description().contains("archive.jar!"));
		assertTrue(tool(tools, "grep").description().contains("code-lens"));
	}

	private static void addZipEntry(ZipOutputStream zip, String name, String content) throws IOException {
		zip.putNextEntry(new ZipEntry(name));
		zip.write(content.getBytes(StandardCharsets.UTF_8));
		zip.closeEntry();
	}

	private static void initializeGitRepository(Path directory) throws Exception {
		Process process = new ProcessBuilder("git", "init", "-q")
				.directory(directory.toFile())
				.redirectErrorStream(true)
				.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertEquals(0, process.waitFor(), output);
	}

	private static AgentTool tool(List<AgentTool> tools, String name) {
		return tools.stream().filter(tool -> tool.name().equals(name)).findFirst().orElseThrow();
	}

	private static String run(List<AgentTool> tools, String name, ObjectNode arguments) throws Exception {
		AgentTool.ToolResult result = tool(tools, name).execute("id", arguments, new AbortSignal(), ignored -> {});
		return ((TextContent) result.content().getFirst()).text();
	}

	private static String shellCommandThatPrintsOk() {
		return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win") ? "[Console]::Write('ok')" : "printf ok";
	}
}
