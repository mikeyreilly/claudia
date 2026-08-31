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
import java.util.Collections;
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
import java.util.concurrent.CancellationException;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.io.ByteArrayOutputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.InvalidPathException;
import java.nio.file.PathMatcher;
import java.nio.file.ProviderNotFoundException;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.quaxt.codingagent.agent.AgentEvent;
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
import com.quaxt.codingagent.ai.http.SseReader;
import com.quaxt.codingagent.ai.json.Json;
import com.quaxt.codingagent.ai.providers.AnthropicProvider;
import com.quaxt.codingagent.ai.providers.FauxProvider;
import com.quaxt.codingagent.ai.providers.OpenAiResponsesProvider;
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
import com.quaxt.codingagent.cli.ActivityStatus;
import com.quaxt.codingagent.cli.McpSelector;
import com.quaxt.codingagent.cli.TurnDetailsComponent;
import com.quaxt.codingagent.cli.session.SessionSnapshot;
import com.quaxt.codingagent.cli.tools.BuiltInTools;
import com.quaxt.codingagent.cli.tools.LocalTool;
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
import com.quaxt.codingagent.tui.AnsiRenderer;
import com.quaxt.codingagent.tui.FuzzyMatcher;
import com.quaxt.codingagent.tui.FuzzySelector;
import com.quaxt.codingagent.tui.Keybindings;
import com.quaxt.codingagent.tui.SelectItem;
import com.quaxt.codingagent.tui.Theme;
import com.quaxt.codingagent.tui.TuiComponent;
import com.quaxt.codingagent.tui.TuiFrame;
import com.quaxt.codingagent.tui.TuiInput;
import com.quaxt.codingagent.tui.TuiRuntime;
import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Attributes;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.jline.utils.InfoCmp.Capability;
import org.jline.utils.NonBlockingReader;
import org.jline.utils.Status;
import org.jline.utils.WCWidth;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

/** Main class. Should contain all application logic to the greatest extent that is reasonable.*/
public enum CodingAgentOperations implements Provider, CredentialStore {
    INSTANCE;
    private static final String APP_NAME = "codingagent";
    private static final String VERSION = "0.1.0-java";

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
    private static final String CHATGPT_PROVIDER_ID = "chatgpt";
    public static final URI CHATGPT_CODEX_API_BASE_URL = URI.create("https://chatgpt.com/backend-api/codex");
    private static final String CHATGPT_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    private static final String CHATGPT_ACCOUNT_ID = "accountId";
    private static final long CHATGPT_REFRESH_SKEW_MS = 5 * 60 * 1000L;
    private static final long CHATGPT_DEVICE_CODE_LIFETIME_MS = 15 * 60 * 1000L;
    private static final String CHATGPT_PROVIDER_NAME = "ChatGPT Plus/Pro";
    private static final Set<String> CHATGPT_CODEX_MODEL_IDS = Set.of(
            "gpt-5.3-codex",
            "gpt-5.3-codex-spark",
            "gpt-5.4",
            "gpt-5.5",
            "gpt-5.6-luna",
            "gpt-5.6-sol",
            "gpt-5.6-terra");

    // GitHub Copilot authentication and provider
    public static final String GITHUB_COPILOT_PROVIDER_ID = "github-copilot";
    private static final String GITHUB_COPILOT_CLIENT_ID = "Iv1.b507a08c87ecfe98";
    private static final String GITHUB_COPILOT_USER_AGENT = "GitHubCopilotChat/0.35.0";
    private static final String GITHUB_COPILOT_DEFAULT_BASE_URL = "https://api.individual.githubcopilot.com";
    private static final Pattern GITHUB_COPILOT_PROXY_ENDPOINT = Pattern.compile("(?:^|;)proxy-ep=([^;]+)");
    private static final long GITHUB_COPILOT_REFRESH_SKEW_MS = 5 * 60 * 1000L;
    private static final String GITHUB_COPILOT_PROVIDER_NAME = "GitHub Copilot";
    private static final String GITHUB_COPILOT_COMPLETIONS_BASE_URL =
            "https://api.individual.githubcopilot.com";

    private static final String GOOGLE_API = "google-generative-ai";
    private static final String OPENAI_COMPATIBLE_API = "openai-completions";

    /** Identifies which folded provider role an operations carrier represents. */
    private enum ProviderKind {
        CHATGPT,
        GITHUB_COPILOT,
        GOOGLE,
        OPENAI_COMPATIBLE
    }

    /** Pending ChatGPT device authorization presented to the user. */
    public static final class ChatGptDeviceCode {
        private String deviceAuthId;
        public String userCode;
        private URI verificationUri;
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
        private URI verificationUri;
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
    private static final class CopilotModelAccess {
        private int policiesEnabled;
        private List<Model> models;

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

    // Folded file credential store and provider-authentication state
    private Path authPath;
    private Path lockPath;
    private Path fallbackAuthPath;
    private CredentialStore credentials;
    private URI authBaseUrl;
    private String clientId;
    private URI githubBaseUrl;
    private URI copilotTokenUrl;
    private URI defaultCopilotBaseUrl;

    // Folded provider state. Each provider remains a distinct operations carrier.
    private ProviderKind providerKind;
    private String id;
    private List<Model> models;

    private AnthropicProvider anthropic;
    private OpenAiResponsesProvider responses;

    // Folded Agent and AgentState state
    private Provider agentProvider;
    private List<Consumer<AgentEvent>> listeners = new CopyOnWriteArrayList<>();
    private volatile AbortSignal activeSignal;
    private Retry.Policy retryPolicy = Retry.Policy.DEFAULT;
    private Model selectedModel;
    private ThinkingLevel thinkingLevel = ThinkingLevel.OFF;
    private List<AgentTool> tools = new ArrayList<>();
    private List<Message> messages = new ArrayList<>();
    private boolean isStreaming;
    private AssistantMessage streamingMessage;
    private Set<String> pendingToolCalls = new LinkedHashSet<>();
    private boolean isCompacting;
    private boolean autoCompactionEnabled = true;
    private int compactionReserveTokens = 16_384;

    // Repository instructions, sessions, settings, and git-ignore filtering
    private static final String AGENTS_FILE = "AGENTS.md";
    private static final String AGENTS_OVERRIDE_FILE = "AGENTS.override.md";
    private static final Duration GIT_IGNORE_TIMEOUT = Duration.ofSeconds(30);

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
    private static final class Settings {
        private String defaultProvider;
        private String defaultModel;
        private ThinkingLevel defaultThinkingLevel;
        private String theme;
        private boolean hideThinkingBlock;

        private Settings(
                String defaultProvider,
                String defaultModel,
                ThinkingLevel defaultThinkingLevel,
                String theme,
                boolean hideThinkingBlock) {
            this.defaultProvider = defaultProvider;
            this.defaultModel = defaultModel;
            this.defaultThinkingLevel = defaultThinkingLevel;
            this.theme = theme;
            this.hideThinkingBlock = hideThinkingBlock;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Settings that
                    && Objects.equals(defaultProvider, that.defaultProvider)
                    && Objects.equals(defaultModel, that.defaultModel)
                    && defaultThinkingLevel == that.defaultThinkingLevel
                    && Objects.equals(theme, that.theme)
                    && hideThinkingBlock == that.hideThinkingBlock;
        }

        @Override
        public int hashCode() {
            return Objects.hash(defaultProvider, defaultModel, defaultThinkingLevel, theme, hideThinkingBlock);
        }

        @Override
        public String toString() {
            return "Settings[defaultProvider=" + defaultProvider
                    + ", defaultModel=" + defaultModel
                    + ", defaultThinkingLevel=" + defaultThinkingLevel
                    + ", theme=" + theme
                    + ", hideThinkingBlock=" + hideThinkingBlock + "]";
        }
    }

    private Path repositoryRoot;
    private String baseSystemPrompt;
    private Path currentDirectory;
    private List<Path> sources = List.of();
    public String sessionId;
    private Path directory;
    private List<Path> legacyDirectories;
    private Path settingsPath;
    public String executable;

    // MCP manager and OAuth
    private static final int MCP_OAUTH_DEFAULT_CALLBACK_PORT = 19_876;
    private static final String MCP_OAUTH_DEFAULT_CALLBACK_PATH = "/mcp/oauth/callback";
    private static final Duration MCP_OAUTH_DEFAULT_CALLBACK_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration MCP_OAUTH_HTTP_TIMEOUT = Duration.ofSeconds(30);
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
        private String name;
        public McpState state;
        private String message;
        private int toolCount;
        private int enabledToolCount;
        private String target;
        private String authorizationUrl;

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
        private String serverName;
        private String name;
        private String description;
        private boolean enabled;

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

    // Interactive shell and terminal
    private static final List<String> SLASH_COMMANDS = List.of(
            "/compact", "/details", "/exit", "/fork", "/help", "/login", "/logout",
            "/mcp", "/models", "/quit", "/resume", "/settings", "/theme");
    private static final int VISIBLE_COMMANDS = 4;
    private static final int DEFAULT_COLUMNS = 80;
    private static final int DEFAULT_ROWS = 24;
    private static final String BEGIN_SYNCHRONIZED_OUTPUT = "\u001b[?2026h";
    private static final String END_SYNCHRONIZED_OUTPUT = "\u001b[?2026l";
    private static final String CLEAR_SCREEN_AND_SCROLLBACK = "\u001b[2J\u001b[H\u001b[3J";
    private static final String SECONDARY_PROMPT = "%M> ";
    private static final String BRACKETED_PASTE_END = "\u001b[201~";
    private static final long PASTE_LOOKAHEAD_MILLIS = 10;

    private enum StreamOutput {
        NONE,
        THINKING,
        TEXT
    }

    public enum StatusAccent {
        NONE,
        READY,
        ACTIVE,
        TOOL,
        WARNING
    }

    // InteractiveShell fields
    private Settings settings;
    private boolean agentConfigured;
    private boolean recordingSession;
    private String sessionName;
    private Path cwd = Path.of(".").toAbsolutePath().normalize();
    private boolean emittedText;
    private boolean hideThinkingBlock;
    private StreamOutput streamOutput = StreamOutput.NONE;
    private int streamedThinkingCharacters;
    private Object activityLock = new Object();
    private ScheduledExecutorService statusTicker;
    private volatile ActivityStatus activity;
    private volatile String statusLocation = "";
    private volatile String statusModel = "";

    // InteractiveTerminal fields
    private Terminal jlineTerminal;
    private LineReaderImpl reader;
    private Callable<Void> suspendAction;
    private boolean supportsSuspend;
    private Attributes shellAttributes;
    private Terminal.SignalHandler previousContinueHandler;
    private Terminal.SignalHandler previousResizeHandler;
    private StringBuilder screenDocument = new StringBuilder();
    private Status statusBar;
    private String statusActivity;
    private StatusAccent statusAccent = StatusAccent.NONE;
    private String statusLeft;
    private String statusRight;
    private Attributes fullScreenResumeAttributes;
    private volatile boolean managedSuspend;
    private volatile Theme theme = Theme.DARK;
    private String suspendedBuffer;
    private int suspendedCursor = -1;
    private int restoreCursor = -1;
    private boolean commandSuggestionsActive;
    private Supplier<AttributedString> dynamicPost;

    // CommandSuggestions fields
    private List<String> commands;
    private String query;
    private String dismissedBuffer;
    private List<String> matches = List.of();
    private int selectedIndex;
    private int visibleStart;

    // CLI arguments
    private boolean help;
    private boolean version;
    private boolean listModels;
    private boolean print;
    private String modelSearch;
    private String provider;
    private String model;
    private String apiKey;
    private String systemPrompt = "";
    private String message = "";
    private boolean noSession;
    private String mode = "print";

    private static final CancellationException SUSPEND_REQUESTED =
            new CancellationException("Interactive terminal suspend requested");



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
    private void onAbort(AbortSignal signal, Runnable listener) {
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

    private static String role(Message message) {
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
    private static String text(ToolResultMessage message) {
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
        return httpPost(url, "application/json", headers, body, timeoutMs, signal);
    }

    /**
     * POST an URL-encoded form body and return a streaming response.
     */
    private HttpTransport.Response httpPostForm(
            String url, Map<String, String> headers, byte[] body, Integer timeoutMs, AbortSignal signal)
            throws IOException {
        return httpPost(url, "application/x-www-form-urlencoded", headers, body, timeoutMs, signal);
    }

    private HttpTransport.Response httpPost(
            String url,
            String contentType,
            Map<String, String> headers,
            byte[] body,
            Integer timeoutMs,
            AbortSignal signal)
            throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs != null ? timeoutMs : HttpTransport.DEFAULT_TIMEOUT_MS))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        builder.header("content-type", contentType);
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (header.getValue() != null && !header.getKey().equalsIgnoreCase("content-type")) {
                builder.header(header.getKey(), header.getValue());
            }
        }
        return httpSend(builder.build(), signal);
    }

