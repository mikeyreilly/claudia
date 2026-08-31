package com.quaxt.codingagent;

import com.quaxt.codingagent.ai.Provider;
import com.quaxt.codingagent.ai.auth.CredentialStore;
import com.quaxt.codingagent.ai.providers.ProviderState;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataCarrierArchitectureTest {
    @Test
    void separatesApplicationOperationsFromTheFourProviderRoles() throws Exception {
        assertArrayEquals(
                new CodingAgentOperations[] {CodingAgentOperations.INSTANCE},
                CodingAgentOperations.values());
        assertFalse(Provider.class.isAssignableFrom(CodingAgentOperations.class));
        assertArrayEquals(
                new ProviderState[] {
                    ProviderState.CHATGPT_OPERATIONS,
                    ProviderState.GOOGLE_OPERATIONS,
                    ProviderState.GITHUB_COPILOT_OPERATIONS,
                    ProviderState.OPENAI_COMPATIBLE_OPERATIONS
                },
                ProviderState.values());
        assertTrue(Provider.class.isAssignableFrom(ProviderState.class));

        for (String name : Set.of(
                "id",
                "models",
                "credentials",
                "authBaseUrl",
                "clientId",
                "githubBaseUrl",
                "copilotTokenUrl",
                "defaultCopilotBaseUrl",
                "anthropic",
                "responses")) {
            Field field = ProviderState.class.getDeclaredField(name);
            assertTrue(Modifier.isPublic(field.getModifiers()), name);
            assertFalse(Modifier.isFinal(field.getModifiers()), name);
        }
    }

    @Test
    void coreInitializationRegistersDistinctStateAndRetainsEveryCopilotProtocol() throws Exception {
        CodingAgentOperations operations = CodingAgentOperations.INSTANCE;
        operations.initializeCoreProviders();

        Field field = CodingAgentOperations.class.getDeclaredField("coreProviders");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Provider> providers = (Map<String, Provider>) field.get(operations);

        assertSame(ProviderState.CHATGPT_OPERATIONS, providers.get("chatgpt"));
        assertSame(ProviderState.GOOGLE_OPERATIONS, providers.get("google"));
        assertSame(ProviderState.GITHUB_COPILOT_OPERATIONS, providers.get("github-copilot"));
        assertNotSame(providers.get("chatgpt"), providers.get("google"));
        assertNotSame(providers.get("google"), providers.get("github-copilot"));

        assertTrue(ProviderState.CHATGPT_OPERATIONS.models.stream()
                .allMatch(model -> model.provider.equals("chatgpt")));
        assertTrue(ProviderState.GOOGLE_OPERATIONS.models.stream()
                .allMatch(model -> model.provider.equals("google")));
        assertTrue(ProviderState.GITHUB_COPILOT_OPERATIONS.models.stream()
                .allMatch(model -> model.provider.equals("github-copilot")));
        assertEquals(
                Set.of("anthropic-messages", "openai-completions", "openai-responses"),
                ProviderState.GITHUB_COPILOT_OPERATIONS.models.stream()
                        .map(model -> model.api)
                        .collect(Collectors.toSet()));
        assertEquals(
                operations.catalogModelsForProvider("github-copilot").stream()
                        .map(model -> model.id)
                        .toList(),
                ProviderState.GITHUB_COPILOT_OPERATIONS.models.stream().map(model -> model.id).toList());
    }

    @Test
    void authConfigurationDoesNotBleedBetweenProviderStates() {
        CodingAgentOperations operations = CodingAgentOperations.INSTANCE;
        CredentialStore chatStore = new CredentialStore() {};
        CredentialStore copilotStore = new CredentialStore() {};
        URI chatBase = URI.create("https://chat-auth.example");
        URI githubBase = URI.create("https://github-auth.example");
        URI tokenBase = URI.create("https://copilot-token.example");
        URI apiBase = URI.create("https://copilot-api.example");

        operations.chatGptAuth(
                ProviderState.CHATGPT_OPERATIONS, chatStore, chatBase, "chat-client");
        operations.gitHubCopilotAuth(
                ProviderState.GITHUB_COPILOT_OPERATIONS,
                copilotStore,
                githubBase,
                tokenBase,
                apiBase);

        assertSame(chatStore, ProviderState.CHATGPT_OPERATIONS.credentials);
        assertEquals(chatBase, ProviderState.CHATGPT_OPERATIONS.authBaseUrl);
        assertEquals("chat-client", ProviderState.CHATGPT_OPERATIONS.clientId);
        assertSame(copilotStore, ProviderState.GITHUB_COPILOT_OPERATIONS.credentials);
        assertEquals(githubBase, ProviderState.GITHUB_COPILOT_OPERATIONS.githubBaseUrl);
        assertEquals(tokenBase, ProviderState.GITHUB_COPILOT_OPERATIONS.copilotTokenUrl);
        assertEquals(apiBase, ProviderState.GITHUB_COPILOT_OPERATIONS.defaultCopilotBaseUrl);
        assertNotSame(
                ProviderState.CHATGPT_OPERATIONS.credentials,
                ProviderState.GITHUB_COPILOT_OPERATIONS.credentials);
    }
}
