package com.quaxt.codingagent.cli.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
	void schemasDescribeRequiredInputs() {
		List<AgentTool> tools = BuiltInTools.create(tempDir);
		AgentTool write = tool(tools, "write");
		assertTrue(write.parameters().path("required").toString().contains("\"path\""));
		assertTrue(write.parameters().path("required").toString().contains("\"content\""));
		assertFalse(tool(tools, "ls").parameters().path("required").toString().contains("\"path\""));

		AgentTool grep = tool(tools, "grep");
		assertTrue(grep.parameters().path("properties").has("path"));
		assertTrue(grep.parameters().path("properties").has("glob"));
		assertTrue(grep.parameters().path("properties").path("path").path("description").asText().contains("Literal"));
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
