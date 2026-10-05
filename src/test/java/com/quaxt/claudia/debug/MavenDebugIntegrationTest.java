package com.quaxt.claudia.debug;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.quaxt.claudia.ai.json.Json;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class MavenDebugIntegrationTest {
    private static final String FIXTURE = "com.quaxt.claudia.debug.ClassFixture";
    private static final String SELECTOR = FIXTURE + "#first";
    @TempDir Path project;

    @Test
    @Timeout(90)
    void debugsExactlyOnePrecompiledMavenTest() throws Exception {
        Files.writeString(project.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.quaxt.claudia</groupId>
                  <artifactId>debug-test-fixture</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.junit.jupiter</groupId>
                      <artifactId>junit-jupiter</artifactId>
                      <version>6.1.3</version>
                      <scope>test</scope>
                    </dependency>
                  </dependencies>
                  <build><plugins><plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <version>3.5.6</version>
                    <configuration><forkCount>1</forkCount></configuration>
                  </plugin></plugins></build>
                </project>
                """);
        Path compiled = project.resolve("target/test-classes/" + FIXTURE.replace('.', '/') + ".class");
        Files.createDirectories(compiled.getParent());
        // Use the already compiled test resource; never compile or run the enclosing project here.
        try (InputStream fixture = ClassFixture.class.getResourceAsStream("ClassFixture.class")) {
            assertNotNull(fixture, "Precompiled ClassFixture.class must be on the test classpath");
            Files.copy(fixture, compiled);
        }

        ProcessHandle launched = null;
        List<ProcessHandle> owned = new ArrayList<>();
        try (DebugManager manager = new DebugManager()) {
            ObjectNode start = ok(manager, "debug_launch", obj().put("test_selector", SELECTOR)
                    .put("stop_on_entry", true).put("working_directory", project.toString()));
            String session = start.path("session_id").asText();
            assertFalse(session.isBlank(), start.toString());
            assertNotEquals(start.path("pid").asLong(), start.path("runner_pid").asLong(), start.toString());
            launched = ProcessHandle.of(start.path("runner_pid").asLong()).orElseThrow();
            owned.add(launched);
            owned.add(ProcessHandle.of(start.path("pid").asLong()).orElseThrow());
            ObjectNode stopped = await(manager, session, "stopped", Duration.ofSeconds(25), owned);
            assertEquals("entry", stopped.path("stop").path("reason").asText(), stopped.toString());
            String stopId = stopped.path("stop").path("stop_id").asText();
            assertFalse(stopId.isBlank(), stopped.toString());
            ok(manager, "debug_continue", obj().put("session_id", session).put("stop_id", stopId));
            ObjectNode completed = await(manager, session, "completed", Duration.ofSeconds(35), owned);
            // VM disconnect can precede Maven's exit and the final stdout drain.
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (!completed.has("exit_code") && System.nanoTime() < deadline) {
                rememberDescendants(launched, owned);
                Thread.sleep(50);
                completed = ok(manager, "debug_status", obj().put("session_id", session));
            }
            assertEquals("completed", completed.path("status").asText(), completed.toString());
            assertEquals(0, completed.path("exit_code").asInt(-1), completed.toString());
            deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            StringBuilder output = new StringBuilder();
            int cursor = 0;
            while (System.nanoTime() < deadline) {
                ObjectNode page = ok(manager, "debug_output", obj().put("session_id", session).put("cursor", cursor));
                for (JsonNode chunk : page.path("output")) {
                    output.append(chunk.path("text").asText());
                    cursor = chunk.path("cursor").asInt();
                }
                if (output.toString().contains("Tests run: 1") && output.toString().contains("ClassFixture")) break;
                Thread.sleep(50);
            }
            assertTrue(output.toString().contains("Tests run: 1"), output.toString());
            assertTrue(output.toString().contains("ClassFixture"), output.toString());
            Path report = project.resolve("target/surefire-reports/TEST-" + FIXTURE + ".xml");
            String xml = Files.readString(report);
            assertTrue(xml.contains("name=\"first\""), xml);
            assertFalse(xml.contains("name=\"second\""), xml);
            ObjectNode detached = ok(manager, "debug_detach", obj().put("session_id", session)
                    .put("leave_running", true).put("close_session", true));
            assertEquals("completed", detached.path("status").asText(), detached.toString());
            assertEquals("session_not_found", manager.call("debug_status", obj().put("session_id", session), project)
                    .path("code").asText());
        } finally {
            // Never scan or kill unrelated Maven processes; only this launch and its descendants.
            if (launched != null) rememberDescendants(launched, owned);
            for (int i = owned.size() - 1; i >= 0; i--) {
                ProcessHandle handle = owned.get(i);
                if (handle.isAlive()) handle.destroy();
            }
            for (int i = owned.size() - 1; i >= 0; i--) {
                ProcessHandle handle = owned.get(i);
                if (handle.isAlive()) {
                    try { handle.onExit().get(2, TimeUnit.SECONDS); }
                    catch (Exception ignored) { handle.destroyForcibly(); }
                }
            }
        }
    }

    private static void rememberDescendants(ProcessHandle launched, List<ProcessHandle> owned) {
        try (var descendants = launched.descendants()) {
            descendants.forEach(handle -> { if (!owned.contains(handle)) owned.add(handle); });
        }
    }

    private ObjectNode await(DebugManager manager, String session, String state, Duration timeout,
                                    List<ProcessHandle> owned) {
        long deadline = System.nanoTime() + timeout.toNanos();
        ObjectNode result = null;
        while (System.nanoTime() < deadline) {
            rememberDescendants(owned.get(0), owned);
            result = ok(manager, "debug_status", obj().put("session_id", session));
            if (state.equals(result.path("status").asText())) return result;
            if ("completed".equals(result.path("status").asText())) fail("Exited before " + state + ": " + result);
            result = ok(manager, "debug_wait", obj().put("session_id", session)
                    .put("cursor", result.path("event_cursor").asInt()).put("wait_ms", 200));
            if (state.equals(result.path("status").asText())) return result;
        }
        fail("Timed out waiting for " + state + ": " + result);
        return result;
    }

    private static ObjectNode obj() { return Json.MAPPER.createObjectNode(); }

    private ObjectNode ok(DebugManager manager, String operation, ObjectNode args) {
        ObjectNode result = manager.call(operation, args, project);
        assertNotEquals("error", result.path("status").asText(), operation + ": " + result);
        return result;
    }
}
