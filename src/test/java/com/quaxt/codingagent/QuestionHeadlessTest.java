package com.quaxt.codingagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.quaxt.codingagent.ai.json.Json;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.quaxt.codingagent.CodingAgentOperations.jsonObject;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(25)
class QuestionHeadlessTest {
    @TempDir Path workspace;
    record Response(String tool, ObjectNode arguments, String text) {
        static Response text(String text) { return new Response(null, null, text); }
        static Response question() { return new Response("question", jsonObject().put("question", "Which scope?"), null); }
    }

    @Test void rpcAnswersAChildQuestionWhileMainWaitsAndPreservesCompletionResponses() throws Exception {
        try (var fixture = new Fixture(true)) {
            fixture.steps.add(request -> new Response("subagent", jsonObject().put("task", "Investigate scope").put("name", "Worker"), null));
            fixture.steps.add(request -> {
                assertTrue(request.path("system").asText().contains("Current mode: Plan"));
                return Response.question();
            });
            fixture.steps.add(request -> {
                assertTrue(request.path("messages").toString().contains("Custom scope"));
                return Response.text("Private child answer");
            });
            fixture.steps.add(request -> Response.text("Reviewed plan"));
            fixture.start();
            fixture.send(jsonObject().put("id", "prompt").put("type", "prompt").put("message", "Plan this"));
            var question = fixture.until(node -> node.path("type").asText().equals("question_requested"));
            assertNotEquals("main", question.path("agentId").asText());
            String questionId = question.path("questionId").asText();
            fixture.send(jsonObject().put("id", "state").put("type", "get_state"));
            var state = fixture.response("state");
            assertEquals("plan", state.path("data").path("agentMode").asText());
            assertEquals(questionId, state.path("data").path("pendingQuestions").get(0).path("questionId").asText());
            fixture.send(jsonObject().put("id", "busy").put("type", "set_agent_mode").put("agentMode", "build"));
            assertFalse(fixture.response("busy").path("success").asBoolean());
            fixture.send(jsonObject().put("id", "answer").put("type", "answer_question").put("questionId", questionId).put("answer", "Custom scope"));
            assertTrue(fixture.response("answer").path("success").asBoolean());
            assertTrue(fixture.response("prompt").path("success").asBoolean());
            assertFalse(Files.exists(workspace.resolve("implemented.txt")));
            fixture.send(jsonObject().put("id", "duplicate").put("type", "answer_question").put("questionId", questionId).put("answer", "Again"));
            assertFalse(fixture.response("duplicate").path("success").asBoolean());
            fixture.send(jsonObject().put("id", "build").put("type", "set_agent_mode").put("agentMode", "build"));
            assertEquals("build", fixture.response("build").path("data").path("agentMode").asText());
            assertFalse(Files.exists(workspace.resolve("implemented.txt")), "Switching must not start implementation");
            fixture.steps.add(request -> {
                assertTrue(request.path("system").asText().contains("Current mode: Build"));
                assertTrue(request.path("messages").toString().contains("Reviewed plan"));
                return new Response("write", jsonObject().put("path", "implemented.txt").put("content", "done"), null);
            });
            fixture.steps.add(request -> Response.text("Implementation finished"));
            fixture.send(jsonObject().put("id", "implement").put("type", "prompt").put("message", "Implement the reviewed plan"));
            assertTrue(fixture.response("implement").path("success").asBoolean());
            assertEquals("done", Files.readString(workspace.resolve("implemented.txt")));
            fixture.send(jsonObject().put("id", "fresh").put("type", "new_session"));
            assertTrue(fixture.response("fresh").path("success").asBoolean());
            fixture.send(jsonObject().put("id", "fresh-state").put("type", "get_state"));
            assertEquals("build", fixture.response("fresh-state").path("data").path("agentMode").asText());
            fixture.finish();
            assertTrue(fixture.seen.stream().anyMatch(node -> node.path("text").asText().equals("Reviewed plan")));
            assertFalse(fixture.seen.stream().anyMatch(node -> node.path("text").asText().equals("Private child answer")));
        }
    }

