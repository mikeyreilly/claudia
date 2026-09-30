package com.quaxt.codingagent;

import com.quaxt.codingagent.ai.auth.Credential;
import com.quaxt.codingagent.ai.providers.ProviderState;
import com.quaxt.codingagent.ai.types.Model;
import com.quaxt.codingagent.terminal.Terminal;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class ModelSelectorTest {
    @TempDir Path workspace;

    @Test
    void modelsSelectorExcludesOtherProviders() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Model current = fixture.configure("openai");
            fixture.send("github-copilot\u001b[27u");
            invokeCommand(fixture.cli, "/models");

            String rendered = fixture.rendered();
            assertTrue(rendered.contains("No matching options"), "Other providers must not be searchable in /models");
            assertFalse(rendered.contains("Refreshing GitHub Copilot models"));
            assertEquals("openai", fixture.runtime.state().model().provider);
            assertEquals(current.id, fixture.runtime.state().model().id);
        }
    }

    @Test
    void usesOnlyTheActiveProvidersInitializedModels() throws Exception {
        try (Fixture fixture = new Fixture()) {
            for (String provider : List.of("openai", "chatgpt", "anthropic", "google")) {
                fixture.configure(provider);
                List<Model> models = selectableModels(fixture.cli);
                assertEquals(fixture.runtime.coreProviderModels(provider), models);
                assertTrue(models.stream().allMatch(model -> model.provider.equals(provider)));
            }
        }
    }

    @Test
    void selectingAnotherModelKeepsTheProviderAndSavesTheDefault() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Model current = fixture.configure("chatgpt");
            Model next = fixture.runtime.coreProviderModels("chatgpt").get(1);
            fixture.runtime.restoreMessages(List.of(CodingAgentOperations.userMessage("old session")));
            fixture.send("\u001b[B\r");
            invokeCommand(fixture.cli, "/models");

            assertNotEquals(current.id, next.id);
            assertEquals("chatgpt", fixture.runtime.state().model().provider);
            assertEquals(next.id, fixture.runtime.state().model().id);
            assertTrue(fixture.runtime.state().messages().isEmpty());
            assertEquals("chatgpt", fixture.runtime.loadSettings().defaultProvider);
            assertEquals(next.id, fixture.runtime.loadSettings().defaultModel);
            assertTrue(fixture.rendered().contains("* current"));
        }
    }

    @Test
    void cancelledInitialPickerRemembersLoginProviderUntilLogout() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.loginOpenaiWithoutModel();

            assertNull(fixture.runtime.state().model());
            assertEquals(fixture.runtime.coreProviderModels("openai"), selectableModels(fixture.cli));
            fixture.send("github-copilot\u001b[27u");
            invokeCommand(fixture.cli, "/models");
            assertTrue(fixture.rendered().contains("No matching options"));

            invokeCommand(fixture.cli, "/logout");
            assertTrue(selectableModels(fixture.cli).isEmpty());
            assertFalse(java.nio.file.Files.readString(new CodingAgentPaths(workspace.resolve("home")).authFile()).contains("test-only-api-key"));
            invokeCommand(fixture.cli, "/models");
            assertTrue(fixture.rendered().contains("No provider is selected"));
        }
    }

    @Test
    void newLoginOverridesThePickerWithoutChangingACancelledConversation() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Model previous = fixture.configure("chatgpt");
            fixture.loginOpenaiWithoutModel();

            assertEquals("chatgpt", fixture.runtime.state().model().provider);
            assertEquals(previous.id, fixture.runtime.state().model().id);
            assertEquals(fixture.runtime.coreProviderModels("openai"), selectableModels(fixture.cli));
            invokeCommand(fixture.cli, "/logout");
            assertEquals(fixture.runtime.coreProviderModels("chatgpt"), selectableModels(fixture.cli));
            assertEquals(true, getField(fixture.cli, "agentConfigured"));
        }
    }

    @Test
    void noProviderDoesNotExposeTheCatalog() throws Exception {
        try (Fixture fixture = new Fixture()) {
            invokeCommand(fixture.cli, "/models");
            assertTrue(fixture.rendered().contains("No provider is selected"));
            assertFalse(fixture.rendered().contains("Select a model"));
            assertTrue(selectableModels(fixture.cli).isEmpty());
        }
    }

    @Test
    void otherProvidersNeverRefreshSavedCopilotCredentials() throws Exception {
        try (Fixture fixture = new Fixture()) {
            AtomicInteger requests = new AtomicInteger();
            HttpServer server = modelServer(fixture, requests, false);
            try {
                fixture.configure("openai");
                fixture.send("\u001b[27u");
                invokeCommand(fixture.cli, "/models");
                assertEquals(0, requests.get());
                assertFalse(fixture.rendered().contains("Refreshing GitHub Copilot models"));
            } finally { server.stop(0); }
        }
    }

    @Test
    void copilotSelectorUsesRefreshedAccountEntitlements() throws Exception {
        assumeFalse(CodingAgentOperations.isAnthropicProxyConfigured(), "Proxy intentionally bypasses Copilot HTTP traffic");
        try (Fixture fixture = new Fixture()) {
            AtomicInteger requests = new AtomicInteger();
            HttpServer server = modelServer(fixture, requests, false);
            try {
                fixture.configure("github-copilot");
                List<Model> models = selectableModels(fixture.cli);
                assertEquals(List.of(fixture.copilot().models.get(1)), models);
                assertTrue(requests.get() > 0);
                assertTrue(models.stream().allMatch(model -> model.provider.equals("github-copilot")));
            } finally { server.stop(0); }
        }
    }

    @Test
    void failedCopilotRefreshKeepsOnlyLastKnownEnabledModels() throws Exception {
        assumeFalse(CodingAgentOperations.isAnthropicProxyConfigured(), "Proxy intentionally bypasses Copilot HTTP traffic");
        try (Fixture fixture = new Fixture()) {
            AtomicInteger requests = new AtomicInteger();
            HttpServer server = modelServer(fixture, requests, true);
            try {
                fixture.configure("github-copilot");
                assertEquals(List.of(fixture.copilot().models.getFirst()), selectableModels(fixture.cli));
                assertTrue(requests.get() > 0);
                assertTrue(fixture.rendered().contains("Could not refresh GitHub Copilot model access"));
            } finally { server.stop(0); }
        }
    }

    @Test
    void proxySelectorUsesOnlyCachedCopilotAccessWithoutHttp() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(CodingAgentOperations.isAnthropicProxyConfigured(),
                "Run this test with ANTHROPIC_BASE_URL set");
        try (Fixture fixture = new Fixture()) {
            AtomicInteger requests = new AtomicInteger();
            HttpServer server = modelServer(fixture, requests, true);
            try {
                fixture.configure("github-copilot");
                fixture.saveCopilot(List.of(fixture.copilot().models.getFirst().id), 0);
                fixture.send("\u001b[27u");
                invokeCommand(fixture.cli, "/models");
                assertEquals(List.of(fixture.copilot().models.getFirst()), selectableModels(fixture.cli));
                assertEquals(0, requests.get());
                assertFalse(fixture.rendered().contains("Refreshing GitHub Copilot models"));
                fixture.saveCopilot(null, 0);
                assertTrue(selectableModels(fixture.cli).isEmpty());
                invokeCommand(fixture.cli, "/models");
                assertTrue(fixture.rendered().contains("No selectable models for github-copilot"));
                assertEquals(0, requests.get());
            } finally { server.stop(0); }
        }
    }

    @Test
    void cachedCopilotEntitlementsNeverRefreshTokensOrInventAccess() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ProviderState copilot = fixture.copilot();
            assertTrue(fixture.runtime.gitHubCopilotCachedAvailableModels(copilot).isEmpty());
            fixture.saveCopilot(List.of(copilot.models.getFirst().id, "unknown-model"), 0);
            assertEquals(List.of(copilot.models.getFirst()), fixture.runtime.gitHubCopilotCachedAvailableModels(copilot));
            fixture.saveCopilot(List.of(), 0);
            assertTrue(fixture.runtime.gitHubCopilotCachedAvailableModels(copilot).isEmpty());
            fixture.saveCopilot(null, 0);
            assertTrue(fixture.runtime.gitHubCopilotCachedAvailableModels(copilot).isEmpty());
            fixture.configure("github-copilot");
            fixture.runtime.deleteCredential(fixture.runtime.defaultCredentialStore(), "github-copilot");
            invokeCommand(fixture.cli, "/models");
            assertTrue(fixture.rendered().contains("No selectable models for github-copilot"));
            assertFalse(fixture.rendered().contains("Select a model"));
        }
    }

    private HttpServer modelServer(Fixture fixture, AtomicInteger requests, boolean fail) throws Exception {
        ProviderState copilot = fixture.copilot();
        copilot.models = copilot.models.subList(0, 2);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            String response = fail ? "{\"error\":\"test outage\"}" : exchange.getRequestMethod().equals("POST") ? "{}"
                    : "{\"data\":[{\"id\":\"" + copilot.models.get(1).id
                    + "\",\"model_picker_enabled\":true,\"policy\":{\"state\":\"enabled\"}}]}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(fail ? 503 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        copilot.defaultCopilotBaseUrl = base;
        copilot.copilotTokenUrl = base.resolve("/token");
        fixture.saveCopilot(List.of(copilot.models.getFirst().id), Long.MAX_VALUE);
        server.start();
        return server;
    }

    private final class Fixture implements AutoCloseable {
        final CodingAgentOperations runtime = new CodingAgentOperations();
        final PipedInputStream input = new PipedInputStream(1 << 16);
        final PipedOutputStream writer = new PipedOutputStream(input);
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final CodingAgentCli cli = new CodingAgentCli(runtime);

        Fixture() throws Exception {
            runtime.applicationPaths(new CodingAgentPaths(workspace.resolve("home")));
            runtime.initializeCoreProviders();
            cli.newInteractiveTerminal(Terminal.streams("xterm-256color", input, output, 100, 24), () -> null, false);
            setField(cli, "settings", runtime.loadSettings());
            setField(cli, "activity", CodingAgentCli.readyActivity(System.nanoTime()));
            setField(cli, "noSession", true);
        }

        Model configure(String provider) throws Exception {
            Model model = runtime.coreProviderModels(provider).getFirst();
            var configure = CodingAgentCli.class.getDeclaredMethod("configureShellAgent", Model.class, Path.class, boolean.class, String.class);
            configure.setAccessible(true);
            configure.invoke(cli, model, workspace, false, null);
            return model;
        }

        ProviderState copilot() { return (ProviderState) runtime.requireCoreProvider("github-copilot"); }

        void saveCopilot(List<String> enabled, long expires) throws Exception {
            runtime.modifyCredential(runtime.defaultCredentialStore(), "github-copilot", ignored ->
                    new Credential.OAuthCredential("test-access", "test-refresh", expires, enabled, Map.of()));
        }

        void loginOpenaiWithoutModel() throws Exception {
            var login = java.util.concurrent.CompletableFuture.runAsync(() -> {
                try { invokeCommand(cli, "/login"); }
                catch (Exception error) { throw new RuntimeException(error); }
            });
            // Separate submissions: adjacent lines are intentionally treated as a multiline paste.
            waitUntil(() -> rendered().contains("Select provider [1-3]:"));
            send("2\r");
            waitUntil(() -> rendered().contains("OpenAI API key:"));
            send("test-only-api-key\r");
            waitUntil(() -> {
                try { return Boolean.TRUE.equals(getField(cli, "componentOpen")); }
                catch (Exception error) { throw new RuntimeException(error); }
            });
            send("\u001b[27u");
            login.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }

        void send(String keys) throws Exception { writer.write(keys.getBytes(StandardCharsets.UTF_8)); writer.flush(); }
        String rendered() { return CodingAgentCli.stripAnsi(output.toString(StandardCharsets.UTF_8)); }

        @Override public void close() throws Exception {
            try { cli.closeTerminal(); }
            finally { try { writer.close(); input.close(); } finally { runtime.close(); } }
        }
    }

    private static void waitUntil(java.util.function.BooleanSupplier ready) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!ready.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(ready.getAsBoolean(), "Timed out waiting for login prompt");
    }

    @SuppressWarnings("unchecked")
    private static List<Model> selectableModels(CodingAgentCli cli) throws Exception {
        var method = CodingAgentCli.class.getDeclaredMethod("shellSelectableModels");
        method.setAccessible(true);
        return (List<Model>) method.invoke(cli);
    }

    private static void invokeCommand(CodingAgentCli cli, String command) throws Exception {
        var method = CodingAgentCli.class.getDeclaredMethod("dispatchSlashCommand", String.class);
        method.setAccessible(true);
        method.invoke(cli, command);
    }

    private static void setField(CodingAgentCli cli, String name, Object value) throws Exception {
        var field = CodingAgentCli.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(cli, value);
    }

    private static Object getField(CodingAgentCli cli, String name) throws Exception {
        var field = CodingAgentCli.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(cli);
    }
}
