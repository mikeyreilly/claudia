package com.quaxt.codingagent.ai.providers;

import com.quaxt.codingagent.ai.Provider;
import com.quaxt.codingagent.ai.auth.CredentialStore;
import com.quaxt.codingagent.ai.types.Model;

import java.net.URI;
import java.util.List;

/** Mutable provider configuration owned by one runtime, with an immutable dispatch role. */
public final class ProviderState implements Provider {
    public enum Role { CHATGPT, GOOGLE, GITHUB_COPILOT, OPENAI_COMPATIBLE }

    public final Role role;

    public ProviderState(Role role) {
        this.role = java.util.Objects.requireNonNull(role, "role");
    }

    public String id;
    public List<Model> models = List.of();
    public CredentialStore credentials;

    public URI authBaseUrl;
    public String clientId;

    public URI githubBaseUrl;
    public URI copilotTokenUrl;
    public URI defaultCopilotBaseUrl;

    public AnthropicProvider anthropic;
    public OpenAiResponsesProvider responses;
}