    private HttpTransport.Response httpGet(
            String url, Map<String, String> headers, Integer timeoutMs, AbortSignal signal) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs != null ? timeoutMs : HttpTransport.DEFAULT_TIMEOUT_MS))
                .GET();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (header.getValue() != null) {
                builder.header(header.getKey(), header.getValue());
            }
        }
        return httpSend(builder.build(), signal);
    }

    private HttpTransport.Response httpSend(HttpRequest request, AbortSignal signal) throws IOException {
        if (signal != null && isAborted(signal)) {
            throw new HttpTransport.AbortedException();
        }
        CompletableFuture<HttpResponse<InputStream>> future =
                HttpTransport.CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        if (signal != null) {
            onAbort(signal, () -> future.cancel(true));
        }
        HttpResponse<InputStream> response;
        try {
            response = future.get();
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

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String errorBody;
            try (InputStream stream = response.body()) {
                errorBody = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            int status = response.statusCode();
            String trimmed = errorBody.trim();
            String detail = trimmed.length() <= HttpException.MAX_ERROR_BODY_CHARS
                    ? trimmed
                    : trimmed.substring(0, HttpException.MAX_ERROR_BODY_CHARS) + "... [truncated "
                      + (trimmed.length() - HttpException.MAX_ERROR_BODY_CHARS) + " chars]";
            throw new HttpException(
                    status, errorBody, trimmed.isEmpty() ? status + " status code (no body)" : status + ": " + detail);
        }
        InputStream bodyStream = response.body();
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

    // ----------------------------------------------------------- credentials

    /**
     * Resolves the credential file, its sibling lock file, and the optional legacy file.
     */
    public void fileCredentialStore(Path authPath, Path fallbackAuthPath) {
        Path resolved = authPath.toAbsolutePath().normalize();

        this.authPath = resolved;
        lockPath = resolved.resolveSibling(resolved.getFileName() + ".lock");
        this.fallbackAuthPath =
                fallbackAuthPath == null ? null : fallbackAuthPath.toAbsolutePath().normalize();
    }

    private static String credentialType(Credential credential) {
        return switch (credential) {
            case Credential.ApiKeyCredential ignored -> "api_key";
            case Credential.OAuthCredential ignored -> "oauth";
        };
    }

    private CodingAgentOperations defaultCredentialStore() {
        Path home = Path.of(System.getProperty("user.home"));
        fileCredentialStore(
                home.resolve(".codingagent").resolve("auth.json"),
                home.resolve(".pi-java").resolve("auth.json"));
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
        switch (store) {
            case CodingAgentOperations file -> {
                validateProviderId(providerId);
                if (operation == null) {
                    throw new IllegalArgumentException("operation must not be null");
                }
                Files.createDirectories(file.authPath.getParent());
                setPosixPermissions(file.authPath.getParent(), DIRECTORY_PERMISSIONS);
                try (FileChannel channel =
                             FileChannel.open(file.lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
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

    private void deleteCredential(CredentialStore store, String providerId) throws IOException {
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
    public void chatGptAuth(CredentialStore credentials, URI authBaseUrl, String clientId) {
        URI validatedBaseUrl = requireAbsoluteHttpUri(authBaseUrl, "authBaseUrl");
        if (clientId == null || clientId.isBlank()) throw new IllegalArgumentException("clientId must not be blank");

        this.credentials = credentials;
        this.authBaseUrl = validatedBaseUrl;
        this.clientId = clientId;
    }

    /**
     * Starts the Codex device flow. Display the URI and code before completing it.
     */
    public ChatGptDeviceCode chatGptBeginLogin() throws IOException {
        ObjectNode request = jsonObject().put("client_id", clientId);
        JsonNode response = authPost(
                authBaseUrl.resolve("/api/accounts/deviceauth/usercode"),
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
                authBaseUrl.resolve("/codex/device"),
                interval,
                System.currentTimeMillis() + CHATGPT_DEVICE_CODE_LIFETIME_MS);
    }

    /**
     * Waits for browser authorization, exchanges the code, and saves refreshable tokens.
     */
    public Credential.OAuthCredential chatGptCompleteLogin(ChatGptDeviceCode device)
            throws IOException, InterruptedException {
        JsonNode authorization = null;
        ObjectNode request = jsonObject()
                .put("device_auth_id", device.deviceAuthId)
                .put("user_code", device.userCode);
        while (System.currentTimeMillis() < device.expiresAtMs) {
            try {
                authorization = authPost(
                        authBaseUrl.resolve("/api/accounts/deviceauth/token"),
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
        form.put("client_id", clientId);
        form.put("code_verifier", requiredAuthText(authorization, "code_verifier", "OpenAI"));
        Credential.OAuthCredential credential =
                chatGptCredentialFromTokenResponse(authPost(
                        authBaseUrl.resolve("/oauth/token"),
                        Map.of("Accept", "application/json"),
                        mcpFormEncode(form).getBytes(StandardCharsets.UTF_8),
                        true), null);
        modifyCredential(credentials, CHATGPT_PROVIDER_ID, ignored -> credential);
        return credential;
    }

    /**
     * Returns a usable ChatGPT bearer token, refreshing it when close to expiry.
     */
    public ChatGptToken chatGptResolveToken() throws IOException {
        Credential credential = readCredential(credentials, CHATGPT_PROVIDER_ID)
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
        form.put("client_id", clientId);
        Credential.OAuthCredential refreshed =
                chatGptCredentialFromTokenResponse(authPost(
                        authBaseUrl.resolve("/oauth/token"),
                        Map.of("Accept", "application/json"),
                        mcpFormEncode(form).getBytes(StandardCharsets.UTF_8),
                        true), oauth);
        modifyCredential(credentials, CHATGPT_PROVIDER_ID, ignored -> refreshed);
        return chatGptToken(refreshed);
    }

    public boolean chatGptHasCredential() throws IOException {
        return hasRefreshCredential(credentials, CHATGPT_PROVIDER_ID);
    }

    public void chatGptLogout() throws IOException {
        deleteCredential(credentials, CHATGPT_PROVIDER_ID);
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
        HttpTransport.Response response = form
                ? httpPostForm(url.toString(), headers, body, null, null)
                : httpPostJson(url.toString(), headers, body, null, null);
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
            CredentialStore credentials, URI githubBaseUrl, URI copilotTokenUrl, URI defaultCopilotBaseUrl) {
        this.credentials = credentials;
        this.githubBaseUrl = requireAbsoluteHttpUri(githubBaseUrl, "githubBaseUrl");
        this.copilotTokenUrl = requireAbsoluteHttpUri(copilotTokenUrl, "copilotTokenUrl");
        this.defaultCopilotBaseUrl = requireAbsoluteHttpUri(defaultCopilotBaseUrl, "defaultCopilotBaseUrl");
    }

    /**
     * Starts the device flow. Display the resulting URI and code before completing the login.
     */
    public GitHubCopilotDeviceCode gitHubCopilotBeginLogin() throws IOException {
        JsonNode response = authPost(
                githubBaseUrl.resolve("/login/device/code"),
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
    public Credential.OAuthCredential gitHubCopilotCompleteLogin(GitHubCopilotDeviceCode device) throws IOException, InterruptedException {
        String githubAccessToken = null;
        int intervalSeconds = device.intervalSeconds;
        while (System.currentTimeMillis() < device.expiresAtMs) {
            JsonNode response = authPost(
                    githubBaseUrl.resolve("/login/oauth/access_token"),
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
        Credential.OAuthCredential credential = createCopilotCredential(githubAccessToken, null);
        try {
            credential = withCopilotAvailableModels(credential);
        } catch (IOException ignored) {
            // The Copilot token is valid even if its optional model catalog is transiently unavailable.
        }
        Credential.OAuthCredential saved = credential;
        modifyCredential(credentials, GITHUB_COPILOT_PROVIDER_ID, ignored -> saved);
        return credential;
    }

    /**
     * Returns a valid Copilot API token, refreshing it from the stored GitHub token when necessary.
     */
    public CopilotToken gitHubCopilotResolveToken() throws IOException {
        Credential credential = readCredential(credentials, GITHUB_COPILOT_PROVIDER_ID)
                .orElseThrow(() -> new IOException("GitHub Copilot is not logged in. Run /login."));
        if (!(credential instanceof Credential.OAuthCredential oauth)) {
            throw new IOException("GitHub Copilot credential is not an OAuth credential. Run /login.");
        }
        if (!(oauth.expires <= System.currentTimeMillis()) && !oauth.access.isBlank()) {
            return copilotToken(oauth);
        }
        Credential.OAuthCredential refreshed = createCopilotCredential(oauth.refresh, oauth.availableModelIds);
        try {
            refreshed = withCopilotAvailableModels(refreshed);
        } catch (IOException ignored) {
            // Retain the last known entitlement list if model discovery cannot be refreshed.
        }
        Credential.OAuthCredential saved = refreshed;
        modifyCredential(credentials, GITHUB_COPILOT_PROVIDER_ID, ignored -> saved);
        return copilotToken(refreshed);
    }

    /**
     * Reports whether a saved GitHub OAuth credential can be refreshed.
     */
    private boolean gitHubCopilotHasCredential() throws IOException {
        return hasRefreshCredential(credentials, GITHUB_COPILOT_PROVIDER_ID);
    }

    private void gitHubCopilotLogout() throws IOException {
        deleteCredential(credentials, GITHUB_COPILOT_PROVIDER_ID);
    }

    /**
     * Enables the listed Copilot model policies and reports how many policy requests GitHub accepted.
     */
    public int gitHubCopilotEnableModels(List<String> modelIds) throws IOException {
        CopilotToken token = gitHubCopilotResolveToken();
        int enabled = 0;
        for (String modelId : modelIds) {
            URI policyUrl = token.baseUrl.resolve("/models/" + encodeUrlPathSegment(modelId) + "/policy");
            Map<String, String> headers = copilotModelHeaders(token.accessToken);
            headers.put("openai-intent", "chat-policy");
            headers.put("x-interaction-type", "chat-policy");
            try {
                HttpTransport.Response response = httpPostJson(
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
    public CopilotToken gitHubCopilotRefreshAvailableModels()
            throws IOException {
        CopilotToken current = gitHubCopilotResolveToken();
        Credential credential = readCredential(credentials, GITHUB_COPILOT_PROVIDER_ID)
                .orElseThrow(() -> new IOException("GitHub Copilot is not logged in. Run /login."));
        if (!(credential instanceof Credential.OAuthCredential oauth)) {
            throw new IOException("GitHub Copilot credential is not an OAuth credential. Run /login.");
        }
        Credential.OAuthCredential refreshed = new Credential.OAuthCredential(
                oauth.access,
                oauth.refresh,
                oauth.expires,
                List.copyOf(fetchCopilotAvailableModelIds(current.accessToken)),
                Map.of());
        modifyCredential(credentials, GITHUB_COPILOT_PROVIDER_ID, ignored -> refreshed);
        return copilotToken(refreshed);
    }

    private Credential.OAuthCredential createCopilotCredential(String githubAccessToken, List<String> availableModelIds) throws IOException {
        if (githubAccessToken == null || githubAccessToken.isBlank()) {
            throw new IOException("GitHub returned an empty access token");
        }
        JsonNode response;
        HttpTransport.Response http = httpGet(
                copilotTokenUrl.toString(), copilotHeaders("token " + githubAccessToken), null, null);
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

    private Credential.OAuthCredential withCopilotAvailableModels(Credential.OAuthCredential credential) throws IOException {
        List<String> available = fetchCopilotAvailableModelIds(credential.access);
        return new Credential.OAuthCredential(
                credential.access, credential.refresh, credential.expires, List.copyOf(available), Map.of());
    }

    private List<String> fetchCopilotAvailableModelIds(String copilotToken)
            throws IOException {
        URI modelsUrl = copilotBaseUrlFromToken(copilotToken).resolve("/models");
        JsonNode response;
        HttpTransport.Response http = httpGet(modelsUrl.toString(), copilotModelHeaders(copilotToken), 5_000, null);
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
                && copilotBaseUrlFromToken(copilotToken)
                .toString()
                .equals(GITHUB_COPILOT_DEFAULT_BASE_URL)
                ? List.copyOf(policyEnabled)
                : List.copyOf(pickerEnabled);
    }

    private CopilotToken copilotToken(Credential.OAuthCredential credential) {
        List<String> availableModelIds = credential.availableModelIds;
        return new CopilotToken(
                credential.access,
                copilotBaseUrlFromToken(credential.access),
                availableModelIds == null ? null : List.copyOf(availableModelIds));
    }

    private URI copilotBaseUrlFromToken(String token) {
        Matcher match = GITHUB_COPILOT_PROXY_ENDPOINT.matcher(token);
        if (!match.find()) {
            return defaultCopilotBaseUrl;
        }
        String host = match.group(1);
        if (!host.matches("[A-Za-z0-9.-]+")) {
            return defaultCopilotBaseUrl;
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

    private void coreProviders(Map<String, Provider> providers) {
        coreProviders = providers;
    }

    private CodingAgentOperations chatGptProvider(List<Model> models, OpenAiResponsesProvider responses) {
        providerKind = ProviderKind.CHATGPT;
        this.models = models;
        this.responses = responses;
        return this;
    }

    public void googleProvider(List<Model> models) {
        providerKind = ProviderKind.GOOGLE;
        this.models = models;
    }

    private Provider requireCoreProvider(String id) {
        Provider provider = coreProviders.get(id);
        if (provider == null) {
            throw new IllegalArgumentException("Unknown core provider: " + id);
        }
        return provider;
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

    private static String displayError(Exception error) {
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
                    target.set("input_schema", tool1.parameters);
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
        return options.baseUrl == null || options.baseUrl.isBlank() ? model.baseUrl : options.baseUrl;
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
        JsonNode parsed = Json.MAPPER.readTree(tool.arguments.toString());
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
     * Validates the identity and endpoint of a Chat Completions-compatible service.
     */
    public void openAiCompatibleProvider(
            List<Model> models) {
        providerKind = ProviderKind.OPENAI_COMPATIBLE;
        this.id = requireNonBlank(CodingAgentOperations.GITHUB_COPILOT_PROVIDER_ID, "id");
        requireNonBlank(CodingAgentOperations.GITHUB_COPILOT_PROVIDER_NAME, "name");
        trimTrailingSlash(requireNonBlank(CodingAgentOperations.GITHUB_COPILOT_COMPLETIONS_BASE_URL, "baseUrl"));
        this.models = List.copyOf(models);
    }

    private AssistantMessageEventStream openAiCompatibleStream(Model model, Context context, StreamOptions options) {
        if (!model.api.equals(OPENAI_COMPATIBLE_API)) {
            throw new IllegalArgumentException("Model " + model + " is not a Chat Completions model");
        }
        return providerStream(model, options, (stream, output, requestOptions) -> {
            Map<String, String> headers = new LinkedHashMap<>(model.headers);
            headers.putAll(requestOptions.headers);
            String key = requestOptions.apiKey;
            if (key == null || key.isBlank()) {
                key = resolveSystemApiKey(this.id).orElse(null);
            }
            if (!headers.containsKey("Authorization") && !headers.containsKey("authorization")) {
                if (key == null || key.isBlank()) {
                    throw new IllegalStateException("No API key for provider: " + this.id);
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
    public CodingAgentOperations newGitHubCopilotProvider(List<Model> models) {
        List<Model> all = List.copyOf(models);
        List<Model> models1 = copilotModelsFor(all, OpenAiResponsesProvider.API);
        this.models = all;

        anthropic = new AnthropicProvider(
                        GITHUB_COPILOT_PROVIDER_ID,
                        GITHUB_COPILOT_PROVIDER_NAME,
                        List.copyOf(copilotModelsFor(all, AnthropicProvider.API)),
                        List.of(),
                        true);
        openAiCompatibleProvider(
                copilotModelsFor(all, OPENAI_COMPATIBLE_API));
        responses = new OpenAiResponsesProvider(
                        GITHUB_COPILOT_PROVIDER_ID,
                        GITHUB_COPILOT_PROVIDER_NAME,
                        List.copyOf(models1),
                        List.of(),
                        null,
                        OpenAiResponsesProvider.RequestProfile.STANDARD);
        providerKind = ProviderKind.GITHUB_COPILOT;
        return this;
    }

    private static List<Model> copilotModelsFor(List<Model> models, String api) {
        return models.stream().filter(model -> model.api.equals(api)).toList();
    }

    /**
     * Filters the catalog to models GitHub reports as enabled for the signed-in account.
     */
    private List<Model> gitHubCopilotAvailableModels() throws IOException {
        List<String> enabled = gitHubCopilotResolveToken().availableModelIds;
        return filterEnabledCopilotModels(enabled);
    }

    /**
     * Enables catalog model policies, then refreshes the account's enabled-model list.
     */
    private CopilotModelAccess gitHubCopilotEnableAndRefreshModels() throws IOException {
        int policiesEnabled =
                gitHubCopilotEnableModels(models.stream().map(model -> model.id).toList());
        List<Model> available = filterEnabledCopilotModels(gitHubCopilotRefreshAvailableModels().availableModelIds);
        return new CopilotModelAccess(policiesEnabled, List.copyOf(available));
    }

    private List<Model> filterEnabledCopilotModels(List<String> enabled) {
        if (enabled == null) {
            return models;
        }
        return models.stream().filter(model -> enabled.contains(model.id)).toList();
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


    private static List<Model> providerModels(Provider provider) {
        return switch (provider) {
            case AnthropicProvider anthropic -> anthropic.models;
            case CodingAgentOperations folded -> folded.models;
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
            case CodingAgentOperations chatGpt when chatGpt.providerKind == ProviderKind.CHATGPT -> {
                if (!model.provider.equals(CHATGPT_PROVIDER_ID)) {
                    throw new IllegalArgumentException("Model " + model + " is not a ChatGPT subscription model");
                }
                StreamOptions requestOptions = options == null ? new StreamOptions() : copyStreamOptions(options);
                try {
                    configureCodexRequest(requestOptions, chatGpt.chatGptResolveToken());
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
            case CodingAgentOperations copilot when copilot.providerKind == ProviderKind.GITHUB_COPILOT -> {
                if (!model.provider.equals(GITHUB_COPILOT_PROVIDER_ID)) {
                    throw new IllegalArgumentException("Model " + model + " is not a GitHub Copilot model");
                }
                StreamOptions requestOptions = options == null ? new StreamOptions() : copyStreamOptions(options);
                if (requestOptions.apiKey == null || requestOptions.apiKey.isBlank()) {
                    try {
                        CopilotToken token = copilot.gitHubCopilotResolveToken();
                        requestOptions.apiKey = token.accessToken;
                        requestOptions.baseUrl = token.baseUrl.toString();
                    } catch (IOException error) {
                        yield providerErrorStream(model, error);
                    }
                }
                yield switch (model.api) {
                    case "anthropic-messages" -> anthropicStream(copilot.anthropic, model, context, requestOptions);
                    case "openai-completions" ->
                            copilot.openAiCompatibleStream(model, context, requestOptions);
                    case "openai-responses" -> openAiResponsesStream(copilot.responses, model, context, requestOptions);
                    default -> throw new IllegalArgumentException("Unsupported GitHub Copilot model API: " + model.api);
                };
            }
            case CodingAgentOperations google when google.providerKind == ProviderKind.GOOGLE -> {
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
            case CodingAgentOperations compatible when compatible.providerKind == ProviderKind.OPENAI_COMPATIBLE ->
                    compatible.openAiCompatibleStream(model, context, options);
            case OpenAiResponsesProvider responses -> openAiResponsesStream(responses, model, context, options);
            default -> throw unknownProvider(provider);
        };
    }

    private static IllegalArgumentException unknownProvider(Provider provider) {
        return new IllegalArgumentException(
                "Unknown provider carrier: " + (provider == null ? "null" : provider.getClass().getName()));
    }

    // ---------------------------------------------------------------- agent

    private static final String COMPACTION_SYSTEM_PROMPT =
            "You summarize coding-agent conversations. Do not continue the conversation. "
                    + "Return a concise structured checkpoint covering the goal, completed work, current state, decisions, and next steps.";

    /** Creates the folded mutable state for one agent. */
    private void agentState(String systemPrompt, Model model) {
        this.systemPrompt = systemPrompt;
        selectedModel = model;
    }

    /** Configures the singleton as the active agent. */
    private void agent(Provider provider) {
        agentProvider = provider;
    }

    /**
     * Registers an agent event listener; closing the result unsubscribes it.
     */
    private AutoCloseable subscribe(Consumer<AgentEvent> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /**
     * Cancels the turn in flight, if any.
     */
    private void abort() {
        AbortSignal signal = activeSignal;
        if (signal != null) {
            abort(signal);
        }
    }

    /**
     * Summarizes all active messages in a separate model call, then replaces
     * them with a single checkpoint message. The caller remains responsible for
     * persisting the original transcript if it needs complete history.
     */
    private CompactionResult compact(String customInstructions) throws InterruptedException {
        if (isStreaming || isCompacting) {
            throw new IllegalStateException("Agent is already processing");
        }
        if (messages.isEmpty()) {
            throw new IllegalStateException("Cannot compact an empty conversation");
        }
        isCompacting = true;
        try {
            return performCompaction(compactionPrompt(messages, customInstructions), false);
        } finally {
            isCompacting = false;
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
        AbortSignal signal = new AbortSignal();
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
        emit(new AgentEvent.CompactionEnd(result));
        return result;
    }

    /**
     * Runs a prompt to completion, returning only messages created during this invocation.
     */
    private List<Message> prompt(String text) throws InterruptedException {
        Message[] prompts = new Message[]{userMessage(text)};
        if (isStreaming || isCompacting) {
            throw new IllegalStateException("Agent is already processing");
        }
        activeSignal = new AbortSignal();
        isStreaming = true;
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
                emit(new AgentEvent.MessageStart(prompt));
                emit(new AgentEvent.MessageEnd(prompt));
            }

            while (true) {
                AssistantMessage[] lastAttempt = new AssistantMessage[1];
                AssistantMessage response = retryAssistantCall(
                        () -> {
                            Context context = new Context(systemPrompt);
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
            case LocalTool local -> local.name;
            case McpAgentTool mcp -> mcp.name;
            default -> throw unknownTool(tool);
        };
    }

    public static String toolDescription(AgentTool tool) {
        return switch (tool) {
            case FunctionTool function -> function.description;
            case LocalTool local -> local.description;
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
            case LocalTool local -> local.parameters;
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
        return switch (tool) {
            case FunctionTool function ->
                    function.execute.apply(new ToolInvocation(toolCallId, arguments, signal, onUpdate));
            case LocalTool local -> switch (local.kind) {
                case READ -> {
                    AgentTool.ToolResult result;
                    String pathText = requiredToolText(arguments, "path");
                    BuiltInTools.ArchiveLocation location = localToolArchiveLocation(local, pathText);
                    if (location == null) {
                        result = readToolFile(localToolPath(local, pathText), arguments, signal);
                    } else {
                        if (location.entry.startsWith("/")) {
                            throw new IllegalArgumentException("Archive entry paths must not start with '/'");
                        }
                        FileSystem archive;
                        try {
                            archive = FileSystems.newFileSystem(location.archive);
                        } catch (IOException | ProviderNotFoundException error) {
                            throw new IOException("Unable to open archive " + location.archive + ": " + error.getMessage(), error);
                        }
                        try (archive) {
                            Path root = archive.getPath("/");
                            Path entry = location.entry.isEmpty() ? root : root.resolve(location.entry).normalize();
                            if (!entry.startsWith(root)) {
                                throw new IllegalArgumentException("Archive entry paths must stay within the archive");
                            }
                            requireNotAborted(signal);
                            if (Files.isDirectory(entry)) {
                                List<String> entries;
                                try (var paths = Files.list(entry)) {
                                    entries = paths.sorted(
                                                    Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                                            .map(entry1 -> {
                                                if (Files.isDirectory(entry1)) return entry1.getFileName() + "/";
                                                try {
                                                    return entry1.getFileName() + " (" + Files.size(entry1) + " bytes)";
                                                } catch (IOException ignored) {
                                                    return entry1.getFileName().toString();
                                                }
                                            })
                                            .toList();
                                }
                                result = toolResultText(entries.isEmpty() ? "(empty directory)" : boundToolOutput(String.join("\n", entries), null));
                            } else {
                                if (!Files.isRegularFile(entry)) {
                                    String entry1 = location.entry;
                                    String withoutTrailingSlash = entry1.endsWith("/") ? entry1.substring(0, entry1.length() - 1) : entry1;
                                    int slash = withoutTrailingSlash.lastIndexOf('/');
                                    String prefix = slash < 0 ? "" : withoutTrailingSlash.substring(0, slash + 1);
                                    List<String> nearby = List.of();
                                    if (!prefix.isEmpty()) {
                                        try (var paths = Files.walk(root)) {
                                            nearby = paths.filter(Files::isRegularFile)
                                                    .map(path -> root.relativize(path).toString().replace('\\', '/'))
                                                    .filter(name -> name.startsWith(prefix))
                                                    .sorted(String.CASE_INSENSITIVE_ORDER)
                                                    .limit(20)
                                                    .toList();
                                        }
                                    }
                                    if (nearby.isEmpty()) {
                                        try (var paths = Files.list(root)) {
                                            nearby = paths.map(path -> path.getFileName() + (Files.isDirectory(path) ? "/" : ""))
                                                    .sorted(String.CASE_INSENSITIVE_ORDER)
                                                    .limit(20)
                                                    .toList();
                                        }
                                    }
                                    String suggestions = nearby.isEmpty() ? "(archive is empty)" : String.join("\n", nearby);
                                    throw new IOException("Archive entry not found: " + entry1 + " in " + location.archive
                                            + ". Nearby entries:\n" + suggestions);
                                }
                                result = readToolFile(entry, arguments, signal);
                            }
                        }
                    }
                    yield result;
                }
                case WRITE -> {
                    String pathText = requiredToolText(arguments, "path");
                    rejectArchivePath(local, pathText);
                    Path file = localToolPath(local, pathText);
                    String content = requiredToolText(arguments, "content");
                    requireNotAborted(signal);
                    Path parent = file.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    requireNotAborted(signal);
                    Files.writeString(
                            file, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    requireNotAborted(signal);
                    yield toolResultText(
                            "Successfully wrote " + content.getBytes(StandardCharsets.UTF_8).length + " bytes to " + file);
                }
                case EDIT -> {
                    String pathText = requiredToolText(arguments, "path");
                    rejectArchivePath(local, pathText);
                    Path file = localToolPath(local, pathText);
                    JsonNode editsNode = arguments.get("edits");
                    if (!(editsNode instanceof ArrayNode edits) || edits.isEmpty()) {
                        throw new IllegalArgumentException("edits must be a non-empty array");
                    }
                    requireNotAborted(signal);
                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    List<BuiltInTools.Replacement> replacements = new ArrayList<>();
                    for (JsonNode edit : edits) {
                        if (!edit.isObject()) {
                            throw new IllegalArgumentException("each edit must be an object");
                        }
                        String oldText = requiredToolText((ObjectNode) edit, "oldText");
                        String newText = requiredToolText((ObjectNode) edit, "newText");
                        int first = content.indexOf(oldText);
                        if (first < 0) {
                            throw new IllegalArgumentException("oldText was not found in " + file);
                        }
                        if (content.indexOf(oldText, first + 1) >= 0) {
                            throw new IllegalArgumentException("oldText must match exactly one location in " + file);
                        }
                        replacements.add(new BuiltInTools.Replacement(first, first + oldText.length(), newText));
                    }
                    replacements.sort(Comparator.comparingInt(replacement -> replacement.start));
                    for (int index = 1; index < replacements.size(); index++) {
                        if (replacements.get(index).start < replacements.get(index - 1).end) {
                            throw new IllegalArgumentException("edits must not overlap");
                        }
                    }
                    StringBuilder changed = new StringBuilder(content);
                    for (int index = replacements.size() - 1; index >= 0; index--) {
                        BuiltInTools.Replacement replacement = replacements.get(index);
                        changed.replace(replacement.start, replacement.end, replacement.newText);
                    }
                    requireNotAborted(signal);
                    Files.writeString(file, changed.toString(), StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
                    yield toolResultText("Successfully replaced " + replacements.size() + " block(s) in " + file);
                }
                case SHELL -> {
                    String command = requiredToolText(arguments, "command");
                    double timeoutSeconds;
                    JsonNode value = arguments.get("timeout");
                    if (value == null || value.isNull()) {
                        timeoutSeconds = 0;
                    } else {
                        if (!value.isNumber() || value.asDouble() <= 0 || !Double.isFinite(value.asDouble())) {
                            throw new IllegalArgumentException("timeout" + " must be a positive finite number");
                        }
                        timeoutSeconds = value.asDouble();
                    }
                    Process process = new ProcessBuilder(switch (local.shell) {
                        case BASH -> List.of("/bin/bash", "-lc", command);
                        case POWERSHELL ->
                                List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command);
                    })
                            .directory(local.cwd.toFile())
                            .redirectErrorStream(true)
                            .start();
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    Thread reader = Thread.ofVirtual().start(() -> {
                        try (var input = process.getInputStream()) {
                            input.transferTo(bytes);
                        } catch (IOException ignored) {
                            // The process exit status is reported below.
                        }
                    });
                    long deadline = timeoutSeconds == 0
                            ? Long.MAX_VALUE
                            : System.nanoTime() + Duration.ofMillis((long) (timeoutSeconds * 1_000)).toNanos();
                    while (process.isAlive()) {
                        if (isAborted(signal)) {
                            process.destroyForcibly();
                            throw new IllegalStateException("Command aborted");
                        }
                        if (System.nanoTime() >= deadline) {
                            process.destroyForcibly();
                            throw new IllegalStateException("Command timed out after " + timeoutSeconds + " seconds");
                        }
                        process.waitFor(50, TimeUnit.MILLISECONDS);
                    }
                    reader.join();
                    String output = boundToolOutput(bytes.toString(StandardCharsets.UTF_8), null);
                    if (process.exitValue() != 0) {
                        throw new IllegalStateException(
                                (output.isBlank() ? "" : output + "\n\n") + "Command exited with code " + process.exitValue());
                    }
                    yield toolResultText(output.isBlank() ? "(no output)" : output);
                }
                case GREP -> {
                    String patternText = requiredToolText(arguments, "pattern");
                    boolean literal = optionalToolBoolean(arguments, "literal");
                    int flags = optionalToolBoolean(arguments, "ignoreCase") ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
                    Pattern pattern = Pattern.compile(literal ? Pattern.quote(patternText) : patternText, flags);
                    String pathText = optionalToolText(arguments, "path", ".");
                    Path root1;
                    try {
                        root1 = localToolPath(local, pathText);
                    } catch (InvalidPathException error1) {
                        if (containsGlobMetacharacter(pathText)) {
                            throw wildcardPathError(pathText);
                        }
                        throw error1;
                    }
                    if (!Files.exists(root1) && containsGlobMetacharacter(pathText)) {
                        throw wildcardPathError(pathText);
                    }
                    Path root = root1;
                    int limit = positiveToolIntOrDefault(arguments, "limit", BuiltInTools.DEFAULT_GREP_LIMIT);
                    String glob = optionalToolText(arguments, "glob", null);
                    List<PathMatcher> fileMatchers;
                    if (glob == null) {
                        fileMatchers = List.of();
                    } else {
                        if (glob.isBlank()) {
                            throw new IllegalArgumentException("glob must be a non-empty string");
                        }
                        try {
                            LinkedHashSet<String> variants = new LinkedHashSet<>();
                            List<String> pending = new ArrayList<>();
                            variants.add(glob);
                            pending.add(glob);
                            for (int pendingIndex = 0; pendingIndex < pending.size(); pendingIndex++) {
                                String variant1 = pending.get(pendingIndex);
                                for (int index = variant1.indexOf("**/"); index >= 0; index = variant1.indexOf("**/", index + 3)) {
                                    String withoutDirectoryWildcard = variant1.substring(0, index) + variant1.substring(index + 3);
                                    if (variants.add(withoutDirectoryWildcard)) {
                                        pending.add(withoutDirectoryWildcard);
                                    }
                                }
                            }
                            fileMatchers = List.copyOf(variants).stream()
                                    .map(variant -> FileSystems.getDefault().getPathMatcher("glob:" + variant))
                                    .toList();
                        } catch (java.util.regex.PatternSyntaxException error) {
                            throw new IllegalArgumentException("Invalid glob '" + glob + "': " + error.getDescription(), error);
                        }
                    }
                    List<Path> files = local.gitIgnore.filesUnder(root, optionalToolBoolean(arguments, "includeIgnored"), signal);
                    boolean rootIsDirectory = Files.isDirectory(root);
                    StringBuilder output = new StringBuilder();
                    int filesConsidered = files.size();
                    int filesSearched = 0;
                    int matches = 0;
                    search:
                    for (Path file : files) {
                        requireNotAborted(signal);
                        Path relative = rootIsDirectory ? root.relativize(file) : file.getFileName();
                        if (!fileMatchers.isEmpty()) {
                            Path fileName = relative.getFileName();
                            boolean selected = fileMatchers.stream().anyMatch(matcher -> matcher.matches(relative)
                                    || fileName != null && !fileName.equals(relative) && matcher.matches(fileName));
                            if (!selected) continue;
                        }
                        filesSearched++;
                        List<String> lines;
                        try {
                            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                        } catch (IOException ignored) {
                            continue;
                        }
                        for (int line = 0; line < lines.size(); line++) {
                            if (!pattern.matcher(lines.get(line)).find()) {
                                continue;
                            }
                            matches++;
                            String line1 = lines.get(line);
                            output.append(Files.isDirectory(root)
                                            ? root.relativize(file).toString().replace('\\', '/')
                                            : file.getFileName().toString())
                                    .append(':')
                                    .append(line + 1)
                                    .append(": ")
                                    .append(line1.length() <= 500 ? line1 : line1.substring(0, 500) + "... [truncated]")
                                    .append('\n');
                            if (matches >= limit) {
                                break search;
                            }
                        }
                    }
                    if (matches > 0) {
                        yield toolResultText(boundToolOutput(
                                output.toString(), matches >= limit ? "[" + limit + " matches limit reached]" : null));
                    } else if (glob != null && filesSearched == 0 && filesConsidered > 0) {
                        yield toolResultText("No files matched glob '" + glob + "' (" + filesConsidered + " files under "
                                + root + " were considered). The glob is matched against paths relative to path; check the directory prefix.");
                    } else if (glob != null) {
                        yield toolResultText("No matches found in " + filesSearched + " files matching glob '" + glob + "'");
                    } else {
                        yield toolResultText("No matches found in " + filesSearched + " files");
                    }
                }
                case FIND -> {
                    AgentTool.ToolResult result;
                    String pattern = requiredToolText(arguments, "pattern");
                    Path root = localToolPath(local, optionalToolText(arguments, "path", "."));
                    if (!Files.isDirectory(root)) {
                        throw new IllegalArgumentException("Not a directory: " + root);
                    }
                    int limit = positiveToolIntOrDefault(arguments, "limit", BuiltInTools.DEFAULT_FIND_LIMIT);
                    var matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
                    List<Path> candidates =
                            local.gitIgnore.filesUnder(root, optionalToolBoolean(arguments, "includeIgnored"), signal);
                    List<String> matches = new ArrayList<>();
                    for (Path candidate : candidates) {
                        requireNotAborted(signal);
                        Path relative = root.relativize(candidate);
                        if (matcher.matches(relative) || matcher.matches(relative.getFileName())) {
                            matches.add(relative.toString().replace('\\', '/'));
                        }
                    }
                    matches.sort(String::compareToIgnoreCase);
                    if (matches.isEmpty()) {
                        result = toolResultText("No files found matching pattern");
                    } else {
                        boolean limitReached = matches.size() > limit;
                        if (limitReached) matches = new ArrayList<>(matches.subList(0, limit));
                        String suffix = limitReached ? "\n\n[" + limit + " results limit reached]" : "";
                        result = toolResultText(boundToolOutput(String.join("\n", matches) + suffix, null));
                    }
                    yield result;
                }
                case LS -> {
                    AgentTool.ToolResult result;
                    Path directory = localToolPath(local, optionalToolText(arguments, "path", "."));
                    if (!Files.isDirectory(directory)) {
                        throw new IllegalArgumentException("Not a directory: " + directory);
                    }
                    int limit = positiveToolIntOrDefault(arguments, "limit", BuiltInTools.DEFAULT_LS_LIMIT);
                    List<String> entries;
                    try (var paths = Files.list(directory)) {
                        entries = paths.map(entry -> entry.getFileName() + (Files.isDirectory(entry) ? "/" : ""))
                                .sorted(String.CASE_INSENSITIVE_ORDER)
                                .limit(limit)
                                .toList();
                    }
                    if (entries.isEmpty()) {
                        result = toolResultText("(empty directory)");
                    } else {
                        result = toolResultText(boundToolOutput(String.join("\n", entries), null));
                    }
                    yield result;
                }
            };
            case McpAgentTool mcp -> {
                ObjectNode params = jsonObject().put("name", mcp.definition.name);
                params.set("arguments", arguments == null ? jsonObject() : arguments);
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

    /**
     * Returns tools that filter search results with the given git-ignore configuration.
     */
    public List<AgentTool> builtInTools(Path cwd, Consumer<Path> onPathAccess) {
        Path resolvedCwd = cwd.toAbsolutePath().normalize();
        Consumer<Path> observer = Objects.requireNonNull(onPathAccess, "onPathAccess");
        BuiltInTools.Shell shell = isWindowsHost() ? BuiltInTools.Shell.POWERSHELL : BuiltInTools.Shell.BASH;
        return List.of(
                new LocalTool(
                        BuiltInTools.Kind.READ,
                        resolvedCwd,
                        "read",
                        "Read a text file. Use offset and limit for large files; output is bounded to 2,000 lines or 50KB. To read inside a jar/zip, append '!entry/path' to the archive path; 'archive.jar!' lists entries.",
                        toolSchema("path", toolString("Path to the file to read. A leading ~/ expands to the user home directory."), "offset", toolOptional(toolInteger("1-indexed starting line")), "limit", toolOptional(toolInteger("Maximum lines to read"))),
                        observer,
                        null,
                        null),
                new LocalTool(
                        BuiltInTools.Kind.WRITE,
                        resolvedCwd,
                        "write",
                        "Create or overwrite a text file, creating parent directories as needed.",
                        toolSchema("path", toolString("Path to write. A leading ~/ expands to the user home directory."), "content", toolString("File content")),
                        observer,
                        null,
                        null),
                new LocalTool(
                        BuiltInTools.Kind.EDIT,
                        resolvedCwd,
                        "edit",
                        "Replace one or more unique, non-overlapping exact text blocks in a file.",
                        toolSchema("path", toolString("Path to edit. A leading ~/ expands to the user home directory."), "edits", jsonObject().put("type", "array").put("description", "Exact replacements with oldText and newText")),
                        observer,
                        null,
                        null),
                new LocalTool(
                        BuiltInTools.Kind.SHELL,
                        resolvedCwd,
                        "shell",
                        "Execute a " + shell.displayName + " command in the current working directory. Output is bounded to 2,000 lines or 50KB.",
                        toolSchema("command", toolString(shell.displayName + " command"), "timeout", toolOptional(jsonObject().put("type", "number").put("exclusiveMinimum", 0).put("description", "Optional timeout in seconds"))),
                        observer,
                        null,
                        shell),
                new LocalTool(
                        BuiltInTools.Kind.GREP,
                        resolvedCwd,
                        "grep",
                        "Search text files beneath a literal file or directory. Use glob, not path, to filter file names. Files ignored by git are skipped; set includeIgnored to search them. Returns paths and line numbers, respecting the result limit. For symbol definitions and call sites in indexed repositories, a code-lens context/query tool (when connected) is usually faster and resolves aliases.",
                        toolSchema(
                                "pattern", toolString("Regular expression to search for, or literal text when literal is true"),
                                "path", toolOptional(toolString("Literal file or directory to search (default: current directory); wildcards are not expanded. A leading ~/ expands to the user home directory.")),
                                "glob", toolOptional(toolString("Glob file filter relative to path, for example '*.java' or 'src/**/*.java'; patterns without a slash match file names at any depth")),
                                "ignoreCase", toolOptional(toolBoolean("Case insensitive")),
                                "literal", toolOptional(toolBoolean("Treat pattern literally")),
                                "includeIgnored", toolOptional(toolBoolean("Search files ignored by git")),
                                "context", toolOptional(toolInteger("Lines before and after matches")),
                                "limit", toolOptional(toolInteger("Maximum matches"))),
                        observer,
                        this,
                        null),
                new LocalTool(
                        BuiltInTools.Kind.FIND,
                        resolvedCwd,
                        "find",
                        "Find files by glob pattern. Hidden files are included; .git and node_modules are skipped. Files ignored by git are skipped; set includeIgnored to search them.",
                        toolSchema(
                                "pattern", toolString("Glob pattern"),
                                "path", toolOptional(toolString("Directory to search. A leading ~/ expands to the user home directory.")),
                                "includeIgnored", toolOptional(toolBoolean("Search files ignored by git")),
                                "limit", toolOptional(toolInteger("Maximum results"))),
                        observer,
                        this,
                        null),
                new LocalTool(
                        BuiltInTools.Kind.LS,
                        resolvedCwd,
                        "ls",
                        "List a directory's contents, with a slash suffix on directories.",
                        toolSchema("path", toolOptional(toolString("Directory to list. A leading ~/ expands to the user home directory.")), "limit", toolOptional(toolInteger("Maximum entries"))),
                        observer,
                        null,
                        null));
    }

    private AgentTool.ToolResult readToolFile(Path file, ObjectNode arguments, AbortSignal signal)
            throws IOException {
        requireNotAborted(signal);
        if (!Files.isRegularFile(file)) {
            throw new IOException("Not a readable file: " + file);
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        int offset = positiveToolIntOrDefault(arguments, "offset", 1);
        int start = offset - 1;
        if (start >= lines.size()) {
            throw new IllegalArgumentException(
                    "offset " + offset + " is beyond end of file (" + lines.size() + " lines)");
        }
        int limit = positiveToolIntOrDefault(arguments, "limit", Integer.MAX_VALUE);
        int end = Math.min(lines.size(), start + limit);
        String output = String.join("\n", lines.subList(start, end));
        output = boundToolOutput(output, "Use offset=" + (start + (output.isBlank() ? 0 : (int) output.lines().count()) + 1) + " to continue.");
        if (end < lines.size() && !output.contains("Use offset=")) {
            output += "\n\n[" + (lines.size() - end) + " more lines. Use offset=" + (end + 1) + " to continue.]";
        }
        return toolResultText(output);
    }

    /**
     * Resolves a tool path argument against the tool's working directory.
     */
    private static Path localToolPath(LocalTool tool, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("path must be a non-empty string");
        }
        String expanded = value;
        if (value.equals("~")) {
            expanded = System.getProperty("user.home");
        } else if (value.startsWith("~/") || (isWindowsHost() && value.startsWith("~\\"))) {
            expanded = Path.of(System.getProperty("user.home")).resolve(value.substring(2)).toString();
        } else if (value.startsWith("~")) {
            throw new IllegalArgumentException("~user paths are not supported; use an absolute path");
        }
        Path candidate = Path.of(expanded);
        Path resolved = (candidate.isAbsolute() ? candidate : tool.cwd.resolve(candidate)).normalize();
        tool.onPathAccess.accept(resolved);
        return resolved;
    }

    private static BuiltInTools.ArchiveLocation localToolArchiveLocation(LocalTool tool, String value) {
        for (int separator = value.indexOf('!'); separator >= 0; separator = value.indexOf('!', separator + 1)) {
            if (separator == 0) continue;
            Path archive;
            try {
                archive = localToolPath(tool, value.substring(0, separator));
            } catch (InvalidPathException ignored) {
                continue;
            }
            if (Files.isRegularFile(archive)) {
                return new BuiltInTools.ArchiveLocation(archive, value.substring(separator + 1));
            }
        }
        return null;
    }

    private void rejectArchivePath(LocalTool tool, String value) {
        if (localToolArchiveLocation(tool, value) != null) {
            throw new IllegalArgumentException("archives are read-only through this tool");
        }
    }

    private void requireNotAborted(AbortSignal signal) {
        if (isAborted(signal)) {
            throw new IllegalStateException("Operation aborted");
        }
    }

    private static IllegalArgumentException wildcardPathError(String path) {
        return new IllegalArgumentException(
                "path is literal and does not expand wildcards: " + path
                        + ". Put the search root in path and the file pattern in glob, for example path=\"src\" and glob=\"**/*.java\".");
    }

    private static boolean containsGlobMetacharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (switch (value.charAt(index)) {
                case '*', '?', '[', '{' -> true;
                default -> false;
            }) {
                return true;
            }
        }
        return false;
    }

    // Java's recursive-directory glob requires at least one directory; ripgrep-style globs allow zero.

    private List<Path> filesUnder(Path root, boolean includeIgnored, AbortSignal signal)
            throws IOException {
        List<Path> files;
        if (Files.isRegularFile(root)) {
            files = List.of(root);
        } else {
            if (!Files.isDirectory(root)) {
                throw new IllegalArgumentException("Path not found: " + root);
            }
            List<Path> files1 = new ArrayList<>();
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (isAborted(signal)) throw new IllegalStateException("Operation aborted");
                    if (!directory.equals(root) && isSkippedPath(root.relativize(directory))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (isAborted(signal)) throw new IllegalStateException("Operation aborted");
                    if (attributes.isRegularFile() && !isSkippedPath(root.relativize(file))) files1.add(file);
                    return FileVisitResult.CONTINUE;
                }
            });
            files = List.copyOf(files1);
        }
        if (includeIgnored) {
            return files;
        } else {
            if (files.isEmpty()) return files;
            Path workingDirectory = Files.isDirectory(root) ? root : root.getParent();
            Path result = null;
            for (Path directory = workingDirectory.toAbsolutePath().normalize(); directory != null; directory = directory.getParent()) {
                if (Files.exists(directory.resolve(".git"))) {
                    result = directory;
                    break;
                }
            }
            if (result == null) return files;

            List<String> relativeNames = new ArrayList<>(files.size());
            for (Path candidate : files) {
                relativeNames.add(workingDirectory.relativize(candidate).toString());
            }

            Process process;
            try {
                process = new ProcessBuilder(
                        executable, "-C", workingDirectory.toString(), "check-ignore", "--stdin", "-z")
                        .redirectErrorStream(true)
                        .start();
            } catch (IOException ignored) {
                return files;
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            AtomicReference<IOException> transferFailure = new AtomicReference<>();
            Thread writer = Thread.ofVirtual().start(() -> {
                try (var input = process.getOutputStream()) {
                    for (String relative : relativeNames) {
                        input.write(relative.getBytes(StandardCharsets.UTF_8));
                        input.write(0);
                    }
                } catch (IOException error) {
                    transferFailure.compareAndSet(null, error);
                }
            });
            Thread reader = Thread.ofVirtual().start(() -> {
                try (var bytes = process.getInputStream()) {
                    bytes.transferTo(output);
                } catch (IOException error) {
                    transferFailure.compareAndSet(null, error);
                }
            });

            long deadline = System.nanoTime() + GIT_IGNORE_TIMEOUT.toNanos();
            try {
                while (process.isAlive()) {
                    if (isAborted(signal)) {
                        process.destroyForcibly();
                        joinThreads(writer, reader);
                        throw new IllegalStateException("Operation aborted");
                    }
                    if (System.nanoTime() >= deadline) {
                        process.destroyForcibly();
                        joinThreads(writer, reader);
                        return files;
                    }
                    process.waitFor(50, TimeUnit.MILLISECONDS);
                }
                joinThreads(writer, reader);
            } catch (InterruptedException error) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                return files;
            }
            if (transferFailure.get() != null || (process.exitValue() != 0 && process.exitValue() != 1)) {
                return files;
            }

            byte[] bytes = output.toByteArray();
            Set<String> values = new HashSet<>();
            int start = 0;
            for (int index1 = 0; index1 < bytes.length; index1++) {
                if (bytes[index1] != 0) continue;
                values.add(new String(bytes, start, index1 - start, StandardCharsets.UTF_8));
                start = index1 + 1;
            }
            if (start < bytes.length) {
                values.add(new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8));
            }
            if (values.isEmpty()) return files;
            List<Path> filtered = new ArrayList<>(files.size());
            for (int index = 0; index < files.size(); index++) {
                if (!values.contains(relativeNames.get(index))) filtered.add(files.get(index));
            }
            return List.copyOf(filtered);
        }
    }

    private static boolean isSkippedPath(Path relative) {
        for (Path part : relative) {
            if (part.toString().equals(".git") || part.toString().equals("node_modules")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWindowsHost() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String requiredToolText(ObjectNode arguments, String name) {
        JsonNode value = arguments.get(name);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(name + " must be a string");
        }
        return value.asText();
    }

    private static String optionalToolText(ObjectNode arguments, String name, String defaultValue) {
        JsonNode value = arguments.get(name);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException(name + " must be a string");
        }
        return value.asText();
    }

    private static int positiveToolIntOrDefault(ObjectNode arguments, String name, int defaultValue) {
        JsonNode value = arguments.get(name);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (!value.canConvertToInt() || value.asInt() <= 0) {
            throw new IllegalArgumentException(name + " must be a positive integer");
        }
        return value.asInt();
    }

    private static boolean optionalToolBoolean(ObjectNode arguments, String name) {
        JsonNode value = arguments.get(name);
        if (value == null || value.isNull()) {
            return false;
        }
        if (!value.isBoolean()) {
            throw new IllegalArgumentException(name + " must be a boolean");
        }
        return value.asBoolean();
    }

    private static ObjectNode toolSchema(Object... fields) {
        ObjectNode schema = jsonObject();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        for (int index = 0; index < fields.length; index += 2) {
            String name = (String) fields[index];
            ObjectNode definition = (ObjectNode) fields[index + 1];
            boolean optional = definition.remove("x-java-optional") != null;
            properties.set(name, definition);
            if (!optional) {
                required.add(name);
            }
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    private static ObjectNode toolString(String description) {
        return jsonObject().put("type", "string").put("description", description);
    }

    private static ObjectNode toolInteger(String description) {
        return jsonObject().put("type", "integer").put("minimum", 1).put("description", description);
    }

    private static ObjectNode toolBoolean(String description) {
        return jsonObject().put("type", "boolean").put("description", description);
    }

    private static ObjectNode toolOptional(ObjectNode definition) {
        return definition.put("x-java-optional", true);
    }

    /**
     * Bounds tool output to 2,000 lines or 50KB, appending a truncation notice.
     */
    private static String boundToolOutput(String input, String notice) {
        String[] lines = input.split("\\R", -1);
        StringBuilder output = new StringBuilder();
        int count = 0;
        for (String line : lines) {
            if (count >= BuiltInTools.MAX_LINES) {
                return appendToolNotice(output, notice == null ? "[Output truncated at 2,000 lines]" : notice);
            }
            byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
            int separator = output.isEmpty() ? 0 : 1;
            if (!output.isEmpty()
                    && output.toString().getBytes(StandardCharsets.UTF_8).length + separator + bytes.length
                    > BuiltInTools.MAX_BYTES) {
                return appendToolNotice(output, notice == null ? "[Output truncated at 50KB]" : notice);
            }
            if (!output.isEmpty()) {
                output.append('\n');
            }
            output.append(line);
            count++;
        }
        return output.toString();
    }

    private static String appendToolNotice(StringBuilder output, String notice) {
        if (!output.isEmpty()) {
            output.append("\n\n");
        }
        return output.append(notice).toString();
    }

    // ----------------------------------------------------------- git ignore

    private void joinThreads(Thread... threads) throws InterruptedException {
        for (Thread thread : threads) thread.join();
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
        HttpRequest request = mcpHttpRequestBuilder(
                transport.url,
                Duration.ofSeconds(30),
                bearer,
                transport.headers,
                transport.sessionId,
                transport.protocolVersion)
                .setHeader("Accept", "text/event-stream")
                .GET()
                .build();
        transport.opening = transport.client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        transport.opening.whenComplete((response, error) -> {
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

    /**
     * Builds a manager for the MCP servers in {@code ~/.codingagent/settings.json}.
     */
    private void mcpLoadDefaultManager(Path workspace) throws IOException {
        Path settingsPath = Path.of(System.getProperty("user.home"), ".codingagent", "settings.json");
        mcpCreateManager(
                mcpLoadConfiguration(new McpConfigLoader(
                        settingsPath.toAbsolutePath().normalize(), Map.copyOf(System.getenv()))),
                workspace);
    }

    /**
     * Builds a manager and starts connecting every enabled server.
     */
    private void mcpOAuthStore(
            Path path, Path lockPath, List<Path> importPaths) {
        this.path = path;
        this.lockPath = lockPath;
        this.importPaths = importPaths;
    }

    private void mcpOAuthClient(HttpClient http, Predicate<URI> browser, Duration callbackTimeout) {
        this.http = http;
        this.browser = browser;
        this.callbackTimeout = callbackTimeout;
    }

    public void mcpCreateManager(McpConfiguration configuration, Path workspace) {
        if (!closed && !servers.isEmpty()) {
            mcpCloseManager();
        }
        servers = new LinkedHashMap<>();
        closed = false;
        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        Path home = Path.of(System.getProperty("user.home"));
        String xdg = System.getenv("XDG_DATA_HOME");
        Path openCodeData = xdg == null || xdg.isBlank()
                ? home.resolve(".local/share/opencode/mcp-auth.json")
                : Path.of(xdg).resolve("opencode/mcp-auth.json");
        Path resolved = home.resolve(".codingagent/mcp-auth.json").toAbsolutePath().normalize();
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
        if (name == null || name.isBlank()) throw new IllegalArgumentException("MCP server name must not be blank");
        Files.createDirectories(path.getParent());
        mcpSetPermissions(path.getParent(), DIRECTORY_PERMISSIONS);
        try (FileChannel channel =
                     FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
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


    // ------------------------------------------------------------ tui text

    private static final Pattern TERMINAL_ANSI = Pattern.compile(
            "\u001b(?:\\[[0-?]*[ -/]*[@-~]|\\][^\u0007\u001b]*(?:\u0007|\u001b\\\\))");

    /**
     * Terminal cell width of a string, ignoring ANSI escapes.
     */
    public static int visibleWidth(String value) {
        String plain = stripAnsi(value);
        int width = 0;
        for (int index = 0; index < plain.length(); ) {
            int codePoint = plain.codePointAt(index);
            width += Math.max(0, WCWidth.wcwidth(codePoint));
            index += Character.charCount(codePoint);
        }
        return width;
    }

    /**
     * Truncates plain text to a cell width, adding an ellipsis when there is room.
     */
    private static String truncatePlain(String value, int maximumWidth) {
        if (maximumWidth <= 0) {
            return "";
        }
        if (visibleWidth(value) <= maximumWidth) {
            return value;
        }
        String ellipsis = maximumWidth > 3 ? "..." : "";
        int targetWidth = maximumWidth - ellipsis.length();
        StringBuilder output = new StringBuilder();
        int width = 0;
        for (int index = 0; index < value.length(); ) {
            int codePoint = value.codePointAt(index);
            int codePointWidth = Math.max(0, WCWidth.wcwidth(codePoint));
            if (width + codePointWidth > targetWidth) {
                break;
            }
            output.appendCodePoint(codePoint);
            width += codePointWidth;
            index += Character.charCount(codePoint);
        }
        return output + ellipsis;
    }

    public static String stripAnsi(String value) {
        return TERMINAL_ANSI.matcher(value).replaceAll("");
    }


    // ----------------------------------------------------------------- theme

    private static Theme namedTheme(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "dark" -> Theme.DARK;
            case "light" -> Theme.LIGHT;
            case "plain" -> Theme.PLAIN;
            default -> throw new IllegalArgumentException(
                    "Unknown theme: " + name + " (expected dark, light, or plain)");
        };
    }

    /**
     * Bold green is reserved for the Ready activity so idle is recognizable at a glance.
     */
    public static String readyStatus(Theme theme) {
        return theme==Theme.PLAIN ? "" : "\u001b[1;92m";
    }

    /**
     * Active model and shell work; deliberately never green.
     */
    public static String activeStatus(Theme theme) {
        return switch (theme) {
            case DARK -> "\u001b[1;96m";
            case LIGHT -> "\u001b[1;34m";
            default -> "";
        };
    }

    /**
     * Retry, cancellation, and configuration attention; deliberately never green.
     */
    private static String warningStatus(Theme theme) {
        return theme==Theme.PLAIN ? "" : "\u001b[1;93m";
    }

    /**
     * Background used to visually separate the editable prompt from chat output.
     */
    private static String promptBackground(Theme theme) {
        return switch (theme) {
            case DARK -> Theme.DARK_PROMPT_BACKGROUND;
            case LIGHT -> Theme.LIGHT_PROMPT_BACKGROUND;
            default -> "";
        };
    }

    /**
     * Styles every line as a full-width prompt area.
     */
    private static String promptArea(Theme theme, String value) {
        String background = promptBackground(theme);
        if (background.isEmpty()) return value;
        StringBuilder styled = new StringBuilder(value.length() + 32);
        styled.append(background).append(Theme.CLEAR_TO_END_OF_LINE);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\n') {
                styled.append(theme.reset).append(character);
                if (index + 1 < value.length()) {
                    styled.append(background).append(Theme.CLEAR_TO_END_OF_LINE);
                }
            } else {
                styled.append(character);
            }
        }
        if (value.isEmpty() || value.charAt(value.length() - 1) != '\n') styled.append(theme.reset);
        return styled.toString();
    }

    // -------------------------------------------------------- fuzzy matching

    /**
     * Keeps items matching every whitespace/slash-separated token, best score first.
     */
    private static <T> List<T> fuzzyFilter(List<T> items, String query, Function<T, String> text) {
        String trimmed = query.trim();
        if (trimmed.isEmpty()) {
            return List.copyOf(items);
        }
        String[] tokens = trimmed.split("[\\s/]+");
        List<FuzzyMatcher.Scored<T>> scored = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            T item = items.get(index);
            double total = 0;
            boolean matches = true;
            for (String token : tokens) {
                FuzzyMatcher.Match match;
                String normalizedQuery = token.toLowerCase(Locale.ROOT);
                String normalizedText = text.apply(item).toLowerCase(Locale.ROOT);
                FuzzyMatcher.Match primary = fuzzyMatchNormalized(normalizedQuery, normalizedText);
                if (primary.matches) {
                    match = primary;
                } else {
                    Matcher alphaNumeric = FuzzyMatcher.ALPHA_NUMERIC.matcher(normalizedQuery);
                    Matcher numericAlpha = FuzzyMatcher.NUMERIC_ALPHA.matcher(normalizedQuery);
                    String swapped = alphaNumeric.matches()
                            ? alphaNumeric.group(2) + alphaNumeric.group(1)
                            : numericAlpha.matches() ? numericAlpha.group(2) + numericAlpha.group(1) : "";
                    if (swapped.isEmpty()) {
                        match = primary;
                    } else {
                        FuzzyMatcher.Match swappedMatch = fuzzyMatchNormalized(swapped, normalizedText);
                        match = swappedMatch.matches ? new FuzzyMatcher.Match(true, swappedMatch.score + 5) : primary;
                    }
                }

                if (!match.matches) {
                    matches = false;
                    break;
                }
                total += match.score;
            }
            if (matches) {
                scored.add(new FuzzyMatcher.Scored<>(item, total, index));
            }
        }
        scored.sort((left, right) -> {
            int byScore = Double.compare(left.score, right.score);
            return byScore == 0 ? Integer.compare(left.index, right.index) : byScore;
        });
        return scored.stream().map(entry -> entry.item).toList();
    }

    private static FuzzyMatcher.Match fuzzyMatchNormalized(String query, String text) {
        if (query.isEmpty()) {
            return new FuzzyMatcher.Match(true, 0);
        }
        if (query.length() > text.length()) {
            return new FuzzyMatcher.Match(false, 0);
        }
        int queryIndex = 0;
        int lastMatchIndex = -1;
        int consecutiveMatches = 0;
        double score = 0;
        for (int index = 0; index < text.length() && queryIndex < query.length(); index++) {
            if (text.charAt(index) != query.charAt(queryIndex)) {
                continue;
            }
            boolean wordBoundary;
            if (index == 0) {
                wordBoundary = true;
            } else {
                char value = text.charAt(index - 1);
                wordBoundary = Character.isWhitespace(value)
                        || value == '-'
                        || value == '_'
                        || value == '.'
                        || value == '/'
                        || value == ':';
            }
            if (lastMatchIndex == index - 1) {
                consecutiveMatches++;
                score -= consecutiveMatches * 5;
            } else {
                consecutiveMatches = 0;
                if (lastMatchIndex >= 0) {
                    score += (index - lastMatchIndex - 1) * 2;
                }
            }
            if (wordBoundary) {
                score -= 10;
            }
            score += index * 0.1;
            lastMatchIndex = index;
            queryIndex++;
        }
        if (queryIndex < query.length()) {
            return new FuzzyMatcher.Match(false, 0);
        }
        if (query.equals(text)) {
            score -= 100;
        }
        return new FuzzyMatcher.Match(true, score);
    }

    // -------------------------------------------------------- ansi rendering

    /**
     * Resets the baseline, for example after the terminal scrolls externally.
     */
    private void resetAnsiRenderer(AnsiRenderer renderer) {
        renderer.previousLines = List.of();
    }


    private void ansiMoveTo(StringBuilder output, int zeroBasedLine) {
        output.append("\u001b[").append(zeroBasedLine + 1).append(";1H");
    }

    // ----------------------------------------------------------- keybindings

    /**
     * Shift+Enter is commonly LF, CSI-u, or xterm modifyOtherKeys depending on
     * the terminal. Keep Ctrl+Enter variants as aliases for compatibility.
     */
    private static List<String> editorKeySequences(String action) {
        String binding = Keybindings.DEFAULT_EDITOR_KEYBINDINGS.get(action);
        if ("shift-enter".equals(binding)) {
            return List.of(
                    KeyMap.ctrl('J'),
                    "\u001b[13;2u",
                    "\u001b[27;2;13~",
                    "\u001b[13;5u",
                    "\u001b[27;5;13~");
        }
        if ("enter".equals(binding)) {
            return List.of(KeyMap.ctrl('M'));
        }
        throw new IllegalArgumentException("Unsupported editor keybinding: " + action + "=" + binding);
    }

    private static String appKeySequence(String action) {
        String binding = Keybindings.DEFAULT_APP_KEYBINDINGS.get(action);
        if (binding != null && binding.startsWith("ctrl-") && binding.length() == 6) {
            return KeyMap.ctrl(binding.charAt(5));
        }
        if (binding != null && binding.equals("escape")) {
            return "\u001b";
        }
        throw new IllegalArgumentException("Unsupported application keybinding: " + action + "=" + binding);
    }

    private static boolean appKeyMatches(int value, String action) {
        String sequence = appKeySequence(action);
        return sequence.length() == 1 && sequence.charAt(0) == value;
    }

    // ----------------------------------------------------------- input parsing

    private static final long ESCAPE_TIMEOUT_MS = 25;
    private static final Pattern SGR_MOUSE = Pattern.compile("<(\\d+);(\\d+);(\\d+)([Mm])");
    private static final String TUI_PASTE_END = "\u001b[201~";

    /**
     * Creates a key event without associated text.
     */
    public static TuiInput.Key key(TuiInput.KeyType type) {
        return new TuiInput.Key(type, "");
    }

    /**
     * Reads one normalized input event, or null when the read timed out.
     */
    private static TuiInput readTuiInput(NonBlockingReader reader, long timeoutMs) throws IOException {
        int value = reader.read(timeoutMs);
        if (value == NonBlockingReader.READ_EXPIRED) {
            return null;
        }
        if (value == NonBlockingReader.EOF) {
            return key(TuiInput.KeyType.CANCEL);
        }
        if (value == 0x1b) {
            int next = reader.read(ESCAPE_TIMEOUT_MS);
            if (next == NonBlockingReader.READ_EXPIRED || next == NonBlockingReader.EOF) {
                return key(TuiInput.KeyType.ESCAPE);
            }
            if (next == '[') {
                StringBuilder sequence = new StringBuilder();
                while (sequence.length() < 64) {
                    int value1 = reader.read(ESCAPE_TIMEOUT_MS);
                    if (value1 == NonBlockingReader.READ_EXPIRED || value1 == NonBlockingReader.EOF) {
                        break;
                    }
                    sequence.append((char) value1);
                    if (value1 >= 0x40 && value1 <= 0x7e) {
                        break;
                    }
                }
                if (sequence.toString().equals("200~")) {
                    String result;
                    StringBuilder content = new StringBuilder();
                    StringBuilder suffix = new StringBuilder();
                    while (true) {
                        int value1 = reader.read();
                        if (value1 == NonBlockingReader.EOF) {
                            content.append(suffix);
                            result = content.toString();
                            break;
                        }
                        suffix.append((char) value1);
                        while (!TUI_PASTE_END.startsWith(suffix.toString())) {
                            content.append(suffix.charAt(0));
                            suffix.deleteCharAt(0);
                        }
                        if (suffix.toString().equals(TUI_PASTE_END)) {
                            result = content.toString();
                            break;
                        }
                    }
                    return new TuiInput.Key(TuiInput.KeyType.PASTE, result);
                }
                return csiInput(sequence.toString());
            }
            if (next == 'O') {
                int value1 = reader.read(ESCAPE_TIMEOUT_MS);
                return value1 < 0
                        ? key(TuiInput.KeyType.ESCAPE)
                        : parseInputSequence("\u001bO" + (char) value1);
            }
            return keyInput(next);
        } else {
            return keyInput(value);
        }
    }

    /**
     * Normalizes an already-buffered terminal sequence.
     */
    public static TuiInput parseInputSequence(String sequence) {
        if (sequence == null || sequence.isEmpty()) {
            return key(TuiInput.KeyType.UNKNOWN);
        }
        if (sequence.length() == 1 && sequence.charAt(0) != 0x1b) {
            return keyInput(sequence.charAt(0));
        }
        if (sequence.equals("\u001b")) {
            return key(TuiInput.KeyType.ESCAPE);
        }
        if (sequence.startsWith("\u001b[")) {
            return csiInput(sequence.substring(2));
        }
        if (sequence.startsWith("\u001bO") && sequence.length() == 3) {
            return switch (sequence.charAt(2)) {
                case 'A' -> key(TuiInput.KeyType.UP);
                case 'B' -> key(TuiInput.KeyType.DOWN);
                case 'C' -> key(TuiInput.KeyType.RIGHT);
                case 'D' -> key(TuiInput.KeyType.LEFT);
                default -> key(TuiInput.KeyType.UNKNOWN);
            };
        }
        return key(TuiInput.KeyType.UNKNOWN);
    }

    private static TuiInput csiInput(String sequence) {
        return switch (sequence) {
            case "A" -> key(TuiInput.KeyType.UP);
            case "B" -> key(TuiInput.KeyType.DOWN);
            case "C" -> key(TuiInput.KeyType.RIGHT);
            case "D" -> key(TuiInput.KeyType.LEFT);
            case "H", "1~", "7~" -> key(TuiInput.KeyType.HOME);
            case "F", "4~", "8~" -> key(TuiInput.KeyType.END);
            case "3~" -> key(TuiInput.KeyType.DELETE);
            case "5~" -> key(TuiInput.KeyType.PAGE_UP);
            case "6~" -> key(TuiInput.KeyType.PAGE_DOWN);
            default -> {
                TuiInput result;
                Matcher matcher = SGR_MOUSE.matcher(sequence);
                if (!matcher.matches()) {
                    result = key(TuiInput.KeyType.UNKNOWN);
                } else {
                    int code = Integer.parseInt(matcher.group(1));
                    int x = Integer.parseInt(matcher.group(2));
                    int y = Integer.parseInt(matcher.group(3));
                    if ((code & 64) != 0) {
                        result = new TuiInput.Mouse(
                                (code & 1) == 0 ? TuiInput.MouseAction.SCROLL_UP : TuiInput.MouseAction.SCROLL_DOWN,
                                code & 3,
                                x,
                                y);
                    } else {
                        TuiInput.MouseAction action;
                        if (matcher.group(4).equals("m") || (code & 3) == 3) {
                            action = TuiInput.MouseAction.RELEASE;
                        } else if ((code & 32) != 0) {
                            action = TuiInput.MouseAction.DRAG;
                        } else {
                            action = TuiInput.MouseAction.PRESS;
                        }
                        result = new TuiInput.Mouse(action, code & 3, x, y);
                    }
                }
                yield result;
            }
        };
    }

    private static TuiInput keyInput(int value) {
        TuiInput.KeyType applicationType = null;
        if (appKeyMatches(value, "exit")) {
            applicationType = TuiInput.KeyType.EXIT;
        } else if (appKeyMatches(value, "interrupt")) {
            applicationType = TuiInput.KeyType.CANCEL;
        } else if (appKeyMatches(value, "suspend")) {
            applicationType = TuiInput.KeyType.SUSPEND;
        } else if (appKeyMatches(value, "expandTools")) {
            applicationType = TuiInput.KeyType.EXPAND_TOOLS;
        } else if (appKeyMatches(value, "toggleThinking")) {
            applicationType = TuiInput.KeyType.TOGGLE_THINKING;
        }
        if (applicationType != null) {
            return key(applicationType);
        }
        return switch (value) {
            case 3 -> key(TuiInput.KeyType.CANCEL);
            case 8, 127 -> key(TuiInput.KeyType.BACKSPACE);
            case 9 -> key(TuiInput.KeyType.TAB);
            case 10, 13 -> key(TuiInput.KeyType.ENTER);
            case 14 -> key(TuiInput.KeyType.DOWN);
            case 16 -> key(TuiInput.KeyType.UP);
            case 21 -> key(TuiInput.KeyType.CLEAR);
            default -> value >= 32
                    ? new TuiInput.Key(TuiInput.KeyType.CHARACTER, Character.toString(value))
                    : key(TuiInput.KeyType.UNKNOWN);
        };
    }

    // ------------------------------------------------------ command suggestions


    private void synchronizeCommandSuggestions(String buffer) {
        String value = buffer == null ? "" : buffer;
        if (dismissedBuffer != null) {
            if (dismissedBuffer.equals(value)) {
                matches = List.of();
                query = value;
                selectedIndex = 0;
                visibleStart = 0;
                return;
            }
            dismissedBuffer = null;
        }
        if (value.equals(query)) return;
        query = value;
        selectedIndex = 0;
        visibleStart = 0;
        boolean result = true;
        if (value.isEmpty() || value.charAt(0) != '/') {
            result = false;
        } else {
            for (int index = 1; index < value.length(); index++) {
                if (Character.isWhitespace(value.charAt(index))) {
                    result = false;
                    break;
                }
            }
        }
        if (!result) {
            matches = List.of();
            return;
        }
        matches =
                commands.stream().filter(command -> command.startsWith(value)).toList();
    }

    private static String styleMuted(String value, Theme theme) {
        return theme.muted.isEmpty() ? value : theme.muted + value + theme.reset;
    }

    // --------------------------------------------------------- tui components

    /**
     * Applies one normalized input event to a component.
     */
    private void handleComponentInput(TuiComponent<?> component, TuiInput input) {
        component.handle.accept(input);
    }


    // ---------------------------------------------------------- fuzzy selector

    /**
     * Creates selector state over at least one option, clamping the initial selection.
     */
    public static <T> FuzzySelector<T> fuzzySelector(
            String title, List<SelectItem<T>> items, int initialIndex, boolean searchable) {
        if (items.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
        List<SelectItem<T>> options = List.copyOf(items);
        int selectedIndex = initialIndex < 0 ? 0 : Math.min(initialIndex, options.size() - 1);
        return new FuzzySelector<>(
                title,
                options,
                options,
                initialIndex < 0 ? null : options.get(selectedIndex),
                selectedIndex,
                searchable);
    }

    /**
     * Binds a selector carrier to the full-screen host.
     */
    public static <T> TuiComponent<T> fuzzySelectorComponent(FuzzySelector<T> selector) {
        return new TuiComponent<>(
                frame -> renderFuzzySelector(selector, frame.width, frame.height, frame.theme),
                input -> handleFuzzySelectorInput(selector, input),
                () -> selector.complete,
                () -> selector.result);
    }

    public static <T> List<String> renderFuzzySelector(
            FuzzySelector<T> selector, int width, int height, Theme theme) {
        List<String> lines = new ArrayList<>();
        lines.add(theme.heading + truncatePlain(selector.title, width) + theme.reset);
        lines.add("");
        if (selector.searchable) {
            String beforeCursor = selector.query.substring(0, selector.queryCursor);
            String afterCursor = selector.query.substring(selector.queryCursor);
            String search = "Search: " + beforeCursor + "|" + afterCursor;
            lines.add(truncatePlain(search, width));
            lines.add("");
        }

        selector.optionStartRow = lines.size();
        int reservedLines = lines.size() + 3;
        selector.visibleCount = Math.clamp(height - reservedLines, 1, 10);

        selector.visibleStart = Math.max(
                0,
                Math.min(
                        selector.selectedIndex - (selector.visibleCount / 2),
                        Math.max(0, selector.filteredItems.size() - selector.visibleCount)));
        int visibleEnd =
                Math.min(selector.filteredItems.size(), selector.visibleStart + selector.visibleCount);

        if (selector.filteredItems.isEmpty()) {
            lines.add(theme.muted + "  No matching options" + theme.reset);
        } else {
            for (int index = selector.visibleStart; index < visibleEnd; index++) {
                SelectItem<T> item = selector.filteredItems.get(index);
                boolean selected = index == selector.selectedIndex;
                boolean current = item == selector.currentItem;
                String suffix = current ? " *" : "";
                String description = item.description.isBlank() ? "" : "  " + item.description;
                String row = (selected ? "> " : "  ") + item.label + suffix + description;
                row = truncatePlain(row, width);
                lines.add(selected ? theme.heading + row + theme.reset : row);
            }
            if (selector.visibleStart > 0 || visibleEnd < selector.filteredItems.size()) {
                lines.add(theme.muted
                        + "  "
                        + (selector.selectedIndex + 1)
                        + "/"
                        + selector.filteredItems.size()
                        + theme.reset);
            }
        }
        lines.add("");
        String currentHint = selector.currentItem == null ? "" : "  * current";
        String hint = (selector.searchable
                ? "Type to filter  Up/Down move  Enter select  Esc cancel"
                : "Up/Down move  Enter select  Esc cancel")
                + currentHint;
        lines.add(theme.muted + truncatePlain(hint, width) + theme.reset);
        return lines;
    }

    private static int editQuery(StringBuilder query, int cursor, TuiInput.Key key, Runnable changed) {
        switch (key.type) {
            case CHARACTER, PASTE -> {
                if (key.text == null || key.text.isEmpty()) return cursor;
                String normalized = key.text.replace('\r', ' ').replace('\n', ' ');
                query.insert(cursor, normalized);
                changed.run();
                return cursor + normalized.length();
            }
            case BACKSPACE -> {
                if (cursor == 0) return cursor;
                int start = query.offsetByCodePoints(cursor, -1);
                query.delete(start, cursor);
                changed.run();
                return start;
            }
            case DELETE -> {
                if (cursor == query.length()) return cursor;
                query.delete(cursor, query.offsetByCodePoints(cursor, 1));
                changed.run();
                return cursor;
            }
            case LEFT -> {
                return Math.max(0, cursor - 1);
            }
            case RIGHT -> {
                return Math.min(query.length(), cursor + 1);
            }
            case HOME -> {
                return 0;
            }
            case END -> {
                return query.length();
            }
            case CLEAR -> {
                query.setLength(0);
                changed.run();
                return 0;
            }
            default -> {
                return -1;
            }
        }
    }

    /**
     * Applies one normalized input event to a fuzzy selector.
     */
    public static <T> void handleFuzzySelectorInput(FuzzySelector<T> selector, TuiInput input) {
        switch (input) {
            case TuiInput.Key key -> {
                switch (key.type) {
                    case UP -> moveFuzzySelection(selector, -1);
                    case DOWN -> moveFuzzySelection(selector, 1);
                    case PAGE_UP -> moveFuzzySelection(selector, -Math.max(1, selector.visibleCount));
                    case PAGE_DOWN -> moveFuzzySelection(selector, Math.max(1, selector.visibleCount));
                    case ENTER -> {
                        if (!selector.filteredItems.isEmpty()) {
                            selector.result = selector.filteredItems.get(selector.selectedIndex).value;
                            selector.complete = true;
                        }
                    }
                    case ESCAPE, CANCEL -> selector.complete = true;
                    case HOME -> {
                        if (selector.searchable) {
                            selector.queryCursor = 0;
                        } else if (!selector.filteredItems.isEmpty()) {
                            selector.selectedIndex = 0;
                        }
                    }
                    case END -> {
                        if (selector.searchable) {
                            selector.queryCursor = selector.query.length();
                        } else if (!selector.filteredItems.isEmpty()) {
                            selector.selectedIndex = selector.filteredItems.size() - 1;
                        }
                    }
                    default -> {
                        if (selector.searchable || key.type == TuiInput.KeyType.CLEAR) {
                            int cursor = editQuery(selector.query, selector.queryCursor, key, () -> {
                                selector.filteredItems = fuzzyFilter(
                                        selector.items, selector.query.toString(), item -> item.searchText);
                                if (selector.query.isEmpty()) {
                                    int currentIndex = selector.filteredItems.indexOf(selector.currentItem);
                                    selector.selectedIndex = Math.max(currentIndex, 0);
                                } else {
                                    selector.selectedIndex = 0;
                                }
                            });
                            if (cursor >= 0) selector.queryCursor = cursor;
                        }
                    }
                }
            }
            case TuiInput.Mouse mouse -> {
                switch (mouse.action) {
                    case SCROLL_UP -> moveFuzzySelection(selector, -1);
                    case SCROLL_DOWN -> moveFuzzySelection(selector, 1);
                    case PRESS -> {
                        int row = mouse.y - 1;
                        int itemOffset = row - selector.optionStartRow;
                        if (mouse.button == 0 && itemOffset >= 0 && itemOffset < selector.visibleCount) {
                            int index = selector.visibleStart + itemOffset;
                            if (index < selector.filteredItems.size()) {
                                selector.selectedIndex = index;
                            }
                        }
                    }
                    default -> {
                        // Release and drag events are currently informational.
                    }
                }
            }
            case TuiInput.Resize ignored -> {
                // Rendering derives its viewport directly from the latest dimensions.
            }
        }
    }

    private static <T> void moveFuzzySelection(FuzzySelector<T> selector, int delta) {
        if (selector.filteredItems.isEmpty()) {
            return;
        }
        selector.selectedIndex =
                Math.floorMod(selector.selectedIndex + delta, selector.filteredItems.size());
    }

    /**
     * Runs a searchable selector on the terminal and returns the chosen value.
     */
    private <T> T select(String title, List<SelectItem<T>> options, int initialIndex, boolean searchable)
            throws IOException {
        if (options.isEmpty()) {
            throw new IllegalArgumentException("options must not be empty");
        }
        return runComponent(fuzzySelectorComponent(fuzzySelector(title, options, initialIndex, searchable)));
    }

    // ------------------------------------------------------------ tui runtime

    /**
     * Hosts a component on the alternate screen until it completes.
     */

    private static int tuiWidth(TuiRuntime runtime) {
        int columns = runtime.terminal.getColumns();
        return columns > 0 ? columns : TuiRuntime.DEFAULT_COLUMNS;
    }

    private static int tuiHeight(TuiRuntime runtime) {
        int rows = runtime.terminal.getRows();
        return rows > 0 ? rows : TuiRuntime.DEFAULT_ROWS;
    }

    private void startTuiRuntime(TuiRuntime runtime) {
        runtime.originalAttributes = runtime.terminal.enterRawMode();
        if (!runtime.terminal.puts(Capability.enter_ca_mode)) {
            runtime.terminal.writer().write("\u001b[?1049h");
        }
        runtime.terminal.puts(Capability.keypad_xmit);
        runtime.terminal.trackMouse(Terminal.MouseTracking.Button);
        if (!runtime.terminal.puts(Capability.cursor_invisible)) {
            runtime.terminal.writer().write("\u001b[?25l");
        }
        runtime.active = true;
        resetAnsiRenderer(runtime.renderer);
        clearTuiScreen(runtime);
        runtime.terminal.flush();
    }

    private void stopTuiRuntime(TuiRuntime runtime) {
        if (!runtime.active) {
            return;
        }
        runtime.terminal.trackMouse(Terminal.MouseTracking.Off);
        if (!runtime.terminal.puts(Capability.cursor_normal)) {
            runtime.terminal.writer().write("\u001b[?25h");
        }
        runtime.terminal.puts(Capability.keypad_local);
        if (!runtime.terminal.puts(Capability.exit_ca_mode)) {
            runtime.terminal.writer().write("\u001b[?1049l");
        }
        runtime.terminal.flush();
        if (runtime.originalAttributes != null) {
            runtime.terminal.setAttributes(runtime.originalAttributes);
        }
        runtime.active = false;
    }

    private void renderTuiFrame(
            TuiRuntime runtime, TuiComponent<?> component, int width, int height) {
        int safeWidth = Math.max(20, width);
        int safeHeight = Math.max(5, height);
        List<String> lines = component.render.apply(new TuiFrame(safeWidth, safeHeight, runtime.theme));
        if (lines.size() > safeHeight) {
            lines = lines.subList(0, safeHeight);
        }
        List<String> next = List.copyOf(lines);
        StringBuilder output = new StringBuilder();
        int common = Math.min(runtime.renderer.previousLines.size(), next.size());
        for (int index = 0; index < common; index++) {
            if (!runtime.renderer.previousLines.get(index).equals(next.get(index))) {
                ansiMoveTo(output, index);
                output.append("\u001b[2K").append(next.get(index));
            }
        }
        for (int index = common; index < next.size(); index++) {
            ansiMoveTo(output, index);
            output.append("\u001b[2K").append(next.get(index));
        }
        for (int index = next.size(); index < runtime.renderer.previousLines.size(); index++) {
            ansiMoveTo(output, index);
            output.append("\u001b[2K");
        }
        runtime.renderer.previousLines = next;
        runtime.terminal.writer().write(output.toString());
        runtime.terminal.flush();
    }

    private void clearTuiScreen(TuiRuntime runtime) {
        runtime.terminal.writer().write("\u001b[2J\u001b[H");
    }

    // --------------------------------------------------------------- suspend

    /**
     * Invokes a suspend hook, preserving its IOException contract.
     */
    private void callSuspendAction(Callable<Void> action) throws IOException {
        try {
            action.call();
        } catch (IOException | RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException(error);
        }
    }

    // --------------------------------------------------- interactive terminal

    /**
     * Wires the line editor, signal handlers, and keybindings onto an existing terminal.
     */
    public void newInteractiveTerminal(
            Terminal terminal, Callable<Void> suspendAction, boolean supportsSuspend) {
        screenDocument = new StringBuilder();
        statusBar = null;
        statusActivity = null;
        statusAccent = StatusAccent.NONE;
        statusLeft = null;
        statusRight = null;
        fullScreenResumeAttributes = null;
        managedSuspend = false;
        suspendedBuffer = null;
        suspendedCursor = -1;
        restoreCursor = -1;
        commandSuggestionsActive = false;
        dynamicPost = null;
        jlineTerminal = terminal;
        int columns = terminal.getColumns();
        int rows = terminal.getRows();
        if (columns <= 0 || rows <= 0) {
            terminal.setSize(Size.of(
                    columns > 0 ? columns : DEFAULT_COLUMNS,
                    rows > 0 ? rows : DEFAULT_ROWS));
        }
        this.suspendAction = suspendAction;
        this.supportsSuspend = supportsSuspend;
        shellAttributes = new Attributes(terminal.getAttributes());
        this.reader = new LineReaderImpl(jlineTerminal, this.jlineTerminal.getName(), null) {
            @Override
            public AttributedString getDisplayedBufferWithPrompts(List<AttributedString> secondaryPrompts) {
                if (CodingAgentOperations.this.dynamicPost == null || post != null) {
                    return super.getDisplayedBufferWithPrompts(secondaryPrompts);
                }
                AttributedString rendered = CodingAgentOperations.this.dynamicPost.get();
                if (rendered == null || rendered.isEmpty()) {
                    return super.getDisplayedBufferWithPrompts(secondaryPrompts);
                }
                Supplier<AttributedString> previousPost = post;
                post = () -> rendered;
                try {
                    return super.getDisplayedBufferWithPrompts(secondaryPrompts);
                } finally {
                    post = previousPost;
                }
            }

            @Override
            protected void doCleanup(boolean newline) {
                Supplier<AttributedString> previousDynamicPost = CodingAgentOperations.this.dynamicPost;
                CodingAgentOperations.this.dynamicPost = null;
                try {
                    super.doCleanup(newline);
                } finally {
                    CodingAgentOperations.this.dynamicPost = previousDynamicPost;
                }
            }
        };
        this.reader.setHistory(new DefaultHistory());
        LineReaderImpl reader = this.reader;
        String newlineWidgetName = "codingagent-insert-newline";
        reader.getWidgets().put(newlineWidgetName, () -> {
            reader.getBuffer().write('\n');
            return true;
        });
        Reference insertNewline = new Reference(newlineWidgetName);
        String[] newlineSequences = editorKeySequences("newline").toArray(String[]::new);

        String submitWidgetName = "codingagent-submit-or-insert-pasted-newline";
        reader.getWidgets().put(submitWidgetName, () -> {
            LineReaderImpl reader1 = this.reader;
            int next = reader1.peekCharacter(PASTE_LOOKAHEAD_MILLIS);
            // Preserve multiline paste detection even when the pasted first line
            // happens to look like a slash command. A queued CR is instead treated
            // as the user's second Enter after choosing a command.
            if (next >= 0 && next != '\r') {
                if (next == '\n') reader1.readCharacter();
                reader1.getBuffer().write('\n');
                return true;
            }
            boolean result = false;
            if (commandSuggestionsActive) {
                String command = null;
                String buffer = this.reader.getBuffer().toString();
                synchronizeCommandSuggestions(buffer);
                if (!matches.isEmpty()) {
                    String selected = matches.get(selectedIndex);
                    dismissedBuffer = selected;
                    command = selected;
                }
                if (command != null) {
                    this.reader.getBuffer().clear();
                    this.reader.getBuffer().write(command);
                    result = true;
                }
            }
            if (result) return true;
            if (next >= 0) {
                reader1.getBuffer().write('\n');
                return true;
            }
            reader1.callWidget(LineReader.ACCEPT_LINE);
            return true;
        });
        Reference submit = new Reference(submitWidgetName);
        String[] submitSequences = editorKeySequences("submit").toArray(String[]::new);

        String suggestionUpWidgetName = "codingagent-previous-command-suggestion";
        reader.getWidgets().put(
                suggestionUpWidgetName,
                () -> this.moveSuggestionOrFallback(-1, LineReader.UP_LINE_OR_SEARCH));
        Reference suggestionUp = new Reference(suggestionUpWidgetName);
        String suggestionDownWidgetName = "codingagent-next-command-suggestion";
        reader.getWidgets().put(
                suggestionDownWidgetName,
                () -> this.moveSuggestionOrFallback(1, LineReader.DOWN_LINE_OR_SEARCH));
        Reference suggestionDown = new Reference(suggestionDownWidgetName);
        String terminalUp = KeyMap.key(jlineTerminal, Capability.key_up);
        String terminalDown = KeyMap.key(jlineTerminal, Capability.key_down);

        reader.getWidgets().put(LineReader.BEGIN_PASTE, () -> {
            StringBuilder content = new StringBuilder();
            while (true) {
                int value = this.reader.readCharacter();
                if (value < 0) break;
                content.append((char) value);
                boolean result = true;
                if (content.length() < BRACKETED_PASTE_END.length()) {
                    result = false;
                } else {
                    int offset = content.length() - BRACKETED_PASTE_END.length();
                    for (int index = 0; index < BRACKETED_PASTE_END.length(); index++) {
                        if (content.charAt(offset + index) != BRACKETED_PASTE_END.charAt(index)) {
                            result = false;
                            break;
                        }
                    }
                }
                if (result) {
                    content.setLength(content.length() - BRACKETED_PASTE_END.length());
                    break;
                }
            }
            this.reader.getBuffer().write(content.toString().replace("\r\n", "\n").replace('\r', '\n'));
            return true;
        });
        for (var keyMap1 : reader.getKeyMaps().values()) {
            keyMap1.bind(insertNewline, newlineSequences);
            keyMap1.bind(submit, submitSequences);
            bindNavigationKey(keyMap1, suggestionUp, terminalUp, "\u001b[A", "\u001bOA");
            bindNavigationKey(keyMap1, suggestionDown, terminalDown, "\u001b[B", "\u001bOB");
        }
        previousContinueHandler = supportsSuspend
                ? terminal.handle(Terminal.Signal.CONT, signal -> {
            try {
                if (previousContinueHandler != null
                    && previousContinueHandler != Terminal.SignalHandler.SIG_DFL
                    && previousContinueHandler != Terminal.SignalHandler.SIG_IGN) {
                    previousContinueHandler.handle(signal);
                }
            } finally {
                if (!managedSuspend) repaintScreen();
            }
        })
                : null;
        previousResizeHandler =
                terminal.handle(Terminal.Signal.WINCH, signal -> {
                    if (previousResizeHandler != null
                            && previousResizeHandler != Terminal.SignalHandler.SIG_DFL
                            && previousResizeHandler != Terminal.SignalHandler.SIG_IGN) {
                        previousResizeHandler.handle(signal);
                    }
                    synchronized (this) {
                        if (statusBar != null) {
                            statusBar.resize();
                            renderStatusBar();
                        }
                    }
                });
        if (supportsSuspend) {
            this.reader.getWidgets().compute(LineReader.CALLBACK_INIT, (_, previousInit) -> () -> {
                boolean initialized = previousInit == null || previousInit.apply();
                if (restoreCursor >= 0) {
                    this.reader
                            .getBuffer()
                            .cursor(Math.min(restoreCursor, this.reader.getBuffer().length()));
                    restoreCursor = -1;
                }
                return initialized;
            });
            this.reader.getWidgets().put("suspend-process", () -> {
                suspendedBuffer = this.reader.getBuffer().toString();
                suspendedCursor = this.reader.getBuffer().cursor();
                throw SUSPEND_REQUESTED;
            });
            Reference suspend = new Reference("suspend-process");
            for (var keyMap : this.reader.getKeyMaps().values()) {
                keyMap.bind(suspend, appKeySequence("suspend"));
            }
        }
        theme = Theme.DARK;
    }

    private void bindNavigationKey(
            KeyMap<Binding> keyMap,
            Reference widget,
            String terminalSequence,
            String... fallbackSequences) {
        if (terminalSequence != null && !terminalSequence.isEmpty()) {
            keyMap.bind(widget, terminalSequence);
        }
        keyMap.bind(widget, fallbackSequences);
    }

    private boolean moveSuggestionOrFallback(int delta, String fallbackWidget) {
        if (commandSuggestionsActive) {
            boolean result = true;
            String buffer = reader.getBuffer().toString();
            synchronizeCommandSuggestions(buffer);
            if (matches.isEmpty()) {
                result = false;
            } else {
                selectedIndex = Math.floorMod(selectedIndex + delta, matches.size());
                if (selectedIndex < visibleStart) {
                    visibleStart = selectedIndex;
                } else if (selectedIndex >= visibleStart + VISIBLE_COMMANDS) {
                    visibleStart = selectedIndex - VISIBLE_COMMANDS + 1;
                }
                visibleStart = Math.clamp(matches.size() - VISIBLE_COMMANDS, 0, visibleStart);
            }
            if (result) {
                return true;
            }
        }
        reader.callWidget(fallbackWidget);
        return true;
    }

    /**
     * Returns null on EOF and an empty string after Ctrl-C.
     */
    public String readLine(String prompt) {
        return readLineInternal(prompt, null, null, false);
    }

    private void commandSuggestions(List<String> commands) {
        this.commands = commands;
        query = null;
        dismissedBuffer = null;
        matches = List.of();
        selectedIndex = 0;
        visibleStart = 0;
    }

    /**
     * Reads a line with an alphabetized slash-command panel below the prompt.
     */
    public String readLine(String prompt, List<String> slashCommands) {
        boolean suggestionsEnabled = slashCommands != null && !slashCommands.isEmpty();
        if (suggestionsEnabled) {
            Objects.requireNonNull(slashCommands, "commands");
            commandSuggestions(slashCommands.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(command -> command.startsWith("/") && command.length() > 1)
                    .distinct()
                    .sorted(Comparator.naturalOrder())
                    .toList());
        }
        return readLineInternal(prompt, null, null, suggestionsEnabled);
    }

    /**
     * Reads a line whose editable buffer starts with {@code initialValue}.
     */
    public String readLine(String prompt, String initialValue) {
        return readLineInternal(prompt, initialValue, null, false);
    }

    private String readLineInternal(String prompt, String initialBuffer, Character mask, boolean suggestionsEnabled) {
        while (true) {
            Theme promptTheme = theme;
            try {
                String result;
                String background = promptBackground(promptTheme);
                reader.setVariable(
                        LineReader.SECONDARY_PROMPT_PATTERN,
                        background.isEmpty()
                                ? SECONDARY_PROMPT
                                : hiddenForJLine(background + Theme.CLEAR_TO_END_OF_LINE)
                                  + SECONDARY_PROMPT);
                String editorPrompt;
                if (background.isEmpty()) {
                    editorPrompt = prompt;
                } else {
                    int activeLineOffset = activePromptLineOffset(prompt);
                    editorPrompt = prompt.substring(0, activeLineOffset)
                            + hiddenForJLine(background + Theme.CLEAR_TO_END_OF_LINE)
                            + prompt.substring(activeLineOffset);
                }
                commandSuggestionsActive = suggestionsEnabled;
                dynamicPost =
                        !suggestionsEnabled ? null : () -> {
                            int columns = jlineTerminal.getColumns() > 0
                                    ? jlineTerminal.getColumns()
                                    : DEFAULT_COLUMNS;
                            List<String> lines;
                            String buffer = reader.getBuffer().toString();
                            synchronizeCommandSuggestions(buffer);
                            if (matches.isEmpty()) {
                                lines = List.of();
                            } else {
                                int visibleEnd = Math.min(
                                        matches.size(), visibleStart + VISIBLE_COMMANDS);
                                List<String> visible = matches.subList(visibleStart, visibleEnd);
                                int longestCommand = visible.stream()
                                        .mapToInt(CodingAgentOperations::visibleWidth)
                                        .max()
                                        .orElse(1);
                                int innerWidth = Math.max(1, Math.clamp(columns - 2, 1, longestCommand + 2));
                                String border = "─".repeat(innerWidth);
                                List<String> lines1 = new ArrayList<>(visible.size() + 2);
                                lines1.add(styleMuted("╭" + border + "╮", promptTheme));
                                for (int index = visibleStart; index < visibleEnd; index++) {
                                    boolean selected = index == selectedIndex;
                                    String content = (selected ? "› " : "  ") + matches.get(index);
                                    content = truncatePlain(content, innerWidth);
                                    content += " ".repeat(Math.max(0, innerWidth - visibleWidth(content)));
                                    String styledContent = selected && !promptTheme.heading.isEmpty()
                                            ? promptTheme.heading + content + promptTheme.reset
                                            : content;
                                    lines1.add(styleMuted("│", promptTheme) + styledContent + styleMuted("│", promptTheme));
                                }
                                lines1.add(styleMuted("╰" + border + "╯", promptTheme));
                                lines = lines1;
                            }

                            return lines.isEmpty()
                                   ? new AttributedString("")
                                   : AttributedString.fromAnsi(String.join("\n", lines));
                        };
                try {
                    result = reader.readLine(editorPrompt, null, mask, initialBuffer);
                } finally {
                    dynamicPost = null;
                    commandSuggestionsActive = false;
                    resetPromptBackground(promptTheme);
                }
                String line =
                        result;
                rememberCompletedLine(prompt, line, mask, promptTheme);
                return line;
            } catch (CancellationException signal) {
                if (signal != SUSPEND_REQUESTED) {
                    throw signal;
                }
                initialBuffer = suspendedBuffer;
                restoreCursor = suspendedCursor;
                suspendInteractive(null);
            } catch (UserInterruptException ignored) {
                rememberCompletedLine(prompt, "", mask, promptTheme);
                return "";
            } catch (EndOfFileException ignored) {
                synchronized (this) {
                    int activeLineOffset = activePromptLineOffset(prompt);
                    remember(prompt.substring(0, activeLineOffset));
                    remember(promptArea(promptTheme, prompt.substring(activeLineOffset)));
                }
                return null;
            }
        }
    }

    private static String hiddenForJLine(String value) {
        return "%{" + value + "%}";
    }

    private static int activePromptLineOffset(String prompt) {
        return Math.max(prompt.lastIndexOf('\n'), prompt.lastIndexOf('\r')) + 1;
    }

    /**
     * Hosts a component on the alternate screen, restoring the line editor afterwards.
     */
    public <T> T runComponent(TuiComponent<T> component)
            throws IOException {
        if (reader.isReading()) resetPromptBackground(theme);
        synchronized (this) {
            if (statusBar != null) statusBar.suspend();
        }
        try {
            TuiRuntime runtime = new TuiRuntime(
                    jlineTerminal,
                    theme,
                    supportsSuspend
                            ? () -> {
                        fullScreenResumeAttributes =
                        new Attributes(jlineTerminal.getAttributes());
                        jlineTerminal.setAttributes(shellAttributes);
                        managedSuspend = true;
                        try {
                            callSuspendAction(suspendAction);
                        } catch (IOException | RuntimeException | Error error) {
                            restoreFullScreenAttributes();
                            managedSuspend = false;
                            throw error;
                        }
                        return null;
                    }
                            : null,
                    () -> {
                        synchronized (this) {
                            try {
                                repaintScreen();
                            } finally {
                                restoreFullScreenAttributes();
                                managedSuspend = false;
                            }
                        }
                    });
            int width = tuiWidth(runtime);
            int height = tuiHeight(runtime);
            try {
                startTuiRuntime(runtime);
                handleComponentInput(component, new TuiInput.Resize(width, height));
                renderTuiFrame(runtime, component, width, height);
                while (!component.complete.getAsBoolean()) {
                    TuiInput input = readTuiInput(runtime.terminal.reader(), 100);
                    int nextWidth = tuiWidth(runtime);
                    int nextHeight = tuiHeight(runtime);
                    if (nextWidth != width || nextHeight != height) {
                        width = nextWidth;
                        height = nextHeight;
                        handleComponentInput(component, new TuiInput.Resize(width, height));
                        resetAnsiRenderer(runtime.renderer);
                        clearTuiScreen(runtime);
                        renderTuiFrame(runtime, component, width, height);
                    }
                    if (input == null) {
                        // Components such as the MCP selector can change from background connection threads.
                        renderTuiFrame(runtime, component, width, height);
                        continue;
                    }
                    if (runtime.suspendAction != null
                            && input instanceof TuiInput.Key key
                            && key.type == TuiInput.KeyType.SUSPEND) {
                        stopTuiRuntime(runtime);
                        callSuspendAction(runtime.suspendAction);
                        runtime.resumeMainScreen.run();
                        startTuiRuntime(runtime);
                        width = tuiWidth(runtime);
                        height = tuiHeight(runtime);
                        handleComponentInput(component, new TuiInput.Resize(width, height));
                        resetAnsiRenderer(runtime.renderer);
                        renderTuiFrame(runtime, component, width, height);
                        continue;
                    }
                    handleComponentInput(component, input);
                    renderTuiFrame(runtime, component, width, height);
                }
                return component.result.get();
            } finally {
                stopTuiRuntime(runtime);
            }

        } finally {
            synchronized (this) {
                if (statusBar != null) {
                    statusBar.restore();
                    if (statusBar.size() > 0) {
                        // Alternate-screen switches can drop the scroll region on some terminals.
                        int rows = jlineTerminal.getRows() > 0
                                ? jlineTerminal.getRows()
                                : DEFAULT_ROWS;
                        jlineTerminal.puts(Capability.save_cursor);
                        jlineTerminal.puts(
                                Capability.change_scroll_region, 0, rows - 1 - statusBar.size());
                        jlineTerminal.puts(Capability.restore_cursor);
                        renderStatusBar();
                        jlineTerminal.flush();
                    }
                }
            }
            if (reader.isReading()) reader.callWidget(LineReader.REDRAW_LINE);
        }
    }

    /**
     * Runs an operation while listening for an interrupt key. The operation uses
     * a virtual thread so Escape can be read even while it is blocked on a model
     * response or tool. Ctrl-C remains an interrupt alias outside the line editor.
     */
    public <T> T runInterruptibly(Callable<T> operation, Runnable interruptHandler)
            throws IOException, InterruptedException {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(interruptHandler, "interruptHandler");
        Attributes originalAttributes = jlineTerminal.enterRawMode();
        FutureTask<T> task = new FutureTask<>(operation);
        Thread worker = Thread.ofVirtual().name("codingagent-interactive-operation").start(task);
        boolean interruptRequested = false;
        try {
            while (!task.isDone()) {
                TuiInput input = readTuiInput(jlineTerminal.reader(), 50);
                if (input instanceof TuiInput.Key key
                        && (key.type == TuiInput.KeyType.ESCAPE || key.type == TuiInput.KeyType.CANCEL)
                        && !interruptRequested
                        && !task.isDone()) {
                    interruptRequested = true;
                    interruptHandler.run();
                } else if (input instanceof TuiInput.Key key
                        && key.type == TuiInput.KeyType.SUSPEND
                        && supportsSuspend) {
                    suspendInteractive(originalAttributes);
                }
            }
            try {
                return task.get();
            } catch (ExecutionException error) {
                Throwable cause = error.getCause();
                if (cause instanceof InterruptedException interrupted) throw interrupted;
                if (cause instanceof IOException io) throw io;
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error fatal) throw fatal;
                throw new IllegalStateException(cause);
            }
        } catch (IOException | RuntimeException | Error error) {
            if (!task.isDone()) {
                interruptHandler.run();
                worker.interrupt();
            }
            throw error;
        } finally {
            jlineTerminal.setAttributes(originalAttributes);
        }
    }

    private void suspendInteractive(Attributes restoreBefore) {
        if (restoreBefore != null) jlineTerminal.setAttributes(restoreBefore);
        managedSuspend = true;
        try {
            callSuspendAction(suspendAction);
        } catch (IOException error) {
            println("Could not suspend process: " + error.getMessage());
        } finally {
            repaintScreen();
            managedSuspend = false;
            if (restoreBefore != null) jlineTerminal.enterRawMode();
        }
    }

    /**
     * Binds a configured application action while the line editor is active.
     */
    public void bindAppAction(String action, Runnable handler) {
        Objects.requireNonNull(handler, "handler");
        String widgetName = "codingagent-" + action;
        reader.getWidgets().put(widgetName, () -> {
            resetPromptBackground(theme);
            try {
                handler.run();
            } finally {
                reader.callWidget(LineReader.REDRAW_LINE);
            }
            return true;
        });
        Reference reference = new Reference(widgetName);
        for (var keyMap : reader.getKeyMaps().values()) {
            keyMap.bind(reference, appKeySequence(action));
        }
    }

    /**
     * Prints a status line without losing the active line-editor buffer.
     */
    public void printAbove(String text) {
        synchronized (this) {
            if (reader.isReading()) resetPromptBackground(theme);
            reader.printAbove(text);
            remember(text + System.lineSeparator());
        }
    }

    private void print(String text) {
        synchronized (this) {
            String value = String.valueOf(text);
            jlineTerminal.writer().print(value);
            jlineTerminal.writer().flush();
            remember(value);
        }
    }

    public void println(String text) {
        synchronized (this) {
            String value = String.valueOf(text);
            jlineTerminal.writer().println(value);
            jlineTerminal.writer().flush();
            remember(value + System.lineSeparator());
        }
    }

    /**
     * Replaces the main-screen document and redraws it from the top.
     */
    public void replaceScreen(String document) {
        synchronized (this) {
            screenDocument.setLength(0);
            screenDocument.append(document == null ? "" : document);
            repaintScreen();
        }
    }

    private Theme terminalTheme() {
        return theme;
    }

    private void setTheme(Theme theme) {
        this.theme = theme;
    }

    /**
     * Shows activity first so it remains visible when workspace/model details need truncation.
     */
    public void setStatus(String activity, StatusAccent accent, String left, String right) {
        synchronized (this) {
            statusActivity = activity == null ? "" : activity;
            statusAccent =
                    accent == null ? StatusAccent.NONE : accent;
            statusLeft = left == null ? "" : left;
            statusRight = right == null ? "" : right;
            renderStatusBar();
        }
    }

    private void renderStatusBar() {
        if (statusLeft == null && statusRight == null) return;
        if (statusBar == null) {
            statusBar = Status.getStatus(jlineTerminal);
        }
        if (statusBar == null) return;
        int columns = jlineTerminal.getColumns();
        int width = columns > 0 ? columns : DEFAULT_COLUMNS;
        statusBar.update(List.of(AttributedString.fromAnsi(statusBarLine(
                statusActivity,
                statusAccent,
                statusLeft,
                statusRight,
                width,
                theme))));
    }

    /**
     * Keeps activity ahead of workspace/model metadata. When the terminal is
     * narrow, metadata is discarded before the activity text is truncated.
     */
    public static String statusBarLine(
            String activity,
            StatusAccent accent,
            String left,
            String right,
            int width,
            Theme theme) {
        int safeWidth = Math.max(0, width);
        String activityText = truncatePlain(activity == null ? "" : activity, safeWidth);
        int activityWidth = visibleWidth(activityText);
        String separatorAndDetails = "";
        int remaining = safeWidth - activityWidth;
        if (remaining >= 4
                && (!(left == null || left.isEmpty()) || !(right == null || right.isEmpty()))) {
            String details = alignedStatusDetails(left, right, remaining - 3);
            if (!details.isEmpty()) separatorAndDetails = " │ " + details;
        }

        if (activityText.isEmpty()) {
            String details = alignedStatusDetails(left, right, safeWidth);
            return mutedStatus(details, theme);
        }
        String activityStyle = switch (accent == null ? StatusAccent.NONE : accent) {
            case NONE -> theme.muted;
            case READY -> readyStatus(theme);
            case ACTIVE -> activeStatus(theme);
            case TOOL -> theme == Theme.PLAIN ? "" : "\u001b[1;95m";
            case WARNING -> warningStatus(theme);
        };
        String styledActivity =
                activityStyle.isEmpty() ? activityText : activityStyle + activityText + theme.reset;
        return styledActivity + mutedStatus(separatorAndDetails, theme);
    }

    private static String alignedStatusDetails(String left, String right, int width) {
        if (width <= 0) return "";
        String rightText = truncatePlain(right == null ? "" : right, width);
        int rightWidth = visibleWidth(rightText);
        int leftLimit = rightWidth == 0 ? width : width - rightWidth - 1;
        String leftText = truncatePlain(left == null ? "" : left, Math.max(0, leftLimit));
        int leftWidth = visibleWidth(leftText);
        int padding = Math.max(leftText.isEmpty() ? 0 : 1, width - leftWidth - rightWidth);
        return rightWidth == 0 ? leftText : leftText + " ".repeat(padding) + rightText;
    }

    private static String mutedStatus(String text, Theme theme) {
        return theme.muted.isEmpty() || text.isEmpty() ? text : theme.muted + text + theme.reset;
    }

    private void rememberCompletedLine(String prompt, String line, Character mask, Theme promptTheme) {
        synchronized (this) {
            String displayedLine = line;
            if (mask != null) {
                displayedLine =
                        mask == 0 ? "" : String.valueOf(mask).repeat(line.length());
            }
            int activeLineOffset = activePromptLineOffset(prompt);
            remember(prompt.substring(0, activeLineOffset));
            remember(promptArea(promptTheme, prompt.substring(activeLineOffset) + displayedLine));
            remember(System.lineSeparator());
        }
    }

    private void remember(String text) {
        synchronized (this) {
            screenDocument.append(text);
        }
    }

    private void restoreFullScreenAttributes() {
        if (fullScreenResumeAttributes != null) {
            jlineTerminal.setAttributes(fullScreenResumeAttributes);
            fullScreenResumeAttributes = null;
        }
    }

    private void resetPromptBackground(Theme promptTheme) {
        if (promptBackground(promptTheme).isEmpty()) return;
        jlineTerminal.writer().print(promptTheme.reset);
        jlineTerminal.writer().flush();
    }

    private void repaintScreen() {
        synchronized (this) {
            jlineTerminal.writer().print(theme.reset);
            jlineTerminal.writer().print(BEGIN_SYNCHRONIZED_OUTPUT);
            boolean redrawStatusBar = statusBar != null && statusBar.size() > 0;
            // Release the status rows so the redrawn document starts on a clean screen.
            if (redrawStatusBar) statusBar.update(List.of());
            jlineTerminal.writer().print(CLEAR_SCREEN_AND_SCROLLBACK);
            // Re-reserve the bottom row before printing so the document scrolls above it.
            if (redrawStatusBar) renderStatusBar();
            jlineTerminal.writer().print(screenDocument);
            jlineTerminal.writer().print(END_SYNCHRONIZED_OUTPUT);
            jlineTerminal.writer().flush();
        }
    }

    /**
     * Restores the signal handlers this terminal replaced and closes JLine.
     */
    public void closeTerminal() throws IOException {
        if (previousContinueHandler != null) {
            jlineTerminal.handle(Terminal.Signal.CONT, previousContinueHandler);
        }
        if (previousResizeHandler != null) {
            jlineTerminal.handle(Terminal.Signal.WINCH, previousResizeHandler);
        }
        jlineTerminal.close();
    }

    // ------------------------------------------------------------------- cli

    /**
     * Process entry point for the shaded jar and the native executable.
     */
    public static void main(String[] args) {
        System.exit(INSTANCE.cliRun(args));
    }

    /**
     * Parses the command line, runs the selected mode, and returns the exit code.
     */
    public int cliRun(String[] args) {
        try {
            List<String> messageParts = new ArrayList<>();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "-h", "--help" -> help = true;
                    case "-v", "--version" -> version = true;
                    case "--list-models" -> {
                        listModels = true;
                        if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                            modelSearch = args[++i];
                        }
                    }
                    case "--provider" -> this.provider = cliArgumentValue(args, ++i, arg);
                    case "--model" -> this.model = cliArgumentValue(args, ++i, arg);
                    case "--api-key" -> this.apiKey = cliArgumentValue(args, ++i, arg);
                    case "--system-prompt" -> systemPrompt = cliArgumentValue(args, ++i, arg);
                    case "--no-session" -> noSession = true;
                    case "--mode" -> mode = cliArgumentValue(args, ++i, arg);
                    case "-p", "--print" -> {
                        print = true;
                        if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                            messageParts.add(args[++i]);
                        }
                    }
                    default -> {
                        if (arg.startsWith("-")) {
                            throw new IllegalArgumentException("Unknown option: " + arg);
                        }
                        messageParts.add(arg);
                    }
                }
            }
            this.message = String.join(" ", messageParts);
            if (!mode.equals("print") && !mode.equals("json") && !mode.equals("rpc")) {
                throw new IllegalArgumentException("--mode must be print, json, or rpc");
            }
            if (version) {
                System.out.println(VERSION);
                return 0;
            }
            if (help) {
                System.out.println("""
                        %s - coding agent (Java port)
                        
                        Usage: %s [options] [@file...] [message...]
                        
                        Options:
                          -h, --help     Show help
                          -v, --version  Show version
                          --list-models [search]
                        				 List bundled core-provider models
                          --model <provider/model>
                        				 Select a model for interactive and --print modes
                          --provider <id> Provider when --model is an unqualified model id
                          --api-key <key> Override environment-based API-key lookup
                          --system-prompt <text>
                        				 Set the system prompt for --print
                          --no-session   Do not persist the print-mode transcript
                          --mode <print|json|rpc>
                        				 Select plain text, JSONL events, or stdin/stdout RPC
                          -p, --print <prompt>
                        				 Run a headless coding-agent prompt and print the final answer
                        
                        Interactive mode restores the model and settings selected previously.
                        Run /login to authenticate with GitHub Copilot, an OpenAI API key, or ChatGPT Plus/Pro.
                        """.formatted(APP_NAME, APP_NAME));
                return 0;
            }
            loadBundledModelCatalog();
            defaultCredentialStore();
            Map<String, Provider> providers1 = new LinkedHashMap<>();
            providers1.put(
                    "anthropic",
                    new AnthropicProvider(
                            "anthropic",
                            "Anthropic",
                            catalogModelsForProvider("anthropic").stream()
                                    .filter(model1 -> model1.api.equals("anthropic-messages"))
                                    .toList(),
                            List.of(
                                    EnvApiKeys.ANTHROPIC_AUTH_TOKEN_ENV,
                                    EnvApiKeys.ANTHROPIC_OAUTH_TOKEN_ENV,
                                    EnvApiKeys.ANTHROPIC_API_KEY_ENV),
                            false));
            providers1.put(
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
            chatGptAuth(this, URI.create("https://auth.openai.com"), CHATGPT_CLIENT_ID);
            List<Model> models = catalogModelsForProvider("openai").stream()
                    .filter(model2 -> model2.api.equals("openai-responses"))
                    .filter(model1 -> CHATGPT_CODEX_MODEL_IDS.contains(model1.id))
                    .map(source -> {
                        Model model1 = copyModel(source);
                        model1.provider = CHATGPT_PROVIDER_ID;
                        model1.baseUrl = CHATGPT_CODEX_API_BASE_URL.toString();
                        model1.cost = ModelCost.FREE;
                        return model1;
                    })
                    .toList();
            providers1.put(
                    CHATGPT_PROVIDER_ID,
                    chatGptProvider(models, new OpenAiResponsesProvider(
                                    CHATGPT_PROVIDER_ID,
                                    CHATGPT_PROVIDER_NAME,
                                    List.copyOf(models),
                                    List.of(),
                                    null,
                                    OpenAiResponsesProvider.RequestProfile.CODEX)));
            googleProvider(List.copyOf(catalogModelsForProvider("google").stream()
                    .filter(model -> model.api.equals(GOOGLE_API))
                    .toList()));
            providers1.put("google", this);
            gitHubCopilotAuth(
                    this,
                    URI.create("https://github.com"),
                    URI.create("https://api.github.com/copilot_internal/v2/token"),
                    URI.create(GITHUB_COPILOT_DEFAULT_BASE_URL));
            providers1.put(
                    "github-copilot",
                    newGitHubCopilotProvider(catalogModelsForProvider("github-copilot")));
            coreProviders(Map.copyOf(providers1));
            if (listModels) {
                String needle = modelSearch == null ? "" : modelSearch.toLowerCase();
                for (Provider provider : List.copyOf(coreProviders.values())) {
                    for (Model model : providerModels(provider)) {
                        String id = model.provider + "/" + model.id;
                        if (needle.isEmpty() || id.toLowerCase().contains(needle) || model.name.toLowerCase().contains(needle)) {
                            System.out.printf("%-45s %s%n", id, model.name);
                        }
                    }
                }
                return 0;
            }
            if (mode.equals("rpc")) {
                Model initialModel;
                if (this.model == null) {
                    throw new IllegalArgumentException("--mode rpc requires --model <provider/model>");
                }
                if (this.model.contains("/")) {
                    String[] parts = this.model.split("/", 2);
                    if (this.provider != null && !this.provider.equals(parts[0])) {
                        throw new IllegalArgumentException("--provider conflicts with the provider in --model");
                    }
                    initialModel = requireCatalogModel(parts[0], parts[1]);
                } else {
                    if (this.provider == null) {
                        throw new IllegalArgumentException("--mode rpc requires --model <provider/model>");
                    }
                    initialModel = requireCatalogModel(this.provider, this.model);
                }
                mcpLoadDefaultManager(Path.of(".").toAbsolutePath().normalize());
                try {
                    resetRpcAgent(initialModel);
                } catch (IOException | RuntimeException error) {
                    mcpCloseManager();
                    throw error;
                }
                try (BufferedReader input =
                             new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = input.readLine()) != null) {
                        JsonNode parsed1;
                        try {
                            parsed1 = Json.MAPPER.readTree(line);
                        } catch (IOException e) {
                            respondRpc(null, "parse", false, null, "Invalid JSON: " + e.getMessage());
                            continue;
                        }
                        if (!(parsed1 instanceof ObjectNode command) || !command.path("type").isTextual()) {
                            respondRpc(null, "parse", false, null, "Command must be a JSON object with a string type");
                            continue;
                        }
                        String id = command.path("id").isTextual() ? command.path("id").asText() : null;
                        String type = command.path("type").asText();
                        try {
                            switch (type) {
                                case "prompt" -> {
                                    JsonNode message = command.get("message");
                                    if (message == null || !message.isTextual() || message.asText().isBlank()) {
                                        throw new IllegalArgumentException("prompt requires a non-empty string message");
                                    }
                                    mcpAwaitReady();
                                    tools.removeIf(McpAgentTool.class::isInstance);
                                    tools.addAll(mcpTools());
                                    List<Message> messages = prompt(message.asText());
                                    if (!noSession) appendSessionMessages(messages);
                                    respondRpc(id, "prompt", true, null, null);
                                }
                                case "abort" -> {
                                    abort();
                                    respondRpc(id, type, true, null, null);
                                }
                                case "get_state" -> {
                                    ObjectNode data = jsonObject();
                                    data.set("model", Json.MAPPER.valueToTree(selectedModel));
                                    data.put("isStreaming", isStreaming);
                                    data.put("isCompacting", isCompacting);
                                    data.put("autoCompactionEnabled", autoCompactionEnabled);
                                    data.put("messageCount", messages.size());
                                    data.put("sessionId", noSession ? "" : sessionId);
                                    respondRpc(id, type, true, data, null);
                                }
                                case "get_available_models" -> {
                                    ObjectNode data = jsonObject();
                                    data.set("models", Json.MAPPER.valueToTree(allCatalogModels()));
                                    respondRpc(id, type, true, data, null);
                                }
                                case "set_model" -> {
                                    String provider = requiredRpcText(command, "provider");
                                    String modelId = requiredRpcText(command, "modelId");
                                    Model model = requireCatalogModel(provider, modelId);
                                    resetRpcAgent(model);
                                    respondRpc(id, "set_model", true, Json.MAPPER.valueToTree(model), null);
                                }
                                case "compact" -> {
                                    String instructions = command.path("customInstructions").isTextual()
                                            ? command.path("customInstructions").asText()
                                            : null;
                                    CompactionResult result1 = compact(instructions);
                                    respondRpc(id, type, true, Json.MAPPER.valueToTree(result1), null);
                                }
                                case "set_auto_compaction" -> {
                                    if (!command.path("enabled").isBoolean()) {
                                        throw new IllegalArgumentException("enabled must be a boolean");
                                    }
                                    autoCompactionEnabled = command.path("enabled").asBoolean();
                                    respondRpc(id, type, true, null, null);
                                }
                                case "get_messages" -> {
                                    ObjectNode data = jsonObject();
                                    data.set("messages", Json.MAPPER.valueToTree(messages));
                                    respondRpc(id, type, true, data, null);
                                }
                                case "get_last_assistant_text" -> {
                                    String assistantText = messages.reversed().stream()
                                            .filter(AssistantMessage.class::isInstance)
                                            .map(AssistantMessage.class::cast)
                                            .map(CodingAgentOperations::text)
                                            .findFirst()
                                            .orElse(null);
                                    ObjectNode data = jsonObject();
                                    if (assistantText == null) data.putNull("text");
                                    else data.put("text", assistantText);
                                    respondRpc(id, type, true, data, null);
                                }
                                case "new_session" -> {
                                    resetRpcAgent(selectedModel);
                                    respondRpc(id, type, true, jsonObject().put("cancelled", false), null);
                                }
                                default -> respondRpc(id, type, false, null, "Unsupported command: " + type);
                            }
                        } catch (Exception e) {
                            respondRpc(id, type, false, null, e.getMessage() == null ? e.toString() : e.getMessage());
                        }
                    }
                } finally {
                    mcpCloseManager();
                }
                return 0;
            }
            if (print) {
                if (this.message.isBlank()) {
                    throw new IllegalArgumentException("--print requires a prompt");
                }
                Model model = resolveCliModel(this.provider, this.model);
                Provider provider = requireCoreProvider(model.provider);
                Path cwd = Path.of(".").toAbsolutePath().normalize();
                mcpLoadDefaultManager(cwd);
                try {
                    mcpAwaitReady();
                    agentState(systemPrompt == null ? "" : systemPrompt, model);
                    agent(provider);
                    configureBuiltInTools(cwd, systemPrompt);
                    tools.addAll(mcpTools());
                    if (mode.equals("json")) {
                        subscribe(event -> {
                            ObjectNode node = encodeAgentEvent(event, false);
                            if (node != null) System.out.println(node);
                        });
                    } else {
                        subscribe(event -> {
                            if (event instanceof AgentEvent.InstructionLoaded loaded) {
                                System.err.println(instructionLoadedMessage(loaded.path));
                            }
                        });
                    }
                    if (!noSession) {
                        defaultSessionStore();
                        createSessionRecorder(cwd, model.provider, model.id);
                    }
                    List<Message> messages = prompt(this.message);
                    if (!noSession) appendSessionMessages(messages);
                    if (this.messages.getLast() instanceof AssistantMessage response) {
                        if (response.errorMessage != null) {
                            System.err.println("Error: " + response.errorMessage);
                            return 1;
                        }
                        if (!mode.equals("json")) {
                            System.out.println(text(response));
                        }
                        return 0;
                    }

                    throw new IllegalStateException("Agent ended without an assistant response");
                } finally {
                    mcpCloseManager();
                }
            }
            Path resolved = Path.of(System.getProperty("user.home"), ".codingagent", "settings.json").toAbsolutePath().normalize();
            settingsStore(
                    resolved, resolved.resolveSibling(resolved.getFileName() + ".lock"));
            ObjectNode root = readSettingsObject();
            String provider = optionalSettingsText(root, "defaultProvider");
            String model2 = optionalSettingsText(root, "defaultModel");
            String theme = optionalSettingsText(root, "theme");
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
            Settings settings = new Settings(
                    provider, model2, thinkingLevel, theme, value != null && value.asBoolean(false));
            Path workspace = Path.of(".").toAbsolutePath().normalize();
            mcpLoadDefaultManager(workspace);
            try {
                newInteractiveTerminal(
                        TerminalBuilder.builder().system(true).name(APP_NAME).build(),
                        () -> {
                            Process process =
                                    new ProcessBuilder("/bin/kill", "-TSTP", "0").redirectErrorStream(true).start();
                            try {
                                int exitCode = process.waitFor();
                                if (exitCode != 0) {
                                    String output =
                                            new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                                    throw new IOException(output.isEmpty() ? "kill exited with code " + exitCode : output);
                                }
                            } catch (InterruptedException error) {
                                Thread.currentThread().interrupt();
                                throw new IOException("Interrupted while suspending process", error);
                            }
                            return null;
                        },
                        !System.getProperty("os.name").startsWith("Windows"));
                try {
                    this.settings = settings;
                    agentConfigured = false;
                    recordingSession = false;
                    hideThinkingBlock = settings.hideThinkingBlock;
                    activity = noModelActivity(System.nanoTime());
                    statusTicker = Executors.newSingleThreadScheduledExecutor(
                            Thread.ofPlatform().daemon(true).name("codingagent-status").factory());
                    bindAppAction("expandTools", () -> showShellTurnDetails(true));
                    bindAppAction("toggleThinking", () -> setShellHideThinkingBlock(!hideThinkingBlock, true));
                    try {
                        if (this.settings.theme != null) {
                            setTheme(namedTheme(this.settings.theme));
                        }
                        if (this.model != null) {
                            // An explicit CLI model overrides the saved default for this session only.
                            configureShellModel(resolveCliModel(this.provider, this.model), false);
                        } else if (this.provider != null) {
                            List<Model> models1 = providerModels(requireCoreProvider(this.provider));
                            if (models1.isEmpty()) {
                                throw new IllegalArgumentException("No bundled models for provider: " + this.provider);
                            }
                            List<SelectItem<Model>> items =
                                    models1.stream().map(CodingAgentOperations::shellModelItem).toList();
                            Model model = select("Select a model", items, -1, true);
                            if (model == null) return 0;
                            configureShellModel(model, true);
                        } else {
                            restore: {
                                Settings savedSettings = this.settings;
                                Model model = null;
                                if (savedSettings.defaultProvider != null && savedSettings.defaultModel != null) {
                                    try {
                                        model = findModelIn(
                                                providerModels(requireCoreProvider(savedSettings.defaultProvider)),
                                                savedSettings.defaultProvider,
                                                savedSettings.defaultModel);
                                    } catch (IllegalArgumentException ignored) {
                                    }
                                    if (model == null) {
                                        println("Saved model " + savedSettings.defaultProvider + "/" + savedSettings.defaultModel
                                                + " is unavailable; selecting a fallback.");
                                    } else if (model.provider.equals(GITHUB_COPILOT_PROVIDER_ID)) {
                                        CodingAgentOperations copilot = (CodingAgentOperations)
                                                requireCoreProvider(GITHUB_COPILOT_PROVIDER_ID);
                                        try {
                                            model = copilot.gitHubCopilotHasCredential()
                                                    ? findModelIn(copilot.gitHubCopilotAvailableModels(), model.provider, model.id)
                                                    : null;
                                            if (model == null) {
                                                println("Saved GitHub Copilot model is not enabled for this account; selecting a fallback.");
                                            }
                                        } catch (IOException error) {
                                            println("Could not restore the saved GitHub Copilot model: " + error.getMessage());
                                            model = null;
                                        }
                                    } else if (model.provider.equals(CHATGPT_PROVIDER_ID)) {
                                        try {
                                            if (!((CodingAgentOperations) requireCoreProvider(CHATGPT_PROVIDER_ID))
                                                    .chatGptHasCredential()) model = null;
                                        } catch (IOException error) {
                                            println("Could not restore the saved ChatGPT model: " + error.getMessage());
                                            model = null;
                                        }
                                    }
                                }
                                if (model != null) {
                                    configureShellModel(model, false);
                                    break restore;
                                }

                                CodingAgentOperations chatGpt = (CodingAgentOperations)
                                        requireCoreProvider(CHATGPT_PROVIDER_ID);
                                try {
                                    if (chatGpt.chatGptHasCredential() && !chatGpt.models.isEmpty()) {
                                        configureShellModel(preferredChatGptModel(chatGpt.models), true);
                                        break restore;
                                    }
                                } catch (IOException error) {
                                    println("ChatGPT login needs attention: " + error.getMessage());
                                }
                                CodingAgentOperations copilot = (CodingAgentOperations)
                                        requireCoreProvider(GITHUB_COPILOT_PROVIDER_ID);
                                try {
                                    if (!copilot.gitHubCopilotHasCredential()) break restore;
                                    Model fallback = preferredCopilotModel(copilot.gitHubCopilotAvailableModels());
                                    if (fallback == null) {
                                        println("GitHub Copilot has no enabled coding models. Run /login to refresh access.");
                                    } else {
                                        configureShellModel(fallback, true);
                                    }
                                } catch (IOException error) {
                                    println("GitHub Copilot login needs attention: " + error.getMessage());
                                }
                            }
                        }
                        print(sessionScreenHeader(!agentConfigured ? null : selectedModel));
                        refreshShellStatus();
                        statusTicker.scheduleWithFixedDelay(() -> {
                            if (!isDynamicActivity(activity)) return;
                            try {
                                renderShellStatus();
                            } catch (RuntimeException ignored) {
                                // A best-effort repaint must not terminate the shell's status ticker.
                            }
                        }, 1, 1, TimeUnit.SECONDS);
                        while (true) {
                            String input = readLine("\n> ", SLASH_COMMANDS);
                            if (input == null) {
                                println("");
                                return 0;
                            }
                            if (input.isBlank()) continue;
                            if (input.startsWith("/")) {
                                String trimmed = input.trim();
                                boolean exit = trimmed.equals("/exit") || trimmed.equals("/quit");
                                setShellActivity(activeActivity(
                                        ActivityStatus.Phase.RUNNING_COMMAND, trimmed.split("\\s+", 2)[0], System.nanoTime()));
                                try {
                                    if (!exit) switch (trimmed) {
                                        case "/help" ->
                                                println("Commands: /help, /details, /fork, /resume, /login, /logout, /models, /mcp, /settings, /compact, /theme <dark|light|plain>, /exit\nShortcuts: Shift-Enter inserts a newline; Esc interrupts the active turn; Ctrl-O inspects reasoning/tool steps; Ctrl-T shows or hides streamed thinking.");
                                        case "/details" -> showShellTurnDetails(false);
                                        case "/fork" -> {
                                            if (!agentConfigured) {
                                                println("No model is configured.");
                                                break;
                                            }
                                            String name = readLine("Fork session name: ", forkName(sessionName));
                                            if (name == null || name.isBlank()) {
                                                println("Fork cancelled.");
                                                break;
                                            }
                                            name = name.strip();
                                            Model model = selectedModel;
                                            List<Message> forkMessages = resumableMessages(this.messages);
                                            boolean recordingEnabled = false;
                                            if (!noSession) {
                                                try {
                                                    defaultSessionStore();
                                                    forkSessionRecorder(
                                                            this.cwd, model.provider, model.id, name, forkMessages);
                                                    recordingEnabled = true;
                                                } catch (IOException error) {
                                                    println("Failed to fork session: " + error.getMessage());
                                                    break;
                                                }
                                            }
                                            configureShellAgent(model, this.cwd, recordingEnabled, name);
                                            this.messages.addAll(forkMessages);
                                            refreshShellStatus();
                                            println("Forked session " + name + " with " + forkMessages.size() + " message(s).");
                                        }
                                        case "/resume" -> {
                                            resume: {
                                                if (noSession) {
                                                    println("Session persistence is disabled by --no-session.");
                                                    break resume;
                                                }
                                                defaultSessionStore();
                                                List<SessionSnapshot> sessions;
                                                try {
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
                                                    sessions = List.copyOf(snapshots);
                                                } catch (IOException error) {
                                                    println("Failed to list saved sessions: " + error.getMessage());
                                                    break resume;
                                                }
                                                if (sessions.isEmpty()) {
                                                    println("No saved sessions in " + cwd + ".");
                                                    break resume;
                                                }
                                                String currentId = !recordingSession ? null : sessionId;
                                                List<SelectItem<SessionSnapshot>> items = sessions.stream()
                                                        .filter(session -> session.messageCount > 0 && !session.id.equals(currentId))
                                                        .map(session -> {
                                                            String name = sessionDisplayName(session);
                                                            String label = abbreviateShellText(
                                                                    name.replaceAll("[\\p{Cntrl}]", " "), 90);
                                                            long minutes = Math.max(0, Duration.between(
                                                                    session.modified, Instant.now()).toMinutes());
                                                            long hours = minutes / 60;
                                                            long days = hours / 24;
                                                            String age = minutes < 1 ? "now"
                                                                    : minutes < 60 ? minutes + "m"
                                                                      : hours < 24 ? hours + "h"
                                                                        : days < 7 ? days + "d"
                                                                          : days < 30 ? days / 7 + "w"
                                                                            : days < 365 ? days / 30 + "mo" : days / 365 + "y";
                                                            String description = session.messageCount + " messages  " + age
                                                                    + "  [" + session.provider + "/" + session.model + "]";
                                                            return new SelectItem<>(session, label, description,
                                                                    session.id + " " + name + " " + session.provider + " "
                                                                            + session.model + " " + session.firstMessage + " "
                                                                            + session.allMessagesText);
                                                        })
                                                        .toList();
                                                if (items.isEmpty()) {
                                                    println("No resumable sessions in " + cwd + ".");
                                                    break resume;
                                                }
                                                SessionSnapshot selected =
                                                        select("Resume Session (Current Folder)", items, -1, true);
                                                if (selected == null) break resume;
                                                try {
                                                    if (!Files.isDirectory(selected.cwd)) {
                                                        println("Cannot resume session because its working directory is unavailable: "
                                                                + selected.cwd);
                                                        break resume;
                                                    }
                                                    Model model;
                                                    try {
                                                        model = findModelIn(
                                                                providerModels(requireCoreProvider(selected.provider)),
                                                                selected.provider,
                                                                selected.model);
                                                    } catch (IllegalArgumentException ignored) {
                                                        model = null;
                                                    }
                                                    if (model == null) {
                                                        if (!agentConfigured) {
                                                            println("Cannot restore model " + selected.provider + "/" + selected.model
                                                                    + "; configure an available model before resuming this session.");
                                                            break resume;
                                                        }
                                                        model = selectedModel;
                                                        println("Could not restore model " + selected.provider + "/" + selected.model
                                                                + ". Using " + model + ".");
                                                    }
                                                    if (model.provider.equals(GITHUB_COPILOT_PROVIDER_ID)) {
                                                        CodingAgentOperations copilot = (CodingAgentOperations)
                                                                requireCoreProvider(GITHUB_COPILOT_PROVIDER_ID);
                                                        Model enabled = null;
                                                        try {
                                                            if (copilot.gitHubCopilotHasCredential()) {
                                                                enabled = findModelIn(
                                                                        copilot.gitHubCopilotAvailableModels(),
                                                                        model.provider,
                                                                        model.id);
                                                            }
                                                        } catch (IOException error) {
                                                            println("Could not refresh GitHub Copilot model access: "
                                                                    + error.getMessage());
                                                        }
                                                        if (enabled != null) model = enabled;
                                                        else if (!agentConfigured
                                                                || selectedModel.provider.equals(GITHUB_COPILOT_PROVIDER_ID)) {
                                                            println("Cannot restore GitHub Copilot model " + model.id
                                                                    + "; log in or configure another model first.");
                                                            break resume;
                                                        } else {
                                                            Model fallback = selectedModel;
                                                            println("Could not restore model " + model + ". Using " + fallback + ".");
                                                            model = fallback;
                                                        }
                                                    }
                                                    // A session snapshot's messages are compaction-aware, so resuming cannot
                                                    // resurrect summarized transcript entries into the next model request.
                                                    List<Message> restored = resumableMessages(selected.messages);
                                                    resumeSessionRecorder(selected.id);
                                                    configureShellAgent(model, selected.cwd, true, selected.name);
                                                    settings = withSettingsDefaultModel(settings, model.provider, model.id);
                                                    messages.addAll(restored);
                                                    refreshShellStatus();
                                                    replaceScreen(renderSessionScreen(
                                                            model,
                                                            selected.transcriptMessages,
                                                            hideThinkingBlock,
                                                            terminalTheme()));
                                                    try {
                                                        setSettingsDefaultModelAndProvider(model.provider, model.id);
                                                    } catch (IOException error) {
                                                        println("Resumed model could not be saved as the default: "
                                                                + error.getMessage());
                                                    }
                                                    println("Resumed session " + sessionDisplayName(selected) + " with "
                                                            + restored.size() + " message(s) using " + model + ".");
                                                } catch (IOException | IllegalArgumentException error) {
                                                    println("Failed to resume session: " + error.getMessage());
                                                }
                                            }
                                        }
                                        case "/login" -> {
                                            println("Log in to a provider:");
                                            println("  1. GitHub Copilot — sign in through GitHub's device authorization flow");
                                            println("  2. OpenAI API key — use separately billed Platform API credits");
                                            println("  3. ChatGPT Plus/Pro — use your ChatGPT subscription through Codex");
                                            String choice = readLine("Select provider [1-3]: ");
                                            if (choice == null || choice.isBlank()) {
                                                println("Login cancelled.");
                                            } else {
                                                switch (choice.trim().toLowerCase(Locale.ROOT)) {
                                                    case "1", "github", "github copilot", "copilot" -> {
                                                        CodingAgentOperations copilot = (CodingAgentOperations)
                                                                requireCoreProvider(GITHUB_COPILOT_PROVIDER_ID);
                                                        GitHubCopilotDeviceCode device = copilot.gitHubCopilotBeginLogin();
                                                        println("Open " + device.verificationUri + " and enter code " + device.userCode + ".");
                                                        println("Waiting for GitHub authorization...");
                                                        copilot.gitHubCopilotCompleteLogin(device);
                                                        println("Enabling GitHub Copilot models...");
                                                        CopilotModelAccess access = copilot.gitHubCopilotEnableAndRefreshModels();
                                                        if (access.policiesEnabled < copilot.models.size()) {
                                                            println("Some GitHub Copilot models are unavailable for this account.");
                                                        }
                                                        List<Model> models1 = access.models;
                                                        Model model = shellSavedModelIn(models1);
                                                        if (model == null) model = preferredCopilotModel(models1);
                                                        if (model == null) {
                                                            println("GitHub Copilot login succeeded, but no enabled coding model was returned.");
                                                        } else {
                                                            configureShellModel(model, true);
                                                            println("GitHub Copilot is ready with " + model + ".");
                                                        }
                                                    }
                                                    case "2", "openai", "open ai", "openai api", "openai api key" -> {
                                                        String apiKey = readLineInternal("OpenAI API key: ", null, '*', false);
                                                        if (apiKey == null || apiKey.isBlank()) {
                                                            println("OpenAI login cancelled.");
                                                        } else {
                                                            modifyCredential(
                                                                    defaultCredentialStore(),
                                                                    "openai",
                                                                    ignored -> new Credential.ApiKeyCredential(apiKey.trim(), Map.of()));
                                                            List<Model> models1 = providerModels(requireCoreProvider("openai"));
                                                            Model model = shellSavedModelIn(models1);
                                                            if (model == null) {
                                                                model = this.select("Select an OpenAI model", models1.stream().map(CodingAgentOperations::shellModelItem).toList(), -1, true);
                                                            }
                                                            if (model == null) {
                                                                println("OpenAI API key saved. Run /models when you are ready to select a model.");
                                                            } else {
                                                                configureShellModel(model, true);
                                                                println("OpenAI is ready with " + model + ".");
                                                            }
                                                        }
                                                    }
                                                    case "3", "chatgpt", "chatgpt plus", "chatgpt pro",
                                                         "chatgpt plus/pro" -> {
                                                        CodingAgentOperations chatGpt = (CodingAgentOperations)
                                                                requireCoreProvider(CHATGPT_PROVIDER_ID);
                                                        ChatGptDeviceCode device = chatGpt.chatGptBeginLogin();
                                                        println("Open " + device.verificationUri + " and enter code " + device.userCode + ".");
                                                        println("Waiting for ChatGPT authorization...");
                                                        chatGpt.chatGptCompleteLogin(device);
                                                        List<Model> models1 = chatGpt.models;
                                                        Model model = shellSavedModelIn(models1);
                                                        if (model == null) {
                                                            model = this.select("Select a ChatGPT model", models1.stream().map(CodingAgentOperations::shellModelItem).toList(), -1, true);
                                                        }
                                                        if (model == null) {
                                                            println("ChatGPT login saved. Run /models when you are ready to select a model.");
                                                        } else {
                                                            configureShellModel(model, true);
                                                            println("ChatGPT Plus/Pro is ready with " + model + ".");
                                                        }
                                                    }
                                                    default ->
                                                            println("Unknown provider. Enter 1 for GitHub Copilot, 2 for an OpenAI API key, or 3 for ChatGPT Plus/Pro.");
                                                }
                                            }
                                        }
                                        case "/logout" -> {
                                            try {
                                                if (agentConfigured && selectedModel.provider.equals(CHATGPT_PROVIDER_ID)) {
                                                    ((CodingAgentOperations) requireCoreProvider(CHATGPT_PROVIDER_ID))
                                                            .chatGptLogout();
                                                    agentConfigured = false;
                                                    println("ChatGPT credentials removed. Run /login or /resume to continue.");
                                                } else if (agentConfigured && selectedModel.provider.equals("openai")) {
                                                    deleteCredential(defaultCredentialStore(), "openai");
                                                    agentConfigured = false;
                                                    println("OpenAI API key removed. Run /login or /resume to continue.");
                                                } else {
                                                    ((CodingAgentOperations) requireCoreProvider(GITHUB_COPILOT_PROVIDER_ID))
                                                            .gitHubCopilotLogout();
                                                    if (agentConfigured
                                                            && selectedModel.provider.equals(GITHUB_COPILOT_PROVIDER_ID)) {
                                                        agentConfigured = false;
                                                        println("GitHub Copilot credentials removed. Run /login or /resume to continue.");
                                                    } else {
                                                        println("GitHub Copilot credentials removed.");
                                                    }
                                                }
                                            } finally {
                                                refreshShellStatus();
                                            }
                                        }
                                        case "/models" -> {
                                            List<Model> models2 = new ArrayList<>();
                                            for (Model model1 : allCatalogModels()) {
                                                if (!model1.provider.equals(GITHUB_COPILOT_PROVIDER_ID)) {
                                                    models2.add(model1);
                                                }
                                            }
                                            CodingAgentOperations copilot = (CodingAgentOperations)
                                                    requireCoreProvider(GITHUB_COPILOT_PROVIDER_ID);
                                            try {
                                                if (copilot.gitHubCopilotHasCredential()) {
                                                    println("Refreshing GitHub Copilot models...");
                                                    models2.addAll(copilot.gitHubCopilotEnableAndRefreshModels().models);
                                                } else {
                                                    models2.addAll(copilot.models);
                                                }
                                            } catch (IOException error) {
                                                println("Could not refresh GitHub Copilot model access: " + error.getMessage());
                                                models2.addAll(copilot.models);
                                            }
                                            models2.addAll(((CodingAgentOperations)
                                                    requireCoreProvider(CHATGPT_PROVIDER_ID)).models);
                                            List<Model> models1 = List.copyOf(models2);
                                            List<SelectItem<Model>> items =
                                                    models1.stream().map(CodingAgentOperations::shellModelItem).toList();
                                            int currentIndex;
                                            if (!agentConfigured) {
                                                currentIndex = -1;
                                            } else {
                                                int result1 = -1;
                                                for (int index = 0; index < models1.size(); index++) {
                                                    Model model = models1.get(index);
                                                    if (model.provider.equals(selectedModel.provider) && model.id.equals(selectedModel.id)) {
                                                        result1 = index;
                                                        break;
                                                    }
                                                }
                                                currentIndex = result1;
                                            }
                                            Model model = select("Select a model", items, currentIndex, true);
                                            if (model != null) {
                                                configureShellModel(model, true);
                                                println("Using " + model + " in a new agent session.");
                                            }
                                        }
                                        case "/mcp" -> {
                                            if (servers.isEmpty()) {
                                                println("No MCP servers configured in ~/.codingagent/settings.json.");
                                            } else {
                                                McpSelector selector1 = new McpSelector();
                                                selector1.manager = this;
                                                selector1.onChange = change -> {
                                                    try {
                                                        if (change.toolName != null) {
                                                            requireSettingsValue(change.serverName, "serverName");
                                                            requireSettingsValue(change.toolName, "toolName");
                                                            this.modifySettings(root1 -> {
                                                                ObjectNode server = settingsMcpServer(root1, change.serverName);
                                                                ArrayNode disabledTools = null;
                                                                JsonNode value1 = server.get("disabledTools");
                                                                if (value1 != null && !value1.isNull()) {
                                                                    if (!(value1 instanceof ArrayNode tools)) {
                                                                        throw new UncheckedIOException(new IOException(
                                                                                "Invalid MCP server \"" + change.serverName + "\": disabledTools must be an array"));
                                                                    }
                                                                    for (JsonNode tool1 : tools) {
                                                                        settingsDisabledToolName(change.serverName, tool1);
                                                                    }
                                                                    disabledTools = tools;
                                                                }
                                                                if (change.enabled) {
                                                                    if (disabledTools == null) return;
                                                                    ArrayNode retained = Json.MAPPER.createArrayNode();
                                                                    for (JsonNode tool : disabledTools) {
                                                                        String name = settingsDisabledToolName(change.serverName, tool);
                                                                        if (!name.equals(change.toolName))
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
                                                                    if (settingsDisabledToolName(change.serverName, tool).equals(change.toolName))
                                                                        return;
                                                                }
                                                                disabledTools.add(change.toolName);
                                                            });
                                                        } else {
                                                            requireSettingsValue(change.serverName, "serverName");
                                                            this.modifySettings(root1 -> settingsMcpServer(root1, change.serverName).put("enabled", change.enabled));
                                                        }
                                                    } catch (IOException error) {
                                                        throw new UncheckedIOException(error);
                                                    } finally {
                                                        syncShellMcpTools();
                                                    }
                                                };
                                                selector1.names = servers.values().stream()
                                                        .sorted(Comparator.comparing(runtime -> runtime.name))
                                                        .map(CodingAgentOperations::mcpSnapshot)
                                                        .toList().stream().map(status -> status.name).toList();
                                                selector1.filtered = selector1.names;
                                                runComponent(new TuiComponent<>(
                                                        frame -> {
                                                            if (selector1.view == McpSelector.View.TOOLS)
                                                                refreshMcpSelectorTools(selector1);

                                                            List<String> lines = new ArrayList<>();
                                                            String title = selector1.view == McpSelector.View.SERVERS
                                                                    ? "MCP Servers"
                                                                    : "MCP Tools: " + selector1.toolServer;
                                                            lines.add(frame.theme.heading + truncatePlain(title, frame.width) + frame.theme.reset);
                                                            lines.add("");
                                                            String before = selector1.query.substring(0, selector1.queryCursor);
                                                            String after = selector1.query.substring(selector1.queryCursor);
                                                            lines.add(truncatePlain("Search: " + before + "|" + after, frame.width));
                                                            lines.add("");
                                                            selector1.optionStartRow = lines.size();
                                                            selector1.visibleCount = Math.clamp(frame.height - 9, 1, 10);
                                                            int itemCount = mcpSelectorItemCount(selector1);
                                                            selector1.visibleStart = Math.max(0, Math.clamp(itemCount - selector1.visibleCount, 0,
                                                                    selector1.selectedIndex - selector1.visibleCount / 2));
                                                            int end = Math.min(itemCount, selector1.visibleStart + selector1.visibleCount);
                                                            if (itemCount == 0) {
                                                                String empty = selector1.view == McpSelector.View.SERVERS
                                                                        ? "  No matching servers"
                                                                        : "  No tools available";
                                                                lines.add(frame.theme.muted + empty + frame.theme.reset);
                                                            } else {
                                                                for (int index = selector1.visibleStart; index < end; index++) {
                                                                    McpServerStatus status = selector1.manager.mcpStatus(selector1.filtered.get(index));
                                                                    McpToolStatus status1 = selector1.filteredTools.get(index);
                                                                    String row = (index == selector1.selectedIndex ? "> " : "  ")
                                                                            + (selector1.view == McpSelector.View.SERVERS
                                                                            ? switch (status.state) {
                                                                        case CONNECTING ->
                                                                                "⋯ " + status.name + "  Connecting";
                                                                        case AUTHENTICATING ->
                                                                                "⋯ " + status.name + "  Waiting for OAuth";
                                                                        case AUTH_REQUIRED ->
                                                                                "! " + status.name + "  Authentication required";
                                                                        case CONNECTED -> {
                                                                            String result1;
                                                                            if (status.enabledToolCount == status.toolCount) {
                                                                                result1 = status.toolCount + " tool(s)";
                                                                            } else {
                                                                                result1 = status.enabledToolCount + "/" + status.toolCount + " tool(s)";
                                                                            }
                                                                            yield "✓ " + status.name + "  Enabled · " + result1;
                                                                        }
                                                                        case DISABLED ->
                                                                                "○ " + status.name + "  Disabled";
                                                                        case FAILED -> "✗ " + status.name + "  Failed";
                                                                    }
                                                                            : (status1.enabled ? "✓ " : "○ ") + status1.name + "  " + (status1.enabled ? "Enabled" : "Disabled"));
                                                                    row = truncatePlain(row, frame.width);
                                                                    lines.add(index == selector1.selectedIndex ? frame.theme.heading + row + frame.theme.reset : row);
                                                                }
                                                                if (selector1.visibleStart > 0 || end < itemCount) {
                                                                    lines.add(frame.theme.muted + "  " + (selector1.selectedIndex + 1) + "/" + itemCount + frame.theme.reset);
                                                                }
                                                            }
                                                            lines.add("");
                                                            String detail;
                                                            if (selector1.changeError == null) {
                                                                String result1 = null;
                                                                if (selector1.view == McpSelector.View.SERVERS) {
                                                                    if (!selector1.filtered.isEmpty()) {
                                                                        McpServerStatus selected =
                                                                                selector1.manager.mcpStatus(selector1.filtered.get(selector1.selectedIndex));
                                                                        result1 = selected.message == null ? selected.target : selected.message;
                                                                    }
                                                                } else if (!selector1.filteredTools.isEmpty()) {
                                                                    String description = selector1.filteredTools.get(selector1.selectedIndex).description;
                                                                    result1 = description.isBlank() ? "No description" : description;
                                                                }
                                                                detail = result1;
                                                            } else {
                                                                detail = selector1.changeError;
                                                            }
                                                            if (detail != null) {
                                                                String style = selector1.changeError == null ? frame.theme.muted : warningStatus(frame.theme);
                                                                lines.add(style + truncatePlain("  " + detail, frame.width) + frame.theme.reset);
                                                            }
                                                            if (selector1.view == McpSelector.View.SERVERS && !selector1.filtered.isEmpty()) {
                                                                McpServerStatus selected =
                                                                        selector1.manager.mcpStatus(selector1.filtered.get(selector1.selectedIndex));
                                                                if (selected.authorizationUrl != null) {
                                                                    String label = truncatePlain("Open: " + selected.authorizationUrl, Math.max(1, frame.width - 2));
                                                                    String safeUrl = selected.authorizationUrl.replace("\u001b", "").replace("\u0007", "");
                                                                    String link = "\u001b]8;;" + safeUrl + "\u001b\\" + label + "\u001b]8;;\u001b\\";
                                                                    lines.add(frame.theme.muted + "  " + link + frame.theme.reset);
                                                                }
                                                            }
                                                            String hint = selector1.view == McpSelector.View.SERVERS
                                                                    ? "Type to filter  Up/Down move  Enter toggle/auth/retry  Tab tools  Esc close"
                                                                    : "Type to filter  Up/Down move  Enter toggle  Tab/Esc servers";
                                                            lines.add(frame.theme.muted + truncatePlain(hint, frame.width) + frame.theme.reset);
                                                            return lines;
                                                        },
                                                        input1 -> {
                                                            switch ((TuiInput) input1) {
                                                                case TuiInput.Key key -> {
                                                                    switch (key.type) {
                                                                        case UP -> moveMcpSelector(selector1, -1);
                                                                        case DOWN -> moveMcpSelector(selector1, 1);
                                                                        case PAGE_UP ->
                                                                                moveMcpSelector(selector1, -Math.max(1, selector1.visibleCount));
                                                                        case PAGE_DOWN ->
                                                                                moveMcpSelector(selector1, Math.max(1, selector1.visibleCount));
                                                                        case ENTER -> {
                                                                            if (selector1.view == McpSelector.View.SERVERS) {
                                                                                if (!selector1.filtered.isEmpty()) {
                                                                                    McpServerStatus status;
                                                                                    String name = selector1.filtered.get(selector1.selectedIndex);
                                                                                    McpRuntime runtime2 = selector1.manager.mcpRequireRuntime(name);
                                                                                    McpState state;
                                                                                    synchronized (runtime2.lock) {
                                                                                        state = runtime2.state;
                                                                                    }
                                                                                    if (state == McpState.CONNECTED
                                                                                            || state == McpState.CONNECTING
                                                                                            || state == McpState.AUTHENTICATING) {
                                                                                        McpRuntime runtime1 = selector1.manager.mcpRequireRuntime(name);
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
                                                                                        McpRuntime runtime1 = selector1.manager.mcpRequireRuntime(name);
                                                                                        selector1.manager.mcpStartConnect(runtime1, true);
                                                                                        status = mcpSnapshot(runtime1);
                                                                                    }
                                                                                    boolean result1;
                                                                                    McpRuntime runtime = selector1.manager.mcpRequireRuntime(status.name);
                                                                                    synchronized (runtime.lock) {
                                                                                        result1 = runtime.enabled;
                                                                                    }
                                                                                    notifyMcpSelectorChange(selector1, new McpSelector.Change(
                                                                                            status.name, null, result1));
                                                                                }
                                                                            } else {
                                                                                refreshMcpSelectorTools(selector1);
                                                                                if (!selector1.filteredTools.isEmpty()) {
                                                                                    try {
                                                                                        McpToolStatus result1;
                                                                                        McpRuntime runtime = selector1.manager.mcpRequireRuntime(selector1.toolServer);
                                                                                        synchronized (runtime.lock) {
                                                                                            if (runtime.state != McpState.CONNECTED || runtime.client == null) {
                                                                                                throw new IllegalStateException("MCP server is not connected: " + selector1.toolServer);
                                                                                            }
                                                                                            McpClient.ToolDefinition definition = runtime.tools.stream()
                                                                                                    .filter(tool -> tool.name.equals(selector1.filteredTools.get(selector1.selectedIndex).name))
                                                                                                    .findFirst()
                                                                                                    .orElseThrow(() -> new IllegalArgumentException(
                                                                                                            "MCP tool is not available from " + selector1.toolServer + ": " + selector1.filteredTools.get(selector1.selectedIndex).name));
                                                                                            boolean enabled;
                                                                                            if (runtime.disabledTools.remove(selector1.filteredTools.get(selector1.selectedIndex).name)) {
                                                                                                enabled = true;
                                                                                            } else {
                                                                                                runtime.disabledTools.add(selector1.filteredTools.get(selector1.selectedIndex).name);
                                                                                                enabled = false;
                                                                                            }
                                                                                            result1 = new McpToolStatus(runtime.name, definition.name, definition.description, enabled);
                                                                                        }
                                                                                        McpToolStatus status = result1;
                                                                                        notifyMcpSelectorChange(
                                                                                                selector1, new McpSelector.Change(status.serverName, status.name, status.enabled));
                                                                                        refreshMcpSelectorTools(selector1);
                                                                                    } catch (IllegalStateException |
                                                                                             IllegalArgumentException ignored) {
                                                                                        // The server or its catalog may have changed while this selector was open.
                                                                                        refreshMcpSelectorTools(selector1);
                                                                                    }
                                                                                }
                                                                            }
                                                                        }
                                                                        case TAB -> {
                                                                            if (selector1.view == McpSelector.View.SERVERS) {
                                                                                if (!selector1.filtered.isEmpty()) {
                                                                                    String server = selector1.filtered.get(selector1.selectedIndex);
                                                                                    if (selector1.manager.mcpStatus(server).state == McpState.CONNECTED) {
                                                                                        selector1.view = McpSelector.View.TOOLS;
                                                                                        selector1.toolServer = server;
                                                                                        clearMcpSelectorQuery(selector1);
                                                                                        refreshMcpSelectorTools(selector1);
                                                                                    }
                                                                                }
                                                                            } else closeMcpSelectorTools(selector1);
                                                                        }
                                                                        case ESCAPE, CANCEL -> {
                                                                            if (selector1.view == McpSelector.View.TOOLS)
                                                                                closeMcpSelectorTools(selector1);
                                                                            else selector1.complete = true;
                                                                        }
                                                                        case CHARACTER, PASTE, BACKSPACE, DELETE, LEFT,
                                                                             RIGHT, HOME, END, CLEAR -> {
                                                                            int cursor = editQuery(
                                                                                    selector1.query,
                                                                                    selector1.queryCursor,
                                                                                    key,
                                                                                    () -> filterMcpSelector(selector1));
                                                                            if (cursor >= 0)
                                                                                selector1.queryCursor = cursor;
                                                                        }
                                                                        default -> {
                                                                        }
                                                                    }
                                                                }
                                                                case TuiInput.Mouse mouse -> {
                                                                    switch (mouse.action) {
                                                                        case SCROLL_UP -> moveMcpSelector(selector1, -1);
                                                                        case SCROLL_DOWN ->
                                                                                moveMcpSelector(selector1, 1);
                                                                        case PRESS -> {
                                                                            int offset = mouse.y - 1 - selector1.optionStartRow;
                                                                            int index = selector1.visibleStart + offset;
                                                                            if (mouse.button == 0
                                                                                    && offset >= 0
                                                                                    && offset < selector1.visibleCount
                                                                                    && index < mcpSelectorItemCount(selector1)) {
                                                                                selector1.selectedIndex = index;
                                                                            }
                                                                        }
                                                                        default -> {
                                                                        }
                                                                    }
                                                                }
                                                                case TuiInput.Resize ignored -> {
                                                                }
                                                            }
                                                        },
                                                        () -> selector1.complete,
                                                        () -> null));// An OAuth connection may finish asynchronously while the selector is open.
                                                syncShellMcpTools();
                                            }
                                        }
                                        case "/settings" -> {
                                            if (!agentConfigured) {
                                                println("No model is configured.");
                                            } else {
                                                this.select("Settings", List.of(new SelectItem<>(
                                                                "thinking",
                                                                "Thinking level",
                                                                this.thinkingLevel.wire,
                                                                "Thinking level " + this.thinkingLevel.wire)), 0, false);
                                                List<ThinkingLevel> levels = getSupportedThinkingLevels(selectedModel);
                                                List<SelectItem<ThinkingLevel>> items = levels.stream()
                                                        .map(level -> {
                                                            String description = switch (level) {
                                                                case OFF -> "No reasoning";
                                                                case MINIMAL -> "Very brief reasoning";
                                                                case LOW -> "Light reasoning";
                                                                case MEDIUM -> "Moderate reasoning";
                                                                case HIGH -> "Deep reasoning";
                                                                case XHIGH -> "Extra-high reasoning";
                                                                case MAX -> "Maximum reasoning";
                                                            };
                                                            return new SelectItem<>(
                                                                    level, level.wire, description, level.wire + " " + description);
                                                        })
                                                        .toList();
                                                int currentIndex = Math.max(0, levels.indexOf(this.thinkingLevel));
                                                ThinkingLevel level = select("Thinking level", items, currentIndex, false);
                                                if (level != null) {
                                                    this.thinkingLevel = level;
                                                    refreshShellStatus();
                                                    this.settings = new Settings(
                                                            this.settings.defaultProvider, this.settings.defaultModel, level, this.settings.theme, this.settings.hideThinkingBlock);
                                                    try {
                                                        this.modifySettings(root1 -> root1.put("defaultThinkingLevel", level.wire));
                                                        println("Thinking level: " + level.wire);
                                                    } catch (IOException error) {
                                                        println("Thinking level changed for this session, but could not be saved: " + error.getMessage());
                                                    }
                                                }
                                            }
                                        }
                                        case "/compact" -> {
                                            if (!agentConfigured) {
                                                println("No model is configured.");
                                                break;
                                            }
                                            try {
                                                CompactionResult result1 = compact(null);
                                                println("Context compacted: " + result1.tokensBefore + " -> " + result1.estimatedTokensAfter + " tokens.");
                                                refreshShellStatus();
                                            } catch (IllegalStateException error) {
                                                println("Error: " + error.getMessage());
                                            }
                                        }
                                        default -> {
                                            if (input.startsWith("/theme ")) {
                                                Theme theme1 = namedTheme(input.substring("/theme ".length()).trim());
                                                setTheme(theme1);
                                                refreshShellStatus();
                                                this.settings = new Settings(
                                                        this.settings.defaultProvider,
                                                        this.settings.defaultModel,
                                                        this.settings.defaultThinkingLevel,
                                                        theme1.name1,
                                                        this.settings.hideThinkingBlock);
                                                try {
                                                    requireSettingsValue(theme1.name1, "theme");
                                                    this.modifySettings(root1 -> root1.put("theme", theme1.name1));
                                                    println("Theme: " + terminalTheme().name1);
                                                } catch (IOException error) {
                                                    println("Theme changed for this session, but could not be saved: " + error.getMessage());
                                                }
                                            } else println("Unknown command: " + input);
                                        }
                                    }
                                } finally {
                                    setShellActivity(!agentConfigured
                                            ? noModelActivity(System.nanoTime())
                                            : readyActivity(System.nanoTime()));
                                    refreshShellStatus();
                                }
                                if (exit) return 0;
                                continue;
                            }
                            if (!agentConfigured) {
                                println("No model configured. Run /login to choose a provider.");
                                continue;
                            }
                            setShellActivity(activeActivity(ActivityStatus.Phase.PREPARING_TOOLS, System.nanoTime()));
                            mcpAwaitReady();
                            syncShellMcpTools();
                            emittedText = false;
                            streamOutput = StreamOutput.NONE;
                            streamedThinkingCharacters = 0;
                            AtomicBoolean interrupted = new AtomicBoolean();
                            List<Message> messages = runInterruptibly(() -> prompt(input), () -> {
                                        interrupted.set(true);
                                        setShellActivity(activeActivity(ActivityStatus.Phase.STOPPING, System.nanoTime()));
                                        abort();
                                    });
                            if (recordingSession) appendSessionMessages(messages);
                            if (interrupted.get()) {
                                finishShellStreamOutput();
                                println("Interrupted.");
                            } else if (this.messages.getLast() instanceof AssistantMessage response) {
                                String finalOutput = finalAssistantOutput(response, emittedText);
                                if (finalOutput != null) {
                                    finishShellStreamOutput();
                                    println(finalOutput);
                                }
                            }
// AgentEnd normally performs this transition. Reassert it here to close
// the small race where Escape arrives after AgentEnd but before the task returns.
                            setShellActivity(readyActivity(System.nanoTime()));
                            refreshShellStatus();
                        }
                    } finally {
                        statusTicker.shutdownNow();
                        try {
                            statusTicker.awaitTermination(1, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }
                } finally {
                    closeTerminal();
                }
            } finally {
                mcpCloseManager();
            }
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            return 2;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Error: interrupted");
            return 130;
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private static String cliArgumentValue(String[] args, int index, String flag) {
        if (index >= args.length || args[index].startsWith("-")) {
            throw new IllegalArgumentException(flag + " requires a value");
        }
        return args[index];
    }

    /**
     * Adds local tools and composes repository {@code AGENTS.md} text into the
     * agent's system prompt. Path-based tools refresh the prompt when they move
     * into a deeper descendant scope.
     */
    private void agentInstructions(
            Path repositoryRoot, Path currentDirectory, String baseSystemPrompt) {
        this.repositoryRoot = repositoryRoot;
        this.currentDirectory = currentDirectory;
        this.baseSystemPrompt = baseSystemPrompt;
    }

    private void configureBuiltInTools(Path cwd, String baseSystemPrompt) {
        Path directory = instructionDirectory(cwd);
        if (directory == null) {
            throw new IllegalArgumentException("workingDirectory must have a parent directory");
        }
        Path repositoryRoot = directory;
        for (Path current = directory; current != null; current = current.getParent()) {
            if (Files.exists(current.resolve(".git"))) {
                repositoryRoot = current;
                break;
            }
        }
        agentInstructions(
                repositoryRoot, directory, baseSystemPrompt == null ? "" : baseSystemPrompt);
        refreshAgentInstructionsIn(directory);
        Set<Path> announcedSources = new LinkedHashSet<>();
        subscribe(event -> {
            if (event instanceof AgentEvent.AgentStart) {
                synchronized (this) {
                    refreshAgentInstructionsIn(currentDirectory);
                }
                applyAgentInstructions(announcedSources);
            }
        });
        this.executable = "git";
        tools.addAll(builtInTools(cwd, path -> {
            boolean result;
            synchronized (this) {
                Path directory1 = instructionDirectory(path);
                if (directory1 == null
                        || !directory1.startsWith(this.repositoryRoot)
                        || !directory1.startsWith(currentDirectory)) {
                    result = false;
                } else {
                    result = refreshAgentInstructionsIn(directory1);
                }
            }
            if (result) {
                applyAgentInstructions(announcedSources);
            }
        }));
    }

    private void applyAgentInstructions(Set<Path> announcedSources) {
        List<Path> sources = this.sources;
        announcedSources.retainAll(sources);
        for (Path source : sources) {
            if (announcedSources.add(source)) {
                emit(new AgentEvent.InstructionLoaded(Objects.requireNonNull(source, "path")));
            }
        }
    }

    private static String instructionLoadedMessage(Path path) {
        return "Found " + path;
    }

    /**
     * Resolves {@code --provider}/{@code --model} into one bundled catalog model.
     */
    private Model resolveCliModel(String providerArg, String modelArg) {
        String provider = providerArg;
        String model = modelArg;
        if (model != null && model.contains("/")) {
            String[] parts = model.split("/", 2);
            if (provider != null && !provider.equals(parts[0])) {
                throw new IllegalArgumentException("--provider conflicts with the provider in --model");
            }
            provider = parts[0];
            model = parts[1];
        }
        if (provider == null || model == null) {
            throw new IllegalArgumentException("--print requires --model <provider/model> (for example, anthropic/claude-haiku-4-5)");
        }
        for (Model candidate : providerModels(requireCoreProvider(provider))) {
            if (candidate.id.equals(model)) return candidate;
        }
        throw new IllegalArgumentException("Unknown model: " + provider + "/" + model);
    }

    // -------------------------------------------------------- activity status

    public static ActivityStatus noModelActivity(long nowNanos) {
        return new ActivityStatus(ActivityStatus.Phase.NO_MODEL, "", 0, 0, nowNanos, 0);
    }

    public static ActivityStatus readyActivity(long nowNanos) {
        return new ActivityStatus(ActivityStatus.Phase.READY, "", 0, 0, nowNanos, 0);
    }

    public static ActivityStatus activeActivity(ActivityStatus.Phase phase, long nowNanos) {
        return activeActivity(phase, "", nowNanos);
    }

    public static ActivityStatus activeActivity(ActivityStatus.Phase phase, String detail, long nowNanos) {
        return new ActivityStatus(phase, detail, 0, 0, nowNanos, 0);
    }

    public static ActivityStatus retryingActivity(int attempt, int maxAttempts, long delayMs, long nowNanos) {
        long delayNanos;
        try {
            delayNanos = Math.multiplyExact(Math.max(0, delayMs), 1_000_000L);
        } catch (ArithmeticException ignored) {
            delayNanos = Long.MAX_VALUE;
        }
        return new ActivityStatus(
                ActivityStatus.Phase.RETRYING, "", attempt, maxAttempts, nowNanos, delayNanos);
    }

    /**
     * Whether a repeated event describes the same phase and should retain its elapsed timer.
     */
    public static boolean sameActivity(ActivityStatus status, ActivityStatus other) {
        return other != null
                && status.phase == other.phase
                && status.detail.equals(other.detail)
                && status.attempt == other.attempt
                && status.maxAttempts == other.maxAttempts
                && status.retryDelayNanos == other.retryDelayNanos;
    }

    public static boolean isDynamicActivity(ActivityStatus status) {
        return status.phase != ActivityStatus.Phase.NO_MODEL
                && status.phase != ActivityStatus.Phase.READY;
    }

    public static String activityLabel(ActivityStatus status, long nowNanos) {
        return switch (status.phase) {
            case NO_MODEL -> "○ No model";
            case READY -> "● Ready";
            case RUNNING_COMMAND -> activityBusyLabel(
                    status,
                    status.detail.isBlank() ? "Running command" : "Command: " + status.detail,
                    nowNanos);
            case PREPARING_TOOLS -> activityBusyLabel(status, "Preparing tools", nowNanos);
            case COMPACTING -> activityBusyLabel(status, "Compacting context", nowNanos);
            case WAITING_FOR_MODEL -> activityBusyLabel(status, "Waiting for model", nowNanos);
            case REASONING -> activityBusyLabel(status, "Reasoning", nowNanos);
            case RESPONDING -> activityBusyLabel(status, "Responding", nowNanos);
            case PREPARING_TOOL -> activityBusyLabel(
                    status,
                    status.detail.isBlank() ? "Preparing tool call" : "Preparing tool: " + status.detail,
                    nowNanos);
            case RUNNING_TOOL -> "⚙ Tool: " + (status.detail.isBlank() ? "unknown" : status.detail)
                    + " · " + formatActivityElapsed(status, nowNanos);
            case RETRYING -> {
                String result;
                String progress = status.attempt + "/" + status.maxAttempts;
                long remaining = Math.max(0, status.retryDelayNanos - activityElapsedNanos(status, nowNanos));
                if (remaining > 0) {
                    long seconds = 1 + (remaining - 1) / 1_000_000_000L;
                    result = "↻ Retry " + progress + " in " + seconds + "s";
                } else {
                    result = "↻ Retry " + progress + " · waiting for model";
                }
                yield result;
            }
            case STOPPING -> "◌ Stopping · " + formatActivityElapsed(status, nowNanos);
        };
    }

    public static StatusAccent activityAccent(ActivityStatus status) {
        return switch (status.phase) {
            case READY -> StatusAccent.READY;
            case NO_MODEL, RETRYING, STOPPING -> StatusAccent.WARNING;
            case RUNNING_TOOL -> StatusAccent.TOOL;
            default -> StatusAccent.ACTIVE;
        };
    }

    private static String activityBusyLabel(ActivityStatus status, String description, long nowNanos) {
        long elapsedSeconds = activityElapsedNanos(status, nowNanos) / 1_000_000_000L;
        return ActivityStatus.SPINNER[(int) (elapsedSeconds % ActivityStatus.SPINNER.length)] + " " + description + " · "
                + formatActivityElapsed(status, nowNanos);
    }

    private static String formatActivityElapsed(ActivityStatus status, long nowNanos) {
        long seconds = activityElapsedNanos(status, nowNanos) / 1_000_000_000L;
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        long remainingSeconds = seconds % 60;
        if (minutes < 60) return minutes + "m" + String.format(Locale.ROOT, "%02ds", remainingSeconds);
        long hours = minutes / 60;
        return hours + "h" + String.format(Locale.ROOT, "%02dm", minutes % 60);
    }

    private static long activityElapsedNanos(ActivityStatus status, long nowNanos) {
        return Math.max(0, nowNanos - status.startedNanos);
    }

    // ------------------------------------------------------ agent instructions


    private boolean refreshAgentInstructionsIn(Path directory) {
        List<Path> nextSources = new ArrayList<>();
        List<String> promptParts = new ArrayList<>();
        if (!baseSystemPrompt.isBlank()) {
            promptParts.add(baseSystemPrompt);
        }
        List<Path> directories = new ArrayList<>();
        for (Path current = directory; current != null; current = current.getParent()) {
            directories.add(current);
            if (current.equals(repositoryRoot)) {
                Collections.reverse(directories);
                break;
            }
        }
        if (directories.isEmpty() || !directories.getFirst().equals(repositoryRoot)) {
            directories.clear();
        }
        for (Path scope : directories) {
            Path instructionFile;
            Path override = scope.resolve(AGENTS_OVERRIDE_FILE);
            if (isReadableRegularFile(override)) {
                instructionFile = override;
            } else {
                Path standard = scope.resolve(AGENTS_FILE);
                instructionFile = isReadableRegularFile(standard) ? standard : null;
            }
            if (instructionFile == null) continue;
            try {
                String content = Files.readString(instructionFile, StandardCharsets.UTF_8);
                nextSources.add(instructionFile);
                if (!content.isBlank()) promptParts.add(content);
            } catch (IOException | SecurityException ignored) {
                // Repository instructions are best-effort. An unreadable file must
                // not stop the agent from handling the user's task.
            }
        }

        String nextPrompt = String.join("\n\n", promptParts);
        List<Path> immutableSources = List.copyOf(nextSources);
        boolean changed = !Objects.equals(directory, currentDirectory)
                || !nextPrompt.equals(systemPrompt)
                || !immutableSources.equals(sources);
        currentDirectory = directory;
        systemPrompt = nextPrompt;
        sources = immutableSources;
        return changed;
    }

    private static boolean isReadableRegularFile(Path path) {
        try {
            return Files.isRegularFile(path);
        } catch (SecurityException ignored) {
            return false;
        }
    }

    private static Path instructionDirectory(Path path) {
        Path absolute = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        return Files.isDirectory(absolute) ? absolute : absolute.getParent();
    }

    // ------------------------------------------------------- interactive shell

    public static String forkName(String currentSessionName) {
        return currentSessionName == null || currentSessionName.isBlank()
                ? "fork"
                : currentSessionName.strip() + " fork";
    }

    /**
     * Rebuilds the visible transcript for a resumed session.
     */
    public String renderSessionScreen(
            Model model, List<Message> messages, boolean hideThinking, Theme theme) {
        StringBuilder screen = new StringBuilder(sessionScreenHeader(model));
        Map<String, ToolResultMessage> toolResults = new LinkedHashMap<>();
        for (Message message : messages) {
            if (message instanceof ToolResultMessage result) toolResults.put(result.toolCallId, result);
        }
        Set<String> renderedToolResults = new HashSet<>();
        for (Message message : messages) {
            switch (message) {
                case UserMessage user -> screen.append('\n')
                        .append(promptArea(theme, "> " + text(user)))
                        .append('\n');
                case AssistantMessage assistant -> {
                    for (AssistantContent content : assistant.content) {
                        if (content instanceof ThinkingContent thinking) {
                            if (!hideThinking && !thinking.thinking.isBlank()) {
                                screen.append("\n").append(theme.muted).append("Thinking:").append(theme.reset).append('\n');
                                screen.append(theme.muted).append(thinking.thinking).append(theme.reset).append('\n');
                            }
                        } else if (content instanceof TextContent text) {
                            screen.append(text.text).append('\n');
                        } else if (content instanceof ToolCall call) {
                            screen.append("\n[")
                                    .append(call.name)
                                    .append("] ")
                                    .append(toolCallDescription(call.name, call.arguments))
                                    .append('\n');
                            ToolResultMessage result = toolResults.get(call.id);
                            if (result != null) {
                                renderedToolResults.add(result.toolCallId);
                                appendSessionToolResult(screen, result);
                            }
                        }
                    }
                    if (assistant.errorMessage != null) {
                        screen.append("Error: ").append(assistant.errorMessage).append('\n');
                    }
                }
                case ToolResultMessage result -> {
                    if (renderedToolResults.add(result.toolCallId)) appendSessionToolResult(screen, result);
                }
            }
        }
        return screen.toString();
    }

    private static String sessionScreenHeader(Model model) {
        StringBuilder header = new StringBuilder("codingagent ").append(VERSION);
        if (model != null) header.append("  ").append(model);
        header.append('\n');
        header.append(model == null
                ? "Run /login to choose a provider. Commands: /help, /resume, /login, /mcp, /exit"
                : "Enter submits; Shift-Enter adds a newline; Esc interrupts. Ctrl-O inspects steps; Ctrl-T toggles thinking. Commands: /help, /fork, /resume, /models, /mcp, /settings, /compact, /logout, /theme <dark|light|plain>, /exit");
        header.append('\n');
        return header.toString();
    }

    private void appendSessionToolResult(StringBuilder screen, ToolResultMessage result) {
        screen.append("  ")
                .append(result.isError ? "Error" : "Done")
                .append(": ")
                .append(toolResultSummary(result.toolName, text(result), result.isError))
                .append('\n');
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

    private static String sessionDisplayName(SessionSnapshot session) {
        return session.name == null ? session.firstMessage : session.name;
    }

    /**
     * The text to print after a turn, or null when it was already streamed.
     */
    public static String finalAssistantOutput(AssistantMessage response, boolean emittedText) {
        if (response.errorMessage != null) return "Error: " + response.errorMessage;
        return emittedText ? null : text(response);
    }

    private void configureShellModel(Model model, boolean persistModel)
            throws IOException {
        Path configuredCwd = Path.of(".").toAbsolutePath().normalize();
        boolean recordingEnabled = false;
        if (!noSession) {
            try {
                defaultSessionStore();
                createSessionRecorder(configuredCwd, model.provider, model.id);
                recordingEnabled = true;
            } catch (IOException error) {
                println("Model configured, but session persistence is unavailable: " + error.getMessage());
            }
        }
        configureShellAgent(model, configuredCwd, recordingEnabled, null);
        if (persistModel) {
            settings = withSettingsDefaultModel(settings, model.provider, model.id);
            try {
                setSettingsDefaultModelAndProvider(model.provider, model.id);
            } catch (IOException error) {
                println("Model changed for this session, but could not be saved: " + error.getMessage());
            }
        }
    }

    private void configureShellAgent(Model model, Path configuredCwd, boolean recordingEnabled, String nextSessionName) {
        Provider provider = requireCoreProvider(model.provider);
        agentState(systemPrompt == null ? "" : systemPrompt, model);
        agent(provider);
        thinkingLevel =
                initialThinkingLevel(model, settings.defaultThinkingLevel);
        configureBuiltInTools(configuredCwd, systemPrompt);
        tools.addAll(mcpTools());
        subscribe(event -> {
            switch ((AgentEvent) event) {
                case AgentEvent.AgentStart ignored -> setShellActivity(activeActivity(ActivityStatus.Phase.WAITING_FOR_MODEL, System.nanoTime()));
                case AgentEvent.AgentEnd ignored -> setShellActivity(readyActivity(System.nanoTime()));
                case AgentEvent.InstructionLoaded loaded -> {
                    finishShellStreamOutput();
                    println(instructionLoadedMessage(loaded.path));
                }
                case AgentEvent.CompactionStart ignored -> setShellActivity(activeActivity(ActivityStatus.Phase.COMPACTING, System.nanoTime()));
                case AgentEvent.CompactionEnd end -> {
                    if (recordingSession) {
                        try {
                            appendSessionCompaction(end.result);
                        } catch (IOException error) {
                            // The live agent state has already been compacted. Keep the turn usable,
                            // but make the loss of the resume boundary visible to the user.
                            println("Warning: compacted context could not be saved for resume: " + error.getMessage());
                        }
                    }
                    if (agentConfigured && isStreaming) {
                        setShellActivity(activeActivity(ActivityStatus.Phase.WAITING_FOR_MODEL, System.nanoTime()));
                    } else {
                        setShellActivity(readyActivity(System.nanoTime()));
                    }
                    refreshShellStatus();
                }
                case AgentEvent.TurnStart ignored -> setShellActivity(activeActivity(ActivityStatus.Phase.WAITING_FOR_MODEL, System.nanoTime()));
                case AgentEvent.MessageUpdate update -> {
                    long now = System.nanoTime();
                    switch (update.providerEvent) {
                        case AssistantMessageEvent.ThinkingStart ignored1 -> setShellActivity(activeActivity(ActivityStatus.Phase.REASONING, now));
                        case AssistantMessageEvent.TextStart ignored1 -> setShellActivity(activeActivity(ActivityStatus.Phase.RESPONDING, now));
                        case AssistantMessageEvent.ToolCallStart start -> {
                            String result = "";
                            if (start.contentIndex >= 0
                                    && start.contentIndex < start.partial.content.size()
                                    && start.partial.content.get(start.contentIndex) instanceof ToolCall call) {
                                result = call.name;
                            }
                            setShellActivity(activeActivity(
                                    ActivityStatus.Phase.PREPARING_TOOL,
                                    result,
                                    now));
                        }
                        case AssistantMessageEvent.ToolCallEnd end1 -> setShellActivity(activeActivity(
                                ActivityStatus.Phase.PREPARING_TOOL, end1.toolCall.name, now));
                        default -> {
                            // End events retain the current phase until another block or AgentEnd.
                        }
                    }
                    Theme theme = terminalTheme();
                    switch (update.providerEvent) {
                        case AssistantMessageEvent.ThinkingStart ignored -> {
                            if (!this.hideThinkingBlock) {
                                finishShellStreamOutput();
                                print("\n" + theme.muted + "Thinking:" + theme.reset + "\n");
                                this.streamOutput = StreamOutput.THINKING;
                                this.streamedThinkingCharacters = 0;
                            }
                        }
                        case AssistantMessageEvent.ThinkingDelta delta -> {
                            if (!this.hideThinkingBlock) {
                                if (this.streamOutput != StreamOutput.THINKING) {
                                    print("\n" + theme.muted + "Thinking:" + theme.reset + "\n");
                                    this.streamOutput = StreamOutput.THINKING;
                                    this.streamedThinkingCharacters = 0;
                                }
                                print(theme.muted + delta.delta + theme.reset);
                                this.streamedThinkingCharacters += delta.delta.length();
                            }
                        }
                        case AssistantMessageEvent.ThinkingEnd end -> {
                            if (!this.hideThinkingBlock
                                    && this.streamOutput == StreamOutput.THINKING) {
                                if (this.streamedThinkingCharacters == 0 && !end.content.isBlank()) {
                                    print(theme.muted + end.content + theme.reset);
                                }
                                finishShellStreamOutput();
                            }
                        }
                        case AssistantMessageEvent.TextStart ignored -> {
                            finishShellStreamOutput();
                            this.streamOutput = StreamOutput.TEXT;
                        }
                        case AssistantMessageEvent.TextDelta delta -> {
                            if (this.streamOutput != StreamOutput.TEXT) {
                                finishShellStreamOutput();
                                this.streamOutput = StreamOutput.TEXT;
                            }
                            print(delta.delta);
                            this.emittedText = true;
                        }
                        case AssistantMessageEvent.TextEnd ignored -> finishShellStreamOutput();
                        default -> {
                            // Tool-call argument streaming is rendered once execution starts.
                        }
                    }
                }
                case AgentEvent.MessageEnd end -> {
                    if (end.message instanceof AssistantMessage) {
                        finishShellStreamOutput();
                        refreshShellStatus();
                    }
                }
                case AgentEvent.AutoRetryStart retry -> {
                    setShellActivity(retryingActivity(
                            retry.attempt, retry.maxAttempts, retry.delayMs, System.nanoTime()));
                    finishShellStreamOutput();
                    String result;
                    if (retry.delayMs < 1_000) {
                        result = retry.delayMs + "ms";
                    } else if (retry.delayMs % 1_000 == 0) {
                        result = retry.delayMs / 1_000 + "s";
                    } else {
                        result = String.format(Locale.ROOT, "%.1fs", retry.delayMs / 1_000.0);
                    }
                    println("\nTransient provider error; retrying in "
                            + result
                            + " (" + retry.attempt + "/" + retry.maxAttempts + "): "
                            + retry.errorMessage);
                }
                case AgentEvent.ToolExecutionStart start -> {
                    setShellActivity(activeActivity(
                            ActivityStatus.Phase.RUNNING_TOOL, start.toolName, System.nanoTime()));
                    finishShellStreamOutput();
                    println("\n[" + start.toolName + "] "
                            + toolCallDescription(start.toolName, start.arguments));
                }
                case AgentEvent.ToolExecutionEnd end -> {
                    String label = end.result.isError ? "Error" : "Done";
                    println("  " + label + ": " + toolResultSummary(end.toolName, end.result));
                }
                default -> {
                    // Turn-end and low-level update events do not change the presentation phase.
                }
            }
        });
        cwd = configuredCwd.toAbsolutePath().normalize();
        agentConfigured = true;
        recordingSession = recordingEnabled;
        sessionName = nextSessionName;
        if (activity.phase != ActivityStatus.Phase.RUNNING_COMMAND) {
            setShellActivity(readyActivity(System.nanoTime()));
        }
        refreshShellStatus();
    }

    /**
     * Updates cached workspace/model details, then redraws the live activity status.
     */
    private void refreshShellStatus() {
        String branch = gitBranch(cwd);
        statusLocation = displayPath(Path.of(System.getProperty("user.home", "")), cwd)
                + (branch == null ? "" : " [" + branch + "]")
                + (sessionName == null ? "" : " \u2022 " + sessionName);
        statusModel = !agentConfigured
                ? ""
                : modelStatus(
                selectedModel,
                thinkingLevel,
                contextTokens(messages));
        renderShellStatus();
    }

    private void renderShellStatus() {
        ActivityStatus current = activity;
        setStatus(activityLabel(current, System.nanoTime()), activityAccent(current), statusLocation, statusModel);
    }

    private void setShellActivity(ActivityStatus next) {
        boolean changed;
        synchronized (activityLock) {
            ActivityStatus current = activity;
            if (current.phase == ActivityStatus.Phase.STOPPING
                    && next.phase != ActivityStatus.Phase.READY
                    && next.phase != ActivityStatus.Phase.NO_MODEL) {
                return;
            }
            changed = !sameActivity(current, next);
            if (changed) activity = next;
        }
        if (changed) renderShellStatus();
    }

    /**
     * Formats the model segment, e.g. {@code GPT-5.6 Sol Max (0%)}.
     */
    public static String modelStatus(Model model, ThinkingLevel level, long contextTokens) {
        StringBuilder status = new StringBuilder(model.name);
        if (level != null && level != ThinkingLevel.OFF) {
            status.append(' ').append(switch (level) {
                case OFF -> "Off";
                case MINIMAL -> "Minimal";
                case LOW -> "Low";
                case MEDIUM -> "Medium";
                case HIGH -> "High";
                case XHIGH -> "XHigh";
                case MAX -> "Max";
            });
        }
        long percent = model.contextWindow > 0
                ? Math.max(0, Math.round(100.0 * contextTokens / model.contextWindow))
                : 0;
        return status.append(" (").append(percent).append("%)").toString();
    }

    /**
     * Context tokens consumed by the most recent successful assistant response.
     */
    public static long contextTokens(List<Message> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index) instanceof AssistantMessage assistant
                    && assistant.stopReason != StopReason.ERROR
                    && assistant.stopReason != StopReason.ABORTED) {
                Usage usage = assistant.usage;
                long total = usage.totalTokens > 0
                        ? usage.totalTokens
                        : usage.input + usage.output + usage.cacheRead + usage.cacheWrite;
                if (total > 0) return total;
            }
        }
        return 0;
    }

    /**
     * Abbreviates the home directory to {@code ~}, e.g. {@code ~/xa/coding-agent}.
     */
    public static String displayPath(Path home, Path cwd) {
        Path absolute = cwd.toAbsolutePath().normalize();
        if (home != null && !home.toString().isEmpty()) {
            Path absoluteHome = home.toAbsolutePath().normalize();
            if (absolute.startsWith(absoluteHome)) {
                String relative = absoluteHome.relativize(absolute).toString().replace('\\', '/');
                return relative.isEmpty() ? "~" : "~/" + relative;
            }
        }
        return absolute.toString();
    }

    /**
     * Reads the checked-out branch (or short detached commit) without spawning git.
     */
    public static String gitBranch(Path directory) {
        try {
            for (Path current = directory.toAbsolutePath().normalize(); current != null; current = current.getParent()) {
                Path gitPath = current.resolve(".git");
                if (Files.isRegularFile(gitPath)) {
                    // Worktree or submodule: .git is a file pointing at the real git dir.
                    String content = Files.readString(gitPath).trim();
                    if (!content.startsWith("gitdir:")) return null;
                    return readGitHead(current.resolve(content.substring("gitdir:".length()).trim()).normalize());
                }
                if (Files.isDirectory(gitPath)) return readGitHead(gitPath);
            }
        } catch (IOException | InvalidPathException ignored) {
            // A missing or unreadable repository simply hides the branch segment.
        }
        return null;
    }

    private static String readGitHead(Path gitDir) throws IOException {
        Path head = gitDir.resolve("HEAD");
        if (!Files.isRegularFile(head)) return null;
        String content = Files.readString(head).trim();
        if (content.startsWith("ref:")) {
            String ref = content.substring("ref:".length()).trim();
            return ref.startsWith("refs/heads/") ? ref.substring("refs/heads/".length()) : ref;
        }
        if (content.isEmpty()) return null;
        return content.length() > 7 ? content.substring(0, 7) : content;
    }

    private void syncShellMcpTools() {
        if (!agentConfigured || isStreaming) return;
        tools.removeIf(McpAgentTool.class::isInstance);
        tools.addAll(mcpTools());
    }

    private Model shellSavedModelIn(List<Model> models) {
        if (settings.defaultProvider == null || settings.defaultModel == null) return null;
        return findModelIn(models, settings.defaultProvider, settings.defaultModel);
    }

    /**
     * Clamps the configured (or default) thinking level to what the model supports.
     */
    public static ThinkingLevel initialThinkingLevel(Model model, ThinkingLevel configuredLevel) {
        return clampThinkingLevel(model, configuredLevel == null ? ThinkingLevel.MEDIUM : configuredLevel);
    }

    public static Model preferredCopilotModel(List<Model> models) {
        for (Model model : models) {
            if (model.id.equals("gpt-5.4")) {
                return model;
            }
        }
        return models.isEmpty() ? null : models.getFirst();
    }

    public static Model preferredChatGptModel(List<Model> models) {
        for (String preferred : List.of("gpt-5.6-terra", "gpt-5.6-sol", "gpt-5.4")) {
            for (Model model : models) {
                if (model.id.equals(preferred)) return model;
            }
        }
        return models.isEmpty() ? null : models.getFirst();
    }

    private static Model findModelIn(List<Model> models, String provider, String id) {
        for (Model model : models) {
            if (model.provider.equals(provider) && model.id.equals(id)) return model;
        }
        return null;
    }

    private static SelectItem<Model> shellModelItem(Model model) {
        String description = "[" + model.provider + "] " + model.name;
        return new SelectItem<>(
                model, model.id, description, model.provider + " " + model.id + " " + model.name);
    }

    private void showShellTurnDetails(boolean lineEditorActive) {
        if (!agentConfigured) {
            showShellShortcutStatus("No model is configured.", lineEditorActive);
            return;
        }
        TurnDetailsComponent details =
                turnDetailsForLatestTurn(messages, hideThinkingBlock);
        if (details == null) {
            showShellShortcutStatus("The latest turn has no reasoning or tool details.", lineEditorActive);
            return;
        }
        try {
            boolean hiddenAfter = runComponent(new TuiComponent<>(
                    frame -> renderTurnDetails(details, frame.width, frame.height, frame.theme),
                    input -> handleTurnDetailsInput(details, input),
                    () -> details.complete,
                    () -> details.thinkingHidden));
            if (hiddenAfter != hideThinkingBlock) {
                setShellHideThinkingBlock(hiddenAfter, lineEditorActive);
            }
        } catch (IOException error) {
            showShellShortcutStatus("Could not open turn details: " + error.getMessage(), lineEditorActive);
        }
    }

    private void setShellHideThinkingBlock(boolean hidden, boolean lineEditorActive) {
        hideThinkingBlock = hidden;
        settings = new Settings(
                settings.defaultProvider,
                settings.defaultModel,
                settings.defaultThinkingLevel,
                settings.theme,
                hidden);
        String status = "Thinking blocks: " + (hidden ? "hidden" : "visible");
        try {
            modifySettings(root -> root.put("hideThinkingBlock", hidden));
        } catch (IOException error) {
            status += " (could not save: " + error.getMessage() + ")";
        }
        showShellShortcutStatus(status, lineEditorActive);
    }

    private void showShellShortcutStatus(String status, boolean lineEditorActive) {
        if (lineEditorActive) printAbove(status);
        else println(status);
    }

    private void finishShellStreamOutput() {
        if (streamOutput != StreamOutput.NONE) {
            println("");
            streamOutput = StreamOutput.NONE;
        }
    }

    /**
     * One-line description of the work a tool call is about to perform.
     */
    public static String toolCallDescription(String toolName, ObjectNode arguments) {
        return switch (toolName) {
            case "read" -> {
                int offset = arguments.path("offset").asInt(1);
                int limit = arguments.path("limit").asInt();
                yield "Reading " + toolTextArgument(arguments, "path", ".")
                        + (limit > 0 ? " (lines " + offset + "-" + (offset + limit - 1) + ")" : " (from line " + offset + ")");
            }
            case "write" ->
                    "Writing " + toolTextArgument(arguments, "path", ".") + " (" + toolTextArgument(arguments, "content", "").length() + " characters)";
            case "edit" ->
                    "Editing " + toolTextArgument(arguments, "path", ".") + " (" + arguments.path("edits").size() + " replacement(s))";
            case "shell" -> abbreviateShellText(toolTextArgument(arguments, "command", ""), 240);
            case "grep" ->
                    "Searching for " + toolTextArgument(arguments, "pattern", "") + " in " + toolTextArgument(arguments, "path", ".");
            case "find" ->
                    "Finding " + toolTextArgument(arguments, "pattern", "") + " in " + toolTextArgument(arguments, "path", ".");
            case "ls" -> "Listing " + toolTextArgument(arguments, "path", ".");
            default -> abbreviateShellText(arguments.toString(), 240);
        };
    }

    /**
     * One-line summary of a completed tool result.
     */
    public static String toolResultSummary(String toolName, AgentTool.ToolResult result) {
        StringBuilder text = new StringBuilder();
        for (UserContent block : result.content) {
            if (block instanceof TextContent value) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(value.text);
            }
        }
        return toolResultSummary(toolName, text.toString(), result.isError);
    }

    private static String toolResultSummary(String toolName, String output, boolean error) {
        if (output.isBlank()) {
            return error ? "Tool failed without an error message." : "Completed.";
        }
        if (toolName.equals("read") && !error) {
            return "Read " + output.lines().count() + " line(s).";
        }
        return abbreviateShellText(output, error ? 480 : 320);
    }

    private static String toolTextArgument(ObjectNode arguments, String name, String fallback) {
        JsonNode value = arguments.get(name);
        return value != null && value.isTextual() ? value.asText() : fallback;
    }

    private static String abbreviateShellText(String value, int maximumLength) {
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maximumLength ? normalized : normalized.substring(0, maximumLength) + "...";
    }

    private static ObjectNode encodeAgentEvent(AgentEvent event, boolean rpc) {
        ObjectNode node = jsonObject();
        switch (event) {
            case AgentEvent.AgentStart ignored -> node.put("type", "agent_start");
            case AgentEvent.AgentEnd end -> {
                node.put("type", rpc ? "agent_settled" : "agent_end");
                node.put("messageCount", end.newMessages.size());
            }
            case AgentEvent.InstructionLoaded loaded -> {
                node.put("type", "instruction_loaded");
                node.put("path", loaded.path.toString());
            }
            case AgentEvent.CompactionStart start -> {
                node.put("type", "compaction_start");
                node.put("tokensBefore", start.tokensBefore);
            }
            case AgentEvent.CompactionEnd end -> {
                node.put("type", "compaction_end");
                node.put("tokensBefore", end.result.tokensBefore);
                node.put("estimatedTokensAfter", end.result.estimatedTokensAfter);
            }
            case AgentEvent.TurnStart ignored -> node.put("type", "turn_start");
            case AgentEvent.TurnEnd end -> {
                node.put("type", "turn_end");
                node.put("toolResultCount", end.toolResults.size());
            }
            case AgentEvent.AutoRetryStart retry -> {
                node.put("type", "auto_retry_start");
                node.put("attempt", retry.attempt);
                node.put("maxAttempts", retry.maxAttempts);
                node.put("delayMs", retry.delayMs);
                node.put("error", retry.errorMessage);
            }
            case AgentEvent.AutoRetryEnd retry -> {
                node.put("type", "auto_retry_end");
                node.put("success", retry.success);
                node.put("attempt", retry.attempt);
                if (retry.finalError != null) node.put("error", retry.finalError);
            }
            case AgentEvent.MessageStart start -> {
                node.put("type", "message_start");
                node.put("role", role(start.message));
            }
            case AgentEvent.MessageEnd end -> {
                node.put("type", "message_end");
                node.put("role", role(end.message));
                if (end.message instanceof AssistantMessage assistant) node.put("text", text(assistant));
            }
            case AgentEvent.MessageUpdate update -> {
                if (!(update.providerEvent instanceof AssistantMessageEvent.TextDelta delta)) return null;
                node.put("type", rpc ? "message_update" : "text_delta");
                if (rpc) node.putObject("assistantMessageEvent").put("type", "text_delta").put("delta", delta.delta);
                else node.put("delta", delta.delta);
            }
            case AgentEvent.ToolExecutionStart start -> {
                node.put("type", rpc ? "tool_execution_start" : "tool_start");
                node.put("toolCallId", start.toolCallId);
                node.put(rpc ? "toolName" : "tool", start.toolName);
                node.set("arguments", start.arguments);
            }
            case AgentEvent.ToolExecutionUpdate ignored -> {
                return null;
            }
            case AgentEvent.ToolExecutionEnd end -> {
                node.put("type", rpc ? "tool_execution_end" : "tool_end");
                node.put("toolCallId", end.toolCallId);
                node.put(rpc ? "toolName" : "tool", end.toolName);
                node.put("isError", end.result.isError);
            }
        }
        return node;
    }

    // ------------------------------------------------------------- rpc server

    private void resetRpcAgent(Model model) throws IOException {
        requireCoreProvider(model.provider);
        agentState(systemPrompt == null ? "" : systemPrompt, model);
        agent(this);
        configureBuiltInTools(Path.of("."), systemPrompt);
        tools.addAll(mcpTools());
        subscribe(event -> {
            if (event instanceof AgentEvent.CompactionEnd end) {
                if (!noSession) {
                    try {
                        appendSessionCompaction(end.result);
                    } catch (IOException error) {
                        // Never contaminate the JSONL protocol on stdout. The live state remains
                        // valid even if its append-only resume marker could not be written.
                        System.err.println("Warning: compacted context could not be saved for resume: " + error.getMessage());
                    }
                }
            }
            ObjectNode node = encodeAgentEvent(event, true);
            if (node != null) outputRpc(node);
        });
        if (!noSession) {
            defaultSessionStore();
            createSessionRecorder(Path.of("."), model.provider, model.id);
        }
    }

    private void respondRpc(
            String id, String command, boolean success, JsonNode data, String error) {
        ObjectNode response = jsonObject();
        response.put("type", "response");
        if (id != null) response.put("id", id);
        response.put("command", command);
        response.put("success", success);
        if (success && data != null) response.set("data", data);
        if (!success) response.put("error", error);
        outputRpc(response);
    }

    private static String requiredRpcText(ObjectNode command, String field) {
        JsonNode value = command.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-empty string");
        }
        return value.asText();
    }

    private static synchronized void outputRpc(ObjectNode node) {
        System.out.println(node);
        System.out.flush();
    }

    // ----------------------------------------------------------- mcp selector

    private static int mcpSelectorItemCount(McpSelector selector) {
        return selector.view == McpSelector.View.SERVERS
                ? selector.filtered.size()
                : selector.filteredTools.size();
    }

    private void notifyMcpSelectorChange(McpSelector selector, McpSelector.Change change) {
        try {
            selector.onChange.accept(change);
            selector.changeError = null;
        } catch (UncheckedIOException error) {
            IOException cause = error.getCause();
            String message = cause == null ? error.getMessage() : cause.getMessage();
            String detail = message == null || message.isBlank()
                    ? String.valueOf(cause == null ? error : cause)
                    : message;
            selector.changeError =
                    "Change applied, but not saved: " + detail.replaceAll("\\s+", " ").trim();
        }
    }

    private void closeMcpSelectorTools(McpSelector selector) {
        if (selector.view != McpSelector.View.TOOLS) return;
        String server = selector.toolServer;
        selector.view = McpSelector.View.SERVERS;
        selector.toolServer = null;
        clearMcpSelectorQuery(selector);
        filterMcpSelector(selector);
        int index = selector.filtered.indexOf(server);
        if (index >= 0) selector.selectedIndex = index;
    }

    private void moveMcpSelector(McpSelector selector, int delta) {
        int itemCount = mcpSelectorItemCount(selector);
        if (itemCount > 0) selector.selectedIndex = Math.floorMod(selector.selectedIndex + delta, itemCount);
    }

    private void clearMcpSelectorQuery(McpSelector selector) {
        selector.query.setLength(0);
        selector.queryCursor = 0;
    }

    private void filterMcpSelector(McpSelector selector) {
        if (selector.view == McpSelector.View.SERVERS) {
            selector.filtered = fuzzyFilter(selector.names, selector.query.toString(), name -> {
                McpServerStatus status = selector.manager.mcpStatus(name);
                return name + " " + status.state + " " + status.target;
            });
        } else {
            refreshMcpSelectorTools(selector);
        }
        selector.selectedIndex = 0;
    }

    private void refreshMcpSelectorTools(McpSelector selector) {
        if (selector.toolServer == null) {
            selector.filteredTools = List.of();
            return;
        }
        List<McpToolStatus> tools;
        McpRuntime runtime = selector.manager.mcpRequireRuntime(selector.toolServer);
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
        selector.filteredTools = fuzzyFilter(
                tools,
                selector.query.toString(),
                tool -> tool.name + " " + tool.description + " " + (tool.enabled ? "enabled" : "disabled"));
        if (selector.selectedIndex >= selector.filteredTools.size()) {
            selector.selectedIndex = Math.max(0, selector.filteredTools.size() - 1);
        }
    }

    // ------------------------------------------------------------ turn details

    /**
     * Builds the inspector for the reasoning and tool steps that follow the
     * latest user message, or null when that turn has none.
     */
    public static TurnDetailsComponent turnDetailsForLatestTurn(
            List<Message> messages, boolean thinkingHidden) {
        int start = 0;
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index) instanceof UserMessage) {
                start = index + 1;
                break;
            }
        }

        Map<String, ToolResultMessage> results = new LinkedHashMap<>();
        for (int index = start; index < messages.size(); index++) {
            if (messages.get(index) instanceof ToolResultMessage result) {
                results.put(result.toolCallId, result);
            }
        }

        List<TurnDetailsComponent.Section> sections = new ArrayList<>();
        int thinkingNumber = 0;
        for (int index = start; index < messages.size(); index++) {
            if (!(messages.get(index) instanceof AssistantMessage assistant)) continue;
            for (AssistantContent content : assistant.content) {
                if (content instanceof ThinkingContent thinking && !thinking.thinking.isBlank()) {
                    thinkingNumber++;
                    String body = turnDetailsSafePlain(thinking.thinking.strip());
                    String result = "";
                    if (!body.isBlank()) {
                        String line = body.lines().filter(value -> !value.isBlank()).findFirst().orElse("").strip();
                        result = line.length() <= 100 ? line : line.substring(0, 100) + "...";
                    }
                    sections.add(new TurnDetailsComponent.Section(
                            TurnDetailsComponent.Kind.THINKING,
                            "Thinking " + thinkingNumber,
                            body,
                            result,
                            !thinkingHidden));
                } else if (content instanceof ToolCall call) {
                    ToolResultMessage result = results.get(call.id);
                    String description =
                            turnDetailsSafePlain(toolCallDescription(call.name, call.arguments)).replaceAll("\\s+", " ").strip();
                    String title = call.name + (description.isBlank() ? "" : "  " + description);
                    StringBuilder body = new StringBuilder("Arguments\n").append(call.arguments.toPrettyString());
                    String summary;
                    if (result == null) {
                        summary = "pending";
                    } else {
                        if (result.isError) {
                            summary = "error";
                        } else {
                            String result1 = "done";
                            String text = text(result);
                            if (!text.isBlank()) {
                                long lines = text.lines().count();
                                result1 = lines == 1 ? "done" : lines + " lines";
                            }
                            summary = result1;
                        }
                    }
                    body.append("\n\nResult");
                    if (result != null && result.isError) body.append(" (error)");
                    body.append('\n').append(result == null ? "Pending" : text(result));
                    sections.add(new TurnDetailsComponent.Section(
                            TurnDetailsComponent.Kind.TOOL,
                            title,
                            turnDetailsSafePlain(body.toString()),
                            summary,
                            false));
                }
            }
        }
        if (sections.isEmpty()) return null;
        TurnDetailsComponent details = new TurnDetailsComponent();
        details.sections = sections;
        details.thinkingHidden = thinkingHidden;
        return details;
    }

    public static List<String> renderTurnDetails(
            TurnDetailsComponent details, int width, int height, Theme theme) {
        int safeWidth = Math.max(20, width);
        details.viewportHeight = Math.max(
                1, height - TurnDetailsComponent.HEADER_LINES - TurnDetailsComponent.FOOTER_LINES);
        List<TurnDetailsComponent.RenderedLine> lines1 = new ArrayList<>();
        for (int index1 = 0; index1 < details.sections.size(); index1++) {
            TurnDetailsComponent.Section section = details.sections.get(index1);
            String marker = section.expanded ? "▼ " : "▶ ";
            String suffix = section.expanded || section.summary.isBlank() ? "" : " — " + section.summary;
            String heading = truncatePlain(marker + section.title + suffix, safeWidth);
            if (index1 == details.selectedIndex) heading = theme.heading + heading + theme.reset;
            else heading = theme.strong + heading + theme.reset;
            lines1.add(new TurnDetailsComponent.RenderedLine(heading, index1, true));
            if (section.expanded) {
                List<String> result;
                int maximumWidth = Math.max(1, safeWidth - 3);
                String normalized = section.body.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ");
                List<String> lines = new ArrayList<>();
                for (String sourceLine : normalized.split("\n", -1)) {
                    if (sourceLine.isEmpty()) {
                        lines.add("");
                        continue;
                    }
                    StringBuilder line = new StringBuilder();
                    int width1 = 0;
                    for (int index = 0; index < sourceLine.length(); ) {
                        int codePoint = sourceLine.codePointAt(index);
                        int codePointWidth = Math.max(0, WCWidth.wcwidth(codePoint));
                        if (!line.isEmpty() && width1 + codePointWidth > maximumWidth) {
                            lines.add(line.toString());
                            line.setLength(0);
                            width1 = 0;
                        }
                        line.appendCodePoint(codePoint);
                        width1 += codePointWidth;
                        index += Character.charCount(codePoint);
                    }
                    if (!line.isEmpty()) {
                        lines.add(line.toString());
                    }
                }
                result = List.copyOf(lines);
                for (String bodyLine : result) {
                    String rendered = "   " + bodyLine;
                    if (section.kind == TurnDetailsComponent.Kind.THINKING) {
                        rendered = theme.muted + rendered + theme.reset;
                    }
                    lines1.add(new TurnDetailsComponent.RenderedLine(rendered, index1, false));
                }
            }
            if (index1 + 1 < details.sections.size()) {
                lines1.add(new TurnDetailsComponent.RenderedLine("", index1, false));
            }
        }
        int selectedRow = 0;
        for (int index1 = 0; index1 < lines1.size(); index1++) {
            TurnDetailsComponent.RenderedLine line1 = lines1.get(index1);
            if (line1.heading && line1.sectionIndex == details.selectedIndex) {
                selectedRow = index1;
                break;
            }
        }
        if (details.keepSelectionVisible) {
            if (selectedRow < details.scrollTop) {
                details.scrollTop = selectedRow;
            } else if (selectedRow >= details.scrollTop + details.viewportHeight) {
                details.scrollTop = selectedRow - details.viewportHeight + 1;
            }
        }
        details.scrollTop = Math.max(
                0, Math.clamp(lines1.size() - details.viewportHeight, 0, details.scrollTop));

        List<String> lines = new ArrayList<>();
        lines.add(theme.heading + "Turn details" + theme.reset);
        lines.add("");
        details.visibleSectionsByRow.clear();
        int visibleEnd = Math.min(lines1.size(), details.scrollTop + details.viewportHeight);
        for (int index = details.scrollTop; index < visibleEnd; index++) {
            TurnDetailsComponent.RenderedLine line = lines1.get(index);
            if (line.heading) details.visibleSectionsByRow.put(lines.size(), line.sectionIndex);
            lines.add(line.text);
        }
        String hint = "Up/Down select  Enter expand/collapse  PgUp/PgDn scroll  Ctrl-T thinking  Ctrl-O tools  Esc close";
        lines.add(theme.muted + truncatePlain(hint, safeWidth) + theme.reset);
        return lines;
    }

    public void handleTurnDetailsInput(TurnDetailsComponent details, TuiInput input) {
        switch (input) {
            case TuiInput.Key key -> {
                switch (key.type) {
                    case UP -> moveTurnDetails(details, -1);
                    case DOWN -> moveTurnDetails(details, 1);
                    case PAGE_UP -> scrollTurnDetails(details, -Math.max(1, details.viewportHeight - 1));
                    case PAGE_DOWN -> scrollTurnDetails(details, Math.max(1, details.viewportHeight - 1));
                    case HOME -> {
                        details.selectedIndex = 0;
                        details.keepSelectionVisible = true;
                    }
                    case END -> {
                        details.selectedIndex = details.sections.size() - 1;
                        details.keepSelectionVisible = true;
                    }
                    case ENTER -> toggleSelectedTurnDetail(details);
                    case TOGGLE_THINKING -> toggleTurnDetailsKind(details, TurnDetailsComponent.Kind.THINKING);
                    case EXPAND_TOOLS -> toggleTurnDetailsKind(details, TurnDetailsComponent.Kind.TOOL);
                    case ESCAPE, CANCEL, EXIT -> details.complete = true;
                    case CHARACTER -> {
                        if (key.text.equals(" ")) toggleSelectedTurnDetail(details);
                        else if (key.text.equalsIgnoreCase("q")) details.complete = true;
                    }
                    default -> {
                        // Other keys do not affect the inspector.
                    }
                }
            }
            case TuiInput.Mouse mouse -> {
                switch (mouse.action) {
                    case SCROLL_UP -> scrollTurnDetails(details, -3);
                    case SCROLL_DOWN -> scrollTurnDetails(details, 3);
                    case PRESS -> {
                        if (mouse.button != 0) {
                            break;
                        }
                        Integer section = details.visibleSectionsByRow.get(mouse.y - 1);
                        if (section != null) {
                            details.selectedIndex = section;
                            toggleSelectedTurnDetail(details);
                        }
                    }
                    default -> {
                        // Release and drag do not affect expansion.
                    }
                }
            }
            case TuiInput.Resize ignored -> {
                // Rendering uses the current dimensions directly.
            }
        }
    }

    private void moveTurnDetails(TurnDetailsComponent details, int delta) {
        details.selectedIndex = Math.floorMod(details.selectedIndex + delta, details.sections.size());
        details.keepSelectionVisible = true;
    }

    private void scrollTurnDetails(TurnDetailsComponent details, int delta) {
        details.scrollTop = Math.max(0, details.scrollTop + delta);
        details.keepSelectionVisible = false;
    }

    private void toggleSelectedTurnDetail(TurnDetailsComponent details) {
        TurnDetailsComponent.Section section = details.sections.get(details.selectedIndex);
        section.expanded = !section.expanded;
        details.keepSelectionVisible = true;
    }

    private void toggleTurnDetailsKind(
            TurnDetailsComponent details, TurnDetailsComponent.Kind kind) {
        if (details.sections.stream().noneMatch(section -> section.kind == kind)) return;
        boolean expand =
                details.sections.stream().anyMatch(section -> section.kind == kind && !section.expanded);
        for (TurnDetailsComponent.Section section : details.sections) {
            if (section.kind == kind) section.expanded = expand;
        }
        if (kind == TurnDetailsComponent.Kind.THINKING) details.thinkingHidden = !expand;
        details.keepSelectionVisible = true;
    }

    private static String turnDetailsSafePlain(String value) {
        String stripped = stripAnsi(value == null ? "" : value);
        StringBuilder safe = new StringBuilder(stripped.length());
        for (int index = 0; index < stripped.length(); ) {
            int codePoint = stripped.codePointAt(index);
            if (codePoint == '\n' || codePoint == '\t' || (!Character.isISOControl(codePoint) && codePoint != 0x1b)) {
                safe.appendCodePoint(codePoint);
            }
            index += Character.charCount(codePoint);
        }
        return safe.toString();
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
        this.lockPath = lockPath;
    }

    public void createSessionRecorder(Path cwd, String provider, String model) throws IOException {
        createSessionRecorder(cwd, provider, model, null);
    }

    /**
     * Creates a session with an optional user-visible name.
     */
    private void createSessionRecorder(Path cwd, String provider, String model, String sessionName) throws IOException {
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
        String normalizedName = sessionName == null ? null : sessionName.strip();
        if (normalizedName != null && !normalizedName.isEmpty()) start.put("name", normalizedName);
        appendSessionEntry(id1, "session_start", start);
        sessionRecorder(id1);
    }

    /**
     * Creates a named child session containing a copy of the supplied conversation.
     */
    public void forkSessionRecorder(Path cwd, String provider, String model, String sessionName, List<Message> messages)
            throws IOException {
        createSessionRecorder(cwd, provider, model, sessionName);
        appendSessionMessages(messages);
    }

    /**
     * Opens an existing session so future messages continue in the same JSONL file.
     */
    public void resumeSessionRecorder(String sessionId) throws IOException {
        sessionSnapshot(sessionId);
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

    private void defaultSessionStore() {
        Path home = Path.of(System.getProperty("user.home"));
        sessionStore(
                home.resolve(".codingagent").resolve("sessions"),
                List.of(home.resolve(".pi-java").resolve("sessions")));
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
        List<Message> transcriptMessages = new ArrayList<>();
        List<Message> messages = new ArrayList<>();
        String firstMessage = "";
        StringBuilder allMessages = new StringBuilder();
        long modified = start.timestamp;
        for (SessionEntry entry : entries) {
            modified = Math.max(modified, entry.timestamp);
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
        return new SessionSnapshot(
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

    private static Settings withSettingsDefaultModel(
            Settings settings, String provider, String model) {
        return new Settings(
                provider, model, settings.defaultThinkingLevel, settings.theme, settings.hideThinkingBlock);
    }

    private void setSettingsDefaultModelAndProvider(String provider, String model)
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
        Path parent = settingsPath.getParent();
        if (parent == null) {
            throw new IOException("Settings path has no parent directory: " + settingsPath);
        }
        Files.createDirectories(parent);
        setPosixPermissions(parent, DIRECTORY_PERMISSIONS);
        try (FileChannel channel =
                     FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            setPosixPermissions(lockPath, FILE_PERMISSIONS);
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