    @Test void rpcDeclineAndAbortReleaseWaitingTurns() throws Exception {
        try (var fixture = new Fixture(true)) {
            fixture.steps.add(request -> Response.question());
            fixture.steps.add(request -> {
                assertTrue(request.path("messages").toString().contains("declined"));
                return Response.text("Scope remains unresolved");
            });
            fixture.steps.add(request -> Response.question());
            fixture.start();
            fixture.send(jsonObject().put("id", "first").put("type", "prompt").put("message", "Plan"));
            var question = fixture.until(node -> node.path("type").asText().equals("question_requested"));
            fixture.send(jsonObject().put("id", "decline").put("type", "answer_question")
                    .put("questionId", question.path("questionId").asText()).put("decline", true));
            assertTrue(fixture.response("first").path("success").asBoolean());
            fixture.send(jsonObject().put("id", "second").put("type", "prompt").put("message", "Ask again"));
            fixture.until(node -> node.path("type").asText().equals("question_requested"));
            fixture.send(jsonObject().put("id", "abort").put("type", "abort"));
            assertTrue(fixture.response("abort").path("success").asBoolean());
            assertFalse(fixture.response("second").path("success").asBoolean());
            fixture.finish();
        }
    }

    @Test void rpcEofReleasesPendingQuestionsAndDrainsAcceptedPromptsInOrder() throws Exception {
        try (var fixture = new Fixture(true)) {
            fixture.steps.add(request -> Response.question());
            fixture.steps.add(request -> {
                assertTrue(request.path("messages").toString().contains("unavailable"));
                return Response.text("Please clarify the scope");
            });
            fixture.steps.add(request -> {
                assertTrue(request.path("messages").toString().contains("Second prompt"));
                return Response.text("Second prompt handled");
            });
            fixture.start();
            fixture.send(jsonObject().put("id", "first").put("type", "prompt").put("message", "First prompt"));
            fixture.until(node -> node.path("type").asText().equals("question_requested"));
            fixture.send(jsonObject().put("id", "second").put("type", "prompt").put("message", "Second prompt"));
            fixture.finish();
            var responses = fixture.seen.stream().filter(node -> node.path("command").asText().equals("prompt")).toList();
            assertEquals(List.of("first", "second"), responses.stream().map(node -> node.path("id").asText()).toList());
            assertTrue(responses.stream().allMatch(node -> node.path("success").asBoolean()));
        }
    }

    @Test void rpcCompactionKeepsInputResponsiveAndQueuesTheNextPrompt() throws Exception {
        try (var fixture = new Fixture(true)) {
            var started = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            fixture.steps.add(request -> Response.text("Plan: change X and verify Y"));
            fixture.steps.add(request -> {
                assertTrue(request.path("messages").toString().contains("Keep the verification steps"));
                started.countDown();
                try { assertTrue(release.await(8, TimeUnit.SECONDS)); }
                catch (InterruptedException error) { throw new AssertionError(error); }
                return Response.text("Retained plan and verification steps");
            });
            fixture.steps.add(request -> {
                assertTrue(request.path("messages").toString().contains("Retained plan and verification steps"));
                assertTrue(request.path("system").asText().contains("Current mode: Plan"));
                return Response.text("Refined plan");
            });
            fixture.start();
            try {
                fixture.send(jsonObject().put("id", "first").put("type", "prompt").put("message", "Plan"));
                assertTrue(fixture.response("first").path("success").asBoolean());
                fixture.send(jsonObject().put("id", "compact").put("type", "compact").put("customInstructions", "Keep the verification steps"));
                assertTrue(started.await(3, TimeUnit.SECONDS));
                fixture.send(jsonObject().put("id", "state").put("type", "get_state"));
                assertTrue(fixture.response("state").path("data").path("isCompacting").asBoolean());
                fixture.send(jsonObject().put("id", "next").put("type", "prompt").put("message", "Refine"));
                release.countDown();
                assertEquals("Retained plan and verification steps", fixture.response("compact").path("data").path("summary").asText());
                assertTrue(fixture.response("next").path("success").asBoolean());
                fixture.finish();
            } finally { release.countDown(); }
        }
    }

    @Test void printModeReportsUnavailableQuestionsWithoutReadingInput() throws Exception {
        try (var fixture = new Fixture(false)) {
            fixture.steps.add(request -> {
                assertTrue(request.path("system").asText().contains("Current mode: Plan"));
                return Response.question();
            });
            fixture.steps.add(request -> {
                assertTrue(request.path("messages").toString().contains("unavailable"));
                return Response.text("Which scope should the plan cover?");
            });
            fixture.start();
            fixture.finish();
            assertTrue(fixture.printOutput.contains("Which scope should the plan cover?"));
        }
    }

