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
import com.quaxt.codingagent.CodingAgentOperations;
import com.quaxt.codingagent.agent.AgentTool;
import com.quaxt.codingagent.ai.types.TextContent;
import com.quaxt.codingagent.ai.util.AbortSignal;

class BuiltInToolsTest {
	@TempDir Path tempDir;

	@Test
	void readsWritesAndEditsFiles() throws Exception {
		List<AgentTool> tools = CodingAgentOperations.gitIgnore("git").builtInTools(tempDir, ignored -> {});
		ObjectNode write = CodingAgentOperations.jsonObject().put("path", "nested/example.txt").put("content", "before\nsecond\n");
		assertTrue(run(tools, "write", write).contains("Successfully wrote"));

		ObjectNode edit = CodingAgentOperations.jsonObject().put("path", "nested/example.txt");
		edit.putArray("edits").addObject().put("oldText", "before").put("newText", "after");
		assertTrue(run(tools, "edit", edit).contains("Successfully replaced"));

		ObjectNode read = CodingAgentOperations.jsonObject().put("path", "nested/example.txt").put("offset", 1).put("limit", 1);
		assertEquals("after\n\n[1 more lines. Use offset=2 to continue.]", run(tools, "read", read));
		assertEquals("after\nsecond\n", Files.readString(tempDir.resolve("nested/example.txt")));
	}

	@Test
	void rejectsAmbiguousEdits() throws Exception {
		Files.writeString(tempDir.resolve("example.txt"), "duplicate duplicate");
		ObjectNode edit = CodingAgentOperations.jsonObject().put("path", "example.txt");
		edit.putArray("edits").addObject().put("oldText", "duplicate").put("newText", "changed");
		assertThrows(IllegalArgumentException.class, () -> CodingAgentOperations.executeTool(
				tool(CodingAgentOperations.gitIgnore("git").builtInTools(tempDir, ignored -> {}), "edit"), "id", edit, new AbortSignal(), ignored -> {}));
	}

	@Test
	void findsGrepsListsAndExecutesCommands() throws Exception {
		Files.createDirectories(tempDir.resolve("src"));
		Files.writeString(tempDir.resolve("src/example.java"), "class Example {\n  String value = \"needle\";\n}\n");
		Files.writeString(tempDir.resolve("README.md"), "documentation");

		List<AgentTool> tools = CodingAgentOperations.gitIgnore("git").builtInTools(tempDir, ignored -> {});
		assertEquals("src/example.java", run(tools, "find", CodingAgentOperations.jsonObject().put("pattern", "**/*.java")));
		assertTrue(run(tools, "grep", CodingAgentOperations.jsonObject().put("pattern", "needle")).contains("src/example.java:2:"));
		String listing = run(tools, "ls", CodingAgentOperations.jsonObject());
		assertTrue(listing.contains("README.md"));
		assertTrue(listing.contains("src/"));
		assertEquals("ok", run(tools, "shell", CodingAgentOperations.jsonObject().put("command", shellCommandThatPrintsOk())));
	}

	@Test
	void grepFiltersFilesWithAGlobSeparateFromItsLiteralPath() throws Exception {
		Files.createDirectories(tempDir.resolve("src/nested"));
		Files.writeString(tempDir.resolve("src/Root.java"), "needle");
		Files.writeString(tempDir.resolve("src/nested/Nested.java"), "needle");
		Files.writeString(tempDir.resolve("src/nested/notes.txt"), "needle");

		List<AgentTool> tools = CodingAgentOperations.gitIgnore("git").builtInTools(tempDir, ignored -> {});
		String byFileName = run(
				tools,
				"grep",
				CodingAgentOperations.jsonObject().put("pattern", "needle").put("path", "src").put("glob", "*.java"));
		assertTrue(byFileName.contains("Root.java:1:"));
		assertTrue(byFileName.contains("nested/Nested.java:1:"));
		assertFalse(byFileName.contains("notes.txt"));

		String recursive = run(
				tools,
				"grep",
				CodingAgentOperations.jsonObject().put("pattern", "needle").put("path", "src").put("glob", "**/*.java"));
		assertTrue(recursive.contains("Root.java:1:"));
		assertTrue(recursive.contains("nested/Nested.java:1:"));
	}

