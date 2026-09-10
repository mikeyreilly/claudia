package com.quaxt.codingagent;

import com.quaxt.codingagent.ai.json.Json;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SubagentHeadlessTest {
    @TempDir Path workspace;

    @Test void delegatesThroughPrintMode() throws Exception { smoke(false); }
    @Test void delegatesThroughRpcWithoutLeakingChildEvents() throws Exception { smoke(true); }

    private void smoke(boolean rpc) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            try {
                var request = Json.MAPPER.readTree(exchange.getRequestBody());
                int call = calls.incrementAndGet();
                boolean offersDelegation = false;
                for (var tool : request.path("tools")) if (tool.path("name").asText().equals("subagent")) offersDelegation = true;
                if (call == 2) {
                    assertFalse(offersDelegation);
                    assertEquals(1, request.path("messages").size());
                    assertTrue(request.path("messages").toString().contains("child brief"));
                } else assertTrue(offersDelegation);
                if (call == 3) {
                    assertTrue(request.toString().contains("child final"));
                    assertFalse(request.toString().contains("child private reasoning"));
                }
                String body = event("message_start", "{\"message\":{\"id\":\"m\",\"usage\":{\"input_tokens\":1}}}");
                if (call == 1) {
                    body += event("content_block_start", "{\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"delegate\",\"name\":\"subagent\",\"input\":{}}}")
                            + event("content_block_delta", Json.MAPPER.createObjectNode().put("index", 0)
                                    .set("delta", Json.MAPPER.createObjectNode().put("type", "input_json_delta")
                                            .put("partial_json", "{\"task\":\"child brief\",\"name\":\"Worker\"}")).toString());
                } else {
                    if (call == 2) body += event("content_block_start", "{\"index\":1,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}")
                            + event("content_block_delta", "{\"index\":1,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"child private reasoning\"}}")
                            + event("content_block_stop", "{\"index\":1}");
                    body += event("content_block_start", "{\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}")
                            + event("content_block_delta", "{\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"" + (call == 2 ? "child final" : "main final") + "\"}}");
                }
                body += event("content_block_stop", "{\"index\":0}")
                        + event("message_delta", "{\"delta\":{\"stop_reason\":\"" + (call == 1 ? "tool_use" : "end_turn") + "\"},\"usage\":{\"output_tokens\":1}}")
                        + event("message_stop", "{}");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (Throwable error) { failure.set(error); }
            finally { exchange.close(); }
        });
        server.start();
        Process process = null;
        try {
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            String executable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
            List<String> command = new ArrayList<>(List.of(executable, "-Duser.home=" + workspace, "-cp", classpath,
                    CodingAgentCli.class.getName(), "--model", "anthropic/claude-haiku-4-5", "--api-key", "fixture", "--no-session"));
            command.addAll(rpc ? List.of("--mode", "rpc") : List.of("--print", "parent brief"));
            Path output = workspace.resolve("output.txt"), errors = workspace.resolve("errors.txt");
            ProcessBuilder builder = new ProcessBuilder(command).directory(workspace.toFile())
                    .redirectOutput(output.toFile()).redirectError(errors.toFile());
            builder.environment().put("ANTHROPIC_BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort());
            process = builder.start();
            if (rpc) process.getOutputStream().write("{\"id\":\"task\",\"type\":\"prompt\",\"message\":\"parent brief\"}\n".getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Headless delegation did not finish");
            if (failure.get() != null) throw new AssertionError("Mock provider assertion failed", failure.get());
            assertEquals(0, process.exitValue(), Files.readString(errors));
            assertEquals(3, calls.get());
            String text = Files.readString(output);
            assertTrue(text.contains("main final"), text);
            assertFalse(text.contains("child private reasoning"));
            if (rpc) {
                assertFalse(text.contains("child final"), "Child streaming must not appear among root RPC events");
                for (String line : text.lines().toList()) assertFalse(Json.MAPPER.readTree(line).has("agent_id"));
            }
            assertFalse(Files.exists(workspace.resolve(".codingagent/sessions")));
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            server.stop(0);
        }
    }

    private static String event(String name, String data) { return "event: " + name + "\ndata: " + data + "\n\n"; }
}