    final class Fixture implements AutoCloseable {
        final boolean rpc;
        final HttpServer server;
        final Queue<Function<JsonNode, Response>> steps = new ConcurrentLinkedQueue<>();
        final BlockingQueue<String> output = new LinkedBlockingQueue<>();
        final List<JsonNode> seen = new ArrayList<>();
        final List<JsonNode> unmatched = new ArrayList<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        Process process;
        BufferedWriter input;
        Thread outputReader;
        String printOutput = "";
        final Path errors = workspace.resolve("errors.txt");

        Fixture(boolean rpc) throws Exception {
            this.rpc = rpc;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/messages", exchange -> {
                try {
                    var request = Json.MAPPER.readTree(exchange.getRequestBody());
                    var step = steps.poll();
                    assertNotNull(step, "Unexpected provider call");
                    Response response = step.apply(request);
                    String body = event("message_start", "{\"message\":{\"id\":\"m\",\"usage\":{\"input_tokens\":1}}}");
                    if (response.tool != null) {
                        body += event("content_block_start", jsonObject().put("index", 0).set("content_block",
                                jsonObject().put("type", "tool_use").put("id", "call").put("name", response.tool).set("input", jsonObject())).toString());
                        body += event("content_block_delta", jsonObject().put("index", 0).set("delta",
                                jsonObject().put("type", "input_json_delta").put("partial_json", response.arguments.toString())).toString());
                    } else {
                        body += event("content_block_start", "{\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}");
                        body += event("content_block_delta", jsonObject().put("index", 0).set("delta",
                                jsonObject().put("type", "text_delta").put("text", response.text)).toString());
                    }
                    body += event("content_block_stop", "{\"index\":0}")
                            + event("message_delta", "{\"delta\":{\"stop_reason\":\"" + (response.tool == null ? "end_turn" : "tool_use")
                            + "\"},\"usage\":{\"output_tokens\":1}}") + event("message_stop", "{}");
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (Throwable error) { failure.set(error); }
                finally { exchange.close(); }
            });
        }

        void start() throws Exception {
            server.start();
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            String executable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
            var command = new ArrayList<>(List.of(executable, "-Duser.home=" + workspace, "-cp", classpath,
                    CodingAgentCli.class.getName(), "--model", "anthropic/claude-haiku-4-5", "--api-key", "fixture", "--no-session", "--agent-mode", "plan"));
            command.addAll(rpc ? List.of("--mode", "rpc") : List.of("--print", "Plan the feature"));
            var builder = new ProcessBuilder(command).directory(workspace.toFile()).redirectError(errors.toFile());
            builder.environment().put("ANTHROPIC_BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort());
            process = builder.start();
            input = process.outputWriter(StandardCharsets.UTF_8);
            outputReader = Thread.ofVirtual().start(() -> {
                try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
                    String line; while ((line = reader.readLine()) != null) output.add(line);
                } catch (IOException error) { failure.compareAndSet(null, error); }
            });
        }

        void send(ObjectNode command) throws Exception { input.write(command.toString()); input.newLine(); input.flush(); }
        JsonNode response(String id) throws Exception { return until(node -> node.path("id").asText().equals(id) && node.path("type").asText().equals("response")); }
        JsonNode until(Predicate<JsonNode> matches) throws Exception {
            for (var iterator = unmatched.iterator(); iterator.hasNext();) {
                JsonNode node = iterator.next();
                if (matches.test(node)) { iterator.remove(); return node; }
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (System.nanoTime() < deadline) {
                String line = output.poll(100, TimeUnit.MILLISECONDS);
                if (failure.get() != null) throw new AssertionError(failure.get());
                if (line == null) continue;
                JsonNode node = Json.MAPPER.readTree(line); seen.add(node);
                if (matches.test(node)) return node;
                unmatched.add(node);
            }
            fail("Timed out waiting for RPC output; seen=" + seen + "; errors=" + Files.readString(errors));
            return null;
        }

        void finish() throws Exception {
            input.close();
            assertTrue(process.waitFor(8, TimeUnit.SECONDS), "Process did not finish after EOF");
            outputReader.join(1000);
            assertEquals(0, process.exitValue(), Files.readString(errors));
            if (failure.get() != null) throw new AssertionError(failure.get());
            String line;
            while ((line = output.poll()) != null) {
                if (rpc) seen.add(Json.MAPPER.readTree(line)); else printOutput += line + "\n";
            }
            assertTrue(steps.isEmpty(), "Expected provider calls did not happen");
        }

        @Override public void close() {
            if (process != null && process.isAlive()) process.destroyForcibly();
            server.stop(0);
        }
    }

    static String event(String name, String data) { return "event: " + name + "\ndata: " + data + "\n\n"; }
}
