package com.quaxt.claudia;

import com.quaxt.claudia.agent.AgentTool;
import com.quaxt.claudia.ai.providers.FauxProvider;
import com.quaxt.claudia.ai.providers.ProviderState;
import com.quaxt.claudia.ai.types.AssistantMessage;
import com.quaxt.claudia.ai.types.Model;
import com.quaxt.claudia.ai.types.StopReason;
import com.quaxt.claudia.ai.types.TextContent;
import com.quaxt.claudia.ai.types.ThinkingLevel;
import com.quaxt.claudia.ai.util.AbortSignal;
import com.quaxt.claudia.mcp.McpConfiguration;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static com.quaxt.claudia.ClaudiaOperations.jsonObject;
import static org.junit.jupiter.api.Assertions.*;

/** Runtime ownership tests use multiple live instances, independent of the CLI. */
@Timeout(30)
class AgentRuntimeTest {
    @TempDir Path directory;

    @Test
    void conversationsToolsAndListenersBelongToTheirRuntime() throws Exception {
        try (var first = new ClaudiaOperations(); var second = new ClaudiaOperations()) {
            Path firstCwd = Files.createDirectory(directory.resolve("first"));
            Path secondCwd = Files.createDirectory(directory.resolve("second"));
            configure(first, firstCwd, "first answer");
            configure(second, secondCwd, "second answer");
            var firstEvents = new ArrayList<>();
            first.subscribe(firstEvents::add);
            second.prompt("second question");
            assertTrue(first.state().messages().isEmpty());
            assertTrue(firstEvents.isEmpty());
            first.prompt("first question");
            assertEquals("first answer", ClaudiaOperations.text((AssistantMessage) first.state().messages().getLast()));
            assertEquals("second answer", ClaudiaOperations.text((AssistantMessage) second.state().messages().getLast()));
            execute(first, firstCwd, "write", jsonObject().put("path", "result.txt").put("content", "first"));
            execute(second, secondCwd, "write", jsonObject().put("path", "result.txt").put("content", "second"));
            assertEquals("first", Files.readString(firstCwd.resolve("result.txt")));
            assertEquals("second", Files.readString(secondCwd.resolve("result.txt")));
        }
    }

    @Test
    void providerInitializationAndCredentialsDoNotAffectOtherRuntimes() {
        try (var first = new ClaudiaOperations(); var second = new ClaudiaOperations()) {
            first.applicationPaths(new ClaudiaPaths(directory.resolve("first")));
            second.applicationPaths(new ClaudiaPaths(directory.resolve("second")));
            first.initializeCoreProviders();
            var chat = first.providerState(ProviderState.Role.CHATGPT);
            chat.clientId = "first-client";
            second.initializeCoreProviders();
            assertEquals("first-client", chat.clientId);
            assertSame(first, chat.credentials);
            assertSame(second, second.providerState(ProviderState.Role.CHATGPT).credentials);
            for (var role : ProviderState.Role.values()) {
                assertNotSame(first.providerState(role), second.providerState(role));
            }
            assertNotSame(first.coreProviderModels("chatgpt").getFirst(), second.coreProviderModels("chatgpt").getFirst());
            assertNotEquals(first.applicationPaths().authFile(), second.applicationPaths().authFile());
        }
    }

    @Test
    void closeKillsOnlyOwnedProcessesAndRejectsNewWork() throws Exception {
        try (var first = new ClaudiaOperations(); var second = new ClaudiaOperations()) {
            var started = execute(first, directory, "shell", jsonObject()
                    .put("command", ShellStdioFixture.command("wait")).put("yield_ms", 2000));
            String id = (String) ((Map<?, ?>) started.details).get("session_id");
            var matcher = Pattern.compile("PID=(\\d+)").matcher(((TextContent) started.content.getFirst()).text);
            assertTrue(matcher.find());
            long pid = Long.parseLong(matcher.group(1));
            var other = execute(second, directory, "shell", jsonObject()
                    .put("command", ShellStdioFixture.command("eof")).put("yield_ms", 2000));
            String otherId = (String) ((Map<?, ?>) other.details).get("session_id");
            assertThrows(IllegalArgumentException.class,
                    () -> execute(second, directory, "shell_input", jsonObject().put("session_id", id)));
            first.close();
            var process = ProcessHandle.of(pid);
            if (process.isPresent()) process.get().onExit().get(5, TimeUnit.SECONDS);
            assertThrows(IllegalStateException.class, () -> first.prompt("continue"));
            assertThrows(IllegalStateException.class,
                    () -> execute(first, directory, "shell", jsonObject().put("command", "echo unreachable")));
            assertThrows(IllegalStateException.class,
                    () -> first.mcpCreateManager(new McpConfiguration(Map.of(), List.of()), directory));
            var result = execute(second, directory, "shell_input", jsonObject().put("session_id", otherId)
                    .put("input", "still alive\n").put("close_stdin", true).put("yield_ms", 5000));
            assertFalse(result.isError);
            assertTrue(((TextContent) result.content.getFirst()).text.contains("Received: still alive"));
        }
    }

    private void configure(ClaudiaOperations runtime, Path cwd, String response) {
        var model = new Model();
        model.id = "faux-1";
        model.name = "Faux";
        model.api = "faux";
        model.provider = "faux";
        model.contextWindow = 100_000;
        model.maxTokens = 4096;
        var provider = new FauxProvider("faux", "faux", List.of(model));
        var answer = new AssistantMessage("faux", "faux", "faux-1");
        answer.content.add(new TextContent(response, null));
        answer.stopReason = StopReason.STOP;
        provider.pendingResponses.add(new FauxProvider.ResponseStep.Message(answer));
        runtime.applicationPaths(new ClaudiaPaths(cwd.resolve("home")));
        runtime.configureAgent(provider, model, cwd, "", null, ThinkingLevel.OFF);
    }

    private AgentTool.ToolResult execute(ClaudiaOperations runtime, Path cwd, String name, ObjectNode arguments) throws Exception {
        var tool = runtime.builtInTools(cwd, ignored -> {}).stream()
                .filter(candidate -> ClaudiaOperations.toolName(candidate).equals(name)).findFirst().orElseThrow();
        return runtime.executeTool(tool, "test", arguments, new AbortSignal(), ignored -> {});
    }
}