	@Test
	void grepRejectsWildcardsInLiteralPathWithActionableError() {
		ObjectNode arguments = CodingAgentOperations.jsonObject().put("pattern", "needle").put("path", "src/**/*.java");

		IllegalArgumentException error = assertThrows(
				IllegalArgumentException.class,
				() -> CodingAgentOperations.executeTool(
						tool(CodingAgentOperations.gitIgnore("git").builtInTools(tempDir, ignored -> {}), "grep"),
						"id",
						arguments,
						new AbortSignal(),
						ignored -> {}));

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
			List<AgentTool> tools = CodingAgentOperations.gitIgnore("git").builtInTools(cwd, ignored -> {});
			assertEquals("home content", run(tools, "read", CodingAgentOperations.jsonObject().put("path", "~/from-home.txt")));
			assertTrue(run(tools, "ls", CodingAgentOperations.jsonObject().put("path", "~")).contains("from-home.txt"));
			assertEquals("literal tilde", run(tools, "read", CodingAgentOperations.jsonObject().put("path", "./~")));

			IllegalArgumentException error = assertThrows(
					IllegalArgumentException.class,
					() -> run(tools, "read", CodingAgentOperations.jsonObject().put("path", "~someuser/file.txt")));
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
		List<AgentTool> tools = CodingAgentOperations.gitIgnore("git").builtInTools(tempDir, ignored -> {});

		String wrongPrefix = run(
				tools,
				"grep",
				CodingAgentOperations.jsonObject().put("pattern", "missing").put("glob", "src/**/*.clj"));
		assertTrue(wrongPrefix.contains("No files matched glob 'src/**/*.clj'"));
		assertTrue(wrongPrefix.contains("2 files under"));
		assertTrue(wrongPrefix.contains("relative to path"));

		String noContentMatch = run(
				tools,
				"grep",
				CodingAgentOperations.jsonObject().put("pattern", "missing").put("path", "bases").put("glob", "**/*.clj"));
		assertEquals("No matches found in 1 files matching glob '**/*.clj'", noContentMatch);
		assertEquals(
				"No matches found in 2 files",
				run(tools, "grep", CodingAgentOperations.jsonObject().put("pattern", "missing")));
	}

	@Test
	void readsAndListsArchiveEntriesAndRejectsArchiveEdits() throws Exception {
		Path archive = tempDir.resolve("sample.jar");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
			addZipEntry(zip, "pkg/a.clj", "first\nsecond\nthird");
			addZipEntry(zip, "pkg/b.txt", "other");
		}
		List<AgentTool> tools = CodingAgentOperations.gitIgnore("git").builtInTools(tempDir, ignored -> {});
		String archivePath = archive + "!";

		assertEquals("first\nsecond\nthird", run(
				tools, "read", CodingAgentOperations.jsonObject().put("path", archivePath + "pkg/a.clj")));
		assertEquals(
				"second\n\n[1 more lines. Use offset=3 to continue.]",
				run(tools, "read", CodingAgentOperations.jsonObject().put("path", archivePath + "pkg/a.clj").put("offset", 2).put("limit", 1)));
		assertTrue(run(tools, "read", CodingAgentOperations.jsonObject().put("path", archivePath)).contains("pkg/"));
		String packageListing = run(tools, "read", CodingAgentOperations.jsonObject().put("path", archivePath + "pkg/"));
		assertTrue(packageListing.contains("a.clj (18 bytes)"));
		assertTrue(packageListing.contains("b.txt (5 bytes)"));

		IOException missing = assertThrows(
				IOException.class,
				() -> run(tools, "read", CodingAgentOperations.jsonObject().put("path", archivePath + "pkg/missing.clj")));
		assertTrue(missing.getMessage().contains("pkg/a.clj"));
		assertTrue(missing.getMessage().contains("pkg/b.txt"));

		ObjectNode edit = CodingAgentOperations.jsonObject().put("path", archivePath + "pkg/a.clj");
		edit.putArray("edits").addObject().put("oldText", "first").put("newText", "changed");
		IllegalArgumentException readOnly = assertThrows(
				IllegalArgumentException.class, () -> run(tools, "edit", edit));
		assertEquals("archives are read-only through this tool", readOnly.getMessage());
		IllegalArgumentException writeReadOnly = assertThrows(
				IllegalArgumentException.class,
				() -> run(tools, "write", CodingAgentOperations.jsonObject().put("path", archivePath + "new.txt").put("content", "new")));
		assertEquals("archives are read-only through this tool", writeReadOnly.getMessage());

