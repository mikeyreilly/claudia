package com.quaxt.codingagent.ai.providers;

import com.quaxt.codingagent.ai.Provider;
import com.quaxt.codingagent.ai.auth.CredentialStore;
import com.quaxt.codingagent.ai.types.Model;

import java.net.URI;
import java.util.List;

/** Passive mutable state for provider implementations hosted by the application operations enum. */
public enum ProviderState implements Provider {
    CHATGPT_OPERATIONS,
    GOOGLE_OPERATIONS,
    GITHUB_COPILOT_OPERATIONS,
    OPENAI_COMPATIBLE_OPERATIONS;

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
