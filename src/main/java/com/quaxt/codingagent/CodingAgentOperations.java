package com.quaxt.codingagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.Authenticator;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.quaxt.codingagent.agent.AgentEvent;
import com.quaxt.codingagent.agent.AgentMode;
import com.quaxt.codingagent.agent.QuestionBroker;
import com.quaxt.codingagent.agent.AgentTool;
import com.quaxt.codingagent.agent.CompactionResult;
import com.quaxt.codingagent.agent.FunctionTool;
import com.quaxt.codingagent.agent.ToolInvocation;
import com.quaxt.codingagent.ai.Models;
import com.quaxt.codingagent.ai.Provider;
import com.quaxt.codingagent.ai.Retry;
import com.quaxt.codingagent.ai.StreamOptions;
import com.quaxt.codingagent.ai.auth.Credential;
import com.quaxt.codingagent.ai.auth.CredentialStore;
import com.quaxt.codingagent.ai.auth.EnvApiKeys;
import com.quaxt.codingagent.ai.http.HttpException;
import com.quaxt.codingagent.ai.http.HttpTransport;
import com.quaxt.codingagent.ai.http.IdleTimeoutInputStream;
import com.quaxt.codingagent.ai.http.SseReader;
import com.quaxt.codingagent.ai.json.Json;
import com.quaxt.codingagent.ai.providers.AnthropicProvider;
import com.quaxt.codingagent.ai.providers.FauxProvider;
import com.quaxt.codingagent.ai.providers.OpenAiResponsesProvider;
import com.quaxt.codingagent.ai.providers.ProviderState;
import com.quaxt.codingagent.ai.stream.AssistantMessageEventStream;
import com.quaxt.codingagent.ai.stream.EventStream;
import com.quaxt.codingagent.ai.types.AssistantContent;
import com.quaxt.codingagent.ai.types.AssistantMessage;
import com.quaxt.codingagent.ai.types.AssistantMessageEvent;
import com.quaxt.codingagent.ai.types.Compat;
import com.quaxt.codingagent.ai.types.Context;
import com.quaxt.codingagent.ai.types.ImageContent;
import com.quaxt.codingagent.ai.types.Message;
import com.quaxt.codingagent.ai.types.Model;
import com.quaxt.codingagent.ai.types.ModelCost;
import com.quaxt.codingagent.ai.types.StopReason;
import com.quaxt.codingagent.ai.types.TextContent;
import com.quaxt.codingagent.ai.types.ThinkingContent;
import com.quaxt.codingagent.ai.types.ThinkingLevel;
import com.quaxt.codingagent.ai.types.Tool;
import com.quaxt.codingagent.ai.types.ToolCall;
import com.quaxt.codingagent.ai.types.ToolResultMessage;
import com.quaxt.codingagent.ai.types.Usage;
import com.quaxt.codingagent.ai.types.UserContent;
import com.quaxt.codingagent.ai.types.UserMessage;
import com.quaxt.codingagent.ai.util.AbortSignal;
import com.quaxt.codingagent.cli.session.SessionSnapshot;
import com.quaxt.codingagent.cli.tools.LocalTools;
import com.quaxt.codingagent.cli.tools.TaskState;
import com.quaxt.codingagent.agent.ToolDefinition;
import com.quaxt.codingagent.shell.ShellSessionManager;
import com.quaxt.codingagent.mcp.McpAgentTool;
import com.quaxt.codingagent.mcp.McpClient;
import com.quaxt.codingagent.mcp.McpResultFilter;
import com.quaxt.codingagent.mcp.McpConfigLoader;
import com.quaxt.codingagent.mcp.McpConfiguration;
import com.quaxt.codingagent.mcp.McpHttpException;
import com.quaxt.codingagent.mcp.McpOAuthCallback;
import com.quaxt.codingagent.mcp.McpOAuthRequiredException;
import com.quaxt.codingagent.mcp.McpServerConfig;
import com.quaxt.codingagent.mcp.McpTransport;
import com.quaxt.codingagent.mcp.SseHttpMcpTransport;
import com.quaxt.codingagent.mcp.StdioMcpTransport;
import com.quaxt.codingagent.mcp.StreamableHttpMcpTransport;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

/** Agent execution, providers, tools, instructions, and persistent application state. */
public final class CodingAgentOperations implements CredentialStore, AutoCloseable {
    private final Map<ProviderState.Role, ProviderState> providerStates = new EnumMap<>(ProviderState.Role.class);
    private volatile boolean runtimeClosed;
    private boolean childRuntime;
    private SubagentManager subagents;
    private CodingAgentOperations parentRuntime;
    private volatile AgentMode agentMode = AgentMode.BUILD;
    /* Per-process tools.deps aliases forced into code-lens MCP calls. */
    private String codeLensAliases;
    private final QuestionBroker questionBroker = new QuestionBroker();
    private final TaskState taskState = new TaskState(snapshot -> {
        if (this.recordingSession) appendSessionEntry(this.sessionId, "task_state", snapshot);
    });
    private String questionAgentId = SubagentManager.MAIN;

    public AgentMode agentMode() { return parentRuntime == null ? agentMode : parentRuntime.agentMode(); }
    public QuestionBroker questions() { return parentRuntime == null ? questionBroker : parentRuntime.questions(); }
    public String codeLensAliases() { return parentRuntime == null ? codeLensAliases : parentRuntime.codeLensAliases(); }
    public void setCodeLensAliases(String aliases) {
        if (parentRuntime != null) throw new IllegalStateException("Change code-lens aliases through Main");
        codeLensAliases = aliases;
    }
    public ObjectNode taskStateSnapshot() { return taskState.snapshot(); }
    public void restoreTaskState(JsonNode snapshot) throws IOException { taskState.restore(snapshot); }

    private Object groupLock() { return parentRuntime != null ? parentRuntime.groupLock() : subagents == null ? this : subagents; }

    public void requireIdleGroup() {
        requireIdleAgent();
        if (subagents != null && !subagents.idle()) throw new IllegalStateException("Wait for all agents to be idle");
        if (!questions().pending().isEmpty()) throw new IllegalStateException("Wait for pending questions to be resolved");
    }

    /** Changes the entire group's workflow without starting a turn or resetting its conversation. */
    public void setAgentMode(AgentMode mode) {
        Objects.requireNonNull(mode, "mode");
        if (parentRuntime != null) throw new IllegalStateException("Change the mode through Main");
        synchronized (groupLock()) {
            requireOpen();
            if (agentMode == mode) return;
            requireIdleGroup();
            agentMode = mode;
            if (recordingSession) try {
                appendSessionEntry(sessionId, "agent_mode_change", jsonObject().put("agentMode", mode.wire));
            } catch (IOException error) { persistenceFailure.accept(error); }
        }
    }
    private Path agentWorkspace;
    private String baseInstructions;
    private final List<Message> transcript = new CopyOnWriteArrayList<>();
    private static final Object CREDENTIAL_FILES = new Object();

    public SubagentManager subagents() {
        if (childRuntime) throw new IllegalStateException("Children cannot delegate");
        if (subagents == null) throw new IllegalStateException("No agent configured");
        return subagents;
    }

    public List<Message> transcript() { return snapshotMessages(transcript); }

    static List<Message> snapshotMessages(List<Message> source) {
        return source.stream().map(message -> switch (message) {
            case UserMessage user -> (Message) new UserMessage(copyUserContent(user.content), user.timestamp);
            case ToolResultMessage tool -> new ToolResultMessage(tool.toolCallId, tool.toolName,
                    copyUserContent(tool.content), tool.details instanceof JsonNode node ? node.deepCopy() : tool.details,
                    tool.isError, tool.timestamp);
            case AssistantMessage assistant -> {
                AssistantMessage copy = new AssistantMessage(assistant.api, assistant.provider, assistant.model);
                copy.timestamp = assistant.timestamp; copy.responseModel = assistant.responseModel;
                copy.responseId = assistant.responseId; copy.stopReason = assistant.stopReason;
                copy.errorMessage = assistant.errorMessage; copy.rawStopReason = assistant.rawStopReason;
                for (AssistantContent block : assistant.content) copy.content.add(switch (block) {
                    case TextContent text -> new TextContent(text.text, text.textSignature);
                    case ThinkingContent thought -> new ThinkingContent(thought.thinking, thought.thinkingSignature, thought.redacted);
                    case ToolCall call -> new ToolCall(call.id, call.name, call.arguments.deepCopy(), call.thoughtSignature);
                });
                Usage u = assistant.usage, v = copy.usage;
                v.input = u.input; v.output = u.output; v.cacheRead = u.cacheRead; v.cacheWrite = u.cacheWrite;
                v.cacheWrite1h = u.cacheWrite1h; v.reasoning = u.reasoning; v.totalTokens = u.totalTokens;
                v.cost.input = u.cost.input; v.cost.output = u.cost.output; v.cost.cacheRead = u.cost.cacheRead;
                v.cost.cacheWrite = u.cost.cacheWrite; v.cost.total = u.cost.total;
                yield copy;
            }
        }).toList();
    }

    private static List<UserContent> copyUserContent(List<UserContent> content) {
        return content.stream().map(block -> switch (block) {
            case TextContent text -> (UserContent) new TextContent(text.text, text.textSignature);
            case ImageContent image -> new ImageContent(image.data, image.mimeType);
        }).toList();
    }

    static AgentEvent snapshotEvent(AgentEvent event) {
        return switch (event) {
            case AgentEvent.MessageStart e -> new AgentEvent.MessageStart(snapshotMessages(List.of(e.message)).getFirst());
            case AgentEvent.MessageEnd e -> new AgentEvent.MessageEnd(snapshotMessages(List.of(e.message)).getFirst());
            case AgentEvent.MessageUpdate e -> new AgentEvent.MessageUpdate(snapshotUpdate(e.providerEvent));
            case AgentEvent.AgentEnd e -> new AgentEvent.AgentEnd(snapshotMessages(e.newMessages));
            case AgentEvent.TurnEnd e -> new AgentEvent.TurnEnd(snapshotMessages(List.of(e.assistant)).getFirst(),
                    snapshotMessages(new ArrayList<Message>(e.toolResults)).stream().map(ToolResultMessage.class::cast).toList());
            case AgentEvent.ToolExecutionStart e -> new AgentEvent.ToolExecutionStart(e.toolCallId, e.toolName, e.arguments.deepCopy());
            case AgentEvent.ToolExecutionUpdate e -> new AgentEvent.ToolExecutionUpdate(e.toolCallId, e.toolName, snapshotToolResult(e.partialResult));
            case AgentEvent.ToolExecutionEnd e -> new AgentEvent.ToolExecutionEnd(e.toolCallId, e.toolName, snapshotToolResult(e.result));
            default -> event; // Remaining event payloads are scalar lifecycle metadata.
        };
    }

    private static AgentTool.ToolResult snapshotToolResult(AgentTool.ToolResult result) {
        return new AgentTool.ToolResult(copyUserContent(result.content),
                result.details instanceof JsonNode node ? node.deepCopy() : result.details, result.isError);
    }

    private static AssistantMessage snapshotAssistant(AssistantMessage message) {
        return (AssistantMessage) snapshotMessages(List.of(message)).getFirst();
    }

    private static AssistantMessageEvent snapshotUpdate(AssistantMessageEvent event) {
        return switch (event) {
            case AssistantMessageEvent.Start e -> new AssistantMessageEvent.Start(snapshotAssistant(e.partial));
            case AssistantMessageEvent.TextStart e -> new AssistantMessageEvent.TextStart(e.contentIndex, snapshotAssistant(e.partial));
            case AssistantMessageEvent.TextDelta e -> new AssistantMessageEvent.TextDelta(e.contentIndex, e.delta, snapshotAssistant(e.partial));
            case AssistantMessageEvent.TextEnd e -> new AssistantMessageEvent.TextEnd(e.contentIndex, e.content, snapshotAssistant(e.partial));
            case AssistantMessageEvent.ThinkingStart e -> new AssistantMessageEvent.ThinkingStart(e.contentIndex, snapshotAssistant(e.partial));
            case AssistantMessageEvent.ThinkingDelta e -> new AssistantMessageEvent.ThinkingDelta(e.contentIndex, e.delta, snapshotAssistant(e.partial));
            case AssistantMessageEvent.ThinkingEnd e -> new AssistantMessageEvent.ThinkingEnd(e.contentIndex, e.content, snapshotAssistant(e.partial));
            case AssistantMessageEvent.ToolCallStart e -> new AssistantMessageEvent.ToolCallStart(e.contentIndex, snapshotAssistant(e.partial));
            case AssistantMessageEvent.ToolCallDelta e -> new AssistantMessageEvent.ToolCallDelta(e.contentIndex, e.delta, snapshotAssistant(e.partial));
            case AssistantMessageEvent.ToolCallEnd e -> new AssistantMessageEvent.ToolCallEnd(e.contentIndex,
                    new ToolCall(e.toolCall.id, e.toolCall.name, e.toolCall.arguments.deepCopy(), e.toolCall.thoughtSignature), snapshotAssistant(e.partial));
            case AssistantMessageEvent.Done e -> new AssistantMessageEvent.Done(e.reason, snapshotAssistant(e.message));
            case AssistantMessageEvent.Error e -> new AssistantMessageEvent.Error(e.reason, snapshotAssistant(e.error));
        };
    }

    private void acceptMessage(Message message) {
        transcript.add(message);
        if (recordingSession) try { appendSessionMessages(List.of(message)); }
        catch (IOException error) { persistenceFailure.accept(error); }
    }

    void recordLifecycle(String status) throws IOException {
        if (recordingSession && childRuntime) appendSessionEntry(sessionId, "agent_lifecycle", jsonObject().put("status", status));
    }

    SessionSnapshot createChildSession(String task, String name, Model model, ThinkingLevel level) throws IOException {
        if (!recordingSession) return null;
        try (CodingAgentOperations recorder = new CodingAgentOperations()) {
            recorder.agentMode = agentMode();
            recorder.sessionStore(directory, legacyDirectories);
            recorder.createSessionRecorder(agentWorkspace, model.provider, model.id, name,
                    sessionId, task, level);
            return recorder.sessionSnapshot(recorder.sessionId);
        }
    }

    Model resolveChildModel(String requested) {
        if (requested == null || requested.isBlank()) return copyModel(selectedModel);
        String selection = requested.strip();
        String providerId = selectedModel.provider;
        String modelId = selection;
        if (selection.contains("/")) {
            String[] parts = selection.split("/", 2);
            providerId = parts[0];
            modelId = parts[1];
        }
        if (providerId.isBlank() || modelId.isBlank()) {
            throw new IllegalArgumentException("model must be a model ID or provider/model");
        }
        Provider provider = providerId.equals(selectedModel.provider)
                ? agentProvider
                : requireCoreProvider(providerId);
        for (Model candidate : providerModels(provider)) {
            if (candidate.id.equals(modelId)) return copyModel(candidate);
        }
        throw new IllegalArgumentException("Unknown model: " + providerId + "/" + modelId);
    }

    CodingAgentOperations createChildRuntime(
            SessionSnapshot saved, String agentId, Model model, ThinkingLevel level) throws IOException {
        CodingAgentOperations child = new CodingAgentOperations();
        try {
            child.childRuntime = true;
            child.parentRuntime = this;
            child.questionAgentId = agentId;
            child.codeLensAliases = codeLensAliases();
            child.applicationPaths(applicationPaths);
            if (authPath != null) child.fileCredentialStore(authPath, fallbackAuthPath);
            boolean sameProvider = selectedModel.provider.equals(model.provider);
            Provider sourceProvider = sameProvider ? agentProvider : requireCoreProvider(model.provider);
            Provider provider = copyProviderForChild(sourceProvider, child);
            Map<String, McpServerConfig> configs = new LinkedHashMap<>();
            servers.forEach((name, server) -> {
                synchronized (server.lock) {
                    configs.put(name, switch (server.config) {
                        case McpServerConfig.Local local -> new McpServerConfig.Local(List.copyOf(local.command), local.cwd,
                                Map.copyOf(local.environment), server.enabled, local.timeoutMillis,
                                List.copyOf(local.resultFilters), List.copyOf(server.disabledTools));
                        case McpServerConfig.Remote remote -> new McpServerConfig.Remote(remote.url, Map.copyOf(remote.headers),
                                remote.oauth == null ? null : remote.oauth.deepCopy(), server.enabled, remote.timeoutMillis,
                                List.copyOf(remote.resultFilters), List.copyOf(server.disabledTools));
                    });
                }
            });
            if (!configs.isEmpty()) child.mcpCreateManager(new McpConfiguration(configs, List.of()), agentWorkspace);
            child.configureAgent(provider, copyModel(model), agentWorkspace, baseInstructions,
                    sameProvider ? apiKey : null, level);
            child.retryPolicy = retryPolicy;
            child.autoCompactionEnabled = autoCompactionEnabled;
            child.compactionReserveTokens = compactionReserveTokens;
            if (saved != null) {
                child.sessionStore(directory, legacyDirectories);
                child.resumeSessionRecorder(saved.id);
                child.restoreMessages(saved.messages);
                child.transcript.clear();
                child.transcript.addAll(saved.transcriptMessages);
                child.setSessionRecording(true, persistenceFailure);
            }
            return child;
        } catch (IOException | RuntimeException error) { child.close(); throw error; }
    }

    private Provider copyProviderForChild(Provider provider, CodingAgentOperations child) {
        return switch (provider) {
            // Faux scripts are a synchronized test fixture, not an execution resource.
            case FauxProvider faux -> faux;
            case AnthropicProvider p -> new AnthropicProvider(p.id, p.name, p.models, p.apiKeyEnvVars, p.bearerAuthentication);
            case OpenAiResponsesProvider p -> new OpenAiResponsesProvider(p.id, p.name, p.models, p.apiKeyEnvVars,
                    p.credentials == this ? child : p.credentials, p.requestProfile);
            case ProviderState p -> {
                ProviderState copy = child.providerState(p.role);
                copy.id = p.id; copy.models = p.models;
                copy.credentials = p.credentials == this ? child : p.credentials;
                copy.authBaseUrl = p.authBaseUrl; copy.clientId = p.clientId;
                copy.githubBaseUrl = p.githubBaseUrl; copy.copilotTokenUrl = p.copilotTokenUrl;
                copy.defaultCopilotBaseUrl = p.defaultCopilotBaseUrl;
                if (p.anthropic != null) copy.anthropic = (AnthropicProvider) copyProviderForChild(p.anthropic, child);
                if (p.responses != null) copy.responses = (OpenAiResponsesProvider) copyProviderForChild(p.responses, child);
                yield copy;
            }
            default -> throw unknownProvider(provider);
        };
    }


    public CodingAgentOperations() {
        for (ProviderState.Role role : ProviderState.Role.values()) providerStates.put(role, new ProviderState(role));
    }

    ProviderState providerState(ProviderState.Role role) {
        return providerStates.get(role);
    }

    private void requireOpen() {
        if (runtimeClosed) throw new IllegalStateException("Agent runtime is closed");
    }

    /** Cancels active work and releases this runtime's shell processes, MCP connections, and listeners. */
    @Override
    public void close() {
        if (runtimeClosed) return;
        runtimeClosed = true;
        if (subagents != null) subagents.close();
        try {
            abort();
        } finally {
            if (parentRuntime == null) questionBroker.close();
            try {
                mcpCloseManager();
            } finally {
                shellSessions.close();
                debugger.close();
                listeners.clear();
            }
        }
    }

    // Model catalog
    private static final String MODEL_CATALOG_RESOURCE_ROOT = "/quaxt/codingagent/ai/models/";
    private static final List<String> MODEL_CATALOG_RESOURCE_NAMES =
            List.of("anthropic.json", "openai.json", "google.json", "github-copilot.json");

    // File-backed stores
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    // ChatGPT authentication and provider
    static final String CHATGPT_PROVIDER_ID = "chatgpt";
    public static final URI CHATGPT_CODEX_API_BASE_URL = URI.create("https://chatgpt.com/backend-api/codex");
    private static final String CHATGPT_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    private static final String CHATGPT_ACCOUNT_ID = "accountId";
    private static final long CHATGPT_REFRESH_SKEW_MS = 5 * 60 * 1000L;
    private static final long CHATGPT_DEVICE_CODE_LIFETIME_MS = 15 * 60 * 1000L;
    private static final String CHATGPT_PROVIDER_NAME = "ChatGPT Plus/Pro";
    private static final Set<String> CHATGPT_CODEX_MODEL_IDS = Set.of(
            "gpt-5-chat-latest",
            "gpt-5.2-chat-latest",
            "gpt-5.3-chat-latest",
            "gpt-5.3-codex",
            "gpt-5.3-codex-spark",
            "gpt-5.4",
            "gpt-5.5",
            "gpt-5.6-luna",
            "gpt-5.6-sol",
            "gpt-5.6-terra",
            "gpt-6-astra",
            "gpt-6-luna",
            "gpt-6-sol",
            "gpt-6.1-sol");

    // GitHub Copilot authentication and provider
    public static final String GITHUB_COPILOT_PROVIDER_ID = "github-copilot";
    private static final String GITHUB_COPILOT_CLIENT_ID = "Iv1.b507a08c87ecfe98";
    private static final String GITHUB_COPILOT_USER_AGENT = "GitHubCopilotChat/0.35.0";
    private static final String GITHUB_COPILOT_DEFAULT_BASE_URL = "https://api.individual.githubcopilot.com";
    private static final Pattern GITHUB_COPILOT_PROXY_ENDPOINT = Pattern.compile("(?:^|;)proxy-ep=([^;]+)");
    private static final long GITHUB_COPILOT_REFRESH_SKEW_MS = 5 * 60 * 1000L;
    private static final String GITHUB_COPILOT_PROVIDER_NAME = "GitHub Copilot";
    private static final String GOOGLE_API = "google-generative-ai";
    private static final String OPENAI_COMPATIBLE_API = "openai-completions";

    /** Pending ChatGPT device authorization presented to the user. */
    public static final class ChatGptDeviceCode {
        private String deviceAuthId;
        public String userCode;
        public final URI verificationUri;
        private int intervalSeconds;
        private long expiresAtMs;

        private ChatGptDeviceCode(
                String deviceAuthId, String userCode, URI verificationUri, int intervalSeconds, long expiresAtMs) {
            this.deviceAuthId = deviceAuthId;
            this.userCode = userCode;
            this.verificationUri = verificationUri;
            this.intervalSeconds = intervalSeconds;
            this.expiresAtMs = expiresAtMs;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ChatGptDeviceCode that
                    && Objects.equals(deviceAuthId, that.deviceAuthId)
                    && Objects.equals(userCode, that.userCode)
                    && Objects.equals(verificationUri, that.verificationUri)
                    && intervalSeconds == that.intervalSeconds
                    && expiresAtMs == that.expiresAtMs;
        }

        @Override
        public int hashCode() {
            return Objects.hash(deviceAuthId, userCode, verificationUri, intervalSeconds, expiresAtMs);
        }

        @Override
        public String toString() {
            return "DeviceCode[deviceAuthId=" + deviceAuthId + ", userCode=" + userCode
                    + ", verificationUri=" + verificationUri + ", intervalSeconds=" + intervalSeconds
                    + ", expiresAtMs=" + expiresAtMs + "]";
        }
    }

    /** A current ChatGPT bearer token plus the subscription account id. */
    public static final class ChatGptToken {
        public String accessToken;
        public String accountId;

        public ChatGptToken(String accessToken, String accountId) {
            this.accessToken = accessToken;
            this.accountId = accountId;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ChatGptToken that
                    && Objects.equals(accessToken, that.accessToken)
                    && Objects.equals(accountId, that.accountId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(accessToken, accountId);
        }

        @Override
        public String toString() {
            return "ChatGptToken[accessToken=" + accessToken + ", accountId=" + accountId + "]";
        }
    }

    /** Pending GitHub device authorization presented to the user. */
    public static final class GitHubCopilotDeviceCode {
        private String deviceCode;
        public String userCode;
        public final URI verificationUri;
        private int intervalSeconds;
        private long expiresAtMs;

        private GitHubCopilotDeviceCode(
                String deviceCode, String userCode, URI verificationUri, int intervalSeconds, long expiresAtMs) {
            this.deviceCode = deviceCode;
            this.userCode = userCode;
            this.verificationUri = verificationUri;
            this.intervalSeconds = intervalSeconds;
            this.expiresAtMs = expiresAtMs;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof GitHubCopilotDeviceCode that
                    && Objects.equals(deviceCode, that.deviceCode)
                    && Objects.equals(userCode, that.userCode)
                    && Objects.equals(verificationUri, that.verificationUri)
                    && intervalSeconds == that.intervalSeconds
                    && expiresAtMs == that.expiresAtMs;
        }

        @Override
        public int hashCode() {
            return Objects.hash(deviceCode, userCode, verificationUri, intervalSeconds, expiresAtMs);
        }

        @Override
        public String toString() {
            return "DeviceCode[deviceCode=" + deviceCode + ", userCode=" + userCode
                    + ", verificationUri=" + verificationUri + ", intervalSeconds=" + intervalSeconds
                    + ", expiresAtMs=" + expiresAtMs + "]";
        }
    }

    /** A current derived Copilot bearer token plus its API endpoint. */
    public static final class CopilotToken {
        public String accessToken;
        private URI baseUrl;
        public List<String> availableModelIds;

        private CopilotToken(String accessToken, URI baseUrl, List<String> availableModelIds) {
            this.accessToken = accessToken;
            this.baseUrl = baseUrl;
            this.availableModelIds = availableModelIds;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof CopilotToken that
                    && Objects.equals(accessToken, that.accessToken)
                    && Objects.equals(baseUrl, that.baseUrl)
                    && Objects.equals(availableModelIds, that.availableModelIds);
        }

        @Override
        public int hashCode() {
            return Objects.hash(accessToken, baseUrl, availableModelIds);
        }

        @Override
        public String toString() {
            return "CopilotToken[accessToken=" + accessToken + ", baseUrl=" + baseUrl
                    + ", availableModelIds=" + availableModelIds + "]";
        }
    }

    /** Result of enabling model policies and refreshing the enabled-model list. */
    public static final class CopilotModelAccess {
        public final int policiesEnabled;
        public final List<Model> models;

        private CopilotModelAccess(int policiesEnabled, List<Model> models) {
            this.policiesEnabled = policiesEnabled;
            this.models = models;
        }
    }

    /** Streaming accumulator for one Chat Completions tool-call index. */
    private static final class OpenAiToolCallAccumulator {
        private int wireIndex;
        private List<JsonNode> rawDeltas = new ArrayList<>();
        private int contentIndex = -1;
        private String id = "";
        private String name = "";
        private StringBuilder arguments = new StringBuilder();
        private boolean hasMeaningfulData;

        private OpenAiToolCallAccumulator(int wireIndex) {
            this.wireIndex = wireIndex;
        }
    }

    // Folded model catalog and core-provider registry state
    private Map<String, Model> byProviderAndId;
    private Map<String, List<Model>> byProvider;
    private Map<String, Provider> coreProviders;

    // Folded application paths and file credential store state
    private CodingAgentPaths applicationPaths = CodingAgentPaths.forCurrentUser();
    private Path authPath;
    private Path authLockPath;
    private Path settingsLockPath;
    private Path mcpAuthLockPath;
    private Path fallbackAuthPath;

    // Folded Agent and AgentState state
    private String systemPrompt = "";
    private String apiKey;
    private Provider agentProvider;
    private List<Consumer<AgentEvent>> listeners = new CopyOnWriteArrayList<>();
    private volatile AbortSignal activeSignal;
    private Retry.Policy retryPolicy = Retry.Policy.DEFAULT;
    private Model selectedModel;
    private ThinkingLevel thinkingLevel = ThinkingLevel.OFF;
    private List<AgentTool> tools = new ArrayList<>();
    private List<Message> messages = new CopyOnWriteArrayList<>();
    private volatile boolean isStreaming;
    private AssistantMessage streamingMessage;
    private Set<String> pendingToolCalls = new LinkedHashSet<>();
    private volatile boolean isCompacting;
    private boolean autoCompactionEnabled = true;
    private int compactionReserveTokens = 16_384;
    private boolean recordingSession;
    private Consumer<IOException> persistenceFailure = ignored -> {};
    private final ShellSessionManager shellSessions = new ShellSessionManager();
    private final com.quaxt.codingagent.debug.DebugManager debugger = new com.quaxt.codingagent.debug.DebugManager();
    /** Current conversation metadata and a read-only copy of the message list. */
    public record AgentSnapshot(
            Model model,
            ThinkingLevel thinkingLevel,
            boolean streaming,
            boolean compacting,
            boolean autoCompactionEnabled,
            List<Message> messages,
            String sessionId,
            AgentMode agentMode) {}

    // Repository instructions, sessions, settings, and git-ignore filtering

    /** One complete append-only JSONL session record. */
    public static final class SessionEntry {
        private long timestamp;
        public String type;
        public JsonNode payload;

        private SessionEntry(long timestamp, String type, JsonNode payload) {
            this.timestamp = timestamp;
            this.type = type;
            this.payload = payload;
        }
    }

    /** The settings currently understood by the Java CLI. */
    public static final class Settings {
        public final String defaultProvider;
        public final String defaultModel;
        public final ThinkingLevel defaultThinkingLevel;
        public final boolean hideThinkingBlock;

        public Settings(
                String defaultProvider,
                String defaultModel,
                ThinkingLevel defaultThinkingLevel,
                boolean hideThinkingBlock) {
            this.defaultProvider = defaultProvider;
            this.defaultModel = defaultModel;
            this.defaultThinkingLevel = defaultThinkingLevel;
            this.hideThinkingBlock = hideThinkingBlock;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Settings that
                    && Objects.equals(defaultProvider, that.defaultProvider)
                    && Objects.equals(defaultModel, that.defaultModel)
                    && defaultThinkingLevel == that.defaultThinkingLevel
                    && hideThinkingBlock == that.hideThinkingBlock;
        }

        @Override
        public int hashCode() {
            return Objects.hash(defaultProvider, defaultModel, defaultThinkingLevel, hideThinkingBlock);
        }

        @Override
        public String toString() {
            return "Settings[defaultProvider=" + defaultProvider
                    + ", defaultModel=" + defaultModel
                    + ", defaultThinkingLevel=" + defaultThinkingLevel
                    + ", hideThinkingBlock=" + hideThinkingBlock + "]";
        }
    }

    private AgentInstructionResolver instructionResolver;
    private Path currentInstructionDirectory;
    private List<AgentInstructionResolver.InstructionSource> instructionSources = List.of();
    private String sessionId;
    private Path directory;
    private List<Path> legacyDirectories;
    private Path settingsPath;
    public String executable = "git";

    // MCP manager and OAuth
    private static final int MCP_OAUTH_DEFAULT_CALLBACK_PORT = 19_876;
    private static final String MCP_OAUTH_DEFAULT_CALLBACK_PATH = "/mcp/oauth/callback";
    private static final Duration MCP_OAUTH_DEFAULT_CALLBACK_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration MCP_OAUTH_HTTP_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration MCP_SSE_OPEN_TIMEOUT = Duration.ofSeconds(30);
    private static final long MCP_OAUTH_REFRESH_SKEW_SECONDS = 30;
    private static final String MCP_OAUTH_PROTOCOL_VERSION = "2025-11-25";
    private static final Pattern MCP_OAUTH_AUTH_PARAMETER = Pattern.compile(
            "(?i)(?:^|[,\\s])([a-z][a-z0-9_-]*)\\s*=\\s*(?:\"([^\"]*)\"|([^,\\s]+))");

    public enum McpState {
        CONNECTING,
        AUTHENTICATING,
        AUTH_REQUIRED,
        CONNECTED,
        DISABLED,
        FAILED
    }

    /** Snapshot of one configured MCP server. */
    public static final class McpServerStatus {
        public final String name;
        public final McpState state;
        public final String message;
        public final int toolCount;
        public final int enabledToolCount;
        public final String target;
        public final String authorizationUrl;

        private McpServerStatus(
                String name,
                McpState state,
                String message,
                int toolCount,
                int enabledToolCount,
                String target,
                String authorizationUrl) {
            this.name = name;
            this.state = state;
            this.message = message;
            this.toolCount = toolCount;
            this.enabledToolCount = enabledToolCount;
            this.target = target;
            this.authorizationUrl = authorizationUrl;
        }
    }

    /** One cached MCP tool and whether it is exposed to the model. */
    public static final class McpToolStatus {
        public final String serverName;
        public final String name;
        public final String description;
        public final boolean enabled;

        private McpToolStatus(String serverName, String name, String description, boolean enabled) {
            this.serverName = serverName;
            this.name = name;
            this.description = description;
            this.enabled = enabled;
        }
    }

    /**
     * Mutable state of one configured MCP server. This remains a separate
     * carrier because a manager owns one instance per configured server.
     */
    private static final class McpRuntime {
        private Object lock = new Object();
        private String name;
        private McpServerConfig config;
        private long generation;
        private boolean enabled;
        private McpState state;
        private String message;
        private McpClient client;
        private List<McpClient.ToolDefinition> tools = List.of();
        private Set<String> disabledTools = new HashSet<>();
        private Thread connector;
        private String authorizationUrl;

        private McpRuntime(String name, McpServerConfig config) {
            this.name = name;
            this.config = config;
        }
    }

    /** Per-server OAuth state; the session instance is its own monitor. */
    public static final class McpOAuthSession {
        private String name;
        private McpServerConfig.Remote config;
        private McpOAuthSettings settings;
        private McpOAuthEntry entry;
        private boolean loaded;

        private McpOAuthSession(
                String name,
                McpServerConfig.Remote config,
                McpOAuthSettings settings) {
            this.name = name;
            this.config = config;
            this.settings = settings;
        }
    }

    /** One completed OAuth HTTP exchange. */
    private static final class McpOAuthResponse {
        private int status;
        private URI uri;
        private Map<String, List<String>> headers;
        private String body;

        private McpOAuthResponse(int status, URI uri, Map<String, List<String>> headers, String body) {
            this.status = status;
            this.uri = uri;
            this.headers = headers;
            this.body = body;
        }
    }

    private static final class McpResourceMetadata {
        private URI resource;
        private List<URI> authorizationServers;
        private List<String> scopes;

        private McpResourceMetadata(URI resource, List<URI> authorizationServers, List<String> scopes) {
            this.resource = resource;
            this.authorizationServers = authorizationServers;
            this.scopes = scopes;
        }
    }

    private static final class McpAuthorizationMetadata {
        private URI authorizationEndpoint;
        private URI tokenEndpoint;
        private URI registrationEndpoint;
        private List<String> tokenAuthMethods;
        private List<String> scopes;

        private McpAuthorizationMetadata(
                URI authorizationEndpoint,
                URI tokenEndpoint,
                URI registrationEndpoint,
                List<String> tokenAuthMethods,
                List<String> scopes) {
            this.authorizationEndpoint = authorizationEndpoint;
            this.tokenEndpoint = tokenEndpoint;
            this.registrationEndpoint = registrationEndpoint;
            this.tokenAuthMethods = tokenAuthMethods;
            this.scopes = scopes;
        }
    }

    private static final class McpOAuthDiscovery {
        private McpAuthorizationMetadata metadata;
        private McpResourceMetadata resourceMetadata;
        private URI resource;

        private McpOAuthDiscovery(
                URI authorizationServer,
                McpAuthorizationMetadata metadata,
                McpResourceMetadata resourceMetadata,
                URI resource) {
            this.metadata = metadata;
            this.resourceMetadata = resourceMetadata;
            this.resource = resource;
        }
    }

    private static final class McpOAuthChallenge {
        private URI resourceMetadataUrl;
        private String scope;
        private String error;

        private McpOAuthChallenge(URI resourceMetadataUrl, String scope, String error) {
            this.resourceMetadataUrl = resourceMetadataUrl;
            this.scope = scope;
            this.error = error;
        }
    }

    private static final class McpOAuthSettings {
        private String clientId;
        private String clientSecret;
        private String scope;
        private Integer callbackPort;
        private URI configuredRedirectUri;

        private McpOAuthSettings(
                String clientId,
                String clientSecret,
                String scope,
                Integer callbackPort,
                URI configuredRedirectUri) {
            this.clientId = clientId;
            this.clientSecret = clientSecret;
            this.scope = scope;
            this.callbackPort = callbackPort;
            this.configuredRedirectUri = configuredRedirectUri;
        }
    }

    private static final class McpOAuthFailure extends IOException {
        private int status;
        private String code;

        private McpOAuthFailure(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }

    private static final class McpOAuthEntry {
        private McpOAuthTokens tokens;
        private McpOAuthClientInfo clientInfo;

        private McpOAuthEntry(McpOAuthTokens tokens, McpOAuthClientInfo clientInfo) {
            this.tokens = tokens;
            this.clientInfo = clientInfo;
        }
    }

    private static final class McpOAuthTokens {
        private String accessToken;
        private String refreshToken;
        private Long expiresAt;
        private String scope;

        private McpOAuthTokens(String accessToken, String refreshToken, Long expiresAt, String scope) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.expiresAt = expiresAt;
            this.scope = scope;
        }
    }

    private static final class McpOAuthClientInfo {
        private String clientId;
        private String clientSecret;
        private Long clientIdIssuedAt;
        private Long clientSecretExpiresAt;
        private String tokenEndpointAuthMethod;
        private String redirectUri;

        private McpOAuthClientInfo(
                String clientId,
                String clientSecret,
                Long clientIdIssuedAt,
                Long clientSecretExpiresAt,
                String tokenEndpointAuthMethod,
                String redirectUri) {
            this.clientId = clientId;
            this.clientSecret = clientSecret;
            this.clientIdIssuedAt = clientIdIssuedAt;
            this.clientSecretExpiresAt = clientSecretExpiresAt;
            this.tokenEndpointAuthMethod = tokenEndpointAuthMethod;
            this.redirectUri = redirectUri;
        }
    }

    // McpManager fields
    private Path workspace;
    private LinkedHashMap<String, McpRuntime> servers = new LinkedHashMap<>();
    private volatile boolean closed;

    // McpOAuthClient fields
    private HttpClient http;
    private Predicate<URI> browser;
    private Duration callbackTimeout;
    private SecureRandom random = new SecureRandom();
    private ReentrantLock interactiveLock = new ReentrantLock();

    // McpOAuthStore fields
    private Path path;
    private List<Path> importPaths;

    // ---------------------------------------------------------------- json

    /**
     * Creates an empty mutable JSON object node on the shared mapper.
     */
    public static ObjectNode jsonObject() {
        return Json.MAPPER.createObjectNode();
    }

    // ---------------------------------------------------------------- uuid
    private static final SecureRandom RANDOM = new SecureRandom();
    private static long lastTimestamp = Long.MIN_VALUE;
    private static long sequence;

    private static final Object uuidv7Lock = new Object();
    private static final byte[] hexdigits = {
            '0', '1', '2', '3', '4', '5',
            '6', '7', '8', '9', 'a', 'b',
            'c', 'd', 'e', 'f'
    };
    /**
     * Generates a time-ordered UUIDv7 string.
     */
    public static String uuidv7() {
        byte[] random = new byte[16];
        RANDOM.nextBytes(random);
        long timestampMs;
        long seq;
        synchronized (uuidv7Lock) {
            long now = System.currentTimeMillis();
            if (now > lastTimestamp) {
                sequence = (((random[6] & 0x7FL) << 24) // initialize sequence msb to zero as a rollover guard
                        | ((random[7] & 0xFFL) << 16)
                        | ((random[8] & 0xFFL) << 8)
                        | (random[9] & 0xFFL));
                lastTimestamp = now;
            } else {
                sequence = (sequence + 1) & 0xFFFFFFFFL;
                if (sequence == 0) {
                    lastTimestamp++;
                }
            }
            timestampMs = lastTimestamp;
            seq = sequence;
        }

        byte[] bytes = new byte[16];
        bytes[0] = (byte) (timestampMs >>> 40);
        bytes[1] = (byte) (timestampMs >>> 32);
        bytes[2] = (byte) (timestampMs >>> 24);
        bytes[3] = (byte) (timestampMs >>> 16);
        bytes[4] = (byte) (timestampMs >>> 8);
        bytes[5] = (byte) timestampMs;
        bytes[6] = (byte) (0x70 | ((seq >>> 28) & 0x0F));
        bytes[7] = (byte) ((seq >>> 20) & 0xFF);
        bytes[8] = (byte) (0x80 | ((seq >>> 14) & 0x3F));
        bytes[9] = (byte) ((seq >>> 6) & 0xFF);
        bytes[10] = (byte) (((seq & 0x3F) << 2) | (random[10] & 0x03));
        bytes[11] = random[11];
        bytes[12] = random[12];
        bytes[13] = random[13];
        bytes[14] = random[14];
        bytes[15] = random[15];

        byte[] sb = new byte[36];
        int ci = 0;
        for (int i = 0; i < 16; i++) {
            if (i == 4 || i == 6 || i == 8 || i == 10) sb[ci++] = '-';
            sb[ci++] = hexdigits[bytes[i] >> 4 & 0xF];
            sb[ci++] = hexdigits[bytes[i] & 0xF];
        }
        return new String(sb, 0,36, ISO_8859_1);
    }

    // --------------------------------------------------------- abort signal

    /**
     * Marks the signal aborted and runs (once) every registered listener.
     */
    public void abort(AbortSignal signal) {
        List<Runnable> toRun;
        synchronized (signal) {
            if (signal.aborted) {
                return;
            }
            signal.aborted = true;
            toRun = new ArrayList<>(signal.listeners);
            signal.listeners.clear();
        }
        for (Runnable listener : toRun) {
            listener.run();
        }
    }

    private static boolean isAborted(AbortSignal signal) {
        synchronized (signal) {
            return signal.aborted;
        }
    }

    /**
     * Registers a listener, invoking it immediately if already aborted.
     */
    void onAbort(AbortSignal signal, Runnable listener) {
        boolean runNow;
        synchronized (signal) {
            runNow = signal.aborted;
            if (!signal.aborted) {
                signal.listeners.add(listener);
            }
        }
        if (runNow) {
            listener.run();
        }
    }

    // -------------------------------------------------------- event streams

    /**
     * Pushes an event. Ignored after the stream is done.
     */
    public static <T, R> void push(EventStream<T, R> stream, T event) {
        synchronized (stream) {
            if (stream.done) {
                return;
            }
            if (event instanceof AssistantMessageEvent.Done || event instanceof AssistantMessageEvent.Error) {
                stream.done = true;
                @SuppressWarnings("unchecked")
                R terminalResult = switch (event) {
                    case AssistantMessageEvent.Done done -> (R) done.message;
                    case AssistantMessageEvent.Error error -> (R) error.error;
                    default -> throw new IllegalStateException("Unexpected event type for final result");
                };
                stream.finalResult.complete(terminalResult);
            }
            stream.queue.addLast(event);
            stream.notifyAll();
        }
    }

    /**
     * Blocks until the terminal event arrives and returns the extracted result.
     */
    public static <T, R> R result(EventStream<T, R> stream) throws InterruptedException {
        try {
            return stream.finalResult.get();
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    /**
     * Single-consumer blocking iterator. hasNext() blocks until an event is
     * available or the stream is exhausted (done and queue drained).
     */
    public static <T, R> Iterator<T> iterator(EventStream<T, R> stream) {
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                synchronized (stream) {
                    while (stream.queue.isEmpty() && !stream.done) {
                        try {
                            stream.wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return false;
                        }
                    }
                    return !stream.queue.isEmpty();
                }
            }

            @Override
            public T next() {
                synchronized (stream) {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }
                    return stream.queue.removeFirst();
                }
            }
        };
    }

    /**
     * Blocking for-each view over the stream's events.
     */
    public static <T, R> Iterable<T> events(EventStream<T, R> stream) {
        return () -> iterator(stream);
    }

    // -------------------------------------------------------------- messages

    static String role(Message message) {
        return switch (message) {
            case UserMessage ignored -> "user";
            case AssistantMessage ignored -> "assistant";
            case ToolResultMessage ignored -> "toolResult";
        };
    }

    /**
     * Unix timestamp in milliseconds.
     */
    public static long timestamp(Message message) {
        return switch (message) {
            case UserMessage user -> user.timestamp;
            case AssistantMessage assistant -> assistant.timestamp;
            case ToolResultMessage result -> result.timestamp;
        };
    }

    /**
     * Concatenated text of all text blocks.
     */
    public static String text(AssistantMessage message) {
        return contentText(message.content);
    }

    /**
     * Concatenated text of all thinking blocks.
     */
    public static String thinking(AssistantMessage message) {
        StringBuilder sb = new StringBuilder();
        for (AssistantContent block : message.content) {
            if (block instanceof ThinkingContent thinkingContent) {
                sb.append(thinkingContent.thinking);
            }
        }
        return sb.toString();
    }

    public static List<ToolCall> toolCalls(AssistantMessage message) {
        List<ToolCall> calls = new ArrayList<>();
        for (AssistantContent block : message.content) {
            if (block instanceof ToolCall call) {
                calls.add(call);
            }
        }
        return calls;
    }

    /**
     * Concatenated text of all text blocks.
     */
    public static String text(UserMessage message) {
        return contentText(message.content);
    }

    /**
     * Concatenated text of all text blocks.
     */
    static String text(ToolResultMessage message) {
        return contentText(message.content);
    }

    public static UserMessage userMessage(String text) {
        return new UserMessage(List.of(new TextContent(text, null)), System.currentTimeMillis());
    }

    private static String contentText(List<?> content) {
        StringBuilder sb = new StringBuilder();
        for (Object block : content) {
            if (block instanceof TextContent textContent) {
                sb.append(textContent.text);
            }
        }
        return sb.toString();
    }

    // --------------------------------------------------------------- model

    /**
     * Copy of a model with its mutable collections duplicated.
     */
    public static Model copyModel(Model source) {
        Model copy = new Model();
        copy.id = source.id;
        copy.name = source.name;
        copy.api = source.api;
        copy.provider = source.provider;
        copy.baseUrl = source.baseUrl;
        copy.reasoning = source.reasoning;
        copy.thinkingLevelMap = null;
        if (source.thinkingLevelMap != null) {
            copy.thinkingLevelMap = new EnumMap<>(ThinkingLevel.class);
            copy.thinkingLevelMap.putAll(source.thinkingLevelMap);
        }
        copy.input = new ArrayList<>(source.input);
        copy.cost = source.cost;
        copy.contextWindow = source.contextWindow;
        copy.maxTokens = source.maxTokens;
        copy.headers = new LinkedHashMap<>(source.headers);
        copy.compat = source.compat;
        return copy;
    }

    // ------------------------------------------------------------ wire enums

    private static ThinkingLevel thinkingLevelFromWire(String value) {
        for (ThinkingLevel level : ThinkingLevel.values()) {
            if (level.wire.equals(value)) {
                return level;
            }
        }
        throw new IllegalArgumentException("Unknown thinking level: " + value);
    }

    // ------------------------------------------------------------------ http

    public static String envAnyCase(String key) {
        String lower = System.getenv(key.toLowerCase(Locale.ROOT));
        if (lower != null && !lower.isEmpty()) {
            return lower;
        }
        String upper = System.getenv(key.toUpperCase(Locale.ROOT));
        return upper != null ? upper : "";
    }

    /**
     * POST a JSON body and return the streaming response. Throws HttpException
     * for non-2xx status (body fully read), AbortedException when the signal
     * fires, and IOException for transport failures.
     */
    private HttpTransport.Response httpPostJson(
            String url, Map<String, String> headers, byte[] body, Integer timeoutMs, AbortSignal signal)
            throws IOException {
        return httpPostJson(HttpTransport.CLIENT, url, headers, body, timeoutMs, signal);
    }

    private HttpTransport.Response httpPostJson(
            HttpClient client, String url, Map<String, String> headers, byte[] body, Integer timeoutMs, AbortSignal signal)
            throws IOException {
        return httpPost(client, url, "application/json", headers, body, timeoutMs, signal);
    }

    /**
     * POST an URL-encoded form body and return a streaming response.
     */
    private HttpTransport.Response httpPostForm(
            String url, Map<String, String> headers, byte[] body, Integer timeoutMs, AbortSignal signal)
            throws IOException {
        return httpPostForm(HttpTransport.CLIENT, url, headers, body, timeoutMs, signal);
    }

    private HttpTransport.Response httpPostForm(
            HttpClient client, String url, Map<String, String> headers, byte[] body, Integer timeoutMs, AbortSignal signal)
            throws IOException {
        return httpPost(client, url, "application/x-www-form-urlencoded", headers, body, timeoutMs, signal);
    }

    private HttpTransport.Response httpPost(
            HttpClient client,
            String url,
            String contentType,
            Map<String, String> headers,
            byte[] body,
            Integer timeoutMs,
            AbortSignal signal)
            throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        builder.header("content-type", contentType);
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (header.getValue() != null && !header.getKey().equalsIgnoreCase("content-type")) {
                builder.header(header.getKey(), header.getValue());
            }
        }
        return httpSend(client, builder.build(), timeoutMs, signal);
    }

    private HttpTransport.Response httpGet(
            String url, Map<String, String> headers, Integer timeoutMs, AbortSignal signal) throws IOException {
        return httpGet(HttpTransport.CLIENT, url, headers, timeoutMs, signal);
    }

    private HttpTransport.Response httpGet(
            HttpClient client, String url, Map<String, String> headers, Integer timeoutMs, AbortSignal signal) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (header.getValue() != null) {
                builder.header(header.getKey(), header.getValue());
            }
        }
        return httpSend(client, builder.build(), timeoutMs, signal);
    }

    /**
     * Sends a request whose body is consumed as a stream. The timeout bounds
     * the wait for response headers and then each read of the body, but never
     * the total response time: model streams can legitimately run for longer
     * than any single timeout. This deliberately avoids
     * HttpRequest.Builder.timeout, which since JDK 26 (JDK-8208693) also caps
     * the time spent consuming the whole body.
     */
    private HttpTransport.Response httpSend(
            HttpClient client, HttpRequest request, Integer timeoutMs, AbortSignal signal) throws IOException {
        long timeout = timeoutMs != null ? timeoutMs : HttpTransport.DEFAULT_TIMEOUT_MS;
        if (timeout <= 0) {
            throw new IllegalArgumentException("timeoutMs must be positive: " + timeout);
        }
        if (signal != null && isAborted(signal)) {
            throw new HttpTransport.AbortedException();
        }
        CompletableFuture<HttpResponse<InputStream>> future =
                client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        if (signal != null) {
            onAbort(signal, () -> future.cancel(true));
        }
        HttpResponse<InputStream> response;
        try {
            response = future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new HttpTimeoutException("Request to " + request.uri().getHost()
                    + " timed out after " + timeout + " ms waiting for response headers");
        } catch (CancellationException e) {
            throw new HttpTransport.AbortedException();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HttpTransport.AbortedException();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (signal != null && isAborted(signal)) {
                throw new HttpTransport.AbortedException();
            }
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException(cause);
        }

        Map<String, String> responseHeaders = new LinkedHashMap<>();
        response.headers().map().forEach((key, values) -> {
            if (!values.isEmpty()) {
                responseHeaders.put(key.toLowerCase(Locale.ROOT), values.getFirst());
            }
        });

        InputStream bodyStream = response.body() == null
                ? null
                : new IdleTimeoutInputStream(response.body(), timeout, request.uri().getHost());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String errorBody;
            if (bodyStream == null) {
                errorBody = "";
            } else {
                try (bodyStream) {
                    errorBody = new String(bodyStream.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            int status = response.statusCode();
            String trimmed = errorBody.trim();
            String detail = trimmed.length() <= HttpException.MAX_ERROR_BODY_CHARS
                    ? trimmed
                    : trimmed.substring(0, HttpException.MAX_ERROR_BODY_CHARS) + "... [truncated "
                      + (trimmed.length() - HttpException.MAX_ERROR_BODY_CHARS) + " chars]";
            String target = request.uri().toString();
            String prefix = status + " from " + target;
            throw new HttpException(
                    status, errorBody, trimmed.isEmpty() ? prefix + " (no body)" : prefix + ": " + detail);
        }
        if (bodyStream == null) {
            throw new IOException("No response body from " + request.uri().getHost()
                    + " (status " + response.statusCode() + "). The request may have been blocked by a sandbox or proxy.");
        }
        if (signal != null) {
            onAbort(signal, () -> {
                try {
                    bodyStream.close();
                } catch (IOException ignored) {
                }
            });
        }
        return new HttpTransport.Response(response.statusCode(), responseHeaders, bodyStream);
    }

    /**
     * Releases a streaming response body; failures are ignored.
     */
    private void closeHttpResponse(HttpTransport.Response response) {
        if (response == null) {
            return;
        }
        try {
            response.body.close();
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------------- sse

    /**
     * Wraps a byte stream in the UTF-8 buffered reader the SSE parser reads from.
     */
    public static SseReader sseReader(InputStream stream) {
        return new SseReader(new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8)));
    }

    /**
     * Next server-sent event, or null at end of stream. Parses
     * `event:`/`data:`/`id:` fields and dispatches on blank lines per the SSE
     * spec. Multiple data lines are joined with newlines; comment lines
     * (leading ':') are ignored.
     */
    public static SseReader.SseEvent nextSseEvent(SseReader source) throws IOException {
        String event = null;
        StringBuilder data = null;
        String id = null;
        String line;
        while ((line = source.reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (data != null || event != null || id != null) {
                    return new SseReader.SseEvent(event, data != null ? data.toString() : "", id);
                }
                continue;
            }
            if (line.startsWith(":")) {
                continue;
            }
            int colon = line.indexOf(':');
            String field = colon == -1 ? line : line.substring(0, colon);
            String value = colon == -1 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            switch (field) {
                case "event" -> event = value;
                case "data" -> {
                    if (data == null) {
                        data = new StringBuilder(value);
                    } else {
                        data.append('\n').append(value);
                    }
                }
                case "id" -> id = value;
                default -> {
                    // ignore unknown fields (incl. "retry")
                }
            }
        }
        if (data != null || event != null || id != null) {
            return new SseReader.SseEvent(event, data != null ? data.toString() : "", id);
        }
        return null;
    }

    /**
     * Releases an SSE reader; failures are ignored.
     */
    public void closeSseReader(SseReader source) {
        if (source == null) {
            return;
        }
        try {
            source.reader.close();
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------- env api keys

    /**
     * Returns configured key environment-variable names in provider priority order.
     */
    public static List<String> findApiKeyEnvVars(String provider, Map<String, String> environment) {
        List<String> names = EnvApiKeys.API_KEY_ENV_VARS.get(provider);
        if (names == null) {
            return List.of();
        }
        return names.stream()
                .filter(name -> {
                    String value = environment.get(name);
                    return value != null && !value.isBlank();
                })
                .toList();
    }

    /**
     * Resolves a key value from the given environment. Anthropic's
     * ANTHROPIC_AUTH_TOKEN is intentionally omitted because it requires bearer
     * authorization rather than x-api-key; its provider adapter handles it.
     */
    public static Optional<String> resolveApiKey(String provider, Map<String, String> environment) {
        for (String name : findApiKeyEnvVars(provider, environment)) {
            if (provider.equals("anthropic") && name.equals(EnvApiKeys.ANTHROPIC_AUTH_TOKEN_ENV)) {
                continue;
            }
            return Optional.of(environment.get(name));
        }
        return Optional.empty();
    }

    private static Optional<String> resolveSystemApiKey(String provider) {
        return resolveApiKey(provider, System.getenv());
    }

    /**
     * Returns the local or hosted Anthropic-compatible endpoint selected by an
     * Anthropic CLI proxy, or null when the official endpoint should be used.
     */
    public static String configuredAnthropicBaseUrl(Map<String, String> environment) {
        String baseUrl = environment.get("ANTHROPIC_BASE_URL");
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("ANTHROPIC_BASE_URL must be an absolute HTTP(S) URL", error);
        }
        if (uri.getScheme() == null
                || uri.getHost() == null
                || (!uri.getScheme().equalsIgnoreCase("http") && !uri.getScheme().equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("ANTHROPIC_BASE_URL must be an absolute HTTP(S) URL");
        }
        return trimTrailingSlash(baseUrl);
    }

    static boolean isAnthropicProxyConfigured() {
        return configuredAnthropicBaseUrl(System.getenv()) != null;
    }

    // ----------------------------------------------------------- credentials

    /**
     * Resolves the credential file, its sibling lock file, and the optional legacy file.
     */
    public void fileCredentialStore(Path authPath, Path fallbackAuthPath) {
        Path resolved = authPath.toAbsolutePath().normalize();

        this.authPath = resolved;
        authLockPath = resolved.resolveSibling(resolved.getFileName() + ".lock");
        this.fallbackAuthPath =
                fallbackAuthPath == null ? null : fallbackAuthPath.toAbsolutePath().normalize();
    }

    private static String credentialType(Credential credential) {
        return switch (credential) {
            case Credential.ApiKeyCredential ignored -> "api_key";
            case Credential.OAuthCredential ignored -> "oauth";
        };
    }

    /** Returns the configured user-home-derived application locations. */
    public CodingAgentPaths applicationPaths() {
        return applicationPaths;
    }

    /**
     * Configures the user-home-derived locations used for settings, credentials,
     * sessions, MCP state, and global agent instructions.
     *
     * <p>Call this before initializing providers, configuring an agent, or
     * opening a session. It lets embedders isolate codingagent state without
     * changing the JVM-global {@code user.home} property.
     */
    public void applicationPaths(CodingAgentPaths applicationPaths) {
        requireIdleAgent();
        this.applicationPaths = Objects.requireNonNull(applicationPaths, "applicationPaths");
    }

    CodingAgentOperations defaultCredentialStore() {
        fileCredentialStore(applicationPaths.authFile(), applicationPaths.legacyAuthFile());
        return this;
    }

    private Optional<Credential> readCredential(CredentialStore store, String providerId) throws IOException {
        return switch (store) {
            case CodingAgentOperations file -> {
                validateProviderId(providerId);
                yield Optional.ofNullable(file.readAllCredentials().get(providerId));
            }
            default -> throw new IllegalArgumentException("Unknown credential store: " + store.getClass().getName());
        };
    }

    /**
     * Atomically applies a mutation to a provider credential. Returning null
     * removes the credential.
     */
    public void modifyCredential(
            CredentialStore store, String providerId, UnaryOperator<Credential> operation) throws IOException {
        synchronized (CREDENTIAL_FILES) {
            switch (store) {
                case CodingAgentOperations file -> {
                    validateProviderId(providerId);
                    if (operation == null) {
                        throw new IllegalArgumentException("operation must not be null");
                    }
                    Files.createDirectories(file.authPath.getParent());
                    setPosixPermissions(file.authPath.getParent(), DIRECTORY_PERMISSIONS);
                    try (FileChannel channel =
                                 FileChannel.open(file.authLockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                         FileLock ignored = channel.lock()) {
                        Map<String, Credential> credentials = file.readAllCredentials();
                        Credential next = operation.apply(credentials.get(providerId));
                        if (next == null) {
                            credentials.remove(providerId);
                        } else {
                            credentials.put(providerId, next);
                        }
                        ObjectNode root = jsonObject();
                        for (Map.Entry<String, Credential> entry : credentials.entrySet()) {
                            ObjectNode node = jsonObject();
                            switch (entry.getValue()) {
                                case Credential.ApiKeyCredential apiKey -> {
                                    node.put("type", credentialType(apiKey));
                                    if (apiKey.key != null) {
                                        node.put("key", apiKey.key);
                                    }
                                    if (!apiKey.env.isEmpty()) {
                                        ObjectNode env = node.putObject("env");
                                        apiKey.env.forEach(env::put);
                                    }
                                }
                                case Credential.OAuthCredential oauth -> {
                                    node.put("type", credentialType(oauth));
                                    node.put("access", oauth.access);
                                    node.put("refresh", oauth.refresh);
                                    node.put("expires", oauth.expires);
                                    if (oauth.availableModelIds != null) {
                                        ArrayNode ids = node.putArray("availableModelIds");
                                        oauth.availableModelIds.forEach(ids::add);
                                    }
                                    if (!oauth.metadata.isEmpty()) {
                                        ObjectNode metadata = node.putObject("metadata");
                                        oauth.metadata.forEach(metadata::put);
                                    }
                                }
                            }
                            root.set(entry.getKey(), node);
                        }
                        Path temp = Files.createTempFile(file.authPath.getParent(), "auth-", ".json");
                        try {
                            Files.writeString(temp, Json.MAPPER.writeValueAsString(root) + "\n", StandardCharsets.UTF_8);
                            setPosixPermissions(temp, FILE_PERMISSIONS);
                            try {
                                Files.move(temp, file.authPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                            } catch (AtomicMoveNotSupportedException e) {
                                Files.move(temp, file.authPath, StandardCopyOption.REPLACE_EXISTING);
                            }
                            setPosixPermissions(file.authPath, FILE_PERMISSIONS);
                        } finally {
                            Files.deleteIfExists(temp);
                        }
                        break;
                    }
                }
                default -> throw new IllegalArgumentException("Unknown credential store: " + store.getClass().getName());
            }
        }
    }

    void deleteCredential(CredentialStore store, String providerId) throws IOException {
        modifyCredential(store, providerId, ignored -> null);
    }

    private Map<String, Credential> readAllCredentials() throws IOException {
        Path source = Files.exists(authPath) ? authPath : fallbackAuthPath;
        if (source == null || !Files.exists(source)) {
            return new LinkedHashMap<>();
        }
        JsonNode root;
        try {
            root = Json.MAPPER.readTree(Files.readString(source, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IOException("Failed to read credential file " + source + ": " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IOException("Invalid credential file " + source + ": expected a JSON object");
        }
        Map<String, Credential> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : root.properties()) {
            validateProviderId(entry.getKey());
            String providerId = entry.getKey();
            JsonNode node = entry.getValue();
            if (!node.isObject() || !node.path("type").isTextual()) {
                throw invalidCredential(providerId);
            }
            result.put(entry.getKey(), switch (node.path("type").asText()) {
                case "api_key" -> {
                    JsonNode value = node.get("key");
                    if (value != null && !value.isTextual()) throw new IOException("Invalid credential field: key");
                    yield new Credential.ApiKeyCredential(
                            value == null ? null : value.asText(), Map.copyOf(parseCredentialEnv(providerId, node.get("env"))));
                }
                case "oauth" -> {
                    if (!node.path("access").isTextual()
                            || !node.path("refresh").isTextual()
                            || !node.path("expires").isIntegralNumber()) {
                        throw invalidCredential(providerId);
                    }
                    List<String> result1 = null;
                    JsonNode node1 = node.get("availableModelIds");
                    if (node1 != null) {
                        if (!node1.isArray()) {
                            throw invalidCredential(providerId);
                        }
                        List<String> values = new ArrayList<>();
                        for (JsonNode value : node1) {
                            if (!value.isTextual()) {
                                throw invalidCredential(providerId);
                            }
                            values.add(value.asText());
                        }
                        result1 = values;
                    }
                    yield new Credential.OAuthCredential(
                            node.path("access").asText(),
                            node.path("refresh").asText(),
                            node.path("expires").asLong(),
                            result1 == null ? null : List.copyOf(result1),
                            Map.copyOf(parseCredentialEnv(providerId, node.get("metadata"))));
                }
                default -> throw invalidCredential(providerId);
            });
        }
        return result;
    }

    private static Map<String, String> parseCredentialEnv(String providerId, JsonNode node) throws IOException {
        if (node == null) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw invalidCredential(providerId);
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            if (!entry.getValue().isTextual()) {
                throw invalidCredential(providerId);
            }
            values.put(entry.getKey(), entry.getValue().asText());
        }
        return values;
    }

    private static IOException invalidCredential(String providerId) {
        return new IOException("Invalid credential for provider \"" + providerId + "\"");
    }

    private void setPosixPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Windows lacks POSIX permissions; its ACLs still protect the user profile.
        }
    }

    private void validateProviderId(String providerId) {
        if (providerId == null || providerId.isBlank() || providerId.indexOf('/') >= 0 || providerId.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Invalid provider id: " + providerId);
        }
    }

    // -------------------------------------------------------- chatgpt auth

    /**
     * Validates the authorization endpoint and client id before binding them to the carrier.
     */
    public void chatGptAuth(ProviderState state, CredentialStore credentials, URI authBaseUrl, String clientId) {
        URI validatedBaseUrl = requireAbsoluteHttpUri(authBaseUrl, "authBaseUrl");
        if (clientId == null || clientId.isBlank()) throw new IllegalArgumentException("clientId must not be blank");

        state.credentials = credentials;
        state.authBaseUrl = validatedBaseUrl;
        state.clientId = clientId;
    }

    /**
     * Starts the Codex device flow. Display the URI and code before completing it.
     */
    public ChatGptDeviceCode chatGptBeginLogin(ProviderState state) throws IOException {
        ObjectNode request = jsonObject().put("client_id", state.clientId);
        JsonNode response = authPost(
                state.authBaseUrl.resolve("/api/accounts/deviceauth/usercode"),
                Map.of("Accept", "application/json"),
                Json.MAPPER.writeValueAsBytes(request),
                false);
        String deviceAuthId = requiredAuthText(response, "device_auth_id", "OpenAI");
        String userCode = requiredAuthText(response, "user_code", "OpenAI");
        JsonNode node = response.path("interval");
        int interval;
        try {
            interval = node.isIntegralNumber() ? node.asInt() : Integer.parseInt(node.asText("5"));
        } catch (NumberFormatException error) {
            throw new IOException("Invalid OpenAI device response: invalid interval", error);
        }
        if (interval < 0) {
            throw new IOException("Invalid OpenAI device response: interval must not be negative");
        }
        return new ChatGptDeviceCode(
                deviceAuthId,
                userCode,
                state.authBaseUrl.resolve("/codex/device"),
                interval,
                System.currentTimeMillis() + CHATGPT_DEVICE_CODE_LIFETIME_MS);
    }

    /**
     * Waits for browser authorization, exchanges the code, and saves refreshable tokens.
     */
    public Credential.OAuthCredential chatGptCompleteLogin(ProviderState state, ChatGptDeviceCode device)
            throws IOException, InterruptedException {
        JsonNode authorization = null;
        ObjectNode request = jsonObject()
                .put("device_auth_id", device.deviceAuthId)
                .put("user_code", device.userCode);
        while (System.currentTimeMillis() < device.expiresAtMs) {
            try {
                authorization = authPost(
                        state.authBaseUrl.resolve("/api/accounts/deviceauth/token"),
                        Map.of("Accept", "application/json"),
                        Json.MAPPER.writeValueAsBytes(request),
                        false);
                break;
            } catch (HttpException error) {
                if (error.status != 403 && error.status != 404) {
                    throw error;
                }
                sleepSeconds(device.intervalSeconds);
            }
        }
        if (authorization == null) {
            throw new IOException("ChatGPT device authorization expired before completion");
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", requiredAuthText(authorization, "authorization_code", "OpenAI"));
        form.put("redirect_uri", "https://auth.openai.com/deviceauth/callback");
        form.put("client_id", state.clientId);
        form.put("code_verifier", requiredAuthText(authorization, "code_verifier", "OpenAI"));
        Credential.OAuthCredential credential =
                chatGptCredentialFromTokenResponse(authPost(
                        state.authBaseUrl.resolve("/oauth/token"),
                        Map.of("Accept", "application/json"),
                        mcpFormEncode(form).getBytes(StandardCharsets.UTF_8),
                        true), null);
        modifyCredential(state.credentials, CHATGPT_PROVIDER_ID, ignored -> credential);
        return credential;
    }

    /**
     * Returns a usable ChatGPT bearer token, refreshing it when close to expiry.
     */
    public ChatGptToken chatGptResolveToken(ProviderState state) throws IOException {
        synchronized (CREDENTIAL_FILES) {
            Credential credential = readCredential(state.credentials, CHATGPT_PROVIDER_ID)
                    .orElseThrow(() -> new IOException("ChatGPT Plus/Pro is not logged in. Run /login."));
            if (!(credential instanceof Credential.OAuthCredential oauth)) {
                throw new IOException("ChatGPT credential is not an OAuth credential. Run /login.");
            }
            if (oauth.expires > System.currentTimeMillis() && !oauth.access.isBlank()) {
                return chatGptToken(oauth);
            }
            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", "refresh_token");
            form.put("refresh_token", oauth.refresh);
            form.put("client_id", state.clientId);
            Credential.OAuthCredential refreshed =
                    chatGptCredentialFromTokenResponse(authPost(
                            state.authBaseUrl.resolve("/oauth/token"),
                            Map.of("Accept", "application/json"),
                            mcpFormEncode(form).getBytes(StandardCharsets.UTF_8),
                            true), oauth);
            modifyCredential(state.credentials, CHATGPT_PROVIDER_ID, ignored -> refreshed);
            return chatGptToken(refreshed);
        }
    }

    public boolean chatGptHasCredential(ProviderState state) throws IOException {
        return hasRefreshCredential(state.credentials, CHATGPT_PROVIDER_ID);
    }

    public void chatGptLogout(ProviderState state) throws IOException {
        deleteCredential(state.credentials, CHATGPT_PROVIDER_ID);
    }

    private static Credential.OAuthCredential chatGptCredentialFromTokenResponse(
            JsonNode response, Credential.OAuthCredential previous) throws IOException {
        String access = requiredAuthText(response, "access_token", "OpenAI");
        String refresh = response.path("refresh_token").asText(previous == null ? "" : previous.refresh);
        if (refresh.isBlank()) {
            throw new IOException("Invalid OpenAI response: missing refresh_token");
        }
        long expiresIn = response.path("expires_in").asLong(3600);
        long expires =
                System.currentTimeMillis() + Math.max(1, expiresIn) * 1000 - CHATGPT_REFRESH_SKEW_MS;
        Map<String, String> metadata = new LinkedHashMap<>(previous == null ? Map.of() : previous.metadata);
        String idToken = response.path("id_token").asText();
        String accountId;
        if (idToken == null || idToken.isBlank()) {
            accountId = "";
        } else {
            String[] parts = idToken.split("\\.");
            if (parts.length < 2) {
                throw new IOException("OpenAI returned an invalid ID token");
            }
            try {
                JsonNode claims = Json.MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
                accountId = claims.path("https://api.openai.com/auth")
                        .path("chatgpt_account_id")
                        .asText(claims.path("https://api.openai.com/auth.chatgpt_account_id").asText());
            } catch (IllegalArgumentException error) {
                throw new IOException("OpenAI returned an invalid ID token", error);
            }
        }
        if (!accountId.isBlank()) {
            metadata.put(CHATGPT_ACCOUNT_ID, accountId);
        }
        if (!metadata.containsKey(CHATGPT_ACCOUNT_ID)) {
            throw new IOException("OpenAI login did not return a ChatGPT account id");
        }
        return new Credential.OAuthCredential(access, refresh, expires, null, Map.copyOf(metadata));
    }

    private static ChatGptToken chatGptToken(Credential.OAuthCredential credential) throws IOException {
        String accountId = credential.metadata.get(CHATGPT_ACCOUNT_ID);
        if (accountId == null || accountId.isBlank()) {
            throw new IOException("Saved ChatGPT login is missing its account id. Run /login again.");
        }
        return new ChatGptToken(credential.access, accountId);
    }

    private JsonNode authPost(URI url, Map<String, String> headers, byte[] body, boolean form)
            throws IOException {
        return authPost(HttpTransport.CLIENT, url, headers, body, form);
    }

    private JsonNode authPost(HttpClient client, URI url, Map<String, String> headers, byte[] body, boolean form)
            throws IOException {
        HttpTransport.Response response = form
                ? httpPostForm(client, url.toString(), headers, body, null, null)
                : httpPostJson(client, url.toString(), headers, body, null, null);
        try {
            return Json.MAPPER.readTree(response.body);
        } finally {
            closeHttpResponse(response);
        }
    }

    private boolean hasRefreshCredential(CredentialStore store, String provider) throws IOException {
        return readCredential(store, provider)
                .filter(Credential.OAuthCredential.class::isInstance)
                .map(Credential.OAuthCredential.class::cast)
                .map(value -> !value.refresh.isBlank())
                .orElse(false);
    }

    private static String requiredAuthText(JsonNode node, String field, String service) throws IOException {
        if (!node.path(field).isTextual() || node.path(field).asText().isBlank()) {
            throw new IOException("Invalid " + service + " response: missing " + field);
        }
        return node.path(field).asText();
    }

    private static URI requireAbsoluteHttpUri(URI uri, String field) {
        if (uri == null || !uri.isAbsolute() || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))) {
            throw new IllegalArgumentException(field + " must be an absolute HTTP(S) URI");
        }
        return uri;
    }

    private void sleepSeconds(int seconds) throws InterruptedException {
        if (seconds > 0) {
            Thread.sleep(seconds * 1000L);
        }
    }

    // -------------------------------------------------- github copilot auth

    /**
     * Validates every endpoint before binding it to the carrier. Visible for deterministic HTTP tests.
     */
    public void gitHubCopilotAuth(
            ProviderState state,
            CredentialStore credentials,
            URI githubBaseUrl,
            URI copilotTokenUrl,
            URI defaultCopilotBaseUrl) {
        state.credentials = credentials;
        state.githubBaseUrl = requireAbsoluteHttpUri(githubBaseUrl, "githubBaseUrl");
        state.copilotTokenUrl = requireAbsoluteHttpUri(copilotTokenUrl, "copilotTokenUrl");
        state.defaultCopilotBaseUrl = requireAbsoluteHttpUri(defaultCopilotBaseUrl, "defaultCopilotBaseUrl");
    }

    /**
     * Starts the device flow. Display the resulting URI and code before completing the login.
     */
    public GitHubCopilotDeviceCode gitHubCopilotBeginLogin(ProviderState state) throws IOException {
        JsonNode response = authPost(
                HttpTransport.COPILOT_CLIENT,
                state.githubBaseUrl.resolve("/login/device/code"),
                Map.of("Accept", "application/json", "User-Agent", GITHUB_COPILOT_USER_AGENT),
                mcpFormEncode(Map.of("client_id", GITHUB_COPILOT_CLIENT_ID, "scope", "read:user"))
                        .getBytes(StandardCharsets.UTF_8),
                true);
        String deviceCode = requiredAuthText(response, "device_code", "GitHub");
        String userCode = requiredAuthText(response, "user_code", "GitHub");
        URI verificationUri = requireAbsoluteHttpUri(
                URI.create(requiredAuthText(response, "verification_uri", "GitHub")), "verification_uri");
        long expiresIn = requiredPositiveLong(response, "expires_in");
        int interval = response.path("interval").isIntegralNumber() ? response.path("interval").asInt() : 5;
        if (interval < 0) {
            throw new IOException("Invalid device code response: interval must not be negative");
        }
        return new GitHubCopilotDeviceCode(
                deviceCode, userCode, verificationUri, interval, System.currentTimeMillis() + expiresIn * 1000);
    }

    /**
     * Polls GitHub, exchanges the durable GitHub token for a Copilot token, and saves the credential.
     */
    public Credential.OAuthCredential gitHubCopilotCompleteLogin(
            ProviderState state, GitHubCopilotDeviceCode device) throws IOException, InterruptedException {
        String githubAccessToken = null;
        int intervalSeconds = device.intervalSeconds;
        while (System.currentTimeMillis() < device.expiresAtMs) {
            JsonNode response = authPost(
                    HttpTransport.COPILOT_CLIENT,
                    state.githubBaseUrl.resolve("/login/oauth/access_token"),
                    Map.of("Accept", "application/json", "User-Agent", GITHUB_COPILOT_USER_AGENT),
                    mcpFormEncode(Map.of(
                            "client_id", GITHUB_COPILOT_CLIENT_ID,
                            "device_code", device.deviceCode,
                            "grant_type", "urn:ietf:params:oauth:grant-type:device_code"))
                            .getBytes(StandardCharsets.UTF_8),
                    true);
            if (response.path("access_token").isTextual()) {
                githubAccessToken = response.path("access_token").asText();
                break;
            }
            String error = response.path("error").asText();
            if (error.equals("authorization_pending")) {
                sleepSeconds(intervalSeconds);
                continue;
            }
            if (error.equals("slow_down")) {
                intervalSeconds = Math.max(intervalSeconds + 5, response.path("interval").asInt(0));
                sleepSeconds(intervalSeconds);
                continue;
            }
            String description = response.path("error_description").asText();
            throw new IOException(
                    "GitHub device authorization failed: " + error + (description.isBlank() ? "" : ": " + description));
        }
        if (githubAccessToken == null) {
            throw new IOException("GitHub device authorization expired before completion");
        }
        Credential.OAuthCredential credential = createCopilotCredential(state, githubAccessToken, null);
        try {
            credential = withCopilotAvailableModels(state, credential);
        } catch (IOException ignored) {
            // The Copilot token is valid even if its optional model catalog is transiently unavailable.
        }
        Credential.OAuthCredential saved = credential;
        modifyCredential(state.credentials, GITHUB_COPILOT_PROVIDER_ID, ignored -> saved);
        return credential;
    }

    /**
     * Returns a valid Copilot API token, refreshing it from the stored GitHub token when necessary.
     */
    public CopilotToken gitHubCopilotResolveToken(ProviderState state) throws IOException {
        synchronized (CREDENTIAL_FILES) {
            Credential credential = readCredential(state.credentials, GITHUB_COPILOT_PROVIDER_ID)
                    .orElseThrow(() -> new IOException("GitHub Copilot is not logged in. Run /login."));
            if (!(credential instanceof Credential.OAuthCredential oauth)) {
                throw new IOException("GitHub Copilot credential is not an OAuth credential. Run /login.");
            }
            if (!(oauth.expires <= System.currentTimeMillis()) && !oauth.access.isBlank()) {
                return copilotToken(state, oauth);
            }
            Credential.OAuthCredential refreshed = createCopilotCredential(state, oauth.refresh, oauth.availableModelIds);
            try {
                refreshed = withCopilotAvailableModels(state, refreshed);
            } catch (IOException ignored) {
                // Retain the last known entitlement list if model discovery cannot be refreshed.
            }
            Credential.OAuthCredential saved = refreshed;
            modifyCredential(state.credentials, GITHUB_COPILOT_PROVIDER_ID, ignored -> saved);
            return copilotToken(state, refreshed);
        }
    }

    /**
     * Reports whether a saved GitHub OAuth credential can be refreshed.
     */
    boolean gitHubCopilotHasCredential(ProviderState state) throws IOException {
        return hasRefreshCredential(state.credentials, GITHUB_COPILOT_PROVIDER_ID);
    }

    void gitHubCopilotLogout(ProviderState state) throws IOException {
        deleteCredential(state.credentials, GITHUB_COPILOT_PROVIDER_ID);
    }

    /**
     * Enables the listed Copilot model policies and reports how many policy requests GitHub accepted.
     */
    public int gitHubCopilotEnableModels(ProviderState state, List<String> modelIds) throws IOException {
        CopilotToken token = gitHubCopilotResolveToken(state);
        int enabled = 0;
        for (String modelId : modelIds) {
            URI policyUrl = token.baseUrl.resolve("/models/" + encodeUrlPathSegment(modelId) + "/policy");
            Map<String, String> headers = copilotModelHeaders(token.accessToken);
            headers.put("openai-intent", "chat-policy");
            headers.put("x-interaction-type", "chat-policy");
            try {
                HttpTransport.Response response = httpPostJson(
                        HttpTransport.COPILOT_CLIENT,
                        policyUrl.toString(),
                        headers,
                        "{\"state\":\"enabled\"}".getBytes(StandardCharsets.UTF_8),
                        5_000,
                        null);
                closeHttpResponse(response);
                enabled++;
            } catch (HttpException ignored) {
                // Some account plans do not expose every catalog model; continue enabling the rest.
            }
        }
        return enabled;
    }

    /**
     * Re-fetches and persists the enabled-model list without minting a new Copilot API token.
     */
    public CopilotToken gitHubCopilotRefreshAvailableModels(ProviderState state)
            throws IOException {
        CopilotToken current = gitHubCopilotResolveToken(state);
        Credential credential = readCredential(state.credentials, GITHUB_COPILOT_PROVIDER_ID)
                .orElseThrow(() -> new IOException("GitHub Copilot is not logged in. Run /login."));
        if (!(credential instanceof Credential.OAuthCredential oauth)) {
            throw new IOException("GitHub Copilot credential is not an OAuth credential. Run /login.");
        }
        Credential.OAuthCredential refreshed = new Credential.OAuthCredential(
                oauth.access,
                oauth.refresh,
                oauth.expires,
                List.copyOf(fetchCopilotAvailableModelIds(state, current.accessToken)),
                Map.of());
        modifyCredential(state.credentials, GITHUB_COPILOT_PROVIDER_ID, ignored -> refreshed);
        return copilotToken(state, refreshed);
    }

    private Credential.OAuthCredential createCopilotCredential(
            ProviderState state, String githubAccessToken, List<String> availableModelIds) throws IOException {
        if (githubAccessToken == null || githubAccessToken.isBlank()) {
            throw new IOException("GitHub returned an empty access token");
        }
        JsonNode response;
        HttpTransport.Response http = httpGet(
                HttpTransport.COPILOT_CLIENT,
                state.copilotTokenUrl.toString(), copilotHeaders("token " + githubAccessToken), null, null);
        try {
            response = Json.MAPPER.readTree(http.body);
        } finally {
            closeHttpResponse(http);
        }
        String token = requiredAuthText(response, "token", "GitHub");
        long expiresAtSeconds = requiredPositiveLong(response, "expires_at");
        long expires = Math.max(
                System.currentTimeMillis(), expiresAtSeconds * 1000 - GITHUB_COPILOT_REFRESH_SKEW_MS);
        return new Credential.OAuthCredential(
                token,
                githubAccessToken,
                expires,
                availableModelIds == null ? null : List.copyOf(availableModelIds),
                Map.of());
    }

    private Credential.OAuthCredential withCopilotAvailableModels(
            ProviderState state, Credential.OAuthCredential credential) throws IOException {
        List<String> available = fetchCopilotAvailableModelIds(state, credential.access);
        return new Credential.OAuthCredential(
                credential.access, credential.refresh, credential.expires, List.copyOf(available), Map.of());
    }

    private List<String> fetchCopilotAvailableModelIds(ProviderState state, String copilotToken)
            throws IOException {
        URI modelsUrl = copilotBaseUrlFromToken(state, copilotToken).resolve("/models");
        JsonNode response;
        HttpTransport.Response http = httpGet(
                HttpTransport.COPILOT_CLIENT, modelsUrl.toString(), copilotModelHeaders(copilotToken), 5_000, null);
        try {
            response = Json.MAPPER.readTree(http.body);
        } finally {
            closeHttpResponse(http);
        }
        JsonNode data = response.path("data");
        if (!data.isArray()) {
            throw new IOException("Invalid Copilot models response");
        }
        List<String> pickerEnabled = new ArrayList<>();
        List<String> policyEnabled = new ArrayList<>();
        for (JsonNode model : data) {
            if (!model.path("id").isTextual()
                    || !model.path("capabilities").path("supports").path("tool_calls").asBoolean(true)) {
                continue;
            }
            String id = model.path("id").asText();
            if (model.path("model_picker_enabled").asBoolean(false)
                    && !model.path("policy").path("state").asText().equals("disabled")) {
                pickerEnabled.add(id);
            }
            if (model.path("policy").path("state").asText().equals("enabled")) {
                policyEnabled.add(id);
            }
        }
        return pickerEnabled.isEmpty()
                && copilotBaseUrlFromToken(state, copilotToken)
                .toString()
                .equals(GITHUB_COPILOT_DEFAULT_BASE_URL)
                ? List.copyOf(policyEnabled)
                : List.copyOf(pickerEnabled);
    }

    private CopilotToken copilotToken(ProviderState state, Credential.OAuthCredential credential) {
        List<String> availableModelIds = credential.availableModelIds;
        return new CopilotToken(
                credential.access,
                copilotBaseUrlFromToken(state, credential.access),
                availableModelIds == null ? null : List.copyOf(availableModelIds));
    }

    private URI copilotBaseUrlFromToken(ProviderState state, String token) {
        Matcher match = GITHUB_COPILOT_PROXY_ENDPOINT.matcher(token);
        if (!match.find()) {
            return state.defaultCopilotBaseUrl;
        }
        String host = match.group(1);
        if (!host.matches("[A-Za-z0-9.-]+")) {
            return state.defaultCopilotBaseUrl;
        }
        return URI.create("https://" + host.replaceFirst("^proxy\\.", "api."));
    }

    private static Map<String, String> copilotHeaders(String authorization) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("Authorization", authorization);
        headers.put("User-Agent", GITHUB_COPILOT_USER_AGENT);
        headers.put("Editor-Version", "vscode/1.107.0");
        headers.put("Editor-Plugin-Version", "copilot-chat/0.35.0");
        headers.put("Copilot-Integration-Id", "vscode-chat");
        return headers;
    }

    private static Map<String, String> copilotModelHeaders(String copilotToken) {
        Map<String, String> headers = copilotHeaders("Bearer " + copilotToken);
        headers.put("X-GitHub-Api-Version", "2026-06-01");
        return headers;
    }

    private static String encodeUrlPathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static long requiredPositiveLong(JsonNode node, String field) throws IOException {
        if (!node.path(field).isIntegralNumber() || node.path(field).asLong() <= 0) {
            throw new IOException("Invalid GitHub response: missing " + field);
        }
        return node.path(field).asLong();
    }

    // ----------------------------------------------------------- model ops

    /**
     * Computes and stores cost on usage.cost, returning it.
     */
    public static Usage.Cost calculateCost(Model model, Usage usage) {
        long inputTokens = usage.input + usage.cacheRead + usage.cacheWrite;
        double rateInput = model.cost.input;
        double rateOutput = model.cost.output;
        double rateCacheRead = model.cost.cacheRead;
        double rateCacheWrite = model.cost.cacheWrite;
        long matchedThreshold = -1;
        for (ModelCost.Tier tier : model.cost.tiers) {
            if (inputTokens > tier.inputTokensAbove && tier.inputTokensAbove > matchedThreshold) {
                rateInput = tier.input;
                rateOutput = tier.output;
                rateCacheRead = tier.cacheRead;
                rateCacheWrite = tier.cacheWrite;
                matchedThreshold = tier.inputTokensAbove;
            }
        }

        // Anthropic charges 2x base input for 1h cache writes.
        long longWrite = usage.cacheWrite1h != null ? usage.cacheWrite1h : 0;
        long shortWrite = usage.cacheWrite - longWrite;
        usage.cost.input = (rateInput / 1_000_000) * usage.input;
        usage.cost.output = (rateOutput / 1_000_000) * usage.output;
        usage.cost.cacheRead = (rateCacheRead / 1_000_000) * usage.cacheRead;
        usage.cost.cacheWrite = (rateCacheWrite * shortWrite + rateInput * 2 * longWrite) / 1_000_000;
        usage.cost.total = usage.cost.input + usage.cost.output + usage.cost.cacheRead + usage.cost.cacheWrite;
        return usage.cost;
    }

    public static List<ThinkingLevel> getSupportedThinkingLevels(Model model) {
        if (!model.reasoning) {
            return List.of(ThinkingLevel.OFF);
        }
        List<ThinkingLevel> supported = new ArrayList<>();
        for (ThinkingLevel level : Models.EXTENDED_THINKING_LEVELS) {
            boolean hasKey = model.thinkingLevelMap != null && model.thinkingLevelMap.containsKey(level);
            String mapped = hasKey ? model.thinkingLevelMap.get(level) : null;
            if (hasKey && mapped == null) {
                continue; // explicit null marks the level unsupported
            }
            if (level == ThinkingLevel.XHIGH || level == ThinkingLevel.MAX) {
                if (!hasKey) {
                    continue; // xhigh/max require an explicit mapping
                }
            }
            supported.add(level);
        }
        return supported;
    }

    public static ThinkingLevel clampThinkingLevel(Model model, ThinkingLevel level) {
        List<ThinkingLevel> available = getSupportedThinkingLevels(model);
        if (available.contains(level)) {
            return level;
        }
        int requestedIndex = level.ordinal();
        for (int i = requestedIndex; i < Models.EXTENDED_THINKING_LEVELS.length; i++) {
            if (available.contains(Models.EXTENDED_THINKING_LEVELS[i])) {
                return Models.EXTENDED_THINKING_LEVELS[i];
            }
        }
        for (int i = requestedIndex - 1; i >= 0; i--) {
            if (available.contains(Models.EXTENDED_THINKING_LEVELS[i])) {
                return Models.EXTENDED_THINKING_LEVELS[i];
            }
        }
        return available.isEmpty() ? ThinkingLevel.OFF : available.getFirst();
    }

    /**
     * Resolves a requested thinking level to the provider's wire value. Null
     * disables reasoning after model-specific clamping.
     */
    public static String providerThinkingLevel(Model model, ThinkingLevel level) {
        if (level == null || level == ThinkingLevel.OFF) {
            return null;
        }
        ThinkingLevel clamped = clampThinkingLevel(model, level);
        if (clamped == ThinkingLevel.OFF) {
            return null;
        }
        if (model.thinkingLevelMap != null && model.thinkingLevelMap.containsKey(clamped)) {
            return model.thinkingLevelMap.get(clamped);
        }
        return clamped.wire;
    }

    // -------------------------------------------------------- model catalog

    /**
     * Loads the bundled provider model snapshots.
     */
    public void loadBundledModelCatalog() {
        Map<String, Model> models = new LinkedHashMap<>();
        Map<String, List<Model>> providers = new LinkedHashMap<>();
        for (String resourceName : MODEL_CATALOG_RESOURCE_NAMES) {
            try (InputStream input = CodingAgentOperations.class.getResourceAsStream(MODEL_CATALOG_RESOURCE_ROOT + resourceName)) {
                if (input == null) {
                    throw new IllegalStateException("Missing bundled model catalog resource: " + resourceName);
                }
                JsonNode root = Json.MAPPER.readTree(input);
                for (Iterator<JsonNode> groups = root.elements(); groups.hasNext(); ) {
                    JsonNode group = groups.next();
                    for (Iterator<JsonNode> entries = group.elements(); entries.hasNext(); ) {
                        JsonNode node = entries.next();
                        Model model1 = new Model();
                        model1.id = requiredCatalogText(node, "id");
                        model1.name = requiredCatalogText(node, "name");
                        model1.api = requiredCatalogText(node, "api");
                        model1.provider = requiredCatalogText(node, "provider");
                        model1.baseUrl = requiredCatalogText(node, "baseUrl");
                        model1.reasoning = node.path("reasoning").asBoolean();
                        JsonNode node1 = node.path("input");
                        List<String> values = new ArrayList<>();
                        for (JsonNode value : node1) {
                            values.add(value.asText());
                        }
                        model1.input = new ArrayList<>(values);
                        JsonNode node2 = node.path("cost");
                        List<ModelCost.Tier> tiers = new ArrayList<>();
                        JsonNode tierNodes = node2.path("tiers");
                        if (tierNodes.isArray()) {
                            for (JsonNode tier : tierNodes) {
                                tiers.add(new ModelCost.Tier(
                                        tier.path("inputTokensAbove").asLong(),
                                        tier.path("input").asDouble(),
                                        tier.path("output").asDouble(),
                                        tier.path("cacheRead").asDouble(),
                                        tier.path("cacheWrite").asDouble()));
                            }
                        }
                        model1.cost = new ModelCost(
                                node2.path("input").asDouble(),
                                node2.path("output").asDouble(),
                                node2.path("cacheRead").asDouble(),
                                node2.path("cacheWrite").asDouble(),
                                List.copyOf(tiers));
                        model1.contextWindow = node.path("contextWindow").asLong();
                        model1.maxTokens = node.path("maxTokens").asLong();

                        JsonNode thinkingLevelMap = node.get("thinkingLevelMap");
                        if (thinkingLevelMap != null && thinkingLevelMap.isObject()) {
                            Map<ThinkingLevel, String> levels = new EnumMap<>(ThinkingLevel.class);
                            for (Map.Entry<String, JsonNode> field : thinkingLevelMap.properties()) {
                                levels.put(
                                        thinkingLevelFromWire(field.getKey()),
                                        field.getValue().isNull() ? null : field.getValue().asText());
                            }
                            model1.thinkingLevelMap = levels;
                        }

                        JsonNode headers = node.get("headers");
                        if (headers != null && headers.isObject()) {
                            Map<String, String> parsedHeaders = new LinkedHashMap<>();
                            for (Map.Entry<String, JsonNode> field : headers.properties()) {
                                parsedHeaders.put(field.getKey(), field.getValue().asText());
                            }
                            model1.headers = parsedHeaders;
                        }

                        Compat compat = null;
                        JsonNode compat1 = node.get("compat");
                        if (compat1 != null && compat1.isObject()) {
                            if (node.path("api").asText().equals("anthropic-messages")) {
                                JsonNode adaptiveThinking = compat1.get("forceAdaptiveThinking");
                                if (adaptiveThinking != null && adaptiveThinking.isBoolean()) {
                                    compat = new Compat.AnthropicMessages(null, null, null, null, adaptiveThinking.booleanValue());
                                }
                            }
                        }
                        if (compat != null) {
                            model1.compat = compat;
                        }
                        String modelKey = modelCatalogKey(model1.provider, model1.id);
                        if (models.putIfAbsent(modelKey, model1) != null) {
                            throw new IllegalStateException("Duplicate bundled model: " + modelKey);
                        }
                        providers.computeIfAbsent(model1.provider, ignored -> new ArrayList<>()).add(model1);
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("Unable to load bundled model catalog: " + resourceName, e);
            }
        }
        Map<String, List<Model>> immutableByProvider = new LinkedHashMap<>();
        for (Map.Entry<String, List<Model>> entry : providers.entrySet()) {
            immutableByProvider.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        byProviderAndId = Map.copyOf(models);
        byProvider = Map.copyOf(immutableByProvider);
    }

    /**
     * Finds a model by its provider and id, returning null if it is absent. Use
     * {@link #requireCatalogModel} when absence is a user-facing error.
     */
    public Model findCatalogModel(String provider, String id) {
        return byProviderAndId.get(modelCatalogKey(provider, id));
    }

    /**
     * Finds a model or throws a clear error that includes the provider/id pair.
     */
    public Model requireCatalogModel(String provider, String id) {
        Model model = findCatalogModel(provider, id);
        if (model == null) {
            throw new IllegalArgumentException("Unknown model: " + provider + "/" + id);
        }
        return model;
    }

    /**
     * Returns every bundled model from a provider, preserving source-file order.
     */
    public List<Model> catalogModelsForProvider(String provider) {
        return byProvider.getOrDefault(provider, List.of());
    }

    /**
     * Returns every bundled model, preserving resource and source-file order.
     */
    public List<Model> allCatalogModels() {
        return List.copyOf(byProviderAndId.values());
    }

    private static String requiredCatalogText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalStateException("Bundled model is missing text field: " + field);
        }
        return value.asText();
    }

    private static String modelCatalogKey(String provider, String id) {
        return Objects.requireNonNull(provider) + "\u0000" + Objects.requireNonNull(id);
    }

    // -------------------------------------------------------- core providers

    public List<Provider> coreProviders() {
        return List.copyOf(coreProviders.values());
    }

    void coreProviders(Map<String, Provider> providers) {
        coreProviders = providers;
    }

    /**
     * Builds the models served through a ChatGPT subscription from the bundled
     * OpenAI Responses catalog.
     */
    public List<Model> chatGptSubscriptionModels() {
        return catalogModelsForProvider("openai").stream()
                .filter(model -> model.api.equals("openai-responses"))
                .filter(model -> CHATGPT_CODEX_MODEL_IDS.contains(model.id))
                .map(source -> {
                    Model model = copyModel(source);
                    model.provider = CHATGPT_PROVIDER_ID;
                    model.baseUrl = CHATGPT_CODEX_API_BASE_URL.toString();
                    model.cost = ModelCost.FREE;
                    return model;
                })
                .toList();
    }

    private ProviderState chatGptProvider(
            ProviderState state, List<Model> models, OpenAiResponsesProvider responses) {
        state.id = CHATGPT_PROVIDER_ID;
        state.models = List.copyOf(models);
        state.responses = responses;
        return state;
    }

    public ProviderState googleProvider(ProviderState state, List<Model> models) {
        state.id = "google";
        state.models = List.copyOf(models);
        return state;
    }

    Provider requireCoreProvider(String id) {
        Provider provider = coreProviders.get(id);
        if (provider == null) {
            throw new IllegalArgumentException("Unknown core provider: " + id);
        }
        return provider;
    }

    /** Returns the initialized model list for one core provider. */
    public List<Model> coreProviderModels(String id) {
        return providerModels(requireCoreProvider(id));
    }

    /**
     * Builds the core-provider registry with independent state for each provider
     * implementation owned by this runtime.
     */
    public void initializeCoreProviders() {
        requireOpen();
        loadBundledModelCatalog();
        defaultCredentialStore();
        Map<String, Provider> providers = new LinkedHashMap<>();
        providers.put(
                "anthropic",
                new AnthropicProvider(
                        "anthropic",
                        "Anthropic",
                        catalogModelsForProvider("anthropic").stream()
                                .filter(model -> model.api.equals("anthropic-messages"))
                                .toList(),
                        List.of(
                                EnvApiKeys.ANTHROPIC_AUTH_TOKEN_ENV,
                                EnvApiKeys.ANTHROPIC_OAUTH_TOKEN_ENV,
                                EnvApiKeys.ANTHROPIC_API_KEY_ENV),
                        false));
        providers.put(
                "openai",
                new OpenAiResponsesProvider(
                        "openai",
                        "OpenAI",
                        List.copyOf(catalogModelsForProvider("openai").stream()
                                .filter(model -> model.api.equals("openai-responses"))
                                .toList()),
                        List.of("OPENAI_API_KEY"),
                        this,
                        OpenAiResponsesProvider.RequestProfile.STANDARD));

        List<Model> chatGptModels = chatGptSubscriptionModels();
        chatGptAuth(providerState(ProviderState.Role.CHATGPT), this, URI.create("https://auth.openai.com"), CHATGPT_CLIENT_ID);
        providers.put(
                CHATGPT_PROVIDER_ID,
                chatGptProvider(
                        providerState(ProviderState.Role.CHATGPT),
                        chatGptModels,
                        new OpenAiResponsesProvider(
                                CHATGPT_PROVIDER_ID,
                                CHATGPT_PROVIDER_NAME,
                                List.copyOf(chatGptModels),
                                List.of(),
                                null,
                                OpenAiResponsesProvider.RequestProfile.CODEX)));

        providers.put(
                "google",
                googleProvider(
                        providerState(ProviderState.Role.GOOGLE),
                        catalogModelsForProvider("google").stream()
                                .filter(model -> model.api.equals(GOOGLE_API))
                                .toList()));

        gitHubCopilotAuth(
                providerState(ProviderState.Role.GITHUB_COPILOT),
                this,
                URI.create("https://github.com"),
                URI.create("https://api.github.com/copilot_internal/v2/token"),
                URI.create(GITHUB_COPILOT_DEFAULT_BASE_URL));
        providers.put(
                GITHUB_COPILOT_PROVIDER_ID,
                newGitHubCopilotProvider(
                        providerState(ProviderState.Role.GITHUB_COPILOT),
                        catalogModelsForProvider(GITHUB_COPILOT_PROVIDER_ID)));
        coreProviders(Map.copyOf(providers));
    }

    // -------------------------------------------------------- stream options

    private static boolean isAborted(StreamOptions options) {
        return options != null && options.signal != null && isAborted(options.signal);
    }

    /**
     * Shallow copy with independent header and metadata maps.
     */
    private static StreamOptions copyStreamOptions(StreamOptions source) {
        StreamOptions copy = new StreamOptions();
        copy.signal = source.signal;
        copy.apiKey = source.apiKey;
        copy.baseUrl = source.baseUrl;
        copy.temperature = source.temperature;
        copy.maxTokens = source.maxTokens;
        copy.headers = new LinkedHashMap<>(source.headers);
        copy.timeoutMs = source.timeoutMs;
        copy.maxRetries = source.maxRetries;
        copy.maxRetryDelayMs = source.maxRetryDelayMs;
        copy.cacheRetention = source.cacheRetention;
        copy.sessionId = source.sessionId;
        copy.reasoning = source.reasoning;
        copy.metadata = new LinkedHashMap<>(source.metadata);
        return copy;
    }

    // ----------------------------------------------------------------- retry

    /**
     * Classifies whether a failed assistant message looks like a transient
     * provider/transport error.
     */
    public static boolean isRetryableAssistantError(AssistantMessage message) {
        if (message.stopReason != StopReason.ERROR || message.errorMessage == null) {
            return false;
        }
        if (Retry.NON_RETRYABLE_PROVIDER_LIMIT_ERROR_PATTERN.matcher(message.errorMessage).find()) {
            return false;
        }
        return Retry.RETRYABLE_PROVIDER_ERROR_PATTERN.matcher(message.errorMessage).find();
    }

    /**
     * Run a single assistant-producing call with bounded retry on transient
     * errors. Aborts are terminal and never retried; aborts during backoff are
     * normalized to an aborted AssistantMessage.
     */
    public AssistantMessage retryAssistantCall(
            Callable<AssistantMessage> produce, Retry.Policy policy, AbortSignal signal, Retry.Callbacks callbacks)
            throws InterruptedException {
        int maxAttempts = policy != null && policy.enabled ? policy.maxRetries : 0;

        int attempt = 0;
        Integer lastRetryAttempt = null;
        while (true) {
            AssistantMessage response;
            try {
                response = produce.call();
            } catch (InterruptedException | RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }

            if (response.stopReason == StopReason.ABORTED) {
                if (lastRetryAttempt != null) {
                    notifyRetryFinished(callbacks, false, lastRetryAttempt, null);
                }
                return response;
            }
            if (response.stopReason != StopReason.ERROR) {
                if (lastRetryAttempt != null) {
                    notifyRetryFinished(callbacks, true, lastRetryAttempt, null);
                }
                return response;
            }
            if (attempt >= maxAttempts || !isRetryableAssistantError(response)) {
                if (lastRetryAttempt != null) {
                    notifyRetryFinished(callbacks, false, lastRetryAttempt, response.errorMessage);
                }
                return response;
            }

            attempt++;
            lastRetryAttempt = attempt;
            String errorMessage = response.errorMessage != null ? response.errorMessage : "Unknown error";
            long delayMs = policy.baseDelayMs * (1L << (attempt - 1));
            if (callbacks != null && callbacks.onRetryScheduled != null) {
                callbacks.onRetryScheduled.accept(new Retry.Scheduled(attempt, maxAttempts, delayMs, errorMessage));
            }

            if (signal == null) {
                Thread.sleep(delayMs);
            } else if (!isAborted(signal)) {
                Object monitor = new Object();
                onAbort(signal, () -> {
                    synchronized (monitor) {
                        monitor.notifyAll();
                    }
                });
                long deadline = System.currentTimeMillis() + delayMs;
                synchronized (monitor) {
                    while (!isAborted(signal)) {
                        long remaining = deadline - System.currentTimeMillis();
                        if (remaining <= 0) break;
                        monitor.wait(remaining);
                    }
                }
            }
            if (signal != null && isAborted(signal)) {
                notifyRetryFinished(callbacks, false, attempt, errorMessage);
                response.stopReason = StopReason.ABORTED;
                response.errorMessage = null;
                return response;
            }
            if (callbacks != null && callbacks.onRetryAttemptStart != null) {
                callbacks.onRetryAttemptStart.run();
            }
        }
    }

    private void notifyRetryFinished(
            Retry.Callbacks callbacks, boolean success, int attempt, String finalError) {
        if (callbacks != null && callbacks.onRetryFinished != null) {
            callbacks.onRetryFinished.accept(new Retry.Finished(success, attempt, finalError));
        }
    }

    // ------------------------------------------------------ shared provider

    /**
     * Error text for an assistant message. Appends cause messages that the
     * outer message hides: the JDK HTTP client, for example, reports every
     * mid-body transport failure as "closed" with the real reason attached as
     * the cause, which retry classification needs to see.
     */
    static String displayError(Throwable error) {
        StringBuilder message = new StringBuilder(errorText(error));
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.add(error);
        for (Throwable cause = error.getCause(); cause != null && seen.add(cause); cause = cause.getCause()) {
            String detail = errorText(cause);
            if (!detail.isBlank() && message.indexOf(detail) < 0) {
                message.append(": ").append(detail);
            }
        }
        return message.toString();
    }

    private static String errorText(Throwable error) {
        return error.getMessage() == null ? error.toString() : error.getMessage();
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    @FunctionalInterface
    private interface ProviderStreamOperation {
        void run(AssistantMessageEventStream stream, AssistantMessage output, StreamOptions options) throws Exception;
    }

    @FunctionalInterface
    private interface SseOperation {
        void run(SseReader reader) throws Exception;
    }

    private static AssistantMessageEventStream providerStream(
            Model model, StreamOptions options, ProviderStreamOperation operation) {
        AssistantMessageEventStream stream = new AssistantMessageEventStream();
        StreamOptions requestOptions = options != null ? options : new StreamOptions();
        Thread.startVirtualThread(() -> {
            AssistantMessage output = new AssistantMessage(model.api, model.provider, model.id);
            try {
                operation.run(stream, output, requestOptions);
            } catch (Exception error) {
                output.stopReason = isAborted(requestOptions) ? StopReason.ABORTED : StopReason.ERROR;
                output.errorMessage = isAborted(requestOptions) ? "Request was aborted" : displayError(error);
                push(stream, new AssistantMessageEvent.Error(output.stopReason, output));
            }
        });
        return stream;
    }

    private void postJsonSse(
            String url,
            Map<String, String> headers,
            JsonNode request,
            StreamOptions options,
            SseOperation operation)
            throws Exception {
        HttpTransport.Response response = httpPostJson(
                url, headers, Json.MAPPER.writeValueAsBytes(request), options.timeoutMs, options.signal);
        try {
            SseReader reader = sseReader(response.body);
            try {
                operation.run(reader);
            } finally {
                closeSseReader(reader);
            }
        } finally {
            closeHttpResponse(response);
        }
    }

    private void abortStream(AssistantMessageEventStream stream, AssistantMessage output) {
        output.stopReason = StopReason.ABORTED;
        output.errorMessage = "Request was aborted";
        push(stream, new AssistantMessageEvent.Error(StopReason.ABORTED, output));
    }

    private void ensureContentIndex(AssistantMessage output, int index, AssistantContent value) {
        while (output.content.size() <= index) {
            output.content.add(new TextContent("", null));
        }
        output.content.set(index, value);
    }

    // ------------------------------------------------------------- anthropic

    private AssistantMessageEventStream anthropicStream(
            AnthropicProvider provider, Model model, Context context, StreamOptions options) {
        if (!model.api.equals(AnthropicProvider.API)) {
            throw new IllegalArgumentException("Model " + model + " is not an Anthropic Messages model");
        }
        return providerStream(model, options, (stream, output, requestOptions) -> {
            Map<String, String> headers = new LinkedHashMap<>(model.headers);
            headers.putAll(requestOptions.headers);
            headers.putIfAbsent("anthropic-version", AnthropicProvider.API_VERSION);
            if (!headers.containsKey("authorization")
                    && !headers.containsKey("Authorization")
                    && !headers.containsKey("x-api-key")) {
                String key = requestOptions.apiKey;
                if (!provider.bearerAuthentication) {
                    String bearer = System.getenv(EnvApiKeys.ANTHROPIC_AUTH_TOKEN_ENV);
                    if (bearer != null && !bearer.isBlank()) {
                        headers.put("authorization", "Bearer " + bearer);
                        key = bearer;
                    }
                }
                if (!headers.containsKey("authorization")) {
                    if ((key == null || key.isBlank()) && !provider.bearerAuthentication) {
                        key = resolveSystemApiKey("anthropic").orElse(null);
                    }
                    if (key == null || key.isBlank()) {
                        throw new IllegalStateException("No API key for provider: " + provider.id);
                    }
                    headers.put(
                            provider.bearerAuthentication ? "Authorization" : "x-api-key",
                            provider.bearerAuthentication ? "Bearer " + key : key);
                }
            }
            ObjectNode request1 = jsonObject();
            request1.put("model", model.id);
            request1.put("stream", true);
            request1.put("max_tokens", requestOptions.maxTokens != null ? requestOptions.maxTokens : model.maxTokens);
            if (context.systemPrompt != null && !context.systemPrompt.isBlank()) {
                request1.put("system", context.systemPrompt);
            }
            if (requestOptions.temperature != null) {
                request1.put("temperature", requestOptions.temperature);
            }
            if (requestOptions.reasoning != null && requestOptions.reasoning != ThinkingLevel.OFF) {
                ObjectNode thinking1 = request1.putObject("thinking");
                if (model.compat instanceof Compat.AnthropicMessages compat
                        && Boolean.TRUE.equals(compat.forceAdaptiveThinking)) {
                    thinking1.put("type", "adaptive");
                    thinking1.put("display", "summarized");
                    ThinkingLevel level = clampThinkingLevel(model, requestOptions.reasoning);
                    String effort = model.thinkingLevelMap == null ? null : model.thinkingLevelMap.get(level);
                    if (effort == null) {
                        effort = switch (level) {
                            case MINIMAL, LOW -> "low";
                            case MEDIUM -> "medium";
                            case HIGH, XHIGH, MAX -> "high";
                            case OFF -> throw new IllegalArgumentException(
                                    "Adaptive thinking requires a non-off thinking level");
                        };
                    }
                    request1.putObject("output_config").put("effort", effort);
                } else {
                    thinking1.put("type", "enabled");
                    thinking1.put("budget_tokens", Math.min(model.maxTokens, 16_000));
                }
            }
            ArrayNode messages = request1.putArray("messages");
            for (Message message1 : context.messages) {
                switch (message1) {
                    case UserMessage user -> {
                        ObjectNode target = messages.addObject().put("role", "user");
                        boolean onlyText = user.content.stream().allMatch(TextContent.class::isInstance);
                        if (onlyText) {
                            target.put(
                                    "content",
                                    user.content.stream()
                                            .map(TextContent.class::cast)
                                            .map(block -> block.text)
                                            .reduce("", String::concat));
                        } else {
                            anthropicAppendContent(target.putArray("content"), user.content);
                        }
                    }
                    case AssistantMessage assistant -> {
                        ObjectNode target = messages.addObject().put("role", "assistant");
                        ArrayNode content = target.putArray("content");
                        for (AssistantContent block : assistant.content) {
                            if (block instanceof TextContent text1) {
                                content.addObject().put("type", "text").put("text", text1.text);
                            } else if (block instanceof ThinkingContent thinking1) {
                                String signature1 = thinking1.thinkingSignature;
                                boolean hasSignature = signature1 != null && !signature1.isBlank();
                                if (!hasSignature) {
// Anthropic rejects a thinking block without a non-empty opaque signature.
// Preserve visible reasoning as ordinary context instead of replaying an
// invalid empty signature from an interrupted or incomplete stream.
                                    if (!thinking1.thinking.isBlank()) {
                                        content.addObject().put("type", "text").put("text", thinking1.thinking);
                                    }
                                } else {
                                    content.addObject()
                                            .put("type", "thinking")
                                            .put("thinking", thinking1.thinking)
                                            .put("signature", signature1);
                                }
                            } else if (block instanceof ToolCall call) {
                                content.addObject()
                                        .put("type", "tool_use")
                                        .put("id", call.id)
                                        .put("name", call.name)
                                        .set("input", call.arguments);
                            }
                        }
                    }
                    case ToolResultMessage result -> {
                        ObjectNode target = messages.addObject().put("role", "user");
                        ArrayNode content = target.putArray("content");
                        ObjectNode toolResult =
                                content.addObject().put("type", "tool_result").put("tool_use_id", result.toolCallId);
                        toolResult.put("is_error", result.isError);
                        anthropicAppendContent(toolResult.putArray("content"), result.content);
                    }
                }
            }
            if (!context.tools.isEmpty()) {
                ArrayNode tools1 = request1.putArray("tools");
                for (Tool tool1 : context.tools) {
                    ObjectNode target = tools1.addObject();
                    target.put("name", tool1.name);
                    target.put("description", tool1.description);
                    target.set("input_schema", anthropicInputSchema(tool1.parameters));
                }
            }
            postJsonSse(
                    providerBaseUrl(model, requestOptions) + "/v1/messages",
                    headers,
                    request1,
                    requestOptions,
                    reader -> {
                        push(stream, new AssistantMessageEvent.Start(output));
                        Map<Integer, AnthropicProvider.ToolCallAccumulator> tools = new LinkedHashMap<>();
                        SseReader.SseEvent sse;
                        while ((sse = nextSseEvent(reader)) != null) {
                            if (isAborted(requestOptions)) {
                                abortStream(stream, output);
                                return;
                            }
                            JsonNode event = Json.MAPPER.readTree(sse.data);
                            if (event == null) {
                                continue;
                            }
                            switch (sse.event) {
                                case "message_start" -> {
                                    JsonNode message = event.path("message");
                                    output.responseId = message.path("id").asText(null);
                                    output.responseModel = message.path("model").asText(null);
                                    anthropicReadUsage(message.path("usage"), output);
                                }
                                case "content_block_start" -> {
                                    int index = event.path("index").asInt();
                                    JsonNode block = event.path("content_block");
                                    switch (block.path("type").asText()) {
                                        case "text" -> {
                                            ensureContentIndex(output, index, new TextContent("", null));
                                            push(stream, new AssistantMessageEvent.TextStart(index, output));
                                        }
                                        case "thinking" -> {
                                            JsonNode value = block.get("signature");
                                            ensureContentIndex(output, index, new ThinkingContent(
                                                    "", value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : null, false));
                                            push(stream, new AssistantMessageEvent.ThinkingStart(index, output));
                                        }
                                        case "tool_use" -> {
                                            AnthropicProvider.ToolCallAccumulator tool = new AnthropicProvider.ToolCallAccumulator(index);
                                            tool.id = block.path("id").asText();
                                            tool.name = block.path("name").asText();
                                            tools.put(index, tool);
                                            ensureContentIndex(output, index, new ToolCall(tool.id, tool.name, jsonObject(), null));
                                            push(stream, new AssistantMessageEvent.ToolCallStart(index, output));
                                        }
                                        default -> {
                                            // Anthropic may introduce non-user-visible block types.
                                        }
                                    }
                                }
                                case "content_block_delta" -> {
                                    int index = event.path("index").asInt();
                                    JsonNode delta = event.path("delta");
                                    switch (delta.path("type").asText()) {
                                        case "text_delta" -> {
                                            TextContent current = (TextContent) output.content.get(index);
                                            String text = delta.path("text").asText();
                                            output.content.set(index, new TextContent(current.text + text, current.textSignature));
                                            push(stream, new AssistantMessageEvent.TextDelta(index, text, output));
                                        }
                                        case "thinking_delta" -> {
                                            ThinkingContent current = (ThinkingContent) output.content.get(index);
                                            String thinking = delta.path("thinking").asText();
                                            output.content.set(index, new ThinkingContent(
                                                    current.thinking + thinking, current.thinkingSignature, current.redacted));
                                            push(stream, new AssistantMessageEvent.ThinkingDelta(index, thinking, output));
                                        }
                                        case "signature_delta" -> {
                                            ThinkingContent current = (ThinkingContent) output.content.get(index);
                                            String signature = delta.path("signature").asText();
                                            if (!signature.isEmpty()) {
                                                String previous = current.thinkingSignature;
                                                String signature1 = (previous == null ? "" : previous) + signature;
                                                output.content.set(index, new ThinkingContent(current.thinking, signature1, current.redacted));
                                            }
                                        }
                                        case "input_json_delta" -> {
                                            AnthropicProvider.ToolCallAccumulator tool = tools.get(index);
                                            if (tool == null) {
                                                throw new IllegalStateException("Anthropic tool input delta without tool block at " + index);
                                            }
                                            String json = delta.path("partial_json").asText();
                                            tool.arguments.append(json);
                                            push(stream, new AssistantMessageEvent.ToolCallDelta(index, json, output));
                                        }
                                        default -> {
// Unknown future delta types have no private event.
                                        }
                                    }
                                }
                                case "content_block_stop" -> {
                                    int index = event.path("index").asInt();
                                    AssistantContent content = output.content.get(index);
                                    if (content instanceof TextContent text) {
                                        push(stream, new AssistantMessageEvent.TextEnd(index, text.text, output));
                                    } else if (content instanceof ThinkingContent thinking) {
                                        push(stream, new AssistantMessageEvent.ThinkingEnd(index, thinking.thinking, output));
                                    } else if (content instanceof ToolCall) {
                                        anthropicFinishTool(stream, output, index, tools);
                                    }
                                }
                                case "message_delta" -> {
                                    JsonNode delta = event.path("delta");
                                    String reason = delta.path("stop_reason").asText("");
                                    output.rawStopReason = reason.isEmpty() ? output.rawStopReason : reason;
                                    if (!reason.isEmpty()) {
                                        output.stopReason = switch (reason) {
                                            case "max_tokens" -> StopReason.LENGTH;
                                            case "tool_use" -> StopReason.TOOL_USE;
                                            default -> StopReason.STOP;
                                        };
                                    }
                                    anthropicReadUsage(event.path("usage"), output);
                                }
                                case "message_stop" -> {
                                    for (int index : List.copyOf(tools.keySet())) {
                                        if (output.content.get(index) instanceof ToolCall) {
                                            anthropicFinishTool(stream, output, index, tools);
                                        }
                                    }
                                    if (output.stopReason == StopReason.PENDING) {
                                        output.stopReason = toolCalls(output).isEmpty() ? StopReason.STOP : StopReason.TOOL_USE;
                                    }
                                    calculateCost(model, output.usage);
                                    push(stream, new AssistantMessageEvent.Done(output.stopReason, output));
                                    return;
                                }
                                case "error" ->
                                        throw new IOException(event.path("error").path("message").asText("Anthropic stream error"));
                                default -> {
                                    // ping and unknown future events do not affect the private stream.
                                }
                            }
                        }
                        throw new IOException("Anthropic stream ended before message_stop");
                    });
        });
    }

    private static String providerBaseUrl(Model model, StreamOptions options) {
        if (options.baseUrl != null && !options.baseUrl.isBlank()) {
            return options.baseUrl;
        }
        String configuredBaseUrl = model.provider.equals("anthropic")
                ? configuredAnthropicBaseUrl(System.getenv())
                : null;
        return configuredBaseUrl == null ? model.baseUrl : configuredBaseUrl;
    }

    /**
     * Anthropic rejects input schemas with a top-level oneOf, allOf, or anyOf;
     * drop them and rely on the tool's own argument validation.
     */
    public static ObjectNode anthropicInputSchema(ObjectNode schema) {
        if (schema == null || !(schema.has("oneOf") || schema.has("allOf") || schema.has("anyOf"))) {
            return schema;
        }
        ObjectNode copy = schema.deepCopy();
        copy.remove(List.of("oneOf", "allOf", "anyOf"));
        return copy;
    }

    private void anthropicAppendContent(ArrayNode target, List<? extends UserContent> content) {
        for (UserContent block : content) {
            if (block instanceof TextContent text) {
                target.addObject().put("type", "text").put("text", text.text);
            } else if (block instanceof ImageContent image) {
                ObjectNode source = target.addObject().put("type", "image").putObject("source");
                source.put("type", "base64");
                source.put("media_type", image.mimeType);
                source.put("data", image.data);
            }
        }
    }

    private void anthropicFinishTool(
            AssistantMessageEventStream stream,
            AssistantMessage output,
            int index,
            Map<Integer, AnthropicProvider.ToolCallAccumulator> tools)
            throws IOException {
        AnthropicProvider.ToolCallAccumulator tool = tools.remove(index);
        if (tool == null) {
            return;
        }
        String rawArguments = tool.arguments.toString();
        JsonNode parsed;
        if (rawArguments.isBlank()) {
            // Anthropic emits no input_json_delta events at all for parameterless
            // tool calls, leaving the accumulator empty instead of holding "{}".
            parsed = jsonObject();
        } else {
            parsed = Json.MAPPER.readTree(rawArguments);
        }
        if (!(parsed instanceof ObjectNode arguments)) {
            throw new IOException("Anthropic tool input must be a JSON object");
        }
        ToolCall completed = new ToolCall(tool.id, tool.name, arguments, null);
        output.content.set(index, completed);
        push(stream, new AssistantMessageEvent.ToolCallEnd(index, completed, output));
    }

    private void anthropicReadUsage(JsonNode usage, AssistantMessage output) {
        if (!usage.isObject()) {
            return;
        }
        if (usage.path("input_tokens").isIntegralNumber()) {
            output.usage.input = usage.path("input_tokens").asLong();
        }
        if (usage.path("output_tokens").isIntegralNumber()) {
            output.usage.output = usage.path("output_tokens").asLong();
        }
        if (usage.path("cache_read_input_tokens").isIntegralNumber()) {
            output.usage.cacheRead = usage.path("cache_read_input_tokens").asLong();
        }
        if (usage.path("cache_creation_input_tokens").isIntegralNumber()) {
            output.usage.cacheWrite = usage.path("cache_creation_input_tokens").asLong();
        }
        if (usage.path("cache_creation").path("ephemeral_1h_input_tokens").isIntegralNumber()) {
            output.usage.cacheWrite1h =
                    usage.path("cache_creation").path("ephemeral_1h_input_tokens").asLong();
        }
        output.usage.totalTokens =
                output.usage.input + output.usage.output + output.usage.cacheRead + output.usage.cacheWrite;
    }

    // ------------------------------------------------- openai chat completions

    /**
     * Configures the identity and models of a Chat Completions-compatible service.
     */
    public ProviderState openAiCompatibleProvider(
            ProviderState state, String id, List<Model> models) {
        state.id = requireNonBlank(id, "id");
        state.models = List.copyOf(models);
        return state;
    }

    private AssistantMessageEventStream openAiCompatibleStream(
            ProviderState state, Model model, Context context, StreamOptions options) {
        if (!model.api.equals(OPENAI_COMPATIBLE_API)) {
            throw new IllegalArgumentException("Model " + model + " is not a Chat Completions model");
        }
        return providerStream(model, options, (stream, output, requestOptions) -> {
            Map<String, String> headers = new LinkedHashMap<>(model.headers);
            headers.putAll(requestOptions.headers);
            String key = requestOptions.apiKey;
            if (key == null || key.isBlank()) {
                key = resolveSystemApiKey(state.id).orElse(null);
            }
            if (!headers.containsKey("Authorization") && !headers.containsKey("authorization")) {
                if (key == null || key.isBlank()) {
                    throw new IllegalStateException("No API key for provider: " + state.id);
                }
                headers.put("Authorization", "Bearer " + key);
            }
            ObjectNode request1 = jsonObject();
            request1.put("model", model.id);
            request1.put("stream", true);
            request1.putObject("stream_options").put("include_usage", true);
            if (requestOptions.temperature != null) {
                request1.put("temperature", requestOptions.temperature);
            }
            if (requestOptions.maxTokens != null) {
                request1.put("max_completion_tokens", requestOptions.maxTokens);
            }
            String reasoning = providerThinkingLevel(model, requestOptions.reasoning);
            if (reasoning != null) {
                request1.put("reasoning_effort", reasoning);
            }
            ArrayNode messages = request1.putArray("messages");
            if (context.systemPrompt != null && !context.systemPrompt.isBlank()) {
                messages.addObject().put("role", "system").put("content", context.systemPrompt);
            }
            for (Message message : context.messages) {
                switch (message) {
                    case UserMessage user -> {
                        ObjectNode target = messages.addObject().put("role", "user");
                        boolean hasImage = user.content.stream().anyMatch(ImageContent.class::isInstance);
                        if (!hasImage) {
                            target.put(
                                    "content",
                                    user.content.stream()
                                            .filter(TextContent.class::isInstance)
                                            .map(TextContent.class::cast)
                                            .map(block -> block.text)
                                            .reduce("", String::concat));
                        } else {
                            ArrayNode parts = target.putArray("content");
                            for (UserContent block : user.content) {
                                if (block instanceof TextContent text1) {
                                    parts.addObject().put("type", "text").put("text", text1.text);
                                } else if (block instanceof ImageContent image) {
                                    parts.addObject()
                                            .put("type", "image_url")
                                            .putObject("image_url")
                                            .put("url", "data:" + image.mimeType + ";base64," + image.data);
                                }
                            }
                        }
                    }
                    case AssistantMessage assistant -> {
                        ObjectNode target = messages.addObject().put("role", "assistant");
                        String text1 = text(assistant);
                        target.put("content", text1.isEmpty() ? "" : text1);
                        ArrayNode toolCalls = null;
                        for (AssistantContent block : assistant.content) {
                            if (block instanceof ToolCall call1) {
                                if (toolCalls == null) {
                                    toolCalls = target.putArray("tool_calls");
                                }
                                ObjectNode function1 = toolCalls
                                        .addObject()
                                        .put("id", call1.id)
                                        .put("type", "function")
                                        .putObject("function");
                                function1.put("name", call1.name);
                                function1.put("arguments", call1.arguments.toString());
                            }
                        }
                    }
                    case ToolResultMessage toolResult -> messages.addObject()
                            .put("role", "tool")
                            .put("tool_call_id", toolResult.toolCallId)
                            .put("content", text(toolResult));
                }
            }
            if (!context.tools.isEmpty()) {
                ArrayNode tools1 = request1.putArray("tools");
                for (Tool tool : context.tools) {
                    ObjectNode function1 = tools1.addObject().put("type", "function").putObject("function");
                    function1.put("name", tool.name);
                    function1.put("description", tool.description);
                    function1.set("parameters", tool.parameters);
                }
            }
            postJsonSse(
                    (requestOptions.baseUrl == null || requestOptions.baseUrl.isBlank()
                            ? model.baseUrl
                            : trimTrailingSlash(requestOptions.baseUrl)) + "/chat/completions",
                    headers,
                    request1,
                    requestOptions,
                    reader -> {
                        push(stream, new AssistantMessageEvent.Start(output));
                        Map<Integer, OpenAiToolCallAccumulator> tools = new LinkedHashMap<>();
                        SseReader.SseEvent event;
                        while ((event = nextSseEvent(reader)) != null) {
                            if (isAborted(requestOptions)) {
                                abortStream(stream, output);
                                return;
                            }
                            if (event.data.equals("[DONE]")) {
                                break;
                            }
                            JsonNode chunk = Json.MAPPER.readTree(event.data);
                            if (chunk == null) {
                                continue;
                            }
                            JsonNode usage = chunk.path("usage");
                            if (usage.isObject()) {
                                output.usage.input = usage.path("prompt_tokens").asLong(output.usage.input);
                                output.usage.output = usage.path("completion_tokens").asLong(output.usage.output);
                                output.usage.totalTokens = usage.path("total_tokens").asLong(output.usage.input + output.usage.output);
                                JsonNode details = usage.path("prompt_tokens_details");
                                if (details.path("cached_tokens").isIntegralNumber()) {
                                    output.usage.cacheRead = details.path("cached_tokens").asLong();
                                    output.usage.input -= output.usage.cacheRead;
                                }
                            }
                            JsonNode choices = chunk.path("choices");
                            if (!choices.isArray() || choices.isEmpty()) {
                                continue;
                            }
                            JsonNode choice = choices.get(0);
                            JsonNode delta = choice.path("delta");
                            if (!delta.isMissingNode()) {
                                if (delta.path("content").isTextual()) {
                                    String text = delta.path("content").asText();
                                    int index = lastContentIndex(output, TextContent.class);
                                    if (index == -1) {
                                        index = output.content.size();
                                        output.content.add(new TextContent("", null));
                                        push(stream, new AssistantMessageEvent.TextStart(index, output));
                                    }
                                    TextContent content = (TextContent) output.content.get(index);
                                    output.content.set(index, new TextContent(content.text + text, content.textSignature));
                                    push(stream, new AssistantMessageEvent.TextDelta(index, text, output));
                                }
                                JsonNode value = delta.has("reasoning_content") ? delta.path("reasoning_content") : delta.path("reasoning");
                                if (value.isTextual()) {
                                    String thinking = value.asText();
                                    int index = lastContentIndex(output, ThinkingContent.class);
                                    if (index == -1) {
                                        index = output.content.size();
                                        output.content.add(new ThinkingContent("", null, false));
                                        push(stream, new AssistantMessageEvent.ThinkingStart(index, output));
                                    }
                                    ThinkingContent content = (ThinkingContent) output.content.get(index);
                                    output.content.set(index, new ThinkingContent(
                                            content.thinking + thinking, content.thinkingSignature, content.redacted));
                                    push(stream, new AssistantMessageEvent.ThinkingDelta(index, thinking, output));
                                }
                                JsonNode deltas = delta.path("tool_calls");
                                if (deltas.isArray()) {
                                    for (JsonNode delta1 : deltas) {
                                        int wireIndex = delta1.path("index").asInt();
                                        OpenAiToolCallAccumulator accumulator =
                                                tools.computeIfAbsent(wireIndex, OpenAiToolCallAccumulator::new);
                                        accumulator.rawDeltas.add(delta1.deepCopy());

                                        JsonNode id = delta1.get("id");
                                        if (openAiFragmentHasValue(id)) {
                                            accumulator.hasMeaningfulData = true;
                                        }
                                        if (id != null && id.isTextual()) {
                                            accumulator.id = id.asText();
                                        }

                                        JsonNode function = delta1.get("function");
                                        JsonNode name = function != null && function.isObject() ? function.get("name") : null;
                                        JsonNode arguments = function != null && function.isObject() ? function.get("arguments") : null;
                                        if (function != null && !function.isNull() && !function.isObject()) {
                                            accumulator.hasMeaningfulData = true;
                                        }
                                        if (openAiFragmentHasValue(name) || openAiFragmentHasValue(arguments)) {
                                            accumulator.hasMeaningfulData = true;
                                        }
                                        if (name != null && name.isTextual()) {
                                            accumulator.name += name.asText();
                                        }
                                        String argumentFragment = null;
                                        if (arguments != null && arguments.isTextual()) {
                                            argumentFragment = arguments.asText();
                                            accumulator.arguments.append(argumentFragment);
                                        }

                                        if (accumulator.contentIndex == -1 && accumulator.hasMeaningfulData) {
                                            accumulator.contentIndex = output.content.size();
                                            output.content.add(new ToolCall(accumulator.id, accumulator.name, jsonObject(), null));
                                            push(stream, new AssistantMessageEvent.ToolCallStart(accumulator.contentIndex, output));
                                        }
                                        if (accumulator.contentIndex != -1) {
                                            output.content.set(
                                                    accumulator.contentIndex,
                                                    new ToolCall(accumulator.id, accumulator.name, jsonObject(), null));
                                            if (argumentFragment != null) {
                                                push(
                                                        stream,
                                                        new AssistantMessageEvent.ToolCallDelta(
                                                                accumulator.contentIndex, argumentFragment, output));
                                            }
                                        }
                                    }
                                }
                            }
                            if (!choice.path("finish_reason").isNull() && !choice.path("finish_reason").isMissingNode()) {
                                output.rawStopReason = choice.path("finish_reason").asText();
                                output.stopReason = switch (output.rawStopReason) {
                                    case "length" -> StopReason.LENGTH;
                                    case "tool_calls", "function_call" -> StopReason.TOOL_USE;
                                    default -> StopReason.STOP;
                                };
                            }
                        }
                        for (OpenAiToolCallAccumulator accumulator : tools.values()) {
                            if (accumulator.contentIndex == -1) {
                                // Some OpenAI-compatible streams emit a second, index-only/blank
                                // tool-call entry. It carries no call data and should not create a
                                // visible placeholder or invalidate otherwise usable calls.
                                continue;
                            }
                            String rawArguments = accumulator.arguments.toString();
                            JsonNode parsed;
                            if (rawArguments.isBlank()) {
                                // Some compatible models emit an empty argument string instead of
                                // the JSON object "{}" for parameterless tools.
                                parsed = jsonObject();
                            } else {
                                try {
                                    parsed = Json.MAPPER.readTree(rawArguments);
                                } catch (IOException error) {
                                    throw invalidOpenAiToolCall(accumulator, error);
                                }
                            }
                            if (!(parsed instanceof ObjectNode arguments)) {
                                throw invalidOpenAiToolCall(accumulator, null);
                            }
                            ToolCall call = new ToolCall(accumulator.id, accumulator.name, arguments, null);
                            output.content.set(accumulator.contentIndex, call);
                            push(stream, new AssistantMessageEvent.ToolCallEnd(accumulator.contentIndex, call, output));
                        }
                        if (output.stopReason == StopReason.PENDING) {
                            output.stopReason = toolCalls(output).isEmpty() ? StopReason.STOP : StopReason.TOOL_USE;
                        }
                        calculateCost(model, output.usage);
                        push(stream, new AssistantMessageEvent.Done(output.stopReason, output));
                    });
        });
    }

    private static boolean openAiFragmentHasValue(JsonNode fragment) {
        return fragment != null && !fragment.isNull() && (!fragment.isTextual() || !fragment.asText().isBlank());
    }

    private static IOException invalidOpenAiToolCall(
            OpenAiToolCallAccumulator accumulator, IOException cause) {
        ObjectNode diagnostic = jsonObject();
        diagnostic.put("index", accumulator.wireIndex);
        diagnostic.put("id", accumulator.id);
        diagnostic.put("name", accumulator.name);
        diagnostic.put("arguments", accumulator.arguments.toString());
        ArrayNode rawDeltas = diagnostic.putArray("rawDeltas");
        for (JsonNode delta : accumulator.rawDeltas) {
            rawDeltas.add(delta);
        }
        String message = "OpenAI tool call arguments must be a JSON object; raw tool call: " + diagnostic;
        return cause == null ? new IOException(message) : new IOException(message, cause);
    }

    private static int lastContentIndex(AssistantMessage output, Class<? extends AssistantContent> contentType) {
        for (int i = output.content.size() - 1; i >= 0; i--) {
            if (contentType.isInstance(output.content.get(i))) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------ openai responses

    private AssistantMessageEventStream openAiResponsesStream(
            OpenAiResponsesProvider provider, Model model, Context context, StreamOptions options) {
        if (!model.api.equals(OpenAiResponsesProvider.API)) {
            throw new IllegalArgumentException("Model " + model + " is not an OpenAI Responses model");
        }
        return providerStream(model, options, (stream, output, requestOptions) -> {
            boolean codex = provider.requestProfile == OpenAiResponsesProvider.RequestProfile.CODEX;
            ObjectNode request1 = jsonObject();
            request1.put("model", model.id);
            request1.put("stream", true);
            request1.put("store", false);
            if (context.systemPrompt != null && !context.systemPrompt.isBlank()) {
                request1.put("instructions", context.systemPrompt);
            } else if (codex) {
                request1.put("instructions", "You are a helpful assistant.");
            }
            if (codex) {
                request1.putObject("text").put("verbosity", "low");
                request1.put("tool_choice", "auto");
                request1.put("parallel_tool_calls", true);
                if (requestOptions.sessionId != null && !requestOptions.sessionId.isBlank()) {
                    request1.put("prompt_cache_key", requestOptions.sessionId);
                }
            }
            if (requestOptions.maxTokens != null && !codex) {
                request1.put("max_output_tokens", Math.max(16, requestOptions.maxTokens));
            }
            if (requestOptions.temperature != null) {
                request1.put("temperature", requestOptions.temperature);
            }
            String reasoning = providerThinkingLevel(model, requestOptions.reasoning);
            if (reasoning != null) {
                String summary = codex ? "detailed" : "auto";
                request1.putObject("reasoning").put("effort", reasoning).put("summary", summary);
                request1.putArray("include").add("reasoning.encrypted_content");
            }
            ArrayNode input = request1.putArray("input");
            for (Message message : context.messages) {
                switch (message) {
                    case UserMessage user -> {
                        ObjectNode target = input.addObject().put("role", "user");
                        ArrayNode content = target.putArray("content");
                        for (UserContent block : user.content) {
                            if (block instanceof TextContent text1) {
                                content.addObject().put("type", "input_text").put("text", text1.text);
                            } else if (block instanceof ImageContent image) {
                                content.addObject()
                                        .put("type", "input_image")
                                        .put("image_url", "data:" + image.mimeType + ";base64," + image.data);
                            }
                        }
                    }
                    case AssistantMessage assistant -> {
                        ObjectNode target = null;
                        ArrayNode content = null;
                        for (AssistantContent block : assistant.content) {
                            if (block instanceof ThinkingContent thinking1 && thinking1.thinkingSignature != null) {
                                try {
                                    JsonNode reasoningItem = Json.MAPPER.readTree(thinking1.thinkingSignature);
                                    if (reasoningItem != null
                                            && reasoningItem.isObject()
                                            && reasoningItem.path("type").asText().equals("reasoning")) {
                                        input.add(reasoningItem);
                                    }
                                } catch (IOException ignored) {
// Signatures from another provider are not OpenAI response items.
                                }
                            } else if (block instanceof TextContent text1) {
                                if (target == null) {
                                    target = input.addObject().put("role", "assistant");
                                    content = target.putArray("content");
                                }
                                content.addObject().put("type", "output_text").put("text", text1.text);
                            } else if (block instanceof ToolCall call) {
                                ObjectNode function = input.addObject().put("type", "function_call");
                                function.put("call_id", call.id);
                                function.put("name", call.name);
                                function.put("arguments", call.arguments.toString());
                            }
                        }
                    }
                    case ToolResultMessage result -> input.addObject()
                            .put("type", "function_call_output")
                            .put("call_id", result.toolCallId)
                            .put("output", text(result));
                }
            }
            if (!context.tools.isEmpty()) {
                ArrayNode tools = request1.putArray("tools");
                for (Tool tool : context.tools) {
                    ObjectNode target = tools.addObject().put("type", "function");
                    target.put("name", tool.name);
                    target.put("description", tool.description);
                    target.set("parameters", tool.parameters);
                }
            }
            Map<String, String> headers = new LinkedHashMap<>(model.headers);
            headers.putAll(requestOptions.headers);
            if (!headers.containsKey("Authorization") && !headers.containsKey("authorization")) {
                String key = requestOptions.apiKey;
                if ((key == null || key.isBlank()) && provider.credentials != null) {
                    key = readCredential(provider.credentials, provider.id)
                            .filter(Credential.ApiKeyCredential.class::isInstance)
                            .map(Credential.ApiKeyCredential.class::cast)
                            .map(credential -> credential.key)
                            .orElse(null);
                }
                if (key == null || key.isBlank()) {
                    key = resolveSystemApiKey(provider.id).orElse(null);
                }
                if (key == null || key.isBlank()) {
                    throw new IllegalStateException("No API key for provider: " + provider.id);
                }
                headers.put("Authorization", "Bearer " + key);
            }
            postJsonSse(
                    providerBaseUrl(model, requestOptions) + "/responses",
                    headers,
                    request1,
                    requestOptions,
                    reader -> {
                        push(stream, new AssistantMessageEvent.Start(output));
                        Map<String, OpenAiResponsesProvider.OutputItem> items = new HashMap<>();
                        SseReader.SseEvent sse;
                        while ((sse = nextSseEvent(reader)) != null) {
                            if (isAborted(requestOptions)) {
                                abortStream(stream, output);
                                return;
                            }
                            JsonNode event = Json.MAPPER.readTree(sse.data);
                            if (event == null) {
                                continue;
                            }
                            switch (event.path("type").asText()) {
                                case "response.created" ->
                                        output.responseId = event.path("response").path("id").asText(null);
                                case "response.output_item.added" -> {
                                    JsonNode item = event.path("item");
                                    String itemId = openAiResponsesItemKey(event);
                                    String type = item.path("type").asText();
                                    int contentIndex = output.content.size();
                                    OpenAiResponsesProvider.OutputItem outputItem =
                                            new OpenAiResponsesProvider.OutputItem(contentIndex, type);
                                    items.put(itemId, outputItem);
                                    switch (type) {
                                        case "message" -> {
                                            output.content.add(new TextContent("", null));
                                            push(stream, new AssistantMessageEvent.TextStart(contentIndex, output));
                                        }
                                        case "reasoning" -> {
                                            output.content.add(new ThinkingContent("", null, false));
                                            push(stream, new AssistantMessageEvent.ThinkingStart(contentIndex, output));
                                        }
                                        case "function_call" -> {
                                            outputItem.callId = item.path("call_id").asText();
                                            outputItem.name = item.path("name").asText();
                                            output.content.add(new ToolCall(
                                                    outputItem.callId, outputItem.name, jsonObject(), null));
                                            push(stream, new AssistantMessageEvent.ToolCallStart(contentIndex, output));
                                        }
                                        default -> items.remove(itemId);
                                    }
                                }
                                case "response.output_text.delta", "response.refusal.delta" -> {
                                    OpenAiResponsesProvider.OutputItem item = items.get(openAiResponsesItemKey(event));
                                    if (item != null && item.type.equals("message")) {
                                        String delta = event.path("delta").asText();
                                        TextContent current = (TextContent) output.content.get(item.contentIndex);
                                        output.content.set(item.contentIndex, new TextContent(
                                                current.text + delta, current.textSignature));
                                        push(stream, new AssistantMessageEvent.TextDelta(item.contentIndex, delta, output));
                                    }
                                }
                                case "response.reasoning_text.delta", "response.reasoning_summary_text.delta" ->
                                        openAiResponsesAppendThinkingDelta(
                                                stream, output, items.get(openAiResponsesItemKey(event)), event.path("delta").asText());
                                case "response.reasoning_summary_part.done" -> {
                                    OpenAiResponsesProvider.OutputItem item = items.get(openAiResponsesItemKey(event));
                                    if (item != null && item.type.equals("reasoning")) {
                                        ThinkingContent current = (ThinkingContent) output.content.get(item.contentIndex);
                                        if (!current.thinking.isEmpty() && !current.thinking.endsWith("\n\n")) {
                                            openAiResponsesAppendThinkingDelta(stream, output, item, "\n\n");
                                        }
                                    }
                                }
                                case "response.function_call_arguments.delta" -> {
                                    OpenAiResponsesProvider.OutputItem item = items.get(openAiResponsesItemKey(event));
                                    if (item != null && item.type.equals("function_call")) {
                                        String delta = event.path("delta").asText();
                                        item.arguments.append(delta);
                                        push(stream, new AssistantMessageEvent.ToolCallDelta(item.contentIndex, delta, output));
                                    }
                                }
                                case "response.function_call_arguments.done" ->
                                        openAiResponsesFinishTool(stream, output, event, items);
                                case "response.output_item.done" -> {
                                    String itemId = openAiResponsesItemKey(event);
                                    OpenAiResponsesProvider.OutputItem item = items.get(itemId);
                                    if (item != null) {
                                        switch (item.type) {
                                            case "message" -> {
                                                TextContent text = (TextContent) output.content.get(item.contentIndex);
                                                push(stream, new AssistantMessageEvent.TextEnd(item.contentIndex, text.text, output));
                                                items.remove(itemId);
                                            }
                                            case "reasoning" -> {
                                                ThinkingContent thinking = (ThinkingContent) output.content.get(item.contentIndex);
                                                JsonNode completedItem = event.path("item");
                                                String completedThinking = openAiResponsesReasoningText(completedItem);
                                                if (completedThinking.isBlank()) {
                                                    completedThinking = thinking.thinking.stripTrailing();
                                                }
                                                String signature = completedItem.isObject() ? completedItem.toString() : thinking.thinkingSignature;
                                                thinking = new ThinkingContent(completedThinking, signature, thinking.redacted);
                                                output.content.set(item.contentIndex, thinking);
                                                push(stream, new AssistantMessageEvent.ThinkingEnd(item.contentIndex, thinking.thinking, output));
                                                items.remove(itemId);
                                            }
                                            case "function_call" ->
                                                    openAiResponsesFinishTool(stream, output, event, items);
                                        }
                                    }
                                }
                                case "response.completed", "response.incomplete" -> {
                                    JsonNode response1 = event.path("response");
                                    output.responseId = response1.path("id").asText(output.responseId);
                                    output.responseModel = response1.path("model").asText(null);
                                    JsonNode usage = response1.path("usage");
                                    output.usage.input = usage.path("input_tokens").asLong();
                                    output.usage.output = usage.path("output_tokens").asLong();
                                    output.usage.totalTokens = usage.path("total_tokens").asLong(output.usage.input + output.usage.output);
                                    output.usage.cacheRead = usage.path("input_tokens_details").path("cached_tokens").asLong();
                                    JsonNode reasoningTokens = usage.path("output_tokens_details").path("reasoning_tokens");
                                    if (reasoningTokens.isIntegralNumber()) {
                                        output.usage.reasoning = reasoningTokens.asLong();
                                    }
                                    output.usage.input -= output.usage.cacheRead;
                                    output.stopReason = toolCalls(output).isEmpty()
                                            ? (response1.path("status").asText().equals("incomplete") ? StopReason.LENGTH : StopReason.STOP)
                                            : StopReason.TOOL_USE;
                                    calculateCost(model, output.usage);
                                    JsonNode responseItems = response1.path("output");
                                    if (responseItems.isArray()) {
                                        for (JsonNode responseItem : responseItems) {
                                            if (!responseItem.path("type").asText().equals("reasoning")) {
                                                continue;
                                            }
                                            int fallbackIndex = -1;
                                            for (int index = 0; index < output.content.size(); index++) {
                                                if (!(output.content.get(index) instanceof ThinkingContent thinking)) {
                                                    continue;
                                                }
                                                if (fallbackIndex < 0 && thinking.thinkingSignature == null) {
                                                    fallbackIndex = index;
                                                }
                                                String result = "";
                                                if (thinking.thinkingSignature != null) {
                                                    try {
                                                        result = Json.MAPPER.readTree(thinking.thinkingSignature).path("id").asText();
                                                    } catch (IOException ignored) {
                                                    }
                                                }
                                                if (result
                                                        .equals(responseItem.path("id").asText())) {
                                                    fallbackIndex = index;
                                                    break;
                                                }
                                            }
                                            if (fallbackIndex < 0) {
                                                continue;
                                            }
                                            ThinkingContent thinking = (ThinkingContent) output.content.get(fallbackIndex);
                                            String text = openAiResponsesReasoningText(responseItem);
                                            output.content.set(
                                                    fallbackIndex,
                                                    new ThinkingContent(
                                                            text.isBlank() ? thinking.thinking : text, responseItem.toString(), thinking.redacted));
                                        }
                                    }
                                    for (String itemId : List.copyOf(items.keySet())) {
                                        OpenAiResponsesProvider.OutputItem item = items.get(itemId);
                                        switch (item.type) {
                                            case "message" -> {
                                                TextContent text = (TextContent) output.content.get(item.contentIndex);
                                                push(stream, new AssistantMessageEvent.TextEnd(item.contentIndex, text.text, output));
                                                items.remove(itemId);
                                            }
                                            case "reasoning" -> {
                                                ThinkingContent thinking = (ThinkingContent) output.content.get(item.contentIndex);
                                                push(stream, new AssistantMessageEvent.ThinkingEnd(item.contentIndex, thinking.thinking, output));
                                                items.remove(itemId);
                                            }
                                            case "function_call" -> {
                                                ObjectNode synthetic = jsonObject().put("arguments", item.arguments.toString());
                                                if (itemId.startsWith("output:")) {
                                                    synthetic.put("output_index", Integer.parseInt(itemId.substring("output:".length())));
                                                } else {
                                                    synthetic.put("item_id", itemId.substring("item:".length()));
                                                }
                                                openAiResponsesFinishTool(stream, output, synthetic, items);
                                            }
                                        }
                                    }
                                    push(stream, new AssistantMessageEvent.Done(output.stopReason, output));
                                    return;
                                }
                                case "response.failed", "error" -> throw new IOException(
                                        event.path("error").path("message").asText("OpenAI Responses stream error"));
                                default -> {
                                    // Other emitted event types do not change the normalized stream.
                                }
                            }
                        }
                        throw new IOException("OpenAI Responses stream ended without completion");
                    });
        });
    }

    private void openAiResponsesAppendThinkingDelta(
            AssistantMessageEventStream stream,
            AssistantMessage output,
            OpenAiResponsesProvider.OutputItem item,
            String delta) {
        if (item == null || !item.type.equals("reasoning") || delta.isEmpty()) {
            return;
        }
        ThinkingContent current = (ThinkingContent) output.content.get(item.contentIndex);
        output.content.set(item.contentIndex, new ThinkingContent(
                current.thinking + delta, current.thinkingSignature, current.redacted));
        push(stream, new AssistantMessageEvent.ThinkingDelta(item.contentIndex, delta, output));
    }

    private void openAiResponsesFinishTool(
            AssistantMessageEventStream stream,
            AssistantMessage output,
            JsonNode event,
            Map<String, OpenAiResponsesProvider.OutputItem> items)
            throws IOException {
        OpenAiResponsesProvider.OutputItem item = items.remove(openAiResponsesItemKey(event));
        if (item == null || !item.type.equals("function_call")) {
            return;
        }
        String rawArguments = event.path("arguments").asText(item.arguments.toString());
        JsonNode parsed = Json.MAPPER.readTree(rawArguments);
        if (!(parsed instanceof ObjectNode arguments)) {
            throw new IOException("OpenAI function call arguments must be a JSON object");
        }
        ToolCall call = new ToolCall(item.callId, item.name, arguments, null);
        output.content.set(item.contentIndex, call);
        push(stream, new AssistantMessageEvent.ToolCallEnd(item.contentIndex, call, output));
    }

    private static String openAiResponsesItemKey(JsonNode event) {
        if (event.path("output_index").isIntegralNumber()) {
            return "output:" + event.path("output_index").asInt();
        }
        String itemId = event.path("item_id").asText(null);
        if (itemId == null || itemId.isBlank()) {
            itemId = event.path("item").path("id").asText(null);
        }
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("OpenAI Responses event is missing output_index and item id");
        }
        return "item:" + itemId;
    }

    private static String openAiResponsesReasoningText(JsonNode item) {
        StringBuilder text = new StringBuilder();
        for (String field : List.of("summary", "content")) {
            JsonNode parts = item.path(field);
            if (!parts.isArray()) {
                continue;
            }
            for (JsonNode part : parts) {
                String value = part.path("text").asText();
                if (value.isBlank()) {
                    continue;
                }
                if (!text.isEmpty()) {
                    text.append("\n\n");
                }
                text.append(value);
            }
            if (!text.isEmpty()) {
                break;
            }
        }
        return text.toString();
    }

    // ------------------------------------------------------ chatgpt provider

    public void configureCodexRequest(StreamOptions options, ChatGptToken token) {
        options.apiKey = token.accessToken;
        options.baseUrl = CHATGPT_CODEX_API_BASE_URL.toString();
        options.headers.put("ChatGPT-Account-Id", token.accountId);
        options.headers.put("originator", "pi-java");
        options.headers.put("User-Agent", "pi-java (" + System.getProperty("os.name", "unknown") + " "
                + System.getProperty("os.version", "unknown") + "; "
                + System.getProperty("os.arch", "unknown") + ")");
        options.headers.put("Accept", "text/event-stream");
        options.headers.put("OpenAI-Beta", "responses=experimental");
        if (options.sessionId != null && !options.sessionId.isBlank()) {
            options.headers.put("session-id", options.sessionId);
            options.headers.put("x-client-request-id", options.sessionId);
        }
    }

    private static AssistantMessageEventStream providerErrorStream(Model model, IOException error) {
        return providerStream(model, null, (_, _, _) -> {
            throw error;
        });
    }

    // ----------------------------------------------- github copilot provider

    /**
     * Builds the GitHub Copilot router provider over its catalog slice.
     */
    public ProviderState newGitHubCopilotProvider(ProviderState state, List<Model> models) {
        List<Model> all = List.copyOf(models);
        List<Model> models1 = copilotModelsFor(all, OpenAiResponsesProvider.API);
        state.id = GITHUB_COPILOT_PROVIDER_ID;
        state.models = all;

        state.anthropic = new AnthropicProvider(
                        GITHUB_COPILOT_PROVIDER_ID,
                        GITHUB_COPILOT_PROVIDER_NAME,
                        List.copyOf(copilotModelsFor(all, AnthropicProvider.API)),
                        List.of(),
                        true);
        openAiCompatibleProvider(
                state,
                GITHUB_COPILOT_PROVIDER_ID,
                copilotModelsFor(all, OPENAI_COMPATIBLE_API));
        state.responses = new OpenAiResponsesProvider(
                        GITHUB_COPILOT_PROVIDER_ID,
                        GITHUB_COPILOT_PROVIDER_NAME,
                        List.copyOf(models1),
                        List.of(),
                        null,
                        OpenAiResponsesProvider.RequestProfile.STANDARD);
        state.models = all;
        return state;
    }

    private static List<Model> copilotModelsFor(List<Model> models, String api) {
        return models.stream().filter(model -> model.api.equals(api)).toList();
    }

    /**
     * Filters the catalog to models GitHub reports as enabled for the signed-in account.
     */
    List<Model> gitHubCopilotAvailableModels(ProviderState state) throws IOException {
        List<String> enabled = gitHubCopilotResolveToken(state).availableModelIds;
        return filterEnabledCopilotModels(state, enabled);
    }

    /** Returns only last-known account entitlements, without refreshing tokens or making HTTP requests. */
    List<Model> gitHubCopilotCachedAvailableModels(ProviderState state) throws IOException {
        Credential credential = readCredential(state.credentials, GITHUB_COPILOT_PROVIDER_ID).orElse(null);
        if (!(credential instanceof Credential.OAuthCredential oauth) || oauth.availableModelIds == null) {
            return List.of();
        }
        return filterEnabledCopilotModels(state, oauth.availableModelIds);
    }

    /**
     * Enables catalog model policies, then refreshes the account's enabled-model list.
     */
    CopilotModelAccess gitHubCopilotEnableAndRefreshModels(ProviderState state) throws IOException {
        int policiesEnabled =
                gitHubCopilotEnableModels(state, state.models.stream().map(model -> model.id).toList());
        List<Model> available =
                filterEnabledCopilotModels(state, gitHubCopilotRefreshAvailableModels(state).availableModelIds);
        return new CopilotModelAccess(policiesEnabled, List.copyOf(available));
    }

    private List<Model> filterEnabledCopilotModels(ProviderState state, List<String> enabled) {
        if (enabled == null) {
            return state.models;
        }
        return state.models.stream().filter(model -> enabled.contains(model.id)).toList();
    }

    private static List<String> fauxChunks(String value) {
        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < value.length(); start += 4) {
            chunks.add(value.substring(start, Math.min(value.length(), start + 4)));
        }
        return chunks.isEmpty() ? List.of("") : chunks;
    }

    private static AssistantMessage fauxErrorMessage(Model model, String message) {
        AssistantMessage result = new AssistantMessage(model.api, model.provider, model.id);
        result.stopReason = StopReason.ERROR;
        result.errorMessage = message;
        return result;
    }

    private static long fauxEstimateTokens(String text) {
        return text == null || text.isEmpty() ? 0 : (text.length() + 3L) / 4L;
    }

    static List<Model> providerModels(Provider provider) {
        return switch (provider) {
            case AnthropicProvider anthropic -> anthropic.models;
            case ProviderState state -> state.models;
            case FauxProvider faux -> faux.models;
            case OpenAiResponsesProvider responses -> responses.models;
            default -> throw unknownProvider(provider);
        };
    }

    /**
     * Starts a streaming request. Once invoked, failures are encoded in the
     * returned stream (Error event with stopReason ERROR/ABORTED) rather than
     * thrown, except for argument validation.
     */
    public AssistantMessageEventStream stream(
            Provider provider, Model model, Context context, StreamOptions options) {
        return switch (provider) {
            case AnthropicProvider anthropic -> anthropicStream(anthropic, model, context, options);
            case ProviderState chatGpt when chatGpt.role == ProviderState.Role.CHATGPT -> {
                if (!model.provider.equals(CHATGPT_PROVIDER_ID)) {
                    throw new IllegalArgumentException("Model " + model + " is not a ChatGPT subscription model");
                }
                StreamOptions requestOptions = options == null ? new StreamOptions() : copyStreamOptions(options);
                try {
                    configureCodexRequest(requestOptions, chatGptResolveToken(chatGpt));
                } catch (IOException error) {
                    yield providerErrorStream(model, error);
                }
                yield openAiResponsesStream(chatGpt.responses, model, context, requestOptions);
            }
            case FauxProvider faux -> {
                AssistantMessageEventStream stream = new AssistantMessageEventStream();
                FauxProvider.ResponseStep step;
                synchronized (faux) {
                    step = faux.pendingResponses.pollFirst();
                    synchronized (faux.state) {
                        faux.state.callCount++;
                    }
                }
                Thread.startVirtualThread(() -> {
                    StreamOptions requestOptions = options != null ? options : new StreamOptions();
                    try {
                        AssistantMessage response;
                        response = step == null ? fauxErrorMessage(model, "No more faux responses queued") : switch (step) {
                            case FauxProvider.ResponseStep.Message message -> {
                                AssistantMessage copy = new AssistantMessage(message.response.api, message.response.provider, message.response.model);
                                copy.content.addAll(message.response.content);
                                copy.responseModel = message.response.responseModel;
                                copy.responseId = message.response.responseId;
                                copy.stopReason = message.response.stopReason;
                                copy.errorMessage = message.response.errorMessage;
                                copy.rawStopReason = message.response.rawStopReason;
                                copy.timestamp = message.response.timestamp;
                                copy.usage = message.response.usage;
                                yield copy;
                            }
                            case FauxProvider.ResponseStep.Factory factory ->
                                    factory.factory.apply(new FauxProvider.Request(context, requestOptions, faux.state, model));
                        };
                        response.api = faux.api;
                        response.provider = faux.id;
                        response.model = model.id;
                        if (response.usage == null) {
                            response.usage = new Usage();
                        }
                        long input = fauxEstimateTokens(context.systemPrompt);
                        for (Message message : context.messages) {
                            input += fauxEstimateTokens(message.toString());
                        }
                        long output = fauxEstimateTokens(text(response)) + fauxEstimateTokens(thinking(response));
                        response.usage.input = input;
                        response.usage.output = output;
                        response.usage.totalTokens = input + output;
                        AssistantMessage partial = new AssistantMessage(response.api, response.provider, response.model);
                        partial.responseId = response.responseId;
                        partial.usage = response.usage;
                        push(stream, new AssistantMessageEvent.Start(partial));

                        for (int index = 0; index < response.content.size(); index++) {
                            if (isAborted(requestOptions)) {
                                abortStream(stream, partial);
                                return;
                            }
                            AssistantContent block = response.content.get(index);
                            if (block instanceof TextContent text) {
                                partial.content.add(new TextContent("", text.textSignature));
                                push(stream, new AssistantMessageEvent.TextStart(index, partial));
                                StringBuilder value = new StringBuilder();
                                for (String chunk : fauxChunks(text.text)) {
                                    if (isAborted(requestOptions)) {
                                        abortStream(stream, partial);
                                        return;
                                    }
                                    value.append(chunk);
                                    partial.content.set(index, new TextContent(value.toString(), text.textSignature));
                                    push(stream, new AssistantMessageEvent.TextDelta(index, chunk, partial));
                                }
                                push(stream, new AssistantMessageEvent.TextEnd(index, text.text, partial));
                            } else if (block instanceof ThinkingContent thinking) {
                                partial.content.add(new ThinkingContent("", thinking.thinkingSignature, thinking.redacted));
                                push(stream, new AssistantMessageEvent.ThinkingStart(index, partial));
                                StringBuilder value = new StringBuilder();
                                for (String chunk : fauxChunks(thinking.thinking)) {
                                    if (isAborted(requestOptions)) {
                                        abortStream(stream, partial);
                                        return;
                                    }
                                    value.append(chunk);
                                    partial.content.set(
                                            index, new ThinkingContent(value.toString(), thinking.thinkingSignature, thinking.redacted));
                                    push(stream, new AssistantMessageEvent.ThinkingDelta(index, chunk, partial));
                                }
                                push(stream, new AssistantMessageEvent.ThinkingEnd(index, thinking.thinking, partial));
                            } else if (block instanceof ToolCall call) {
                                partial.content.add(new ToolCall(call.id, call.name, jsonObject(), call.thoughtSignature));
                                push(stream, new AssistantMessageEvent.ToolCallStart(index, partial));
                                String encoded = call.arguments.toString();
                                for (String chunk : fauxChunks(encoded)) {
                                    if (isAborted(requestOptions)) {
                                        abortStream(stream, partial);
                                        return;
                                    }
                                    push(stream, new AssistantMessageEvent.ToolCallDelta(index, chunk, partial));
                                }
                                partial.content.set(index, call);
                                push(stream, new AssistantMessageEvent.ToolCallEnd(index, call, partial));
                            }
                        }
                        partial.usage = response.usage;
                        partial.stopReason = response.stopReason;
                        partial.errorMessage = response.errorMessage;
                        partial.rawStopReason = response.rawStopReason;
                        partial.responseId = response.responseId;
                        partial.responseModel = response.responseModel;
                        partial.timestamp = response.timestamp;
                        if (response.stopReason == StopReason.ERROR || response.stopReason == StopReason.ABORTED) {
                            push(stream, new AssistantMessageEvent.Error(response.stopReason, partial));
                        } else if (response.stopReason == StopReason.PENDING) {
                            partial.stopReason = StopReason.ERROR;
                            partial.errorMessage = "Faux response ended without a stop reason";
                            push(stream, new AssistantMessageEvent.Error(StopReason.ERROR, partial));
                        } else {
                            push(stream, new AssistantMessageEvent.Done(response.stopReason, partial));
                        }

                    } catch (Exception e) {
                        AssistantMessage error = fauxErrorMessage(model, e.getMessage() != null ? e.getMessage() : e.toString());
                        push(stream, new AssistantMessageEvent.Error(StopReason.ERROR, error));
                    }
                });
                yield stream;
            }
            case ProviderState copilot when copilot.role == ProviderState.Role.GITHUB_COPILOT -> {
                if (!model.provider.equals(GITHUB_COPILOT_PROVIDER_ID)) {
                    throw new IllegalArgumentException("Model " + model + " is not a GitHub Copilot model");
                }
                StreamOptions requestOptions = options == null ? new StreamOptions() : copyStreamOptions(options);
                if (requestOptions.apiKey == null || requestOptions.apiKey.isBlank()) {
                    try {
                        CopilotToken token = gitHubCopilotResolveToken(copilot);
                        requestOptions.apiKey = token.accessToken;
                        requestOptions.baseUrl = token.baseUrl.toString();
                    } catch (IOException error) {
                        yield providerErrorStream(model, error);
                    }
                }
                yield switch (model.api) {
                    case "anthropic-messages" -> anthropicStream(copilot.anthropic, model, context, requestOptions);
                    case "openai-completions" ->
                            openAiCompatibleStream(copilot, model, context, requestOptions);
                    case "openai-responses" -> openAiResponsesStream(copilot.responses, model, context, requestOptions);
                    default -> throw new IllegalArgumentException("Unsupported GitHub Copilot model API: " + model.api);
                };
            }
            case ProviderState google when google.role == ProviderState.Role.GOOGLE -> {
                if (!model.api.equals(GOOGLE_API)) {
                    throw new IllegalArgumentException("Model " + model + " is not a Google Generative AI model");
                }
                yield providerStream(model, options, (stream, output, requestOptions) -> {
                    String key = requestOptions.apiKey;
                    if (key == null || key.isBlank()) {
                        key = resolveSystemApiKey("google").orElse(null);
                    }
                    if (key == null || key.isBlank()) {
                        throw new IllegalStateException("No API key for provider: google");
                    }
                    String url = model.baseUrl + "/models/" + encodeUrlPathSegment(model.id)
                            + ":streamGenerateContent?alt=sse&key=" + URLEncoder.encode(key, StandardCharsets.UTF_8);
                    Map<String, String> headers = new LinkedHashMap<>(model.headers);
                    headers.putAll(requestOptions.headers);
                    ObjectNode request = jsonObject();
                    if (context.systemPrompt != null && !context.systemPrompt.isBlank()) {
                        request.putObject("systemInstruction").putArray("parts").addObject().put("text", context.systemPrompt);
                    }
                    ArrayNode contents = request.putArray("contents");
                    for (Message message : context.messages) {
                        switch (message) {
                            case UserMessage user -> {
                                ObjectNode target = contents.addObject().put("role", "user");
                                ArrayNode parts = target.putArray("parts");
                                for (UserContent block1 : user.content) {
                                    if (block1 instanceof TextContent text1) {
                                        parts.addObject().put("text", text1.text);
                                    } else if (block1 instanceof ImageContent image) {
                                        parts.addObject().putObject("inlineData").put("mimeType", image.mimeType).put("data", image.data);
                                    }
                                }
                            }
                            case AssistantMessage assistant -> {
                                ArrayNode parts = contents.addObject().put("role", "model").putArray("parts");
                                for (AssistantContent block1 : assistant.content) {
                                    if (block1 instanceof TextContent text1) {
                                        parts.addObject().put("text", text1.text);
                                    } else if (block1 instanceof ThinkingContent thinking1) {
                                        ObjectNode part1 = parts.addObject().put("text", thinking1.thinking).put("thought", true);
                                        if (thinking1.thinkingSignature != null) {
                                            part1.put("thoughtSignature", thinking1.thinkingSignature);
                                        }
                                    } else if (block1 instanceof ToolCall call1) {
                                        ObjectNode function1 = parts.addObject().putObject("functionCall");
                                        function1.put("name", call1.name);
                                        function1.set("args", call1.arguments);
                                        function1.put("id", call1.id);
                                    }
                                }
                            }
                            case ToolResultMessage result -> {
                                ObjectNode function1 = contents.addObject()
                                        .put("role", "user")
                                        .putArray("parts")
                                        .addObject()
                                        .putObject("functionResponse");
                                function1.put("name", result.toolName);
                                function1.put("id", result.toolCallId);
                                function1.putObject("response").put(result.isError ? "error" : "output", text(result));
                            }
                        }
                    }
                    ObjectNode generation = request.putObject("generationConfig");
                    if (requestOptions.temperature != null) {
                        generation.put("temperature", requestOptions.temperature);
                    }
                    if (requestOptions.maxTokens != null) {
                        generation.put("maxOutputTokens", requestOptions.maxTokens);
                    }
                    if (requestOptions.reasoning != null && requestOptions.reasoning != ThinkingLevel.OFF) {
                        generation.putObject("thinkingConfig").put("includeThoughts", true);
                    }
                    if (!context.tools.isEmpty()) {
                        ArrayNode declarations = request.putArray("tools").addObject().putArray("functionDeclarations");
                        for (Tool tool : context.tools) {
                            ObjectNode declaration = declarations.addObject();
                            declaration.put("name", tool.name);
                            declaration.put("description", tool.description);
                            declaration.set("parametersJsonSchema", tool.parameters);
                        }
                    }
                    postJsonSse(url, headers, request, requestOptions, reader -> {
                        push(stream, new AssistantMessageEvent.Start(output));
                        SseReader.SseEvent sse;
                        int toolCounter = 0;
                        while ((sse = nextSseEvent(reader)) != null) {
                            if (isAborted(requestOptions)) {
                                abortStream(stream, output);
                                return;
                            }
                            JsonNode chunk = Json.MAPPER.readTree(sse.data);
                            if (chunk == null) {
                                continue;
                            }
                            output.responseId = chunk.path("responseId").asText(output.responseId);
                            JsonNode candidate = chunk.path("candidates").path(0);
                            for (JsonNode part : candidate.path("content").path("parts")) {
                                if (part.path("text").isTextual()) {
                                    String text = part.path("text").asText();
                                    if (part.path("thought").asBoolean()) {
                                        int index;
                                        if (!output.content.isEmpty() && output.content.getLast() instanceof ThinkingContent) {
                                            index = output.content.size() - 1;
                                        } else {
                                            int index1 = output.content.size();
                                            output.content.add(new ThinkingContent("", null, false));
                                            push(stream, new AssistantMessageEvent.ThinkingStart(index1, output));
                                            index = index1;
                                        }
                                        ThinkingContent current = (ThinkingContent) output.content.get(index);
                                        output.content.set(
                                                index,
                                                new ThinkingContent(
                                                        current.thinking + text,
                                                        part.path("thoughtSignature").asText(current.thinkingSignature),
                                                        false));
                                        push(stream, new AssistantMessageEvent.ThinkingDelta(index, text, output));
                                    } else {
                                        int index;
                                        if (!output.content.isEmpty() && output.content.getLast() instanceof TextContent) {
                                            index = output.content.size() - 1;
                                        } else {
                                            int index1 = output.content.size();
                                            output.content.add(new TextContent("", null));
                                            push(stream, new AssistantMessageEvent.TextStart(index1, output));
                                            index = index1;
                                        }
                                        TextContent current = (TextContent) output.content.get(index);
                                        output.content.set(
                                                index,
                                                new TextContent(
                                                        current.text + text,
                                                        part.path("thoughtSignature").asText(current.textSignature)));
                                        push(stream, new AssistantMessageEvent.TextDelta(index, text, output));
                                    }
                                }
                                JsonNode function = part.path("functionCall");
                                if (function.isObject()) {
                                    int index = output.content.size();
                                    String id = function.path("id").asText("call_" + (++toolCounter));
                                    String name = function.path("name").asText();
                                    JsonNode args = function.path("args");
                                    ObjectNode arguments = args instanceof ObjectNode object ? object : jsonObject();
                                    ToolCall call = new ToolCall(id, name, arguments, part.path("thoughtSignature").asText(null));
                                    output.content.add(call);
                                    push(stream, new AssistantMessageEvent.ToolCallStart(index, output));
                                    push(stream, new AssistantMessageEvent.ToolCallDelta(index, arguments.toString(), output));
                                    push(stream, new AssistantMessageEvent.ToolCallEnd(index, call, output));
                                }
                            }
                            JsonNode usage = chunk.path("usageMetadata");
                            if (usage.isObject()) {
                                long cacheRead = usage.path("cachedContentTokenCount").asLong();
                                output.usage.input = usage.path("promptTokenCount").asLong() - cacheRead;
                                output.usage.output =
                                        usage.path("candidatesTokenCount").asLong() + usage.path("thoughtsTokenCount").asLong();
                                output.usage.cacheRead = cacheRead;
                                output.usage.reasoning = usage.path("thoughtsTokenCount").asLong();
                                output.usage.totalTokens =
                                        usage.path("totalTokenCount").asLong(output.usage.input + output.usage.output + cacheRead);
                            }
                            String finish = candidate.path("finishReason").asText("");
                            if (!finish.isEmpty()) {
                                output.rawStopReason = finish;
                                output.stopReason = switch (finish) {
                                    case "MAX_TOKENS" -> StopReason.LENGTH;
                                    case "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT" -> StopReason.ERROR;
                                    default -> toolCalls(output).isEmpty() ? StopReason.STOP : StopReason.TOOL_USE;
                                };
                            }
                        }
                        for (int index = 0; index < output.content.size(); index++) {
                            AssistantContent block = output.content.get(index);
                            if (block instanceof TextContent text) {
                                push(stream, new AssistantMessageEvent.TextEnd(index, text.text, output));
                            } else if (block instanceof ThinkingContent thinking) {
                                push(stream, new AssistantMessageEvent.ThinkingEnd(index, thinking.thinking, output));
                            }
                        }
                        if (output.stopReason == StopReason.PENDING) {
                            output.stopReason = toolCalls(output).isEmpty() ? StopReason.STOP : StopReason.TOOL_USE;
                        }
                        calculateCost(model, output.usage);
                        if (output.stopReason == StopReason.ERROR) {
                            output.errorMessage = "Google response blocked: " + output.rawStopReason;
                            push(stream, new AssistantMessageEvent.Error(StopReason.ERROR, output));
                        } else {
                            push(stream, new AssistantMessageEvent.Done(output.stopReason, output));
                        }
                    });
                });
            }
            case ProviderState compatible when compatible.role == ProviderState.Role.OPENAI_COMPATIBLE ->
                    openAiCompatibleStream(compatible, model, context, options);
            case OpenAiResponsesProvider responses -> openAiResponsesStream(responses, model, context, options);
            default -> throw unknownProvider(provider);
        };
    }

    private static IllegalArgumentException unknownProvider(Provider provider) {
        return new IllegalArgumentException(
                "Unknown provider carrier: " + (provider == null ? "null" : provider.getClass().getName()));
    }

    // ---------------------------------------------------------------- agent

    public AgentSnapshot state() {
        return new AgentSnapshot(
                selectedModel == null ? null : copyModel(selectedModel), thinkingLevel,
                isStreaming, isCompacting, autoCompactionEnabled, snapshotMessages(messages), sessionId, agentMode());
    }

    /** Starts a fresh conversation with the selected provider and workspace tools. */
    public void configureAgent(Model model, Path cwd, String systemPrompt, String apiKey, ThinkingLevel level) {
        configureAgent(requireCoreProvider(model.provider), model, cwd, systemPrompt, apiKey, level);
    }

    /** Configures an explicitly supplied provider, including an in-process provider for embedding or tests. */
    public void configureAgent(Provider provider, Model model, Path cwd, String systemPrompt, String apiKey, ThinkingLevel level) {
        if (childRuntime) requireIdleAgent(); else requireIdleGroup();
        if (subagents != null) { subagents.close(); subagents = null; }
        closeShellSessions();
        debugger.closeSessions();
        agentWorkspace = cwd.toAbsolutePath().normalize();
        baseInstructions = systemPrompt;
        transcript.clear();
        taskState.reset();
        agentState(systemPrompt == null ? "" : systemPrompt, model);
        agent(Objects.requireNonNull(provider, "provider"));
        this.apiKey = apiKey;
        thinkingLevel = clampThinkingLevel(model, level == null ? ThinkingLevel.OFF : level);
        recordingSession = false;
        persistenceFailure = ignored -> {};
        configureBuiltInTools(cwd, systemPrompt);
        if (!childRuntime) { subagents = new SubagentManager(this); tools.add(subagents.tool()); }
        syncMcpTools();
    }

    /** Restores the conversation after selecting its model and session recorder. */
    public void restoreMessages(List<Message> restored) {
        requireIdleAgent();
        messages = new CopyOnWriteArrayList<>(resumableMessages(restored));
        transcript.clear();
        transcript.addAll(restored);
    }

    public void setThinkingLevel(ThinkingLevel level) {
        requireIdleAgent();
        thinkingLevel = clampThinkingLevel(selectedModel, Objects.requireNonNull(level, "level"));
    }

    public void setAutoCompaction(boolean enabled) {
        requireIdleAgent();
        autoCompactionEnabled = enabled;
    }

    /** Enables persistence for the current recorder; checkpoint failures are reported without losing the live turn. */
    public void setSessionRecording(boolean enabled, Consumer<IOException> onCheckpointFailure) {
        requireIdleAgent();
        if (enabled && sessionId == null) throw new IllegalStateException("No session recorder configured");
        recordingSession = enabled;
        persistenceFailure = Objects.requireNonNull(onCheckpointFailure, "onCheckpointFailure");
    }

    public List<Message> prompt(String text) throws IOException, InterruptedException {
        requireOpen();
        return runPrompt(text);
    }

    public void syncMcpTools() {
        requireIdleAgent();
        tools.removeIf(McpAgentTool.class::isInstance);
        tools.addAll(mcpTools());
    }

    private void requireIdleAgent() {
        requireOpen();
        if (isStreaming || isCompacting) throw new IllegalStateException("Agent is already processing");
    }

    private static final String COMPACTION_SYSTEM_PROMPT =
            "You summarize coding-agent conversations. Do not continue the conversation. "
                    + "Return a concise structured checkpoint covering the goal, completed work, current state, decisions, and next steps. "
                    + "Preserve the latest implementation plan, ordered changes, clarification questions and explicit answers, "
                    + "unresolved issues, assumptions, and verification steps. Do not invent approval or answers. "
                    + "The application's current mode instructions, supplied separately on future turns, determine whether implementation is allowed.";

    /** Creates the folded mutable state for one agent. */
    private void agentState(String systemPrompt, Model model) {
        this.systemPrompt = systemPrompt;
        selectedModel = model;
        messages = new CopyOnWriteArrayList<>();
        streamingMessage = null;
        pendingToolCalls = new LinkedHashSet<>();
        isStreaming = false;
        isCompacting = false;
    }

    /** Configures this runtime as a fresh active agent. */
    void agent(Provider provider) {
        agentProvider = provider;
        listeners = new CopyOnWriteArrayList<>();
        activeSignal = null;
        retryPolicy = Retry.Policy.DEFAULT;
        tools = new ArrayList<>();
    }

    /**
     * Registers an agent event listener; closing the result unsubscribes it.
     */
    public AutoCloseable subscribe(Consumer<AgentEvent> listener) {
        requireOpen();
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /**
     * Cancels the turn in flight, if any.
     */
    public void abort() {
        if (subagents != null) subagents.cancel(SubagentManager.MAIN);
        else abortLocal();
    }

    void abortLocal() {
        AbortSignal signal = activeSignal;
        if (signal != null) {
            abort(signal);
        }
        questions().cancelAgent(questionAgentId);
        closeShellSessions();
    }

    /**
     * Summarizes all active messages in a separate model call, then replaces
     * them with a single checkpoint message. The caller remains responsible for
     * persisting the original transcript if it needs complete history.
     */
    public CompactionResult compact(String customInstructions) throws InterruptedException {
        requireOpen();
        synchronized (groupLock()) {
            requireIdleAgent();
            if (messages.isEmpty()) throw new IllegalStateException("Cannot compact an empty conversation");
            activeSignal = new AbortSignal();
            isCompacting = true;
        }
        try {
            return performCompaction(compactionPrompt(messages, customInstructions), false);
        } finally {
            isCompacting = false;
            activeSignal = null;
        }
    }

    private static String compactionPrompt(List<Message> messages, String instructions) {
        StringBuilder serialized = new StringBuilder();
        for (Message message : messages) {
            if (message instanceof UserMessage user) serialized.append("[User] ").append(text(user));
            else if (message instanceof AssistantMessage assistant) {
                String thinking = thinking(assistant);
                if (!thinking.isBlank()) serialized.append("[Assistant thinking] ").append(thinking).append('\n');
                serialized.append("[Assistant] ").append(text(assistant));
                for (ToolCall call : toolCalls(assistant))
                    serialized.append("\n[Tool call] ").append(call.name).append(' ').append(call.arguments);
            } else if (message instanceof ToolResultMessage toolResult)
                serialized.append("[Tool result] ").append(text(toolResult));
            serialized.append("\n\n");
        }
        return "<conversation>\n" + serialized + "\n</conversation>\n\n"
                + (instructions == null || instructions.isBlank()
                ? "Summarize this conversation for a future coding-agent turn."
                : "Summarize this conversation with this focus: " + instructions);
    }

    private CompactionResult performCompaction(String prompt, boolean automatic)
            throws InterruptedException {
        long tokensBefore = estimateMessageTokens(messages);
        emit(new AgentEvent.CompactionStart(tokensBefore));
        Context context = new Context(COMPACTION_SYSTEM_PROMPT);
        context.messages.add(userMessage(prompt));
        AbortSignal signal = activeSignal == null ? new AbortSignal() : activeSignal;
        AssistantMessage response = retryAssistantCall(
                () -> {
                    StreamOptions options = new StreamOptions();
                    options.signal = signal;
                    options.reasoning = thinkingLevel;
                    options.apiKey = apiKey;
                    options.maxTokens = 4_096;
                    AssistantMessageEventStream stream = stream(agentProvider, selectedModel, context, options);
                    AssistantMessage completed = null;
                    for (AssistantMessageEvent event : events(stream)) {
                        if (event instanceof AssistantMessageEvent.Done done) completed = done.message;
                        if (event instanceof AssistantMessageEvent.Error error) completed = error.error;
                    }
                    return completed == null ? result(stream) : completed;
                },
                retryPolicy,
                signal,
                agentRetryCallbacks(null));
        String summary = text(response);
        if (response.stopReason == StopReason.ERROR || response.stopReason == StopReason.ABORTED || summary.isBlank()) {
            String detail = summary.isBlank() && !automatic
                    && response.stopReason != StopReason.ERROR && response.stopReason != StopReason.ABORTED
                    ? "provider returned an empty summary"
                    : response.errorMessage;
            throw new IllegalStateException((automatic ? "Automatic compaction failed: " : "Compaction failed: ") + detail);
        }
        messages.clear();
        messages.add(userMessage("[Conversation checkpoint]\n" + summary));
        CompactionResult result = new CompactionResult(summary, tokensBefore, estimateMessageTokens(messages));
        if (recordingSession) {
            try {
                appendSessionCompaction(result);
            } catch (IOException error) {
                persistenceFailure.accept(error);
            }
        }
        emit(new AgentEvent.CompactionEnd(result));
        return result;
    }

    /**
     * Runs a prompt to completion, returning only messages created during this invocation.
     */
    private List<Message> runPrompt(String text) throws InterruptedException {
        Message[] prompts = new Message[]{userMessage(text)};
        synchronized (groupLock()) {
            requireIdleAgent();
            activeSignal = new AbortSignal();
            isStreaming = true;
        }
        List<Message> newMessages = new ArrayList<>();
        try {
            if (autoCompactionEnabled
                    && messages.size() > 1
                    && selectedModel.contextWindow > compactionReserveTokens
                    && estimateMessageTokens(messages) > selectedModel.contextWindow - compactionReserveTokens) {
                isCompacting = true;
                try {
                    performCompaction(compactionPrompt(messages, null), true);
                } finally {
                    isCompacting = false;
                }
            }
            emit(new AgentEvent.AgentStart());
            emit(new AgentEvent.TurnStart());
            for (Message prompt : prompts) {
                messages.add(prompt);
                newMessages.add(prompt);
                acceptMessage(prompt);
                emit(new AgentEvent.MessageStart(prompt));
                emit(new AgentEvent.MessageEnd(prompt));
            }

            while (true) {
                AssistantMessage[] lastAttempt = new AssistantMessage[1];
                AssistantMessage response = retryAssistantCall(
                        () -> {
                            Context context = new Context(systemPrompt + "\n\n" + agentMode().instructions());
                            for (Message message : messages) {
                                if (!(message instanceof AssistantMessage assistant)
                                        || (assistant.stopReason != StopReason.ERROR && assistant.stopReason != StopReason.ABORTED)) {
                                    context.messages.add(message);
                                }
                            }
                            for (AgentTool tool : tools) {
                                String name = toolName(tool);
                                String description = toolDescription(tool);
                                ObjectNode parameters = toolParameters(tool);
                                if (name == null || description == null || parameters == null) {
                                    throw new IllegalArgumentException("name, description, and parameters must not be null");
                                }
                                context.tools.add(new Tool(name, description, parameters));
                            }
                            StreamOptions options = new StreamOptions();
                            options.signal = activeSignal;
                            options.reasoning = thinkingLevel;
                            options.apiKey = apiKey;
                            AssistantMessageEventStream stream = stream(agentProvider, selectedModel, context, options);
                            AssistantMessage finalMessage = null;
                            for (AssistantMessageEvent event : events(stream)) {
                                switch (event) {
                                    case AssistantMessageEvent.Start start -> {
                                        streamingMessage = start.partial;
                                        messages.add(start.partial);
                                        emit(new AgentEvent.MessageStart(start.partial));
                                    }
                                    case AssistantMessageEvent.Done done -> finalMessage = done.message;
                                    case AssistantMessageEvent.Error error -> finalMessage = error.error;
                                    default -> emit(new AgentEvent.MessageUpdate(event));
                                }
                            }
                            if (finalMessage == null) {
                                finalMessage = result(stream);
                            }
                            if (streamingMessage != null) {
                                messages.set(messages.size() - 1, finalMessage);
                            } else {
                                messages.add(finalMessage);
                                emit(new AgentEvent.MessageStart(finalMessage));
                            }
                            streamingMessage = null;
                            emit(new AgentEvent.MessageEnd(finalMessage));
                            return lastAttempt[0] = finalMessage;
                        },
                        retryPolicy,
                        activeSignal,
                        agentRetryCallbacks(() -> {
                            streamingMessage = null;
                            if (lastAttempt[0] == null) return;
                            for (int index = messages.size() - 1; index >= 0; index--) {
                                if (messages.get(index) == lastAttempt[0]) {
                                    messages.remove(index);
                                    return;
                                }
                            }
                        }));
                newMessages.add(response);
                acceptMessage(response);
                if (response.stopReason == StopReason.ERROR || response.stopReason == StopReason.ABORTED) {
                    emit(new AgentEvent.TurnEnd(response, List.of()));
                    break;
                }
                Map<String, AgentTool> toolsByName = new LinkedHashMap<>();
                for (AgentTool tool : tools) {
                    toolsByName.put(toolName(tool), tool);
                }
                List<ToolResultMessage> results1 = new ArrayList<>();
                for (ToolCall call : toolCalls(response)) {
                    if (isAborted(activeSignal)) {
                        break;
                    }
                    pendingToolCalls.add(call.id);
                    emit(new AgentEvent.ToolExecutionStart(call.id, call.name, call.arguments));
                    AgentTool.ToolResult toolResult;
                    AgentTool tool = toolsByName.get(call.name);
                    if (tool == null) {
                        toolResult = new AgentTool.ToolResult(
                                List.of(new TextContent("Unknown tool: " + call.name, null)), null, true);
                    } else {
                        try {
                            toolResult = executeTool(
                                    tool,
                                    call.id,
                                    call.arguments,
                                    activeSignal,
                                    partial -> emit(new AgentEvent.ToolExecutionUpdate(call.id, call.name, partial)));
                        } catch (Exception e) {
                            toolResult = new AgentTool.ToolResult(
                                    List.of(new TextContent(e.getMessage() == null ? e.toString() : e.getMessage(), null)), null, true);
                        }
                    }
                    pendingToolCalls.remove(call.id);
                    emit(new AgentEvent.ToolExecutionEnd(call.id, call.name, toolResult));
                    ToolResultMessage resultMessage = new ToolResultMessage(
                            call.id,
                            call.name,
                            List.copyOf(toolResult.content),
                            toolResult.details,
                            toolResult.isError,
                            System.currentTimeMillis());
                    messages.add(resultMessage);
                    results1.add(resultMessage);
                    acceptMessage(resultMessage);
                    emit(new AgentEvent.MessageStart(resultMessage));
                    emit(new AgentEvent.MessageEnd(resultMessage));
                }
                newMessages.addAll(results1);
                emit(new AgentEvent.TurnEnd(response, results1));
                if (results1.isEmpty()) {
                    break;
                }
                emit(new AgentEvent.TurnStart());
            }
            return List.copyOf(newMessages);
        } finally {
            isStreaming = false;
            streamingMessage = null;
            pendingToolCalls.clear();
            emit(new AgentEvent.AgentEnd(List.copyOf(newMessages)));
            activeSignal = null;
        }
    }

    private Retry.Callbacks agentRetryCallbacks(Runnable beforeRetryAttempt) {
        return new Retry.Callbacks(
                scheduled -> emit(new AgentEvent.AutoRetryStart(
                        scheduled.attempt, scheduled.maxAttempts, scheduled.delayMs, scheduled.errorMessage)),
                () -> {
                    if (beforeRetryAttempt != null) beforeRetryAttempt.run();
                },
                finished -> emit(new AgentEvent.AutoRetryEnd(
                        finished.success, finished.attempt, finished.finalError)));
    }

    private static long estimateMessageTokens(List<Message> messages) {
        long characters = 0;
        for (Message message : messages) {
            if (message instanceof UserMessage user) characters += text(user).length();
            else if (message instanceof AssistantMessage assistant) {
                characters += text(assistant).length() + thinking(assistant).length();
                for (ToolCall call : toolCalls(assistant))
                    characters += call.name.length() + call.arguments.toString().length();
            } else if (message instanceof ToolResultMessage toolResult) characters += text(toolResult).length();
        }
        return (characters + 3) / 4;
    }

    private void emit(AgentEvent event) {
        for (Consumer<AgentEvent> listener : listeners) {
            listener.accept(event);
        }
    }

    // -------------------------------------------------------- tool dispatch

    public static String toolName(AgentTool tool) {
        return switch (tool) {
            case FunctionTool function -> function.name;
            case ToolDefinition.Bound<?> registered -> registered.definition().name();
            case McpAgentTool mcp -> mcp.name;
            default -> throw unknownTool(tool);
        };
    }

    public static String toolDescription(AgentTool tool) {
        return switch (tool) {
            case FunctionTool function -> function.description;
            case ToolDefinition.Bound<?> registered -> registered.definition().description();
            case McpAgentTool mcp -> mcp.definition.description;
            default -> throw unknownTool(tool);
        };
    }

    /**
     * Model-visible JSON schema of the tool's arguments.
     */
    public static ObjectNode toolParameters(AgentTool tool) {
        return switch (tool) {
            case FunctionTool function -> function.parameters;
            case ToolDefinition.Bound<?> registered -> registered.definition().parameters().schema();
            case McpAgentTool mcp -> mcp.definition.inputSchema.deepCopy();
            default -> throw unknownTool(tool);
        };
    }

    /**
     * Runs one tool call. Partial results are reported to {@code onUpdate} by
     * tools that produce them; failures are thrown for the caller to surface.
     */
    public AgentTool.ToolResult executeTool(
            AgentTool tool,
            String toolCallId,
            ObjectNode arguments,
            AbortSignal signal,
            Consumer<AgentTool.ToolResult> onUpdate)
            throws Exception {
        requireOpen();
        return switch (tool) {
            case FunctionTool function ->
                    function.execute.apply(new ToolInvocation(toolCallId, arguments, signal, onUpdate));
            case ToolDefinition.Bound<?> registered ->
                    registered.execute(new ToolInvocation(toolCallId, arguments, signal, onUpdate));
            case McpAgentTool mcp -> {
                ObjectNode effectiveArguments = arguments == null ? jsonObject() : arguments.deepCopy();
                JsonNode properties = mcp.definition.inputSchema.path("properties");
                String aliases = codeLensAliases();
                /* code-lens advertises this optional field on every read tool. The command-line
                 * configuration is authoritative, so do not let a model-selected value change
                 * the dependency graph mid-session. Server identity avoids affecting unrelated
                 * MCP tools that may also happen to use an "aliases" property. */
                if (aliases != null && "code-lens".equals(mcp.client.serverName)
                        && properties.isObject() && properties.has("aliases")) {
                    effectiveArguments.put("aliases", aliases);
                }
                ObjectNode params = jsonObject().put("name", mcp.definition.name);
                params.set("arguments", effectiveArguments);
                JsonNode response = mcpTransportRequest(mcp.client.transport, "tools/call", params, mcp.client.timeout, signal);
                if (!(response instanceof ObjectNode object)) {
                    throw new IOException("MCP tools/call returned an invalid result");
                }
                McpClient.CallResult callResult = new McpClient.CallResult(object.deepCopy(), object.path("isError").asBoolean(false));
                ObjectNode filtered;
                Set<String> dropKeys = new LinkedHashSet<>();
                for (McpResultFilter filter : mcp.resultFilters) {
                    boolean result1;
                    String tool1 = filter.tool;
                    if (tool1.indexOf('*') < 0 && tool1.indexOf('?') < 0) {
                        result1 = tool1.equals(mcp.definition.name);
                    } else {
                        StringBuilder regex = new StringBuilder("^");
                        for (int index = 0; index < tool1.length(); index++) {
                            switch (tool1.charAt(index)) {
                                case '*' -> regex.append(".*");
                                case '?' -> regex.append('.');
                                default -> regex.append(Pattern.quote(String.valueOf(tool1.charAt(index))));
                            }
                        }
                        result1 = Pattern.compile(regex.append('$').toString(), Pattern.DOTALL).matcher(mcp.definition.name).matches();
                    }
                    if (result1) dropKeys.addAll(filter.dropKeys);
                }
                if (dropKeys.isEmpty()) {
                    filtered = callResult.raw;
                } else {
                    ObjectNode filtered1 = callResult.raw.deepCopy();
                    mcpFilterNode(filtered1, dropKeys);
                    filtered = filtered1;
                }
                List<UserContent> output = new ArrayList<>();
                JsonNode content1 = filtered.get("content");
                if (content1 != null && content1.isArray()) {
                    for (JsonNode item : content1) {
                        if (!item.isObject()) {
                            output.add(new TextContent(item.toString(), null));
                            continue;
                        }
                        switch (item.path("type").asText()) {
                            case "text" -> output.add(new TextContent(item.path("text").asText(""), null));
                            case "image" -> {
                                if (item.path("data").isTextual() && item.path("mimeType").isTextual()) {
                                    output.add(new ImageContent(item.path("data").asText(), item.path("mimeType").asText()));
                                } else output.add(new TextContent(item.toString(), null));
                            }
                            case "resource" -> {
                                JsonNode resource = item.path("resource");
                                if (!resource.isObject()) {
                                    output.add(new TextContent(resource.toString(), null));
                                } else if (resource.path("text").isTextual()) {
                                    output.add(new TextContent(resource.path("text").asText(), null));
                                } else if (resource.path("blob").isTextual()
                                        && resource.path("mimeType").isTextual()
                                        && resource.path("mimeType").asText().startsWith("image/")) {
                                    output.add(new ImageContent(resource.path("blob").asText(), resource.path("mimeType").asText()));
                                } else {
                                    output.add(new TextContent(resource.toString(), null));
                                }
                            }
                            case "resource_link" -> {
                                String label = item.path("name").isTextual()
                                        ? item.path("name").asText()
                                        : item.path("uri").asText("resource");
                                output.add(new TextContent(label + ": " + item.path("uri").asText(item.toString()), null));
                            }
                            case "audio" -> output.add(
                                    new TextContent("[MCP audio content: " + item.path("mimeType").asText("unknown type") + "]", null));
                            default -> output.add(new TextContent(item.toString(), null));
                        }
                    }
                }
                if (output.isEmpty()) {
                    JsonNode structured = filtered.get("structuredContent");
                    if (structured != null && !structured.isNull())
                        output.add(new TextContent(structured.toString(), null));
                }
                List<UserContent> content = List.copyOf(output);
                if (content.isEmpty()) {
                    content = List.of(new TextContent(callResult.isError ? "MCP tool returned an error" : "", null));
                }
                yield new AgentTool.ToolResult(List.copyOf(content), filtered, callResult.isError);
            }
            default -> throw unknownTool(tool);
        };
    }

    public static AgentTool.ToolResult toolResultText(String text) {
        return new AgentTool.ToolResult(List.of(new TextContent(text, null)), null, false);
    }

    private static IllegalArgumentException unknownTool(AgentTool tool) {
        return new IllegalArgumentException(
                "Unknown tool carrier: " + (tool == null ? "null" : tool.getClass().getName()));
    }

    // ------------------------------------------------------- built-in tools

    /** Releases processes retained between conversation turns. */
    public void closeShellSessions() {
        shellSessions.closeSessions();
    }

    /** Binds registered built-in tools to this runtime's workspace and process manager. */
    public List<AgentTool> builtInTools(Path cwd, Consumer<Path> onPathAccess) {
        return LocalTools.bind(cwd, onPathAccess, executable, shellSessions, questions(), questionAgentId, taskState, debugger);
    }

    // ------------------------------------------------------------ mcp tools

    /**
     * Replaces characters that providers reject in tool names.
     */
    private static String mcpSanitizeName(String value) {
        return value.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private static JsonNode mcpFilterNode(JsonNode node, Set<String> dropKeys) {
        if (node instanceof ObjectNode object) {
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                if (dropKeys.contains(name)) object.remove(name);
                else object.set(name, mcpFilterNode(object.get(name), dropKeys));
            }
            return object;
        }
        if (node instanceof ArrayNode array) {
            for (int index = 0; index < array.size(); index++) {
                array.set(index, mcpFilterNode(array.get(index), dropKeys));
            }
            return array;
        }
        if (!node.isTextual()) return node;
        String text = node.asText();
        String trimmed = text.trim();
        if (!((trimmed.startsWith("{") && trimmed.endsWith("}"))
                || (trimmed.startsWith("[") && trimmed.endsWith("]")))) return node;
        try {
            JsonNode embedded = Json.MAPPER.readTree(text);
            if (embedded == null || (!embedded.isObject() && !embedded.isArray())) return node;
            return Json.MAPPER.getNodeFactory().textNode(mcpFilterNode(embedded, dropKeys).toString());
        } catch (IOException ignored) {
            return node;
        }
    }

    // ---------------------------------------------------------- mcp browser

    // ----------------------------------------------------- mcp server config

    /**
     * Whether this server should be connected by a manager.
     */
    private static boolean mcpConfigEnabled(McpServerConfig config) {
        return switch (config) {
            case McpServerConfig.Local local -> local.enabled;
            case McpServerConfig.Remote remote -> remote.enabled;
        };
    }

    // ----------------------------------------------------- mcp configuration

    /**
     * Loads configured MCP servers, or an empty configuration when settings do not exist.
     */
    public static McpConfiguration mcpLoadConfiguration(McpConfigLoader loader) throws IOException {
        Path settingsPath = loader.settingsPath;
        if (!Files.exists(settingsPath)) return new McpConfiguration(new LinkedHashMap<>(), List.of());
        if (!Files.isRegularFile(settingsPath)) {
            throw new IOException("Settings path is not a file: " + settingsPath);
        }

        String text;
        try {
            text = Files.readString(settingsPath, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IOException("Failed to read settings file " + settingsPath + ": " + error.getMessage(), error);
        }

        JsonNode root;
        try {
            Matcher envMatcher = McpConfigLoader.ENVIRONMENT.matcher(text);
            StringBuilder environmentExpanded = new StringBuilder();
            while (envMatcher.find()) {
                envMatcher.appendReplacement(
                        environmentExpanded,
                        Matcher.quoteReplacement(loader.environment.getOrDefault(envMatcher.group(1), "")));
            }
            envMatcher.appendTail(environmentExpanded);
            String text1 = environmentExpanded.toString();

            Matcher fileMatcher = McpConfigLoader.FILE.matcher(text1);
            StringBuilder output = new StringBuilder();
            int cursor = 0;
            while (fileMatcher.find()) {
                output.append(text1, cursor, fileMatcher.start());
                String token = fileMatcher.group();
                String configuredPath = fileMatcher.group(1);
                Path file;
                if (configuredPath.equals("~")) file = Path.of(System.getProperty("user.home"));
                else if (configuredPath.startsWith("~/")) {
                    file = Path.of(System.getProperty("user.home")).resolve(configuredPath.substring(2));
                } else {
                    file = mcpResolvePath(settingsPath.getParent(), configuredPath);
                }
                String value;
                try {
                    value = Files.readString(file, StandardCharsets.UTF_8).trim();
                } catch (IOException error) {
                    throw new IOException("Bad file reference " + token + " in " + loader.settingsPath + ": " + file, error);
                }
                String quoted = Json.MAPPER.writeValueAsString(value);
                output.append(quoted, 1, quoted.length() - 1);
                cursor = fileMatcher.end();
            }
            root = Json.MAPPER.readTree(output.append(text1, cursor, text1.length()).toString());
        } catch (IOException error) {
            throw new IOException("Failed to parse settings file " + settingsPath + ": " + error.getMessage(), error);
        }
        if (root == null || !root.isObject()) {
            throw new IOException("Invalid settings file " + settingsPath + ": expected a JSON object");
        }

        JsonNode mcp = root.get("mcp");
        if (mcp == null || mcp.isNull()) {
            return new McpConfiguration(new LinkedHashMap<>(), List.of(settingsPath));
        }
        if (!mcp.isObject()) {
            throw new IOException("Invalid mcp in " + settingsPath + ": expected an object");
        }

        LinkedHashMap<String, McpServerConfig> servers = new LinkedHashMap<>();
        for (var entry : mcp.properties()) {
            if (!(entry.getValue() instanceof ObjectNode server)) {
                throw new IOException("Invalid MCP server \"" + entry.getKey() + "\": expected an object");
            }
            String name = entry.getKey();
            if (name.isBlank()) throw mcpInvalidServer(name, "server name must not be blank");
            JsonNode typeNode = server.get("type");
            if (typeNode == null || !typeNode.isTextual()) throw mcpInvalidServer(name, "type must be a string");
            JsonNode node = server.get("enabled");
            if (node != null && !node.isNull() && !node.isBoolean()) {
                throw mcpInvalidServer(name, "enabled must be a boolean");
            }
            boolean enabled = node == null || node.isNull() || node.asBoolean();
            Long timeout = null;
            JsonNode node1 = server.get("timeout");
            if (node1 != null && !node1.isNull()) {
                if (!node1.isIntegralNumber() || !node1.canConvertToLong() || node1.asLong() <= 0) {
                    throw mcpInvalidServer(name, "timeout" + " must be a positive integer");
                }
                timeout = node1.asLong();
            }
            List<McpResultFilter> filters = new ArrayList<>();
            JsonNode node3 = server.get("resultFilters");
            if (node3 != null && !node3.isNull()) {
                if (!node3.isArray()) throw mcpInvalidServer(name, "resultFilters must be an array");
                for (int index = 0; index < node3.size(); index++) {
                    JsonNode filter = node3.get(index);
                    if (!filter.isObject())
                        throw mcpInvalidServer(name, "resultFilters[" + index + "] must be an object");
                    JsonNode tool1 = filter.get("tool");
                    if (tool1 == null || !tool1.isTextual() || tool1.asText().isBlank()) {
                        throw mcpInvalidServer(name, "resultFilters[" + index + "].tool must be a non-empty string");
                    }
                    JsonNode dropKeys = filter.get("dropKeys");
                    if (dropKeys == null || !dropKeys.isArray() || dropKeys.isEmpty()) {
                        throw mcpInvalidServer(
                                name, "resultFilters[" + index + "].dropKeys must be a non-empty array of strings");
                    }
                    List<String> keys = new ArrayList<>();
                    for (JsonNode key : dropKeys) {
                        if (!key.isTextual() || key.asText().isBlank()) {
                            throw mcpInvalidServer(
                                    name, "resultFilters[" + index + "].dropKeys must contain non-empty strings");
                        }
                        keys.add(key.asText());
                    }
                    filters.add(new McpResultFilter(tool1.asText(), List.copyOf(keys)));
                }
            }
            List<McpResultFilter> resultFilters = List.copyOf(filters);
            List<String> tools = new ArrayList<>();
            JsonNode node2 = server.get("disabledTools");
            if (node2 != null && !node2.isNull()) {
                if (!node2.isArray()) {
                    throw mcpInvalidServer(name, "disabledTools must be an array of non-empty strings");
                }
                for (JsonNode tool : node2) {
                    if (!tool.isTextual() || tool.asText().isBlank()) {
                        throw mcpInvalidServer(name, "disabledTools must contain non-empty strings");
                    }
                    tools.add(tool.asText());
                }
            }
            List<String> disabledTools = List.copyOf(tools);
            servers.put(entry.getKey(), switch (typeNode.asText()) {
                case "local" -> {
                    JsonNode commandNode = server.get("command");
                    if (commandNode == null || !commandNode.isArray() || commandNode.isEmpty()) {
                        throw mcpInvalidServer(name, "command must be a non-empty array of strings");
                    }
                    List<String> command = new ArrayList<>();
                    for (JsonNode argument : commandNode) {
                        if (!argument.isTextual() || argument.asText().isEmpty()) {
                            throw mcpInvalidServer(name, "command must contain only non-empty strings");
                        }
                        command.add(argument.asText());
                    }
                    String cwd = mcpOptionalText(name, server, "cwd");
                    Map<String, String> environment = mcpStringMap(name, server, "environment");
                    yield new McpServerConfig.Local(
                            List.copyOf(command),
                            cwd,
                            Map.copyOf(environment),
                            enabled,
                            timeout,
                            List.copyOf(resultFilters),
                            List.copyOf(disabledTools));
                }
                case "remote" -> {
                    String rawUrl = mcpOptionalText(name, server, "url");
                    if (rawUrl == null || rawUrl.isBlank()) {
                        throw mcpInvalidServer(name, "url must be a non-empty string");
                    }
                    URI url;
                    try {
                        url = new URI(rawUrl);
                    } catch (URISyntaxException error) {
                        throw mcpInvalidServer(name, "url is invalid: " + rawUrl);
                    }
                    if (!(url.getScheme() != null
                            && (url.getScheme().equalsIgnoreCase("http") || url.getScheme().equalsIgnoreCase("https"))
                            && url.getHost() != null
                            && url.getUserInfo() == null
                            && url.getFragment() == null)) {
                        throw mcpInvalidServer(
                                name, "url must be an absolute http or https URL without user info or a fragment");
                    }
                    JsonNode oauth = server.get("oauth");
                    if (oauth != null && !oauth.isNull() && !oauth.isObject() && !oauth.isBoolean()) {
                        throw mcpInvalidServer(name, "oauth must be an object or false");
                    }
                    if (oauth != null && oauth.isBoolean() && oauth.asBoolean()) {
                        throw mcpInvalidServer(name, "oauth may be an object or false, not true");
                    }
                    if (oauth instanceof ObjectNode oauthObject) {
                        for (String field : List.of("clientId", "clientSecret", "scope")) {
                            JsonNode value = oauthObject.get(field);
                            if (value != null && !value.isNull() && (!value.isTextual() || value.asText().isBlank())) {
                                throw mcpInvalidServer(name, "oauth." + field + " must be a non-empty string");
                            }
                        }
                        JsonNode callbackPort = oauthObject.get("callbackPort");
                        if (callbackPort != null && !callbackPort.isNull()
                                && (!callbackPort.isIntegralNumber()
                                || !callbackPort.canConvertToInt()
                                || callbackPort.asInt() < 1
                                || callbackPort.asInt() > 65_535)) {
                            throw mcpInvalidServer(name, "oauth.callbackPort must be an integer from 1 to 65535");
                        }
                        JsonNode redirect = oauthObject.get("redirectUri");
                        if (redirect != null && !redirect.isNull()) {
                            if (!redirect.isTextual() || redirect.asText().isBlank()) {
                                throw mcpInvalidServer(name, "oauth.redirectUri must be a non-empty string");
                            }
                            try {
                                URI uri = new URI(redirect.asText());
                                String host = uri.getHost();
                                if (!uri.isAbsolute()
                                        || host == null
                                        || !uri.getScheme().equalsIgnoreCase("http")
                                        || !(host.equalsIgnoreCase("localhost") || host.startsWith("127.") || host.equals("::1"))
                                        || uri.getFragment() != null) {
                                    throw mcpInvalidServer(name, "oauth.redirectUri must be an HTTP loopback URL without a fragment");
                                }
                            } catch (URISyntaxException error) {
                                throw mcpInvalidServer(name, "oauth.redirectUri is invalid: " + redirect.asText());
                            }
                        }
                    }
                    yield new McpServerConfig.Remote(
                            url,
                            Map.copyOf(mcpStringMap(name, server, "headers")),
                            oauth == null ? null : oauth.deepCopy(),
                            enabled,
                            timeout,
                            List.copyOf(resultFilters),
                            List.copyOf(disabledTools));
                }
                default -> throw mcpInvalidServer(name, "type must be local or remote");
            });
        }
        return new McpConfiguration(new LinkedHashMap<>(servers), List.of(settingsPath));
    }

    private static Map<String, String> mcpStringMap(String server, ObjectNode value, String field) throws IOException {
        JsonNode node = value.get(field);
        if (node == null || node.isNull()) return Map.of();
        if (!node.isObject()) throw mcpInvalidServer(server, field + " must be an object of string values");
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (var entry : node.properties()) {
            if (!entry.getValue().isTextual()) {
                throw mcpInvalidServer(server, field + "." + entry.getKey() + " must be a string");
            }
            result.put(entry.getKey(), entry.getValue().asText());
        }
        return result;
    }

    private static String mcpOptionalText(String server, ObjectNode value, String field) throws IOException {
        JsonNode node = value.get(field);
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) throw mcpInvalidServer(server, field + " must be a string");
        return node.asText();
    }

    private static Path mcpResolvePath(Path base, String value) {
        Path path = Path.of(value);
        return (path.isAbsolute() ? path : base.resolve(path)).toAbsolutePath().normalize();
    }

    private static IOException mcpInvalidServer(String server, String message) {
        return new IOException("Invalid MCP server \"" + server + "\": " + message);
    }

    // --------------------------------------------------------- mcp transport

    private static <T> T mcpAwaitFuture(
            CompletableFuture<T> future,
            Duration timeout,
            AbortSignal signal,
            String cancelledMessage,
            String timeoutPrefix,
            boolean cancelOnAbortOrTimeout)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            if ((signal != null && isAborted(signal)) || Thread.currentThread().isInterrupted()) {
                if (cancelOnAbortOrTimeout) future.cancel(true);
                throw new InterruptedException(cancelledMessage);
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                if (cancelOnAbortOrTimeout) future.cancel(true);
                throw new TimeoutException(timeoutPrefix + " timed out after " + timeout.toMillis() + "ms");
            }
            try {
                return future.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS);
            } catch (TimeoutException ignored) {
                // Poll cooperative cancellation.
            } catch (InterruptedException error) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw error;
            } catch (ExecutionException error) {
                Throwable cause = error.getCause();
                if (cause instanceof Exception exception) throw exception;
                throw new IOException(String.valueOf(cause), cause);
            }
        }
    }

    /**
     * Sends one JSON-RPC request over the transport that owns this connection.
     */
    private JsonNode mcpTransportRequest(
            McpTransport transport, String method, ObjectNode params, Duration timeout, AbortSignal signal)
            throws Exception {
        return switch (transport) {
            case StdioMcpTransport stdio -> {
                long id = stdio.nextId.getAndIncrement();
                CompletableFuture<JsonNode> response = new CompletableFuture<>();
                stdio.pending.put(id, response);
                ObjectNode message = jsonObject().put("jsonrpc", "2.0").put("id", id).put("method", method);
                if (params != null) message.set("params", params);
                try {
                    mcpStdioWrite(stdio, message);
                } catch (Exception error) {
                    stdio.pending.remove(id);
                    throw error;
                }

                try {
                    yield mcpAwaitFuture(
                            response,
                            timeout,
                            signal,
                            "MCP request cancelled",
                            "MCP request " + method,
                            false);
                } catch (TimeoutException error) {
                    mcpStdioCancel(stdio, id, "Request timed out");
                    throw error;
                } catch (InterruptedException error) {
                    mcpStdioCancel(stdio, id, "Request cancelled");
                    throw error;
                } finally {
                    stdio.pending.remove(id);
                }
            }
            case StreamableHttpMcpTransport http -> {
                long id = http.nextId.getAndIncrement();
                ObjectNode envelope = jsonObject().put("jsonrpc", "2.0").put("id", id).put("method", method);
                if (params != null) envelope.set("params", params);
                HttpResponse<String> response = mcpStreamablePost(http, envelope, timeout, signal, true);
                for (JsonNode message : mcpStreamableResponseMessages(http, response)) {
                    JsonNode value = message.get("id");
                    if (value != null
                            && value.canConvertToLong()
                            && value.asLong() == id
                            && message.get("method") == null) {
                        JsonNode error = message.get("error");
                        if (error != null && !error.isNull()) throw mcpRpcError(error, true);
                        yield message.get("result");
                    }
                    mcpStreamableDispatchServerMessage(http, message, timeout);
                }
                throw new IOException("MCP HTTP response did not contain JSON-RPC result for " + method);
            }
            case SseHttpMcpTransport sse -> {
                URI target = mcpAwaitSse(sse.endpoint, timeout, signal);
                long id = sse.nextId.getAndIncrement();
                CompletableFuture<JsonNode> result = new CompletableFuture<>();
                sse.pending.put(id, result);
                ObjectNode envelope = jsonObject().put("jsonrpc", "2.0").put("id", id).put("method", method);
                if (params != null) envelope.set("params", params);
                try {
                    mcpSsePost(sse, target, envelope, timeout, signal, true);
                    yield mcpAwaitSse(result, timeout, signal);
                } catch (TimeoutException | InterruptedException error) {
                    try {
                        mcpSseNotify(
                                sse,
                                "notifications/cancelled",
                                jsonObject().put("requestId", id).put("reason", error.getMessage()));
                    } catch (Exception ignored) {
                    }
                    throw error;
                } finally {
                    sse.pending.remove(id);
                }
            }
        };
    }

    /**
     * Releases the process, sockets, and streams owned by this transport.
     */
    private void mcpTransportClose(McpTransport transport) {
        switch (transport) {
            case StdioMcpTransport stdio -> {
                if (!stdio.closed) {
                    stdio.closed = true;
                    mcpFailPending(stdio.pending, new IOException("MCP stdio transport closed"));
                    try {
                        stdio.writer.close();
                    } catch (IOException ignored) {
                    }
                    stdio.process.descendants().forEach(handle -> {
                        try {
                            handle.destroy();
                        } catch (RuntimeException ignored) {
                        }
                    });
                    stdio.process.destroy();
                    try {
                        if (!stdio.process.waitFor(300, TimeUnit.MILLISECONDS)) {
                            stdio.process.descendants().forEach(ProcessHandle::destroyForcibly);
                            stdio.process.destroyForcibly();
                        }
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        stdio.process.destroyForcibly();
                    }
                }
            }
            case StreamableHttpMcpTransport http -> {
                if (!http.closed) {
                    http.closed = true;
                    if (http.listenerRequest != null) http.listenerRequest.cancel(true);
                    if (http.listenerStream != null) {
                        try {
                            http.listenerStream.close();
                        } catch (IOException ignored) {
                        }
                    }
                    if (http.sessionId != null) {
                        try {
                            String bearer = http.oauth == null ? null : mcpAccessToken(http.oauth);
                            HttpRequest request = mcpHttpRequestBuilder(
                                    http.url, Duration.ofSeconds(2), bearer, http.headers, http.sessionId, http.protocolVersion)
                                    .DELETE()
                                    .build();
                            http.client.sendAsync(request, HttpResponse.BodyHandlers.discarding());
                        } catch (Exception ignored) {
                            // Session deletion is best effort.
                        }
                    }
                }
            }
            case SseHttpMcpTransport sse -> {
                if (!sse.closed) {
                    sse.closed = true;
                    if (sse.opening != null) sse.opening.cancel(true);
                    mcpFailPending(sse.pending, new IOException("MCP SSE transport closed"));
                    sse.endpoint.completeExceptionally(new IOException("MCP SSE transport closed"));
                    InputStream stream = sse.eventStream;
                    if (stream != null) {
                        try {
                            stream.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
            }
        }
    }

    private static IOException mcpRpcError(JsonNode error, boolean includeData) {
        String message = error.path("message").asText("MCP JSON-RPC error");
        if (error.has("code")) message += " (" + error.path("code").asText() + ")";
        if (includeData && error.has("data")) message += ": " + error.path("data");
        return new IOException(message);
    }

    private static boolean mcpRestrictedHeader(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "content-length", "host", "connection", "upgrade" -> true;
            default -> false;
        };
    }

    private static String mcpAbbreviate(String value) {
        if (value == null) return "";
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500) + "...";
    }

    private static ObjectNode mcpClientResponse(JsonNode id, String method, Path workspace) {
        ObjectNode response = jsonObject().put("jsonrpc", "2.0");
        response.set("id", id);
        if (method.equals("ping")) response.set("result", jsonObject());
        else if (method.equals("roots/list")) {
            ObjectNode result = jsonObject();
            result.putArray("roots")
                    .addObject()
                    .put("uri", workspace.toUri().toString())
                    .put("name", workspace.getFileName() == null
                            ? workspace.toString()
                            : workspace.getFileName().toString());
            response.set("result", result);
        } else response.set(
                "error", jsonObject().put("code", -32601).put("message", "Client does not support " + method));
        return response;
    }

    // --------------------------------------------------- mcp stdio transport

    private void mcpStdioNotify(StdioMcpTransport transport, String method, ObjectNode params)
            throws IOException {
        ObjectNode message = jsonObject().put("jsonrpc", "2.0").put("method", method);
        if (params != null) message.set("params", params);
        mcpStdioWrite(transport, message);
    }

    private void mcpStdioDispatch(StdioMcpTransport transport, JsonNode message) {
        if (message.isArray()) {
            for (JsonNode item : message) mcpStdioDispatch(transport, item);
            return;
        }
        if (!message.isObject()) return;
        JsonNode method = message.get("method");
        JsonNode id = message.get("id");
        if (method != null && method.isTextual()) {
            JsonNode params = message.get("params");
            if (id != null && !id.isNull()) {
                try {
                    mcpStdioWrite(transport, mcpClientResponse(id, method.asText(), transport.workspace));
                } catch (IOException ignored) {
                    // A transport failure is reported to pending client requests by the reader/process watcher.
                }
            } else transport.notificationListener.accept(method.asText(), params);
            return;
        }
        if (id == null || !id.canConvertToLong()) return;
        CompletableFuture<JsonNode> future = transport.pending.get(id.asLong());
        if (future == null) return;
        JsonNode error = message.get("error");
        if (error != null && !error.isNull()) future.completeExceptionally(mcpRpcError(error, true));
        else future.complete(message.get("result"));
    }

    private void mcpStdioCancel(StdioMcpTransport transport, long id, String reason) {
        transport.pending.remove(id);
        try {
            mcpStdioNotify(
                    transport, "notifications/cancelled", jsonObject().put("requestId", id).put("reason", reason));
        } catch (IOException ignored) {
            // Cancellation is best effort.
        }
    }

    private void mcpStdioWrite(StdioMcpTransport transport, JsonNode message) throws IOException {
        if (transport.closed) throw new IOException("MCP stdio transport is closed");
        synchronized (transport.writeLock) {
            transport.writer.write(Json.MAPPER.writeValueAsString(message));
            transport.writer.newLine();
            transport.writer.flush();
        }
    }

    private void mcpStdioAppendStderr(StdioMcpTransport transport, String value) {
        synchronized (transport.stderr) {
            transport.stderr.append(value);
            if (transport.stderr.length() > StdioMcpTransport.STDERR_LIMIT) {
                transport.stderr.delete(0, transport.stderr.length() - StdioMcpTransport.STDERR_LIMIT);
            }
        }
    }

    private static String mcpStdioExitMessage(StdioMcpTransport transport) {
        String detail;
        synchronized (transport.stderr) {
            detail = transport.stderr.toString().trim();
        }
        String status = transport.process.isAlive()
                ? "MCP server closed its stdout"
                : "MCP server exited with code " + transport.process.exitValue();
        return detail.isBlank() ? status : status + ": " + detail;
    }

    private void mcpFailPending(Map<Long, CompletableFuture<JsonNode>> pending, Exception error) {
        for (CompletableFuture<JsonNode> future : pending.values()) future.completeExceptionally(error);
        pending.clear();
    }

    // ---------------------------------------- mcp streamable http transport

    private HttpResponse<String> mcpStreamablePost(
            StreamableHttpMcpTransport transport,
            JsonNode message,
            Duration timeout,
            AbortSignal signal,
            boolean authRetry)
            throws Exception {
        if (transport.closed) throw new IOException("MCP HTTP transport is closed");
        String bearer = transport.oauth == null ? null : mcpAccessToken(transport.oauth);
        HttpRequest.Builder request = mcpHttpRequestBuilder(
                transport.url, timeout, bearer, transport.headers, transport.sessionId, transport.protocolVersion)
                .setHeader("Content-Type", "application/json")
                .setHeader("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(
                        Json.MAPPER.writeValueAsString(message), StandardCharsets.UTF_8));
        CompletableFuture<HttpResponse<String>> future =
                transport.client.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        HttpResponse<String> response = mcpAwaitFuture(
                future,
                timeout,
                signal,
                "MCP HTTP request cancelled",
                "MCP HTTP request",
                true);
        response.headers().firstValue("Mcp-Session-Id").ifPresent(value -> transport.sessionId = value);
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            int status1 = response.statusCode();
            String body = response.body();
            String message1 = switch (status1) {
                case 401 -> "MCP server requires authentication";
                case 403 -> "MCP server rejected the configured credentials";
                case 404 -> "MCP endpoint was not found";
                case 405 -> "MCP endpoint does not support Streamable HTTP";
                default -> "MCP server returned HTTP " + status1;
            };
            if (body != null && !body.isBlank()) message1 += ": " + mcpAbbreviate(body);
            McpHttpException failure = mcpHttpException(
                    status1, response.uri(), response.headers().map(), body, message1 + " (" + transport.url + ")");
            if (authRetry
                    && transport.oauth != null
                    && mcpRefreshAfterUnauthorized(transport.oauth, failure, bearer)) {
                return mcpStreamablePost(transport, message, timeout, signal, false);
            }
            throw failure;
        }
        return response;
    }

    private List<JsonNode> mcpStreamableResponseMessages(
            StreamableHttpMcpTransport transport, HttpResponse<String> response) throws IOException {
        String body = response.body();
        if (body == null || body.isBlank() || response.statusCode() == 202) return List.of();
        String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        if (contentType.contains("text/event-stream") || body.stripLeading().startsWith("event:")) {
            List<JsonNode> result = new ArrayList<>();
            StringBuilder data = new StringBuilder();
            for (String line : body.split("\\R", -1)) {
                if (line.isEmpty()) {
                    mcpAddSseData(result, data);
                    continue;
                }
                if (line.startsWith("data:")) {
                    if (!data.isEmpty()) data.append('\n');
                    data.append(line.substring(5).stripLeading());
                }
            }
            mcpAddSseData(result, data);
            return result;
        }
        JsonNode parsed;
        try {
            parsed = Json.MAPPER.readTree(body);
        } catch (IOException error) {
            throw new IOException(
                    "Invalid JSON response from MCP server at " + transport.url + ": " + mcpAbbreviate(body), error);
        }
        if (parsed.isArray()) {
            List<JsonNode> result = new ArrayList<>();
            parsed.forEach(result::add);
            return result;
        }
        return List.of(parsed);
    }

    private void mcpAddSseData(List<JsonNode> result, StringBuilder data) throws IOException {
        if (data.isEmpty()) return;
        JsonNode parsed = Json.MAPPER.readTree(data.toString());
        if (parsed.isArray()) parsed.forEach(result::add);
        else result.add(parsed);
        data.setLength(0);
    }

    private void mcpStreamableDispatchServerMessage(
            StreamableHttpMcpTransport transport, JsonNode message, Duration timeout) {
        if (!message.isObject() || !message.path("method").isTextual()) return;
        String method = message.path("method").asText();
        JsonNode id = message.get("id");
        JsonNode params = message.get("params");
        if (id == null || id.isNull()) {
            transport.notificationListener.accept(method, params);
            return;
        }
        try {
            mcpStreamablePost(transport, mcpClientResponse(id, method, transport.workspace), timeout, null, true);
        } catch (Exception ignored) {
            // The original request will report a transport failure if one occurs.
        }
    }

    private void mcpStreamableDispatchListenerData(StreamableHttpMcpTransport transport, StringBuilder data) {
        if (data.isEmpty()) return;
        try {
            JsonNode message = Json.MAPPER.readTree(data.toString());
            if (message.isArray()) {
                message.forEach(item -> mcpStreamableDispatchServerMessage(transport, item, Duration.ofSeconds(10)));
            } else {
                mcpStreamableDispatchServerMessage(transport, message, Duration.ofSeconds(10));
            }
        } catch (IOException ignored) {
            // Ignore malformed optional server events; request responses still use POST.
        } finally {
            data.setLength(0);
        }
    }

    private static HttpRequest.Builder mcpHttpRequestBuilder(
            URI target,
            Duration timeout,
            String bearer,
            Map<String, String> headers,
            String sessionId,
            String protocolVersion) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(target);
        if (timeout != null) builder.timeout(timeout);
        for (var header : headers.entrySet()) {
            if (!mcpRestrictedHeader(header.getKey())) builder.header(header.getKey(), header.getValue());
        }
        if (bearer != null) builder.setHeader("Authorization", "Bearer " + bearer);
        if (sessionId != null) builder.setHeader("Mcp-Session-Id", sessionId);
        if (protocolVersion != null) builder.setHeader("MCP-Protocol-Version", protocolVersion);
        return builder;
    }

    // ----------------------------------------------------- mcp sse transport

    private void mcpSseOpenEventStream(SseHttpMcpTransport transport, boolean authRetry) {
        String bearer;
        try {
            bearer = transport.oauth == null ? null : mcpAccessToken(transport.oauth);
        } catch (Exception error) {
            transport.endpoint.completeExceptionally(error);
            return;
        }
        // The GET body is the long-lived event stream. Since JDK 26 an
        // HttpRequest timeout also caps consuming the body, so bound only the
        // wait for response headers.
        HttpRequest request = mcpHttpRequestBuilder(
                transport.url,
                null,
                bearer,
                transport.headers,
                transport.sessionId,
                transport.protocolVersion)
                .setHeader("Accept", "text/event-stream")
                .GET()
                .build();
        CompletableFuture<HttpResponse<InputStream>> opening =
                transport.client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        transport.opening = opening;
        CompletableFuture.delayedExecutor(MCP_SSE_OPEN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).execute(() -> {
            if (!opening.isDone()) {
                transport.endpoint.completeExceptionally(new HttpTimeoutException(
                        "Timed out after " + MCP_SSE_OPEN_TIMEOUT.toSeconds() + " s opening MCP SSE event stream"));
                opening.cancel(true);
            }
        });
        opening.whenComplete((response, error) -> {
            if (transport.closed) {
                if (response != null) {
                    try {
                        response.body().close();
                    } catch (IOException ignored) {
                    }
                }
                return;
            }
            if (error != null) {
                transport.endpoint.completeExceptionally(error);
                mcpFailPending(transport.pending, new IOException("Failed to open MCP SSE stream", error));
                return;
            }
            mcpCaptureSseSession(transport, response);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String body;
                InputStream stream = response.body();
                try (stream) {
                    byte[] bytes = stream.readNBytes(4_001);
                    String value = new String(bytes, 0, Math.min(bytes.length, 4_000), StandardCharsets.UTF_8);
                    body = bytes.length > 4_000 ? value + "..." : value;
                } catch (IOException ignored) {
                    body = "";
                }
                McpHttpException failure = mcpSseHttpError(
                        response.statusCode(), response.uri(), response.headers().map(), body, transport.url);
                try {
                    if (authRetry
                            && transport.oauth != null
                            && mcpRefreshAfterUnauthorized(transport.oauth, failure, bearer)) {
                        mcpSseOpenEventStream(transport, false);
                        return;
                    }
                } catch (Exception refreshError) {
                    transport.endpoint.completeExceptionally(refreshError);
                    mcpFailPending(transport.pending, refreshError);
                    return;
                }
                transport.endpoint.completeExceptionally(failure);
                mcpFailPending(transport.pending, failure);
                return;
            }
            transport.eventStream = response.body();
            Thread.ofVirtual().name("mcp-sse-reader").start(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    String event = "message";
                    StringBuilder data = new StringBuilder();
                    String line;
                    while (!transport.closed && (line = reader.readLine()) != null) {
                        if (line.isEmpty()) {
                            mcpSseDispatchEvent(transport, event, data.toString());
                            event = "message";
                            data.setLength(0);
                        } else if (line.startsWith("event:")) {
                            event = line.substring(6).strip();
                        } else if (line.startsWith("data:")) {
                            if (!data.isEmpty()) data.append('\n');
                            data.append(line.substring(5).stripLeading());
                        }
                    }
                    if (!data.isEmpty()) mcpSseDispatchEvent(transport, event, data.toString());
                    if (!transport.closed) mcpFailPending(
                            transport.pending, new IOException("MCP SSE event stream closed"));
                } catch (Exception error1) {
                    if (!transport.closed) {
                        transport.endpoint.completeExceptionally(error1);
                        mcpFailPending(transport.pending, error1);
                    }
                }
            });
        });
    }

    private void mcpSseNotify(SseHttpMcpTransport transport, String method, ObjectNode params) throws Exception {
        URI target = mcpAwaitSse(transport.endpoint, Duration.ofSeconds(10), null);
        ObjectNode envelope = jsonObject().put("jsonrpc", "2.0").put("method", method);
        if (params != null) envelope.set("params", params);
        mcpSsePost(transport, target, envelope, Duration.ofSeconds(10), null, true);
    }

    private void mcpSsePost(
            SseHttpMcpTransport transport,
            URI target,
            JsonNode message,
            Duration timeout,
            AbortSignal signal,
            boolean authRetry)
            throws Exception {
        String bearer = transport.oauth == null ? null : mcpAccessToken(transport.oauth);
        HttpRequest request = mcpHttpRequestBuilder(
                target, timeout, bearer, transport.headers, transport.sessionId, transport.protocolVersion)
                .setHeader("Content-Type", "application/json")
                .setHeader("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        Json.MAPPER.writeValueAsString(message), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = mcpAwaitSse(
                transport.client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)),
                timeout,
                signal);
        mcpCaptureSseSession(transport, response);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            McpHttpException failure = mcpSseHttpError(
                    response.statusCode(), response.uri(), response.headers().map(), response.body(), target);
            if (authRetry
                    && transport.oauth != null
                    && mcpRefreshAfterUnauthorized(transport.oauth, failure, bearer)) {
                mcpSsePost(transport, target, message, timeout, signal, false);
                return;
            }
            throw failure;
        }
        if (response.body() != null && !response.body().isBlank()) {
            mcpSseDispatch(transport, Json.MAPPER.readTree(response.body()));
        }
    }

    private void mcpSseDispatchEvent(SseHttpMcpTransport transport, String event, String data)
            throws IOException {
        if (data.isBlank()) return;
        if (event.equals("endpoint")) {
            try {
                transport.endpoint.complete(transport.url.resolve(data.strip()));
            } catch (IllegalArgumentException error) {
                transport.endpoint.completeExceptionally(
                        new IOException("Invalid MCP SSE message endpoint: " + data, error));
            }
            return;
        }
        mcpSseDispatch(transport, Json.MAPPER.readTree(data));
    }

    private void mcpSseDispatch(SseHttpMcpTransport transport, JsonNode message) {
        if (message == null) return;
        if (message.isArray()) {
            message.forEach(item -> mcpSseDispatch(transport, item));
            return;
        }
        if (!message.isObject()) return;
        JsonNode methodNode = message.get("method");
        JsonNode id = message.get("id");
        if (methodNode != null && methodNode.isTextual()) {
            String method = methodNode.asText();
            JsonNode params = message.get("params");
            if (id == null || id.isNull()) transport.notificationListener.accept(method, params);
            else {
                ObjectNode response = mcpClientResponse(id, method, transport.workspace);
                transport.endpoint.thenAccept(target -> Thread.ofVirtual().start(() -> {
                    try {
                        mcpSsePost(transport, target, response, Duration.ofSeconds(10), null, true);
                    } catch (Exception ignored) {
                    }
                }));
            }
            return;
        }
        if (id == null || !id.canConvertToLong()) return;
        CompletableFuture<JsonNode> result = transport.pending.get(id.asLong());
        if (result == null) return;
        JsonNode error = message.get("error");
        if (error != null && !error.isNull()) result.completeExceptionally(mcpRpcError(error, false));
        else result.complete(message.get("result"));
    }

    private void mcpCaptureSseSession(SseHttpMcpTransport transport, HttpResponse<?> response) {
        response.headers().firstValue("Mcp-Session-Id").ifPresent(value -> transport.sessionId = value);
    }

    private static <T> T mcpAwaitSse(CompletableFuture<T> future, Duration timeout, AbortSignal signal)
            throws Exception {
        return mcpAwaitFuture(
                future, timeout, signal, "MCP request cancelled", "MCP request", false);
    }

    private static McpHttpException mcpSseHttpError(
            int status, URI responseUri, Map<String, List<String>> headers, String body, URI target) {
        String message = switch (status) {
            case 401 -> "MCP server requires authentication";
            case 403 -> "MCP server rejected the configured credentials";
            default -> "MCP SSE endpoint returned HTTP " + status;
        };
        if (body != null && !body.isBlank()) message += ": " + mcpAbbreviate(body);
        return mcpHttpException(status, responseUri, headers, body, message + " (" + target + ")");
    }

    // ------------------------------------------------------ mcp http failure

    /**
     * Builds an MCP HTTP failure with its response headers normalized to lower case.
     */
    private static McpHttpException mcpHttpException(
            int status, URI uri, Map<String, List<String>> headers, String body, String message) {
        LinkedHashMap<String, List<String>> copied = new LinkedHashMap<>();
        headers.forEach((name, values) -> copied.put(name.toLowerCase(Locale.ROOT), List.copyOf(values)));
        return new McpHttpException(status, uri, Map.copyOf(copied), body == null ? "" : body, message);
    }

    /**
     * Finds the MCP HTTP failure in a cause or suppressed chain.
     */
    private static McpHttpException mcpFindHttpException(Throwable error) {
        return mcpFindHttpException(error, new ArrayList<>());
    }

    private static McpHttpException mcpFindHttpException(Throwable error, List<Throwable> visited) {
        if (error == null || visited.contains(error)) return null;
        visited.add(error);
        if (error instanceof McpHttpException http) return http;
        McpHttpException cause = mcpFindHttpException(error.getCause(), visited);
        if (cause != null) return cause;
        for (Throwable suppressed : error.getSuppressed()) {
            McpHttpException found = mcpFindHttpException(suppressed, visited);
            if (found != null) return found;
        }
        return null;
    }

    // ----------------------------------------------------------- mcp client

    private McpClient mcpConnectRemote(
            McpServerConfig.Remote remote, Path workspace, Duration timeout, McpOAuthSession oauth)
            throws Exception {
        Exception streamableFailure;
        StreamableHttpMcpTransport streamable = new StreamableHttpMcpTransport();
        streamable.url = remote.url;
        streamable.headers = remote.headers;
        streamable.oauth = oauth;
        streamable.workspace = workspace.toAbsolutePath().normalize();
        try {
            return mcpInitializeOwned(streamable, timeout);
        } catch (Exception error) {
            streamableFailure = error;
            if (error instanceof InterruptedException || Thread.currentThread().isInterrupted()) throw error;
            if (oauth != null && mcpIsOAuthChallenge(error)) throw error;
        }
        SseHttpMcpTransport sse = new SseHttpMcpTransport();
        sse.url = remote.url;
        sse.headers = remote.headers;
        sse.oauth = oauth;
        sse.workspace = workspace.toAbsolutePath().normalize();
        mcpSseOpenEventStream(sse, true);
        try {
            return mcpInitializeOwned(sse, timeout);
        } catch (Exception error) {
            error.addSuppressed(streamableFailure);
            throw error;
        }
    }

    private McpClient mcpInitializeOwned(McpTransport transport, Duration timeout) throws Exception {
        try {
            ObjectNode params = jsonObject().put("protocolVersion", McpClient.PROTOCOL_VERSION);
            params.putObject("capabilities").putObject("roots");
            params.putObject("clientInfo").put("name", "codingagent").put("version", "0.1.0-java");
            JsonNode resultNode = mcpTransportRequest(transport, "initialize", params, timeout, null);
            if (!(resultNode instanceof ObjectNode result)) {
                throw new IOException("MCP initialize returned no result object");
            }
            String negotiated = result.path("protocolVersion").isTextual()
                    ? result.path("protocolVersion").asText()
                    : McpClient.PROTOCOL_VERSION;
            switch (transport) {
                case StdioMcpTransport ignored1 -> {
                }
                case StreamableHttpMcpTransport http1 -> http1.protocolVersion = negotiated;
                case SseHttpMcpTransport sse1 -> sse1.protocolVersion = negotiated;
            }
            McpClient client = new McpClient();
            client.transport = transport;
            client.timeout = timeout;
            client.capabilities = result.path("capabilities") instanceof ObjectNode object
                    ? object.deepCopy()
                    : jsonObject();
            client.serverName = result.path("serverInfo").path("name").isTextual()
                    ? result.path("serverInfo").path("name").asText()
                    : null;
            client.instructions = result.path("instructions").isTextual()
                    ? result.path("instructions").asText().trim()
                    : null;
            switch (transport) {
                case StdioMcpTransport stdio -> mcpStdioNotify(stdio, "notifications/initialized", jsonObject());
                case StreamableHttpMcpTransport http -> {
                    ObjectNode envelope = jsonObject()
                            .put("jsonrpc", "2.0")
                            .put("method", "notifications/initialized");
                    envelope.set("params", jsonObject());
                    HttpResponse<String> response =
                            mcpStreamablePost(http, envelope, Duration.ofSeconds(10), null, true);
                    for (JsonNode message : mcpStreamableResponseMessages(http, response)) {
                        mcpStreamableDispatchServerMessage(http, message, Duration.ofSeconds(10));
                    }
                    if (response.statusCode() == 202 && !http.closed && http.listenerRequest == null) {
                        try {
                            String bearer = http.oauth == null ? null : mcpAccessToken(http.oauth);
                            HttpRequest request = mcpHttpRequestBuilder(
                                    http.url,
                                    null,
                                    bearer,
                                    http.headers,
                                    http.sessionId,
                                    http.protocolVersion)
                                    .setHeader("Accept", "text/event-stream")
                                    .GET()
                                    .build();
                            CompletableFuture<HttpResponse<InputStream>> future =
                                    http.client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
                            http.listenerRequest = future;
                            future.whenComplete((listener, error) -> {
                                if (http.closed || error != null || listener == null) return;
                                if (listener.statusCode() < 200 || listener.statusCode() >= 300) {
                                    try {
                                        listener.body().close();
                                    } catch (IOException ignored) {
                                    }
                                    return;
                                }
                                http.listenerStream = listener.body();
                                Thread.ofVirtual().name("mcp-http-listener").start(() -> {
                                    try (BufferedReader input = new BufferedReader(new InputStreamReader(
                                            listener.body(), StandardCharsets.UTF_8))) {
                                        StringBuilder data = new StringBuilder();
                                        String line;
                                        while (!http.closed && (line = input.readLine()) != null) {
                                            if (line.isEmpty()) mcpStreamableDispatchListenerData(http, data);
                                            else if (line.startsWith("data:")) {
                                                if (!data.isEmpty()) data.append('\n');
                                                data.append(line.substring(5).stripLeading());
                                            }
                                        }
                                        mcpStreamableDispatchListenerData(http, data);
                                    } catch (IOException ignored) {
                                        // The optional GET stream may be unavailable or close at any time.
                                    }
                                });
                            });
                        } catch (Exception ignored) {
                        }
                    }
                }
                case SseHttpMcpTransport sse -> mcpSseNotify(sse, "notifications/initialized", jsonObject());
            }
            return client;
        } catch (Exception error) {
            mcpTransportClose(transport);
            throw error;
        }
    }

    /**
     * Reads the full paginated tool catalog advertised by a connected server.
     */
    private List<McpClient.ToolDefinition> mcpListTools(McpClient client) throws Exception {
        if (!client.capabilities.has("tools")) return List.of();
        List<McpClient.ToolDefinition> result = new ArrayList<>();
        Set<String> cursors = new HashSet<>();
        String cursor = null;
        for (int page = 0; page < McpClient.MAX_LIST_PAGES; page++) {
            ObjectNode params = jsonObject();
            if (cursor != null) params.put("cursor", cursor);
            JsonNode response = mcpTransportRequest(client.transport, "tools/list", params, client.timeout, null);
            if (response == null || !response.isObject() || !response.path("tools").isArray()) {
                throw new IOException("MCP tools/list returned an invalid result");
            }
            for (JsonNode tool : response.path("tools")) {
                if (!tool.isObject() || !tool.path("name").isTextual() || tool.path("name").asText().isBlank()) {
                    throw new IOException("MCP tools/list returned a tool without a name");
                }
                String description = tool.path("description").isTextual() ? tool.path("description").asText() : "";
                ObjectNode inputSchema = tool.path("inputSchema") instanceof ObjectNode object
                        ? object.deepCopy()
                        : jsonObject();
                inputSchema.put("type", "object");
                if (!(inputSchema.get("properties") instanceof ObjectNode)) inputSchema.set("properties", jsonObject());
                // Match OpenCode's dynamic MCP tool conversion. It keeps schemas bounded
                // and avoids providers rejecting unspecified object properties.
                inputSchema.put("additionalProperties", false);
                result.add(new McpClient.ToolDefinition(tool.path("name").asText(), description, inputSchema));
            }
            JsonNode next = response.get("nextCursor");
            if (next == null || next.isNull()) return List.copyOf(result);
            if (!next.isTextual()) throw new IOException("MCP tools/list nextCursor must be a string");
            cursor = next.asText();
            if (!cursors.add(cursor)) throw new IOException("MCP tools/list returned duplicate cursor: " + cursor);
        }
        throw new IOException("MCP tools/list exceeded " + McpClient.MAX_LIST_PAGES + " pages");
    }

    /**
     * Closes the session and the transport it owns.
     */
    private void mcpCloseClient(McpClient client) {
        mcpTransportClose(client.transport);
    }

    // ---------------------------------------------------------- mcp manager

    public List<McpServerStatus> mcpStatuses() {
        return servers.values().stream()
                .sorted(Comparator.comparing(runtime -> runtime.name))
                .map(CodingAgentOperations::mcpSnapshot)
                .toList();
    }

    public boolean toggleMcpServer(String name) {
        McpServerStatus status;

        McpRuntime runtime2 = mcpRequireRuntime(name);
        McpState state;
        synchronized (runtime2.lock) {
            state = runtime2.state;
        }
        if (state == McpState.CONNECTED
                || state == McpState.CONNECTING
                || state == McpState.AUTHENTICATING) {
            McpRuntime runtime1 = mcpRequireRuntime(name);
            McpClient client;
            Thread connector;
            synchronized (runtime1.lock) {
                runtime1.generation++;
                connector = runtime1.connector;
                runtime1.connector = null;
                client = runtime1.client;
                runtime1.client = null;
                runtime1.tools = List.of();
                runtime1.enabled = false;
                runtime1.state = McpState.DISABLED;
                runtime1.message = null;
                runtime1.authorizationUrl = null;
            }
            if (connector != null)
                connector.interrupt();
            if (client != null)
                mcpCloseClient(client);
            status = mcpSnapshot(runtime1);
        } else {
            McpRuntime runtime1 = mcpRequireRuntime(name);
            mcpStartConnect(runtime1, true);
            status = mcpSnapshot(runtime1);
        }
        boolean result1;
        McpRuntime runtime = mcpRequireRuntime(status.name);
        synchronized (runtime.lock) {
            result1 = runtime.enabled;
        }
        return result1;
    }

    public McpToolStatus toggleMcpTool(String serverName, String toolName) {
        McpToolStatus result1;
        McpRuntime runtime = mcpRequireRuntime(serverName);
        synchronized (runtime.lock) {
            if (runtime.state != McpState.CONNECTED || runtime.client == null) {
                throw new IllegalStateException("MCP server is not connected: " + serverName);
            }
            McpClient.ToolDefinition definition = runtime.tools.stream()
                    .filter(tool -> tool.name.equals(toolName))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "MCP tool is not available from " + serverName + ": " + toolName));
            boolean enabled;
            if (runtime.disabledTools.remove(toolName)) {
                enabled = true;
            } else {
                runtime.disabledTools.add(toolName);
                enabled = false;
            }
            result1 = new McpToolStatus(runtime.name, definition.name, definition.description, enabled);
        }
        return result1;
    }

    public List<McpToolStatus> mcpToolStatuses(String serverName) {
        List<McpToolStatus> tools;
        McpRuntime runtime = mcpRequireRuntime(serverName);
        synchronized (runtime.lock) {
            if (runtime.state != McpState.CONNECTED || runtime.client == null) {
                tools = List.of();
            } else {
                tools = runtime.tools.stream()
                        .map(tool1 -> new McpToolStatus(
                                runtime.name, tool1.name, tool1.description, !runtime.disabledTools.contains(tool1.name)))
                        .toList();
            }
        }
        return tools;
    }

    /**
     * Builds a manager for the MCP servers in codingagent's settings file.
     */
    void mcpLoadDefaultManager(Path workspace) throws IOException {
        mcpCreateManager(
                mcpLoadConfiguration(new McpConfigLoader(
                        applicationPaths.settingsFile(), Map.copyOf(System.getenv()))),
                workspace);
    }

    /**
     * Builds a manager and starts connecting every enabled server.
     */
    private void mcpOAuthStore(
            Path path, Path lockPath, List<Path> importPaths) {
        this.path = path;
        this.mcpAuthLockPath = lockPath;
        this.importPaths = importPaths;
    }

    public static HttpClient newHttpClient() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15));
        Authenticator auth = Authenticator.getDefault();
        if (auth != null) builder.authenticator(auth);
        return builder.build();
    }

    private void mcpOAuthClient(HttpClient http, Predicate<URI> browser, Duration callbackTimeout) {
        this.http = http;
        this.browser = browser;
        this.callbackTimeout = callbackTimeout;
    }

    public void mcpCreateManager(McpConfiguration configuration, Path workspace) {
        requireOpen();
        mcpCloseManager();
        servers = new LinkedHashMap<>();
        closed = false;
        HttpClient http = newHttpClient();
        Path home = applicationPaths.homeDirectory();
        String xdg = System.getenv("XDG_DATA_HOME");
        Path openCodeData = xdg == null || xdg.isBlank()
                ? home.resolve(".local/share/opencode/mcp-auth.json")
                : Path.of(xdg).resolve("opencode/mcp-auth.json");
        Path resolved = applicationPaths.mcpAuthFile();
        mcpOAuthStore(
                resolved,
                resolved.resolveSibling(resolved.getFileName() + ".lock"),
                List.of(openCodeData, home.resolve("Library/Application Support/opencode/mcp-auth.json"))
                        .stream()
                        .map(value -> value.toAbsolutePath().normalize())
                        .toList());
        this.mcpOAuthClient(Objects.requireNonNull(http, "http"), Objects.requireNonNull(uri -> {
                    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
                    List<String> command;
                    if (os.contains("mac")) command = List.of("open", uri.toString());
                    else if (os.contains("win"))
                        command = List.of("rundll32", "url.dll,FileProtocolHandler", uri.toString());
                    else command = List.of("xdg-open", uri.toString());
                    try {
                        new ProcessBuilder(command)
                                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                                .redirectError(ProcessBuilder.Redirect.DISCARD)
                                .start();
                        return true;
                    } catch (IOException | RuntimeException ignored) {
                        return false;
                    }
                }, "browser"), Objects.requireNonNull(MCP_OAUTH_DEFAULT_CALLBACK_TIMEOUT, "callbackTimeout"));
        this.workspace = workspace.toAbsolutePath().normalize();
        configuration.servers.forEach((name, config) -> {
            McpRuntime runtime = new McpRuntime(name, config);
            runtime.disabledTools.addAll(switch ((McpServerConfig) config) {
                case McpServerConfig.Local local -> local.disabledTools;
                case McpServerConfig.Remote remote -> remote.disabledTools;
            });
            runtime.enabled = mcpConfigEnabled(config);
            runtime.state = runtime.enabled ? McpState.CONNECTING : McpState.DISABLED;
            servers.put(name, runtime);
        });
        for (McpRuntime runtime : servers.values()) {
            // Startup may refresh an existing token, but never opens a browser unexpectedly.
            if (mcpConfigEnabled(runtime.config)) mcpStartConnect(runtime, false);
        }
    }

    /**
     * Snapshots one configured server.
     */
    public McpServerStatus mcpStatus(String name) {
        return mcpSnapshot(mcpRequireRuntime(name));
    }

    /**
     * Waits for all currently-starting configured servers.
     */
    public void mcpAwaitReady() throws InterruptedException {
        while (true) {
            List<Thread> connecting = new ArrayList<>();
            for (McpRuntime runtime : servers.values()) {
                synchronized (runtime.lock) {
                    if (runtime.connector != null) connecting.add(runtime.connector);
                }
            }
            if (connecting.isEmpty()) return;
            for (Thread thread : connecting) thread.join();
        }
    }

    /**
     * Returns the current model-visible tool adapters, with OpenCode-compatible names.
     */
    public List<AgentTool> mcpTools() {
        LinkedHashMap<String, AgentTool> result = new LinkedHashMap<>();
        for (McpRuntime runtime : servers.values()) {
            McpClient client;
            List<McpClient.ToolDefinition> definitions;
            Set<String> disabledTools;
            synchronized (runtime.lock) {
                if (runtime.state != McpState.CONNECTED || runtime.client == null) continue;
                client = runtime.client;
                definitions = runtime.tools;
                disabledTools = Set.copyOf(runtime.disabledTools);
            }
            for (McpClient.ToolDefinition definition : definitions) {
                if (disabledTools.contains(definition.name)) continue;
                List<McpResultFilter> resultFilters = switch (runtime.config) {
                    case McpServerConfig.Local local -> local.resultFilters;
                    case McpServerConfig.Remote remote -> remote.resultFilters;
                };
                McpAgentTool tool = new McpAgentTool(
                        runtime.name,
                        definition,
                        client,
                        List.copyOf(resultFilters),
                        mcpSanitizeName(runtime.name) + "_" + mcpSanitizeName(definition.name));
                result.put(tool.name, tool);
            }
        }
        return List.copyOf(result.values());
    }

    /**
     * Disconnects every server and releases the manager's sessions.
     */
    public void mcpCloseManager() {
        if (closed) return;
        closed = true;
        servers.values().forEach(runtime -> {
            McpClient client;
            Thread connector;
            synchronized (runtime.lock) {
                runtime.generation++;
                connector = runtime.connector;
                runtime.connector = null;
                client = runtime.client;
                runtime.client = null;
                runtime.tools = List.of();
                runtime.state = McpState.DISABLED;
                runtime.authorizationUrl = null;
            }
            if (connector != null) connector.interrupt();
            if (client != null) mcpCloseClient(client);
            mcpSnapshot(runtime);
        });
        if (http != null) {
            http.shutdownNow();
            http = null;
        }
    }

    private void mcpFailConnect(McpRuntime runtime, long generation, McpState state, Exception error) {
        synchronized (runtime.lock) {
            if (closed || runtime.generation != generation) return;
            runtime.state = state;
            String message = error.getMessage();
            if (message == null || message.isBlank()) message = error.toString();
            runtime.message = message.replaceAll("\\s+", " ").trim();
            runtime.authorizationUrl = null;
            runtime.client = null;
            runtime.tools = List.of();
        }
    }

    private void mcpStartConnect(McpRuntime runtime, boolean interactiveOAuth) {
        McpClient previous;
        Thread previousConnector;
        long generation;
        Thread connector;
        synchronized (runtime.lock) {
            if (closed) return;
            runtime.enabled = true;
            if (runtime.state == McpState.CONNECTED && runtime.client != null) return;
            previous = runtime.client;
            previousConnector = runtime.connector;
            runtime.client = null;
            runtime.tools = List.of();
            runtime.state = McpState.CONNECTING;
            runtime.message = null;
            runtime.authorizationUrl = null;
            generation = ++runtime.generation;
            connector = Thread.ofVirtual()
                    .name("mcp-connect-" + mcpSanitizeName(runtime.name))
                    .unstarted(() -> {
                        McpClient candidate = null;
                        try {
                            Consumer<URI> authorizationListener = url -> {
                                synchronized (runtime.lock) {
                                    if (closed || runtime.generation != generation) return;
                                    runtime.state = McpState.AUTHENTICATING;
                                    runtime.message = "Complete OAuth authorization in your browser";
                                    runtime.authorizationUrl = url.toString();
                                }
                            };
                            Long configuredTimeout = switch (runtime.config) {
                                case McpServerConfig.Local local1 -> local1.timeoutMillis;
                                case McpServerConfig.Remote remote1 -> remote1.timeoutMillis;
                            };
                            Duration timeout = configuredTimeout == null
                                    ? McpClient.DEFAULT_TIMEOUT
                                    : Duration.ofMillis(configuredTimeout);
                            if (runtime.config instanceof McpServerConfig.Local local) {
                                StdioMcpTransport transport = new StdioMcpTransport();
                                transport.workspace = workspace.toAbsolutePath().normalize();
                                Path processDirectory = local.cwd == null || local.cwd.isBlank()
                                        ? transport.workspace
                                        : mcpResolvePath(transport.workspace, local.cwd);
                                ProcessBuilder builder = new ProcessBuilder(local.command);
                                builder.directory(processDirectory.toFile());
                                builder.environment().putAll(local.environment);
                                transport.process = builder.start();
                                transport.writer = new BufferedWriter(
                                        new OutputStreamWriter(transport.process.getOutputStream(), StandardCharsets.UTF_8));
                                Thread.ofVirtual().name("mcp-stdio-reader").start(() -> {
                                    try (BufferedReader input = new BufferedReader(
                                            new InputStreamReader(transport.process.getInputStream(), StandardCharsets.UTF_8))) {
                                        String line;
                                        while (!transport.closed && (line = input.readLine()) != null) {
                                            if (line.isBlank()) continue;
                                            JsonNode message;
                                            try {
                                                message = Json.MAPPER.readTree(line);
                                            } catch (IOException malformed) {
                                                mcpStdioAppendStderr(transport, "Invalid JSON on MCP stdout: " + line + "\n");
                                                continue;
                                            }
                                            mcpStdioDispatch(transport, message);
                                        }
                                        if (!transport.closed) {
                                            mcpFailPending(transport.pending, new IOException(mcpStdioExitMessage(transport)));
                                        }
                                    } catch (IOException error) {
                                        if (!transport.closed) mcpFailPending(transport.pending, error);
                                    }
                                });
                                Thread.ofVirtual().name("mcp-stderr-reader").start(() -> {
                                    try (InputStream input = transport.process.getErrorStream()) {
                                        byte[] buffer = new byte[2_048];
                                        int count;
                                        while ((count = input.read(buffer)) >= 0) {
                                            mcpStdioAppendStderr(transport, new String(buffer, 0, count, StandardCharsets.UTF_8));
                                        }
                                    } catch (IOException ignored) {
                                        // Stderr is diagnostic only.
                                    }
                                });
                                transport.process
                                        .onExit()
                                        .thenRun(() -> mcpFailPending(
                                                transport.pending, new IOException(mcpStdioExitMessage(transport))));
                                candidate = mcpInitializeOwned(transport, timeout);
                            } else {
                                McpServerConfig.Remote remote = (McpServerConfig.Remote) runtime.config;
                                McpOAuthSession oauthSession = null;
                                if ((remote.oauth == null || !remote.oauth.isBoolean() || remote.oauth.asBoolean()) &&
                                        !remote.headers.keySet().stream().anyMatch(name1 -> name1.equalsIgnoreCase("Authorization"))) {
                                    McpOAuthSettings settings = remote.oauth instanceof ObjectNode oauth
                                            ? new McpOAuthSettings(
                                            mcpOAuthOptionalText(oauth, "clientId"),
                                            mcpOAuthOptionalText(oauth, "clientSecret"),
                                            mcpOAuthOptionalText(oauth, "scope"),
                                            oauth.path("callbackPort").isIntegralNumber() ? oauth.path("callbackPort").asInt() : null,
                                            oauth.path("redirectUri").isTextual()
                                            ? URI.create(oauth.path("redirectUri").asText())
                                            : null)
                                            : new McpOAuthSettings(null, null, null, null, null);
                                    oauthSession = new McpOAuthSession(runtime.name, remote, settings);
                                }
                                try {
                                    candidate = mcpConnectRemote(remote, workspace, timeout, oauthSession);
                                } catch (Exception error) {
                                    if (oauthSession == null || !mcpIsOAuthChallenge(error)) throw error;
                                    if (!interactiveOAuth) {
                                        McpHttpException http = mcpFindHttpException(error);
                                        McpOAuthChallenge challenge = http == null ? null : mcpOAuthChallenge(http);
                                        throw new McpOAuthRequiredException(
                                                challenge != null && mcpOAuthInsufficientScope(challenge)
                                                        ? "MCP server \"" + runtime.name + "\" requires additional OAuth permissions; press Enter to authorize"
                                                        : "MCP server \"" + runtime.name + "\" requires OAuth authentication; press Enter to authorize");
                                    }
                                    McpHttpException challenge = mcpFindHttpException(error);
                                    McpOAuthChallenge challenge1 = mcpOAuthChallenge(challenge);
                                    if (challenge1 == null) throw challenge;
                                    interactiveLock.lockInterruptibly();
                                    try {
                                        McpOAuthDiscovery discovery = mcpOAuthDiscover(oauthSession.config, challenge1);
                                        String state = mcpRandomUrlToken(32);
                                        String verifier = mcpRandomUrlToken(64);
                                        byte[] result1;
                                        try {
                                            result1 = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
                                        } catch (NoSuchAlgorithmException error2) {
                                            throw new IllegalStateException("SHA-256 is unavailable", error2);
                                        }
                                        String challengeValue = Base64.getUrlEncoder().withoutPadding().encodeToString(result1);
                                        URI redirectUri = mcpOAuthRedirectUri(oauthSession.settings);
                                        if (redirectUri == null || !redirectUri.isAbsolute() || !redirectUri.getScheme().equalsIgnoreCase("http") || redirectUri.getHost() == null) {
                                            throw new IllegalArgumentException("MCP OAuth redirectUri must be an absolute loopback HTTP URL");
                                        }
                                        String host = redirectUri.getHost();
                                        boolean result2;
                                        try {
                                            result2 = InetAddress.getByName(host).isLoopbackAddress();
                                        } catch (IOException error2) {
                                            result2 = false;
                                        }
                                        if (!(host.equalsIgnoreCase("localhost") || result2)) {
                                            throw new IllegalArgumentException("MCP OAuth redirectUri must use localhost or a loopback IP address");
                                        }
                                        if (redirectUri.getFragment() != null) {
                                            throw new IllegalArgumentException("MCP OAuth redirectUri must not contain a fragment");
                                        }
                                        McpOAuthCallback callback = new McpOAuthCallback();
                                        callback.redirectUri = redirectUri;
                                        callback.expectedState = state;
                                        int port = redirectUri.getPort() >= 0 ? redirectUri.getPort() : 80;
                                        InetAddress loopback = InetAddress.getByName(
                                                redirectUri.getHost().equalsIgnoreCase("localhost") ? "127.0.0.1" : redirectUri.getHost());
                                        callback.server = HttpServer.create(new InetSocketAddress(loopback, port), 0);
                                        callback.executor = Executors.newVirtualThreadPerTaskExecutor();
                                        callback.server.setExecutor(callback.executor);
                                        callback.server.createContext("/", exchange -> {
                                            try (exchange) {
                                                String path = callback.redirectUri.getPath();
                                                if (!exchange.getRequestMethod().equals("GET")
                                                        || !exchange.getRequestURI().getPath().equals(path == null || path.isEmpty() ? "/" : path)) {
                                                    mcpRespondToRedirect(exchange, 404, mcpCallbackPage("Not found", false));
                                                    return;
                                                }
                                                Map<String, String> parameters = new LinkedHashMap<>();
                                                String query = exchange.getRequestURI().getRawQuery();
                                                if (query != null && !query.isEmpty()) {
                                                    for (String part : query.split("&")) {
                                                        int separator = part.indexOf('=');
                                                        String name = separator < 0 ? part : part.substring(0, separator);
                                                        String value = separator < 0 ? "" : part.substring(separator + 1);
                                                        parameters.put(
                                                                URLDecoder.decode(name, StandardCharsets.UTF_8),
                                                                URLDecoder.decode(value, StandardCharsets.UTF_8));
                                                    }
                                                }
                                                String returnedState = parameters.get("state");
                                                if (returnedState == null || !MessageDigest.isEqual(
                                                        callback.expectedState.getBytes(StandardCharsets.UTF_8),
                                                        returnedState.getBytes(StandardCharsets.UTF_8))) {
                                                    mcpRespondToRedirect(exchange, 400, mcpCallbackPage(
                                                            "The OAuth state was missing or invalid. Return to codingagent and try again.", false));
                                                    return;
                                                }
                                                String oauthError = parameters.get("error");
                                                if (oauthError != null) {
                                                    String description = parameters.getOrDefault("error_description", oauthError);
                                                    mcpRespondToRedirect(exchange, 200, mcpCallbackPage(description, false));
                                                    callback.code.completeExceptionally(
                                                            new IOException("OAuth authorization was rejected: " + description));
                                                    return;
                                                }
                                                String code = parameters.get("code");
                                                if (code == null || code.isBlank()) {
                                                    mcpRespondToRedirect(exchange, 400, mcpCallbackPage(
                                                            "No authorization code was returned. Return to codingagent and try again.", false));
                                                    return;
                                                }
                                                if (callback.code.isDone()) {
                                                    mcpRespondToRedirect(exchange, 400,
                                                            mcpCallbackPage("This OAuth authorization has already been completed.", false));
                                                    return;
                                                }
                                                mcpRespondToRedirect(exchange, 200, mcpCallbackPage(
                                                        "Authorization complete. You can close this window and return to codingagent.", true));
                                                callback.code.complete(code);
                                            }
                                        });
                                        callback.server.start();
                                        try {
                                            McpOAuthEntry current = mcpOAuthReload(oauthSession);
                                            String scope = challenge1.scope != null
                                                    ? challenge1.scope
                                                    : discovery.resourceMetadata != null && !discovery.resourceMetadata.scopes.isEmpty()
                                                      ? String.join(" ", discovery.resourceMetadata.scopes)
                                                      : oauthSession.settings.scope;
                                            McpOAuthClientInfo clientInfo;
                                            if (oauthSession.settings.clientId != null) {
                                                clientInfo = new McpOAuthClientInfo(
                                                        oauthSession.settings.clientId,
                                                        oauthSession.settings.clientSecret,
                                                        null,
                                                        null,
                                                        null,
                                                        redirectUri.toString());
                                            } else {
                                                McpOAuthClientInfo stored = current == null ? null : current.clientInfo;
                                                if (mcpOAuthClientUsable(oauthSession, stored, redirectUri)) {
                                                    clientInfo = stored;
                                                } else {
                                                    URI endpoint = discovery.metadata.registrationEndpoint;
                                                    if (endpoint == null) {
                                                        throw new IOException(
                                                                "OAuth server does not support dynamic client registration; configure oauth.clientId for MCP server \""
                                                                        + oauthSession.name + "\"");
                                                    }
                                                    ObjectNode request = jsonObject();
                                                    request.putArray("redirect_uris").add(redirectUri.toString());
                                                    request.put("client_name", "codingagent")
                                                            .put("client_uri", "https://github.com/mikeyreilly/coding-agent")
                                                            .put("token_endpoint_auth_method", "none");
                                                    request.putArray("grant_types").add("authorization_code").add("refresh_token");
                                                    request.putArray("response_types").add("code");
                                                    if (scope != null) request.put("scope", scope);
                                                    HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                                                            .timeout(MCP_OAUTH_HTTP_TIMEOUT)
                                                            .setHeader("Accept", "application/json")
                                                            .setHeader("Content-Type", "application/json")
                                                            .POST(HttpRequest.BodyPublishers.ofString(
                                                                    Json.MAPPER.writeValueAsString(request), StandardCharsets.UTF_8));
                                                    McpOAuthResponse response =
                                                            mcpOAuthSend(builder.build());
                                                    if (!mcpOAuthSuccess(response)) {
                                                        throw mcpOAuthFailure(response, "Dynamic OAuth client registration failed");
                                                    }
                                                    JsonNode body = mcpOAuthParseObject(response, "dynamic client registration");
                                                    McpOAuthClientInfo registered = new McpOAuthClientInfo(
                                                            mcpOAuthRequiredText(body, "client_id", "dynamic client registration"),
                                                            mcpOAuthOptionalText(body, "client_secret"),
                                                            mcpOAuthOptionalLong(body, "client_id_issued_at"),
                                                            mcpOAuthOptionalLong(body, "client_secret_expires_at"),
                                                            mcpOAuthOptionalText(body, "token_endpoint_auth_method"),
                                                            redirectUri.toString());
                                                    McpOAuthEntry updated = current == null
                                                            ? new McpOAuthEntry(null, registered)
                                                            : new McpOAuthEntry(current.tokens, registered);
                                                    mcpOAuthWrite(oauthSession.name, oauthSession.config.url.toString(), updated);
                                                    oauthSession.entry = updated;
                                                    oauthSession.loaded = true;
                                                    clientInfo = registered;
                                                }
                                            }
                                            LinkedHashMap<String, String> parameters = new LinkedHashMap<>();
                                            parameters.put("response_type", "code");
                                            parameters.put("client_id", clientInfo.clientId);
                                            parameters.put("code_challenge", challengeValue);
                                            parameters.put("code_challenge_method", "S256");
                                            parameters.put("redirect_uri", redirectUri.toString());
                                            parameters.put("state", state);
                                            if (scope != null) parameters.put("scope", scope);
                                            if (scope != null && List.of(scope.split("\\s+")).contains("offline_access")) {
                                                parameters.put("prompt", "consent");
                                            }
                                            parameters.put("resource", discovery.resource.toString());
                                            String value = discovery.metadata.authorizationEndpoint.toString();
                                            int fragment = value.indexOf('#');
                                            if (fragment >= 0) value = value.substring(0, fragment);
                                            String separator = discovery.metadata.authorizationEndpoint.getRawQuery() == null ? "?" : "&";
                                            URI authorizationUrl = URI.create(value + separator + mcpFormEncode(parameters));
                                            authorizationListener.accept(authorizationUrl);
                                            browser.test(authorizationUrl);
                                            String code;
                                            try {
                                                code = callback.code.get(callbackTimeout.toMillis(), TimeUnit.MILLISECONDS);
                                            } catch (TimeoutException error1) {
                                                throw new IOException("OAuth authorization timed out after " + callbackTimeout.toMinutes() + " minutes", error1);
                                            } catch (ExecutionException error1) {
                                                Throwable cause = error1.getCause();
                                                if (cause instanceof IOException io) throw io;
                                                throw new IOException(cause == null ? "OAuth authorization failed" : cause.getMessage(), cause);
                                            }
                                            LinkedHashMap<String, String> parameters1 = new LinkedHashMap<>();
                                            parameters1.put("grant_type", "authorization_code");
                                            parameters1.put("code", code);
                                            parameters1.put("code_verifier", verifier);
                                            parameters1.put("redirect_uri", redirectUri.toString());
                                            McpOAuthTokens tokens =
                                                    mcpOAuthRequestTokens(discovery, clientInfo, parameters1, null, Map.of());
                                            if (tokens.scope == null && scope != null) {
                                                tokens = new McpOAuthTokens(
                                                        tokens.accessToken, tokens.refreshToken, tokens.expiresAt, scope);
                                            }
                                            McpOAuthEntry saved = new McpOAuthEntry(
                                                    tokens, oauthSession.settings.clientId == null ? clientInfo : null);
                                            mcpOAuthWrite(oauthSession.name, oauthSession.config.url.toString(), saved);
                                            oauthSession.entry = saved;
                                            oauthSession.loaded = true;
                                        } finally {
                                            callback.server.stop(0);
                                            callback.executor.shutdownNow();
                                        }
                                    } finally {
                                        interactiveLock.unlock();
                                    }
                                    try {
                                        candidate = mcpConnectRemote(remote, workspace, timeout, oauthSession);
                                    } catch (Exception retryError) {
                                        if (mcpIsOAuthChallenge(retryError)) {
                                            throw new IOException("MCP server rejected the OAuth token after authorization", retryError);
                                        }
                                        throw retryError;
                                    }
                                }
                            }

                            List<McpClient.ToolDefinition> tools = mcpListTools(candidate);
                            McpClient connected = candidate;
                            // Keep the last usable catalog when a list-changed refresh fails.
                            BiConsumer<String, JsonNode> listener = (method, _) -> {
                                if (method.equals("notifications/tools/list_changed")) {
                                    Thread.ofVirtual().name("mcp-tools-refresh").start(() -> {
                                        try {
                                            List<McpClient.ToolDefinition> tools1 = mcpListTools(connected);
                                            synchronized (runtime.lock) {
                                                if (runtime.generation == generation
                                                        && runtime.client == connected
                                                        && runtime.state == McpState.CONNECTED) {
                                                    runtime.tools = List.copyOf(tools1);
                                                }
                                            }
                                        } catch (Exception ignored) {
                                            // Keep the last usable catalog when a list-changed refresh fails.
                                        }
                                    });
                                }
                            };
                            switch (candidate.transport) {
                                case StdioMcpTransport stdio -> stdio.notificationListener = listener;
                                case StreamableHttpMcpTransport http -> http.notificationListener = listener;
                                case SseHttpMcpTransport sse -> sse.notificationListener = listener;
                            }
                            synchronized (runtime.lock) {
                                if (closed || runtime.generation != generation || Thread.currentThread().isInterrupted()) {
                                    return;
                                }
                                runtime.client = candidate;
                                runtime.tools = List.copyOf(tools);
                                runtime.state = McpState.CONNECTED;
                                runtime.message = null;
                                runtime.authorizationUrl = null;
                                candidate = null;
                            }
                        } catch (McpOAuthRequiredException error) {
                            mcpFailConnect(runtime, generation, McpState.AUTH_REQUIRED, error);
                        } catch (Exception error) {
                            mcpFailConnect(runtime, generation, McpState.FAILED, error);
                        } finally {
                            if (candidate != null) mcpCloseClient(candidate);
                            synchronized (runtime.lock) {
                                if (runtime.generation == generation && runtime.connector == Thread.currentThread()) {
                                    runtime.connector = null;
                                }
                            }
                        }
                    });
            runtime.connector = connector;
        }
        if (previousConnector != null && previousConnector != connector) previousConnector.interrupt();
        if (previous != null) mcpCloseClient(previous);
        connector.start();
    }

    private static McpServerStatus mcpSnapshot(McpRuntime runtime) {
        synchronized (runtime.lock) {
            int enabledToolCount =
                    (int) runtime.tools.stream().filter(tool -> !runtime.disabledTools.contains(tool.name)).count();
            return new McpServerStatus(
                    runtime.name,
                    runtime.state,
                    runtime.message,
                    runtime.tools.size(),
                    enabledToolCount,
                    switch (runtime.config) {
                        case McpServerConfig.Local local -> String.join(" ", local.command);
                        case McpServerConfig.Remote remote -> remote.url.toString();
                    },
                    runtime.authorizationUrl);
        }
    }

    private McpRuntime mcpRequireRuntime(String name) {
        McpRuntime runtime = servers.get(name);
        if (runtime == null) throw new IllegalArgumentException("MCP server is not configured: " + name);
        return runtime;
    }

    // ------------------------------------------------------- mcp oauth client

    /**
     * Returns a current token and silently refreshes it when it is near expiry.
     */
    private String mcpAccessToken(McpOAuthSession session) throws Exception {
        synchronized (session) {
            McpOAuthEntry current = session.loaded ? session.entry : mcpOAuthReload(session);
            if (current == null || current.tokens == null) return null;
            McpOAuthTokens tokens = current.tokens;
            if (!(tokens.expiresAt != null
                    && tokens.expiresAt <= Instant.now().getEpochSecond() + MCP_OAUTH_REFRESH_SKEW_SECONDS))
                return tokens.accessToken;
            if (tokens.refreshToken == null) {
                return tokens.expiresAt != null && tokens.expiresAt > Instant.now().getEpochSecond()
                        ? tokens.accessToken
                        : null;
            }
            try {
                return mcpOAuthRefresh(session, current, null).tokens.accessToken;
            } catch (McpOAuthFailure error) {
                if (mcpOAuthInvalidCredential(error)) {
                    mcpOAuthInvalidateAfterRefreshFailure(session, current, error);
                    return null;
                }
                throw error;
            } catch (IOException error) {
                // A refresh attempted inside the safety window must not waste an access token that is still valid.
                if (tokens.expiresAt != null && tokens.expiresAt > Instant.now().getEpochSecond()) {
                    return tokens.accessToken;
                }
                throw error;
            }
        }
    }

    /**
     * Refreshes a token rejected by the resource server and tells the transport whether to retry.
     */
    private boolean mcpRefreshAfterUnauthorized(
            McpOAuthSession session, McpHttpException failure, String rejectedToken) throws Exception {
        synchronized (session) {
            McpOAuthChallenge challenge = mcpOAuthChallenge(failure);
            if (challenge == null || mcpOAuthInsufficientScope(challenge) || rejectedToken == null) return false;
            McpOAuthEntry current = mcpOAuthReload(session);
            if (current == null || current.tokens == null) return false;
            if (!current.tokens.accessToken.equals(rejectedToken)) return true;
            if (current.tokens.refreshToken == null) return false;
            try {
                McpOAuthEntry refreshed = mcpOAuthRefresh(session, current, challenge);
                return !refreshed.tokens.accessToken.equals(rejectedToken);
            } catch (McpOAuthFailure error) {
                if (mcpOAuthInvalidCredential(error)) {
                    mcpOAuthInvalidateAfterRefreshFailure(session, current, error);
                    return false;
                }
                throw error;
            }
        }
    }

    /**
     * Whether this failure is a bearer challenge that OAuth can answer.
     */
    private static boolean mcpIsOAuthChallenge(Throwable error) {
        McpHttpException http = mcpFindHttpException(error);
        return http != null && mcpOAuthChallenge(http) != null;
    }

    private McpOAuthEntry mcpOAuthRefresh(
            McpOAuthSession session, McpOAuthEntry current, McpOAuthChallenge challenge)
            throws Exception {
        synchronized (CREDENTIAL_FILES) {
            McpOAuthEntry latest = mcpOAuthReload(session);
            if (latest != null && latest.tokens != null && current != null && current.tokens != null
                    && !Objects.equals(latest.tokens.accessToken, current.tokens.accessToken)) return latest;

            McpOAuthDiscovery discovery = mcpOAuthDiscover(session.config, challenge);
            McpOAuthClientInfo clientInfo;
            URI redirectUri = mcpOAuthRedirectUri(session.settings);
            if (session.settings.clientId != null) {
                clientInfo = new McpOAuthClientInfo(
                        session.settings.clientId,
                        session.settings.clientSecret,
                        null,
                        null,
                        null,
                        redirectUri.toString());
            } else {
                McpOAuthClientInfo candidate = current == null ? null : current.clientInfo;
                clientInfo = mcpOAuthClientUsable(session, candidate, redirectUri) ? candidate : null;
            }
            if (clientInfo == null) {
                throw new McpOAuthFailure(
                        400, "invalid_client", "No OAuth client is registered for this MCP server");
            }
            LinkedHashMap<String, String> parameters = new LinkedHashMap<>();
            parameters.put("grant_type", "refresh_token");
            parameters.put("refresh_token", current.tokens.refreshToken);
            McpOAuthTokens refreshed =
                    mcpOAuthRequestTokens(discovery, clientInfo, parameters, current.tokens, Map.of());
            McpOAuthEntry updated = new McpOAuthEntry(refreshed, current.clientInfo);
            mcpOAuthWrite(session.name, session.config.url.toString(), updated);
            session.entry = updated;
            session.loaded = true;
            return updated;
        }
    }

    private static boolean mcpOAuthClientUsable(
            McpOAuthSession session, McpOAuthClientInfo clientInfo, URI redirectUri) {
        if (clientInfo == null || clientInfo.clientId == null || clientInfo.clientId.isBlank()) return false;
        Long expires = clientInfo.clientSecretExpiresAt;
        if (expires != null && expires > 0 && expires <= Instant.now().getEpochSecond()) return false;
        if (clientInfo.redirectUri != null) return clientInfo.redirectUri.equals(redirectUri.toString());
        return session.settings.configuredRedirectUri == null && session.settings.callbackPort == null;
    }

    private McpOAuthEntry mcpOAuthReload(McpOAuthSession session) throws IOException {
        McpOAuthEntry result = null;
        String serverUrl = session.config.url.toString();
        McpOAuthEntry own = mcpOAuthReadEntry(path, session.name, serverUrl, false);
        if (own != null) {
            result = own;
        } else {
            for (Path candidate : importPaths) {
                McpOAuthEntry imported = mcpOAuthReadEntry(candidate, session.name, serverUrl, true);
                if (imported != null) {
                    result = imported;
                    break;
                }
            }
        }
        session.entry = result;
        session.loaded = true;
        return session.entry;
    }

    private void mcpOAuthInvalidateAfterRefreshFailure(
            McpOAuthSession session, McpOAuthEntry current, McpOAuthFailure failure)
            throws IOException {
        boolean invalidClient = "invalid_client".equals(failure.code) || "unauthorized_client".equals(failure.code);
        McpOAuthEntry cleared = invalidClient && session.settings.clientId == null
                ? new McpOAuthEntry(null, null)
                : new McpOAuthEntry(null, current.clientInfo);
        mcpOAuthWrite(session.name, session.config.url.toString(), cleared);
        session.entry = cleared;
        session.loaded = true;
    }

    private McpOAuthDiscovery mcpOAuthDiscover(McpServerConfig.Remote config, McpOAuthChallenge challenge)
            throws Exception {
        McpResourceMetadata resourceMetadata = null;
        if (challenge != null && challenge.resourceMetadataUrl != null) {
            URI endpoint1 = challenge.resourceMetadataUrl;
            mcpRequireSecureEndpoint(endpoint1, "OAuth protected resource metadata");
            McpOAuthResponse response1 = mcpOAuthSend(mcpOAuthGet(endpoint1, Map.of()));
            if (!mcpOAuthSuccess(response1)) {
                throw new IOException("OAuth protected resource metadata returned HTTP " + response1.status + " ("
                        + endpoint1 + ")");
            }
            resourceMetadata = mcpOAuthParseResourceMetadata(response1);
        } else {
            URI server = config.url;
            String path1 = server.getRawPath();
            if (path1 == null || path1.isEmpty()) path1 = "/";
            if (path1.endsWith("/") && path1.length() > 1) path1 = path1.substring(0, path1.length() - 1);
            URI pathAware = mcpUriAtOrigin(
                    server,
                    "/.well-known/oauth-protected-resource" + (path1.equals("/") ? "" : path1),
                    server.getRawQuery());
            McpOAuthResponse response1 = mcpOAuthSend(mcpOAuthGet(pathAware, Map.of()));
            if (mcpOAuthSuccess(response1)) {
                resourceMetadata = mcpOAuthParseResourceMetadata(response1);
            } else {
                if (!(response1.status >= 400 && response1.status < 500)) {
                    throw new IOException(
                            "OAuth protected resource metadata returned HTTP " + response1.status + " (" + pathAware + ")");
                }
                if (!path1.equals("/")) {
                    URI root = mcpUriAtOrigin(server, "/.well-known/oauth-protected-resource", null);
                    response1 = mcpOAuthSend(mcpOAuthGet(root, Map.of()));
                    if (mcpOAuthSuccess(response1)) {
                        resourceMetadata = mcpOAuthParseResourceMetadata(response1);
                    } else {
                        if (!(response1.status >= 400 && response1.status < 500)) {
                            throw new IOException(
                                    "OAuth protected resource metadata returned HTTP " + response1.status + " (" + root + ")");
                        }
                    }
                }
            }
        }

        URI authorizationServer = resourceMetadata != null && !resourceMetadata.authorizationServers.isEmpty()
                ? resourceMetadata.authorizationServers.getFirst()
                : mcpUriOrigin(config.url);
        mcpRequireSecureEndpoint(authorizationServer, "authorization server");
        McpAuthorizationMetadata metadata = null;
        List<URI> result;
        String path = authorizationServer.getRawPath();
        if (path == null || path.isEmpty() || path.equals("/")) {
            result = List.of(
                    mcpUriAtOrigin(authorizationServer, "/.well-known/oauth-authorization-server", null),
                    mcpUriAtOrigin(authorizationServer, "/.well-known/openid-configuration", null));
        } else {
            if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            result = List.of(
                    mcpUriAtOrigin(authorizationServer, "/.well-known/oauth-authorization-server" + path, null),
                    mcpUriAtOrigin(authorizationServer, "/.well-known/openid-configuration" + path, null),
                    mcpUriAtOrigin(authorizationServer, path + "/.well-known/openid-configuration", null));
        }
        for (URI endpoint : result) {
            McpOAuthResponse response = mcpOAuthSend(mcpOAuthGet(endpoint, Map.of()));
            if (mcpOAuthSuccess(response)) {
                JsonNode body = mcpOAuthParseObject(response, "OAuth authorization server metadata");
                mcpOAuthRequiredText(body, "issuer", "OAuth authorization server metadata");
                URI authorization =
                        mcpOAuthRequiredUri(body, "authorization_endpoint", "OAuth authorization server metadata");
                URI token = mcpOAuthRequiredUri(body, "token_endpoint", "OAuth authorization server metadata");
                String value = mcpOAuthOptionalText(body, "registration_endpoint");
                URI registration =
                        value == null ? null : mcpParseAbsoluteHttpUri(value, "OAuth authorization server metadata" + "." + "registration_endpoint");
                mcpRequireSecureEndpoint(authorization, "OAuth authorization endpoint");
                mcpRequireSecureEndpoint(token, "OAuth token endpoint");
                if (registration != null) mcpRequireSecureEndpoint(registration, "OAuth registration endpoint");
                List<String> responseTypes = mcpOAuthTextArray(body.get("response_types_supported"), "response_types_supported");
                if (!responseTypes.contains("code")) {
                    throw new IOException("OAuth server does not support authorization code responses");
                }
                List<String> challengeMethods =
                        mcpOAuthTextArray(body.get("code_challenge_methods_supported"), "code_challenge_methods_supported");
                if (!challengeMethods.contains("S256")) {
                    throw new IOException("OAuth server does not advertise PKCE S256 support");
                }
                metadata = new McpAuthorizationMetadata(
                        authorization,
                        token,
                        registration,
                        mcpOAuthTextArray(
                                body.get("token_endpoint_auth_methods_supported"), "token_endpoint_auth_methods_supported"),
                        mcpOAuthTextArray(body.get("scopes_supported"), "scopes_supported"));
                break;
            }
            if (response.status >= 400 && response.status < 500) continue;
            throw new IOException(
                    "OAuth authorization metadata returned HTTP " + response.status + " (" + endpoint + ")");
        }
        if (metadata == null) {
            throw new IOException("OAuth authorization server metadata was not found for " + authorizationServer);
        }
        URI resource;
        if (resourceMetadata == null) {
            String value = config.url.toString();
            int fragment = value.indexOf('#');
            resource = fragment < 0 ? config.url : URI.create(value.substring(0, fragment));
        } else {
            resource = resourceMetadata.resource;
        }
        if (resourceMetadata != null) {
            boolean result1 = false;
            if (mcpUriOrigin(config.url).equals(mcpUriOrigin(resource))) {
                String requestedPath = mcpNormalizedResourcePath(config.url.getPath());
                String configuredPath = mcpNormalizedResourcePath(resource.getPath());
                result1 = requestedPath.startsWith(configuredPath);
            }
            if (!result1) {
                throw new IOException(
                        "OAuth protected resource " + resource + " does not match MCP endpoint " + config.url);
            }
        }
        return new McpOAuthDiscovery(authorizationServer, metadata, resourceMetadata, resource);
    }

    private static McpResourceMetadata mcpOAuthParseResourceMetadata(McpOAuthResponse response)
            throws IOException {
        JsonNode body = mcpOAuthParseObject(response, "OAuth protected resource metadata");
        URI resource = mcpOAuthRequiredUri(body, "resource", "OAuth protected resource metadata");
        JsonNode node = body.get("authorization_servers");
        List<String> values = mcpOAuthTextArray(node, "authorization_servers");
        List<URI> result = new ArrayList<>();
        for (String value : values) result.add(mcpParseAbsoluteHttpUri(value, "authorization_servers"));
        List<URI> servers = List.copyOf(result);
        List<String> scopes = mcpOAuthTextArray(body.get("scopes_supported"), "scopes_supported");
        return new McpResourceMetadata(resource, servers, scopes);
    }

    private McpOAuthTokens mcpOAuthRequestTokens(McpOAuthDiscovery discovery, McpOAuthClientInfo clientInfo, LinkedHashMap<String, String> parameters, McpOAuthTokens previous, Map<String, String> configuredHeaders)
            throws Exception {
        parameters.put("resource", discovery.resource.toString());
        LinkedHashMap<String, String> headers = new LinkedHashMap<>(configuredHeaders);
        headers.put("Accept", "application/json");
        String method;
        List<String> supported = discovery.metadata.tokenAuthMethods;
        String registered = clientInfo.tokenEndpointAuthMethod;
        if (registered != null
                && List.of("client_secret_basic", "client_secret_post", "none").contains(registered)
                && (supported.isEmpty() || supported.contains(registered))) {
            method = registered;
        } else if (supported.isEmpty()) {
            method = clientInfo.clientSecret == null ? "none" : "client_secret_basic";
        } else if (clientInfo.clientSecret != null && supported.contains("client_secret_basic")) {
            method = "client_secret_basic";
        } else if (clientInfo.clientSecret != null && supported.contains("client_secret_post")) {
            method = "client_secret_post";
        } else if (supported.contains("none")) {
            method = "none";
        } else {
            method = clientInfo.clientSecret == null ? "none" : "client_secret_post";
        }
        switch (method) {
            case "client_secret_basic" -> {
                if (clientInfo.clientSecret == null) {
                    throw new IOException("OAuth client_secret_basic requires a client secret");
                }
                String value = clientInfo.clientId + ":" + clientInfo.clientSecret;
                headers.put(
                        "Authorization",
                        "Basic " + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
            }
            case "client_secret_post" -> {
                parameters.put("client_id", clientInfo.clientId);
                if (clientInfo.clientSecret != null)
                    parameters.put("client_secret", clientInfo.clientSecret);
            }
            case "none" -> parameters.put("client_id", clientInfo.clientId);
            default -> throw new IOException("Unsupported OAuth token endpoint authentication method: " + method);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(discovery.metadata.tokenEndpoint)
                .timeout(MCP_OAUTH_HTTP_TIMEOUT)
                .setHeader("Accept", "application/json")
                .setHeader("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(mcpFormEncode(parameters), StandardCharsets.UTF_8));
        mcpApplyOAuthHeaders(builder, headers);
        HttpRequest request = builder.build();
        McpOAuthResponse response = mcpOAuthSend(request);
        if (!mcpOAuthSuccess(response)) throw mcpOAuthFailure(response, "OAuth token request failed");
        JsonNode body = mcpOAuthParseObject(response, "OAuth token response");
        String access = mcpOAuthRequiredText(body, "access_token", "OAuth token response");
        String tokenType = mcpOAuthRequiredText(body, "token_type", "OAuth token response");
        if (!tokenType.equalsIgnoreCase("Bearer")) {
            throw new IOException("OAuth token response did not return a Bearer token");
        }
        String refresh = mcpOAuthOptionalText(body, "refresh_token");
        if (refresh == null && previous != null) refresh = previous.refreshToken;
        Long expiresIn = mcpOAuthOptionalLong(body, "expires_in");
        if (expiresIn != null && expiresIn < 0) {
            throw new IOException("OAuth token response contains a negative expires_in");
        }
        Long expiresAt = expiresIn == null ? null : Instant.now().getEpochSecond() + expiresIn;
        String scope = mcpOAuthOptionalText(body, "scope");
        if (scope == null && previous != null) scope = previous.scope;
        return new McpOAuthTokens(access, refresh, expiresAt, scope);
    }

    private HttpRequest mcpOAuthGet(URI uri, Map<String, String> configuredHeaders) {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(uri).timeout(MCP_OAUTH_HTTP_TIMEOUT).GET();
        builder.setHeader("Accept", "application/json");
        builder.setHeader("MCP-Protocol-Version", MCP_OAUTH_PROTOCOL_VERSION);
        mcpApplyOAuthHeaders(builder, configuredHeaders);
        return builder.build();
    }

    private McpOAuthResponse mcpOAuthSend(HttpRequest request)
            throws IOException, InterruptedException {
        HttpResponse<String> response =
                http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new McpOAuthResponse(
                response.statusCode(), response.uri(), response.headers().map(), response.body());
    }

    private static boolean mcpOAuthSuccess(McpOAuthResponse response) {
        return response.status >= 200 && response.status < 300;
    }

    private void mcpApplyOAuthHeaders(HttpRequest.Builder request, Map<String, String> headers) {
        for (var header : headers.entrySet()) {
            String name = header.getKey();
            if (!mcpRestrictedHeader(name)
                    && !name.equalsIgnoreCase("Content-Type")
                    && !name.equalsIgnoreCase("Accept")) {
                request.header(name, header.getValue());
            }
        }
    }

    private static McpOAuthFailure mcpOAuthFailure(McpOAuthResponse response, String fallback) {
        String code = null;
        String description = null;
        try {
            JsonNode node = Json.MAPPER.readTree(response.body);
            code = mcpOAuthOptionalText(node, "error");
            description = mcpOAuthOptionalText(node, "error_description");
        } catch (IOException ignored) {
        }
        String message = description != null ? description : code != null ? code : mcpAbbreviate(response.body);
        if (message.isBlank()) message = fallback;
        return new McpOAuthFailure(response.status, code, fallback + ": " + message);
    }

    private static boolean mcpOAuthInvalidCredential(McpOAuthFailure error) {
        return error.code != null
                && List.of("invalid_grant", "invalid_client", "unauthorized_client").contains(error.code);
    }

    private static JsonNode mcpOAuthParseObject(McpOAuthResponse response, String source) throws IOException {
        JsonNode body;
        try {
            body = Json.MAPPER.readTree(response.body);
        } catch (IOException error) {
            throw new IOException("Invalid JSON in " + source + " from " + response.uri, error);
        }
        if (body == null || !body.isObject()) throw new IOException("Invalid " + source + ": expected a JSON object");
        return body;
    }

    private static String mcpOAuthRequiredText(JsonNode node, String field, String source) throws IOException {
        String value = mcpOAuthOptionalText(node, field);
        if (value == null) throw new IOException("Invalid " + source + ": missing " + field);
        return value;
    }

    private static String mcpOAuthOptionalText(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static Long mcpOAuthOptionalLong(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (value.isNumber()) return (long) Math.floor(value.asDouble());
        if (value.isTextual()) {
            try {
                return Long.parseLong(value.asText());
            } catch (NumberFormatException ignored) {
            }
        }
        throw new IOException("Invalid OAuth field " + field + ": expected a number");
    }

    private static URI mcpOAuthRequiredUri(JsonNode node, String field, String source) throws IOException {
        String value = mcpOAuthRequiredText(node, field, source);
        return mcpParseAbsoluteHttpUri(value, source + "." + field);
    }

    private static URI mcpParseAbsoluteHttpUri(String value, String field) throws IOException {
        try {
            URI uri = URI.create(value);
            if (!uri.isAbsolute()
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null
                    || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid " + field + ": expected an absolute HTTP(S) URL", error);
        }
    }

    private static List<String> mcpOAuthTextArray(JsonNode node, String field) throws IOException {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) throw new IOException("Invalid OAuth metadata field " + field + ": expected an array");
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isTextual() || value.asText().isBlank()) {
                throw new IOException("Invalid OAuth metadata field " + field + ": expected strings");
            }
            values.add(value.asText());
        }
        return List.copyOf(values);
    }

    private static String mcpNormalizedResourcePath(String path) {
        String value = path == null || path.isEmpty() ? "/" : path;
        return value.endsWith("/") ? value : value + "/";
    }

    private static URI mcpUriOrigin(URI uri) {
        return mcpUriAtOrigin(uri, "/", null);
    }

    private static URI mcpUriAtOrigin(URI uri, String path, String query) {
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        StringBuilder value = new StringBuilder()
                .append(uri.getScheme().toLowerCase(Locale.ROOT))
                .append("://")
                .append(host.indexOf(':') >= 0 ? "[" + host + "]" : host);
        if (uri.getPort() >= 0) value.append(':').append(uri.getPort());
        value.append(path);
        if (query != null && !query.isEmpty()) value.append('?').append(query);
        return URI.create(value.toString());
    }

    private static String mcpFormEncode(Map<String, String> values) {
        return values.entrySet().stream()
                .map(entry -> mcpUrlEncode(entry.getKey()) + "=" + mcpUrlEncode(entry.getValue()))
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
    }

    private static String mcpUrlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String mcpRandomUrlToken(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private void mcpRequireSecureEndpoint(URI uri, String description) throws IOException {
        if (uri.getScheme().equalsIgnoreCase("https")) return;
        if (uri.getScheme().equalsIgnoreCase("http")) {
            String host = uri.getHost();
            if (host != null && (host.equalsIgnoreCase("localhost") || host.equals("::1") || host.startsWith("127.")))
                return;
        }
        throw new IOException(description + " must use HTTPS: " + uri);
    }

    private static McpOAuthChallenge mcpOAuthChallenge(McpHttpException response) {
        List<String> values1 = response.headers.get("WWW-Authenticate".toLowerCase(Locale.ROOT));
        String authenticate = values1 == null || values1.isEmpty() ? null : String.join(", ", values1);
        if (response.status != 401 && response.status != 403) return null;
        if (authenticate != null && !authenticate.toLowerCase(Locale.ROOT).contains("bearer")) return null;
        Map<String, String> fields;
        if (authenticate == null) {
            fields = Map.of();
        } else {
            LinkedHashMap<String, String> values = new LinkedHashMap<>();
            Matcher matcher = MCP_OAUTH_AUTH_PARAMETER.matcher(authenticate);
            while (matcher.find()) {
                values.put(
                        matcher.group(1).toLowerCase(Locale.ROOT),
                        matcher.group(2) != null ? matcher.group(2) : matcher.group(3));
            }
            fields = values;
        }
        String error = fields.get("error");
        if (response.status == 403 && !"insufficient_scope".equals(error)) return null;
        URI resource = null;
        String rawResource = fields.get("resource_metadata");
        if (rawResource != null) {
            try {
                resource = URI.create(rawResource);
                if (!resource.isAbsolute() || resource.getHost() == null) resource = null;
            } catch (IllegalArgumentException ignored) {
            }
        }
        return new McpOAuthChallenge(resource, fields.get("scope"), error);
    }

    private static boolean mcpOAuthInsufficientScope(McpOAuthChallenge challenge) {
        return "insufficient_scope".equals(challenge.error);
    }

    private static URI mcpOAuthRedirectUri(McpOAuthSettings settings) {
        if (settings.configuredRedirectUri != null) return settings.configuredRedirectUri;
        int port = settings.callbackPort == null ? MCP_OAUTH_DEFAULT_CALLBACK_PORT : settings.callbackPort;
        return URI.create("http://127.0.0.1:" + port + MCP_OAUTH_DEFAULT_CALLBACK_PATH);
    }

    // -------------------------------------------------------- mcp oauth store

    /**
     * Stores one server credential under an exclusive file lock.
     */
    private void mcpOAuthWrite(String name, String serverUrl, McpOAuthEntry entry) throws IOException {
        synchronized (CREDENTIAL_FILES) {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("MCP server name must not be blank");
            Files.createDirectories(path.getParent());
            mcpSetPermissions(path.getParent(), DIRECTORY_PERMISSIONS);
            try (FileChannel channel =
                         FileChannel.open(mcpAuthLockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                ObjectNode root = mcpOAuthReadRoot(path, false);
                ObjectNode node = jsonObject().put("serverUrl", serverUrl);
                if (entry.tokens != null) {
                    McpOAuthTokens value = entry.tokens;
                    ObjectNode tokens = node.putObject("tokens").put("accessToken", value.accessToken);
                    mcpPutText(tokens, "refreshToken", value.refreshToken);
                    if (value.expiresAt != null) tokens.put("expiresAt", value.expiresAt);
                    mcpPutText(tokens, "scope", value.scope);
                }
                if (entry.clientInfo != null) {
                    McpOAuthClientInfo value = entry.clientInfo;
                    ObjectNode client = node.putObject("clientInfo").put("clientId", value.clientId);
                    mcpPutText(client, "clientSecret", value.clientSecret);
                    if (value.clientIdIssuedAt != null) client.put("clientIdIssuedAt", value.clientIdIssuedAt);
                    if (value.clientSecretExpiresAt != null) {
                        client.put("clientSecretExpiresAt", value.clientSecretExpiresAt);
                    }
                    mcpPutText(client, "tokenEndpointAuthMethod", value.tokenEndpointAuthMethod);
                    mcpPutText(client, "redirectUri", value.redirectUri);
                }
                root.set(name, node);
                Path temporary = Files.createTempFile(path.getParent(), "mcp-auth-", ".json");
                try {
                    Files.writeString(temporary, Json.MAPPER.writeValueAsString(root) + "\n", StandardCharsets.UTF_8);
                    mcpSetPermissions(temporary, FILE_PERMISSIONS);
                    try {
                        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException error) {
                        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
                    }
                    mcpSetPermissions(path, FILE_PERMISSIONS);
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
        }
    }

    private static McpOAuthEntry mcpOAuthReadEntry(
            Path source, String name, String serverUrl, boolean lenient) throws IOException {
        if (!Files.isRegularFile(source)) return null;
        ObjectNode root;
        try {
            root = mcpOAuthReadRoot(source, lenient);
        } catch (IOException error) {
            if (lenient) return null;
            throw error;
        }
        JsonNode node = root.get(name);
        if (node == null || !node.isObject()) return null;
        if (!node.path("serverUrl").isTextual() || !node.path("serverUrl").asText().equals(serverUrl)) return null;
        try {
            McpOAuthTokens tokens = null;
            JsonNode tokenNode = node.get("tokens");
            if (tokenNode != null && tokenNode.isObject() && mcpStoredText(tokenNode, "accessToken") != null) {
                tokens = new McpOAuthTokens(
                        mcpStoredText(tokenNode, "accessToken"),
                        mcpStoredText(tokenNode, "refreshToken"),
                        mcpStoredNumber(tokenNode, "expiresAt"),
                        mcpStoredText(tokenNode, "scope"));
            }
            McpOAuthClientInfo client = null;
            JsonNode clientNode = node.get("clientInfo");
            if (clientNode != null && clientNode.isObject() && mcpStoredText(clientNode, "clientId") != null) {
                client = new McpOAuthClientInfo(
                        mcpStoredText(clientNode, "clientId"),
                        mcpStoredText(clientNode, "clientSecret"),
                        mcpStoredNumber(clientNode, "clientIdIssuedAt"),
                        mcpStoredNumber(clientNode, "clientSecretExpiresAt"),
                        mcpStoredText(clientNode, "tokenEndpointAuthMethod"),
                        mcpStoredText(clientNode, "redirectUri"));
            }
            return new McpOAuthEntry(tokens, client);
        } catch (RuntimeException error) {
            if (lenient) return null;
            throw new IOException("Invalid MCP OAuth credential for \"" + name + "\" in " + source, error);
        }
    }

    private static ObjectNode mcpOAuthReadRoot(Path source, boolean lenient) throws IOException {
        if (!Files.exists(source)) return jsonObject();
        try {
            JsonNode parsed = Json.MAPPER.readTree(Files.readString(source, StandardCharsets.UTF_8));
            if (parsed instanceof ObjectNode object) return object.deepCopy();
            if (lenient) return jsonObject();
            throw new IOException("Invalid MCP OAuth credential file " + source + ": expected a JSON object");
        } catch (IOException error) {
            if (lenient) return jsonObject();
            throw new IOException(
                    "Failed to read MCP OAuth credential file " + source + ": " + error.getMessage(), error);
        }
    }

    private static String mcpStoredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static Long mcpStoredNumber(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? (long) Math.floor(value.asDouble()) : null;
    }

    private void mcpPutText(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) node.put(field, value);
    }

    private void mcpSetPermissions(Path target, Set<PosixFilePermission> permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(target, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Windows protects files through the user's profile ACL instead.
        }
    }

    // ----------------------------------------------------- mcp oauth callback

    private void mcpRespondToRedirect(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders()
                .set(
                        "Content-Security-Policy",
                        "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static String mcpCallbackPage(String message, boolean success) {
        String title = success ? "Authorization complete" : "Authorization failed";
        String color = success ? "#16803c" : "#b42318";
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">"
                + "<title>" + title
                + "</title><style>body{font:16px system-ui;margin:4rem auto;max-width:42rem;padding:0 1.5rem}"
                + "h1{color:" + color + "}</style></head><body><h1>" + title + "</h1><p>" + message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                + "</p></body></html>";
    }

    /** Configures workspace tools and the resolver that supplies their instruction context. */
    private void configureBuiltInTools(Path cwd, String baseSystemPrompt) {
        instructionResolver = AgentInstructionResolver.forWorkspace(
                cwd, applicationPaths.globalInstructionsFile(), baseSystemPrompt);
        currentInstructionDirectory = instructionResolver.workingDirectory();
        instructionSources = List.of();
        refreshAgentInstructionsIn(currentInstructionDirectory);
        Set<Path> announcedSources = new LinkedHashSet<>();
        subscribe(event -> {
            if (event instanceof AgentEvent.AgentStart) {
                synchronized (this) {
                    refreshAgentInstructionsIn(currentInstructionDirectory);
                }
                applyAgentInstructions(announcedSources);
            }
        });
        this.executable = "git";
        tools.addAll(builtInTools(cwd, path -> {
            boolean result;
            synchronized (this) {
                Path directory = AgentInstructionResolver.instructionDirectory(path);
                if (directory == null
                        || !directory.startsWith(instructionResolver.repositoryRoot())
                        || !directory.startsWith(currentInstructionDirectory)) {
                    result = false;
                } else {
                    result = refreshAgentInstructionsIn(directory);
                }
            }
            if (result) {
                applyAgentInstructions(announcedSources);
            }
        }));
    }

    private void applyAgentInstructions(Set<Path> announcedSources) {
        Set<Path> activeSources = new LinkedHashSet<>();
        for (AgentInstructionResolver.InstructionSource source : instructionSources) {
            activeSources.add(source.path());
        }
        announcedSources.retainAll(activeSources);
        for (AgentInstructionResolver.InstructionSource source : instructionSources) {
            if (announcedSources.add(source.path())) {
                emit(new AgentEvent.InstructionLoaded(source.path()));
            }
        }
    }

    // ------------------------------------------------------ agent instructions

    /** Refreshes the agent's prompt and instruction-file sources for one local-tool scope. */
    private boolean refreshAgentInstructionsIn(Path directory) {
        Path resolvedDirectory = AgentInstructionResolver.instructionDirectory(directory);
        if (resolvedDirectory == null) {
            throw new IllegalArgumentException("instruction directory must have a parent directory");
        }
        AgentInstructionResolver.ResolvedInstructions next = instructionResolver.resolve(resolvedDirectory);
        boolean changed = !Objects.equals(resolvedDirectory, currentInstructionDirectory)
                || !next.systemPrompt().equals(systemPrompt)
                || !next.sources().equals(instructionSources);
        currentInstructionDirectory = resolvedDirectory;
        systemPrompt = next.systemPrompt();
        instructionSources = next.sources();
        return changed;
    }

    /**
     * Drops failed turns and synthesizes results for tool calls that never completed.
     */
    public List<Message> resumableMessages(List<Message> messages) {
        List<Message> result = new ArrayList<>();
        Map<String, String> pendingToolCalls = new LinkedHashMap<>();
        for (Message message : messages) {
            if (message instanceof AssistantMessage assistant) {
                appendMissingToolResults(result, pendingToolCalls);
                if (assistant.stopReason == StopReason.ERROR || assistant.stopReason == StopReason.ABORTED) continue;
                toolCalls(assistant).forEach(call -> pendingToolCalls.put(call.id, call.name));
                result.add(message);
            } else if (message instanceof ToolResultMessage toolResult) {
                if (pendingToolCalls.remove(toolResult.toolCallId) != null) result.add(message);
            } else {
                appendMissingToolResults(result, pendingToolCalls);
                result.add(message);
            }
        }
        appendMissingToolResults(result, pendingToolCalls);
        return List.copyOf(result);
    }

    private void appendMissingToolResults(
            List<Message> messages, Map<String, String> pendingToolCalls) {
        for (Map.Entry<String, String> toolCall : pendingToolCalls.entrySet()) {
            messages.add(new ToolResultMessage(
                    toolCall.getKey(),
                    toolCall.getValue(),
                    List.of(new TextContent("No result provided", null)),
                    null,
                    true,
                    System.currentTimeMillis()));
        }
        pendingToolCalls.clear();
    }

    // ------------------------------------------------------------ session codec

    private void encodeSessionUserContent(ArrayNode target, List<UserContent> source) {
        for (UserContent block : source) {
            if (block instanceof TextContent text) {
                ObjectNode encoded = target.addObject().put("type", "text").put("text", text.text);
                putNullableSessionText(encoded, "textSignature", text.textSignature);
            } else if (block instanceof ImageContent image) {
                target.addObject().put("type", "image").put("data", image.data).put("mimeType", image.mimeType);
            }
        }
    }

    private static List<UserContent> decodeSessionUserContent(JsonNode content) throws IOException {
        if (content == null || content.isNull()) {
            return List.of();
        }
        if (!content.isArray()) {
            throw new IOException("Message content must be an array");
        }
        List<UserContent> decoded = new ArrayList<>();
        for (JsonNode block : content) {
            String type = requiredSessionText(block, "type");
            switch (type) {
                case "text" -> decoded.add(new TextContent(
                        requiredSessionText(block, "text"), optionalSessionText(block, "textSignature")));
                case "image" -> decoded.add(new ImageContent(
                        requiredSessionText(block, "data"), requiredSessionText(block, "mimeType")));
                default -> throw new IOException("Unknown user content type: " + type);
            }
        }
        return List.copyOf(decoded);
    }

    private static String requiredSessionText(JsonNode node, String field) throws IOException {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual()) {
            throw new IOException(field + " must be a string");
        }
        return value.asText();
    }

    private static String optionalSessionText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private void putNullableSessionText(ObjectNode node, String field, String value) {
        if (value != null) node.put(field, value);
    }

    // --------------------------------------------------------- session recorder

    private CodingAgentOperations sessionRecorder(String sessionId) {
        this.sessionId = sessionId;
        return this;
    }

    private void settingsStore(Path settingsPath, Path lockPath) {
        this.settingsPath = settingsPath;
        this.settingsLockPath = lockPath;
    }

    public void createSessionRecorder(Path cwd, String provider, String model) throws IOException {
        createSessionRecorder(cwd, provider, model, null);
    }

    /**
     * Creates a session with an optional user-visible name.
     */
    void createSessionRecorder(Path cwd, String provider, String model, String sessionName) throws IOException {
        createSessionRecorder(cwd, provider, model, sessionName, null, null, null);
    }

    private void createSessionRecorder(Path cwd, String provider, String model, String sessionName,
            String parent, String task, ThinkingLevel level) throws IOException {
        Files.createDirectories(directory);
        setPosixPermissions(directory, DIRECTORY_PERMISSIONS);
        String id1 = uuidv7();
        Path file = directory.resolve(id1 + ".jsonl");
        Files.createFile(file);
        setPosixPermissions(file, FILE_PERMISSIONS);
        ObjectNode start = jsonObject();
        start.put("cwd", cwd.toAbsolutePath().normalize().toString());
        start.put("provider", provider);
        start.put("model", model);
        start.put("agentMode", agentMode().wire);
        String normalizedName = sessionName == null ? null : sessionName.strip();
        if (normalizedName != null && !normalizedName.isEmpty()) start.put("name", normalizedName);
        if (parent != null) {
            start.put("parentSessionId", parent).put("task", task).put("thinkingLevel", level.wire).put("lifecycle", "IDLE");
        }
        appendSessionEntry(id1, "session_start", start);
        sessionRecorder(id1);
        taskState.reset();
    }

    /**
     * Creates a named child session containing a copy of the supplied conversation.
     */
    public void forkSessionRecorder(Path cwd, String provider, String model, String sessionName, List<Message> messages)
            throws IOException {
        ObjectNode forkedState = taskState.snapshot();
        String previousSessionId = sessionId;
        try {
            createSessionRecorder(cwd, provider, model, sessionName);
            appendSessionMessages(messages);
            appendSessionEntry(sessionId, "task_state", forkedState);
            taskState.restore(forkedState);
        } catch (IOException | RuntimeException error) {
            sessionRecorder(previousSessionId);
            taskState.restore(forkedState);
            throw error;
        }
    }

    /**
     * Opens an existing session so future messages continue in the same JSONL file.
     */
    public void resumeSessionRecorder(String sessionId) throws IOException {
        if (!childRuntime) requireIdleGroup();
        SessionSnapshot saved = sessionSnapshot(sessionId);
        if (!childRuntime) agentMode = saved.agentMode;
        if (saved.taskState == null) taskState.reset(); else taskState.restore(saved.taskState);
        sessionRecorder(sessionId);
        transcript.clear();
        transcript.addAll(saved.transcriptMessages);
        if (!childRuntime && subagents != null) {
            subagents.restore(listAllSessions(saved.cwd).stream()
                    .filter(child -> sessionId.equals(child.parentSessionId))
                    .sorted(Comparator.comparing(child -> child.created)).toList());
        }
    }

    /**
     * Reattaches an already-running conversation to its existing recorder after its workspace-bound
     * tools have been rebuilt. The workspace transition is append-only, so an interrupted process
     * can resume the session from its latest workspace without rewriting its original metadata.
     */
    void moveSessionRecorderToWorkspace(String sessionId, Path workspace) throws IOException {
        if (!childRuntime) requireIdleGroup();
        SessionSnapshot saved = sessionSnapshot(sessionId);
        Path normalized = Objects.requireNonNull(workspace, "workspace").toAbsolutePath().normalize();
        if (!saved.cwd.equals(normalized)) {
            appendSessionEntry(sessionId, "workspace_change", jsonObject().put("cwd", normalized.toString()));
        }
        sessionRecorder(sessionId);
    }

    /**
     * Appends finished agent messages in chronological order.
     */
    public void appendSessionMessages(List<Message> messages) throws IOException {
        for (Message message : messages) {
            ObjectNode node = jsonObject();
            node.put("role", role(message));
            node.put("timestamp", timestamp(message));
            ArrayNode content = node.putArray("content");
            switch (message) {
                case UserMessage user -> encodeSessionUserContent(content, user.content);
                case AssistantMessage assistant -> {
                    putNullableSessionText(node, "api", assistant.api);
                    putNullableSessionText(node, "provider", assistant.provider);
                    putNullableSessionText(node, "model", assistant.model);
                    putNullableSessionText(node, "responseModel", assistant.responseModel);
                    putNullableSessionText(node, "responseId", assistant.responseId);
                    node.put("stopReason", assistant.stopReason.wire);
                    putNullableSessionText(node, "error", assistant.errorMessage);
                    putNullableSessionText(node, "rawStopReason", assistant.rawStopReason);
                    ObjectNode node1 = node.putObject("usage");
                    node1.put("input", assistant.usage.input);
                    node1.put("output", assistant.usage.output);
                    node1.put("cacheRead", assistant.usage.cacheRead);
                    node1.put("cacheWrite", assistant.usage.cacheWrite);
                    if (assistant.usage.cacheWrite1h != null) node1.put("cacheWrite1h", assistant.usage.cacheWrite1h);
                    if (assistant.usage.reasoning != null) node1.put("reasoning", assistant.usage.reasoning);
                    node1.put("totalTokens", assistant.usage.totalTokens);
                    ObjectNode cost = node1.putObject("cost");
                    cost.put("input", assistant.usage.cost.input);
                    cost.put("output", assistant.usage.cost.output);
                    cost.put("cacheRead", assistant.usage.cost.cacheRead);
                    cost.put("cacheWrite", assistant.usage.cost.cacheWrite);
                    cost.put("total", assistant.usage.cost.total);
                    for (AssistantContent block : assistant.content) {
                        if (block instanceof TextContent text) {
                            ObjectNode encoded = content.addObject().put("type", "text").put("text", text.text);
                            putNullableSessionText(encoded, "textSignature", text.textSignature);
                        } else if (block instanceof ThinkingContent thinking) {
                            ObjectNode encoded = content.addObject()
                                    .put("type", "thinking")
                                    .put("text", thinking.thinking)
                                    .put("redacted", thinking.redacted);
                            putNullableSessionText(encoded, "thinkingSignature", thinking.thinkingSignature);
                        } else if (block instanceof ToolCall call) {
                            ObjectNode encoded = content.addObject()
                                    .put("type", "toolCall")
                                    .put("id", call.id)
                                    .put("name", call.name);
                            encoded.set("arguments", call.arguments);
                            putNullableSessionText(encoded, "thoughtSignature", call.thoughtSignature);
                        }
                    }
                }
                case ToolResultMessage result -> {
                    node.put("toolCallId", result.toolCallId);
                    node.put("toolName", result.toolName);
                    node.put("isError", result.isError);
                    if (result.details != null) {
                        node.set("details", Json.MAPPER.valueToTree(result.details));
                    }
                    encodeSessionUserContent(content, result.content);
                }
            }
            appendSessionEntry(sessionId, "message", node);
        }
    }

    /**
     * Appends a compaction boundary without rewriting the prior transcript. On
     * resume, the latest boundary rebuilds the active model context from that
     * checkpoint and later messages only.
     */
    public void appendSessionCompaction(CompactionResult result) throws IOException {
        Objects.requireNonNull(result, "result");
        if (result.summary == null || result.summary.isBlank()) {
            throw new IllegalArgumentException("Compaction summary must not be blank");
        }
        ObjectNode checkpoint = jsonObject();
        checkpoint.put("summary", result.summary);
        checkpoint.put("tokensBefore", result.tokensBefore);
        checkpoint.put("estimatedTokensAfter", result.estimatedTokensAfter);
        appendSessionEntry(sessionId, "compaction", checkpoint);
    }

    // ------------------------------------------------------------ session store

    public List<SessionSnapshot> listSessions(Path cwd) throws IOException {
        return listAllSessions(cwd).stream().filter(session -> session.parentSessionId == null).toList();
    }

    private List<SessionSnapshot> listAllSessions(Path cwd) throws IOException {
        Set<String> ids = new LinkedHashSet<>();
        for (Path directory : Stream.concat(
                Stream.of(this.directory), legacyDirectories.stream()).toList()) {
            if (!Files.isDirectory(directory)) continue;
            try (Stream<Path> files = Files.list(directory)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                        .map(path -> path.getFileName().toString().replaceFirst("\\.jsonl$", ""))
                        .forEach(ids::add);
            }
        }
        Path normalizedCwd = cwd == null ? null : cwd.toAbsolutePath().normalize();
        List<SessionSnapshot> snapshots = new ArrayList<>();
        for (String id : ids) {
            try {
                SessionSnapshot snapshot = sessionSnapshot(id);
                boolean sameDirectory = normalizedCwd == null;
                if (!sameDirectory) try {
                    sameDirectory = snapshot.cwd.toRealPath().equals(normalizedCwd.toRealPath());
                } catch (IOException ignored) {
                    sameDirectory = snapshot.cwd.equals(normalizedCwd);
                }
                if (sameDirectory) snapshots.add(snapshot);
            } catch (IOException | IllegalArgumentException ignored) {
                // Discovery is best effort: one corrupt session must not hide the rest.
            }
        }
        snapshots.sort(Comparator.comparing(
                (SessionSnapshot snapshot) -> snapshot.modified).reversed());
        return List.copyOf(snapshots);
    }

    /**
     * Resolves the session directory, dropping legacy directories that duplicate it.
     */
    public void sessionStore(Path directory, List<Path> legacyDirectories) {
        Path resolved = directory.toAbsolutePath().normalize();
        this.directory = resolved;
        this.legacyDirectories = legacyDirectories.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .filter(path -> !path.equals(resolved))
                .toList();
    }

    void defaultSessionStore() {
        sessionStore(
                applicationPaths.sessionsDirectory(),
                List.of(applicationPaths.legacySessionsDirectory()));
    }

    /**
     * Appends a typed payload to an existing session.
     */
    private void appendSessionEntry(String sessionId, String type, JsonNode payload)
            throws IOException {
        validateSessionId(sessionId);
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Session entry type must not be blank");
        }
        if (payload == null) {
            throw new IllegalArgumentException("Session entry payload must not be null");
        }
        Path file = existingSessionPath(sessionId);
        if (file == null) {
            throw new IOException("Unknown session: " + sessionId);
        }
        ObjectNode entry = jsonObject();
        entry.put("timestamp", System.currentTimeMillis());
        entry.put("type", type);
        entry.set("payload", payload);
        try (BufferedWriter writer =
                     Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.APPEND)) {
            writer.write(Json.MAPPER.writeValueAsString(entry));
            writer.newLine();
        }
    }

    /**
     * Reads and validates all complete entries in file order.
     */
    public List<SessionEntry> readSession(String sessionId) throws IOException {
        validateSessionId(sessionId);
        Path file = existingSessionPath(sessionId);
        if (file == null) {
            throw new IOException("Unknown session: " + sessionId);
        }
        List<SessionEntry> entries = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            int index = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                index++;
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node;
                try {
                    node = Json.MAPPER.readTree(line);
                } catch (IOException e) {
                    throw new IOException("Invalid JSONL entry at " + file + ":" + index, e);
                }
                if (!node.isObject()
                        || !node.path("timestamp").isIntegralNumber()
                        || !node.path("type").isTextual()
                        || !node.has("payload")) {
                    throw new IOException("Invalid JSONL entry at " + file + ":" + index);
                }
                entries.add(new SessionEntry(
                        node.path("timestamp").asLong(), node.path("type").asText(), node.path("payload")));
            }
        }
        return List.copyOf(entries);
    }

    /**
     * Loads metadata, the complete transcript, and compaction-aware continuation context.
     */
    public SessionSnapshot sessionSnapshot(String sessionId) throws IOException {
        List<SessionEntry> entries = readSession(sessionId);
        if (entries.isEmpty() || !entries.getFirst().type.equals("session_start")) {
            throw new IOException("Session has no session_start entry: " + sessionId);
        }
        SessionEntry start = entries.getFirst();
        JsonNode payload = start.payload;
        String cwdText = requiredSessionPayloadText(payload, "cwd", sessionId);
        String provider = requiredSessionPayloadText(payload, "provider", sessionId);
        String model = requiredSessionPayloadText(payload, "model", sessionId);
        String name = optionalSessionText(payload, "name");
        String parentSessionId = optionalSessionText(payload, "parentSessionId");
        String task = optionalSessionText(payload, "task");
        String level = optionalSessionText(payload, "thinkingLevel");
        String lifecycle = optionalSessionText(payload, "lifecycle");
        AgentMode savedMode = sessionAgentMode(payload);
        ObjectNode savedTaskState = null;
        List<Message> transcriptMessages = new ArrayList<>();
        List<Message> messages = new ArrayList<>();
        String firstMessage = "";
        StringBuilder allMessages = new StringBuilder();
        long modified = start.timestamp;
        for (SessionEntry entry : entries) {
            modified = Math.max(modified, entry.timestamp);
            if (entry.type.equals("agent_lifecycle")) lifecycle = optionalSessionText(entry.payload, "status");
            if (entry.type.equals("agent_mode_change")) savedMode = sessionAgentMode(entry.payload);
            if (entry.type.equals("workspace_change")) {
                cwdText = requiredSessionPayloadText(entry.payload, "cwd", sessionId);
            }
            if (entry.type.equals("task_state")) {
                if (!(entry.payload instanceof ObjectNode object)) throw new IOException("Invalid task_state entry in " + sessionId);
                TaskState validator = new TaskState(ignored -> {});
                validator.restore(object);
                savedTaskState = object.deepCopy();
            }
            if (entry.type.equals("compaction")) {
                messages.clear();
                String summary = requiredSessionPayloadText(entry.payload, "summary", sessionId);
                messages.add(new UserMessage(
                        List.of(new TextContent("[Conversation checkpoint]\n" + summary, null)), entry.timestamp));
                continue;
            }
            if (!entry.type.equals("message")) continue;
            Message message;
            try {
                if (entry.payload == null || !entry.payload.isObject() || !entry.payload.path("role").isTextual()) {
                    throw new IOException("Message payload must be an object with a string role");
                }
                long timestamp = entry.payload.path("timestamp").isIntegralNumber()
                        ? entry.payload.path("timestamp").asLong()
                        : System.currentTimeMillis();
                switch (entry.payload.path("role").asText()) {
                    case "user":
                        message = new UserMessage(
                                List.copyOf(decodeSessionUserContent(entry.payload.get("content"))), timestamp);
                        break;
                    case "assistant": {
                        AssistantMessage assistant = new AssistantMessage(
                                optionalSessionText(entry.payload, "api"),
                                optionalSessionText(entry.payload, "provider"),
                                optionalSessionText(entry.payload, "model"));
                        assistant.timestamp = timestamp;
                        assistant.responseModel = optionalSessionText(entry.payload, "responseModel");
                        assistant.responseId = optionalSessionText(entry.payload, "responseId");
                        assistant.errorMessage = optionalSessionText(entry.payload, "error");
                        assistant.rawStopReason = optionalSessionText(entry.payload, "rawStopReason");
                        String stopReason = optionalSessionText(entry.payload, "stopReason");
                        if (stopReason != null) {
                            try {
                                assistant.stopReason = Arrays.stream(StopReason.values())
                                        .filter(reason -> reason.wire.equals(stopReason))
                                        .findFirst()
                                        .orElseThrow(() -> new IllegalArgumentException("Unknown stop reason: " + stopReason));
                            } catch (IllegalArgumentException error) {
                                try {
                                    assistant.stopReason = StopReason.valueOf(stopReason.toUpperCase(Locale.ROOT));
                                } catch (IllegalArgumentException ignored) {
                                    throw new IOException("Unknown assistant stop reason: " + stopReason, error);
                                }
                            }
                        }
                        JsonNode node = entry.payload.get("usage");
                        if (node != null && node.isObject()) {
                            assistant.usage.input = node.path("input").asLong();
                            assistant.usage.output = node.path("output").asLong();
                            assistant.usage.cacheRead = node.path("cacheRead").asLong();
                            assistant.usage.cacheWrite = node.path("cacheWrite").asLong();
                            if (node.path("cacheWrite1h").isIntegralNumber()) {
                                assistant.usage.cacheWrite1h = node.path("cacheWrite1h").asLong();
                            }
                            if (node.path("reasoning").isIntegralNumber()) {
                                assistant.usage.reasoning = node.path("reasoning").asLong();
                            }
                            assistant.usage.totalTokens = node.path("totalTokens").asLong();
                            JsonNode cost = node.get("cost");
                            if (cost != null && cost.isObject()) {
                                assistant.usage.cost.input = cost.path("input").asDouble();
                                assistant.usage.cost.output = cost.path("output").asDouble();
                                assistant.usage.cost.cacheRead = cost.path("cacheRead").asDouble();
                                assistant.usage.cost.cacheWrite = cost.path("cacheWrite").asDouble();
                                assistant.usage.cost.total = cost.path("total").asDouble();
                            }
                        }
                        JsonNode content = entry.payload.get("content");
                        if (content != null && !content.isArray()) {
                            throw new IOException("Assistant message content must be an array");
                        }
                        if (content != null) {
                            for (JsonNode block : content) {
                                String type = requiredSessionText(block, "type");
                                switch (type) {
                                    case "text" -> assistant.content.add(new TextContent(
                                            requiredSessionText(block, "text"), optionalSessionText(block, "textSignature")));
                                    case "thinking" -> assistant.content.add(new ThinkingContent(
                                            requiredSessionText(block, "text"),
                                            optionalSessionText(block, "thinkingSignature"),
                                            block.path("redacted").asBoolean(false)));
                                    case "toolCall" -> {
                                        JsonNode arguments = block.get("arguments");
                                        if (!(arguments instanceof ObjectNode object)) {
                                            throw new IOException("Tool call arguments must be an object");
                                        }
                                        assistant.content.add(new ToolCall(
                                                requiredSessionText(block, "id"),
                                                requiredSessionText(block, "name"),
                                                object.deepCopy(),
                                                optionalSessionText(block, "thoughtSignature")));
                                    }
                                    default -> throw new IOException("Unknown assistant content type: " + type);
                                }
                            }
                        }
                        message = assistant;
                        break;
                    }
                    case "toolResult":
                        message = new ToolResultMessage(
                                requiredSessionText(entry.payload, "toolCallId"),
                                requiredSessionText(entry.payload, "toolName"),
                                List.copyOf(decodeSessionUserContent(entry.payload.get("content"))),
                                entry.payload.has("details")
                                        ? Json.MAPPER.treeToValue(entry.payload.get("details"), Object.class)
                                        : null,
                                entry.payload.path("isError").asBoolean(false),
                                timestamp);
                        break;
                    default:
                        throw new IOException("Unknown session message role: " + entry.payload.path("role").asText());
                }
            } catch (IOException | RuntimeException error) {
                throw new IOException("Invalid message in session " + sessionId, error);
            }
            transcriptMessages.add(message);
            messages.add(message);
            String text = switch (message) {
                case UserMessage user -> text(user);
                case AssistantMessage assistant -> text(assistant);
                case ToolResultMessage result -> text(result);
            };
            if (!text.isBlank()) {
                if (!allMessages.isEmpty()) allMessages.append(' ');
                allMessages.append(text);
                if (firstMessage.isEmpty() && message instanceof UserMessage) firstMessage = text;
            }
        }
        Path file = existingSessionPath(sessionId);
        if (file == null) throw new IOException("Unknown session: " + sessionId);
        Path sessionCwd;
        try {
            sessionCwd = Path.of(cwdText).toAbsolutePath().normalize();
        } catch (RuntimeException error) {
            throw new IOException("Session " + sessionId + " has an invalid cwd", error);
        }
        String firstMessage1 = firstMessage.isEmpty() ? "(no messages)" : firstMessage;
        String normalized = name == null ? null : name.strip();
        SessionSnapshot snapshot = new SessionSnapshot(
                Objects.requireNonNull(sessionId, "id"),
                normalized == null || normalized.isEmpty() ? null : normalized,
                Objects.requireNonNull(file, "path"),
                Objects.requireNonNull(sessionCwd, "cwd"),
                Objects.requireNonNull(provider, "provider"),
                Objects.requireNonNull(model, "model"),
                Objects.requireNonNull(Instant.ofEpochMilli(start.timestamp), "created"),
                Objects.requireNonNull(Instant.ofEpochMilli(modified), "modified"),
                transcriptMessages.size(),
                Objects.requireNonNull(firstMessage1, "firstMessage"),
                Objects.requireNonNull(allMessages.toString(), "allMessagesText"),
                List.copyOf(messages),
                List.copyOf(transcriptMessages));
        snapshot.parentSessionId = parentSessionId;
        snapshot.task = task;
        snapshot.thinkingLevel = level == null ? null : thinkingLevelFromWire(level);
        snapshot.lifecycle = lifecycle;
        snapshot.agentMode = savedMode;
        snapshot.taskState = savedTaskState;
        return snapshot;
    }

    private static AgentMode sessionAgentMode(JsonNode payload) throws IOException {
        if (!payload.has("agentMode")) return AgentMode.BUILD;
        try { return AgentMode.parse(payload.path("agentMode").asText()); }
        catch (IllegalArgumentException error) { throw new IOException("Invalid session agentMode", error); }
    }

    private Path existingSessionPath(String sessionId) {
        Path current = directory.resolve(sessionId + ".jsonl");
        if (Files.isRegularFile(current)) return current;
        for (Path legacyDirectory : legacyDirectories) {
            Path legacy = legacyDirectory.resolve(sessionId + ".jsonl");
            if (Files.isRegularFile(legacy)) return legacy;
        }
        return null;
    }

    private static String requiredSessionPayloadText(JsonNode payload, String field, String sessionId)
            throws IOException {
        JsonNode value = payload.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IOException("Session " + sessionId + " has no valid " + field);
        }
        return value.asText();
    }

    private void validateSessionId(String sessionId) {
        if (sessionId == null
                || !sessionId.matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("Invalid session id: " + sessionId);
        }
    }

    // ----------------------------------------------------------- settings store

    public void saveThinkingLevel(ThinkingLevel level) throws IOException {
        modifySettings(root -> root.put("defaultThinkingLevel", level.wire));
    }

    public void saveHideThinkingBlock(boolean hidden) throws IOException {
        modifySettings(root -> root.put("hideThinkingBlock", hidden));
    }

    public Settings loadSettings() throws IOException {
        Path resolved = applicationPaths.settingsFile();
        settingsStore(
                resolved, resolved.resolveSibling(resolved.getFileName() + ".lock"));
        ObjectNode root = readSettingsObject();
        String provider = optionalSettingsText(root, "defaultProvider");
        String model2 = optionalSettingsText(root, "defaultModel");
        String thinking = optionalSettingsText(root, "defaultThinkingLevel");
        ThinkingLevel thinkingLevel = null;
        if (thinking != null) {
            try {
                thinkingLevel = thinkingLevelFromWire(thinking);
            } catch (IllegalArgumentException error2) {
                throw new IOException(
                        "Invalid defaultThinkingLevel in " + settingsPath + ": " + thinking, error2);
            }
        }
        JsonNode value = root.get("hideThinkingBlock");
        if (value != null && !value.isNull() && !value.isBoolean()) {
            throw new IOException("Invalid setting hideThinkingBlock: expected a boolean");
        }
        return new Settings(
                provider, model2, thinkingLevel, value != null && value.asBoolean(false));
    }

    public void saveMcpPreference(String serverName, String toolName, boolean enabled) throws IOException {
        if (toolName != null) {
            requireSettingsValue(serverName, "serverName");
            requireSettingsValue(toolName, "toolName");
            modifySettings(root1 -> {
                ObjectNode server = settingsMcpServer(root1, serverName);
                ArrayNode disabledTools = null;
                JsonNode value1 = server.get("disabledTools");
                if (value1 != null && !value1.isNull()) {
                    if (!(value1 instanceof ArrayNode tools)) {
                        throw new UncheckedIOException(new IOException(
                                "Invalid MCP server \"" + serverName + "\": disabledTools must be an array"));
                    }
                    for (JsonNode tool1 : tools) {
                        settingsDisabledToolName(serverName, tool1);
                    }
                    disabledTools = tools;
                }
                if (enabled) {
                    if (disabledTools == null) return;
                    ArrayNode retained = Json.MAPPER.createArrayNode();
                    for (JsonNode tool : disabledTools) {
                        String name = settingsDisabledToolName(serverName, tool);
                        if (!name.equals(toolName))
                            retained.add(name);
                    }
                    if (retained.isEmpty())
                        server.remove("disabledTools");
                    else server.set("disabledTools", retained);
                    return;
                }
                if (disabledTools == null)
                    disabledTools = server.putArray("disabledTools");
                for (JsonNode tool : disabledTools) {
                    if (settingsDisabledToolName(serverName, tool).equals(toolName))
                        return;
                }
                disabledTools.add(toolName);
            });
        } else {
            requireSettingsValue(serverName, "serverName");
            modifySettings(root1 -> settingsMcpServer(root1, serverName).put("enabled", enabled));
        }
    }

    static Settings withSettingsDefaultModel(
            Settings settings, String provider, String model) {
        return new Settings(
                provider, model, settings.defaultThinkingLevel, settings.hideThinkingBlock);
    }

    void setSettingsDefaultModelAndProvider(String provider, String model)
            throws IOException {
        requireSettingsValue(provider, "provider");
        requireSettingsValue(model, "model");
        modifySettings(root -> {
            root.put("defaultProvider", provider);
            root.put("defaultModel", model);
        });
    }

    /**
     * Serializes an update across processes, preserves unknown settings, and
     * replaces the file atomically. The update reports invalid stored JSON with
     * UncheckedIOException, which is unwrapped into the declared IOException.
     */
    private void modifySettings(Consumer<ObjectNode> operation) throws IOException {
        synchronized (CREDENTIAL_FILES) {
            Path parent = settingsPath.getParent();
            if (parent == null) {
                throw new IOException("Settings path has no parent directory: " + settingsPath);
            }
            Files.createDirectories(parent);
            setPosixPermissions(parent, DIRECTORY_PERMISSIONS);
            try (FileChannel channel =
                         FileChannel.open(settingsLockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                setPosixPermissions(settingsLockPath, FILE_PERMISSIONS);
                ObjectNode root = readSettingsObject();
                operation.accept(root);
                Path temp = Files.createTempFile(settingsPath.getParent(), "settings-", ".json");
                try {
                    Files.writeString(
                            temp,
                            Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n",
                            StandardCharsets.UTF_8,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING);
                    setPosixPermissions(temp, FILE_PERMISSIONS);
                    try {
                        Files.move(temp, settingsPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException error) {
                        Files.move(temp, settingsPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                    setPosixPermissions(settingsPath, FILE_PERMISSIONS);
                } finally {
                    Files.deleteIfExists(temp);
                }
            } catch (UncheckedIOException error) {
                throw error.getCause();
            }
        }
    }

    private ObjectNode readSettingsObject() throws IOException {
        if (!Files.exists(settingsPath)) {
            return jsonObject();
        }
        JsonNode root;
        try {
            root = Json.MAPPER.readTree(Files.readString(settingsPath, StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw new IOException(
                    "Failed to read settings file " + settingsPath + ": " + error.getMessage(), error);
        }
        if (!(root instanceof ObjectNode object)) {
            throw new IOException("Invalid settings file " + settingsPath + ": expected a JSON object");
        }
        return object;
    }

    private static ObjectNode settingsMcpServer(ObjectNode root, String serverName) {
        JsonNode mcp = root.get("mcp");
        if (!(mcp instanceof ObjectNode servers)) {
            throw new UncheckedIOException(
                    new IOException("Cannot update MCP server \"" + serverName + "\": mcp must be an object"));
        }
        JsonNode server = servers.get(serverName);
        if (!(server instanceof ObjectNode object)) {
            throw new UncheckedIOException(
                    new IOException("Cannot update MCP server \"" + serverName + "\": server is not configured"));
        }
        return object;
    }

    private static String settingsDisabledToolName(String serverName, JsonNode tool) {
        if (!tool.isTextual() || tool.asText().isBlank()) {
            throw new UncheckedIOException(new IOException(
                    "Invalid MCP server \"" + serverName + "\": disabledTools must contain non-empty strings"));
        }
        return tool.asText();
    }

    private static String optionalSettingsText(ObjectNode root, String field) throws IOException {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IOException("Invalid setting " + field + ": expected a non-empty string");
        }
        return value.asText();
    }

    private void requireSettingsValue(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

}