		Path notZip = tempDir.resolve("not-zip.bin");
		Files.writeString(notZip, "not an archive");
		IOException badArchive = assertThrows(
				IOException.class,
				() -> run(tools, "read", CodingAgentOperations.jsonObject().put("path", notZip + "!entry.txt")));
		assertTrue(badArchive.getMessage().contains(notZip.toString()));

		Path bangFile = tempDir.resolve("plain!name.txt");
		Files.writeString(bangFile, "plain bang file");
		assertEquals("plain bang file", run(tools, "read", CodingAgentOperations.jsonObject().put("path", bangFile.toString())));
	}

	@Test
	void grepAndFindSkipGitIgnoredFilesUnlessRequested() throws Exception {
		initializeGitRepository(tempDir);
		Files.writeString(tempDir.resolve(".gitignore"), "logs/\n");
		Files.createDirectories(tempDir.resolve("logs"));
		Files.writeString(tempDir.resolve("logs/ignored.log"), "secret needle");
		List<AgentTool> tools = CodingAgentOperations.gitIgnore("git").builtInTools(tempDir, ignored -> {});

		assertFalse(run(tools, "grep", CodingAgentOperations.jsonObject().put("pattern", "needle")).contains("ignored.log"));
		assertTrue(run(
				tools,
				"grep",
				CodingAgentOperations.jsonObject().put("pattern", "needle").put("includeIgnored", true)).contains("logs/ignored.log"));
		assertEquals("No files found matching pattern", run(
				tools, "find", CodingAgentOperations.jsonObject().put("pattern", "**/*.log")));
		assertEquals("logs/ignored.log", run(
				tools,
				"find",
				CodingAgentOperations.jsonObject().put("pattern", "**/*.log").put("includeIgnored", true)));
	}

	@Test
	void gitIgnoreFilteringFallsBackWhenGitIsUnavailable() throws Exception {
		initializeGitRepository(tempDir);
		Files.writeString(tempDir.resolve(".gitignore"), "ignored.txt\n");
		Files.writeString(tempDir.resolve("ignored.txt"), "fallback needle");
		List<AgentTool> tools = CodingAgentOperations.gitIgnore(tempDir.resolve("missing-git-executable").toString()).builtInTools(tempDir, ignored -> {});

		assertTrue(run(tools, "grep", CodingAgentOperations.jsonObject().put("pattern", "needle")).contains("ignored.txt"));
	}

	@Test
	void schemasDescribeRequiredInputs() {
		List<AgentTool> tools = CodingAgentOperations.gitIgnore("git").builtInTools(tempDir, ignored -> {});
		ObjectNode write = CodingAgentOperations.toolParameters(tool(tools, "write"));
		assertTrue(write.path("required").toString().contains("\"path\""));
		assertTrue(write.path("required").toString().contains("\"content\""));
		assertFalse(CodingAgentOperations.toolParameters(tool(tools, "ls"))
				.path("required").toString().contains("\"path\""));

		ObjectNode grep = CodingAgentOperations.toolParameters(tool(tools, "grep"));
		assertTrue(grep.path("properties").has("path"));
		assertTrue(grep.path("properties").has("glob"));
		assertTrue(grep.path("properties").has("includeIgnored"));
		assertTrue(grep.path("properties").path("path").path("description").asText().contains("Literal"));
		for (String name : List.of("read", "write", "edit", "grep", "find", "ls")) {
			assertTrue(CodingAgentOperations.toolParameters(tool(tools, name)).path("properties").path("path")
					.path("description").asText().contains("leading ~/"));
		}
		assertTrue(CodingAgentOperations.toolDescription(tool(tools, "read")).contains("archive.jar!"));
		assertTrue(CodingAgentOperations.toolDescription(tool(tools, "grep")).contains("code-lens"));
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
		return tools.stream()
				.filter(tool -> CodingAgentOperations.toolName(tool).equals(name))
				.findFirst()
				.orElseThrow();
	}

	private static String run(List<AgentTool> tools, String name, ObjectNode arguments) throws Exception {
		AgentTool.ToolResult result = CodingAgentOperations.executeTool(
				tool(tools, name), "id", arguments, new AbortSignal(), ignored -> {});
		return ((TextContent) result.content.getFirst()).text;
	}

	private static String shellCommandThatPrintsOk() {
		return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win") ? "[Console]::Write('ok')" : "printf ok";
	}
}
