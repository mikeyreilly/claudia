package works.earendil.pi.cli.tools;

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
import works.earendil.pi.agent.AgentTool;
import works.earendil.pi.ai.json.Json;
import works.earendil.pi.ai.types.TextContent;
import works.earendil.pi.ai.util.AbortSignal;

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
		assertEquals("ok", run(tools, "bash", Json.object().put("command", "printf ok")));
	}

	@Test
	void schemasDescribeRequiredInputs() {
		AgentTool write = tool(BuiltInTools.create(tempDir), "write");
		assertTrue(write.parameters().path("required").toString().contains("\"path\""));
		assertTrue(write.parameters().path("required").toString().contains("\"content\""));
		assertFalse(tool(BuiltInTools.create(tempDir), "ls").parameters().path("required").toString().contains("\"path\""));
	}

	private static AgentTool tool(List<AgentTool> tools, String name) {
		return tools.stream().filter(tool -> tool.name().equals(name)).findFirst().orElseThrow();
	}

	private static String run(List<AgentTool> tools, String name, ObjectNode arguments) throws Exception {
		AgentTool.ToolResult result = tool(tools, name).execute("id", arguments, new AbortSignal(), ignored -> {});
		return ((TextContent) result.content().getFirst()).text();
	}
}
