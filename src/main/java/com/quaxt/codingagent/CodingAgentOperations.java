package com.quaxt.codingagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
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
import java.util.function.Predicate;
import java.util.function.Supplier;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicBoolean;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.quaxt.codingagent.agent.Agent;
import com.quaxt.codingagent.agent.AgentEvent;
import com.quaxt.codingagent.agent.AgentState;
import com.quaxt.codingagent.agent.AgentTool;
import com.quaxt.codingagent.agent.CompactionResult;
import com.quaxt.codingagent.agent.FunctionTool;
import com.quaxt.codingagent.agent.ToolInvocation;
import com.quaxt.codingagent.ai.CoreProviders;
import com.quaxt.codingagent.ai.ModelCatalog;
import com.quaxt.codingagent.ai.Models;
import com.quaxt.codingagent.ai.Provider;
import com.quaxt.codingagent.ai.Retry;
import com.quaxt.codingagent.ai.StreamOptions;
import com.quaxt.codingagent.ai.auth.ChatGptAuth;
import com.quaxt.codingagent.ai.auth.Credential;
import com.quaxt.codingagent.ai.auth.CredentialStore;
import com.quaxt.codingagent.ai.auth.EnvApiKeys;
import com.quaxt.codingagent.ai.auth.FileCredentialStore;
import com.quaxt.codingagent.ai.auth.GitHubCopilotAuth;
import com.quaxt.codingagent.ai.http.HttpException;
import com.quaxt.codingagent.ai.http.HttpTransport;
import com.quaxt.codingagent.ai.http.SseReader;
import com.quaxt.codingagent.ai.json.Json;
import com.quaxt.codingagent.ai.providers.AnthropicProvider;
import com.quaxt.codingagent.ai.providers.ChatGptProvider;
import com.quaxt.codingagent.ai.providers.FauxProvider;
import com.quaxt.codingagent.ai.providers.GitHubCopilotProvider;
import com.quaxt.codingagent.ai.providers.GoogleProvider;
import com.quaxt.codingagent.ai.providers.OpenAiCompatibleProvider;
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
import com.quaxt.codingagent.ai.util.Uuid;
import com.quaxt.codingagent.cli.ActivityStatus;
import com.quaxt.codingagent.cli.AgentInstructions;
import com.quaxt.codingagent.cli.Cli;
import com.quaxt.codingagent.cli.InteractiveShell;
import com.quaxt.codingagent.cli.McpSelector;
import com.quaxt.codingagent.cli.RpcServer;
import com.quaxt.codingagent.cli.TurnDetailsComponent;
import com.quaxt.codingagent.cli.session.SessionRecorder;
import com.quaxt.codingagent.cli.session.SessionSnapshot;
import com.quaxt.codingagent.cli.session.SessionStore;
import com.quaxt.codingagent.cli.settings.SettingsStore;
import com.quaxt.codingagent.cli.tools.BuiltInTools;
import com.quaxt.codingagent.cli.tools.GitIgnore;
import com.quaxt.codingagent.cli.tools.LocalTool;
import com.quaxt.codingagent.mcp.McpAgentTool;
import com.quaxt.codingagent.mcp.McpClient;
import com.quaxt.codingagent.mcp.McpResultFilter;
import com.quaxt.codingagent.mcp.McpConfigLoader;
import com.quaxt.codingagent.mcp.McpConfiguration;
import com.quaxt.codingagent.mcp.McpHttpException;
import com.quaxt.codingagent.mcp.McpManager;
import com.quaxt.codingagent.mcp.McpOAuthCallback;
import com.quaxt.codingagent.mcp.McpOAuthClient;
import com.quaxt.codingagent.mcp.McpOAuthRequiredException;
import com.quaxt.codingagent.mcp.McpOAuthStore;
import com.quaxt.codingagent.mcp.McpServerConfig;
import com.quaxt.codingagent.mcp.McpTransport;
import com.quaxt.codingagent.mcp.SseHttpMcpTransport;
import com.quaxt.codingagent.mcp.StdioMcpTransport;
import com.quaxt.codingagent.mcp.StreamableHttpMcpTransport;
import com.quaxt.codingagent.tui.AnsiRenderer;
import com.quaxt.codingagent.tui.CommandSuggestions;
import com.quaxt.codingagent.tui.FuzzyMatcher;
import com.quaxt.codingagent.tui.FuzzySelector;
import com.quaxt.codingagent.tui.InteractiveTerminal;
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
import org.jline.reader.Widget;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.terminal.impl.jni.JniTerminalProvider;
import org.jline.terminal.impl.jni.win.ShiftAwareNativeWinSysTerminal;
import org.jline.terminal.spi.SystemStream;
import org.jline.terminal.spi.TerminalProvider;
import org.jline.utils.AttributedString;
import org.jline.utils.InfoCmp.Capability;
import org.jline.utils.NonBlockingReader;
import org.jline.utils.Status;
import org.jline.utils.WCWidth;

/**
 * Single home for project-owned behavior. Every operation is static and takes
 * the data carrier it acts on; the class holds no state of its own.
 */
public final class CodingAgentOperations extends JniTerminalProvider {
	/** Required by JLine's reflective JNI provider loading. */
	public CodingAgentOperations() {}

	private static final CancellationException SUSPEND_REQUESTED =
			new CancellationException("Interactive terminal suspend requested");

	/**
	 * JLine's named provider SPI requires an instance override. The terminal
	 * construction remains in the static operation below.
	 */
	@Override
	public Terminal winSysTerminal(
			String name,
			String type,
			boolean ansiPassThrough,
			Charset encoding,
			Charset stdinEncoding,
			Charset stdoutEncoding,
			Charset stderrEncoding,
			boolean nativeSignals,
			Terminal.SignalHandler signalHandler,
			boolean paused,
			SystemStream systemStream)
			throws IOException {
		return shiftAwareWinSysTerminal(
				this,
				name,
				type,
				ansiPassThrough,
				encoding,
				stdinEncoding,
				stdoutEncoding,
				stderrEncoding,
				nativeSignals,
				signalHandler,
				paused,
				systemStream);
	}

	// ---------------------------------------------------------------- json

	/** Creates an empty mutable JSON object node on the shared mapper. */
	public static ObjectNode jsonObject() {
		return Json.MAPPER.createObjectNode();
	}

	// ---------------------------------------------------------------- uuid

	/** Generates a time-ordered UUIDv7 string. */
	public static String uuidv7() {
		byte[] random = new byte[16];
		Uuid.RANDOM.nextBytes(random);
		long timestampMs;
		long seq;
		synchronized (Uuid.class) {
			long now = System.currentTimeMillis();
			if (now > Uuid.lastTimestamp) {
				Uuid.sequence = ((random[6] & 0xFFL) << 24)
						| ((random[7] & 0xFFL) << 16)
						| ((random[8] & 0xFFL) << 8)
						| (random[9] & 0xFFL);
				Uuid.lastTimestamp = now;
			} else {
				Uuid.sequence = (Uuid.sequence + 1) & 0xFFFFFFFFL;
				if (Uuid.sequence == 0) {
					Uuid.lastTimestamp++;
				}
			}
			timestampMs = Uuid.lastTimestamp;
			seq = Uuid.sequence;
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

		StringBuilder sb = new StringBuilder(36);
		for (int i = 0; i < 16; i++) {
			if (i == 4 || i == 6 || i == 8 || i == 10) {
				sb.append('-');
			}
			sb.append(Character.forDigit((bytes[i] >> 4) & 0xF, 16));
			sb.append(Character.forDigit(bytes[i] & 0xF, 16));
		}
		return sb.toString();
	}

	// --------------------------------------------------------- abort signal

	/** Marks the signal aborted and runs (once) every registered listener. */
	public static void abort(AbortSignal signal) {
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

	public static boolean isAborted(AbortSignal signal) {
		synchronized (signal) {
			return signal.aborted;
		}
	}

	/** Registers a listener, invoking it immediately if already aborted. */
	public static void onAbort(AbortSignal signal, Runnable listener) {
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

	/** Pushes an event. Ignored after the stream is done. */
	public static <T, R> void push(EventStream<T, R> stream, T event) {
		synchronized (stream) {
			if (stream.done) {
				return;
			}
			if (isTerminalEvent(event)) {
				stream.done = true;
				stream.finalResult.complete(terminalResult(event));
			}
			stream.queue.addLast(event);
			stream.notifyAll();
		}
	}

	/** Terminates the stream without a terminal event, optionally supplying the result. */
	public static <T, R> void end(EventStream<T, R> stream, R result) {
		synchronized (stream) {
			stream.done = true;
			if (result != null) {
				stream.finalResult.complete(result);
			}
			stream.notifyAll();
		}
	}

	public static <T, R> void end(EventStream<T, R> stream) {
		end(stream, null);
	}

	/** Blocks until the terminal event arrives and returns the extracted result. */
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

	/** Blocking for-each view over the stream's events. */
	public static <T, R> Iterable<T> events(EventStream<T, R> stream) {
		return () -> iterator(stream);
	}

	private static boolean isTerminalEvent(Object event) {
		return event instanceof AssistantMessageEvent.Done || event instanceof AssistantMessageEvent.Error;
	}

	@SuppressWarnings("unchecked")
	private static <R> R terminalResult(Object event) {
		return switch (event) {
			case AssistantMessageEvent.Done done -> (R) done.message;
			case AssistantMessageEvent.Error error -> (R) error.error;
			default -> throw new IllegalStateException("Unexpected event type for final result");
		};
	}

	// -------------------------------------------------------------- messages

	public static String role(Message message) {
		return switch (message) {
			case UserMessage ignored -> "user";
			case AssistantMessage ignored -> "assistant";
			case ToolResultMessage ignored -> "toolResult";
		};
	}

	/** Unix timestamp in milliseconds. */
	public static long timestamp(Message message) {
		return switch (message) {
			case UserMessage user -> user.timestamp;
			case AssistantMessage assistant -> assistant.timestamp;
			case ToolResultMessage result -> result.timestamp;
		};
	}

	/** Concatenated text of all text blocks. */
	public static String text(AssistantMessage message) {
		StringBuilder sb = new StringBuilder();
		for (AssistantContent block : message.content) {
			if (block instanceof TextContent textContent) {
				sb.append(textContent.text);
			}
		}
		return sb.toString();
	}

	/** Concatenated text of all thinking blocks. */
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

	/** Concatenated text of all text blocks. */
	public static String text(UserMessage message) {
		return contentText(message.content);
	}

	/** Concatenated text of all text blocks. */
	public static String text(ToolResultMessage message) {
		return contentText(message.content);
	}

	public static UserMessage userMessage(String text) {
		return userMessage(List.of(textContent(text)), System.currentTimeMillis());
	}

	public static UserMessage userMessage(List<UserContent> content) {
		return userMessage(content, System.currentTimeMillis());
	}

	/** Creates a user message with an unmodifiable copy of the supplied content. */
	public static UserMessage userMessage(List<UserContent> content, long timestamp) {
		return new UserMessage(List.copyOf(content), timestamp);
	}

	public static ToolResultMessage toolResultMessage(
			String toolCallId, String toolName, String text, boolean isError) {
		return toolResultMessage(
				toolCallId, toolName, List.of(textContent(text)), null, isError, System.currentTimeMillis());
	}

	/** Creates a tool result message with an unmodifiable copy of the supplied content. */
	public static ToolResultMessage toolResultMessage(
			String toolCallId,
			String toolName,
			List<UserContent> content,
			Object details,
			boolean isError,
			long timestamp) {
		return new ToolResultMessage(toolCallId, toolName, List.copyOf(content), details, isError, timestamp);
	}

	private static String contentText(List<UserContent> content) {
		StringBuilder sb = new StringBuilder();
		for (UserContent block : content) {
			if (block instanceof TextContent textContent) {
				sb.append(textContent.text);
			}
		}
		return sb.toString();
	}

	// --------------------------------------------------------- content blocks

	public static TextContent textContent(String text) {
		return textContent(text, null);
	}

	/** Creates a text block; the text itself is required. */
	public static TextContent textContent(String text, String textSignature) {
		if (text == null) {
			throw new IllegalArgumentException("text must not be null");
		}
		return new TextContent(text, textSignature);
	}

	public static ThinkingContent thinkingContent(String thinking) {
		return thinkingContent(thinking, null, false);
	}

	/** Creates a thinking block; the thinking text itself is required. */
	public static ThinkingContent thinkingContent(String thinking, String thinkingSignature, boolean redacted) {
		if (thinking == null) {
			throw new IllegalArgumentException("thinking must not be null");
		}
		return new ThinkingContent(thinking, thinkingSignature, redacted);
	}

	/** Creates an image block; both the payload and its mime type are required. */
	public static ImageContent imageContent(String data, String mimeType) {
		if (data == null || mimeType == null) {
			throw new IllegalArgumentException("data and mimeType must not be null");
		}
		return new ImageContent(data, mimeType);
	}

	public static ToolCall toolCall(String id, String name, ObjectNode arguments) {
		return toolCall(id, name, arguments, null);
	}

	/** Creates a tool call; only the Google-specific thought signature is optional. */
	public static ToolCall toolCall(String id, String name, ObjectNode arguments, String thoughtSignature) {
		if (id == null || name == null || arguments == null) {
			throw new IllegalArgumentException("id, name, and arguments must not be null");
		}
		return new ToolCall(id, name, arguments, thoughtSignature);
	}

	/** Creates a model-visible tool definition; every field is required. */
	public static Tool toolDefinition(String name, String description, ObjectNode parameters) {
		if (name == null || description == null || parameters == null) {
			throw new IllegalArgumentException("name, description, and parameters must not be null");
		}
		return new Tool(name, description, parameters);
	}

	public static TextContent withText(TextContent block, String newText) {
		return textContent(newText, block.textSignature);
	}

	public static ThinkingContent withThinking(ThinkingContent block, String newThinking) {
		return thinkingContent(newThinking, block.thinkingSignature, block.redacted);
	}

	public static ThinkingContent withSignature(ThinkingContent block, String signature) {
		return thinkingContent(block.thinking, signature, block.redacted);
	}

	// --------------------------------------------------------------- context

	/** Shallow copy: message and tool lists are duplicated, elements shared. */
	public static Context copy(Context context) {
		Context copy = new Context(context.systemPrompt);
		copy.messages.addAll(context.messages);
		copy.tools.addAll(context.tools);
		return copy;
	}

	// --------------------------------------------------------------- model

	public static ModelCost modelCost(double input, double output, double cacheRead, double cacheWrite) {
		return modelCost(input, output, cacheRead, cacheWrite, List.of());
	}

	/** Creates model pricing; a missing tier list becomes the empty list. */
	public static ModelCost modelCost(
			double input, double output, double cacheRead, double cacheWrite, List<ModelCost.Tier> tiers) {
		return new ModelCost(input, output, cacheRead, cacheWrite, tiers == null ? List.of() : List.copyOf(tiers));
	}

	/** Copy of a model with its mutable collections duplicated. */
	public static Model copyModel(Model source) {
		Model copy = new Model();
		copy.id = source.id;
		copy.name = source.name;
		copy.api = source.api;
		copy.provider = source.provider;
		copy.baseUrl = source.baseUrl;
		copy.reasoning = source.reasoning;
		copy.thinkingLevelMap = copyThinkingLevelMap(source.thinkingLevelMap);
		copy.input = new ArrayList<>(source.input);
		copy.cost = source.cost;
		copy.contextWindow = source.contextWindow;
		copy.maxTokens = source.maxTokens;
		copy.headers = new LinkedHashMap<>(source.headers);
		copy.compat = source.compat;
		return copy;
	}

	private static Map<ThinkingLevel, String> copyThinkingLevelMap(Map<ThinkingLevel, String> source) {
		if (source == null) {
			return null;
		}
		Map<ThinkingLevel, String> copy = new EnumMap<>(ThinkingLevel.class);
		copy.putAll(source);
		return copy;
	}

	// ------------------------------------------------------------ wire enums

	public static StopReason stopReasonFromWire(String value) {
		for (StopReason reason : StopReason.values()) {
			if (reason.wire.equals(value)) {
				return reason;
			}
		}
		throw new IllegalArgumentException("Unknown stop reason: " + value);
	}

	public static ThinkingLevel thinkingLevelFromWire(String value) {
		for (ThinkingLevel level : ThinkingLevel.values()) {
			if (level.wire.equals(value)) {
				return level;
			}
		}
		throw new IllegalArgumentException("Unknown thinking level: " + value);
	}

	// ------------------------------------------------------------------ http

	public static ProxySelector proxySelectorFromEnv() {
		String httpsProxy = envAnyCase("https_proxy");
		String httpProxy = envAnyCase("http_proxy");
		if (httpsProxy.isEmpty() && httpProxy.isEmpty()) {
			return ProxySelector.getDefault();
		}
		String chosen = !httpsProxy.isEmpty() ? httpsProxy : httpProxy;
		try {
			URI proxyUri = URI.create(chosen);
			int port = proxyUri.getPort() != -1 ? proxyUri.getPort() : 80;
			return ProxySelector.of(new InetSocketAddress(proxyUri.getHost(), port));
		} catch (IllegalArgumentException e) {
			return ProxySelector.getDefault();
		}
	}

	public static String envAnyCase(String key) {
		String lower = System.getenv(key.toLowerCase(Locale.ROOT));
		if (lower != null && !lower.isEmpty()) {
			return lower;
		}
		String upper = System.getenv(key.toUpperCase(Locale.ROOT));
		return upper != null ? upper : "";
	}

	public static String httpErrorMessage(int status, String body) {
		String trimmed = body == null ? "" : body.trim();
		if (trimmed.isEmpty()) {
			return status + " status code (no body)";
		}
		return status + ": " + truncate(trimmed, HttpException.MAX_ERROR_BODY_CHARS);
	}

	/** Creates a non-2xx provider failure with its composed display message. */
	public static HttpException httpException(int status, String body) {
		return new HttpException(status, body, httpErrorMessage(status, body));
	}

	public static String truncate(String text, int maxChars) {
		if (text.length() <= maxChars) {
			return text;
		}
		return text.substring(0, maxChars) + "... [truncated " + (text.length() - maxChars) + " chars]";
	}

	/**
	 * POST a JSON body and return the streaming response. Throws HttpException
	 * for non-2xx status (body fully read), AbortedException when the signal
	 * fires, and IOException for transport failures.
	 */
	public static HttpTransport.Response httpPostJson(
			String url, Map<String, String> headers, byte[] body, Integer timeoutMs, AbortSignal signal)
			throws IOException {
		return httpPost(url, "application/json", headers, body, timeoutMs, signal);
	}

	/** POST an URL-encoded form body and return a streaming response. */
	public static HttpTransport.Response httpPostForm(
			String url, Map<String, String> headers, byte[] body, Integer timeoutMs, AbortSignal signal)
			throws IOException {
		return httpPost(url, "application/x-www-form-urlencoded", headers, body, timeoutMs, signal);
	}

	private static HttpTransport.Response httpPost(
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

	public static HttpTransport.Response httpGet(
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

	private static HttpTransport.Response httpSend(HttpRequest request, AbortSignal signal) throws IOException {
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
			throw httpException(response.statusCode(), errorBody);
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

	/** Releases a streaming response body; failures are ignored. */
	public static void closeHttpResponse(HttpTransport.Response response) {
		if (response == null) {
			return;
		}
		try {
			response.body.close();
		} catch (IOException ignored) {
		}
	}

	// ------------------------------------------------------------------- sse

	/** Wraps a byte stream in the UTF-8 buffered reader the SSE parser reads from. */
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

	/** Releases an SSE reader; failures are ignored. */
	public static void closeSseReader(SseReader source) {
		if (source == null) {
			return;
		}
		try {
			source.reader.close();
		} catch (IOException ignored) {
		}
	}

	// ------------------------------------------------------- env api keys

	/** Returns configured key environment-variable names in provider priority order. */
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

	public static Optional<String> resolveSystemApiKey(String provider) {
		return resolveApiKey(provider, System.getenv());
	}

	// ----------------------------------------------------------- credentials

	public static Credential.ApiKeyCredential apiKeyCredential(String key) {
		return apiKeyCredential(key, Map.of());
	}

	/** Creates an API key credential with an unmodifiable copy of its configuration values. */
	public static Credential.ApiKeyCredential apiKeyCredential(String key, Map<String, String> env) {
		return new Credential.ApiKeyCredential(key, env == null ? Map.of() : Map.copyOf(env));
	}

	public static Credential.OAuthCredential oauthCredential(String access, String refresh, long expires) {
		return oauthCredential(access, refresh, expires, null, Map.of());
	}

	public static Credential.OAuthCredential oauthCredential(
			String access, String refresh, long expires, List<String> availableModelIds) {
		return oauthCredential(access, refresh, expires, availableModelIds, Map.of());
	}

	/**
	 * Creates an OAuth credential. A missing model list stays null (meaning "not
	 * restricted"); missing metadata becomes the empty map.
	 */
	public static Credential.OAuthCredential oauthCredential(
			String access,
			String refresh,
			long expires,
			List<String> availableModelIds,
			Map<String, String> metadata) {
		return new Credential.OAuthCredential(
				access,
				refresh,
				expires,
				availableModelIds == null ? null : List.copyOf(availableModelIds),
				metadata == null ? Map.of() : Map.copyOf(metadata));
	}

	public static FileCredentialStore fileCredentialStore(Path authPath) {
		return fileCredentialStore(authPath, null);
	}

	/** Resolves the credential file, its sibling lock file, and the optional legacy file. */
	public static FileCredentialStore fileCredentialStore(Path authPath, Path fallbackAuthPath) {
		Path resolved = authPath.toAbsolutePath().normalize();
		return new FileCredentialStore(
				resolved,
				resolved.resolveSibling(resolved.getFileName() + ".lock"),
				fallbackAuthPath == null ? null : fallbackAuthPath.toAbsolutePath().normalize());
	}

	public static String credentialType(Credential credential) {
		return switch (credential) {
			case Credential.ApiKeyCredential ignored -> "api_key";
			case Credential.OAuthCredential ignored -> "oauth";
		};
	}

	public static boolean oauthCredentialIsExpired(Credential.OAuthCredential credential, long nowMs) {
		return credential.expires <= nowMs;
	}

	public static FileCredentialStore defaultCredentialStore() {
		Path home = Path.of(System.getProperty("user.home"));
		return fileCredentialStore(
				home.resolve(".codingagent").resolve("auth.json"),
				home.resolve(".pi-java").resolve("auth.json"));
	}

	public static Optional<Credential> readCredential(CredentialStore store, String providerId) throws IOException {
		return switch (store) {
			case FileCredentialStore file -> {
				validateProviderId(providerId);
				yield Optional.ofNullable(readAllCredentials(file).get(providerId));
			}
		};
	}

	public static List<CredentialStore.CredentialInfo> listCredentials(CredentialStore store) throws IOException {
		return switch (store) {
			case FileCredentialStore file -> {
				List<CredentialStore.CredentialInfo> result = new ArrayList<>();
				for (Map.Entry<String, Credential> entry : readAllCredentials(file).entrySet()) {
					result.add(new CredentialStore.CredentialInfo(entry.getKey(), credentialType(entry.getValue())));
				}
				yield List.copyOf(result);
			}
		};
	}

	/**
	 * Atomically applies a mutation to a provider credential. Returning null
	 * removes the credential.
	 */
	public static Optional<Credential> modifyCredential(
			CredentialStore store, String providerId, UnaryOperator<Credential> operation) throws IOException {
		return switch (store) {
			case FileCredentialStore file -> {
				validateProviderId(providerId);
				if (operation == null) {
					throw new IllegalArgumentException("operation must not be null");
				}
				ensureCredentialParentDirectory(file);
				try (FileChannel channel =
								FileChannel.open(file.lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
						FileLock ignored = channel.lock()) {
					Map<String, Credential> credentials = readAllCredentials(file);
					Credential next = operation.apply(credentials.get(providerId));
					if (next == null) {
						credentials.remove(providerId);
					} else {
						credentials.put(providerId, next);
					}
					writeAllCredentials(file, credentials);
					yield Optional.ofNullable(next);
				}
			}
		};
	}

	public static void deleteCredential(CredentialStore store, String providerId) throws IOException {
		modifyCredential(store, providerId, ignored -> null);
	}

	private static Map<String, Credential> readAllCredentials(FileCredentialStore store) throws IOException {
		Path source = Files.exists(store.authPath) ? store.authPath : store.fallbackAuthPath;
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
			result.put(entry.getKey(), parseCredential(entry.getKey(), entry.getValue()));
		}
		return result;
	}

	private static void writeAllCredentials(FileCredentialStore store, Map<String, Credential> credentials)
			throws IOException {
		ObjectNode root = jsonObject();
		for (Map.Entry<String, Credential> entry : credentials.entrySet()) {
			root.set(entry.getKey(), serializeCredential(entry.getValue()));
		}
		Path temp = Files.createTempFile(store.authPath.getParent(), "auth-", ".json");
		try {
			Files.writeString(temp, Json.MAPPER.writeValueAsString(root) + "\n", StandardCharsets.UTF_8);
			setPosixPermissions(temp, FileCredentialStore.FILE_PERMISSIONS);
			try {
				Files.move(temp, store.authPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temp, store.authPath, StandardCopyOption.REPLACE_EXISTING);
			}
			setPosixPermissions(store.authPath, FileCredentialStore.FILE_PERMISSIONS);
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	private static Credential parseCredential(String providerId, JsonNode node) throws IOException {
		if (!node.isObject() || !node.path("type").isTextual()) {
			throw invalidCredential(providerId);
		}
		return switch (node.path("type").asText()) {
			case "api_key" -> apiKeyCredential(
					optionalCredentialText(node, "key"), parseCredentialEnv(providerId, node.get("env")));
			case "oauth" -> {
				if (!node.path("access").isTextual()
						|| !node.path("refresh").isTextual()
						|| !node.path("expires").isIntegralNumber()) {
					throw invalidCredential(providerId);
				}
				yield oauthCredential(
						node.path("access").asText(),
						node.path("refresh").asText(),
						node.path("expires").asLong(),
						parseAvailableModelIds(providerId, node.get("availableModelIds")),
						parseCredentialEnv(providerId, node.get("metadata")));
			}
			default -> throw invalidCredential(providerId);
		};
	}

	private static ObjectNode serializeCredential(Credential credential) {
		ObjectNode node = jsonObject();
		switch (credential) {
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
		return node;
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

	private static List<String> parseAvailableModelIds(String providerId, JsonNode node) throws IOException {
		if (node == null) {
			return null;
		}
		if (!node.isArray()) {
			throw invalidCredential(providerId);
		}
		List<String> values = new ArrayList<>();
		for (JsonNode value : node) {
			if (!value.isTextual()) {
				throw invalidCredential(providerId);
			}
			values.add(value.asText());
		}
		return values;
	}

	private static String optionalCredentialText(JsonNode node, String field) throws IOException {
		JsonNode value = node.get(field);
		if (value == null) {
			return null;
		}
		if (!value.isTextual()) {
			throw new IOException("Invalid credential field: " + field);
		}
		return value.asText();
	}

	private static IOException invalidCredential(String providerId) {
		return new IOException("Invalid credential for provider \"" + providerId + "\"");
	}

	private static void ensureCredentialParentDirectory(FileCredentialStore store) throws IOException {
		Files.createDirectories(store.authPath.getParent());
		setPosixPermissions(store.authPath.getParent(), FileCredentialStore.DIRECTORY_PERMISSIONS);
	}

	private static void setPosixPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
		try {
			Files.setPosixFilePermissions(path, permissions);
		} catch (UnsupportedOperationException ignored) {
			// Windows lacks POSIX permissions; its ACLs still protect the user profile.
		}
	}

	private static void validateProviderId(String providerId) {
		if (providerId == null || providerId.isBlank() || providerId.indexOf('/') >= 0 || providerId.indexOf('\\') >= 0) {
			throw new IllegalArgumentException("Invalid provider id: " + providerId);
		}
	}

	// -------------------------------------------------------- chatgpt auth

	public static ChatGptAuth chatGptAuth(CredentialStore credentials) {
		return chatGptAuth(credentials, URI.create("https://auth.openai.com"), ChatGptAuth.CLIENT_ID);
	}

	/** Validates the authorization endpoint and client id before binding them to the carrier. */
	public static ChatGptAuth chatGptAuth(CredentialStore credentials, URI authBaseUrl, String clientId) {
		URI validatedBaseUrl = requireAbsoluteHttpUri(authBaseUrl, "authBaseUrl");
		if (clientId == null || clientId.isBlank()) throw new IllegalArgumentException("clientId must not be blank");
		return new ChatGptAuth(credentials, validatedBaseUrl, clientId);
	}

	/** Starts the Codex device flow. Display the URI and code before completing it. */
	public static ChatGptAuth.DeviceCode chatGptBeginLogin(ChatGptAuth auth) throws IOException {
		ObjectNode request = jsonObject().put("client_id", auth.clientId);
		JsonNode response = authPostJson(auth.authBaseUrl.resolve("/api/accounts/deviceauth/usercode"), request);
		String deviceAuthId = requiredOpenAiText(response, "device_auth_id");
		String userCode = requiredOpenAiText(response, "user_code");
		int interval = parseDeviceInterval(response.path("interval"));
		return new ChatGptAuth.DeviceCode(
				deviceAuthId,
				userCode,
				auth.authBaseUrl.resolve("/codex/device"),
				interval,
				System.currentTimeMillis() + ChatGptAuth.DEVICE_CODE_LIFETIME_MS);
	}

	/** Waits for browser authorization, exchanges the code, and saves refreshable tokens. */
	public static Credential.OAuthCredential chatGptCompleteLogin(ChatGptAuth auth, ChatGptAuth.DeviceCode device)
			throws IOException, InterruptedException {
		JsonNode authorization = pollForChatGptAuthorization(auth, device);
		Map<String, String> form = new LinkedHashMap<>();
		form.put("grant_type", "authorization_code");
		form.put("code", requiredOpenAiText(authorization, "authorization_code"));
		form.put("redirect_uri", "https://auth.openai.com/deviceauth/callback");
		form.put("client_id", auth.clientId);
		form.put("code_verifier", requiredOpenAiText(authorization, "code_verifier"));
		Credential.OAuthCredential credential =
				chatGptCredentialFromTokenResponse(authPostForm(auth.authBaseUrl.resolve("/oauth/token"), form), null);
		modifyCredential(auth.credentials, ChatGptAuth.PROVIDER_ID, ignored -> credential);
		return credential;
	}

	/** Returns a usable ChatGPT bearer token, refreshing it when close to expiry. */
	public static ChatGptAuth.ChatGptToken chatGptResolveToken(ChatGptAuth auth) throws IOException {
		Credential.OAuthCredential oauth = chatGptOauthCredential(auth);
		if (oauth.expires > System.currentTimeMillis() && !oauth.access.isBlank()) {
			return chatGptToken(oauth);
		}
		Map<String, String> form = new LinkedHashMap<>();
		form.put("grant_type", "refresh_token");
		form.put("refresh_token", oauth.refresh);
		form.put("client_id", auth.clientId);
		Credential.OAuthCredential refreshed =
				chatGptCredentialFromTokenResponse(authPostForm(auth.authBaseUrl.resolve("/oauth/token"), form), oauth);
		modifyCredential(auth.credentials, ChatGptAuth.PROVIDER_ID, ignored -> refreshed);
		return chatGptToken(refreshed);
	}

	public static boolean chatGptHasCredential(ChatGptAuth auth) throws IOException {
		Optional<Credential> credential = readCredential(auth.credentials, ChatGptAuth.PROVIDER_ID);
		return credential.filter(Credential.OAuthCredential.class::isInstance)
				.map(Credential.OAuthCredential.class::cast)
				.map(value -> !value.refresh.isBlank())
				.orElse(false);
	}

	public static void chatGptLogout(ChatGptAuth auth) throws IOException {
		deleteCredential(auth.credentials, ChatGptAuth.PROVIDER_ID);
	}

	private static JsonNode pollForChatGptAuthorization(ChatGptAuth auth, ChatGptAuth.DeviceCode device)
			throws IOException, InterruptedException {
		ObjectNode request = jsonObject()
				.put("device_auth_id", device.deviceAuthId)
				.put("user_code", device.userCode);
		while (System.currentTimeMillis() < device.expiresAtMs) {
			try {
				return authPostJson(auth.authBaseUrl.resolve("/api/accounts/deviceauth/token"), request);
			} catch (HttpException error) {
				if (error.status != 403 && error.status != 404) {
					throw error;
				}
				sleepSeconds(device.intervalSeconds);
			}
		}
		throw new IOException("ChatGPT device authorization expired before completion");
	}

	private static Credential.OAuthCredential chatGptCredentialFromTokenResponse(
			JsonNode response, Credential.OAuthCredential previous) throws IOException {
		String access = requiredOpenAiText(response, "access_token");
		String refresh = response.path("refresh_token").asText(previous == null ? "" : previous.refresh);
		if (refresh.isBlank()) {
			throw new IOException("Invalid OpenAI response: missing refresh_token");
		}
		long expiresIn = response.path("expires_in").asLong(3600);
		long expires =
				System.currentTimeMillis() + Math.max(1, expiresIn) * 1000 - ChatGptAuth.REFRESH_SKEW_MS;
		Map<String, String> metadata = new LinkedHashMap<>(previous == null ? Map.of() : previous.metadata);
		String idToken = response.path("id_token").asText();
		String accountId = chatGptAccountIdFromJwt(idToken);
		if (!accountId.isBlank()) {
			metadata.put(ChatGptAuth.ACCOUNT_ID, accountId);
		}
		if (!metadata.containsKey(ChatGptAuth.ACCOUNT_ID)) {
			throw new IOException("OpenAI login did not return a ChatGPT account id");
		}
		return oauthCredential(access, refresh, expires, null, metadata);
	}

	private static Credential.OAuthCredential chatGptOauthCredential(ChatGptAuth auth) throws IOException {
		Credential credential = readCredential(auth.credentials, ChatGptAuth.PROVIDER_ID)
				.orElseThrow(() -> new IOException("ChatGPT Plus/Pro is not logged in. Run /login."));
		if (credential instanceof Credential.OAuthCredential oauth) {
			return oauth;
		}
		throw new IOException("ChatGPT credential is not an OAuth credential. Run /login.");
	}

	private static ChatGptAuth.ChatGptToken chatGptToken(Credential.OAuthCredential credential) throws IOException {
		String accountId = credential.metadata.get(ChatGptAuth.ACCOUNT_ID);
		if (accountId == null || accountId.isBlank()) {
			throw new IOException("Saved ChatGPT login is missing its account id. Run /login again.");
		}
		return new ChatGptAuth.ChatGptToken(credential.access, accountId);
	}

	private static String chatGptAccountIdFromJwt(String token) throws IOException {
		if (token == null || token.isBlank()) {
			return "";
		}
		String[] parts = token.split("\\.");
		if (parts.length < 2) {
			throw new IOException("OpenAI returned an invalid ID token");
		}
		try {
			JsonNode claims = Json.MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
			return claims.path("https://api.openai.com/auth")
					.path("chatgpt_account_id")
					.asText(claims.path("https://api.openai.com/auth.chatgpt_account_id").asText());
		} catch (IllegalArgumentException error) {
			throw new IOException("OpenAI returned an invalid ID token", error);
		}
	}

	private static int parseDeviceInterval(JsonNode node) throws IOException {
		int interval;
		try {
			interval = node.isIntegralNumber() ? node.asInt() : Integer.parseInt(node.asText("5"));
		} catch (NumberFormatException error) {
			throw new IOException("Invalid OpenAI device response: invalid interval", error);
		}
		if (interval < 0) {
			throw new IOException("Invalid OpenAI device response: interval must not be negative");
		}
		return interval;
	}

	private static JsonNode authPostJson(URI url, JsonNode body) throws IOException {
		HttpTransport.Response response = httpPostJson(
				url.toString(), Map.of("Accept", "application/json"), Json.MAPPER.writeValueAsBytes(body), null, null);
		try {
			return Json.MAPPER.readTree(response.body);
		} finally {
			closeHttpResponse(response);
		}
	}

	private static JsonNode authPostForm(URI url, Map<String, String> parameters) throws IOException {
		String body = parameters.entrySet().stream()
				.map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
						+ URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
				.reduce((left, right) -> left + "&" + right)
				.orElse("");
		HttpTransport.Response response = httpPostForm(
				url.toString(),
				Map.of("Accept", "application/json"),
				body.getBytes(StandardCharsets.UTF_8),
				null,
				null);
		try {
			return Json.MAPPER.readTree(response.body);
		} finally {
			closeHttpResponse(response);
		}
	}

	private static String requiredOpenAiText(JsonNode node, String field) throws IOException {
		if (!node.path(field).isTextual() || node.path(field).asText().isBlank()) {
			throw new IOException("Invalid OpenAI response: missing " + field);
		}
		return node.path(field).asText();
	}

	public static URI requireAbsoluteHttpUri(URI uri, String field) {
		if (uri == null || !uri.isAbsolute() || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))) {
			throw new IllegalArgumentException(field + " must be an absolute HTTP(S) URI");
		}
		return uri;
	}

	private static void sleepSeconds(int seconds) throws InterruptedException {
		if (seconds > 0) {
			Thread.sleep(seconds * 1000L);
		}
	}

	// -------------------------------------------------- github copilot auth

	public static GitHubCopilotAuth gitHubCopilotAuth(CredentialStore credentials) {
		return gitHubCopilotAuth(
				credentials,
				URI.create("https://github.com"),
				URI.create("https://api.github.com/copilot_internal/v2/token"),
				URI.create(GitHubCopilotAuth.DEFAULT_COPILOT_BASE_URL));
	}

	/** Validates every endpoint before binding it to the carrier. Visible for deterministic HTTP tests. */
	public static GitHubCopilotAuth gitHubCopilotAuth(
			CredentialStore credentials, URI githubBaseUrl, URI copilotTokenUrl, URI defaultCopilotBaseUrl) {
		return new GitHubCopilotAuth(
				credentials,
				requireAbsoluteHttpUri(githubBaseUrl, "githubBaseUrl"),
				requireAbsoluteHttpUri(copilotTokenUrl, "copilotTokenUrl"),
				requireAbsoluteHttpUri(defaultCopilotBaseUrl, "defaultCopilotBaseUrl"));
	}

	/** Starts the device flow. Display the resulting URI and code before completing the login. */
	public static GitHubCopilotAuth.DeviceCode gitHubCopilotBeginLogin(GitHubCopilotAuth auth) throws IOException {
		JsonNode response = gitHubPostForm(
				auth.githubBaseUrl.resolve("/login/device/code"),
				Map.of("client_id", GitHubCopilotAuth.CLIENT_ID, "scope", "read:user"));
		String deviceCode = requiredGitHubText(response, "device_code");
		String userCode = requiredGitHubText(response, "user_code");
		URI verificationUri = requireAbsoluteHttpUri(
				URI.create(requiredGitHubText(response, "verification_uri")), "verification_uri");
		long expiresIn = requiredPositiveLong(response, "expires_in");
		int interval = response.path("interval").isIntegralNumber() ? response.path("interval").asInt() : 5;
		if (interval < 0) {
			throw new IOException("Invalid device code response: interval must not be negative");
		}
		return new GitHubCopilotAuth.DeviceCode(
				deviceCode, userCode, verificationUri, interval, System.currentTimeMillis() + expiresIn * 1000);
	}

	/** Polls GitHub, exchanges the durable GitHub token for a Copilot token, and saves the credential. */
	public static Credential.OAuthCredential gitHubCopilotCompleteLogin(
			GitHubCopilotAuth auth, GitHubCopilotAuth.DeviceCode device) throws IOException, InterruptedException {
		String githubAccessToken = pollForGitHubAccessToken(auth, device);
		Credential.OAuthCredential credential = createCopilotCredential(auth, githubAccessToken, null);
		try {
			credential = withCopilotAvailableModels(auth, credential);
		} catch (IOException ignored) {
			// The Copilot token is valid even if its optional model catalog is transiently unavailable.
		}
		Credential.OAuthCredential saved = credential;
		modifyCredential(auth.credentials, GitHubCopilotAuth.PROVIDER_ID, ignored -> saved);
		return credential;
	}

	/** Returns a valid Copilot API token, refreshing it from the stored GitHub token when necessary. */
	public static GitHubCopilotAuth.CopilotToken gitHubCopilotResolveToken(GitHubCopilotAuth auth) throws IOException {
		Credential credential = readCredential(auth.credentials, GitHubCopilotAuth.PROVIDER_ID)
				.orElseThrow(() -> new IOException("GitHub Copilot is not logged in. Run /login."));
		if (!(credential instanceof Credential.OAuthCredential oauth)) {
			throw new IOException("GitHub Copilot credential is not an OAuth credential. Run /login.");
		}
		if (!oauthCredentialIsExpired(oauth, System.currentTimeMillis()) && !oauth.access.isBlank()) {
			return copilotToken(auth, oauth);
		}
		Credential.OAuthCredential refreshed = createCopilotCredential(auth, oauth.refresh, oauth.availableModelIds);
		try {
			refreshed = withCopilotAvailableModels(auth, refreshed);
		} catch (IOException ignored) {
			// Retain the last known entitlement list if model discovery cannot be refreshed.
		}
		Credential.OAuthCredential saved = refreshed;
		modifyCredential(auth.credentials, GitHubCopilotAuth.PROVIDER_ID, ignored -> saved);
		return copilotToken(auth, refreshed);
	}

	/** Reports whether a saved GitHub OAuth credential can be refreshed. */
	public static boolean gitHubCopilotHasCredential(GitHubCopilotAuth auth) throws IOException {
		Optional<Credential> credential = readCredential(auth.credentials, GitHubCopilotAuth.PROVIDER_ID);
		return credential.filter(Credential.OAuthCredential.class::isInstance)
				.map(Credential.OAuthCredential.class::cast)
				.map(value -> !value.refresh.isBlank())
				.orElse(false);
	}

	public static void gitHubCopilotLogout(GitHubCopilotAuth auth) throws IOException {
		deleteCredential(auth.credentials, GitHubCopilotAuth.PROVIDER_ID);
	}

	/** Enables the listed Copilot model policies and reports how many policy requests GitHub accepted. */
	public static int gitHubCopilotEnableModels(GitHubCopilotAuth auth, List<String> modelIds) throws IOException {
		GitHubCopilotAuth.CopilotToken token = gitHubCopilotResolveToken(auth);
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

	/** Re-fetches and persists the enabled-model list without minting a new Copilot API token. */
	public static GitHubCopilotAuth.CopilotToken gitHubCopilotRefreshAvailableModels(GitHubCopilotAuth auth)
			throws IOException {
		GitHubCopilotAuth.CopilotToken current = gitHubCopilotResolveToken(auth);
		Credential.OAuthCredential oauth = copilotOauthCredential(auth);
		Credential.OAuthCredential refreshed = oauthCredential(
				oauth.access, oauth.refresh, oauth.expires, fetchCopilotAvailableModelIds(auth, current.accessToken));
		modifyCredential(auth.credentials, GitHubCopilotAuth.PROVIDER_ID, ignored -> refreshed);
		return copilotToken(auth, refreshed);
	}

	private static Credential.OAuthCredential createCopilotCredential(
			GitHubCopilotAuth auth, String githubAccessToken, List<String> availableModelIds) throws IOException {
		if (githubAccessToken == null || githubAccessToken.isBlank()) {
			throw new IOException("GitHub returned an empty access token");
		}
		JsonNode response;
		HttpTransport.Response http = httpGet(
				auth.copilotTokenUrl.toString(), copilotHeaders("token " + githubAccessToken), null, null);
		try {
			response = Json.MAPPER.readTree(http.body);
		} finally {
			closeHttpResponse(http);
		}
		String token = requiredGitHubText(response, "token");
		long expiresAtSeconds = requiredPositiveLong(response, "expires_at");
		long expires = Math.max(
				System.currentTimeMillis(), expiresAtSeconds * 1000 - GitHubCopilotAuth.REFRESH_SKEW_MS);
		return oauthCredential(token, githubAccessToken, expires, availableModelIds);
	}

	private static Credential.OAuthCredential withCopilotAvailableModels(
			GitHubCopilotAuth auth, Credential.OAuthCredential credential) throws IOException {
		List<String> available = fetchCopilotAvailableModelIds(auth, credential.access);
		return oauthCredential(
				credential.access, credential.refresh, credential.expires, available);
	}

	private static List<String> fetchCopilotAvailableModelIds(GitHubCopilotAuth auth, String copilotToken)
			throws IOException {
		URI modelsUrl = copilotBaseUrlFromToken(auth, copilotToken).resolve("/models");
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
					|| model.path("capabilities").path("supports").path("tool_calls").asBoolean(true) == false) {
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
						&& copilotBaseUrlFromToken(auth, copilotToken)
								.toString()
								.equals(GitHubCopilotAuth.DEFAULT_COPILOT_BASE_URL)
				? List.copyOf(policyEnabled)
				: List.copyOf(pickerEnabled);
	}

	private static String pollForGitHubAccessToken(GitHubCopilotAuth auth, GitHubCopilotAuth.DeviceCode device)
			throws IOException, InterruptedException {
		int intervalSeconds = device.intervalSeconds;
		while (System.currentTimeMillis() < device.expiresAtMs) {
			JsonNode response = gitHubPostForm(
					auth.githubBaseUrl.resolve("/login/oauth/access_token"),
					Map.of(
							"client_id", GitHubCopilotAuth.CLIENT_ID,
							"device_code", device.deviceCode,
							"grant_type", "urn:ietf:params:oauth:grant-type:device_code"));
			if (response.path("access_token").isTextual()) {
				return response.path("access_token").asText();
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
		throw new IOException("GitHub device authorization expired before completion");
	}

	private static GitHubCopilotAuth.CopilotToken copilotToken(
			GitHubCopilotAuth auth, Credential.OAuthCredential credential) {
		List<String> availableModelIds = credential.availableModelIds;
		return new GitHubCopilotAuth.CopilotToken(
				credential.access,
				copilotBaseUrlFromToken(auth, credential.access),
				availableModelIds == null ? null : List.copyOf(availableModelIds));
	}

	private static Credential.OAuthCredential copilotOauthCredential(GitHubCopilotAuth auth) throws IOException {
		Credential credential = readCredential(auth.credentials, GitHubCopilotAuth.PROVIDER_ID)
				.orElseThrow(() -> new IOException("GitHub Copilot is not logged in. Run /login."));
		if (credential instanceof Credential.OAuthCredential oauth) {
			return oauth;
		}
		throw new IOException("GitHub Copilot credential is not an OAuth credential. Run /login.");
	}

	private static URI copilotBaseUrlFromToken(GitHubCopilotAuth auth, String token) {
		Matcher match = GitHubCopilotAuth.PROXY_ENDPOINT.matcher(token);
		if (!match.find()) {
			return auth.defaultCopilotBaseUrl;
		}
		String host = match.group(1);
		if (!host.matches("[A-Za-z0-9.-]+")) {
			return auth.defaultCopilotBaseUrl;
		}
		return URI.create("https://" + host.replaceFirst("^proxy\\.", "api."));
	}

	private static Map<String, String> copilotHeaders(String authorization) {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Accept", "application/json");
		headers.put("Authorization", authorization);
		headers.put("User-Agent", GitHubCopilotAuth.USER_AGENT);
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

	public static String encodeUrlPathSegment(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
	}

	private static JsonNode gitHubPostForm(URI url, Map<String, String> parameters) throws IOException {
		String body = parameters.entrySet().stream()
				.map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
						+ "="
						+ URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
				.reduce((left, right) -> left + "&" + right)
				.orElse("");
		HttpTransport.Response response = httpPostForm(
				url.toString(),
				Map.of("Accept", "application/json", "User-Agent", GitHubCopilotAuth.USER_AGENT),
				body.getBytes(StandardCharsets.UTF_8),
				null,
				null);
		try {
			return Json.MAPPER.readTree(response.body);
		} finally {
			closeHttpResponse(response);
		}
	}

	private static String requiredGitHubText(JsonNode node, String field) throws IOException {
		if (!node.path(field).isTextual() || node.path(field).asText().isBlank()) {
			throw new IOException("Invalid GitHub response: missing " + field);
		}
		return node.path(field).asText();
	}

	private static long requiredPositiveLong(JsonNode node, String field) throws IOException {
		if (!node.path(field).isIntegralNumber() || node.path(field).asLong() <= 0) {
			throw new IOException("Invalid GitHub response: missing " + field);
		}
		return node.path(field).asLong();
	}

	// ----------------------------------------------------------- model ops

	/** Computes and stores cost on usage.cost, returning it. */
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

	public static boolean modelsAreEqual(Model a, Model b) {
		if (a == null || b == null) {
			return false;
		}
		return a.id.equals(b.id) && a.provider.equals(b.provider);
	}

	// -------------------------------------------------------- model catalog

	/** Loads the bundled provider model snapshots. */
	public static ModelCatalog loadBundledModelCatalog() {
		Map<String, Model> models = new LinkedHashMap<>();
		Map<String, List<Model>> providers = new LinkedHashMap<>();
		for (String resourceName : ModelCatalog.RESOURCE_NAMES) {
			loadModelCatalogResource(resourceName, models, providers);
		}
		Map<String, List<Model>> immutableByProvider = new LinkedHashMap<>();
		for (Map.Entry<String, List<Model>> entry : providers.entrySet()) {
			immutableByProvider.put(entry.getKey(), List.copyOf(entry.getValue()));
		}
		return new ModelCatalog(Map.copyOf(models), Map.copyOf(immutableByProvider));
	}

	/**
	 * Finds a model by its provider and id, returning null if it is absent. Use
	 * {@link #requireCatalogModel} when absence is a user-facing error.
	 */
	public static Model findCatalogModel(ModelCatalog catalog, String provider, String id) {
		return catalog.byProviderAndId.get(modelCatalogKey(provider, id));
	}

	/** Finds a model or throws a clear error that includes the provider/id pair. */
	public static Model requireCatalogModel(ModelCatalog catalog, String provider, String id) {
		Model model = findCatalogModel(catalog, provider, id);
		if (model == null) {
			throw new IllegalArgumentException("Unknown model: " + provider + "/" + id);
		}
		return model;
	}

	/** Returns every bundled model from a provider, preserving source-file order. */
	public static List<Model> catalogModelsForProvider(ModelCatalog catalog, String provider) {
		return catalog.byProvider.getOrDefault(provider, List.of());
	}

	/** Returns every bundled model, preserving resource and source-file order. */
	public static List<Model> allCatalogModels(ModelCatalog catalog) {
		return List.copyOf(catalog.byProviderAndId.values());
	}

	private static void loadModelCatalogResource(
			String resourceName, Map<String, Model> models, Map<String, List<Model>> providers) {
		try (InputStream input = ModelCatalog.class.getResourceAsStream(ModelCatalog.RESOURCE_ROOT + resourceName)) {
			if (input == null) {
				throw new IllegalStateException("Missing bundled model catalog resource: " + resourceName);
			}
			JsonNode root = Json.MAPPER.readTree(input);
			for (Iterator<JsonNode> groups = root.elements(); groups.hasNext(); ) {
				JsonNode group = groups.next();
				for (Iterator<JsonNode> entries = group.elements(); entries.hasNext(); ) {
					Model model = parseCatalogModel(entries.next());
					String modelKey = modelCatalogKey(model.provider, model.id);
					if (models.putIfAbsent(modelKey, model) != null) {
						throw new IllegalStateException("Duplicate bundled model: " + modelKey);
					}
					providers.computeIfAbsent(model.provider, ignored -> new ArrayList<>()).add(model);
				}
			}
		} catch (IOException e) {
			throw new IllegalStateException("Unable to load bundled model catalog: " + resourceName, e);
		}
	}

	private static Model parseCatalogModel(JsonNode node) {
		Model model = new Model();
		model.id = requiredCatalogText(node, "id");
		model.name = requiredCatalogText(node, "name");
		model.api = requiredCatalogText(node, "api");
		model.provider = requiredCatalogText(node, "provider");
		model.baseUrl = requiredCatalogText(node, "baseUrl");
		model.reasoning = node.path("reasoning").asBoolean();
		model.input = new ArrayList<>(catalogTextList(node.path("input")));
		model.cost = parseCatalogCost(node.path("cost"));
		model.contextWindow = node.path("contextWindow").asLong();
		model.maxTokens = node.path("maxTokens").asLong();

		JsonNode thinkingLevelMap = node.get("thinkingLevelMap");
		if (thinkingLevelMap != null && thinkingLevelMap.isObject()) {
			Map<ThinkingLevel, String> levels = new EnumMap<>(ThinkingLevel.class);
			for (Map.Entry<String, JsonNode> field : thinkingLevelMap.properties()) {
				levels.put(
						thinkingLevelFromWire(field.getKey()),
						field.getValue().isNull() ? null : field.getValue().asText());
			}
			model.thinkingLevelMap = levels;
		}

		JsonNode headers = node.get("headers");
		if (headers != null && headers.isObject()) {
			Map<String, String> parsedHeaders = new LinkedHashMap<>();
			for (Map.Entry<String, JsonNode> field : headers.properties()) {
				parsedHeaders.put(field.getKey(), field.getValue().asText());
			}
			model.headers = parsedHeaders;
		}

		Compat compat = parseCatalogCompat(node);
		if (compat != null) {
			model.compat = compat;
		}
		return model;
	}

	private static Compat parseCatalogCompat(JsonNode model) {
		JsonNode compat = model.get("compat");
		if (compat == null || !compat.isObject()) {
			return null;
		}
		if (!model.path("api").asText().equals("anthropic-messages")) {
			return null;
		}
		JsonNode adaptiveThinking = compat.get("forceAdaptiveThinking");
		if (adaptiveThinking == null || !adaptiveThinking.isBoolean()) {
			return null;
		}
		return new Compat.AnthropicMessages(null, null, null, null, adaptiveThinking.booleanValue());
	}

	private static ModelCost parseCatalogCost(JsonNode node) {
		List<ModelCost.Tier> tiers = new ArrayList<>();
		JsonNode tierNodes = node.path("tiers");
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
		return modelCost(
				node.path("input").asDouble(),
				node.path("output").asDouble(),
				node.path("cacheRead").asDouble(),
				node.path("cacheWrite").asDouble(),
				tiers);
	}

	private static List<String> catalogTextList(JsonNode node) {
		List<String> values = new ArrayList<>();
		for (JsonNode value : node) {
			values.add(value.asText());
		}
		return values;
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

	public static CoreProviders loadBundledCoreProviders() {
		ModelCatalog catalog = loadBundledModelCatalog();
		FileCredentialStore credentials = defaultCredentialStore();
		Map<String, Provider> providers = new LinkedHashMap<>();
		providers.put(
				"anthropic",
				anthropicProvider(catalogModelsForProvider(catalog, "anthropic").stream()
						.filter(model -> model.api.equals("anthropic-messages"))
						.toList()));
		providers.put(
				"openai",
				openAiResponsesProvider(
						catalogModelsForProvider(catalog, "openai").stream()
								.filter(model -> model.api.equals("openai-responses"))
								.toList(),
						credentials));
		providers.put(
				ChatGptAuth.PROVIDER_ID,
				newChatGptProvider(
						catalogModelsForProvider(catalog, "openai").stream()
								.filter(model -> model.api.equals("openai-responses"))
								.toList(),
						chatGptAuth(credentials)));
		providers.put(
				"google",
				googleProvider(catalogModelsForProvider(catalog, "google").stream()
						.filter(model -> model.api.equals("google-generative-ai"))
						.toList()));
		providers.put(
				"github-copilot",
				newGitHubCopilotProvider(
						catalogModelsForProvider(catalog, "github-copilot"), gitHubCopilotAuth(credentials)));
		return new CoreProviders(catalog, Map.copyOf(providers));
	}

	public static Provider requireCoreProvider(CoreProviders core, String id) {
		Provider provider = core.providers.get(id);
		if (provider == null) {
			throw new IllegalArgumentException("Unknown core provider: " + id);
		}
		return provider;
	}

	public static List<Provider> allCoreProviders(CoreProviders core) {
		return List.copyOf(core.providers.values());
	}

	/**
	 * Creates a one-model provider for a user-configured Chat
	 * Completions-compatible service (including local models).
	 */
	public static OpenAiCompatibleProvider openAiCompatibleProvider(
			String providerId, String providerName, String baseUrl, String modelId) {
		Model model = new Model();
		model.id = modelId;
		model.name = modelId;
		model.api = "openai-completions";
		model.provider = providerId;
		model.baseUrl = baseUrl;
		model.input = new ArrayList<>(List.of("text", "image"));
		model.cost = ModelCost.FREE;
		model.contextWindow = 128_000;
		model.maxTokens = 16_384;
		return openAiCompatibleProvider(providerId, providerName, baseUrl, List.of(model));
	}

	// -------------------------------------------------------- stream options

	public static boolean isAborted(StreamOptions options) {
		return options != null && options.signal != null && isAborted(options.signal);
	}

	/** Shallow copy with independent header and metadata maps. */
	public static StreamOptions copyStreamOptions(StreamOptions source) {
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
	public static AssistantMessage retryAssistantCall(
			Callable<AssistantMessage> produce, Retry.Policy policy, AbortSignal signal, Retry.Callbacks callbacks)
			throws InterruptedException {
		int maxAttempts = policy != null && policy.enabled ? policy.maxRetries : 0;

		int attempt = 0;
		Integer lastRetryAttempt = null;
		while (true) {
			AssistantMessage response = callAssistantProducer(produce);

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
			notifyRetryScheduled(callbacks, attempt, maxAttempts, delayMs, errorMessage);

			if (!sleepAbortable(delayMs, signal)) {
				notifyRetryFinished(callbacks, false, attempt, errorMessage);
				response.stopReason = StopReason.ABORTED;
				response.errorMessage = null;
				return response;
			}
			notifyRetryAttemptStart(callbacks);
		}
	}

	/**
	 * Invokes a producer, preserving InterruptedException and unchecked
	 * failures. Other checked exceptions cannot cross the retry loop's
	 * signature and are wrapped.
	 */
	private static AssistantMessage callAssistantProducer(Callable<AssistantMessage> produce)
			throws InterruptedException {
		try {
			return produce.call();
		} catch (InterruptedException | RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static void notifyRetryScheduled(
			Retry.Callbacks callbacks, int attempt, int maxAttempts, long delayMs, String errorMessage) {
		if (callbacks != null && callbacks.onRetryScheduled != null) {
			callbacks.onRetryScheduled.accept(new Retry.Scheduled(attempt, maxAttempts, delayMs, errorMessage));
		}
	}

	private static void notifyRetryAttemptStart(Retry.Callbacks callbacks) {
		if (callbacks != null && callbacks.onRetryAttemptStart != null) {
			callbacks.onRetryAttemptStart.run();
		}
	}

	private static void notifyRetryFinished(
			Retry.Callbacks callbacks, boolean success, int attempt, String finalError) {
		if (callbacks != null && callbacks.onRetryFinished != null) {
			callbacks.onRetryFinished.accept(new Retry.Finished(success, attempt, finalError));
		}
	}

	/** Returns false if aborted during sleep. */
	private static boolean sleepAbortable(long ms, AbortSignal signal) throws InterruptedException {
		if (signal == null) {
			Thread.sleep(ms);
			return true;
		}
		if (isAborted(signal)) {
			return false;
		}
		Object monitor = new Object();
		onAbort(signal, () -> {
			synchronized (monitor) {
				monitor.notifyAll();
			}
		});
		long deadline = System.currentTimeMillis() + ms;
		synchronized (monitor) {
			while (!isAborted(signal)) {
				long remaining = deadline - System.currentTimeMillis();
				if (remaining <= 0) {
					return true;
				}
				monitor.wait(remaining);
			}
		}
		return false;
	}

	// ------------------------------------------------------ shared provider

	public static String displayError(Exception error) {
		return error.getMessage() == null ? error.toString() : error.getMessage();
	}

	public static String requireNonBlank(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		return value;
	}

	public static String trimTrailingSlash(String value) {
		return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
	}

	private static void abortStream(AssistantMessageEventStream stream, AssistantMessage output) {
		output.stopReason = StopReason.ABORTED;
		output.errorMessage = "Request was aborted";
		push(stream, new AssistantMessageEvent.Error(StopReason.ABORTED, output));
	}

	private static void ensureContentIndex(AssistantMessage output, int index, AssistantContent value) {
		while (output.content.size() <= index) {
			output.content.add(textContent(""));
		}
		output.content.set(index, value);
	}

	// ------------------------------------------------------------- anthropic

	public static AnthropicProvider anthropicProvider(List<Model> models) {
		return anthropicProvider(
				"anthropic",
				"Anthropic",
				models,
				List.of(
						EnvApiKeys.ANTHROPIC_AUTH_TOKEN_ENV,
						EnvApiKeys.ANTHROPIC_OAUTH_TOKEN_ENV,
						EnvApiKeys.ANTHROPIC_API_KEY_ENV),
				false);
	}

	/** Creates an Anthropic Messages provider with unmodifiable model and env-var lists. */
	public static AnthropicProvider anthropicProvider(
			String id, String name, List<Model> models, List<String> apiKeyEnvVars, boolean bearerAuthentication) {
		return new AnthropicProvider(id, name, List.copyOf(models), List.copyOf(apiKeyEnvVars), bearerAuthentication);
	}

	public static AssistantMessageEventStream anthropicStream(
			AnthropicProvider provider, Model model, Context context, StreamOptions options) {
		if (!model.api.equals(AnthropicProvider.API)) {
			throw new IllegalArgumentException("Model " + model + " is not an Anthropic Messages model");
		}
		AssistantMessageEventStream stream = new AssistantMessageEventStream();
		StreamOptions requestOptions = options != null ? options : new StreamOptions();
		Thread.startVirtualThread(() -> anthropicProduce(provider, stream, model, context, requestOptions));
		return stream;
	}

	private static void anthropicProduce(
			AnthropicProvider provider,
			AssistantMessageEventStream stream,
			Model model,
			Context context,
			StreamOptions options) {
		AssistantMessage output = new AssistantMessage(model.api, model.provider, model.id);
		try {
			Map<String, String> headers = anthropicRequestHeaders(provider, model, options);
			ObjectNode request = anthropicRequestBody(model, context, options);
			HttpTransport.Response response = httpPostJson(
					providerBaseUrl(model, options) + "/v1/messages",
					headers,
					Json.MAPPER.writeValueAsBytes(request),
					options.timeoutMs,
					options.signal);
			try {
				SseReader reader = sseReader(response.body);
				try {
					push(stream, new AssistantMessageEvent.Start(output));
					anthropicReadSse(stream, reader, model, output, options);
				} finally {
					closeSseReader(reader);
				}
			} finally {
				closeHttpResponse(response);
			}
		} catch (Exception e) {
			output.stopReason = isAborted(options) ? StopReason.ABORTED : StopReason.ERROR;
			output.errorMessage = isAborted(options) ? "Request was aborted" : displayError(e);
			push(stream, new AssistantMessageEvent.Error(output.stopReason, output));
		}
	}

	private static Map<String, String> anthropicRequestHeaders(
			AnthropicProvider provider, Model model, StreamOptions options) {
		Map<String, String> headers = new LinkedHashMap<>(model.headers);
		headers.putAll(options.headers);
		headers.putIfAbsent("anthropic-version", AnthropicProvider.API_VERSION);
		if (headers.containsKey("authorization")
				|| headers.containsKey("Authorization")
				|| headers.containsKey("x-api-key")) {
			return headers;
		}
		String key = options.apiKey;
		if (!provider.bearerAuthentication) {
			String bearer = System.getenv(EnvApiKeys.ANTHROPIC_AUTH_TOKEN_ENV);
			if (bearer != null && !bearer.isBlank()) {
				headers.put("authorization", "Bearer " + bearer);
				return headers;
			}
		}
		if ((key == null || key.isBlank()) && !provider.bearerAuthentication) {
			key = resolveSystemApiKey("anthropic").orElse(null);
		}
		if (key == null || key.isBlank()) {
			throw new IllegalStateException("No API key for provider: " + provider.id);
		}
		headers.put(
				provider.bearerAuthentication ? "Authorization" : "x-api-key",
				provider.bearerAuthentication ? "Bearer " + key : key);
		return headers;
	}

	private static String providerBaseUrl(Model model, StreamOptions options) {
		return options.baseUrl == null || options.baseUrl.isBlank() ? model.baseUrl : options.baseUrl;
	}

	private static ObjectNode anthropicRequestBody(Model model, Context context, StreamOptions options) {
		ObjectNode request = jsonObject();
		request.put("model", model.id);
		request.put("stream", true);
		request.put("max_tokens", options.maxTokens != null ? options.maxTokens : model.maxTokens);
		if (context.systemPrompt != null && !context.systemPrompt.isBlank()) {
			request.put("system", context.systemPrompt);
		}
		if (options.temperature != null) {
			request.put("temperature", options.temperature);
		}
		if (options.reasoning != null && options.reasoning != ThinkingLevel.OFF) {
			ObjectNode thinking = request.putObject("thinking");
			if (anthropicUsesAdaptiveThinking(model)) {
				thinking.put("type", "adaptive");
				thinking.put("display", "summarized");
				request.putObject("output_config")
						.put("effort", anthropicAdaptiveThinkingEffort(model, options.reasoning));
			} else {
				thinking.put("type", "enabled");
				thinking.put("budget_tokens", Math.min(model.maxTokens, 16_000));
			}
		}
		ArrayNode messages = request.putArray("messages");
		for (Message message : context.messages) {
			anthropicAppendMessage(messages, message);
		}
		if (!context.tools.isEmpty()) {
			ArrayNode tools = request.putArray("tools");
			for (Tool tool : context.tools) {
				ObjectNode target = tools.addObject();
				target.put("name", tool.name);
				target.put("description", tool.description);
				target.set("input_schema", tool.parameters);
			}
		}
		return request;
	}

	private static boolean anthropicUsesAdaptiveThinking(Model model) {
		return model.compat instanceof Compat.AnthropicMessages compat
				&& Boolean.TRUE.equals(compat.forceAdaptiveThinking);
	}

	private static String anthropicAdaptiveThinkingEffort(Model model, ThinkingLevel requested) {
		ThinkingLevel level = clampThinkingLevel(model, requested);
		if (model.thinkingLevelMap != null) {
			String mapped = model.thinkingLevelMap.get(level);
			if (mapped != null) {
				return mapped;
			}
		}
		return switch (level) {
			case MINIMAL, LOW -> "low";
			case MEDIUM -> "medium";
			case HIGH, XHIGH, MAX -> "high";
			case OFF -> throw new IllegalArgumentException("Adaptive thinking requires a non-off thinking level");
		};
	}

	private static void anthropicAppendMessage(ArrayNode messages, Message message) {
		switch (message) {
			case UserMessage user -> anthropicAppendUser(messages.addObject().put("role", "user"), user.content);
			case AssistantMessage assistant ->
				anthropicAppendAssistant(messages.addObject().put("role", "assistant"), assistant);
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

	private static void anthropicAppendUser(ObjectNode target, List<UserContent> content) {
		boolean onlyText = content.stream().allMatch(TextContent.class::isInstance);
		if (onlyText) {
			target.put(
					"content",
					content.stream()
							.map(TextContent.class::cast)
							.map(block -> block.text)
							.reduce("", String::concat));
			return;
		}
		anthropicAppendContent(target.putArray("content"), content);
	}

	private static void anthropicAppendContent(ArrayNode target, List<? extends UserContent> content) {
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

	private static void anthropicAppendAssistant(ObjectNode target, AssistantMessage message) {
		ArrayNode content = target.putArray("content");
		for (AssistantContent block : message.content) {
			if (block instanceof TextContent text) {
				content.addObject().put("type", "text").put("text", text.text);
			} else if (block instanceof ThinkingContent thinking) {
				String signature = thinking.thinkingSignature;
				boolean hasSignature = signature != null && !signature.isBlank();
				if (!hasSignature) {
					// Anthropic rejects a thinking block without a non-empty opaque signature.
					// Preserve visible reasoning as ordinary context instead of replaying an
					// invalid empty signature from an interrupted or incomplete stream.
					if (!thinking.thinking.isBlank()) {
						content.addObject().put("type", "text").put("text", thinking.thinking);
					}
				} else {
					content.addObject()
							.put("type", "thinking")
							.put("thinking", thinking.thinking)
							.put("signature", signature);
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

	private static void anthropicReadSse(
			AssistantMessageEventStream stream,
			SseReader reader,
			Model model,
			AssistantMessage output,
			StreamOptions options)
			throws IOException {
		Map<Integer, AnthropicProvider.ToolCallAccumulator> tools = new LinkedHashMap<>();
		SseReader.SseEvent sse;
		while ((sse = nextSseEvent(reader)) != null) {
			if (isAborted(options)) {
				abortStream(stream, output);
				return;
			}
			JsonNode event = Json.MAPPER.readTree(sse.data);
			if (event == null) {
				continue;
			}
			switch (sse.event) {
				case "message_start" -> anthropicReadMessageStart(event, output);
				case "content_block_start" -> anthropicStartContent(stream, output, event, tools);
				case "content_block_delta" -> anthropicContentDelta(stream, output, event, tools);
				case "content_block_stop" -> anthropicStopContent(stream, output, event, tools);
				case "message_delta" -> anthropicReadMessageDelta(event, output);
				case "message_stop" -> {
					anthropicFinishUnstoppedTools(stream, output, tools);
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
					// ping and unknown future events do not affect the public stream.
				}
			}
		}
		throw new IOException("Anthropic stream ended before message_stop");
	}

	private static void anthropicReadMessageStart(JsonNode event, AssistantMessage output) {
		JsonNode message = event.path("message");
		output.responseId = message.path("id").asText(null);
		output.responseModel = message.path("model").asText(null);
		anthropicReadUsage(message.path("usage"), output);
	}

	private static void anthropicStartContent(
			AssistantMessageEventStream stream,
			AssistantMessage output,
			JsonNode event,
			Map<Integer, AnthropicProvider.ToolCallAccumulator> tools) {
		int index = event.path("index").asInt();
		JsonNode block = event.path("content_block");
		switch (block.path("type").asText()) {
			case "text" -> {
				ensureContentIndex(output, index, textContent(""));
				push(stream, new AssistantMessageEvent.TextStart(index, output));
			}
			case "thinking" -> {
				ensureContentIndex(
						output, index, thinkingContent("", anthropicNonBlankText(block, "signature"), false));
				push(stream, new AssistantMessageEvent.ThinkingStart(index, output));
			}
			case "tool_use" -> {
				AnthropicProvider.ToolCallAccumulator tool = new AnthropicProvider.ToolCallAccumulator(index);
				tool.id = block.path("id").asText();
				tool.name = block.path("name").asText();
				tools.put(index, tool);
				ensureContentIndex(output, index, toolCall(tool.id, tool.name, jsonObject()));
				push(stream, new AssistantMessageEvent.ToolCallStart(index, output));
			}
			default -> {
				// Anthropic may introduce non-user-visible block types.
			}
		}
	}

	private static void anthropicContentDelta(
			AssistantMessageEventStream stream,
			AssistantMessage output,
			JsonNode event,
			Map<Integer, AnthropicProvider.ToolCallAccumulator> tools) {
		int index = event.path("index").asInt();
		JsonNode delta = event.path("delta");
		switch (delta.path("type").asText()) {
			case "text_delta" -> {
				TextContent current = (TextContent) output.content.get(index);
				String text = delta.path("text").asText();
				output.content.set(index, withText(current, current.text + text));
				push(stream, new AssistantMessageEvent.TextDelta(index, text, output));
			}
			case "thinking_delta" -> {
				ThinkingContent current = (ThinkingContent) output.content.get(index);
				String thinking = delta.path("thinking").asText();
				output.content.set(index, withThinking(current, current.thinking + thinking));
				push(stream, new AssistantMessageEvent.ThinkingDelta(index, thinking, output));
			}
			case "signature_delta" -> {
				ThinkingContent current = (ThinkingContent) output.content.get(index);
				String signature = delta.path("signature").asText();
				if (!signature.isEmpty()) {
					String previous = current.thinkingSignature;
					output.content.set(index, withSignature(current, (previous == null ? "" : previous) + signature));
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
				// Unknown future delta types have no public event.
			}
		}
	}

	private static String anthropicNonBlankText(JsonNode object, String field) {
		JsonNode value = object.get(field);
		return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
	}

	private static void anthropicStopContent(
			AssistantMessageEventStream stream,
			AssistantMessage output,
			JsonNode event,
			Map<Integer, AnthropicProvider.ToolCallAccumulator> tools)
			throws IOException {
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

	private static void anthropicFinishUnstoppedTools(
			AssistantMessageEventStream stream,
			AssistantMessage output,
			Map<Integer, AnthropicProvider.ToolCallAccumulator> tools)
			throws IOException {
		for (int index : List.copyOf(tools.keySet())) {
			if (output.content.get(index) instanceof ToolCall) {
				anthropicFinishTool(stream, output, index, tools);
			}
		}
	}

	private static void anthropicFinishTool(
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
		ToolCall completed = toolCall(tool.id, tool.name, arguments);
		output.content.set(index, completed);
		push(stream, new AssistantMessageEvent.ToolCallEnd(index, completed, output));
	}

	private static void anthropicReadMessageDelta(JsonNode event, AssistantMessage output) {
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

	private static void anthropicReadUsage(JsonNode usage, AssistantMessage output) {
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

	// ---------------------------------------------------------------- google

	/** Creates a Google Generative AI provider with an unmodifiable model list. */
	public static GoogleProvider googleProvider(List<Model> models) {
		return new GoogleProvider(List.copyOf(models));
	}

	public static AssistantMessageEventStream googleStream(
			GoogleProvider provider, Model model, Context context, StreamOptions options) {
		if (!model.api.equals(GoogleProvider.API)) {
			throw new IllegalArgumentException("Model " + model + " is not a Google Generative AI model");
		}
		AssistantMessageEventStream stream = new AssistantMessageEventStream();
		StreamOptions requestOptions = options != null ? options : new StreamOptions();
		Thread.startVirtualThread(() -> googleProduce(stream, model, context, requestOptions));
		return stream;
	}

	private static void googleProduce(
			AssistantMessageEventStream stream, Model model, Context context, StreamOptions options) {
		AssistantMessage output = new AssistantMessage(model.api, model.provider, model.id);
		try {
			String key = options.apiKey;
			if (key == null || key.isBlank()) {
				key = resolveSystemApiKey("google").orElse(null);
			}
			if (key == null || key.isBlank()) {
				throw new IllegalStateException("No API key for provider: google");
			}
			String url = model.baseUrl + "/models/" + encodeUrlPathSegment(model.id)
					+ ":streamGenerateContent?alt=sse&key=" + URLEncoder.encode(key, StandardCharsets.UTF_8);
			Map<String, String> headers = new LinkedHashMap<>(model.headers);
			headers.putAll(options.headers);
			HttpTransport.Response response = httpPostJson(
					url,
					headers,
					Json.MAPPER.writeValueAsBytes(googleRequestBody(model, context, options)),
					options.timeoutMs,
					options.signal);
			try {
				SseReader reader = sseReader(response.body);
				try {
					push(stream, new AssistantMessageEvent.Start(output));
					googleReadSse(stream, reader, model, output, options);
				} finally {
					closeSseReader(reader);
				}
			} finally {
				closeHttpResponse(response);
			}
		} catch (Exception e) {
			output.stopReason = isAborted(options) ? StopReason.ABORTED : StopReason.ERROR;
			output.errorMessage = isAborted(options) ? "Request was aborted" : displayError(e);
			push(stream, new AssistantMessageEvent.Error(output.stopReason, output));
		}
	}

	private static ObjectNode googleRequestBody(Model model, Context context, StreamOptions options) {
		ObjectNode request = jsonObject();
		if (context.systemPrompt != null && !context.systemPrompt.isBlank()) {
			request.putObject("systemInstruction").putArray("parts").addObject().put("text", context.systemPrompt);
		}
		ArrayNode contents = request.putArray("contents");
		for (Message message : context.messages) {
			googleAppendMessage(contents, message);
		}
		ObjectNode generation = request.putObject("generationConfig");
		if (options.temperature != null) {
			generation.put("temperature", options.temperature);
		}
		if (options.maxTokens != null) {
			generation.put("maxOutputTokens", options.maxTokens);
		}
		if (options.reasoning != null && options.reasoning != ThinkingLevel.OFF) {
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
		return request;
	}

	private static void googleAppendMessage(ArrayNode contents, Message message) {
		switch (message) {
			case UserMessage user -> googleAppendUser(contents.addObject().put("role", "user"), user.content);
			case AssistantMessage assistant -> {
				ArrayNode parts = contents.addObject().put("role", "model").putArray("parts");
				for (AssistantContent block : assistant.content) {
					if (block instanceof TextContent text) {
						parts.addObject().put("text", text.text);
					} else if (block instanceof ThinkingContent thinking) {
						ObjectNode part = parts.addObject().put("text", thinking.thinking).put("thought", true);
						if (thinking.thinkingSignature != null) {
							part.put("thoughtSignature", thinking.thinkingSignature);
						}
					} else if (block instanceof ToolCall call) {
						ObjectNode function = parts.addObject().putObject("functionCall");
						function.put("name", call.name);
						function.set("args", call.arguments);
						function.put("id", call.id);
					}
				}
			}
			case ToolResultMessage result -> {
				ObjectNode function = contents.addObject()
						.put("role", "user")
						.putArray("parts")
						.addObject()
						.putObject("functionResponse");
				function.put("name", result.toolName);
				function.put("id", result.toolCallId);
				function.putObject("response").put(result.isError ? "error" : "output", text(result));
			}
		}
	}

	private static void googleAppendUser(ObjectNode target, List<UserContent> content) {
		ArrayNode parts = target.putArray("parts");
		for (UserContent block : content) {
			if (block instanceof TextContent text) {
				parts.addObject().put("text", text.text);
			} else if (block instanceof ImageContent image) {
				parts.addObject().putObject("inlineData").put("mimeType", image.mimeType).put("data", image.data);
			}
		}
	}

	private static void googleReadSse(
			AssistantMessageEventStream stream,
			SseReader reader,
			Model model,
			AssistantMessage output,
			StreamOptions options)
			throws IOException {
		SseReader.SseEvent sse;
		int toolCounter = 0;
		while ((sse = nextSseEvent(reader)) != null) {
			if (isAborted(options)) {
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
						int index = googleEnsureThinking(stream, output);
						ThinkingContent current = (ThinkingContent) output.content.get(index);
						output.content.set(
								index,
								thinkingContent(
										current.thinking + text,
										part.path("thoughtSignature").asText(current.thinkingSignature),
										false));
						push(stream, new AssistantMessageEvent.ThinkingDelta(index, text, output));
					} else {
						int index = googleEnsureText(stream, output);
						TextContent current = (TextContent) output.content.get(index);
						output.content.set(
								index,
								textContent(
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
					ToolCall call = toolCall(id, name, arguments, part.path("thoughtSignature").asText(null));
					output.content.add(call);
					push(stream, new AssistantMessageEvent.ToolCallStart(index, output));
					push(stream, new AssistantMessageEvent.ToolCallDelta(index, arguments.toString(), output));
					push(stream, new AssistantMessageEvent.ToolCallEnd(index, call, output));
				}
			}
			googleReadUsage(chunk.path("usageMetadata"), output);
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
		googleCloseOpenBlocks(stream, output);
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
	}

	private static int googleEnsureText(AssistantMessageEventStream stream, AssistantMessage output) {
		if (!output.content.isEmpty() && output.content.getLast() instanceof TextContent) {
			return output.content.size() - 1;
		}
		int index = output.content.size();
		output.content.add(textContent(""));
		push(stream, new AssistantMessageEvent.TextStart(index, output));
		return index;
	}

	private static int googleEnsureThinking(AssistantMessageEventStream stream, AssistantMessage output) {
		if (!output.content.isEmpty() && output.content.getLast() instanceof ThinkingContent) {
			return output.content.size() - 1;
		}
		int index = output.content.size();
		output.content.add(thinkingContent(""));
		push(stream, new AssistantMessageEvent.ThinkingStart(index, output));
		return index;
	}

	private static void googleCloseOpenBlocks(AssistantMessageEventStream stream, AssistantMessage output) {
		for (int index = 0; index < output.content.size(); index++) {
			AssistantContent block = output.content.get(index);
			if (block instanceof TextContent text) {
				push(stream, new AssistantMessageEvent.TextEnd(index, text.text, output));
			} else if (block instanceof ThinkingContent thinking) {
				push(stream, new AssistantMessageEvent.ThinkingEnd(index, thinking.thinking, output));
			}
		}
	}

	private static void googleReadUsage(JsonNode usage, AssistantMessage output) {
		if (!usage.isObject()) {
			return;
		}
		long cacheRead = usage.path("cachedContentTokenCount").asLong();
		output.usage.input = usage.path("promptTokenCount").asLong() - cacheRead;
		output.usage.output =
				usage.path("candidatesTokenCount").asLong() + usage.path("thoughtsTokenCount").asLong();
		output.usage.cacheRead = cacheRead;
		output.usage.reasoning = usage.path("thoughtsTokenCount").asLong();
		output.usage.totalTokens =
				usage.path("totalTokenCount").asLong(output.usage.input + output.usage.output + cacheRead);
	}

	// ------------------------------------------------- openai chat completions

	/** Validates the identity and endpoint of a Chat Completions-compatible service. */
	public static OpenAiCompatibleProvider openAiCompatibleProvider(
			String id, String name, String baseUrl, List<Model> models) {
		return new OpenAiCompatibleProvider(
				requireNonBlank(id, "id"),
				requireNonBlank(name, "name"),
				trimTrailingSlash(requireNonBlank(baseUrl, "baseUrl")),
				List.copyOf(models));
	}

	public static AssistantMessageEventStream openAiCompatibleStream(
			OpenAiCompatibleProvider provider, Model model, Context context, StreamOptions options) {
		if (!model.api.equals(OpenAiCompatibleProvider.API)) {
			throw new IllegalArgumentException("Model " + model + " is not a Chat Completions model");
		}
		AssistantMessageEventStream stream = new AssistantMessageEventStream();
		StreamOptions requestOptions = options != null ? options : new StreamOptions();
		Thread.startVirtualThread(() -> openAiCompatibleProduce(provider, stream, model, context, requestOptions));
		return stream;
	}

	private static void openAiCompatibleProduce(
			OpenAiCompatibleProvider provider,
			AssistantMessageEventStream stream,
			Model model,
			Context context,
			StreamOptions options) {
		AssistantMessage output = new AssistantMessage(model.api, model.provider, model.id);
		try {
			Map<String, String> headers = openAiCompatibleRequestHeaders(provider, model, options);
			ObjectNode request = openAiCompatibleRequestBody(model, context, options);
			HttpTransport.Response response = httpPostJson(
					openAiCompatibleBaseUrl(model, options) + "/chat/completions",
					headers,
					Json.MAPPER.writeValueAsBytes(request),
					options.timeoutMs,
					options.signal);
			try {
				SseReader reader = sseReader(response.body);
				try {
					push(stream, new AssistantMessageEvent.Start(output));
					openAiCompatibleReadSse(stream, reader, model, output, options);
				} finally {
					closeSseReader(reader);
				}
			} finally {
				closeHttpResponse(response);
			}
		} catch (Exception e) {
			output.stopReason = isAborted(options) ? StopReason.ABORTED : StopReason.ERROR;
			output.errorMessage = isAborted(options) ? "Request was aborted" : displayError(e);
			push(stream, new AssistantMessageEvent.Error(output.stopReason, output));
		}
	}

	private static Map<String, String> openAiCompatibleRequestHeaders(
			OpenAiCompatibleProvider provider, Model model, StreamOptions options) {
		Map<String, String> headers = new LinkedHashMap<>(model.headers);
		headers.putAll(options.headers);
		String key = options.apiKey;
		if (key == null || key.isBlank()) {
			key = resolveSystemApiKey(provider.id).orElse(null);
		}
		if (!headers.containsKey("Authorization") && !headers.containsKey("authorization")) {
			if (key == null || key.isBlank()) {
				throw new IllegalStateException("No API key for provider: " + provider.id);
			}
			headers.put("Authorization", "Bearer " + key);
		}
		return headers;
	}

	private static String openAiCompatibleBaseUrl(Model model, StreamOptions options) {
		return options.baseUrl == null || options.baseUrl.isBlank()
				? model.baseUrl
				: trimTrailingSlash(options.baseUrl);
	}

	private static ObjectNode openAiCompatibleRequestBody(Model model, Context context, StreamOptions options) {
		ObjectNode request = jsonObject();
		request.put("model", model.id);
		request.put("stream", true);
		request.putObject("stream_options").put("include_usage", true);
		if (options.temperature != null) {
			request.put("temperature", options.temperature);
		}
		if (options.maxTokens != null) {
			request.put("max_completion_tokens", options.maxTokens);
		}
		String reasoning = providerThinkingLevel(model, options.reasoning);
		if (reasoning != null) {
			request.put("reasoning_effort", reasoning);
		}
		ArrayNode messages = request.putArray("messages");
		if (context.systemPrompt != null && !context.systemPrompt.isBlank()) {
			messages.addObject().put("role", "system").put("content", context.systemPrompt);
		}
		for (Message message : context.messages) {
			openAiCompatibleAppendMessage(messages, message);
		}
		if (!context.tools.isEmpty()) {
			ArrayNode tools = request.putArray("tools");
			for (Tool tool : context.tools) {
				ObjectNode function = tools.addObject().put("type", "function").putObject("function");
				function.put("name", tool.name);
				function.put("description", tool.description);
				function.set("parameters", tool.parameters);
			}
		}
		return request;
	}

	private static void openAiCompatibleAppendMessage(ArrayNode messages, Message message) {
		switch (message) {
			case UserMessage user -> {
				ObjectNode target = messages.addObject().put("role", "user");
				openAiCompatibleAppendUserContent(target, user.content);
			}
			case AssistantMessage assistant -> openAiCompatibleAppendAssistantMessage(messages, assistant);
			case ToolResultMessage toolResult -> messages.addObject()
					.put("role", "tool")
					.put("tool_call_id", toolResult.toolCallId)
					.put("content", text(toolResult));
		}
	}

	private static void openAiCompatibleAppendUserContent(ObjectNode target, List<UserContent> content) {
		boolean hasImage = content.stream().anyMatch(ImageContent.class::isInstance);
		if (!hasImage) {
			target.put(
					"content",
					content.stream()
							.filter(TextContent.class::isInstance)
							.map(TextContent.class::cast)
							.map(block -> block.text)
							.reduce("", String::concat));
			return;
		}
		ArrayNode parts = target.putArray("content");
		for (UserContent block : content) {
			if (block instanceof TextContent text) {
				parts.addObject().put("type", "text").put("text", text.text);
			} else if (block instanceof ImageContent image) {
				parts.addObject()
						.put("type", "image_url")
						.putObject("image_url")
						.put("url", "data:" + image.mimeType + ";base64," + image.data);
			}
		}
	}

	private static void openAiCompatibleAppendAssistantMessage(ArrayNode messages, AssistantMessage message) {
		ObjectNode target = messages.addObject().put("role", "assistant");
		String text = text(message);
		target.put("content", text.isEmpty() ? "" : text);
		ArrayNode toolCalls = null;
		for (AssistantContent block : message.content) {
			if (block instanceof ToolCall call) {
				if (toolCalls == null) {
					toolCalls = target.putArray("tool_calls");
				}
				ObjectNode function = toolCalls
						.addObject()
						.put("id", call.id)
						.put("type", "function")
						.putObject("function");
				function.put("name", call.name);
				function.put("arguments", call.arguments.toString());
			}
		}
	}

	private static void openAiCompatibleReadSse(
			AssistantMessageEventStream stream,
			SseReader reader,
			Model model,
			AssistantMessage output,
			StreamOptions options)
			throws IOException {
		Map<Integer, OpenAiCompatibleProvider.ToolCallAccumulator> tools = new LinkedHashMap<>();
		SseReader.SseEvent event;
		while ((event = nextSseEvent(reader)) != null) {
			if (isAborted(options)) {
				output.stopReason = StopReason.ABORTED;
				output.errorMessage = "Request was aborted";
				push(stream, new AssistantMessageEvent.Error(StopReason.ABORTED, output));
				return;
			}
			if (event.data.equals("[DONE]")) {
				break;
			}
			JsonNode chunk = Json.MAPPER.readTree(event.data);
			if (chunk == null) {
				continue;
			}
			openAiCompatibleReadUsage(chunk, output);
			JsonNode choices = chunk.path("choices");
			if (!choices.isArray() || choices.isEmpty()) {
				continue;
			}
			JsonNode choice = choices.get(0);
			JsonNode delta = choice.path("delta");
			if (!delta.isMissingNode()) {
				openAiCompatibleReadContentDelta(stream, output, delta);
				openAiCompatibleReadThinkingDelta(stream, output, delta);
				openAiCompatibleReadToolCallDeltas(stream, output, delta.path("tool_calls"), tools);
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
		openAiCompatibleFinishToolCalls(stream, output, tools);
		if (output.stopReason == StopReason.PENDING) {
			output.stopReason = toolCalls(output).isEmpty() ? StopReason.STOP : StopReason.TOOL_USE;
		}
		calculateCost(model, output.usage);
		push(stream, new AssistantMessageEvent.Done(output.stopReason, output));
	}

	private static void openAiCompatibleReadContentDelta(
			AssistantMessageEventStream stream, AssistantMessage output, JsonNode delta) {
		if (!delta.path("content").isTextual()) {
			return;
		}
		String text = delta.path("content").asText();
		int index = lastContentIndex(output, TextContent.class);
		if (index == -1) {
			index = output.content.size();
			output.content.add(textContent(""));
			push(stream, new AssistantMessageEvent.TextStart(index, output));
		}
		TextContent content = (TextContent) output.content.get(index);
		output.content.set(index, withText(content, content.text + text));
		push(stream, new AssistantMessageEvent.TextDelta(index, text, output));
	}

	private static void openAiCompatibleReadThinkingDelta(
			AssistantMessageEventStream stream, AssistantMessage output, JsonNode delta) {
		JsonNode value = delta.has("reasoning_content") ? delta.path("reasoning_content") : delta.path("reasoning");
		if (!value.isTextual()) {
			return;
		}
		String thinking = value.asText();
		int index = lastContentIndex(output, ThinkingContent.class);
		if (index == -1) {
			index = output.content.size();
			output.content.add(thinkingContent(""));
			push(stream, new AssistantMessageEvent.ThinkingStart(index, output));
		}
		ThinkingContent content = (ThinkingContent) output.content.get(index);
		output.content.set(index, withThinking(content, content.thinking + thinking));
		push(stream, new AssistantMessageEvent.ThinkingDelta(index, thinking, output));
	}

	private static void openAiCompatibleReadToolCallDeltas(
			AssistantMessageEventStream stream,
			AssistantMessage output,
			JsonNode deltas,
			Map<Integer, OpenAiCompatibleProvider.ToolCallAccumulator> tools) {
		if (!deltas.isArray()) {
			return;
		}
		for (JsonNode delta : deltas) {
			int wireIndex = delta.path("index").asInt();
			OpenAiCompatibleProvider.ToolCallAccumulator accumulator =
					tools.computeIfAbsent(wireIndex, OpenAiCompatibleProvider.ToolCallAccumulator::new);
			accumulator.rawDeltas.add(delta.deepCopy());

			JsonNode id = delta.get("id");
			if (openAiFragmentHasValue(id)) {
				accumulator.hasMeaningfulData = true;
			}
			if (id != null && id.isTextual()) {
				accumulator.id = id.asText();
			}

			JsonNode function = delta.get("function");
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
				output.content.add(toolCall(accumulator.id, accumulator.name, jsonObject()));
				push(stream, new AssistantMessageEvent.ToolCallStart(accumulator.contentIndex, output));
			}
			if (accumulator.contentIndex != -1) {
				output.content.set(
						accumulator.contentIndex, toolCall(accumulator.id, accumulator.name, jsonObject()));
				if (argumentFragment != null) {
					push(
							stream,
							new AssistantMessageEvent.ToolCallDelta(
									accumulator.contentIndex, argumentFragment, output));
				}
			}
		}
	}

	private static boolean openAiFragmentHasValue(JsonNode fragment) {
		return fragment != null && !fragment.isNull() && (!fragment.isTextual() || !fragment.asText().isBlank());
	}

	private static void openAiCompatibleFinishToolCalls(
			AssistantMessageEventStream stream,
			AssistantMessage output,
			Map<Integer, OpenAiCompatibleProvider.ToolCallAccumulator> accumulators)
			throws IOException {
		for (OpenAiCompatibleProvider.ToolCallAccumulator accumulator : accumulators.values()) {
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
			ToolCall call = toolCall(accumulator.id, accumulator.name, arguments);
			output.content.set(accumulator.contentIndex, call);
			push(stream, new AssistantMessageEvent.ToolCallEnd(accumulator.contentIndex, call, output));
		}
	}

	private static IOException invalidOpenAiToolCall(
			OpenAiCompatibleProvider.ToolCallAccumulator accumulator, IOException cause) {
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

	private static void openAiCompatibleReadUsage(JsonNode chunk, AssistantMessage output) {
		JsonNode usage = chunk.path("usage");
		if (!usage.isObject()) {
			return;
		}
		output.usage.input = usage.path("prompt_tokens").asLong(output.usage.input);
		output.usage.output = usage.path("completion_tokens").asLong(output.usage.output);
		output.usage.totalTokens = usage.path("total_tokens").asLong(output.usage.input + output.usage.output);
		JsonNode details = usage.path("prompt_tokens_details");
		if (details.path("cached_tokens").isIntegralNumber()) {
			output.usage.cacheRead = details.path("cached_tokens").asLong();
			output.usage.input -= output.usage.cacheRead;
		}
	}

	// ------------------------------------------------------ openai responses

	public static OpenAiResponsesProvider openAiResponsesProvider(List<Model> models) {
		return openAiResponsesProvider(
				"openai",
				"OpenAI",
				models,
				List.of("OPENAI_API_KEY"),
				null,
				OpenAiResponsesProvider.RequestProfile.STANDARD);
	}

	public static OpenAiResponsesProvider openAiResponsesProvider(List<Model> models, CredentialStore credentials) {
		return openAiResponsesProvider(
				"openai",
				"OpenAI",
				models,
				List.of("OPENAI_API_KEY"),
				credentials,
				OpenAiResponsesProvider.RequestProfile.STANDARD);
	}

	public static OpenAiResponsesProvider openAiResponsesProvider(
			String id, String name, List<Model> models, List<String> apiKeyEnvVars) {
		return openAiResponsesProvider(
				id, name, models, apiKeyEnvVars, null, OpenAiResponsesProvider.RequestProfile.STANDARD);
	}

	public static OpenAiResponsesProvider openAiResponsesProvider(
			String id, String name, List<Model> models, List<String> apiKeyEnvVars, CredentialStore credentials) {
		return openAiResponsesProvider(
				id, name, models, apiKeyEnvVars, credentials, OpenAiResponsesProvider.RequestProfile.STANDARD);
	}

	/** Creates a Responses provider with unmodifiable model and env-var lists. */
	public static OpenAiResponsesProvider openAiResponsesProvider(
			String id,
			String name,
			List<Model> models,
			List<String> apiKeyEnvVars,
			CredentialStore credentials,
			OpenAiResponsesProvider.RequestProfile requestProfile) {
		return new OpenAiResponsesProvider(
				id, name, List.copyOf(models), List.copyOf(apiKeyEnvVars), credentials, requestProfile);
	}

	/** Codex-profile Responses provider used by the ChatGPT subscription adapter. */
	public static OpenAiResponsesProvider codexResponsesProvider(String id, String name, List<Model> models) {
		return openAiResponsesProvider(
				id, name, models, List.of(), null, OpenAiResponsesProvider.RequestProfile.CODEX);
	}

	public static AssistantMessageEventStream openAiResponsesStream(
			OpenAiResponsesProvider provider, Model model, Context context, StreamOptions options) {
		if (!model.api.equals(OpenAiResponsesProvider.API)) {
			throw new IllegalArgumentException("Model " + model + " is not an OpenAI Responses model");
		}
		AssistantMessageEventStream stream = new AssistantMessageEventStream();
		StreamOptions requestOptions = options != null ? options : new StreamOptions();
		Thread.startVirtualThread(() -> openAiResponsesProduce(provider, stream, model, context, requestOptions));
		return stream;
	}

	private static void openAiResponsesProduce(
			OpenAiResponsesProvider provider,
			AssistantMessageEventStream stream,
			Model model,
			Context context,
			StreamOptions options) {
		AssistantMessage output = new AssistantMessage(model.api, model.provider, model.id);
		try {
			ObjectNode request = openAiResponsesRequestBody(provider, model, context, options);
			HttpTransport.Response response = httpPostJson(
					providerBaseUrl(model, options) + "/responses",
					openAiResponsesRequestHeaders(provider, model, options),
					Json.MAPPER.writeValueAsBytes(request),
					options.timeoutMs,
					options.signal);
			try {
				SseReader reader = sseReader(response.body);
				try {
					push(stream, new AssistantMessageEvent.Start(output));
					openAiResponsesReadSse(stream, reader, model, output, options);
				} finally {
					closeSseReader(reader);
				}
			} finally {
				closeHttpResponse(response);
			}
		} catch (Exception e) {
			output.stopReason = isAborted(options) ? StopReason.ABORTED : StopReason.ERROR;
			output.errorMessage = isAborted(options) ? "Request was aborted" : displayError(e);
			push(stream, new AssistantMessageEvent.Error(output.stopReason, output));
		}
	}

	private static Map<String, String> openAiResponsesRequestHeaders(
			OpenAiResponsesProvider provider, Model model, StreamOptions options) throws IOException {
		Map<String, String> headers = new LinkedHashMap<>(model.headers);
		headers.putAll(options.headers);
		if (!headers.containsKey("Authorization") && !headers.containsKey("authorization")) {
			String key = options.apiKey;
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
		return headers;
	}

	private static ObjectNode openAiResponsesRequestBody(
			OpenAiResponsesProvider provider, Model model, Context context, StreamOptions options) {
		boolean codex = provider.requestProfile == OpenAiResponsesProvider.RequestProfile.CODEX;
		ObjectNode request = jsonObject();
		request.put("model", model.id);
		request.put("stream", true);
		request.put("store", false);
		if (context.systemPrompt != null && !context.systemPrompt.isBlank()) {
			request.put("instructions", context.systemPrompt);
		} else if (codex) {
			request.put("instructions", "You are a helpful assistant.");
		}
		if (codex) {
			request.putObject("text").put("verbosity", "low");
			request.put("tool_choice", "auto");
			request.put("parallel_tool_calls", true);
			if (options.sessionId != null && !options.sessionId.isBlank()) {
				request.put("prompt_cache_key", options.sessionId);
			}
		}
		if (options.maxTokens != null && !codex) {
			request.put("max_output_tokens", Math.max(16, options.maxTokens));
		}
		if (options.temperature != null) {
			request.put("temperature", options.temperature);
		}
		String reasoning = providerThinkingLevel(model, options.reasoning);
		if (reasoning != null) {
			String summary = codex ? "detailed" : "auto";
			request.putObject("reasoning").put("effort", reasoning).put("summary", summary);
			request.putArray("include").add("reasoning.encrypted_content");
		}
		ArrayNode input = request.putArray("input");
		for (Message message : context.messages) {
			openAiResponsesAppendMessage(input, message);
		}
		if (!context.tools.isEmpty()) {
			ArrayNode tools = request.putArray("tools");
			for (Tool tool : context.tools) {
				ObjectNode target = tools.addObject().put("type", "function");
				target.put("name", tool.name);
				target.put("description", tool.description);
				target.set("parameters", tool.parameters);
			}
		}
		return request;
	}

	private static void openAiResponsesAppendMessage(ArrayNode input, Message message) {
		switch (message) {
			case UserMessage user -> {
				ObjectNode target = input.addObject().put("role", "user");
				ArrayNode content = target.putArray("content");
				for (UserContent block : user.content) {
					if (block instanceof TextContent text) {
						content.addObject().put("type", "input_text").put("text", text.text);
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
					if (block instanceof ThinkingContent thinking && thinking.thinkingSignature != null) {
						try {
							JsonNode reasoningItem = Json.MAPPER.readTree(thinking.thinkingSignature);
							if (reasoningItem != null
									&& reasoningItem.isObject()
									&& reasoningItem.path("type").asText().equals("reasoning")) {
								input.add(reasoningItem);
							}
						} catch (IOException ignored) {
							// Signatures from another provider are not OpenAI response items.
						}
					} else if (block instanceof TextContent text) {
						if (target == null) {
							target = input.addObject().put("role", "assistant");
							content = target.putArray("content");
						}
						content.addObject().put("type", "output_text").put("text", text.text);
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

	private static void openAiResponsesReadSse(
			AssistantMessageEventStream stream,
			SseReader reader,
			Model model,
			AssistantMessage output,
			StreamOptions options)
			throws IOException {
		Map<String, OpenAiResponsesProvider.OutputItem> items = new HashMap<>();
		SseReader.SseEvent sse;
		while ((sse = nextSseEvent(reader)) != null) {
			if (isAborted(options)) {
				abortStream(stream, output);
				return;
			}
			JsonNode event = Json.MAPPER.readTree(sse.data);
			if (event == null) {
				continue;
			}
			switch (event.path("type").asText()) {
				case "response.created" -> output.responseId = event.path("response").path("id").asText(null);
				case "response.output_item.added" -> openAiResponsesStartItem(stream, output, event, items);
				case "response.output_text.delta", "response.refusal.delta" ->
					openAiResponsesTextDelta(stream, output, event, items);
				case "response.reasoning_text.delta", "response.reasoning_summary_text.delta" ->
					openAiResponsesThinkingDelta(stream, output, event, items);
				case "response.reasoning_summary_part.done" ->
					openAiResponsesThinkingPartDone(stream, output, event, items);
				case "response.function_call_arguments.delta" ->
					openAiResponsesToolDelta(stream, output, event, items);
				case "response.function_call_arguments.done" ->
					openAiResponsesFinishTool(stream, output, event, items);
				case "response.output_item.done" -> openAiResponsesFinishItem(stream, output, event, items);
				case "response.completed", "response.incomplete" -> {
					JsonNode response = event.path("response");
					openAiResponsesReadCompletion(response, model, output);
					openAiResponsesFinalizeReasoningSignatures(response.path("output"), output);
					openAiResponsesFinishRemainingItems(stream, output, items);
					push(stream, new AssistantMessageEvent.Done(output.stopReason, output));
					return;
				}
				case "response.failed", "error" ->
					throw new IOException(
							event.path("error").path("message").asText("OpenAI Responses stream error"));
				default -> {
					// Other emitted event types do not change the normalized stream.
				}
			}
		}
		throw new IOException("OpenAI Responses stream ended without completion");
	}

	private static void openAiResponsesStartItem(
			AssistantMessageEventStream stream,
			AssistantMessage output,
			JsonNode event,
			Map<String, OpenAiResponsesProvider.OutputItem> items) {
		JsonNode item = event.path("item");
		String itemId = openAiResponsesItemKey(event);
		String type = item.path("type").asText();
		int contentIndex = output.content.size();
		OpenAiResponsesProvider.OutputItem outputItem =
				new OpenAiResponsesProvider.OutputItem(contentIndex, type);
		items.put(itemId, outputItem);
		switch (type) {
			case "message" -> {
				output.content.add(textContent(""));
				push(stream, new AssistantMessageEvent.TextStart(contentIndex, output));
			}
			case "reasoning" -> {
				output.content.add(thinkingContent(""));
				push(stream, new AssistantMessageEvent.ThinkingStart(contentIndex, output));
			}
			case "function_call" -> {
				outputItem.callId = item.path("call_id").asText();
				outputItem.name = item.path("name").asText();
				output.content.add(toolCall(outputItem.callId, outputItem.name, jsonObject()));
				push(stream, new AssistantMessageEvent.ToolCallStart(contentIndex, output));
			}
			default -> items.remove(itemId);
		}
	}

	private static void openAiResponsesTextDelta(
			AssistantMessageEventStream stream,
			AssistantMessage output,
			JsonNode event,
			Map<String, OpenAiResponsesProvider.OutputItem> items) {
		OpenAiResponsesProvider.OutputItem item = items.get(openAiResponsesItemKey(event));
		if (item == null || !item.type.equals("message")) {
			return;
		}
		String delta = event.path("delta").asText();
		TextContent current = (TextContent) output.content.get(item.contentIndex);
		output.content.set(item.contentIndex, withText(current, current.text + delta));
		push(stream, new AssistantMessageEvent.TextDelta(item.contentIndex, delta, output));
	}

	private static void openAiResponsesThinkingDelta(
			AssistantMessageEventStream stream,
			AssistantMessage output,
			JsonNode event,
			Map<String, OpenAiResponsesProvider.OutputItem> items) {
		openAiResponsesAppendThinkingDelta(
				stream, output, items.get(openAiResponsesItemKey(event)), event.path("delta").asText());
	}

	private static void openAiResponsesThinkingPartDone(
			AssistantMessageEventStream stream,
			AssistantMessage output,
			JsonNode event,
			Map<String, OpenAiResponsesProvider.OutputItem> items) {
		OpenAiResponsesProvider.OutputItem item = items.get(openAiResponsesItemKey(event));
		if (item == null || !item.type.equals("reasoning")) {
			return;
		}
		ThinkingContent current = (ThinkingContent) output.content.get(item.contentIndex);
		if (!current.thinking.isEmpty() && !current.thinking.endsWith("\n\n")) {
			openAiResponsesAppendThinkingDelta(stream, output, item, "\n\n");
		}
	}

	private static void openAiResponsesAppendThinkingDelta(
			AssistantMessageEventStream stream,
			AssistantMessage output,
			OpenAiResponsesProvider.OutputItem item,
			String delta) {
		if (item == null || !item.type.equals("reasoning") || delta.isEmpty()) {
			return;
		}
		ThinkingContent current = (ThinkingContent) output.content.get(item.contentIndex);
		output.content.set(item.contentIndex, withThinking(current, current.thinking + delta));
		push(stream, new AssistantMessageEvent.ThinkingDelta(item.contentIndex, delta, output));
	}

	private static void openAiResponsesToolDelta(
			AssistantMessageEventStream stream,
			AssistantMessage output,
			JsonNode event,
			Map<String, OpenAiResponsesProvider.OutputItem> items) {
		OpenAiResponsesProvider.OutputItem item = items.get(openAiResponsesItemKey(event));
		if (item == null || !item.type.equals("function_call")) {
			return;
		}
		String delta = event.path("delta").asText();
		item.arguments.append(delta);
		push(stream, new AssistantMessageEvent.ToolCallDelta(item.contentIndex, delta, output));
	}

	private static void openAiResponsesFinishTool(
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
		ToolCall call = toolCall(item.callId, item.name, arguments);
		output.content.set(item.contentIndex, call);
		push(stream, new AssistantMessageEvent.ToolCallEnd(item.contentIndex, call, output));
	}

	private static void openAiResponsesFinishItem(
			AssistantMessageEventStream stream,
			AssistantMessage output,
			JsonNode event,
			Map<String, OpenAiResponsesProvider.OutputItem> items)
			throws IOException {
		String itemId = openAiResponsesItemKey(event);
		OpenAiResponsesProvider.OutputItem item = items.get(itemId);
		if (item == null) {
			return;
		}
		if (item.type.equals("message")) {
			TextContent text = (TextContent) output.content.get(item.contentIndex);
			push(stream, new AssistantMessageEvent.TextEnd(item.contentIndex, text.text, output));
			items.remove(itemId);
		} else if (item.type.equals("reasoning")) {
			ThinkingContent thinking = (ThinkingContent) output.content.get(item.contentIndex);
			JsonNode completedItem = event.path("item");
			String completedThinking = openAiResponsesReasoningText(completedItem);
			if (completedThinking.isBlank()) {
				completedThinking = thinking.thinking.stripTrailing();
			}
			String signature = completedItem.isObject() ? completedItem.toString() : thinking.thinkingSignature;
			thinking = thinkingContent(completedThinking, signature, thinking.redacted);
			output.content.set(item.contentIndex, thinking);
			push(stream, new AssistantMessageEvent.ThinkingEnd(item.contentIndex, thinking.thinking, output));
			items.remove(itemId);
		} else if (item.type.equals("function_call")) {
			openAiResponsesFinishTool(stream, output, event, items);
		}
	}

	private static void openAiResponsesFinishRemainingItems(
			AssistantMessageEventStream stream,
			AssistantMessage output,
			Map<String, OpenAiResponsesProvider.OutputItem> items)
			throws IOException {
		for (String itemId : List.copyOf(items.keySet())) {
			OpenAiResponsesProvider.OutputItem item = items.get(itemId);
			if (item.type.equals("message")) {
				TextContent text = (TextContent) output.content.get(item.contentIndex);
				push(stream, new AssistantMessageEvent.TextEnd(item.contentIndex, text.text, output));
				items.remove(itemId);
			} else if (item.type.equals("reasoning")) {
				ThinkingContent thinking = (ThinkingContent) output.content.get(item.contentIndex);
				push(stream, new AssistantMessageEvent.ThinkingEnd(item.contentIndex, thinking.thinking, output));
				items.remove(itemId);
			} else if (item.type.equals("function_call")) {
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

	private static void openAiResponsesFinalizeReasoningSignatures(JsonNode responseItems, AssistantMessage output) {
		if (!responseItems.isArray()) {
			return;
		}
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
				if (openAiResponsesSignatureId(thinking.thinkingSignature)
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
					thinkingContent(
							text.isBlank() ? thinking.thinking : text, responseItem.toString(), thinking.redacted));
		}
	}

	private static String openAiResponsesSignatureId(String signature) {
		if (signature == null) {
			return "";
		}
		try {
			return Json.MAPPER.readTree(signature).path("id").asText();
		} catch (IOException ignored) {
			return "";
		}
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

	private static void openAiResponsesReadCompletion(JsonNode response, Model model, AssistantMessage output) {
		output.responseId = response.path("id").asText(output.responseId);
		output.responseModel = response.path("model").asText(null);
		JsonNode usage = response.path("usage");
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
				? (response.path("status").asText().equals("incomplete") ? StopReason.LENGTH : StopReason.STOP)
				: StopReason.TOOL_USE;
		calculateCost(model, output.usage);
	}

	// ------------------------------------------------------ chatgpt provider

	/** Builds the ChatGPT subscription provider from the OpenAI catalog slice. */
	public static ChatGptProvider newChatGptProvider(List<Model> openAiModels, ChatGptAuth auth) {
		List<Model> models = openAiModels.stream()
				.filter(model -> ChatGptProvider.CODEX_MODEL_IDS.contains(model.id))
				.map(CodingAgentOperations::chatGptSubscriptionModel)
				.toList();
		return new ChatGptProvider(
				models, auth, codexResponsesProvider(ChatGptAuth.PROVIDER_ID, ChatGptProvider.NAME, models));
	}

	private static Model chatGptSubscriptionModel(Model source) {
		Model model = copyModel(source);
		model.provider = ChatGptAuth.PROVIDER_ID;
		model.baseUrl = ChatGptAuth.CODEX_API_BASE_URL.toString();
		model.cost = ModelCost.FREE;
		return model;
	}

	public static AssistantMessageEventStream chatGptStream(
			ChatGptProvider provider, Model model, Context context, StreamOptions options) {
		if (!model.provider.equals(ChatGptAuth.PROVIDER_ID)) {
			throw new IllegalArgumentException("Model " + model + " is not a ChatGPT subscription model");
		}
		StreamOptions requestOptions = options == null ? new StreamOptions() : copyStreamOptions(options);
		try {
			configureCodexRequest(requestOptions, chatGptResolveToken(provider.auth));
			return openAiResponsesStream(provider.responses, model, context, requestOptions);
		} catch (IOException error) {
			return providerErrorStream(model, error);
		}
	}

	public static void configureCodexRequest(StreamOptions options, ChatGptAuth.ChatGptToken token) {
		options.apiKey = token.accessToken;
		options.baseUrl = ChatGptAuth.CODEX_API_BASE_URL.toString();
		options.headers.put("ChatGPT-Account-Id", token.accountId);
		options.headers.put("originator", "pi-java");
		options.headers.put("User-Agent", codexUserAgent());
		options.headers.put("Accept", "text/event-stream");
		options.headers.put("OpenAI-Beta", "responses=experimental");
		if (options.sessionId != null && !options.sessionId.isBlank()) {
			options.headers.put("session-id", options.sessionId);
			options.headers.put("x-client-request-id", options.sessionId);
		}
	}

	private static String codexUserAgent() {
		return "pi-java (" + System.getProperty("os.name", "unknown") + " "
				+ System.getProperty("os.version", "unknown") + "; "
				+ System.getProperty("os.arch", "unknown") + ")";
	}

	private static AssistantMessageEventStream providerErrorStream(Model model, IOException error) {
		AssistantMessageEventStream stream = new AssistantMessageEventStream();
		Thread.startVirtualThread(() -> {
			AssistantMessage message = new AssistantMessage(model.api, model.provider, model.id);
			message.stopReason = StopReason.ERROR;
			message.errorMessage = error.getMessage() == null ? error.toString() : error.getMessage();
			push(stream, new AssistantMessageEvent.Error(StopReason.ERROR, message));
		});
		return stream;
	}

	// ----------------------------------------------- github copilot provider

	/** Creates the enabled-model result with an unmodifiable model list. */
	public static GitHubCopilotProvider.ModelAccess modelAccess(int policiesEnabled, List<Model> models) {
		return new GitHubCopilotProvider.ModelAccess(policiesEnabled, List.copyOf(models));
	}

	/** Builds the GitHub Copilot router provider over its catalog slice. */
	public static GitHubCopilotProvider newGitHubCopilotProvider(List<Model> models, GitHubCopilotAuth auth) {
		List<Model> all = List.copyOf(models);
		return new GitHubCopilotProvider(
				all,
				auth,
				anthropicProvider(
						GitHubCopilotAuth.PROVIDER_ID,
						GitHubCopilotProvider.NAME,
						copilotModelsFor(all, AnthropicProvider.API),
						List.of(),
						true),
				openAiCompatibleProvider(
						GitHubCopilotAuth.PROVIDER_ID,
						GitHubCopilotProvider.NAME,
						GitHubCopilotProvider.COMPLETIONS_BASE_URL,
						copilotModelsFor(all, OpenAiCompatibleProvider.API)),
				openAiResponsesProvider(
						GitHubCopilotAuth.PROVIDER_ID,
						GitHubCopilotProvider.NAME,
						copilotModelsFor(all, OpenAiResponsesProvider.API),
						List.of()));
	}

	private static List<Model> copilotModelsFor(List<Model> models, String api) {
		return models.stream().filter(model -> model.api.equals(api)).toList();
	}

	/** Filters the catalog to models GitHub reports as enabled for the signed-in account. */
	public static List<Model> gitHubCopilotAvailableModels(GitHubCopilotProvider provider) throws IOException {
		List<String> enabled = gitHubCopilotResolveToken(provider.auth).availableModelIds;
		return filterEnabledCopilotModels(provider, enabled);
	}

	/** Enables catalog model policies, then refreshes the account's enabled-model list. */
	public static GitHubCopilotProvider.ModelAccess gitHubCopilotEnableAndRefreshModels(
			GitHubCopilotProvider provider) throws IOException {
		int policiesEnabled =
				gitHubCopilotEnableModels(provider.auth, provider.models.stream().map(model -> model.id).toList());
		List<Model> available = filterEnabledCopilotModels(
				provider, gitHubCopilotRefreshAvailableModels(provider.auth).availableModelIds);
		return modelAccess(policiesEnabled, available);
	}

	private static List<Model> filterEnabledCopilotModels(GitHubCopilotProvider provider, List<String> enabled) {
		if (enabled == null) {
			return provider.models;
		}
		return provider.models.stream().filter(model -> enabled.contains(model.id)).toList();
	}

	public static AssistantMessageEventStream gitHubCopilotStream(
			GitHubCopilotProvider provider, Model model, Context context, StreamOptions options) {
		if (!model.provider.equals(GitHubCopilotAuth.PROVIDER_ID)) {
			throw new IllegalArgumentException("Model " + model + " is not a GitHub Copilot model");
		}
		StreamOptions requestOptions = options == null ? new StreamOptions() : copyStreamOptions(options);
		if (requestOptions.apiKey == null || requestOptions.apiKey.isBlank()) {
			try {
				GitHubCopilotAuth.CopilotToken token = gitHubCopilotResolveToken(provider.auth);
				requestOptions.apiKey = token.accessToken;
				requestOptions.baseUrl = token.baseUrl.toString();
			} catch (IOException error) {
				return providerErrorStream(model, error);
			}
		}
		return switch (model.api) {
			case "anthropic-messages" -> anthropicStream(provider.anthropic, model, context, requestOptions);
			case "openai-completions" -> openAiCompatibleStream(provider.completions, model, context, requestOptions);
			case "openai-responses" -> openAiResponsesStream(provider.responses, model, context, requestOptions);
			default -> throw new IllegalArgumentException("Unsupported GitHub Copilot model API: " + model.api);
		};
	}

	// --------------------------------------------------------- faux provider

	/** Creates a deterministic test provider; at least one model is required. */
	public static FauxProvider fauxProvider(String api, String id, List<Model> models) {
		Objects.requireNonNull(api);
		Objects.requireNonNull(id);
		List<Model> catalog = List.copyOf(models);
		if (catalog.isEmpty()) {
			throw new IllegalArgumentException("Faux provider needs at least one model");
		}
		return new FauxProvider(api, id, catalog);
	}

	public static FauxProvider newFauxProvider() {
		return fauxProvider(FauxProvider.DEFAULT_API, FauxProvider.DEFAULT_PROVIDER, List.of(fauxDefaultModel()));
	}

	public static Model fauxDefaultModel() {
		Model model = new Model();
		model.id = FauxProvider.DEFAULT_MODEL_ID;
		model.name = "Faux Model";
		model.api = FauxProvider.DEFAULT_API;
		model.provider = FauxProvider.DEFAULT_PROVIDER;
		model.baseUrl = "http://localhost:0";
		model.input = new ArrayList<>(List.of("text", "image"));
		model.cost = ModelCost.FREE;
		model.contextWindow = 128_000;
		model.maxTokens = 16_384;
		return model;
	}

	/** Replaces the provider's FIFO script of literal or context-dependent responses. */
	public static void setFauxResponses(FauxProvider provider, List<FauxProvider.ResponseStep> responses) {
		synchronized (provider) {
			provider.pendingResponses.clear();
			provider.pendingResponses.addAll(responses);
		}
	}

	public static void appendFauxResponses(FauxProvider provider, List<FauxProvider.ResponseStep> responses) {
		synchronized (provider) {
			provider.pendingResponses.addAll(responses);
		}
	}

	public static int fauxPendingResponseCount(FauxProvider provider) {
		synchronized (provider) {
			return provider.pendingResponses.size();
		}
	}

	public static int fauxCallCount(FauxProvider provider) {
		synchronized (provider.state) {
			return provider.state.callCount;
		}
	}

	public static AssistantMessageEventStream fauxStream(
			FauxProvider provider, Model model, Context context, StreamOptions options) {
		AssistantMessageEventStream stream = new AssistantMessageEventStream();
		FauxProvider.ResponseStep step;
		synchronized (provider) {
			step = provider.pendingResponses.pollFirst();
			synchronized (provider.state) {
				provider.state.callCount++;
			}
		}
		Thread.startVirtualThread(() -> fauxProduce(
				provider, stream, model, context, options != null ? options : new StreamOptions(), step));
		return stream;
	}

	private static void fauxProduce(
			FauxProvider provider,
			AssistantMessageEventStream stream,
			Model model,
			Context context,
			StreamOptions options,
			FauxProvider.ResponseStep step) {
		try {
			AssistantMessage response = step == null
					? fauxErrorMessage(model, "No more faux responses queued")
					: resolveFauxStep(step, new FauxProvider.Request(context, options, provider.state, model));
			response.api = provider.api;
			response.provider = provider.id;
			response.model = model.id;
			if (response.usage == null) {
				response.usage = new Usage();
			}
			fauxEstimateUsage(response, context);
			fauxEmit(stream, response, options);
		} catch (Exception e) {
			AssistantMessage error = fauxErrorMessage(model, e.getMessage() != null ? e.getMessage() : e.toString());
			push(stream, new AssistantMessageEvent.Error(StopReason.ERROR, error));
		}
	}

	/** Resolves one scripted step against the current request. */
	public static AssistantMessage resolveFauxStep(FauxProvider.ResponseStep step, FauxProvider.Request request) {
		return switch (step) {
			case FauxProvider.ResponseStep.Message message -> copyAssistantMessage(message.response);
			case FauxProvider.ResponseStep.Factory factory -> factory.factory.apply(request);
		};
	}

	private static void fauxEmit(
			AssistantMessageEventStream stream, AssistantMessage response, StreamOptions options) {
		AssistantMessage partial = new AssistantMessage(response.api, response.provider, response.model);
		partial.responseId = response.responseId;
		partial.usage = response.usage;
		push(stream, new AssistantMessageEvent.Start(partial));

		for (int index = 0; index < response.content.size(); index++) {
			if (isAborted(options)) {
				abortStream(stream, partial);
				return;
			}
			AssistantContent block = response.content.get(index);
			if (block instanceof TextContent text) {
				fauxEmitText(stream, partial, index, text, options);
			} else if (block instanceof ThinkingContent thinking) {
				fauxEmitThinking(stream, partial, index, thinking, options);
			} else if (block instanceof ToolCall call) {
				fauxEmitToolCall(stream, partial, index, call, options);
			}
			if (isAborted(options)) {
				abortStream(stream, partial);
				return;
			}
		}

		fauxCopyTerminalFields(response, partial);
		if (response.stopReason == StopReason.ERROR || response.stopReason == StopReason.ABORTED) {
			push(stream, new AssistantMessageEvent.Error(response.stopReason, partial));
		} else if (response.stopReason == StopReason.PENDING) {
			partial.stopReason = StopReason.ERROR;
			partial.errorMessage = "Faux response ended without a stop reason";
			push(stream, new AssistantMessageEvent.Error(StopReason.ERROR, partial));
		} else {
			push(stream, new AssistantMessageEvent.Done(response.stopReason, partial));
		}
	}

	private static void fauxEmitText(
			AssistantMessageEventStream stream,
			AssistantMessage partial,
			int index,
			TextContent text,
			StreamOptions options) {
		partial.content.add(textContent("", text.textSignature));
		push(stream, new AssistantMessageEvent.TextStart(index, partial));
		StringBuilder value = new StringBuilder();
		for (String chunk : fauxChunks(text.text)) {
			if (isAborted(options)) {
				return;
			}
			value.append(chunk);
			partial.content.set(index, textContent(value.toString(), text.textSignature));
			push(stream, new AssistantMessageEvent.TextDelta(index, chunk, partial));
		}
		push(stream, new AssistantMessageEvent.TextEnd(index, text.text, partial));
	}

	private static void fauxEmitThinking(
			AssistantMessageEventStream stream,
			AssistantMessage partial,
			int index,
			ThinkingContent thinking,
			StreamOptions options) {
		partial.content.add(thinkingContent("", thinking.thinkingSignature, thinking.redacted));
		push(stream, new AssistantMessageEvent.ThinkingStart(index, partial));
		StringBuilder value = new StringBuilder();
		for (String chunk : fauxChunks(thinking.thinking)) {
			if (isAborted(options)) {
				return;
			}
			value.append(chunk);
			partial.content.set(
					index, thinkingContent(value.toString(), thinking.thinkingSignature, thinking.redacted));
			push(stream, new AssistantMessageEvent.ThinkingDelta(index, chunk, partial));
		}
		push(stream, new AssistantMessageEvent.ThinkingEnd(index, thinking.thinking, partial));
	}

	private static void fauxEmitToolCall(
			AssistantMessageEventStream stream,
			AssistantMessage partial,
			int index,
			ToolCall call,
			StreamOptions options) {
		partial.content.add(toolCall(call.id, call.name, jsonObject(), call.thoughtSignature));
		push(stream, new AssistantMessageEvent.ToolCallStart(index, partial));
		String encoded = call.arguments.toString();
		for (String chunk : fauxChunks(encoded)) {
			if (isAborted(options)) {
				return;
			}
			push(stream, new AssistantMessageEvent.ToolCallDelta(index, chunk, partial));
		}
		partial.content.set(index, call);
		push(stream, new AssistantMessageEvent.ToolCallEnd(index, call, partial));
	}

	private static List<String> fauxChunks(String value) {
		List<String> chunks = new ArrayList<>();
		for (int start = 0; start < value.length(); start += 4) {
			chunks.add(value.substring(start, Math.min(value.length(), start + 4)));
		}
		return chunks.isEmpty() ? List.of("") : chunks;
	}

	private static void fauxCopyTerminalFields(AssistantMessage source, AssistantMessage target) {
		target.usage = source.usage;
		target.stopReason = source.stopReason;
		target.errorMessage = source.errorMessage;
		target.rawStopReason = source.rawStopReason;
		target.responseId = source.responseId;
		target.responseModel = source.responseModel;
		target.timestamp = source.timestamp;
	}

	private static AssistantMessage fauxErrorMessage(Model model, String message) {
		AssistantMessage result = new AssistantMessage(model.api, model.provider, model.id);
		result.stopReason = StopReason.ERROR;
		result.errorMessage = message;
		return result;
	}

	private static void fauxEstimateUsage(AssistantMessage response, Context context) {
		long input = fauxEstimateTokens(context.systemPrompt);
		for (Message message : context.messages) {
			input += fauxEstimateTokens(message.toString());
		}
		long output = fauxEstimateTokens(text(response)) + fauxEstimateTokens(thinking(response));
		response.usage.input = input;
		response.usage.output = output;
		response.usage.totalTokens = input + output;
		Model costModel = new Model();
		costModel.id = response.model;
		costModel.api = response.api;
		costModel.provider = response.provider;
		costModel.baseUrl = "http://localhost:0";
		costModel.cost = ModelCost.FREE;
		calculateCost(costModel, response.usage);
	}

	private static long fauxEstimateTokens(String text) {
		return text == null || text.isEmpty() ? 0 : (text.length() + 3L) / 4L;
	}

	public static AssistantMessage fauxText(String content) {
		AssistantMessage response = new AssistantMessage(
				FauxProvider.DEFAULT_API, FauxProvider.DEFAULT_PROVIDER, FauxProvider.DEFAULT_MODEL_ID);
		response.content.add(textContent(content));
		response.stopReason = StopReason.STOP;
		return response;
	}

	public static AssistantMessage fauxThinking(String content) {
		AssistantMessage response = new AssistantMessage(
				FauxProvider.DEFAULT_API, FauxProvider.DEFAULT_PROVIDER, FauxProvider.DEFAULT_MODEL_ID);
		response.content.add(thinkingContent(content));
		response.stopReason = StopReason.STOP;
		return response;
	}

	public static AssistantMessage fauxToolCall(String name, ObjectNode arguments) {
		AssistantMessage response = new AssistantMessage(
				FauxProvider.DEFAULT_API, FauxProvider.DEFAULT_PROVIDER, FauxProvider.DEFAULT_MODEL_ID);
		response.content.add(toolCall("tool-call-1", name, arguments));
		response.stopReason = StopReason.TOOL_USE;
		return response;
	}

	/** Shallow copy of an assistant message sharing its content blocks and usage. */
	public static AssistantMessage copyAssistantMessage(AssistantMessage source) {
		AssistantMessage copy = new AssistantMessage(source.api, source.provider, source.model);
		copy.content.addAll(source.content);
		copy.responseModel = source.responseModel;
		copy.responseId = source.responseId;
		copy.stopReason = source.stopReason;
		copy.errorMessage = source.errorMessage;
		copy.rawStopReason = source.rawStopReason;
		copy.timestamp = source.timestamp;
		copy.usage = source.usage;
		return copy;
	}

	// ------------------------------------------------------ provider dispatch

	public static String providerId(Provider provider) {
		return switch (provider) {
			case AnthropicProvider anthropic -> anthropic.id;
			case ChatGptProvider ignored -> ChatGptAuth.PROVIDER_ID;
			case FauxProvider faux -> faux.id;
			case GitHubCopilotProvider ignored -> GitHubCopilotAuth.PROVIDER_ID;
			case GoogleProvider ignored -> "google";
			case OpenAiCompatibleProvider compatible -> compatible.id;
			case OpenAiResponsesProvider responses -> responses.id;
			default -> throw unknownProvider(provider);
		};
	}

	public static String providerName(Provider provider) {
		return switch (provider) {
			case AnthropicProvider anthropic -> anthropic.name;
			case ChatGptProvider ignored -> ChatGptProvider.NAME;
			case FauxProvider ignored -> FauxProvider.NAME;
			case GitHubCopilotProvider ignored -> GitHubCopilotProvider.NAME;
			case GoogleProvider ignored -> "Google";
			case OpenAiCompatibleProvider compatible -> compatible.name;
			case OpenAiResponsesProvider responses -> responses.name;
			default -> throw unknownProvider(provider);
		};
	}

	public static String providerApi(Provider provider) {
		return switch (provider) {
			case AnthropicProvider ignored -> AnthropicProvider.API;
			case ChatGptProvider ignored -> ChatGptProvider.API;
			case FauxProvider faux -> faux.api;
			case GitHubCopilotProvider ignored -> GitHubCopilotProvider.API;
			case GoogleProvider ignored -> GoogleProvider.API;
			case OpenAiCompatibleProvider ignored -> OpenAiCompatibleProvider.API;
			case OpenAiResponsesProvider ignored -> OpenAiResponsesProvider.API;
			default -> throw unknownProvider(provider);
		};
	}

	public static List<Model> providerModels(Provider provider) {
		return switch (provider) {
			case AnthropicProvider anthropic -> anthropic.models;
			case ChatGptProvider chatGpt -> chatGpt.models;
			case FauxProvider faux -> faux.models;
			case GitHubCopilotProvider copilot -> copilot.models;
			case GoogleProvider google -> google.models;
			case OpenAiCompatibleProvider compatible -> compatible.models;
			case OpenAiResponsesProvider responses -> responses.models;
			default -> throw unknownProvider(provider);
		};
	}

	public static List<String> providerApiKeyEnvVars(Provider provider) {
		return switch (provider) {
			case AnthropicProvider anthropic -> anthropic.apiKeyEnvVars;
			case GoogleProvider ignored -> List.of("GEMINI_API_KEY", "GOOGLE_API_KEY");
			case OpenAiCompatibleProvider compatible ->
				compatible.id.equals("openai") ? List.of("OPENAI_API_KEY") : List.of();
			case OpenAiResponsesProvider responses -> responses.apiKeyEnvVars;
			case ChatGptProvider ignored -> List.of();
			case FauxProvider ignored -> List.of();
			case GitHubCopilotProvider ignored -> List.of();
			default -> throw unknownProvider(provider);
		};
	}

	/** Reports whether a credential-routed provider has a saved login. */
	public static boolean providerHasCredential(Provider provider) throws IOException {
		return switch (provider) {
			case ChatGptProvider chatGpt -> chatGptHasCredential(chatGpt.auth);
			case GitHubCopilotProvider copilot -> gitHubCopilotHasCredential(copilot.auth);
			default -> false;
		};
	}

	/** Removes a credential-routed provider's saved login. */
	public static void providerLogout(Provider provider) throws IOException {
		switch (provider) {
			case ChatGptProvider chatGpt -> chatGptLogout(chatGpt.auth);
			case GitHubCopilotProvider copilot -> gitHubCopilotLogout(copilot.auth);
			default -> throw unknownProvider(provider);
		}
	}

	/**
	 * Starts a streaming request. Once invoked, failures are encoded in the
	 * returned stream (Error event with stopReason ERROR/ABORTED) rather than
	 * thrown, except for argument validation.
	 */
	public static AssistantMessageEventStream stream(
			Provider provider, Model model, Context context, StreamOptions options) {
		return switch (provider) {
			case AnthropicProvider anthropic -> anthropicStream(anthropic, model, context, options);
			case ChatGptProvider chatGpt -> chatGptStream(chatGpt, model, context, options);
			case FauxProvider faux -> fauxStream(faux, model, context, options);
			case GitHubCopilotProvider copilot -> gitHubCopilotStream(copilot, model, context, options);
			case GoogleProvider google -> googleStream(google, model, context, options);
			case OpenAiCompatibleProvider compatible -> openAiCompatibleStream(compatible, model, context, options);
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

	/** Creates agent state; a missing system prompt becomes the empty string. */
	public static AgentState newAgentState(String systemPrompt, Model model) {
		return new AgentState(systemPrompt == null ? "" : systemPrompt, model);
	}

	/** Creates an agent over freshly initialized state. */
	public static Agent newAgent(String systemPrompt, Model model, Provider provider) {
		return new Agent(newAgentState(systemPrompt, model), provider);
	}

	/** Creates a tool result with an unmodifiable copy of the supplied content. */
	public static AgentTool.ToolResult toolResult(List<UserContent> content, Object details, boolean isError) {
		return new AgentTool.ToolResult(List.copyOf(content), details, isError);
	}

	/** Registers an agent event listener; closing the result unsubscribes it. */
	public static AutoCloseable subscribe(Agent agent, Consumer<AgentEvent> listener) {
		agent.listeners.add(listener);
		return () -> agent.listeners.remove(listener);
	}

	/** Reports that a repository instruction file will apply to later model requests. */
	public static void instructionLoaded(Agent agent, Path path) {
		emit(agent, new AgentEvent.InstructionLoaded(Objects.requireNonNull(path, "path")));
	}

	/** Cancels the turn in flight, if any. */
	public static void abort(Agent agent) {
		AbortSignal signal = agent.activeSignal;
		if (signal != null) {
			abort(signal);
		}
	}

	/**
	 * Summarizes all active messages in a separate model call, then replaces
	 * them with a single checkpoint message. The caller remains responsible for
	 * persisting the original transcript if it needs complete history.
	 */
	public static CompactionResult compact(Agent agent, String customInstructions) throws InterruptedException {
		AgentState state = agent.state;
		if (state.isStreaming || state.isCompacting) {
			throw new IllegalStateException("Agent is already processing");
		}
		if (state.messages.isEmpty()) {
			throw new IllegalStateException("Cannot compact an empty conversation");
		}
		state.isCompacting = true;
		AbortSignal signal = new AbortSignal();
		try {
			long tokensBefore = estimateMessageTokens(state.messages);
			emit(agent, new AgentEvent.CompactionStart(tokensBefore));
			Context context = new Context(COMPACTION_SYSTEM_PROMPT);
			String prompt = "<conversation>\n" + serializeMessages(state.messages) + "\n</conversation>\n\n"
					+ (customInstructions == null || customInstructions.isBlank()
							? "Summarize this conversation for a future coding-agent turn."
							: "Summarize this conversation with this focus: " + customInstructions);
			context.messages.add(userMessage(prompt));
			AssistantMessage response = agentComplete(agent, context, signal);
			if (response.stopReason == StopReason.ERROR || response.stopReason == StopReason.ABORTED) {
				throw new IllegalStateException("Compaction failed: " + response.errorMessage);
			}
			String summary = text(response);
			if (summary.isBlank()) {
				throw new IllegalStateException("Compaction failed: provider returned an empty summary");
			}
			state.messages.clear();
			state.compactionSummary = summary;
			state.messages.add(userMessage("[Conversation checkpoint]\n" + summary));
			CompactionResult result =
					new CompactionResult(summary, tokensBefore, estimateMessageTokens(state.messages));
			emit(agent, new AgentEvent.CompactionEnd(result));
			return result;
		} finally {
			state.isCompacting = false;
		}
	}

	/** Runs a prompt to completion, returning only messages created during this invocation. */
	public static List<Message> prompt(Agent agent, String text) throws InterruptedException {
		return prompt(agent, userMessage(text));
	}

	/** Runs one or more prompt messages to completion. */
	public static List<Message> prompt(Agent agent, Message... prompts) throws InterruptedException {
		AgentState state = agent.state;
		if (state.isStreaming || state.isCompacting) {
			throw new IllegalStateException("Agent is already processing");
		}
		agent.activeSignal = new AbortSignal();
		state.errorMessage = null;
		state.isStreaming = true;
		List<Message> newMessages = new ArrayList<>();
		try {
			if (agentShouldAutoCompact(state)) {
				agentCompactInternal(agent);
			}
			emit(agent, new AgentEvent.AgentStart());
			emit(agent, new AgentEvent.TurnStart());
			for (Message prompt : prompts) {
				state.messages.add(prompt);
				newMessages.add(prompt);
				emit(agent, new AgentEvent.MessageStart(prompt));
				emit(agent, new AgentEvent.MessageEnd(prompt));
			}

			while (true) {
				AssistantMessage response = agentStreamAssistant(agent);
				newMessages.add(response);
				if (response.stopReason == StopReason.ERROR || response.stopReason == StopReason.ABORTED) {
					state.errorMessage = response.errorMessage;
					emit(agent, new AgentEvent.TurnEnd(response, List.of()));
					break;
				}
				List<ToolResultMessage> results = agentExecuteTools(agent, response);
				newMessages.addAll(results);
				emit(agent, new AgentEvent.TurnEnd(response, results));
				if (results.isEmpty()) {
					break;
				}
				emit(agent, new AgentEvent.TurnStart());
			}
			return List.copyOf(newMessages);
		} finally {
			state.isStreaming = false;
			state.streamingMessage = null;
			state.pendingToolCalls.clear();
			emit(agent, new AgentEvent.AgentEnd(List.copyOf(newMessages)));
			agent.activeSignal = null;
		}
	}

	private static AssistantMessage agentStreamAssistant(Agent agent) throws InterruptedException {
		AssistantMessage[] lastAttempt = new AssistantMessage[1];
		return retryAssistantCall(
				() -> lastAttempt[0] = agentStreamAssistantOnce(agent),
				agent.retryPolicy,
				agent.activeSignal,
				agentRetryCallbacks(agent, () -> agentDiscardAssistantAttempt(agent, lastAttempt[0])));
	}

	private static AssistantMessage agentStreamAssistantOnce(Agent agent) throws InterruptedException {
		AgentState state = agent.state;
		Context context = new Context(state.systemPrompt);
		for (Message message : state.messages) {
			if (!(message instanceof AssistantMessage assistant)
					|| (assistant.stopReason != StopReason.ERROR && assistant.stopReason != StopReason.ABORTED)) {
				context.messages.add(message);
			}
		}
		for (AgentTool tool : state.tools) {
			context.tools.add(toolDefinition(toolName(tool), toolDescription(tool), toolParameters(tool)));
		}
		StreamOptions options = new StreamOptions();
		options.signal = agent.activeSignal;
		options.reasoning = state.thinkingLevel;
		options.apiKey = agent.apiKey;
		AssistantMessageEventStream stream = stream(agent.provider, state.model, context, options);
		AssistantMessage finalMessage = null;
		for (AssistantMessageEvent event : events(stream)) {
			switch (event) {
				case AssistantMessageEvent.Start start -> {
					state.streamingMessage = start.partial;
					state.messages.add(start.partial);
					emit(agent, new AgentEvent.MessageStart(start.partial));
				}
				case AssistantMessageEvent.Done done -> finalMessage = done.message;
				case AssistantMessageEvent.Error error -> finalMessage = error.error;
				default -> emit(agent, new AgentEvent.MessageUpdate(event));
			}
		}
		if (finalMessage == null) {
			finalMessage = result(stream);
		}
		if (state.streamingMessage != null) {
			state.messages.set(state.messages.size() - 1, finalMessage);
		} else {
			state.messages.add(finalMessage);
			emit(agent, new AgentEvent.MessageStart(finalMessage));
		}
		state.streamingMessage = null;
		emit(agent, new AgentEvent.MessageEnd(finalMessage));
		return finalMessage;
	}

	private static void agentDiscardAssistantAttempt(Agent agent, AssistantMessage attempt) {
		AgentState state = agent.state;
		state.streamingMessage = null;
		if (attempt == null) return;
		for (int index = state.messages.size() - 1; index >= 0; index--) {
			if (state.messages.get(index) == attempt) {
				state.messages.remove(index);
				return;
			}
		}
	}

	private static Retry.Callbacks agentRetryCallbacks(Agent agent, Runnable beforeRetryAttempt) {
		return new Retry.Callbacks(
				scheduled -> emit(agent, new AgentEvent.AutoRetryStart(
						scheduled.attempt, scheduled.maxAttempts, scheduled.delayMs, scheduled.errorMessage)),
				() -> {
					if (beforeRetryAttempt != null) beforeRetryAttempt.run();
				},
				finished -> emit(agent, new AgentEvent.AutoRetryEnd(
						finished.success, finished.attempt, finished.finalError)));
	}

	private static void agentCompactInternal(Agent agent) throws InterruptedException {
		AgentState state = agent.state;
		long tokensBefore = estimateMessageTokens(state.messages);
		state.isCompacting = true;
		AbortSignal signal = new AbortSignal();
		try {
			emit(agent, new AgentEvent.CompactionStart(tokensBefore));
			Context context = new Context(COMPACTION_SYSTEM_PROMPT);
			context.messages.add(userMessage("<conversation>\n" + serializeMessages(state.messages)
					+ "\n</conversation>\n\nSummarize this conversation for a future coding-agent turn."));
			AssistantMessage response = agentComplete(agent, context, signal);
			String summary = text(response);
			if (response.stopReason == StopReason.ERROR
					|| response.stopReason == StopReason.ABORTED
					|| summary.isBlank()) {
				throw new IllegalStateException("Automatic compaction failed: " + response.errorMessage);
			}
			state.messages.clear();
			state.compactionSummary = summary;
			state.messages.add(userMessage("[Conversation checkpoint]\n" + summary));
			emit(agent, new AgentEvent.CompactionEnd(
					new CompactionResult(summary, tokensBefore, estimateMessageTokens(state.messages))));
		} finally {
			state.isCompacting = false;
		}
	}

	private static AssistantMessage agentComplete(Agent agent, Context context, AbortSignal signal)
			throws InterruptedException {
		return retryAssistantCall(
				() -> agentCompleteOnce(agent, context, signal),
				agent.retryPolicy,
				signal,
				agentRetryCallbacks(agent, null));
	}

	private static AssistantMessage agentCompleteOnce(Agent agent, Context context, AbortSignal signal)
			throws InterruptedException {
		StreamOptions options = new StreamOptions();
		options.signal = signal;
		options.reasoning = agent.state.thinkingLevel;
		options.apiKey = agent.apiKey;
		options.maxTokens = 4_096;
		AssistantMessageEventStream stream = stream(agent.provider, agent.state.model, context, options);
		AssistantMessage response = null;
		for (AssistantMessageEvent event : events(stream)) {
			if (event instanceof AssistantMessageEvent.Done done) response = done.message;
			if (event instanceof AssistantMessageEvent.Error error) response = error.error;
		}
		return response == null ? result(stream) : response;
	}

	private static boolean agentShouldAutoCompact(AgentState state) {
		return state.autoCompactionEnabled
				&& state.messages.size() > 1
				&& state.model.contextWindow > state.compactionReserveTokens
				&& estimateMessageTokens(state.messages) > state.model.contextWindow - state.compactionReserveTokens;
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

	private static String serializeMessages(List<Message> messages) {
		StringBuilder output = new StringBuilder();
		for (Message message : messages) {
			if (message instanceof UserMessage user) output.append("[User] ").append(text(user));
			else if (message instanceof AssistantMessage assistant) {
				String thinking = thinking(assistant);
				if (!thinking.isBlank()) output.append("[Assistant thinking] ").append(thinking).append('\n');
				output.append("[Assistant] ").append(text(assistant));
				for (ToolCall call : toolCalls(assistant))
					output.append("\n[Tool call] ").append(call.name).append(' ').append(call.arguments);
			} else if (message instanceof ToolResultMessage toolResult)
				output.append("[Tool result] ").append(text(toolResult));
			output.append("\n\n");
		}
		return output.toString();
	}

	private static List<ToolResultMessage> agentExecuteTools(Agent agent, AssistantMessage message) {
		AgentState state = agent.state;
		Map<String, AgentTool> toolsByName = new LinkedHashMap<>();
		for (AgentTool tool : state.tools) {
			toolsByName.put(toolName(tool), tool);
		}
		List<ToolResultMessage> results = new ArrayList<>();
		for (ToolCall call : toolCalls(message)) {
			if (isAborted(agent.activeSignal)) {
				break;
			}
			state.pendingToolCalls.add(call.id);
			emit(agent, new AgentEvent.ToolExecutionStart(call.id, call.name, call.arguments));
			AgentTool.ToolResult toolResult;
			AgentTool tool = toolsByName.get(call.name);
			if (tool == null) {
				toolResult = toolResultError("Unknown tool: " + call.name);
			} else {
				try {
					toolResult = executeTool(
							tool,
							call.id,
							call.arguments,
							agent.activeSignal,
							partial -> emit(agent, new AgentEvent.ToolExecutionUpdate(call.id, call.name, partial)));
				} catch (Exception e) {
					toolResult = toolResultError(e.getMessage() == null ? e.toString() : e.getMessage());
				}
			}
			state.pendingToolCalls.remove(call.id);
			emit(agent, new AgentEvent.ToolExecutionEnd(call.id, call.name, toolResult));
			ToolResultMessage resultMessage = toolResultMessage(
					call.id,
					call.name,
					toolResult.content,
					toolResult.details,
					toolResult.isError,
					System.currentTimeMillis());
			state.messages.add(resultMessage);
			results.add(resultMessage);
			emit(agent, new AgentEvent.MessageStart(resultMessage));
			emit(agent, new AgentEvent.MessageEnd(resultMessage));
		}
		return results;
	}

	private static void emit(Agent agent, AgentEvent event) {
		for (Consumer<AgentEvent> listener : agent.listeners) {
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

	/** Model-visible JSON schema of the tool's arguments. */
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
	public static AgentTool.ToolResult executeTool(
			AgentTool tool,
			String toolCallId,
			ObjectNode arguments,
			AbortSignal signal,
			Consumer<AgentTool.ToolResult> onUpdate)
			throws Exception {
		return switch (tool) {
			case FunctionTool function ->
					function.execute.apply(new ToolInvocation(toolCallId, arguments, signal, onUpdate));
			case LocalTool local -> executeLocalTool(local, arguments, signal);
			case McpAgentTool mcp -> executeMcpTool(mcp, arguments, signal);
			default -> throw unknownTool(tool);
		};
	}

	public static AgentTool.ToolResult toolResultText(String text) {
		return toolResult(List.of(textContent(text)), null, false);
	}

	public static AgentTool.ToolResult toolResultError(String text) {
		return toolResult(List.of(textContent(text)), null, true);
	}

	private static IllegalArgumentException unknownTool(AgentTool tool) {
		return new IllegalArgumentException(
				"Unknown tool carrier: " + (tool == null ? "null" : tool.getClass().getName()));
	}

	// ------------------------------------------------------- built-in tools

	/** Returns tools scoped to the given working directory. */
	public static List<AgentTool> builtInTools(Path cwd) {
		return builtInTools(cwd, ignored -> {});
	}

	/**
	 * Returns tools scoped to the given working directory and reports paths as
	 * they are accessed. The callback is observational; it does not alter tool
	 * permissions or path resolution.
	 */
	public static List<AgentTool> builtInTools(Path cwd, Consumer<Path> onPathAccess) {
		return builtInTools(cwd, new GitIgnore("git"), onPathAccess);
	}

	/** Returns tools that filter search results with the given git-ignore configuration. */
	public static List<AgentTool> builtInTools(Path cwd, GitIgnore gitIgnore, Consumer<Path> onPathAccess) {
		Path resolvedCwd = cwd.toAbsolutePath().normalize();
		Consumer<Path> observer = Objects.requireNonNull(onPathAccess, "onPathAccess");
		BuiltInTools.Shell shell = currentShell();
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
						toolSchema("path", toolString("Path to edit. A leading ~/ expands to the user home directory."), "edits", toolArray("Exact replacements with oldText and newText")),
						observer,
						null,
						null),
				new LocalTool(
						BuiltInTools.Kind.SHELL,
						resolvedCwd,
						"shell",
						"Execute a " + shell.displayName + " command in the current working directory. Output is bounded to 2,000 lines or 50KB.",
						toolSchema("command", toolString(shell.displayName + " command"), "timeout", toolOptional(toolNumber("Optional timeout in seconds"))),
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
						gitIgnore,
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
						gitIgnore,
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

	private static AgentTool.ToolResult executeLocalTool(LocalTool tool, ObjectNode arguments, AbortSignal signal)
			throws Exception {
		return switch (tool.kind) {
			case READ -> readToolExecute(tool, arguments, signal);
			case WRITE -> writeToolExecute(tool, arguments, signal);
			case EDIT -> editToolExecute(tool, arguments, signal);
			case SHELL -> shellToolExecute(tool, arguments, signal);
			case GREP -> grepToolExecute(tool, arguments, signal);
			case FIND -> findToolExecute(tool, arguments, signal);
			case LS -> lsToolExecute(tool, arguments, signal);
		};
	}

	private static AgentTool.ToolResult readToolExecute(LocalTool tool, ObjectNode arguments, AbortSignal signal)
			throws IOException {
		String pathText = requiredToolText(arguments, "path");
		BuiltInTools.ArchiveLocation location = localToolArchiveLocation(tool, pathText);
		if (location == null) {
			return readToolFile(localToolPath(tool, pathText), arguments, signal);
		}
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
				return toolResultText(listArchiveDirectory(entry));
			}
			if (!Files.isRegularFile(entry)) {
				throw archiveEntryNotFound(location, root);
			}
			return readToolFile(entry, arguments, signal);
		}
	}

	private static AgentTool.ToolResult readToolFile(Path file, ObjectNode arguments, AbortSignal signal)
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
		output = boundToolOutput(output, "Use offset=" + (start + toolOutputLineCount(output) + 1) + " to continue.");
		if (end < lines.size() && !output.contains("Use offset=")) {
			output += "\n\n[" + (lines.size() - end) + " more lines. Use offset=" + (end + 1) + " to continue.]";
		}
		return toolResultText(output);
	}

	private static String listArchiveDirectory(Path directory) throws IOException {
		List<String> entries;
		try (var paths = Files.list(directory)) {
			entries = paths.sorted(
							Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
					.map(CodingAgentOperations::archiveListingEntry)
					.toList();
		}
		return entries.isEmpty() ? "(empty directory)" : boundToolOutput(String.join("\n", entries), null);
	}

	private static String archiveListingEntry(Path entry) {
		if (Files.isDirectory(entry)) return entry.getFileName() + "/";
		try {
			return entry.getFileName() + " (" + Files.size(entry) + " bytes)";
		} catch (IOException ignored) {
			return entry.getFileName().toString();
		}
	}

	private static IOException archiveEntryNotFound(BuiltInTools.ArchiveLocation location, Path root)
			throws IOException {
		String entry = location.entry;
		String withoutTrailingSlash = entry.endsWith("/") ? entry.substring(0, entry.length() - 1) : entry;
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
		return new IOException("Archive entry not found: " + entry + " in " + location.archive
				+ ". Nearby entries:\n" + suggestions);
	}

	private static AgentTool.ToolResult writeToolExecute(LocalTool tool, ObjectNode arguments, AbortSignal signal)
			throws IOException {
		String pathText = requiredToolText(arguments, "path");
		rejectArchivePath(tool, pathText);
		Path file = localToolPath(tool, pathText);
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
		return toolResultText(
				"Successfully wrote " + content.getBytes(StandardCharsets.UTF_8).length + " bytes to " + file);
	}

	private static AgentTool.ToolResult editToolExecute(LocalTool tool, ObjectNode arguments, AbortSignal signal)
			throws IOException {
		String pathText = requiredToolText(arguments, "path");
		rejectArchivePath(tool, pathText);
		Path file = localToolPath(tool, pathText);
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
		return toolResultText("Successfully replaced " + replacements.size() + " block(s) in " + file);
	}

	private static AgentTool.ToolResult shellToolExecute(LocalTool tool, ObjectNode arguments, AbortSignal signal)
			throws Exception {
		String command = requiredToolText(arguments, "command");
		double timeoutSeconds = optionalToolPositiveNumber(arguments, "timeout", 0);
		Process process = new ProcessBuilder(shellCommand(tool.shell, command))
				.directory(tool.cwd.toFile())
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
		return toolResultText(output.isBlank() ? "(no output)" : output);
	}

	private static AgentTool.ToolResult grepToolExecute(LocalTool tool, ObjectNode arguments, AbortSignal signal)
			throws IOException {
		String patternText = requiredToolText(arguments, "pattern");
		boolean literal = optionalToolBoolean(arguments, "literal");
		int flags = optionalToolBoolean(arguments, "ignoreCase") ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
		Pattern pattern = Pattern.compile(literal ? Pattern.quote(patternText) : patternText, flags);
		String pathText = optionalToolText(arguments, "path", ".");
		Path root = grepRoot(tool, pathText);
		int limit = positiveToolIntOrDefault(arguments, "limit", BuiltInTools.DEFAULT_GREP_LIMIT);
		String glob = optionalToolText(arguments, "glob", null);
		List<PathMatcher> fileMatchers = glob == null ? List.of() : globMatchers(glob);
		List<Path> files = filesUnder(root, optionalToolBoolean(arguments, "includeIgnored"), tool.gitIgnore, signal);
		boolean rootIsDirectory = Files.isDirectory(root);
		StringBuilder output = new StringBuilder();
		int filesConsidered = files.size();
		int filesSearched = 0;
		int matches = 0;
		for (Path file : files) {
			requireNotAborted(signal);
			Path relative = rootIsDirectory ? root.relativize(file) : file.getFileName();
			if (!fileMatchers.isEmpty() && !matchesGlob(fileMatchers, relative)) {
				continue;
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
				output.append(displayToolPath(root, file))
						.append(':')
						.append(line + 1)
						.append(": ")
						.append(truncateGrepLine(lines.get(line)))
						.append('\n');
				if (matches >= limit) {
					return toolResultText(
							boundToolOutput(output.toString(), "[" + limit + " matches limit reached]"));
				}
			}
		}
		if (matches > 0) return toolResultText(boundToolOutput(output.toString(), null));
		if (glob != null && filesSearched == 0 && filesConsidered > 0) {
			return toolResultText("No files matched glob '" + glob + "' (" + filesConsidered + " files under "
					+ root + " were considered). The glob is matched against paths relative to path; check the directory prefix.");
		}
		if (glob != null) {
			return toolResultText("No matches found in " + filesSearched + " files matching glob '" + glob + "'");
		}
		return toolResultText("No matches found in " + filesSearched + " files");
	}

	private static Path grepRoot(LocalTool tool, String pathText) {
		Path root;
		try {
			root = localToolPath(tool, pathText);
		} catch (InvalidPathException error) {
			if (containsGlobMetacharacter(pathText)) {
				throw wildcardPathError(pathText);
			}
			throw error;
		}
		if (!Files.exists(root) && containsGlobMetacharacter(pathText)) {
			throw wildcardPathError(pathText);
		}
		return root;
	}

	private static AgentTool.ToolResult findToolExecute(LocalTool tool, ObjectNode arguments, AbortSignal signal)
			throws IOException {
		String pattern = requiredToolText(arguments, "pattern");
		Path root = localToolPath(tool, optionalToolText(arguments, "path", "."));
		if (!Files.isDirectory(root)) {
			throw new IllegalArgumentException("Not a directory: " + root);
		}
		int limit = positiveToolIntOrDefault(arguments, "limit", BuiltInTools.DEFAULT_FIND_LIMIT);
		var matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
		List<Path> candidates =
				filesUnder(root, optionalToolBoolean(arguments, "includeIgnored"), tool.gitIgnore, signal);
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
			return toolResultText("No files found matching pattern");
		}
		boolean limitReached = matches.size() > limit;
		if (limitReached) matches = new ArrayList<>(matches.subList(0, limit));
		String suffix = limitReached ? "\n\n[" + limit + " results limit reached]" : "";
		return toolResultText(boundToolOutput(String.join("\n", matches) + suffix, null));
	}

	private static AgentTool.ToolResult lsToolExecute(LocalTool tool, ObjectNode arguments, AbortSignal signal)
			throws IOException {
		Path directory = localToolPath(tool, optionalToolText(arguments, "path", "."));
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
			return toolResultText("(empty directory)");
		}
		return toolResultText(boundToolOutput(String.join("\n", entries), null));
	}

	/** Resolves a tool path argument against the tool's working directory. */
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

	private static void rejectArchivePath(LocalTool tool, String value) {
		if (localToolArchiveLocation(tool, value) != null) {
			throw new IllegalArgumentException("archives are read-only through this tool");
		}
	}

	private static void requireNotAborted(AbortSignal signal) {
		if (isAborted(signal)) {
			throw new IllegalStateException("Operation aborted");
		}
	}

	private static BuiltInTools.Shell currentShell() {
		return isWindowsHost() ? BuiltInTools.Shell.POWERSHELL : BuiltInTools.Shell.BASH;
	}

	private static List<String> shellCommand(BuiltInTools.Shell shell, String command) {
		return switch (shell) {
			case BASH -> List.of("/bin/bash", "-lc", command);
			case POWERSHELL -> List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command);
		};
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

	private static List<PathMatcher> globMatchers(String glob) {
		if (glob.isBlank()) {
			throw new IllegalArgumentException("glob must be a non-empty string");
		}
		try {
			return globVariants(glob).stream()
					.map(variant -> FileSystems.getDefault().getPathMatcher("glob:" + variant))
					.toList();
		} catch (java.util.regex.PatternSyntaxException error) {
			throw new IllegalArgumentException("Invalid glob '" + glob + "': " + error.getDescription(), error);
		}
	}

	// Java's recursive-directory glob requires at least one directory; ripgrep-style globs allow zero.
	private static List<String> globVariants(String glob) {
		LinkedHashSet<String> variants = new LinkedHashSet<>();
		List<String> pending = new ArrayList<>();
		variants.add(glob);
		pending.add(glob);
		for (int pendingIndex = 0; pendingIndex < pending.size(); pendingIndex++) {
			String variant = pending.get(pendingIndex);
			for (int index = variant.indexOf("**/"); index >= 0; index = variant.indexOf("**/", index + 3)) {
				String withoutDirectoryWildcard = variant.substring(0, index) + variant.substring(index + 3);
				if (variants.add(withoutDirectoryWildcard)) {
					pending.add(withoutDirectoryWildcard);
				}
			}
		}
		return List.copyOf(variants);
	}

	private static boolean matchesGlob(List<PathMatcher> matchers, Path relative) {
		for (PathMatcher matcher : matchers) {
			if (matcher.matches(relative)) {
				return true;
			}
		}
		Path fileName = relative.getFileName();
		if (fileName != null && !fileName.equals(relative)) {
			for (PathMatcher matcher : matchers) {
				if (matcher.matches(fileName)) {
					return true;
				}
			}
		}
		return false;
	}

	private static List<Path> filesUnder(Path root, boolean includeIgnored, GitIgnore gitIgnore, AbortSignal signal)
			throws IOException {
		List<Path> files;
		if (Files.isRegularFile(root)) {
			files = List.of(root);
		} else {
			if (!Files.isDirectory(root)) {
				throw new IllegalArgumentException("Path not found: " + root);
			}
			files = walkRegularFiles(root, signal);
		}
		return includeIgnored ? files : gitIgnoreFilter(gitIgnore, root, files, signal);
	}

	private static List<Path> walkRegularFiles(Path root, AbortSignal signal) throws IOException {
		List<Path> files = new ArrayList<>();
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
				if (attributes.isRegularFile() && !isSkippedPath(root.relativize(file))) files.add(file);
				return FileVisitResult.CONTINUE;
			}
		});
		return List.copyOf(files);
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

	private static String displayToolPath(Path root, Path file) {
		return Files.isDirectory(root)
				? root.relativize(file).toString().replace('\\', '/')
				: file.getFileName().toString();
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

	private static double optionalToolPositiveNumber(ObjectNode arguments, String name, double defaultValue) {
		JsonNode value = arguments.get(name);
		if (value == null || value.isNull()) {
			return defaultValue;
		}
		if (!value.isNumber() || value.asDouble() <= 0 || !Double.isFinite(value.asDouble())) {
			throw new IllegalArgumentException(name + " must be a positive finite number");
		}
		return value.asDouble();
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

	private static ObjectNode toolNumber(String description) {
		return jsonObject().put("type", "number").put("exclusiveMinimum", 0).put("description", description);
	}

	private static ObjectNode toolBoolean(String description) {
		return jsonObject().put("type", "boolean").put("description", description);
	}

	private static ObjectNode toolOptional(ObjectNode definition) {
		return definition.put("x-java-optional", true);
	}

	private static ObjectNode toolArray(String description) {
		return jsonObject().put("type", "array").put("description", description);
	}

	/** Bounds tool output to 2,000 lines or 50KB, appending a truncation notice. */
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
			if (output.length() > 0
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

	private static int toolOutputLineCount(String text) {
		return text.isBlank() ? 0 : (int) text.lines().count();
	}

	private static String truncateGrepLine(String line) {
		return line.length() <= 500 ? line : line.substring(0, 500) + "... [truncated]";
	}

	// ----------------------------------------------------------- git ignore

	/** Drops git-ignored candidates; returns them unchanged when git cannot answer. */
	public static List<Path> gitIgnoreFilter(
			GitIgnore gitIgnore, Path walkRoot, List<Path> candidates, AbortSignal signal) {
		if (candidates.isEmpty()) return candidates;
		Path workingDirectory = Files.isDirectory(walkRoot) ? walkRoot : walkRoot.getParent();
		if (workingDirectory == null || enclosingWorktree(workingDirectory) == null) return candidates;

		List<String> relativeNames = new ArrayList<>(candidates.size());
		for (Path candidate : candidates) {
			relativeNames.add(workingDirectory.relativize(candidate).toString());
		}

		Process process;
		try {
			process = new ProcessBuilder(
							gitIgnore.executable, "-C", workingDirectory.toString(), "check-ignore", "--stdin", "-z")
					.redirectErrorStream(true)
					.start();
		} catch (IOException ignored) {
			return candidates;
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

		long deadline = System.nanoTime() + GitIgnore.TIMEOUT.toNanos();
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
					return candidates;
				}
				process.waitFor(50, TimeUnit.MILLISECONDS);
			}
			joinThreads(writer, reader);
		} catch (InterruptedException error) {
			process.destroyForcibly();
			Thread.currentThread().interrupt();
			return candidates;
		}
		if (transferFailure.get() != null || (process.exitValue() != 0 && process.exitValue() != 1)) {
			return candidates;
		}

		Set<String> ignored = nulSeparated(output.toByteArray());
		if (ignored.isEmpty()) return candidates;
		List<Path> filtered = new ArrayList<>(candidates.size());
		for (int index = 0; index < candidates.size(); index++) {
			if (!ignored.contains(relativeNames.get(index))) filtered.add(candidates.get(index));
		}
		return List.copyOf(filtered);
	}

	private static Path enclosingWorktree(Path start) {
		for (Path directory = start.toAbsolutePath().normalize(); directory != null; directory = directory.getParent()) {
			if (Files.exists(directory.resolve(".git"))) return directory;
		}
		return null;
	}

	private static Set<String> nulSeparated(byte[] bytes) {
		Set<String> values = new HashSet<>();
		int start = 0;
		for (int index = 0; index < bytes.length; index++) {
			if (bytes[index] != 0) continue;
			values.add(new String(bytes, start, index - start, StandardCharsets.UTF_8));
			start = index + 1;
		}
		if (start < bytes.length) {
			values.add(new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8));
		}
		return values;
	}

	private static void joinThreads(Thread... threads) throws InterruptedException {
		for (Thread thread : threads) thread.join();
	}

	// ------------------------------------------------------------ mcp tools

	/** Builds the model-visible adapter for one remote MCP tool. */
	public static McpAgentTool newMcpAgentTool(
			String serverName,
			McpClient.ToolDefinition definition,
			McpClient client,
			List<McpResultFilter> resultFilters) {
		return new McpAgentTool(
				serverName,
				definition,
				client,
				List.copyOf(resultFilters),
				mcpSanitizeName(serverName) + "_" + mcpSanitizeName(definition.name));
	}

	/** Replaces characters that providers reject in tool names. */
	public static String mcpSanitizeName(String value) {
		return value.replaceAll("[^a-zA-Z0-9_-]", "_");
	}

	private static AgentTool.ToolResult executeMcpTool(McpAgentTool tool, ObjectNode arguments, AbortSignal signal)
			throws Exception {
		McpClient.CallResult callResult = mcpCallTool(tool.client, tool.definition.name, arguments, signal);
		ObjectNode filtered = mcpFilterResult(tool.definition.name, callResult.raw, tool.resultFilters);
		List<UserContent> content = mcpConvertContent(filtered);
		if (content.isEmpty()) {
			content = List.of(textContent(callResult.isError ? "MCP tool returned an error" : ""));
		}
		return toolResult(content, filtered, callResult.isError);
	}

	private static ObjectNode mcpFilterResult(String toolName, ObjectNode result, List<McpResultFilter> filters) {
		Set<String> dropKeys = new LinkedHashSet<>();
		for (McpResultFilter filter : filters) {
			if (mcpFilterMatches(filter, toolName)) dropKeys.addAll(filter.dropKeys);
		}
		if (dropKeys.isEmpty()) return result;
		ObjectNode filtered = result.deepCopy();
		mcpFilterNode(filtered, dropKeys);
		return filtered;
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

	private static List<UserContent> mcpConvertContent(ObjectNode result) {
		List<UserContent> output = new ArrayList<>();
		JsonNode content = result.get("content");
		if (content != null && content.isArray()) {
			for (JsonNode item : content) mcpAppendContent(output, item);
		}
		if (output.isEmpty()) {
			JsonNode structured = result.get("structuredContent");
			if (structured != null && !structured.isNull()) output.add(textContent(structured.toString()));
		}
		return List.copyOf(output);
	}

	private static void mcpAppendContent(List<UserContent> output, JsonNode item) {
		if (!item.isObject()) {
			output.add(textContent(item.toString()));
			return;
		}
		switch (item.path("type").asText()) {
			case "text" -> output.add(textContent(item.path("text").asText("")));
			case "image" -> {
				if (item.path("data").isTextual() && item.path("mimeType").isTextual()) {
					output.add(imageContent(item.path("data").asText(), item.path("mimeType").asText()));
				} else output.add(textContent(item.toString()));
			}
			case "resource" -> mcpAppendResource(output, item.path("resource"));
			case "resource_link" -> {
				String label = item.path("name").isTextual()
						? item.path("name").asText()
						: item.path("uri").asText("resource");
				output.add(textContent(label + ": " + item.path("uri").asText(item.toString())));
			}
			case "audio" -> output.add(
					textContent("[MCP audio content: " + item.path("mimeType").asText("unknown type") + "]"));
			default -> output.add(textContent(item.toString()));
		}
	}

	private static void mcpAppendResource(List<UserContent> output, JsonNode resource) {
		if (!resource.isObject()) {
			output.add(textContent(resource.toString()));
			return;
		}
		if (resource.path("text").isTextual()) {
			output.add(textContent(resource.path("text").asText()));
			return;
		}
		if (resource.path("blob").isTextual()
				&& resource.path("mimeType").isTextual()
				&& resource.path("mimeType").asText().startsWith("image/")) {
			output.add(imageContent(resource.path("blob").asText(), resource.path("mimeType").asText()));
			return;
		}
		output.add(textContent(resource.toString()));
	}

	// ---------------------------------------------------------- mcp browser

	/** Opens an OAuth URL using the host platform without an external desktop dependency. */
	public static boolean mcpOpenBrowser(URI uri) {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		List<String> command;
		if (os.contains("mac")) command = List.of("open", uri.toString());
		else if (os.contains("win")) command = List.of("rundll32", "url.dll,FileProtocolHandler", uri.toString());
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
	}

	// ----------------------------------------------------- mcp server config

	/** Creates a stdio server configuration with unmodifiable command, environment, and filter lists. */
	public static McpServerConfig.Local localMcpServerConfig(
			List<String> command,
			String cwd,
			Map<String, String> environment,
			boolean enabled,
			Long timeoutMillis,
			List<McpResultFilter> resultFilters,
			List<String> disabledTools) {
		return new McpServerConfig.Local(
				List.copyOf(command),
				cwd,
				Map.copyOf(environment),
				enabled,
				timeoutMillis,
				List.copyOf(resultFilters),
				List.copyOf(disabledTools));
	}

	/** Creates a remote server configuration, defensively copying its headers and OAuth block. */
	public static McpServerConfig.Remote remoteMcpServerConfig(
			URI url,
			Map<String, String> headers,
			JsonNode oauth,
			boolean enabled,
			Long timeoutMillis,
			List<McpResultFilter> resultFilters,
			List<String> disabledTools) {
		return new McpServerConfig.Remote(
				url,
				Map.copyOf(headers),
				oauth == null ? null : oauth.deepCopy(),
				enabled,
				timeoutMillis,
				List.copyOf(resultFilters),
				List.copyOf(disabledTools));
	}

	/** Creates a result filter with an unmodifiable key list; the tool pattern is required. */
	public static McpResultFilter mcpResultFilter(String tool, List<String> dropKeys) {
		return new McpResultFilter(Objects.requireNonNull(tool, "tool"), List.copyOf(dropKeys));
	}

	/** Whether this server should be connected by a manager. */
	public static boolean mcpConfigEnabled(McpServerConfig config) {
		return switch (config) {
			case McpServerConfig.Local local -> local.enabled;
			case McpServerConfig.Remote remote -> remote.enabled;
		};
	}

	/** Request and startup timeout in milliseconds, or {@code null} for the default. */
	public static Long mcpConfigTimeoutMillis(McpServerConfig config) {
		return switch (config) {
			case McpServerConfig.Local local -> local.timeoutMillis;
			case McpServerConfig.Remote remote -> remote.timeoutMillis;
		};
	}

	/** Recursive JSON result filters configured for tools on this server. */
	public static List<McpResultFilter> mcpConfigResultFilters(McpServerConfig config) {
		return switch (config) {
			case McpServerConfig.Local local -> local.resultFilters;
			case McpServerConfig.Remote remote -> remote.resultFilters;
		};
	}

	/** Raw MCP tool names disabled by the user in this server's settings. */
	public static List<String> mcpConfigDisabledTools(McpServerConfig config) {
		return switch (config) {
			case McpServerConfig.Local local -> local.disabledTools;
			case McpServerConfig.Remote remote -> remote.disabledTools;
		};
	}

	/** Human-readable command line or URL this server connects to. */
	public static String mcpConfigTarget(McpServerConfig config) {
		return switch (config) {
			case McpServerConfig.Local local -> String.join(" ", local.command);
			case McpServerConfig.Remote remote -> remote.url.toString();
		};
	}

	/** Whether a result filter's glob pattern selects this raw MCP tool name. */
	public static boolean mcpFilterMatches(McpResultFilter filter, String toolName) {
		String tool = filter.tool;
		if (tool.indexOf('*') < 0 && tool.indexOf('?') < 0) return tool.equals(toolName);
		StringBuilder regex = new StringBuilder("^");
		for (int index = 0; index < tool.length(); index++) {
			switch (tool.charAt(index)) {
				case '*' -> regex.append(".*");
				case '?' -> regex.append('.');
				default -> regex.append(Pattern.quote(String.valueOf(tool.charAt(index))));
			}
		}
		return Pattern.compile(regex.append('$').toString(), Pattern.DOTALL).matcher(toolName).matches();
	}

	// ----------------------------------------------------- mcp configuration

	/** Creates a configuration with its own server map and an unmodifiable source list. */
	public static McpConfiguration mcpConfiguration(Map<String, McpServerConfig> servers, List<Path> sources) {
		return new McpConfiguration(new LinkedHashMap<>(servers), List.copyOf(sources));
	}

	/** Resolves the settings file and captures an unmodifiable substitution environment. */
	public static McpConfigLoader mcpConfigLoader(Path settingsPath, Map<String, String> environment) {
		return new McpConfigLoader(settingsPath.toAbsolutePath().normalize(), Map.copyOf(environment));
	}

	/** An MCP configuration with no servers and no source files. */
	public static McpConfiguration mcpEmptyConfiguration() {
		return mcpConfiguration(Map.of(), List.of());
	}

	/** Loads MCP servers from {@code ~/.codingagent/settings.json}. */
	public static McpConfiguration mcpLoadDefaultConfiguration() throws IOException {
		Path settingsPath = Path.of(System.getProperty("user.home"), ".codingagent", "settings.json");
		return mcpLoadConfiguration(mcpConfigLoader(settingsPath, System.getenv()));
	}

	/** Loads configured MCP servers, or an empty configuration when settings do not exist. */
	public static McpConfiguration mcpLoadConfiguration(McpConfigLoader loader) throws IOException {
		Path settingsPath = loader.settingsPath;
		if (!Files.exists(settingsPath)) return mcpEmptyConfiguration();
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
			root = Json.MAPPER.readTree(mcpSubstitute(loader, text, settingsPath.getParent()));
		} catch (IOException error) {
			throw new IOException("Failed to parse settings file " + settingsPath + ": " + error.getMessage(), error);
		}
		if (root == null || !root.isObject()) {
			throw new IOException("Invalid settings file " + settingsPath + ": expected a JSON object");
		}

		JsonNode mcp = root.get("mcp");
		if (mcp == null || mcp.isNull()) {
			return mcpConfiguration(Map.of(), List.of(settingsPath));
		}
		if (!mcp.isObject()) {
			throw new IOException("Invalid mcp in " + settingsPath + ": expected an object");
		}

		LinkedHashMap<String, McpServerConfig> servers = new LinkedHashMap<>();
		for (var entry : mcp.properties()) {
			if (!(entry.getValue() instanceof ObjectNode server)) {
				throw new IOException("Invalid MCP server \"" + entry.getKey() + "\": expected an object");
			}
			servers.put(entry.getKey(), mcpParseServer(entry.getKey(), server));
		}
		return mcpConfiguration(servers, List.of(settingsPath));
	}

	private static String mcpSubstitute(McpConfigLoader loader, String input, Path settingsDirectory)
			throws IOException {
		Matcher envMatcher = McpConfigLoader.ENVIRONMENT.matcher(input);
		StringBuffer environmentExpanded = new StringBuffer();
		while (envMatcher.find()) {
			envMatcher.appendReplacement(
					environmentExpanded,
					Matcher.quoteReplacement(loader.environment.getOrDefault(envMatcher.group(1), "")));
		}
		envMatcher.appendTail(environmentExpanded);
		String text = environmentExpanded.toString();

		Matcher fileMatcher = McpConfigLoader.FILE.matcher(text);
		StringBuilder output = new StringBuilder();
		int cursor = 0;
		while (fileMatcher.find()) {
			output.append(text, cursor, fileMatcher.start());
			String token = fileMatcher.group();
			String configuredPath = fileMatcher.group(1);
			Path file;
			if (configuredPath.equals("~")) file = Path.of(System.getProperty("user.home"));
			else if (configuredPath.startsWith("~/")) {
				file = Path.of(System.getProperty("user.home")).resolve(configuredPath.substring(2));
			} else {
				file = mcpResolvePath(settingsDirectory, configuredPath);
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
		return output.append(text, cursor, text.length()).toString();
	}

	private static McpServerConfig mcpParseServer(String name, ObjectNode value) throws IOException {
		if (name.isBlank()) throw mcpInvalidServer(name, "server name must not be blank");
		JsonNode typeNode = value.get("type");
		if (typeNode == null || !typeNode.isTextual()) throw mcpInvalidServer(name, "type must be a string");
		boolean enabled = mcpOptionalBoolean(name, value, "enabled", true);
		Long timeout = mcpOptionalPositiveLong(name, value, "timeout");
		List<McpResultFilter> resultFilters = mcpParseResultFilters(name, value);
		List<String> disabledTools = mcpParseDisabledTools(name, value);
		return switch (typeNode.asText()) {
			case "local" -> {
				JsonNode commandNode = value.get("command");
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
				String cwd = mcpOptionalText(name, value, "cwd");
				Map<String, String> environment = mcpStringMap(name, value, "environment");
				yield localMcpServerConfig(
						command, cwd, environment, enabled, timeout, resultFilters, disabledTools);
			}
			case "remote" -> {
				String rawUrl = mcpRequiredText(name, value, "url");
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
				JsonNode oauth = value.get("oauth");
				if (oauth != null && !oauth.isNull() && !oauth.isObject() && !oauth.isBoolean()) {
					throw mcpInvalidServer(name, "oauth must be an object or false");
				}
				if (oauth != null && oauth.isBoolean() && oauth.asBoolean()) {
					throw mcpInvalidServer(name, "oauth may be an object or false, not true");
				}
				if (oauth instanceof ObjectNode oauthObject) mcpValidateOAuthSettings(name, oauthObject);
				yield remoteMcpServerConfig(
						url,
						mcpStringMap(name, value, "headers"),
						oauth,
						enabled,
						timeout,
						resultFilters,
						disabledTools);
			}
			default -> throw mcpInvalidServer(name, "type must be local or remote");
		};
	}

	private static List<McpResultFilter> mcpParseResultFilters(String server, ObjectNode value) throws IOException {
		JsonNode node = value.get("resultFilters");
		if (node == null || node.isNull()) return List.of();
		if (!node.isArray()) throw mcpInvalidServer(server, "resultFilters must be an array");
		List<McpResultFilter> filters = new ArrayList<>();
		for (int index = 0; index < node.size(); index++) {
			JsonNode filter = node.get(index);
			if (!filter.isObject()) throw mcpInvalidServer(server, "resultFilters[" + index + "] must be an object");
			JsonNode tool = filter.get("tool");
			if (tool == null || !tool.isTextual() || tool.asText().isBlank()) {
				throw mcpInvalidServer(server, "resultFilters[" + index + "].tool must be a non-empty string");
			}
			JsonNode dropKeys = filter.get("dropKeys");
			if (dropKeys == null || !dropKeys.isArray() || dropKeys.isEmpty()) {
				throw mcpInvalidServer(
						server, "resultFilters[" + index + "].dropKeys must be a non-empty array of strings");
			}
			List<String> keys = new ArrayList<>();
			for (JsonNode key : dropKeys) {
				if (!key.isTextual() || key.asText().isBlank()) {
					throw mcpInvalidServer(
							server, "resultFilters[" + index + "].dropKeys must contain non-empty strings");
				}
				keys.add(key.asText());
			}
			filters.add(mcpResultFilter(tool.asText(), keys));
		}
		return List.copyOf(filters);
	}

	private static List<String> mcpParseDisabledTools(String server, ObjectNode value) throws IOException {
		JsonNode node = value.get("disabledTools");
		if (node == null || node.isNull()) return List.of();
		if (!node.isArray()) {
			throw mcpInvalidServer(server, "disabledTools must be an array of non-empty strings");
		}
		List<String> tools = new ArrayList<>();
		for (JsonNode tool : node) {
			if (!tool.isTextual() || tool.asText().isBlank()) {
				throw mcpInvalidServer(server, "disabledTools must contain non-empty strings");
			}
			tools.add(tool.asText());
		}
		return List.copyOf(tools);
	}

	private static void mcpValidateOAuthSettings(String server, ObjectNode oauth) throws IOException {
		for (String field : List.of("clientId", "clientSecret", "scope")) {
			JsonNode value = oauth.get(field);
			if (value != null && !value.isNull() && (!value.isTextual() || value.asText().isBlank())) {
				throw mcpInvalidServer(server, "oauth." + field + " must be a non-empty string");
			}
		}
		JsonNode callbackPort = oauth.get("callbackPort");
		if (callbackPort != null && !callbackPort.isNull()
				&& (!callbackPort.isIntegralNumber()
						|| !callbackPort.canConvertToInt()
						|| callbackPort.asInt() < 1
						|| callbackPort.asInt() > 65_535)) {
			throw mcpInvalidServer(server, "oauth.callbackPort must be an integer from 1 to 65535");
		}
		JsonNode redirect = oauth.get("redirectUri");
		if (redirect != null && !redirect.isNull()) {
			if (!redirect.isTextual() || redirect.asText().isBlank()) {
				throw mcpInvalidServer(server, "oauth.redirectUri must be a non-empty string");
			}
			try {
				URI uri = new URI(redirect.asText());
				String host = uri.getHost();
				if (!uri.isAbsolute()
						|| host == null
						|| !uri.getScheme().equalsIgnoreCase("http")
						|| !(host.equalsIgnoreCase("localhost") || host.startsWith("127.") || host.equals("::1"))
						|| uri.getFragment() != null) {
					throw mcpInvalidServer(server, "oauth.redirectUri must be an HTTP loopback URL without a fragment");
				}
			} catch (URISyntaxException error) {
				throw mcpInvalidServer(server, "oauth.redirectUri is invalid: " + redirect.asText());
			}
		}
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

	private static String mcpRequiredText(String server, ObjectNode value, String field) throws IOException {
		String result = mcpOptionalText(server, value, field);
		if (result == null || result.isBlank()) throw mcpInvalidServer(server, field + " must be a non-empty string");
		return result;
	}

	private static String mcpOptionalText(String server, ObjectNode value, String field) throws IOException {
		JsonNode node = value.get(field);
		if (node == null || node.isNull()) return null;
		if (!node.isTextual()) throw mcpInvalidServer(server, field + " must be a string");
		return node.asText();
	}

	private static boolean mcpOptionalBoolean(String server, ObjectNode value, String field, boolean fallback)
			throws IOException {
		JsonNode node = value.get(field);
		if (node == null || node.isNull()) return fallback;
		if (!node.isBoolean()) throw mcpInvalidServer(server, field + " must be a boolean");
		return node.asBoolean();
	}

	private static Long mcpOptionalPositiveLong(String server, ObjectNode value, String field) throws IOException {
		JsonNode node = value.get(field);
		if (node == null || node.isNull()) return null;
		if (!node.isIntegralNumber() || !node.canConvertToLong() || node.asLong() <= 0) {
			throw mcpInvalidServer(server, field + " must be a positive integer");
		}
		return node.asLong();
	}

	private static Path mcpResolvePath(Path base, String value) {
		Path path = Path.of(value);
		return (path.isAbsolute() ? path : base.resolve(path)).toAbsolutePath().normalize();
	}

	private static IOException mcpInvalidServer(String server, String message) {
		return new IOException("Invalid MCP server \"" + server + "\": " + message);
	}

	// --------------------------------------------------------- mcp transport

	/** Sends one JSON-RPC request over the transport that owns this connection. */
	public static JsonNode mcpTransportRequest(
			McpTransport transport, String method, ObjectNode params, Duration timeout, AbortSignal signal)
			throws Exception {
		return switch (transport) {
			case StdioMcpTransport stdio -> mcpStdioRequest(stdio, method, params, timeout, signal);
			case StreamableHttpMcpTransport http -> mcpStreamableRequest(http, method, params, timeout, signal);
			case SseHttpMcpTransport sse -> mcpSseRequest(sse, method, params, timeout, signal);
		};
	}

	/** Sends one JSON-RPC notification. */
	public static void mcpTransportNotify(McpTransport transport, String method, ObjectNode params) throws Exception {
		switch (transport) {
			case StdioMcpTransport stdio -> mcpStdioNotify(stdio, method, params);
			case StreamableHttpMcpTransport http -> mcpStreamableNotify(http, method, params);
			case SseHttpMcpTransport sse -> mcpSseNotify(sse, method, params);
		}
	}

	/** Records the protocol version negotiated during initialize. */
	public static void mcpTransportProtocolVersion(McpTransport transport, String version) {
		switch (transport) {
			case StdioMcpTransport ignored -> {}
			case StreamableHttpMcpTransport http -> http.protocolVersion = version;
			case SseHttpMcpTransport sse -> sse.protocolVersion = version;
		}
	}

	/** Installs the listener invoked for server-initiated notifications. */
	public static void mcpTransportOnNotification(McpTransport transport, BiConsumer<String, JsonNode> listener) {
		BiConsumer<String, JsonNode> effective = listener == null ? (method, params) -> {} : listener;
		switch (transport) {
			case StdioMcpTransport stdio -> stdio.notificationListener = effective;
			case StreamableHttpMcpTransport http -> http.notificationListener = effective;
			case SseHttpMcpTransport sse -> sse.notificationListener = effective;
		}
	}

	/** Releases the process, sockets, and streams owned by this transport. */
	public static void mcpTransportClose(McpTransport transport) {
		switch (transport) {
			case StdioMcpTransport stdio -> mcpStdioClose(stdio);
			case StreamableHttpMcpTransport http -> mcpStreamableClose(http);
			case SseHttpMcpTransport sse -> mcpSseClose(sse);
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

	private static ObjectNode mcpRootsResult(Path workspace) {
		ObjectNode result = jsonObject();
		result.putArray("roots")
				.addObject()
				.put("uri", workspace.toUri().toString())
				.put(
						"name",
						workspace.getFileName() == null
								? workspace.toString()
								: workspace.getFileName().toString());
		return result;
	}

	// --------------------------------------------------- mcp stdio transport

	/** Starts a local MCP server process and its reader threads. */
	public static StdioMcpTransport mcpOpenStdioTransport(McpServerConfig.Local config, Path workspace)
			throws IOException {
		StdioMcpTransport transport = new StdioMcpTransport();
		transport.workspace = workspace.toAbsolutePath().normalize();
		Path processDirectory = config.cwd == null || config.cwd.isBlank()
				? transport.workspace
				: mcpResolvePath(transport.workspace, config.cwd);
		ProcessBuilder builder = new ProcessBuilder(config.command);
		builder.directory(processDirectory.toFile());
		builder.environment().putAll(config.environment);
		transport.process = builder.start();
		transport.writer = new BufferedWriter(
				new OutputStreamWriter(transport.process.getOutputStream(), StandardCharsets.UTF_8));
		Thread.ofVirtual().name("mcp-stdio-reader").start(() -> mcpStdioReadMessages(transport));
		Thread.ofVirtual().name("mcp-stderr-reader").start(() -> mcpStdioReadStderr(transport));
		transport.process
				.onExit()
				.thenRun(() -> mcpStdioFailPending(transport, new IOException(mcpStdioExitMessage(transport))));
		return transport;
	}

	private static JsonNode mcpStdioRequest(
			StdioMcpTransport transport, String method, ObjectNode params, Duration timeout, AbortSignal signal)
			throws Exception {
		long id = transport.nextId.getAndIncrement();
		CompletableFuture<JsonNode> response = new CompletableFuture<>();
		transport.pending.put(id, response);
		ObjectNode message = jsonObject().put("jsonrpc", "2.0").put("id", id).put("method", method);
		if (params != null) message.set("params", params);
		try {
			mcpStdioWrite(transport, message);
		} catch (Exception error) {
			transport.pending.remove(id);
			throw error;
		}

		long deadline = System.nanoTime() + timeout.toNanos();
		try {
			while (true) {
				if ((signal != null && isAborted(signal)) || Thread.currentThread().isInterrupted()) {
					mcpStdioCancel(transport, id, "Request cancelled");
					throw new InterruptedException("MCP request cancelled");
				}
				long remaining = deadline - System.nanoTime();
				if (remaining <= 0) {
					mcpStdioCancel(transport, id, "Request timed out");
					throw new TimeoutException(
							"MCP request " + method + " timed out after " + timeout.toMillis() + "ms");
				}
				try {
					return response.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS);
				} catch (TimeoutException ignored) {
					// Poll so the agent's cooperative abort signal is observed promptly.
				} catch (InterruptedException error) {
					mcpStdioCancel(transport, id, "Request cancelled");
					Thread.currentThread().interrupt();
					throw error;
				} catch (ExecutionException error) {
					Throwable cause = error.getCause();
					if (cause instanceof Exception exception) throw exception;
					throw new IOException(String.valueOf(cause), cause);
				}
			}
		} finally {
			transport.pending.remove(id);
		}
	}

	private static void mcpStdioNotify(StdioMcpTransport transport, String method, ObjectNode params)
			throws IOException {
		ObjectNode message = jsonObject().put("jsonrpc", "2.0").put("method", method);
		if (params != null) message.set("params", params);
		mcpStdioWrite(transport, message);
	}

	private static void mcpStdioReadMessages(StdioMcpTransport transport) {
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
				mcpStdioFailPending(transport, new IOException(mcpStdioExitMessage(transport)));
			}
		} catch (IOException error) {
			if (!transport.closed) mcpStdioFailPending(transport, error);
		}
	}

	private static void mcpStdioDispatch(StdioMcpTransport transport, JsonNode message) {
		if (message.isArray()) {
			for (JsonNode item : message) mcpStdioDispatch(transport, item);
			return;
		}
		if (!message.isObject()) return;
		JsonNode method = message.get("method");
		JsonNode id = message.get("id");
		if (method != null && method.isTextual()) {
			JsonNode params = message.get("params");
			if (id != null && !id.isNull()) mcpStdioRespondToServerRequest(transport, id, method.asText(), params);
			else transport.notificationListener.accept(method.asText(), params);
			return;
		}
		if (id == null || !id.canConvertToLong()) return;
		CompletableFuture<JsonNode> future = transport.pending.get(id.asLong());
		if (future == null) return;
		JsonNode error = message.get("error");
		if (error != null && !error.isNull()) future.completeExceptionally(mcpRpcError(error, true));
		else future.complete(message.get("result"));
	}

	private static void mcpStdioRespondToServerRequest(
			StdioMcpTransport transport, JsonNode id, String method, JsonNode params) {
		ObjectNode response = jsonObject().put("jsonrpc", "2.0");
		response.set("id", id);
		switch (method) {
			case "ping" -> response.set("result", jsonObject());
			case "roots/list" -> response.set("result", mcpRootsResult(transport.workspace));
			default -> response.set(
					"error", jsonObject().put("code", -32601).put("message", "Client does not support " + method));
		}
		try {
			mcpStdioWrite(transport, response);
		} catch (IOException ignored) {
			// A transport failure is reported to pending client requests by the reader/process watcher.
		}
	}

	private static void mcpStdioCancel(StdioMcpTransport transport, long id, String reason) {
		transport.pending.remove(id);
		try {
			mcpStdioNotify(
					transport, "notifications/cancelled", jsonObject().put("requestId", id).put("reason", reason));
		} catch (IOException ignored) {
			// Cancellation is best effort.
		}
	}

	private static void mcpStdioWrite(StdioMcpTransport transport, JsonNode message) throws IOException {
		if (transport.closed) throw new IOException("MCP stdio transport is closed");
		synchronized (transport.writeLock) {
			transport.writer.write(Json.MAPPER.writeValueAsString(message));
			transport.writer.newLine();
			transport.writer.flush();
		}
	}

	private static void mcpStdioReadStderr(StdioMcpTransport transport) {
		try (InputStream input = transport.process.getErrorStream()) {
			byte[] buffer = new byte[2_048];
			int count;
			while ((count = input.read(buffer)) >= 0) {
				mcpStdioAppendStderr(transport, new String(buffer, 0, count, StandardCharsets.UTF_8));
			}
		} catch (IOException ignored) {
			// Stderr is diagnostic only.
		}
	}

	private static void mcpStdioAppendStderr(StdioMcpTransport transport, String value) {
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

	private static void mcpStdioFailPending(StdioMcpTransport transport, Exception error) {
		for (CompletableFuture<JsonNode> future : transport.pending.values()) future.completeExceptionally(error);
		transport.pending.clear();
	}

	private static void mcpStdioClose(StdioMcpTransport transport) {
		if (transport.closed) return;
		transport.closed = true;
		mcpStdioFailPending(transport, new IOException("MCP stdio transport closed"));
		try {
			transport.writer.close();
		} catch (IOException ignored) {}
		transport.process.descendants().forEach(handle -> {
			try {
				handle.destroy();
			} catch (RuntimeException ignored) {}
		});
		transport.process.destroy();
		try {
			if (!transport.process.waitFor(300, TimeUnit.MILLISECONDS)) {
				transport.process.descendants().forEach(ProcessHandle::destroyForcibly);
				transport.process.destroyForcibly();
			}
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			transport.process.destroyForcibly();
		}
	}

	// ---------------------------------------- mcp streamable http transport

	/** Prepares the Streamable HTTP transport for a remote MCP server. */
	public static StreamableHttpMcpTransport mcpOpenStreamableTransport(
			McpServerConfig.Remote config, Path workspace, McpOAuthClient.Session oauth) {
		StreamableHttpMcpTransport transport = new StreamableHttpMcpTransport();
		transport.url = config.url;
		transport.headers = config.headers;
		transport.oauth = oauth;
		transport.workspace = workspace.toAbsolutePath().normalize();
		return transport;
	}

	private static JsonNode mcpStreamableRequest(
			StreamableHttpMcpTransport transport,
			String method,
			ObjectNode params,
			Duration timeout,
			AbortSignal signal)
			throws Exception {
		long id = transport.nextId.getAndIncrement();
		ObjectNode envelope = jsonObject().put("jsonrpc", "2.0").put("id", id).put("method", method);
		if (params != null) envelope.set("params", params);
		HttpResponse<String> response = mcpStreamablePost(transport, envelope, timeout, signal, true);
		for (JsonNode message : mcpStreamableResponseMessages(transport, response)) {
			if (mcpIsRequestId(message.get("id"), id) && message.get("method") == null) {
				JsonNode error = message.get("error");
				if (error != null && !error.isNull()) throw mcpRpcError(error, true);
				return message.get("result");
			}
			mcpStreamableDispatchServerMessage(transport, message, timeout);
		}
		throw new IOException("MCP HTTP response did not contain JSON-RPC result for " + method);
	}

	private static void mcpStreamableNotify(StreamableHttpMcpTransport transport, String method, ObjectNode params)
			throws Exception {
		ObjectNode envelope = jsonObject().put("jsonrpc", "2.0").put("method", method);
		if (params != null) envelope.set("params", params);
		HttpResponse<String> response = mcpStreamablePost(transport, envelope, Duration.ofSeconds(10), null, true);
		for (JsonNode message : mcpStreamableResponseMessages(transport, response)) {
			mcpStreamableDispatchServerMessage(transport, message, Duration.ofSeconds(10));
		}
		if (method.equals("notifications/initialized") && response.statusCode() == 202) {
			mcpStreamableStartListener(transport);
		}
	}

	private static HttpResponse<String> mcpStreamablePost(
			StreamableHttpMcpTransport transport,
			JsonNode message,
			Duration timeout,
			AbortSignal signal,
			boolean authRetry)
			throws Exception {
		if (transport.closed) throw new IOException("MCP HTTP transport is closed");
		String bearer = transport.oauth == null ? null : mcpAccessToken(transport.oauth);
		HttpRequest.Builder request = mcpStreamableRequestBuilder(transport, transport.url, timeout, bearer)
				.setHeader("Content-Type", "application/json")
				.setHeader("Accept", "application/json, text/event-stream")
				.POST(HttpRequest.BodyPublishers.ofString(
						Json.MAPPER.writeValueAsString(message), StandardCharsets.UTF_8));
		HttpResponse<String> response = mcpAwaitHttp(
				transport.client.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)),
				timeout,
				signal);
		mcpCaptureStreamableSession(transport, response);
		int status = response.statusCode();
		if (status < 200 || status >= 300) {
			McpHttpException failure = mcpStreamableHttpError(transport, response);
			if (authRetry
					&& transport.oauth != null
					&& mcpRefreshAfterUnauthorized(transport.oauth, failure, bearer)) {
				return mcpStreamablePost(transport, message, timeout, signal, false);
			}
			throw failure;
		}
		return response;
	}

	private static List<JsonNode> mcpStreamableResponseMessages(
			StreamableHttpMcpTransport transport, HttpResponse<String> response) throws IOException {
		String body = response.body();
		if (body == null || body.isBlank() || response.statusCode() == 202) return List.of();
		String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
		if (contentType.contains("text/event-stream") || body.stripLeading().startsWith("event:")) {
			return mcpParseSseBody(body);
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

	private static List<JsonNode> mcpParseSseBody(String body) throws IOException {
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

	private static void mcpAddSseData(List<JsonNode> result, StringBuilder data) throws IOException {
		if (data.isEmpty()) return;
		JsonNode parsed = Json.MAPPER.readTree(data.toString());
		if (parsed.isArray()) parsed.forEach(result::add);
		else result.add(parsed);
		data.setLength(0);
	}

	private static void mcpStreamableDispatchServerMessage(
			StreamableHttpMcpTransport transport, JsonNode message, Duration timeout) {
		if (!message.isObject() || !message.path("method").isTextual()) return;
		String method = message.path("method").asText();
		JsonNode id = message.get("id");
		JsonNode params = message.get("params");
		if (id == null || id.isNull()) {
			transport.notificationListener.accept(method, params);
			return;
		}
		ObjectNode response = jsonObject().put("jsonrpc", "2.0");
		response.set("id", id);
		if (method.equals("ping")) response.set("result", jsonObject());
		else if (method.equals("roots/list")) response.set("result", mcpRootsResult(transport.workspace));
		else response.set(
				"error", jsonObject().put("code", -32601).put("message", "Client does not support " + method));
		try {
			mcpStreamablePost(transport, response, timeout, null, true);
		} catch (Exception ignored) {
			// The original request will report a transport failure if one occurs.
		}
	}

	private static void mcpStreamableStartListener(StreamableHttpMcpTransport transport) {
		if (transport.closed || transport.listenerRequest != null) return;
		HttpRequest request;
		try {
			String bearer = transport.oauth == null ? null : mcpAccessToken(transport.oauth);
			request = mcpStreamableRequestBuilder(transport, transport.url, null, bearer)
					.setHeader("Accept", "text/event-stream")
					.GET()
					.build();
		} catch (Exception ignored) {
			return;
		}
		CompletableFuture<HttpResponse<InputStream>> future =
				transport.client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
		transport.listenerRequest = future;
		future.whenComplete((response, error) -> {
			if (transport.closed || error != null || response == null) return;
			if (response.statusCode() == 405) {
				try {
					response.body().close();
				} catch (IOException ignored) {}
				return;
			}
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				try {
					response.body().close();
				} catch (IOException ignored) {}
				return;
			}
			transport.listenerStream = response.body();
			Thread.ofVirtual()
					.name("mcp-http-listener")
					.start(() -> mcpStreamableReadListener(transport, response.body()));
		});
	}

	private static void mcpStreamableReadListener(StreamableHttpMcpTransport transport, InputStream stream) {
		try (BufferedReader input = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
			StringBuilder data = new StringBuilder();
			String line;
			while (!transport.closed && (line = input.readLine()) != null) {
				if (line.isEmpty()) {
					mcpStreamableDispatchListenerData(transport, data);
				} else if (line.startsWith("data:")) {
					if (!data.isEmpty()) data.append('\n');
					data.append(line.substring(5).stripLeading());
				}
			}
			mcpStreamableDispatchListenerData(transport, data);
		} catch (IOException ignored) {
			// The optional GET stream may be unavailable or close at any time.
		}
	}

	private static void mcpStreamableDispatchListenerData(StreamableHttpMcpTransport transport, StringBuilder data) {
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

	private static HttpRequest.Builder mcpStreamableRequestBuilder(
			StreamableHttpMcpTransport transport, URI target, Duration timeout, String bearer) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(target);
		if (timeout != null) builder.timeout(timeout);
		for (var header : transport.headers.entrySet()) {
			if (!mcpRestrictedHeader(header.getKey())) builder.header(header.getKey(), header.getValue());
		}
		if (bearer != null) builder.setHeader("Authorization", "Bearer " + bearer);
		if (transport.sessionId != null) builder.setHeader("Mcp-Session-Id", transport.sessionId);
		if (transport.protocolVersion != null) {
			builder.setHeader("MCP-Protocol-Version", transport.protocolVersion);
		}
		return builder;
	}

	private static void mcpCaptureStreamableSession(StreamableHttpMcpTransport transport, HttpResponse<?> response) {
		response.headers().firstValue("Mcp-Session-Id").ifPresent(value -> transport.sessionId = value);
	}

	private static McpHttpException mcpStreamableHttpError(
			StreamableHttpMcpTransport transport, HttpResponse<String> response) {
		int status = response.statusCode();
		String body = response.body();
		String message = switch (status) {
			case 401 -> "MCP server requires authentication";
			case 403 -> "MCP server rejected the configured credentials";
			case 404 -> "MCP endpoint was not found";
			case 405 -> "MCP endpoint does not support Streamable HTTP";
			default -> "MCP server returned HTTP " + status;
		};
		if (body != null && !body.isBlank()) message += ": " + mcpAbbreviate(body);
		return mcpHttpException(
				status, response.uri(), response.headers().map(), body, message + " (" + transport.url + ")");
	}

	private static void mcpStreamableClose(StreamableHttpMcpTransport transport) {
		if (transport.closed) return;
		transport.closed = true;
		if (transport.listenerRequest != null) transport.listenerRequest.cancel(true);
		if (transport.listenerStream != null) {
			try {
				transport.listenerStream.close();
			} catch (IOException ignored) {}
		}
		if (transport.sessionId == null) return;
		try {
			String bearer = transport.oauth == null ? null : mcpCachedAccessToken(transport.oauth);
			HttpRequest request = mcpStreamableRequestBuilder(transport, transport.url, Duration.ofSeconds(2), bearer)
					.DELETE()
					.build();
			transport.client.sendAsync(request, HttpResponse.BodyHandlers.discarding());
		} catch (Exception ignored) {
			// Session deletion is best effort.
		}
	}

	private static boolean mcpIsRequestId(JsonNode value, long id) {
		return value != null && value.canConvertToLong() && value.asLong() == id;
	}

	private static <T> T mcpAwaitHttp(CompletableFuture<T> future, Duration timeout, AbortSignal signal)
			throws Exception {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (true) {
			if ((signal != null && isAborted(signal)) || Thread.currentThread().isInterrupted()) {
				future.cancel(true);
				throw new InterruptedException("MCP HTTP request cancelled");
			}
			long remaining = deadline - System.nanoTime();
			if (remaining <= 0) {
				future.cancel(true);
				throw new TimeoutException("MCP HTTP request timed out after " + timeout.toMillis() + "ms");
			}
			try {
				return future.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS);
			} catch (TimeoutException ignored) {
				// Poll cancellation.
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

	// ----------------------------------------------------- mcp sse transport

	/** Opens the legacy HTTP+SSE transport and begins discovering its message endpoint. */
	public static SseHttpMcpTransport mcpOpenSseTransport(
			McpServerConfig.Remote config, Path workspace, McpOAuthClient.Session oauth) {
		SseHttpMcpTransport transport = new SseHttpMcpTransport();
		transport.url = config.url;
		transport.headers = config.headers;
		transport.oauth = oauth;
		transport.workspace = workspace.toAbsolutePath().normalize();
		mcpSseOpenEventStream(transport, true);
		return transport;
	}

	private static void mcpSseOpenEventStream(SseHttpMcpTransport transport, boolean authRetry) {
		String bearer;
		try {
			bearer = transport.oauth == null ? null : mcpAccessToken(transport.oauth);
		} catch (Exception error) {
			transport.endpoint.completeExceptionally(error);
			return;
		}
		HttpRequest request = mcpSseRequestBuilder(transport, transport.url, Duration.ofSeconds(30), bearer)
				.setHeader("Accept", "text/event-stream")
				.GET()
				.build();
		transport.opening = transport.client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
		transport.opening.whenComplete((response, error) -> {
			if (transport.closed) {
				if (response != null) {
					try {
						response.body().close();
					} catch (IOException ignored) {}
				}
				return;
			}
			if (error != null) {
				transport.endpoint.completeExceptionally(error);
				mcpSseFailPending(transport, new IOException("Failed to open MCP SSE stream", error));
				return;
			}
			mcpCaptureSseSession(transport, response);
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				String body = mcpReadErrorBody(response.body());
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
					mcpSseFailPending(transport, refreshError);
					return;
				}
				transport.endpoint.completeExceptionally(failure);
				mcpSseFailPending(transport, failure);
				return;
			}
			transport.eventStream = response.body();
			Thread.ofVirtual().name("mcp-sse-reader").start(() -> mcpSseReadEvents(transport, response.body()));
		});
	}

	private static JsonNode mcpSseRequest(
			SseHttpMcpTransport transport, String method, ObjectNode params, Duration timeout, AbortSignal signal)
			throws Exception {
		URI target = mcpAwaitSse(transport.endpoint, timeout, signal);
		long id = transport.nextId.getAndIncrement();
		CompletableFuture<JsonNode> result = new CompletableFuture<>();
		transport.pending.put(id, result);
		ObjectNode envelope = jsonObject().put("jsonrpc", "2.0").put("id", id).put("method", method);
		if (params != null) envelope.set("params", params);
		try {
			mcpSsePost(transport, target, envelope, timeout, signal, true);
			return mcpAwaitSse(result, timeout, signal);
		} catch (TimeoutException | InterruptedException error) {
			try {
				mcpSseNotify(
						transport,
						"notifications/cancelled",
						jsonObject().put("requestId", id).put("reason", error.getMessage()));
			} catch (Exception ignored) {}
			throw error;
		} finally {
			transport.pending.remove(id);
		}
	}

	private static void mcpSseNotify(SseHttpMcpTransport transport, String method, ObjectNode params) throws Exception {
		URI target = mcpAwaitSse(transport.endpoint, Duration.ofSeconds(10), null);
		ObjectNode envelope = jsonObject().put("jsonrpc", "2.0").put("method", method);
		if (params != null) envelope.set("params", params);
		mcpSsePost(transport, target, envelope, Duration.ofSeconds(10), null, true);
	}

	private static void mcpSsePost(
			SseHttpMcpTransport transport,
			URI target,
			JsonNode message,
			Duration timeout,
			AbortSignal signal,
			boolean authRetry)
			throws Exception {
		String bearer = transport.oauth == null ? null : mcpAccessToken(transport.oauth);
		HttpRequest request = mcpSseRequestBuilder(transport, target, timeout, bearer)
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

	private static void mcpSseReadEvents(SseHttpMcpTransport transport, InputStream stream) {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
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
			if (!transport.closed) mcpSseFailPending(transport, new IOException("MCP SSE event stream closed"));
		} catch (Exception error) {
			if (!transport.closed) {
				transport.endpoint.completeExceptionally(error);
				mcpSseFailPending(transport, error);
			}
		}
	}

	private static void mcpSseDispatchEvent(SseHttpMcpTransport transport, String event, String data)
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

	private static void mcpSseDispatch(SseHttpMcpTransport transport, JsonNode message) {
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
			else mcpSseRespondToServerRequest(transport, id, method);
			return;
		}
		if (id == null || !id.canConvertToLong()) return;
		CompletableFuture<JsonNode> result = transport.pending.get(id.asLong());
		if (result == null) return;
		JsonNode error = message.get("error");
		if (error != null && !error.isNull()) result.completeExceptionally(mcpRpcError(error, false));
		else result.complete(message.get("result"));
	}

	private static void mcpSseRespondToServerRequest(SseHttpMcpTransport transport, JsonNode id, String method) {
		ObjectNode response = jsonObject().put("jsonrpc", "2.0");
		response.set("id", id);
		if (method.equals("ping")) response.set("result", jsonObject());
		else if (method.equals("roots/list")) response.set("result", mcpRootsResult(transport.workspace));
		else response.set(
				"error", jsonObject().put("code", -32601).put("message", "Client does not support " + method));
		transport.endpoint.thenAccept(target -> Thread.ofVirtual().start(() -> {
			try {
				mcpSsePost(transport, target, response, Duration.ofSeconds(10), null, true);
			} catch (Exception ignored) {}
		}));
	}

	private static HttpRequest.Builder mcpSseRequestBuilder(
			SseHttpMcpTransport transport, URI target, Duration timeout, String bearer) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(target).timeout(timeout);
		for (var header : transport.headers.entrySet()) {
			if (!mcpRestrictedHeader(header.getKey())) builder.header(header.getKey(), header.getValue());
		}
		if (bearer != null) builder.setHeader("Authorization", "Bearer " + bearer);
		if (transport.sessionId != null) builder.setHeader("Mcp-Session-Id", transport.sessionId);
		if (transport.protocolVersion != null) {
			builder.setHeader("MCP-Protocol-Version", transport.protocolVersion);
		}
		return builder;
	}

	private static void mcpCaptureSseSession(SseHttpMcpTransport transport, HttpResponse<?> response) {
		response.headers().firstValue("Mcp-Session-Id").ifPresent(value -> transport.sessionId = value);
	}

	private static <T> T mcpAwaitSse(CompletableFuture<T> future, Duration timeout, AbortSignal signal)
			throws Exception {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (true) {
			if ((signal != null && isAborted(signal)) || Thread.currentThread().isInterrupted()) {
				throw new InterruptedException("MCP request cancelled");
			}
			long remaining = deadline - System.nanoTime();
			if (remaining <= 0) throw new TimeoutException("MCP request timed out after " + timeout.toMillis() + "ms");
			try {
				return future.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS);
			} catch (TimeoutException ignored) {
				// Poll cancellation.
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

	private static void mcpSseFailPending(SseHttpMcpTransport transport, Exception error) {
		for (CompletableFuture<JsonNode> future : transport.pending.values()) future.completeExceptionally(error);
		transport.pending.clear();
	}

	private static void mcpSseClose(SseHttpMcpTransport transport) {
		if (transport.closed) return;
		transport.closed = true;
		if (transport.opening != null) transport.opening.cancel(true);
		mcpSseFailPending(transport, new IOException("MCP SSE transport closed"));
		transport.endpoint.completeExceptionally(new IOException("MCP SSE transport closed"));
		InputStream stream = transport.eventStream;
		if (stream != null) {
			try {
				stream.close();
			} catch (IOException ignored) {}
		}
	}

	private static String mcpReadErrorBody(InputStream stream) {
		try (stream) {
			byte[] bytes = stream.readNBytes(4_001);
			String value = new String(bytes, 0, Math.min(bytes.length, 4_000), StandardCharsets.UTF_8);
			return bytes.length > 4_000 ? value + "..." : value;
		} catch (IOException ignored) {
			return "";
		}
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

	/** Builds an MCP HTTP failure with its response headers normalized to lower case. */
	public static McpHttpException mcpHttpException(
			int status, URI uri, Map<String, List<String>> headers, String body, String message) {
		LinkedHashMap<String, List<String>> copied = new LinkedHashMap<>();
		headers.forEach((name, values) -> copied.put(name.toLowerCase(Locale.ROOT), List.copyOf(values)));
		return new McpHttpException(status, uri, Map.copyOf(copied), body == null ? "" : body, message);
	}

	/** Returns one response header of an MCP HTTP failure, joining repeated values. */
	public static String mcpHttpHeader(McpHttpException error, String name) {
		List<String> values = error.headers.get(name.toLowerCase(Locale.ROOT));
		return values == null || values.isEmpty() ? null : String.join(", ", values);
	}

	/** Finds the MCP HTTP failure in a cause or suppressed chain. */
	public static McpHttpException mcpFindHttpException(Throwable error) {
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

	/** Connects and initializes one MCP client session for a configured server. */
	public static McpClient mcpConnect(
			String serverName,
			McpServerConfig config,
			Path workspace,
			McpOAuthClient oauth,
			boolean interactiveOAuth,
			Consumer<URI> authorizationListener)
			throws Exception {
		Long configuredTimeout = mcpConfigTimeoutMillis(config);
		Duration timeout = configuredTimeout == null
				? McpClient.DEFAULT_TIMEOUT
				: Duration.ofMillis(configuredTimeout);
		if (config instanceof McpServerConfig.Local local) {
			return mcpInitializeOwned(mcpOpenStdioTransport(local, workspace), timeout);
		}

		McpServerConfig.Remote remote = (McpServerConfig.Remote) config;
		McpOAuthClient.Session oauthSession = mcpOAuthSession(oauth, serverName, remote);
		try {
			return mcpConnectRemote(remote, workspace, timeout, oauthSession);
		} catch (Exception error) {
			if (oauthSession == null || !mcpIsOAuthChallenge(error)) throw error;
			if (!interactiveOAuth) {
				throw new McpOAuthRequiredException(
						mcpOAuthRequiredMessage(serverName, mcpIsInsufficientScope(error)));
			}
			McpHttpException challenge = mcpFindHttpException(error);
			mcpAuthorize(oauthSession, challenge, authorizationListener);
			try {
				return mcpConnectRemote(remote, workspace, timeout, oauthSession);
			} catch (Exception retryError) {
				if (mcpIsOAuthChallenge(retryError)) {
					throw new IOException("MCP server rejected the OAuth token after authorization", retryError);
				}
				throw retryError;
			}
		}
	}

	private static String mcpOAuthRequiredMessage(String serverName, boolean additionalScopes) {
		return additionalScopes
				? "MCP server \"" + serverName + "\" requires additional OAuth permissions; press Enter to authorize"
				: "MCP server \"" + serverName + "\" requires OAuth authentication; press Enter to authorize";
	}

	private static McpClient mcpConnectRemote(
			McpServerConfig.Remote remote, Path workspace, Duration timeout, McpOAuthClient.Session oauth)
			throws Exception {
		Exception streamableFailure;
		McpTransport streamable = mcpOpenStreamableTransport(remote, workspace, oauth);
		try {
			return mcpInitializeOwned(streamable, timeout);
		} catch (Exception error) {
			streamableFailure = error;
			if (error instanceof InterruptedException || Thread.currentThread().isInterrupted()) throw error;
			if (oauth != null && mcpIsOAuthChallenge(error)) throw error;
		}
		McpTransport sse = mcpOpenSseTransport(remote, workspace, oauth);
		try {
			return mcpInitializeOwned(sse, timeout);
		} catch (Exception error) {
			error.addSuppressed(streamableFailure);
			throw error;
		}
	}

	private static McpClient mcpInitializeOwned(McpTransport transport, Duration timeout) throws Exception {
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
			mcpTransportProtocolVersion(transport, negotiated);
			McpClient client = new McpClient();
			client.transport = transport;
			client.timeout = timeout;
			client.capabilities = result.path("capabilities") instanceof ObjectNode object
					? object.deepCopy()
					: jsonObject();
			client.instructions = result.path("instructions").isTextual()
					? result.path("instructions").asText().trim()
					: null;
			mcpTransportNotify(transport, "notifications/initialized", jsonObject());
			return client;
		} catch (Exception error) {
			mcpTransportClose(transport);
			throw error;
		}
	}

	/** Reads the full paginated tool catalog advertised by a connected server. */
	public static List<McpClient.ToolDefinition> mcpListTools(McpClient client) throws Exception {
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
			for (JsonNode tool : response.path("tools")) result.add(mcpParseToolDefinition(tool));
			JsonNode next = response.get("nextCursor");
			if (next == null || next.isNull()) return List.copyOf(result);
			if (!next.isTextual()) throw new IOException("MCP tools/list nextCursor must be a string");
			cursor = next.asText();
			if (!cursors.add(cursor)) throw new IOException("MCP tools/list returned duplicate cursor: " + cursor);
		}
		throw new IOException("MCP tools/list exceeded " + McpClient.MAX_LIST_PAGES + " pages");
	}

	/** Calls one raw MCP tool and returns its unfiltered result. */
	public static McpClient.CallResult mcpCallTool(
			McpClient client, String name, ObjectNode arguments, AbortSignal signal) throws Exception {
		ObjectNode params = jsonObject().put("name", name);
		params.set("arguments", arguments == null ? jsonObject() : arguments);
		JsonNode response = mcpTransportRequest(client.transport, "tools/call", params, client.timeout, signal);
		if (!(response instanceof ObjectNode object)) {
			throw new IOException("MCP tools/call returned an invalid result");
		}
		return new McpClient.CallResult(object.deepCopy(), object.path("isError").asBoolean(false));
	}

	/** Installs the listener for server-initiated notifications on this session. */
	public static void mcpOnNotification(McpClient client, BiConsumer<String, JsonNode> listener) {
		mcpTransportOnNotification(client.transport, listener);
	}

	/** Closes the session and the transport it owns. */
	public static void mcpCloseClient(McpClient client) {
		mcpTransportClose(client.transport);
	}

	private static McpClient.ToolDefinition mcpParseToolDefinition(JsonNode node) throws IOException {
		if (!node.isObject() || !node.path("name").isTextual() || node.path("name").asText().isBlank()) {
			throw new IOException("MCP tools/list returned a tool without a name");
		}
		String description = node.path("description").isTextual() ? node.path("description").asText() : "";
		ObjectNode inputSchema = node.path("inputSchema") instanceof ObjectNode object
				? object.deepCopy()
				: jsonObject();
		inputSchema.put("type", "object");
		if (!(inputSchema.get("properties") instanceof ObjectNode)) inputSchema.set("properties", jsonObject());
		// Match OpenCode's dynamic MCP tool conversion. It keeps schemas bounded
		// and avoids providers rejecting unspecified object properties.
		inputSchema.put("additionalProperties", false);
		return new McpClient.ToolDefinition(node.path("name").asText(), description, inputSchema);
	}

	// ---------------------------------------------------------- mcp manager

	/** Builds a manager for the MCP servers in {@code ~/.codingagent/settings.json}. */
	public static McpManager mcpLoadDefaultManager(Path workspace) throws IOException {
		return mcpCreateManager(mcpLoadDefaultConfiguration(), workspace);
	}

	/** Builds a manager and starts connecting every enabled server. */
	public static McpManager mcpCreateManager(McpConfiguration configuration, Path workspace) {
		return mcpCreateManager(configuration, workspace, mcpDefaultOAuthClient());
	}

	/** Builds a manager on an explicit OAuth client and starts connecting enabled servers. */
	public static McpManager mcpCreateManager(
			McpConfiguration configuration, Path workspace, McpOAuthClient oauth) {
		McpManager manager = new McpManager();
		manager.workspace = workspace.toAbsolutePath().normalize();
		manager.oauth = oauth;
		configuration.servers.forEach((name, config) -> {
			McpManager.Runtime runtime = new McpManager.Runtime(name, config);
			runtime.disabledTools.addAll(mcpConfigDisabledTools(config));
			runtime.enabled = mcpConfigEnabled(config);
			runtime.state = runtime.enabled ? McpManager.State.CONNECTING : McpManager.State.DISABLED;
			manager.servers.put(name, runtime);
		});
		for (McpManager.Runtime runtime : manager.servers.values()) {
			// Startup may refresh an existing token, but never opens a browser unexpectedly.
			if (mcpConfigEnabled(runtime.config)) mcpStartConnect(manager, runtime, false);
		}
		return manager;
	}

	/** Whether the manager has no configured servers at all. */
	public static boolean mcpIsEmpty(McpManager manager) {
		return manager.servers.isEmpty();
	}

	/** Snapshots every configured server, ordered by name. */
	public static List<McpManager.ServerStatus> mcpStatuses(McpManager manager) {
		return manager.servers.values().stream()
				.sorted(Comparator.comparing(runtime -> runtime.name))
				.map(CodingAgentOperations::mcpSnapshot)
				.toList();
	}

	/** Snapshots one configured server. */
	public static McpManager.ServerStatus mcpStatus(McpManager manager, String name) {
		return mcpSnapshot(mcpRequireRuntime(manager, name));
	}

	/** Whether this server is enabled for the current and future manager connections. */
	public static boolean mcpIsEnabled(McpManager manager, String name) {
		McpManager.Runtime runtime = mcpRequireRuntime(manager, name);
		synchronized (runtime.lock) {
			return runtime.enabled;
		}
	}

	/**
	 * Returns the current tool catalog for a connected server, including tools
	 * disabled for this process. A disconnected server has no current catalog.
	 */
	public static List<McpManager.ToolStatus> mcpToolStatuses(McpManager manager, String serverName) {
		McpManager.Runtime runtime = mcpRequireRuntime(manager, serverName);
		synchronized (runtime.lock) {
			if (runtime.state != McpManager.State.CONNECTED || runtime.client == null) return List.of();
			return runtime.tools.stream()
					.map(tool -> new McpManager.ToolStatus(
							runtime.name, tool.name, tool.description, !runtime.disabledTools.contains(tool.name)))
					.toList();
		}
	}

	/**
	 * Enables a currently disabled tool or disables an enabled one without
	 * disconnecting its server. The runtime choice survives a later reconnect
	 * of that server.
	 */
	public static McpManager.ToolStatus mcpToggleTool(McpManager manager, String serverName, String toolName) {
		McpManager.Runtime runtime = mcpRequireRuntime(manager, serverName);
		synchronized (runtime.lock) {
			if (runtime.state != McpManager.State.CONNECTED || runtime.client == null) {
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
			return new McpManager.ToolStatus(runtime.name, definition.name, definition.description, enabled);
		}
	}

	/** Waits for all currently-starting configured servers. */
	public static void mcpAwaitReady(McpManager manager) throws InterruptedException {
		while (true) {
			List<Thread> connecting = new ArrayList<>();
			for (McpManager.Runtime runtime : manager.servers.values()) {
				synchronized (runtime.lock) {
					if (runtime.connector != null) connecting.add(runtime.connector);
				}
			}
			if (connecting.isEmpty()) return;
			for (Thread thread : connecting) thread.join();
		}
	}

	/** Connects or retries a configured server and waits for that attempt. */
	public static McpManager.ServerStatus mcpConnectServer(McpManager manager, String name)
			throws InterruptedException {
		McpManager.Runtime runtime = mcpRequireRuntime(manager, name);
		Thread thread = mcpStartConnect(manager, runtime, true);
		if (thread != null) thread.join();
		return mcpSnapshot(runtime);
	}

	/** Starts the same connect/authenticate action without blocking a TUI event loop. */
	public static McpManager.ServerStatus mcpConnectServerAsync(McpManager manager, String name) {
		McpManager.Runtime runtime = mcpRequireRuntime(manager, name);
		mcpStartConnect(manager, runtime, true);
		return mcpSnapshot(runtime);
	}

	/** Disconnects a server and marks it disabled; callers may persist that choice. */
	public static McpManager.ServerStatus mcpDisconnectServer(McpManager manager, String name) {
		McpManager.Runtime runtime = mcpRequireRuntime(manager, name);
		McpClient client;
		Thread connector;
		synchronized (runtime.lock) {
			runtime.generation++;
			connector = runtime.connector;
			runtime.connector = null;
			client = runtime.client;
			runtime.client = null;
			runtime.tools = List.of();
			runtime.enabled = false;
			runtime.state = McpManager.State.DISABLED;
			runtime.message = null;
			runtime.authorizationUrl = null;
		}
		if (connector != null) connector.interrupt();
		if (client != null) mcpCloseClient(client);
		return mcpSnapshot(runtime);
	}

	/** Connected/connecting means toggle off; disabled/failed means connect or retry. */
	public static McpManager.ServerStatus mcpToggleServer(McpManager manager, String name)
			throws InterruptedException {
		McpManager.Runtime runtime = mcpRequireRuntime(manager, name);
		McpManager.State state;
		synchronized (runtime.lock) {
			state = runtime.state;
		}
		return state == McpManager.State.CONNECTED
						|| state == McpManager.State.CONNECTING
						|| state == McpManager.State.AUTHENTICATING
				? mcpDisconnectServer(manager, name)
				: mcpConnectServer(manager, name);
	}

	/** Asynchronous variant used by the full-screen selector. */
	public static McpManager.ServerStatus mcpToggleServerAsync(McpManager manager, String name) {
		McpManager.Runtime runtime = mcpRequireRuntime(manager, name);
		McpManager.State state;
		synchronized (runtime.lock) {
			state = runtime.state;
		}
		return state == McpManager.State.CONNECTED
						|| state == McpManager.State.CONNECTING
						|| state == McpManager.State.AUTHENTICATING
				? mcpDisconnectServer(manager, name)
				: mcpConnectServerAsync(manager, name);
	}

	/** Returns the current model-visible tool adapters, with OpenCode-compatible names. */
	public static List<AgentTool> mcpTools(McpManager manager) {
		LinkedHashMap<String, AgentTool> result = new LinkedHashMap<>();
		for (McpManager.Runtime runtime : manager.servers.values()) {
			McpClient client;
			List<McpClient.ToolDefinition> definitions;
			Set<String> disabledTools;
			synchronized (runtime.lock) {
				if (runtime.state != McpManager.State.CONNECTED || runtime.client == null) continue;
				client = runtime.client;
				definitions = runtime.tools;
				disabledTools = Set.copyOf(runtime.disabledTools);
			}
			for (McpClient.ToolDefinition definition : definitions) {
				if (disabledTools.contains(definition.name)) continue;
				McpAgentTool tool = newMcpAgentTool(
						runtime.name, definition, client, mcpConfigResultFilters(runtime.config));
				result.put(tool.name, tool);
			}
		}
		return List.copyOf(result.values());
	}

	/** Disconnects every server and releases the manager's sessions. */
	public static void mcpCloseManager(McpManager manager) {
		if (manager.closed) return;
		manager.closed = true;
		for (McpManager.Runtime runtime : manager.servers.values()) {
			McpClient client;
			Thread connector;
			synchronized (runtime.lock) {
				runtime.generation++;
				connector = runtime.connector;
				runtime.connector = null;
				client = runtime.client;
				runtime.client = null;
				runtime.tools = List.of();
				runtime.state = McpManager.State.DISABLED;
				runtime.authorizationUrl = null;
			}
			if (connector != null) connector.interrupt();
			if (client != null) mcpCloseClient(client);
		}
	}

	private static Thread mcpStartConnect(
			McpManager manager, McpManager.Runtime runtime, boolean interactiveOAuth) {
		McpClient previous;
		Thread previousConnector;
		long generation;
		Thread connector;
		synchronized (runtime.lock) {
			if (manager.closed) return null;
			runtime.enabled = true;
			if (runtime.state == McpManager.State.CONNECTED && runtime.client != null) return null;
			previous = runtime.client;
			previousConnector = runtime.connector;
			runtime.client = null;
			runtime.tools = List.of();
			runtime.state = McpManager.State.CONNECTING;
			runtime.message = null;
			runtime.authorizationUrl = null;
			generation = ++runtime.generation;
			connector = Thread.ofVirtual()
					.name("mcp-connect-" + mcpSanitizeName(runtime.name))
					.unstarted(() -> mcpConnectAttempt(manager, runtime, generation, interactiveOAuth));
			runtime.connector = connector;
		}
		if (previousConnector != null && previousConnector != connector) previousConnector.interrupt();
		if (previous != null) mcpCloseClient(previous);
		connector.start();
		return connector;
	}

	private static void mcpConnectAttempt(
			McpManager manager, McpManager.Runtime runtime, long generation, boolean interactiveOAuth) {
		McpClient candidate = null;
		try {
			candidate = mcpConnect(
					runtime.name,
					runtime.config,
					manager.workspace,
					manager.oauth,
					interactiveOAuth,
					url -> mcpAuthorizationStarted(manager, runtime, generation, url.toString()));
			List<McpClient.ToolDefinition> tools = mcpListTools(candidate);
			McpClient connected = candidate;
			mcpOnNotification(candidate, (method, params) -> {
				if (method.equals("notifications/tools/list_changed")) {
					mcpRefreshTools(runtime, generation, connected);
				}
			});
			synchronized (runtime.lock) {
				if (manager.closed || runtime.generation != generation || Thread.currentThread().isInterrupted()) {
					return;
				}
				runtime.client = candidate;
				runtime.tools = List.copyOf(tools);
				runtime.state = McpManager.State.CONNECTED;
				runtime.message = null;
				runtime.authorizationUrl = null;
				candidate = null;
			}
		} catch (McpOAuthRequiredException error) {
			synchronized (runtime.lock) {
				if (!manager.closed && runtime.generation == generation) {
					runtime.state = McpManager.State.AUTH_REQUIRED;
					runtime.message = mcpErrorMessage(error);
					runtime.authorizationUrl = null;
					runtime.client = null;
					runtime.tools = List.of();
				}
			}
		} catch (Exception error) {
			synchronized (runtime.lock) {
				if (!manager.closed && runtime.generation == generation) {
					runtime.state = McpManager.State.FAILED;
					runtime.message = mcpErrorMessage(error);
					runtime.authorizationUrl = null;
					runtime.client = null;
					runtime.tools = List.of();
				}
			}
		} finally {
			if (candidate != null) mcpCloseClient(candidate);
			synchronized (runtime.lock) {
				if (runtime.generation == generation && runtime.connector == Thread.currentThread()) {
					runtime.connector = null;
				}
			}
		}
	}

	private static void mcpAuthorizationStarted(
			McpManager manager, McpManager.Runtime runtime, long generation, String url) {
		synchronized (runtime.lock) {
			if (manager.closed || runtime.generation != generation) return;
			runtime.state = McpManager.State.AUTHENTICATING;
			runtime.message = "Complete OAuth authorization in your browser";
			runtime.authorizationUrl = url;
		}
	}

	private static void mcpRefreshTools(McpManager.Runtime runtime, long generation, McpClient client) {
		Thread.ofVirtual().name("mcp-tools-refresh").start(() -> {
			try {
				List<McpClient.ToolDefinition> tools = mcpListTools(client);
				synchronized (runtime.lock) {
					if (runtime.generation == generation
							&& runtime.client == client
							&& runtime.state == McpManager.State.CONNECTED) {
						runtime.tools = List.copyOf(tools);
					}
				}
			} catch (Exception ignored) {
				// Keep the last usable catalog when a list-changed refresh fails.
			}
		});
	}

	private static McpManager.ServerStatus mcpSnapshot(McpManager.Runtime runtime) {
		synchronized (runtime.lock) {
			int enabledToolCount =
					(int) runtime.tools.stream().filter(tool -> !runtime.disabledTools.contains(tool.name)).count();
			return new McpManager.ServerStatus(
					runtime.name,
					runtime.state,
					runtime.message,
					runtime.tools.size(),
					enabledToolCount,
					mcpConfigTarget(runtime.config),
					runtime.authorizationUrl);
		}
	}

	private static McpManager.Runtime mcpRequireRuntime(McpManager manager, String name) {
		McpManager.Runtime runtime = manager.servers.get(name);
		if (runtime == null) throw new IllegalArgumentException("MCP server is not configured: " + name);
		return runtime;
	}

	private static String mcpErrorMessage(Exception error) {
		String value = error.getMessage();
		if (value == null || value.isBlank()) value = error.toString();
		return value.replaceAll("\\s+", " ").trim();
	}

	// ------------------------------------------------------- mcp oauth client

	/** Creates the OAuth client carrier; every collaborator is required. */
	public static McpOAuthClient mcpOAuthClient(
			McpOAuthStore store, HttpClient http, Predicate<URI> browser, Duration callbackTimeout) {
		return new McpOAuthClient(
				Objects.requireNonNull(store, "store"),
				Objects.requireNonNull(http, "http"),
				Objects.requireNonNull(browser, "browser"),
				Objects.requireNonNull(callbackTimeout, "callbackTimeout"));
	}

	/** Builds the OAuth client used by MCP sessions that are not preauthorized. */
	public static McpOAuthClient mcpDefaultOAuthClient() {
		HttpClient http = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NORMAL)
				.connectTimeout(Duration.ofSeconds(15))
				.build();
		return mcpOAuthClient(
				mcpDefaultOAuthStore(),
				http,
				CodingAgentOperations::mcpOpenBrowser,
				McpOAuthClient.DEFAULT_CALLBACK_TIMEOUT);
	}

	/** Returns the per-server OAuth state, or null when OAuth is disabled or preauthorized. */
	public static McpOAuthClient.Session mcpOAuthSession(
			McpOAuthClient client, String name, McpServerConfig.Remote config) {
		if (mcpOAuthDisabled(config.oauth) || mcpHasAuthorizationHeader(config.headers)) return null;
		return new McpOAuthClient.Session(client, name, config, mcpParseOAuthSettings(config.oauth));
	}

	/** Returns a current token and silently refreshes it when it is near expiry. */
	public static String mcpAccessToken(McpOAuthClient.Session session) throws Exception {
		synchronized (session) {
			McpOAuthStore.Entry current = mcpOAuthLoad(session);
			if (current == null || current.tokens == null) return null;
			McpOAuthStore.Tokens tokens = current.tokens;
			if (!mcpOAuthExpired(tokens)) return tokens.accessToken;
			if (tokens.refreshToken == null) {
				return tokens.expiresAt != null && tokens.expiresAt > Instant.now().getEpochSecond()
						? tokens.accessToken
						: null;
			}
			try {
				return mcpOAuthRefresh(session, current, null).tokens.accessToken;
			} catch (McpOAuthClient.OAuthFailure error) {
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

	/** Returns the last loaded token without doing I/O; used only for best-effort session cleanup. */
	public static String mcpCachedAccessToken(McpOAuthClient.Session session) {
		synchronized (session) {
			return session.entry == null || session.entry.tokens == null ? null : session.entry.tokens.accessToken;
		}
	}

	/** Refreshes a token rejected by the resource server and tells the transport whether to retry. */
	public static boolean mcpRefreshAfterUnauthorized(
			McpOAuthClient.Session session, McpHttpException failure, String rejectedToken) throws Exception {
		synchronized (session) {
			McpOAuthClient.Challenge challenge = mcpOAuthChallenge(failure);
			if (challenge == null || mcpOAuthInsufficientScope(challenge) || rejectedToken == null) return false;
			McpOAuthStore.Entry current = mcpOAuthReload(session);
			if (current == null || current.tokens == null) return false;
			if (!current.tokens.accessToken.equals(rejectedToken)) return true;
			if (current.tokens.refreshToken == null) return false;
			try {
				McpOAuthStore.Entry refreshed = mcpOAuthRefresh(session, current, challenge);
				return !refreshed.tokens.accessToken.equals(rejectedToken);
			} catch (McpOAuthClient.OAuthFailure error) {
				if (mcpOAuthInvalidCredential(error)) {
					mcpOAuthInvalidateAfterRefreshFailure(session, current, error);
					return false;
				}
				throw error;
			}
		}
	}

	/** Runs an interactive authorization flow and leaves the new token available to this transport. */
	public static void mcpAuthorize(
			McpOAuthClient.Session session, McpHttpException failure, Consumer<URI> listener) throws Exception {
		McpOAuthClient client = session.client;
		McpOAuthClient.Challenge challenge = mcpOAuthChallenge(failure);
		if (challenge == null) throw failure;
		client.interactiveLock.lockInterruptibly();
		try {
			McpOAuthClient.Discovery discovery = mcpOAuthDiscover(client, session.config, challenge);
			String state = mcpRandomUrlToken(client, 32);
			String verifier = mcpRandomUrlToken(client, 64);
			String challengeValue = Base64.getUrlEncoder().withoutPadding().encodeToString(mcpSha256(verifier));
			URI redirectUri = mcpOAuthRedirectUri(session.settings);
			McpOAuthCallback callback = mcpOpenOAuthCallback(redirectUri, state);
			try {
				McpOAuthStore.Entry current = mcpOAuthReload(session);
				String scope = mcpSelectScope(challenge, discovery.resourceMetadata, session.settings.scope);
				McpOAuthStore.ClientInfo clientInfo =
						mcpOAuthClientInformation(session, current, discovery, redirectUri, scope);
				URI authorizationUrl =
						mcpOAuthAuthorizationUrl(discovery, clientInfo, redirectUri, state, challengeValue, scope);
				if (listener != null) listener.accept(authorizationUrl);
				client.browser.test(authorizationUrl);
				String code = mcpAwaitOAuthCode(callback, client.callbackTimeout);
				McpOAuthStore.Tokens tokens =
						mcpOAuthExchangeCode(client, discovery, clientInfo, code, verifier, redirectUri, Map.of());
				if (tokens.scope == null && scope != null) {
					tokens = new McpOAuthStore.Tokens(
							tokens.accessToken, tokens.refreshToken, tokens.expiresAt, scope);
				}
				McpOAuthStore.Entry saved = new McpOAuthStore.Entry(
						tokens, session.settings.clientId == null ? clientInfo : null);
				mcpOAuthWrite(client.store, session.name, session.config.url.toString(), saved);
				session.entry = saved;
				session.loaded = true;
			} finally {
				mcpCloseOAuthCallback(callback);
			}
		} finally {
			client.interactiveLock.unlock();
		}
	}

	/** Whether this failure is a bearer challenge that OAuth can answer. */
	public static boolean mcpIsOAuthChallenge(Throwable error) {
		McpHttpException http = mcpFindHttpException(error);
		return http != null && mcpOAuthChallenge(http) != null;
	}

	/** Whether this failure asks for additional OAuth scopes. */
	public static boolean mcpIsInsufficientScope(Throwable error) {
		McpHttpException http = mcpFindHttpException(error);
		McpOAuthClient.Challenge challenge = http == null ? null : mcpOAuthChallenge(http);
		return challenge != null && mcpOAuthInsufficientScope(challenge);
	}

	private static McpOAuthStore.Entry mcpOAuthClientEntry(
			McpOAuthClient.Session session, McpOAuthStore.Entry current, McpOAuthStore.ClientInfo clientInfo)
			throws IOException {
		McpOAuthStore.Entry updated = current == null
				? new McpOAuthStore.Entry(null, clientInfo)
				: new McpOAuthStore.Entry(current.tokens, clientInfo);
		mcpOAuthWrite(session.client.store, session.name, session.config.url.toString(), updated);
		session.entry = updated;
		session.loaded = true;
		return updated;
	}

	private static McpOAuthStore.ClientInfo mcpOAuthClientInformation(
			McpOAuthClient.Session session,
			McpOAuthStore.Entry current,
			McpOAuthClient.Discovery discovery,
			URI redirectUri,
			String scope)
			throws Exception {
		if (session.settings.clientId != null) {
			return new McpOAuthStore.ClientInfo(
					session.settings.clientId,
					session.settings.clientSecret,
					null,
					null,
					null,
					redirectUri.toString());
		}
		McpOAuthStore.ClientInfo stored = current == null ? null : current.clientInfo;
		if (mcpOAuthClientUsable(session, stored, redirectUri)) return stored;
		McpOAuthStore.ClientInfo registered = mcpOAuthRegister(session, discovery, redirectUri, scope);
		mcpOAuthClientEntry(session, current, registered);
		return registered;
	}

	private static McpOAuthStore.Entry mcpOAuthRefresh(
			McpOAuthClient.Session session, McpOAuthStore.Entry current, McpOAuthClient.Challenge challenge)
			throws Exception {
		McpOAuthClient client = session.client;
		McpOAuthClient.Discovery discovery = mcpOAuthDiscover(client, session.config, challenge);
		McpOAuthStore.ClientInfo clientInfo =
				mcpOAuthEffectiveClient(session, current, mcpOAuthRedirectUri(session.settings));
		if (clientInfo == null) {
			throw new McpOAuthClient.OAuthFailure(
					400, "invalid_client", "No OAuth client is registered for this MCP server");
		}
		LinkedHashMap<String, String> parameters = new LinkedHashMap<>();
		parameters.put("grant_type", "refresh_token");
		parameters.put("refresh_token", current.tokens.refreshToken);
		McpOAuthStore.Tokens refreshed =
				mcpOAuthRequestTokens(client, discovery, clientInfo, parameters, current.tokens, Map.of());
		McpOAuthStore.Entry updated = new McpOAuthStore.Entry(refreshed, current.clientInfo);
		mcpOAuthWrite(client.store, session.name, session.config.url.toString(), updated);
		session.entry = updated;
		session.loaded = true;
		return updated;
	}

	private static McpOAuthStore.ClientInfo mcpOAuthEffectiveClient(
			McpOAuthClient.Session session, McpOAuthStore.Entry current, URI redirectUri) {
		if (session.settings.clientId != null) {
			return new McpOAuthStore.ClientInfo(
					session.settings.clientId,
					session.settings.clientSecret,
					null,
					null,
					null,
					redirectUri.toString());
		}
		McpOAuthStore.ClientInfo candidate = current == null ? null : current.clientInfo;
		return mcpOAuthClientUsable(session, candidate, redirectUri) ? candidate : null;
	}

	private static boolean mcpOAuthClientUsable(
			McpOAuthClient.Session session, McpOAuthStore.ClientInfo clientInfo, URI redirectUri) {
		if (clientInfo == null || clientInfo.clientId == null || clientInfo.clientId.isBlank()) return false;
		Long expires = clientInfo.clientSecretExpiresAt;
		if (expires != null && expires > 0 && expires <= Instant.now().getEpochSecond()) return false;
		if (clientInfo.redirectUri != null) return clientInfo.redirectUri.equals(redirectUri.toString());
		return mcpUsesDefaultRedirect(session.settings);
	}

	private static McpOAuthStore.Entry mcpOAuthLoad(McpOAuthClient.Session session) throws IOException {
		return session.loaded ? session.entry : mcpOAuthReload(session);
	}

	private static McpOAuthStore.Entry mcpOAuthReload(McpOAuthClient.Session session) throws IOException {
		session.entry = mcpOAuthRead(session.client.store, session.name, session.config.url.toString());
		session.loaded = true;
		return session.entry;
	}

	private static void mcpOAuthInvalidateAfterRefreshFailure(
			McpOAuthClient.Session session, McpOAuthStore.Entry current, McpOAuthClient.OAuthFailure failure)
			throws IOException {
		boolean invalidClient = "invalid_client".equals(failure.code) || "unauthorized_client".equals(failure.code);
		McpOAuthStore.Entry cleared = invalidClient && session.settings.clientId == null
				? new McpOAuthStore.Entry(null, null)
				: new McpOAuthStore.Entry(null, current.clientInfo);
		mcpOAuthWrite(session.client.store, session.name, session.config.url.toString(), cleared);
		session.entry = cleared;
		session.loaded = true;
	}

	private static boolean mcpOAuthExpired(McpOAuthStore.Tokens tokens) {
		return tokens.expiresAt != null
				&& tokens.expiresAt <= Instant.now().getEpochSecond() + McpOAuthClient.REFRESH_SKEW_SECONDS;
	}

	private static McpOAuthStore.ClientInfo mcpOAuthRegister(
			McpOAuthClient.Session session, McpOAuthClient.Discovery discovery, URI redirectUri, String scope)
			throws Exception {
		URI endpoint = discovery.metadata.registrationEndpoint;
		if (endpoint == null) {
			throw new IOException(
					"OAuth server does not support dynamic client registration; configure oauth.clientId for MCP server \""
							+ session.name + "\"");
		}
		ObjectNode request = jsonObject();
		request.putArray("redirect_uris").add(redirectUri.toString());
		request.put("client_name", "codingagent");
		request.put("client_uri", "https://github.com/mikeyreilly/coding-agent");
		request.putArray("grant_types").add("authorization_code").add("refresh_token");
		request.putArray("response_types").add("code");
		request.put("token_endpoint_auth_method", "none");
		if (scope != null) request.put("scope", scope);
		McpOAuthClient.Response response =
				mcpOAuthSend(session.client, mcpOAuthPostJson(endpoint, request, Map.of()));
		if (!mcpOAuthSuccess(response)) {
			throw mcpOAuthFailure(response, "Dynamic OAuth client registration failed");
		}
		JsonNode body = mcpOAuthParseObject(response, "dynamic client registration");
		String clientId = mcpOAuthRequiredText(body, "client_id", "dynamic client registration");
		return new McpOAuthStore.ClientInfo(
				clientId,
				mcpOAuthOptionalText(body, "client_secret"),
				mcpOAuthOptionalLong(body, "client_id_issued_at"),
				mcpOAuthOptionalLong(body, "client_secret_expires_at"),
				mcpOAuthOptionalText(body, "token_endpoint_auth_method"),
				redirectUri.toString());
	}

	private static McpOAuthClient.Discovery mcpOAuthDiscover(
			McpOAuthClient client, McpServerConfig.Remote config, McpOAuthClient.Challenge challenge)
			throws Exception {
		McpOAuthClient.ResourceMetadata resourceMetadata = mcpOAuthDiscoverResourceMetadata(client, config, challenge);
		URI authorizationServer = resourceMetadata != null && !resourceMetadata.authorizationServers.isEmpty()
				? resourceMetadata.authorizationServers.getFirst()
				: mcpUriOrigin(config.url);
		mcpRequireSecureEndpoint(authorizationServer, "authorization server");
		McpOAuthClient.AuthorizationMetadata metadata =
				mcpOAuthDiscoverAuthorizationMetadata(client, authorizationServer, Map.of());
		if (metadata == null) {
			throw new IOException("OAuth authorization server metadata was not found for " + authorizationServer);
		}
		URI resource = resourceMetadata == null ? mcpCanonicalResource(config.url) : resourceMetadata.resource;
		if (resourceMetadata != null && !mcpResourceAllowed(config.url, resource)) {
			throw new IOException(
					"OAuth protected resource " + resource + " does not match MCP endpoint " + config.url);
		}
		return new McpOAuthClient.Discovery(authorizationServer, metadata, resourceMetadata, resource);
	}

	private static McpOAuthClient.ResourceMetadata mcpOAuthDiscoverResourceMetadata(
			McpOAuthClient client, McpServerConfig.Remote config, McpOAuthClient.Challenge challenge)
			throws Exception {
		if (challenge != null && challenge.resourceMetadataUrl != null) {
			URI endpoint = challenge.resourceMetadataUrl;
			mcpRequireSecureEndpoint(endpoint, "OAuth protected resource metadata");
			McpOAuthClient.Response response = mcpOAuthSend(client, mcpOAuthGet(endpoint, Map.of(), true));
			if (!mcpOAuthSuccess(response)) {
				throw new IOException("OAuth protected resource metadata returned HTTP " + response.status + " ("
						+ endpoint + ")");
			}
			return mcpOAuthParseResourceMetadata(response);
		}

		URI server = config.url;
		String path = server.getRawPath();
		if (path == null || path.isEmpty()) path = "/";
		if (path.endsWith("/") && path.length() > 1) path = path.substring(0, path.length() - 1);
		URI pathAware = mcpUriAtOrigin(
				server,
				"/.well-known/oauth-protected-resource" + (path.equals("/") ? "" : path),
				server.getRawQuery());
		McpOAuthClient.Response response = mcpOAuthSend(client, mcpOAuthGet(pathAware, Map.of(), true));
		if (mcpOAuthSuccess(response)) return mcpOAuthParseResourceMetadata(response);
		if (!(response.status >= 400 && response.status < 500)) {
			throw new IOException(
					"OAuth protected resource metadata returned HTTP " + response.status + " (" + pathAware + ")");
		}
		if (!path.equals("/")) {
			URI root = mcpUriAtOrigin(server, "/.well-known/oauth-protected-resource", null);
			response = mcpOAuthSend(client, mcpOAuthGet(root, Map.of(), true));
			if (mcpOAuthSuccess(response)) return mcpOAuthParseResourceMetadata(response);
			if (!(response.status >= 400 && response.status < 500)) {
				throw new IOException(
						"OAuth protected resource metadata returned HTTP " + response.status + " (" + root + ")");
			}
		}
		return null;
	}

	private static McpOAuthClient.ResourceMetadata mcpOAuthParseResourceMetadata(McpOAuthClient.Response response)
			throws IOException {
		JsonNode body = mcpOAuthParseObject(response, "OAuth protected resource metadata");
		URI resource = mcpOAuthRequiredUri(body, "resource", "OAuth protected resource metadata");
		List<URI> servers = mcpOAuthUriArray(body.get("authorization_servers"), "authorization_servers");
		List<String> scopes = mcpOAuthTextArray(body.get("scopes_supported"), "scopes_supported");
		return new McpOAuthClient.ResourceMetadata(resource, servers, scopes);
	}

	private static McpOAuthClient.AuthorizationMetadata mcpOAuthDiscoverAuthorizationMetadata(
			McpOAuthClient client, URI server, Map<String, String> headers) throws Exception {
		for (URI endpoint : mcpAuthorizationMetadataUrls(server)) {
			McpOAuthClient.Response response = mcpOAuthSend(client, mcpOAuthGet(endpoint, headers, true));
			if (mcpOAuthSuccess(response)) return mcpOAuthParseAuthorizationMetadata(response);
			if (response.status >= 400 && response.status < 500) continue;
			throw new IOException(
					"OAuth authorization metadata returned HTTP " + response.status + " (" + endpoint + ")");
		}
		return null;
	}

	private static McpOAuthClient.AuthorizationMetadata mcpOAuthParseAuthorizationMetadata(
			McpOAuthClient.Response response) throws IOException {
		JsonNode body = mcpOAuthParseObject(response, "OAuth authorization server metadata");
		mcpOAuthRequiredText(body, "issuer", "OAuth authorization server metadata");
		URI authorization =
				mcpOAuthRequiredUri(body, "authorization_endpoint", "OAuth authorization server metadata");
		URI token = mcpOAuthRequiredUri(body, "token_endpoint", "OAuth authorization server metadata");
		URI registration =
				mcpOAuthOptionalUri(body, "registration_endpoint", "OAuth authorization server metadata");
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
		return new McpOAuthClient.AuthorizationMetadata(
				authorization,
				token,
				registration,
				responseTypes,
				challengeMethods,
				mcpOAuthTextArray(
						body.get("token_endpoint_auth_methods_supported"), "token_endpoint_auth_methods_supported"),
				mcpOAuthTextArray(body.get("scopes_supported"), "scopes_supported"));
	}

	private static URI mcpOAuthAuthorizationUrl(
			McpOAuthClient.Discovery discovery,
			McpOAuthStore.ClientInfo client,
			URI redirectUri,
			String state,
			String codeChallenge,
			String scope) {
		LinkedHashMap<String, String> parameters = new LinkedHashMap<>();
		parameters.put("response_type", "code");
		parameters.put("client_id", client.clientId);
		parameters.put("code_challenge", codeChallenge);
		parameters.put("code_challenge_method", "S256");
		parameters.put("redirect_uri", redirectUri.toString());
		parameters.put("state", state);
		if (scope != null) parameters.put("scope", scope);
		if (scope != null && List.of(scope.split("\\s+")).contains("offline_access")) {
			parameters.put("prompt", "consent");
		}
		parameters.put("resource", discovery.resource.toString());
		return mcpAppendQuery(discovery.metadata.authorizationEndpoint, parameters);
	}

	private static McpOAuthStore.Tokens mcpOAuthExchangeCode(
			McpOAuthClient client,
			McpOAuthClient.Discovery discovery,
			McpOAuthStore.ClientInfo clientInfo,
			String code,
			String verifier,
			URI redirectUri,
			Map<String, String> configuredHeaders)
			throws Exception {
		LinkedHashMap<String, String> parameters = new LinkedHashMap<>();
		parameters.put("grant_type", "authorization_code");
		parameters.put("code", code);
		parameters.put("code_verifier", verifier);
		parameters.put("redirect_uri", redirectUri.toString());
		return mcpOAuthRequestTokens(client, discovery, clientInfo, parameters, null, configuredHeaders);
	}

	private static McpOAuthStore.Tokens mcpOAuthRequestTokens(
			McpOAuthClient client,
			McpOAuthClient.Discovery discovery,
			McpOAuthStore.ClientInfo clientInfo,
			LinkedHashMap<String, String> parameters,
			McpOAuthStore.Tokens previous,
			Map<String, String> configuredHeaders)
			throws Exception {
		parameters.put("resource", discovery.resource.toString());
		LinkedHashMap<String, String> headers = new LinkedHashMap<>(configuredHeaders);
		headers.put("Accept", "application/json");
		mcpApplyClientAuthentication(discovery.metadata, clientInfo, parameters, headers);
		HttpRequest request = mcpOAuthPostForm(discovery.metadata.tokenEndpoint, parameters, headers);
		McpOAuthClient.Response response = mcpOAuthSend(client, request);
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
		return new McpOAuthStore.Tokens(access, refresh, expiresAt, scope);
	}

	private static void mcpApplyClientAuthentication(
			McpOAuthClient.AuthorizationMetadata metadata,
			McpOAuthStore.ClientInfo client,
			Map<String, String> parameters,
			Map<String, String> headers)
			throws IOException {
		String method = mcpSelectClientAuthMethod(metadata, client);
		switch (method) {
			case "client_secret_basic" -> {
				if (client.clientSecret == null) {
					throw new IOException("OAuth client_secret_basic requires a client secret");
				}
				String value = client.clientId + ":" + client.clientSecret;
				headers.put(
						"Authorization",
						"Basic " + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
			}
			case "client_secret_post" -> {
				parameters.put("client_id", client.clientId);
				if (client.clientSecret != null) parameters.put("client_secret", client.clientSecret);
			}
			case "none" -> parameters.put("client_id", client.clientId);
			default -> throw new IOException("Unsupported OAuth token endpoint authentication method: " + method);
		}
	}

	private static String mcpSelectClientAuthMethod(
			McpOAuthClient.AuthorizationMetadata metadata, McpOAuthStore.ClientInfo client) {
		List<String> supported = metadata.tokenAuthMethods;
		String registered = client.tokenEndpointAuthMethod;
		if (registered != null
				&& List.of("client_secret_basic", "client_secret_post", "none").contains(registered)
				&& (supported.isEmpty() || supported.contains(registered))) return registered;
		if (supported.isEmpty()) return client.clientSecret == null ? "none" : "client_secret_basic";
		if (client.clientSecret != null && supported.contains("client_secret_basic")) return "client_secret_basic";
		if (client.clientSecret != null && supported.contains("client_secret_post")) return "client_secret_post";
		if (supported.contains("none")) return "none";
		return client.clientSecret == null ? "none" : "client_secret_post";
	}

	private static HttpRequest mcpOAuthGet(URI uri, Map<String, String> configuredHeaders, boolean metadata) {
		HttpRequest.Builder builder =
				HttpRequest.newBuilder(uri).timeout(McpOAuthClient.HTTP_TIMEOUT).GET();
		if (metadata) {
			builder.setHeader("Accept", "application/json");
			builder.setHeader("MCP-Protocol-Version", McpOAuthClient.PROTOCOL_VERSION);
		}
		mcpApplyOAuthHeaders(builder, configuredHeaders);
		return builder.build();
	}

	private static HttpRequest mcpOAuthPostJson(URI uri, JsonNode body, Map<String, String> configuredHeaders)
			throws IOException {
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
				.timeout(McpOAuthClient.HTTP_TIMEOUT)
				.setHeader("Accept", "application/json")
				.setHeader("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(
						Json.MAPPER.writeValueAsString(body), StandardCharsets.UTF_8));
		mcpApplyOAuthHeaders(builder, configuredHeaders);
		return builder.build();
	}

	private static HttpRequest mcpOAuthPostForm(
			URI uri, Map<String, String> parameters, Map<String, String> headers) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
				.timeout(McpOAuthClient.HTTP_TIMEOUT)
				.setHeader("Accept", "application/json")
				.setHeader("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(mcpFormEncode(parameters), StandardCharsets.UTF_8));
		mcpApplyOAuthHeaders(builder, headers);
		return builder.build();
	}

	private static McpOAuthClient.Response mcpOAuthSend(McpOAuthClient client, HttpRequest request)
			throws IOException, InterruptedException {
		HttpResponse<String> response =
				client.http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		return new McpOAuthClient.Response(
				response.statusCode(), response.uri(), response.headers().map(), response.body());
	}

	private static boolean mcpOAuthSuccess(McpOAuthClient.Response response) {
		return response.status >= 200 && response.status < 300;
	}

	private static void mcpApplyOAuthHeaders(HttpRequest.Builder request, Map<String, String> headers) {
		for (var header : headers.entrySet()) {
			String name = header.getKey();
			if (!mcpRestrictedHeader(name)
					&& !name.equalsIgnoreCase("Content-Type")
					&& !name.equalsIgnoreCase("Accept")) {
				request.header(name, header.getValue());
			}
		}
	}

	private static McpOAuthClient.OAuthFailure mcpOAuthFailure(McpOAuthClient.Response response, String fallback) {
		String code = null;
		String description = null;
		try {
			JsonNode node = Json.MAPPER.readTree(response.body);
			code = mcpOAuthOptionalText(node, "error");
			description = mcpOAuthOptionalText(node, "error_description");
		} catch (IOException ignored) {}
		String message = description != null ? description : code != null ? code : mcpAbbreviate(response.body);
		if (message == null || message.isBlank()) message = fallback;
		return new McpOAuthClient.OAuthFailure(response.status, code, fallback + ": " + message);
	}

	private static boolean mcpOAuthInvalidCredential(McpOAuthClient.OAuthFailure error) {
		return error.code != null
				&& List.of("invalid_grant", "invalid_client", "unauthorized_client").contains(error.code);
	}

	private static JsonNode mcpOAuthParseObject(McpOAuthClient.Response response, String source) throws IOException {
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
			} catch (NumberFormatException ignored) {}
		}
		throw new IOException("Invalid OAuth field " + field + ": expected a number");
	}

	private static URI mcpOAuthRequiredUri(JsonNode node, String field, String source) throws IOException {
		String value = mcpOAuthRequiredText(node, field, source);
		return mcpParseAbsoluteHttpUri(value, source + "." + field);
	}

	private static URI mcpOAuthOptionalUri(JsonNode node, String field, String source) throws IOException {
		String value = mcpOAuthOptionalText(node, field);
		return value == null ? null : mcpParseAbsoluteHttpUri(value, source + "." + field);
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

	private static List<URI> mcpOAuthUriArray(JsonNode node, String field) throws IOException {
		List<String> values = mcpOAuthTextArray(node, field);
		List<URI> result = new ArrayList<>();
		for (String value : values) result.add(mcpParseAbsoluteHttpUri(value, field));
		return List.copyOf(result);
	}

	private static List<URI> mcpAuthorizationMetadataUrls(URI server) {
		String path = server.getRawPath();
		if (path == null || path.isEmpty() || path.equals("/")) {
			return List.of(
					mcpUriAtOrigin(server, "/.well-known/oauth-authorization-server", null),
					mcpUriAtOrigin(server, "/.well-known/openid-configuration", null));
		}
		if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
		return List.of(
				mcpUriAtOrigin(server, "/.well-known/oauth-authorization-server" + path, null),
				mcpUriAtOrigin(server, "/.well-known/openid-configuration" + path, null),
				mcpUriAtOrigin(server, path + "/.well-known/openid-configuration", null));
	}

	private static URI mcpCanonicalResource(URI server) {
		String value = server.toString();
		int fragment = value.indexOf('#');
		return fragment < 0 ? server : URI.create(value.substring(0, fragment));
	}

	private static boolean mcpResourceAllowed(URI requested, URI configured) {
		if (!mcpUriOrigin(requested).equals(mcpUriOrigin(configured))) return false;
		String requestedPath = mcpNormalizedResourcePath(requested.getPath());
		String configuredPath = mcpNormalizedResourcePath(configured.getPath());
		return requestedPath.startsWith(configuredPath);
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

	private static URI mcpAppendQuery(URI base, Map<String, String> parameters) {
		String value = base.toString();
		int fragment = value.indexOf('#');
		if (fragment >= 0) value = value.substring(0, fragment);
		String separator = base.getRawQuery() == null ? "?" : "&";
		return URI.create(value + separator + mcpFormEncode(parameters));
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

	private static String mcpRandomUrlToken(McpOAuthClient client, int bytes) {
		byte[] value = new byte[bytes];
		client.random.nextBytes(value);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
	}

	private static byte[] mcpSha256(String value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
		} catch (NoSuchAlgorithmException error) {
			throw new IllegalStateException("SHA-256 is unavailable", error);
		}
	}

	private static void mcpRequireSecureEndpoint(URI uri, String description) throws IOException {
		if (uri.getScheme().equalsIgnoreCase("https")) return;
		if (uri.getScheme().equalsIgnoreCase("http") && mcpLoopbackHost(uri.getHost())) return;
		throw new IOException(description + " must use HTTPS: " + uri);
	}

	private static boolean mcpLoopbackHost(String host) {
		return host != null && (host.equalsIgnoreCase("localhost") || host.equals("::1") || host.startsWith("127."));
	}

	private static boolean mcpHasAuthorizationHeader(Map<String, String> headers) {
		return headers.keySet().stream().anyMatch(name -> name.equalsIgnoreCase("Authorization"));
	}

	private static String mcpSelectScope(
			McpOAuthClient.Challenge challenge, McpOAuthClient.ResourceMetadata resource, String configured) {
		if (challenge != null && challenge.scope != null) return challenge.scope;
		if (resource != null && !resource.scopes.isEmpty()) return String.join(" ", resource.scopes);
		return configured;
	}

	private static McpOAuthClient.Challenge mcpOAuthChallenge(McpHttpException response) {
		String authenticate = mcpHttpHeader(response, "WWW-Authenticate");
		if (response.status != 401 && response.status != 403) return null;
		if (authenticate != null && !authenticate.toLowerCase(Locale.ROOT).contains("bearer")) return null;
		Map<String, String> fields = mcpAuthParameters(authenticate);
		String error = fields.get("error");
		if (response.status == 403 && !"insufficient_scope".equals(error)) return null;
		URI resource = null;
		String rawResource = fields.get("resource_metadata");
		if (rawResource != null) {
			try {
				resource = URI.create(rawResource);
				if (!resource.isAbsolute() || resource.getHost() == null) resource = null;
			} catch (IllegalArgumentException ignored) {}
		}
		return new McpOAuthClient.Challenge(resource, fields.get("scope"), error);
	}

	private static boolean mcpOAuthInsufficientScope(McpOAuthClient.Challenge challenge) {
		return "insufficient_scope".equals(challenge.error);
	}

	private static Map<String, String> mcpAuthParameters(String header) {
		if (header == null) return Map.of();
		LinkedHashMap<String, String> values = new LinkedHashMap<>();
		Matcher matcher = McpOAuthClient.AUTH_PARAMETER.matcher(header);
		while (matcher.find()) {
			values.put(
					matcher.group(1).toLowerCase(Locale.ROOT),
					matcher.group(2) != null ? matcher.group(2) : matcher.group(3));
		}
		return values;
	}

	private static McpOAuthClient.OAuthSettings mcpParseOAuthSettings(JsonNode node) {
		if (node == null || !node.isObject()) {
			return new McpOAuthClient.OAuthSettings(null, null, null, null, null);
		}
		Integer port = node.path("callbackPort").isIntegralNumber() ? node.path("callbackPort").asInt() : null;
		URI redirect = node.path("redirectUri").isTextual() ? URI.create(node.path("redirectUri").asText()) : null;
		return new McpOAuthClient.OAuthSettings(
				mcpOAuthOptionalText(node, "clientId"),
				mcpOAuthOptionalText(node, "clientSecret"),
				mcpOAuthOptionalText(node, "scope"),
				port,
				redirect);
	}

	private static boolean mcpOAuthDisabled(JsonNode node) {
		return node != null && node.isBoolean() && !node.asBoolean();
	}

	private static URI mcpOAuthRedirectUri(McpOAuthClient.OAuthSettings settings) {
		if (settings.configuredRedirectUri != null) return settings.configuredRedirectUri;
		int port = settings.callbackPort == null ? McpOAuthClient.DEFAULT_CALLBACK_PORT : settings.callbackPort;
		return URI.create("http://127.0.0.1:" + port + McpOAuthClient.DEFAULT_CALLBACK_PATH);
	}

	private static boolean mcpUsesDefaultRedirect(McpOAuthClient.OAuthSettings settings) {
		return settings.configuredRedirectUri == null && settings.callbackPort == null;
	}

	// -------------------------------------------------------- mcp oauth store

	/** Resolves the credential file, its sibling lock file, and the read-only import files. */
	public static McpOAuthStore mcpOAuthStore(Path path, List<Path> importPaths) {
		Path resolved = path.toAbsolutePath().normalize();
		return new McpOAuthStore(
				resolved,
				resolved.resolveSibling(resolved.getFileName() + ".lock"),
				importPaths.stream().map(value -> value.toAbsolutePath().normalize()).toList());
	}

	/** Builds the default credential store, importing OpenCode credentials when present. */
	public static McpOAuthStore mcpDefaultOAuthStore() {
		Path home = Path.of(System.getProperty("user.home"));
		String xdg = System.getenv("XDG_DATA_HOME");
		Path openCodeData = xdg == null || xdg.isBlank()
				? home.resolve(".local/share/opencode/mcp-auth.json")
				: Path.of(xdg).resolve("opencode/mcp-auth.json");
		return mcpOAuthStore(
				home.resolve(".codingagent/mcp-auth.json"),
				List.of(openCodeData, home.resolve("Library/Application Support/opencode/mcp-auth.json")));
	}

	/** Reads codingagent credentials first, then imports a matching OpenCode entry when available. */
	public static McpOAuthStore.Entry mcpOAuthRead(McpOAuthStore store, String name, String serverUrl)
			throws IOException {
		McpOAuthStore.Entry own = mcpOAuthReadEntry(store.path, name, serverUrl, false);
		if (own != null) return own;
		for (Path candidate : store.importPaths) {
			McpOAuthStore.Entry imported = mcpOAuthReadEntry(candidate, name, serverUrl, true);
			if (imported != null) return imported;
		}
		return null;
	}

	/** Stores one server credential under an exclusive file lock. */
	public static void mcpOAuthWrite(
			McpOAuthStore store, String name, String serverUrl, McpOAuthStore.Entry entry) throws IOException {
		if (name == null || name.isBlank()) throw new IllegalArgumentException("MCP server name must not be blank");
		Files.createDirectories(store.path.getParent());
		mcpSetPermissions(store.path.getParent(), McpOAuthStore.DIRECTORY_PERMISSIONS);
		try (FileChannel channel =
						FileChannel.open(store.lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
				FileLock ignored = channel.lock()) {
			ObjectNode root = mcpOAuthReadRoot(store.path, false);
			root.set(name, mcpOAuthSerialize(serverUrl, entry));
			mcpOAuthWriteRoot(store, root);
		}
	}

	private static McpOAuthStore.Entry mcpOAuthReadEntry(
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
			return mcpOAuthParseEntry(node);
		} catch (RuntimeException error) {
			if (lenient) return null;
			throw new IOException("Invalid MCP OAuth credential for \"" + name + "\" in " + source, error);
		}
	}

	private static McpOAuthStore.Entry mcpOAuthParseEntry(JsonNode node) {
		McpOAuthStore.Tokens tokens = null;
		JsonNode tokenNode = node.get("tokens");
		if (tokenNode != null && tokenNode.isObject() && mcpStoredText(tokenNode, "accessToken") != null) {
			tokens = new McpOAuthStore.Tokens(
					mcpStoredText(tokenNode, "accessToken"),
					mcpStoredText(tokenNode, "refreshToken"),
					mcpStoredNumber(tokenNode, "expiresAt"),
					mcpStoredText(tokenNode, "scope"));
		}
		McpOAuthStore.ClientInfo client = null;
		JsonNode clientNode = node.get("clientInfo");
		if (clientNode != null && clientNode.isObject() && mcpStoredText(clientNode, "clientId") != null) {
			client = new McpOAuthStore.ClientInfo(
					mcpStoredText(clientNode, "clientId"),
					mcpStoredText(clientNode, "clientSecret"),
					mcpStoredNumber(clientNode, "clientIdIssuedAt"),
					mcpStoredNumber(clientNode, "clientSecretExpiresAt"),
					mcpStoredText(clientNode, "tokenEndpointAuthMethod"),
					mcpStoredText(clientNode, "redirectUri"));
		}
		return new McpOAuthStore.Entry(tokens, client);
	}

	private static ObjectNode mcpOAuthSerialize(String serverUrl, McpOAuthStore.Entry entry) {
		ObjectNode node = jsonObject().put("serverUrl", serverUrl);
		if (entry.tokens != null) {
			McpOAuthStore.Tokens value = entry.tokens;
			ObjectNode tokens = node.putObject("tokens").put("accessToken", value.accessToken);
			mcpPutText(tokens, "refreshToken", value.refreshToken);
			if (value.expiresAt != null) tokens.put("expiresAt", value.expiresAt);
			mcpPutText(tokens, "scope", value.scope);
		}
		if (entry.clientInfo != null) {
			McpOAuthStore.ClientInfo value = entry.clientInfo;
			ObjectNode client = node.putObject("clientInfo").put("clientId", value.clientId);
			mcpPutText(client, "clientSecret", value.clientSecret);
			if (value.clientIdIssuedAt != null) client.put("clientIdIssuedAt", value.clientIdIssuedAt);
			if (value.clientSecretExpiresAt != null) {
				client.put("clientSecretExpiresAt", value.clientSecretExpiresAt);
			}
			mcpPutText(client, "tokenEndpointAuthMethod", value.tokenEndpointAuthMethod);
			mcpPutText(client, "redirectUri", value.redirectUri);
		}
		return node;
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

	private static void mcpOAuthWriteRoot(McpOAuthStore store, ObjectNode root) throws IOException {
		Path temporary = Files.createTempFile(store.path.getParent(), "mcp-auth-", ".json");
		try {
			Files.writeString(temporary, Json.MAPPER.writeValueAsString(root) + "\n", StandardCharsets.UTF_8);
			mcpSetPermissions(temporary, McpOAuthStore.FILE_PERMISSIONS);
			try {
				Files.move(temporary, store.path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException error) {
				Files.move(temporary, store.path, StandardCopyOption.REPLACE_EXISTING);
			}
			mcpSetPermissions(store.path, McpOAuthStore.FILE_PERMISSIONS);
		} finally {
			Files.deleteIfExists(temporary);
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

	private static void mcpPutText(ObjectNode node, String field, String value) {
		if (value != null && !value.isBlank()) node.put(field, value);
	}

	private static void mcpSetPermissions(Path target, Set<PosixFilePermission> permissions) throws IOException {
		try {
			Files.setPosixFilePermissions(target, permissions);
		} catch (UnsupportedOperationException ignored) {
			// Windows protects files through the user's profile ACL instead.
		}
	}

	// ----------------------------------------------------- mcp oauth callback

	/** Starts the loopback receiver that accepts one OAuth authorization-code redirect. */
	public static McpOAuthCallback mcpOpenOAuthCallback(URI redirectUri, String expectedState) throws IOException {
		mcpValidateRedirectUri(redirectUri);
		McpOAuthCallback callback = new McpOAuthCallback();
		callback.redirectUri = redirectUri;
		callback.expectedState = expectedState;
		int port = redirectUri.getPort() >= 0 ? redirectUri.getPort() : 80;
		InetAddress loopback = InetAddress.getByName(
				redirectUri.getHost().equalsIgnoreCase("localhost") ? "127.0.0.1" : redirectUri.getHost());
		callback.server = HttpServer.create(new InetSocketAddress(loopback, port), 0);
		callback.executor = Executors.newVirtualThreadPerTaskExecutor();
		callback.server.setExecutor(callback.executor);
		callback.server.createContext("/", exchange -> mcpHandleOAuthRedirect(callback, exchange));
		callback.server.start();
		return callback;
	}

	/** Waits for the authorization code delivered to the loopback receiver. */
	public static String mcpAwaitOAuthCode(McpOAuthCallback callback, Duration timeout)
			throws IOException, InterruptedException {
		try {
			return callback.code.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
		} catch (TimeoutException error) {
			throw new IOException("OAuth authorization timed out after " + timeout.toMinutes() + " minutes", error);
		} catch (ExecutionException error) {
			Throwable cause = error.getCause();
			if (cause instanceof IOException io) throw io;
			throw new IOException(cause == null ? "OAuth authorization failed" : cause.getMessage(), cause);
		}
	}

	/** Stops the loopback receiver and releases its executor. */
	public static void mcpCloseOAuthCallback(McpOAuthCallback callback) {
		callback.server.stop(0);
		callback.executor.shutdownNow();
	}

	private static void mcpHandleOAuthRedirect(McpOAuthCallback callback, HttpExchange exchange) throws IOException {
		try (exchange) {
			if (!exchange.getRequestMethod().equals("GET")
					|| !exchange.getRequestURI().getPath().equals(mcpCallbackPath(callback))) {
				mcpRespondToRedirect(exchange, 404, mcpCallbackPage("Not found", false));
				return;
			}
			Map<String, String> parameters = mcpCallbackQuery(exchange.getRequestURI().getRawQuery());
			String state = parameters.get("state");
			if (state == null || !mcpConstantTimeEquals(callback.expectedState, state)) {
				mcpRespondToRedirect(
						exchange,
						400,
						mcpCallbackPage(
								"The OAuth state was missing or invalid. Return to codingagent and try again.",
								false));
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
			String authorizationCode = parameters.get("code");
			if (authorizationCode == null || authorizationCode.isBlank()) {
				mcpRespondToRedirect(
						exchange,
						400,
						mcpCallbackPage(
								"No authorization code was returned. Return to codingagent and try again.", false));
				return;
			}
			if (callback.code.isDone()) {
				mcpRespondToRedirect(
						exchange, 400, mcpCallbackPage("This OAuth authorization has already been completed.", false));
				return;
			}
			mcpRespondToRedirect(
					exchange,
					200,
					mcpCallbackPage("Authorization complete. You can close this window and return to codingagent.", true));
			callback.code.complete(authorizationCode);
		}
	}

	private static String mcpCallbackPath(McpOAuthCallback callback) {
		String path = callback.redirectUri.getPath();
		return path == null || path.isEmpty() ? "/" : path;
	}

	private static Map<String, String> mcpCallbackQuery(String rawQuery) {
		LinkedHashMap<String, String> values = new LinkedHashMap<>();
		if (rawQuery == null || rawQuery.isEmpty()) return values;
		for (String part : rawQuery.split("&")) {
			int separator = part.indexOf('=');
			String rawName = separator < 0 ? part : part.substring(0, separator);
			String rawValue = separator < 0 ? "" : part.substring(separator + 1);
			values.put(
					URLDecoder.decode(rawName, StandardCharsets.UTF_8),
					URLDecoder.decode(rawValue, StandardCharsets.UTF_8));
		}
		return values;
	}

	private static void mcpRespondToRedirect(HttpExchange exchange, int status, String body) throws IOException {
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
				+ "h1{color:" + color + "}</style></head><body><h1>" + title + "</h1><p>" + mcpEscapeHtml(message)
				+ "</p></body></html>";
	}

	private static String mcpEscapeHtml(String value) {
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	private static boolean mcpConstantTimeEquals(String expected, String actual) {
		return MessageDigest.isEqual(
				expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
	}

	private static void mcpValidateRedirectUri(URI uri) {
		if (uri == null || !uri.isAbsolute() || !uri.getScheme().equalsIgnoreCase("http") || uri.getHost() == null) {
			throw new IllegalArgumentException("MCP OAuth redirectUri must be an absolute loopback HTTP URL");
		}
		String host = uri.getHost();
		if (!(host.equalsIgnoreCase("localhost") || mcpIsLoopbackAddress(host))) {
			throw new IllegalArgumentException("MCP OAuth redirectUri must use localhost or a loopback IP address");
		}
		if (uri.getFragment() != null) {
			throw new IllegalArgumentException("MCP OAuth redirectUri must not contain a fragment");
		}
	}

	private static boolean mcpIsLoopbackAddress(String host) {
		try {
			return InetAddress.getByName(host).isLoopbackAddress();
		} catch (IOException error) {
			return false;
		}
	}


	// ------------------------------------------------------------ tui text

	private static final Pattern TERMINAL_ANSI = Pattern.compile(
			"\u001b(?:\\[[0-?]*[ -/]*[@-~]|\\][^\u0007\u001b]*(?:\u0007|\u001b\\\\))");

	/** Terminal cell width of a string, ignoring ANSI escapes. */
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

	/** Truncates plain text to a cell width, adding an ellipsis when there is room. */
	public static String truncatePlain(String value, int maximumWidth) {
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

	/** Hard-wraps plain text to terminal cell width while preserving explicit blank lines. */
	public static List<String> wrapPlain(String value, int maximumWidth) {
		if (maximumWidth <= 0) {
			return List.of("");
		}
		String normalized = value.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ");
		List<String> lines = new ArrayList<>();
		for (String sourceLine : normalized.split("\n", -1)) {
			if (sourceLine.isEmpty()) {
				lines.add("");
				continue;
			}
			StringBuilder line = new StringBuilder();
			int width = 0;
			for (int index = 0; index < sourceLine.length(); ) {
				int codePoint = sourceLine.codePointAt(index);
				int codePointWidth = Math.max(0, WCWidth.wcwidth(codePoint));
				if (!line.isEmpty() && width + codePointWidth > maximumWidth) {
					lines.add(line.toString());
					line.setLength(0);
					width = 0;
				}
				line.appendCodePoint(codePoint);
				width += codePointWidth;
				index += Character.charCount(codePoint);
			}
			if (!line.isEmpty()) {
				lines.add(line.toString());
			}
		}
		return List.copyOf(lines);
	}

	/** Wraps text in an OSC 8 hyperlink. */
	public static String hyperlink(String text, String url) {
		String safeUrl = url.replace("\u001b", "").replace("\u0007", "");
		return "\u001b]8;;" + safeUrl + "\u001b\\" + text + "\u001b]8;;\u001b\\";
	}

	public static String stripAnsi(String value) {
		return TERMINAL_ANSI.matcher(value).replaceAll("");
	}

	// -------------------------------------------------------------- markdown

	private static final Pattern MARKDOWN_BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
	private static final Pattern MARKDOWN_CODE = Pattern.compile("`([^`]+)`");

	public static String renderMarkdown(String markdown) {
		return renderMarkdown(markdown, Theme.DARK);
	}

	/** Compact ANSI formatting for the Markdown constructs used in agent replies. */
	public static String renderMarkdown(String markdown, Theme theme) {
		StringBuilder output = new StringBuilder();
		for (String line : markdown.split("\\R", -1)) {
			if (line.startsWith("### ")) output.append(theme.strong).append(line.substring(4)).append(theme.reset);
			else if (line.startsWith("## ")) output.append(theme.heading).append(line.substring(3)).append(theme.reset);
			else if (line.startsWith("# ")) output.append(theme.heading).append(line.substring(2)).append(theme.reset);
			else if (line.startsWith("> ")) output.append(theme.muted).append(line.substring(2)).append(theme.reset);
			else output.append(markdownInline(line, theme));
			output.append('\n');
		}
		return output.isEmpty() ? "" : output.substring(0, output.length() - 1);
	}

	private static String markdownInline(String text, Theme theme) {
		return markdownReplace(
				MARKDOWN_CODE,
				markdownReplace(MARKDOWN_BOLD, text, theme.strong, theme.reset),
				theme.code,
				theme.reset);
	}

	private static String markdownReplace(Pattern pattern, String input, String style, String reset) {
		Matcher matcher = pattern.matcher(input);
		StringBuilder output = new StringBuilder();
		while (matcher.find()) {
			matcher.appendReplacement(output, Matcher.quoteReplacement(style + matcher.group(1) + reset));
		}
		matcher.appendTail(output);
		return output.toString();
	}

	// ----------------------------------------------------------------- theme

	public static Theme namedTheme(String name) {
		return switch (name.toLowerCase(Locale.ROOT)) {
			case "dark" -> Theme.DARK;
			case "light" -> Theme.LIGHT;
			case "plain" -> Theme.PLAIN;
			default -> throw new IllegalArgumentException(
					"Unknown theme: " + name + " (expected dark, light, or plain)");
		};
	}

	/** Bold green is reserved for the Ready activity so idle is recognizable at a glance. */
	public static String readyStatus(Theme theme) {
		return theme.name.equalsIgnoreCase("plain") ? "" : "\u001b[1;92m";
	}

	/** Active model and shell work; deliberately never green. */
	public static String activeStatus(Theme theme) {
		return switch (theme.name.toLowerCase(Locale.ROOT)) {
			case "dark" -> "\u001b[1;96m";
			case "light" -> "\u001b[1;34m";
			default -> "";
		};
	}

	/** Tool execution; deliberately never green. */
	public static String toolStatus(Theme theme) {
		return theme.name.equalsIgnoreCase("plain") ? "" : "\u001b[1;95m";
	}

	/** Retry, cancellation, and configuration attention; deliberately never green. */
	public static String warningStatus(Theme theme) {
		return theme.name.equalsIgnoreCase("plain") ? "" : "\u001b[1;93m";
	}

	/** Background used to visually separate the editable prompt from chat output. */
	public static String promptBackground(Theme theme) {
		return switch (theme.name.toLowerCase(Locale.ROOT)) {
			case "dark" -> Theme.DARK_PROMPT_BACKGROUND;
			case "light" -> Theme.LIGHT_PROMPT_BACKGROUND;
			default -> "";
		};
	}

	/** Styles every line as a full-width prompt area. */
	public static String promptArea(Theme theme, String value) {
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

	/** Ordered-character match with a digit/letter swap fallback; lower scores rank first. */
	public static FuzzyMatcher.Match fuzzyMatch(String query, String text) {
		String normalizedQuery = query.toLowerCase(Locale.ROOT);
		String normalizedText = text.toLowerCase(Locale.ROOT);
		FuzzyMatcher.Match primary = fuzzyMatchNormalized(normalizedQuery, normalizedText);
		if (primary.matches) {
			return primary;
		}

		Matcher alphaNumeric = FuzzyMatcher.ALPHA_NUMERIC.matcher(normalizedQuery);
		Matcher numericAlpha = FuzzyMatcher.NUMERIC_ALPHA.matcher(normalizedQuery);
		String swapped = alphaNumeric.matches()
				? alphaNumeric.group(2) + alphaNumeric.group(1)
				: numericAlpha.matches() ? numericAlpha.group(2) + numericAlpha.group(1) : "";
		if (swapped.isEmpty()) {
			return primary;
		}
		FuzzyMatcher.Match swappedMatch = fuzzyMatchNormalized(swapped, normalizedText);
		return swappedMatch.matches ? new FuzzyMatcher.Match(true, swappedMatch.score + 5) : primary;
	}

	/** Keeps items matching every whitespace/slash-separated token, best score first. */
	public static <T> List<T> fuzzyFilter(List<T> items, String query, Function<T, String> text) {
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
				FuzzyMatcher.Match match = fuzzyMatch(token, text.apply(item));
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
			return byScore != 0 ? byScore : Integer.compare(left.index, right.index);
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
			boolean wordBoundary = index == 0 || isFuzzyBoundary(text.charAt(index - 1));
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

	private static boolean isFuzzyBoundary(char value) {
		return Character.isWhitespace(value)
				|| value == '-'
				|| value == '_'
				|| value == '.'
				|| value == '/'
				|| value == ':';
	}

	// -------------------------------------------------------- ansi rendering

	/** Returns the minimal line updates needed to replace the renderer's prior frame. */
	public static String renderAnsiFrame(AnsiRenderer renderer, List<String> lines) {
		List<String> next = List.copyOf(lines);
		StringBuilder output = new StringBuilder();
		int common = Math.min(renderer.previousLines.size(), next.size());
		for (int index = 0; index < common; index++) {
			if (!renderer.previousLines.get(index).equals(next.get(index))) {
				ansiMoveTo(output, index);
				output.append("\u001b[2K").append(next.get(index));
			}
		}
		for (int index = common; index < next.size(); index++) {
			ansiMoveTo(output, index);
			output.append("\u001b[2K").append(next.get(index));
		}
		for (int index = next.size(); index < renderer.previousLines.size(); index++) {
			ansiMoveTo(output, index);
			output.append("\u001b[2K");
		}
		renderer.previousLines = next;
		return output.toString();
	}

	/** Resets the baseline, for example after the terminal scrolls externally. */
	public static void resetAnsiRenderer(AnsiRenderer renderer) {
		renderer.previousLines = List.of();
	}

	public static List<String> ansiPreviousLines(AnsiRenderer renderer) {
		return new ArrayList<>(renderer.previousLines);
	}

	private static void ansiMoveTo(StringBuilder output, int zeroBasedLine) {
		output.append("\u001b[").append(zeroBasedLine + 1).append(";1H");
	}

	// ----------------------------------------------------------- keybindings

	/**
	 * Shift+Enter is commonly LF, CSI-u, or xterm modifyOtherKeys depending on
	 * the terminal. Keep Ctrl+Enter variants as aliases for compatibility.
	 */
	public static List<String> editorKeySequences(String action) {
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

	public static String appKeySequence(String action) {
		String binding = Keybindings.DEFAULT_APP_KEYBINDINGS.get(action);
		if (binding != null && binding.startsWith("ctrl-") && binding.length() == 6) {
			return KeyMap.ctrl(binding.charAt(5));
		}
		if (binding != null && binding.equals("escape")) {
			return "\u001b";
		}
		throw new IllegalArgumentException("Unsupported application keybinding: " + action + "=" + binding);
	}

	public static TuiInput.KeyType appKeyType(int value) {
		if (appKeyMatches(value, "exit")) return TuiInput.KeyType.EXIT;
		if (appKeyMatches(value, "interrupt")) return TuiInput.KeyType.CANCEL;
		if (appKeyMatches(value, "suspend")) return TuiInput.KeyType.SUSPEND;
		if (appKeyMatches(value, "expandTools")) return TuiInput.KeyType.EXPAND_TOOLS;
		if (appKeyMatches(value, "toggleThinking")) return TuiInput.KeyType.TOGGLE_THINKING;
		return null;
	}

	private static boolean appKeyMatches(int value, String action) {
		String sequence = appKeySequence(action);
		return sequence.length() == 1 && sequence.charAt(0) == value;
	}

	// ----------------------------------------------------------- input parsing

	private static final long ESCAPE_TIMEOUT_MS = 25;
	private static final Pattern SGR_MOUSE = Pattern.compile("<(\\d+);(\\d+);(\\d+)([Mm])");
	private static final String TUI_PASTE_END = "\u001b[201~";

	/** Creates a key event without associated text. */
	public static TuiInput.Key key(TuiInput.KeyType type) {
		return new TuiInput.Key(type, "");
	}

	/** Reads one normalized input event, or null when the read timed out. */
	public static TuiInput readTuiInput(NonBlockingReader reader, long timeoutMs) throws IOException {
		int value = reader.read(timeoutMs);
		if (value == NonBlockingReader.READ_EXPIRED) {
			return null;
		}
		if (value == NonBlockingReader.EOF) {
			return key(TuiInput.KeyType.CANCEL);
		}
		return value == 0x1b ? readEscapeSequence(reader) : keyInput(value);
	}

	/** Normalizes an already-buffered terminal sequence. */
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

	private static TuiInput readEscapeSequence(NonBlockingReader reader) throws IOException {
		int next = reader.read(ESCAPE_TIMEOUT_MS);
		if (next == NonBlockingReader.READ_EXPIRED || next == NonBlockingReader.EOF) {
			return key(TuiInput.KeyType.ESCAPE);
		}
		if (next == '[') {
			StringBuilder sequence = new StringBuilder();
			while (sequence.length() < 64) {
				int value = reader.read(ESCAPE_TIMEOUT_MS);
				if (value == NonBlockingReader.READ_EXPIRED || value == NonBlockingReader.EOF) {
					break;
				}
				sequence.append((char) value);
				if (value >= 0x40 && value <= 0x7e) {
					break;
				}
			}
			if (sequence.toString().equals("200~")) {
				return new TuiInput.Key(TuiInput.KeyType.PASTE, readBracketedPaste(reader));
			}
			return csiInput(sequence.toString());
		}
		if (next == 'O') {
			int value = reader.read(ESCAPE_TIMEOUT_MS);
			return value < 0
					? key(TuiInput.KeyType.ESCAPE)
					: parseInputSequence("\u001bO" + (char) value);
		}
		return keyInput(next);
	}

	private static String readBracketedPaste(NonBlockingReader reader) throws IOException {
		StringBuilder content = new StringBuilder();
		StringBuilder suffix = new StringBuilder();
		while (true) {
			int value = reader.read();
			if (value == NonBlockingReader.EOF) {
				content.append(suffix);
				return content.toString();
			}
			suffix.append((char) value);
			while (!TUI_PASTE_END.startsWith(suffix.toString())) {
				content.append(suffix.charAt(0));
				suffix.deleteCharAt(0);
			}
			if (suffix.toString().equals(TUI_PASTE_END)) {
				return content.toString();
			}
		}
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
			default -> mouseInput(sequence);
		};
	}

	private static TuiInput mouseInput(String sequence) {
		Matcher matcher = SGR_MOUSE.matcher(sequence);
		if (!matcher.matches()) {
			return key(TuiInput.KeyType.UNKNOWN);
		}
		int code = Integer.parseInt(matcher.group(1));
		int x = Integer.parseInt(matcher.group(2));
		int y = Integer.parseInt(matcher.group(3));
		if ((code & 64) != 0) {
			return new TuiInput.Mouse(
					(code & 1) == 0 ? TuiInput.MouseAction.SCROLL_UP : TuiInput.MouseAction.SCROLL_DOWN,
					code & 3,
					x,
					y);
		}
		TuiInput.MouseAction action;
		if (matcher.group(4).equals("m") || (code & 3) == 3) {
			action = TuiInput.MouseAction.RELEASE;
		} else if ((code & 32) != 0) {
			action = TuiInput.MouseAction.DRAG;
		} else {
			action = TuiInput.MouseAction.PRESS;
		}
		return new TuiInput.Mouse(action, code & 3, x, y);
	}

	private static TuiInput keyInput(int value) {
		TuiInput.KeyType applicationType = appKeyType(value);
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

	/** Builds the panel state from an alphabetized, de-duplicated command list. */
	public static CommandSuggestions newCommandSuggestions(List<String> commands) {
		Objects.requireNonNull(commands, "commands");
		return new CommandSuggestions(commands.stream()
				.filter(Objects::nonNull)
				.map(String::trim)
				.filter(command -> command.startsWith("/") && command.length() > 1)
				.distinct()
				.sorted(Comparator.naturalOrder())
				.toList());
	}

	/** Moves the selection when a panel is open; returns false to let JLine handle the key. */
	public static boolean moveCommandSuggestion(CommandSuggestions suggestions, String buffer, int delta) {
		synchronizeCommandSuggestions(suggestions, buffer);
		if (suggestions.matches.isEmpty()) return false;
		suggestions.selectedIndex =
				Math.floorMod(suggestions.selectedIndex + delta, suggestions.matches.size());
		adjustCommandViewport(suggestions);
		return true;
	}

	/** Returns the selected command and dismisses the panel until the buffer is edited. */
	public static String acceptCommandSuggestion(CommandSuggestions suggestions, String buffer) {
		synchronizeCommandSuggestions(suggestions, buffer);
		if (suggestions.matches.isEmpty()) return null;
		String selected = suggestions.matches.get(suggestions.selectedIndex);
		suggestions.dismissedBuffer = selected;
		return selected;
	}

	public static List<String> renderCommandSuggestions(
			CommandSuggestions suggestions, String buffer, int width, Theme theme) {
		synchronizeCommandSuggestions(suggestions, buffer);
		if (suggestions.matches.isEmpty()) return List.of();

		int visibleEnd = Math.min(
				suggestions.matches.size(), suggestions.visibleStart + CommandSuggestions.VISIBLE_COMMANDS);
		List<String> visible = suggestions.matches.subList(suggestions.visibleStart, visibleEnd);
		int longestCommand = visible.stream()
				.mapToInt(CodingAgentOperations::visibleWidth)
				.max()
				.orElse(1);
		int innerWidth = Math.max(1, Math.min(longestCommand + 2, Math.max(1, width - 2)));
		String border = "─".repeat(innerWidth);
		List<String> lines = new ArrayList<>(visible.size() + 2);
		lines.add(styleMuted("╭" + border + "╮", theme));
		for (int index = suggestions.visibleStart; index < visibleEnd; index++) {
			boolean selected = index == suggestions.selectedIndex;
			String content = (selected ? "› " : "  ") + suggestions.matches.get(index);
			content = truncatePlain(content, innerWidth);
			content += " ".repeat(Math.max(0, innerWidth - visibleWidth(content)));
			String styledContent = selected && !theme.heading.isEmpty()
					? theme.heading + content + theme.reset
					: content;
			lines.add(styleMuted("│", theme) + styledContent + styleMuted("│", theme));
		}
		lines.add(styleMuted("╰" + border + "╯", theme));
		return lines;
	}

	public static List<String> visibleCommands(CommandSuggestions suggestions, String buffer) {
		synchronizeCommandSuggestions(suggestions, buffer);
		if (suggestions.matches.isEmpty()) return List.of();
		return List.copyOf(suggestions.matches.subList(
				suggestions.visibleStart,
				Math.min(
						suggestions.matches.size(),
						suggestions.visibleStart + CommandSuggestions.VISIBLE_COMMANDS)));
	}

	public static String selectedCommand(CommandSuggestions suggestions, String buffer) {
		synchronizeCommandSuggestions(suggestions, buffer);
		return suggestions.matches.isEmpty()
				? null
				: suggestions.matches.get(suggestions.selectedIndex);
	}

	private static void synchronizeCommandSuggestions(CommandSuggestions suggestions, String buffer) {
		String value = buffer == null ? "" : buffer;
		if (suggestions.dismissedBuffer != null) {
			if (suggestions.dismissedBuffer.equals(value)) {
				suggestions.matches = List.of();
				suggestions.query = value;
				suggestions.selectedIndex = 0;
				suggestions.visibleStart = 0;
				return;
			}
			suggestions.dismissedBuffer = null;
		}
		if (value.equals(suggestions.query)) return;
		suggestions.query = value;
		suggestions.selectedIndex = 0;
		suggestions.visibleStart = 0;
		if (!isCommandPrefix(value)) {
			suggestions.matches = List.of();
			return;
		}
		suggestions.matches =
				suggestions.commands.stream().filter(command -> command.startsWith(value)).toList();
	}

	private static boolean isCommandPrefix(String value) {
		if (value.isEmpty() || value.charAt(0) != '/') return false;
		for (int index = 1; index < value.length(); index++) {
			if (Character.isWhitespace(value.charAt(index))) return false;
		}
		return true;
	}

	private static void adjustCommandViewport(CommandSuggestions suggestions) {
		if (suggestions.selectedIndex < suggestions.visibleStart) {
			suggestions.visibleStart = suggestions.selectedIndex;
		} else if (suggestions.selectedIndex
				>= suggestions.visibleStart + CommandSuggestions.VISIBLE_COMMANDS) {
			suggestions.visibleStart =
					suggestions.selectedIndex - CommandSuggestions.VISIBLE_COMMANDS + 1;
		}
		suggestions.visibleStart = Math.min(
				suggestions.visibleStart,
				Math.max(0, suggestions.matches.size() - CommandSuggestions.VISIBLE_COMMANDS));
	}

	private static String styleMuted(String value, Theme theme) {
		return theme.muted.isEmpty() ? value : theme.muted + value + theme.reset;
	}

	// --------------------------------------------------------- tui components

	/** Renders a component frame at the given size and palette. */
	public static List<String> renderComponent(
			TuiComponent<?> component, int width, int height, Theme theme) {
		return component.render.apply(new TuiFrame(width, height, theme));
	}

	/** Applies one normalized input event to a component. */
	public static void handleComponentInput(TuiComponent<?> component, TuiInput input) {
		component.handle.accept(input);
	}

	public static boolean isComponentComplete(TuiComponent<?> component) {
		return component.complete.getAsBoolean();
	}

	public static <T> T componentResult(TuiComponent<T> component) {
		return component.result.get();
	}

	// ---------------------------------------------------------- fuzzy selector

	public static <T> SelectItem<T> selectItem(T value, String label) {
		return selectItem(value, label, "", null);
	}

	public static <T> SelectItem<T> selectItem(T value, String label, String description) {
		return selectItem(value, label, description, null);
	}

	/**
	 * Creates a selector option. Value and label are required; a missing
	 * description becomes empty and blank search text falls back to the label
	 * plus description.
	 */
	public static <T> SelectItem<T> selectItem(T value, String label, String description, String searchText) {
		Objects.requireNonNull(value, "value");
		Objects.requireNonNull(label, "label");
		String resolvedDescription = description == null ? "" : description;
		return new SelectItem<>(
				value,
				label,
				resolvedDescription,
				searchText == null || searchText.isBlank()
						? label + (resolvedDescription.isBlank() ? "" : " " + resolvedDescription)
						: searchText);
	}

	/** Creates selector state over at least one option, clamping the initial selection. */
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

	/** Binds a selector carrier to the full-screen host. */
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
		selector.visibleCount = Math.max(1, Math.min(10, height - reservedLines));
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

	public static <T> void handleFuzzySelectorInput(FuzzySelector<T> selector, TuiInput input) {
		switch (input) {
			case TuiInput.Key key -> handleFuzzySelectorKey(selector, key);
			case TuiInput.Mouse mouse -> handleFuzzySelectorMouse(selector, mouse);
			case TuiInput.Resize ignored -> {
				// Rendering derives its viewport directly from the latest dimensions.
			}
		}
	}

	private static <T> void handleFuzzySelectorKey(FuzzySelector<T> selector, TuiInput.Key key) {
		switch (key.type) {
			case UP -> moveFuzzySelection(selector, -1);
			case DOWN -> moveFuzzySelection(selector, 1);
			case PAGE_UP -> moveFuzzySelection(selector, -Math.max(1, selector.visibleCount));
			case PAGE_DOWN -> moveFuzzySelection(selector, Math.max(1, selector.visibleCount));
			case ENTER -> selectFuzzyItem(selector);
			case ESCAPE, CANCEL -> selector.complete = true;
			case CHARACTER, PASTE -> insertFuzzyQuery(selector, key.text);
			case BACKSPACE -> backspaceFuzzyQuery(selector);
			case DELETE -> deleteFuzzyQuery(selector);
			case LEFT -> selector.queryCursor = Math.max(0, selector.queryCursor - 1);
			case RIGHT -> selector.queryCursor =
					Math.min(selector.query.length(), selector.queryCursor + 1);
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
			case CLEAR -> {
				selector.query.setLength(0);
				selector.queryCursor = 0;
				filterFuzzyItems(selector);
			}
			default -> {
				// Other normalized keys do not affect selector state.
			}
		}
	}

	private static <T> void handleFuzzySelectorMouse(FuzzySelector<T> selector, TuiInput.Mouse mouse) {
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

	private static <T> void moveFuzzySelection(FuzzySelector<T> selector, int delta) {
		if (selector.filteredItems.isEmpty()) {
			return;
		}
		selector.selectedIndex =
				Math.floorMod(selector.selectedIndex + delta, selector.filteredItems.size());
	}

	private static <T> void selectFuzzyItem(FuzzySelector<T> selector) {
		if (selector.filteredItems.isEmpty()) {
			return;
		}
		selector.result = selector.filteredItems.get(selector.selectedIndex).value;
		selector.complete = true;
	}

	private static <T> void insertFuzzyQuery(FuzzySelector<T> selector, String text) {
		if (!selector.searchable || text == null || text.isEmpty()) {
			return;
		}
		String normalized = text.replace("\r", " ").replace("\n", " ");
		selector.query.insert(selector.queryCursor, normalized);
		selector.queryCursor += normalized.length();
		filterFuzzyItems(selector);
	}

	private static <T> void backspaceFuzzyQuery(FuzzySelector<T> selector) {
		if (!selector.searchable || selector.queryCursor == 0) {
			return;
		}
		int start = selector.query.offsetByCodePoints(selector.queryCursor, -1);
		selector.query.delete(start, selector.queryCursor);
		selector.queryCursor = start;
		filterFuzzyItems(selector);
	}

	private static <T> void deleteFuzzyQuery(FuzzySelector<T> selector) {
		if (!selector.searchable || selector.queryCursor >= selector.query.length()) {
			return;
		}
		int end = selector.query.offsetByCodePoints(selector.queryCursor, 1);
		selector.query.delete(selector.queryCursor, end);
		filterFuzzyItems(selector);
	}

	private static <T> void filterFuzzyItems(FuzzySelector<T> selector) {
		selector.filteredItems =
				fuzzyFilter(selector.items, selector.query.toString(), item -> item.searchText);
		if (selector.query.isEmpty()) {
			int currentIndex = selector.filteredItems.indexOf(selector.currentItem);
			selector.selectedIndex = currentIndex < 0 ? 0 : currentIndex;
		} else {
			selector.selectedIndex = 0;
		}
	}

	/** Runs a searchable selector on the terminal and returns the chosen value. */
	public static <T> T select(
			InteractiveTerminal terminal,
			String title,
			List<SelectItem<T>> options,
			int initialIndex,
			boolean searchable)
			throws IOException {
		if (options.isEmpty()) {
			throw new IllegalArgumentException("options must not be empty");
		}
		return runComponent(
				terminal, fuzzySelectorComponent(fuzzySelector(title, options, initialIndex, searchable)));
	}

	public static String select(InteractiveTerminal terminal, String title, List<String> options)
			throws IOException {
		List<SelectItem<String>> items =
				options.stream().map(option -> selectItem(option, option)).toList();
		return select(terminal, title, items, -1, options.size() > 10);
	}

	// ------------------------------------------------------------ tui runtime

	/** Hosts a component on the alternate screen until it completes. */
	public static <T> T runTuiComponent(TuiRuntime runtime, TuiComponent<T> component)
			throws IOException {
		int width = tuiWidth(runtime);
		int height = tuiHeight(runtime);
		try {
			startTuiRuntime(runtime);
			handleComponentInput(component, new TuiInput.Resize(width, height));
			renderTuiFrame(runtime, component, width, height);
			while (!isComponentComplete(component)) {
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
			return componentResult(component);
		} finally {
			stopTuiRuntime(runtime);
		}
	}

	private static int tuiWidth(TuiRuntime runtime) {
		int columns = runtime.terminal.getColumns();
		return columns > 0 ? columns : TuiRuntime.DEFAULT_COLUMNS;
	}

	private static int tuiHeight(TuiRuntime runtime) {
		int rows = runtime.terminal.getRows();
		return rows > 0 ? rows : TuiRuntime.DEFAULT_ROWS;
	}

	private static void startTuiRuntime(TuiRuntime runtime) {
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

	private static void stopTuiRuntime(TuiRuntime runtime) {
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

	private static void renderTuiFrame(
			TuiRuntime runtime, TuiComponent<?> component, int width, int height) {
		int safeWidth = Math.max(20, width);
		int safeHeight = Math.max(5, height);
		List<String> lines = renderComponent(component, safeWidth, safeHeight, runtime.theme);
		if (lines.size() > safeHeight) {
			lines = lines.subList(0, safeHeight);
		}
		runtime.terminal.writer().write(renderAnsiFrame(runtime.renderer, lines));
		runtime.terminal.flush();
	}

	private static void clearTuiScreen(TuiRuntime runtime) {
		runtime.terminal.writer().write("\u001b[2J\u001b[H");
	}

	// --------------------------------------------------------------- suspend

	/** Unix foreground-process-group suspension shared by line and full-screen modes. */
	public static void suspendProcessGroup() throws IOException {
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
	}

	/** Invokes a suspend hook, preserving its IOException contract. */
	private static void callSuspendAction(Callable<Void> action) throws IOException {
		try {
			action.call();
		} catch (IOException error) {
			throw error;
		} catch (RuntimeException error) {
			throw error;
		} catch (Exception error) {
			throw new IOException(error);
		}
	}

	// --------------------------------------------------- interactive terminal

	/** Opens the system terminal and wires the line editor for the named application. */
	public static InteractiveTerminal newInteractiveTerminal(String appName) throws IOException {
		return newInteractiveTerminal(
				TerminalBuilder.builder().system(true).name(appName).build(),
				() -> {
					suspendProcessGroup();
					return null;
				},
				!System.getProperty("os.name").startsWith("Windows"));
	}

	/** Wires the line editor, signal handlers, and keybindings onto an existing terminal. */
	public static InteractiveTerminal newInteractiveTerminal(
			Terminal terminal, Callable<Void> suspendAction, boolean supportsSuspend) {
		InteractiveTerminal interactive = new InteractiveTerminal();
		interactive.terminal = terminal;
		ensureUsableTerminalSize(terminal);
		interactive.suspendAction = suspendAction;
		interactive.supportsSuspend = supportsSuspend;
		interactive.shellAttributes = new Attributes(terminal.getAttributes());
		interactive.reader = newPromptLineReader(interactive);
		interactive.reader.setHistory(new DefaultHistory());
		installEditorBindings(interactive);
		interactive.previousContinueHandler = supportsSuspend
				? terminal.handle(Terminal.Signal.CONT, signal -> handleContinue(interactive, signal))
				: null;
		interactive.previousResizeHandler =
				terminal.handle(Terminal.Signal.WINCH, signal -> handleTerminalResize(interactive, signal));
		if (supportsSuspend) {
			Widget previousInit = interactive.reader.getWidgets().get(LineReader.CALLBACK_INIT);
			interactive.reader.getWidgets().put(LineReader.CALLBACK_INIT, () -> {
				boolean initialized = previousInit == null || previousInit.apply();
				if (interactive.restoreCursor >= 0) {
					interactive.reader
							.getBuffer()
							.cursor(Math.min(interactive.restoreCursor, interactive.reader.getBuffer().length()));
					interactive.restoreCursor = -1;
				}
				return initialized;
			});
			interactive.reader.getWidgets().put("suspend-process", () -> requestSuspend(interactive));
			Reference suspend = new Reference("suspend-process");
			for (var keyMap : interactive.reader.getKeyMaps().values()) {
				keyMap.bind(suspend, appKeySequence("suspend"));
			}
		}
		interactive.theme = Theme.DARK;
		return interactive;
	}

	/**
	 * JLine adapter: the post-prompt region and cleanup hooks must be method
	 * overrides, so the subclass is created anonymously here and reads its state
	 * from the terminal carrier.
	 */
	private static LineReaderImpl newPromptLineReader(InteractiveTerminal interactive) {
		return new LineReaderImpl(interactive.terminal, interactive.terminal.getName(), null) {
			@Override
			public AttributedString getDisplayedBufferWithPrompts(List<AttributedString> secondaryPrompts) {
				if (interactive.dynamicPost == null || post != null) {
					return super.getDisplayedBufferWithPrompts(secondaryPrompts);
				}
				AttributedString rendered = interactive.dynamicPost.get();
				if (rendered == null || rendered.length() == 0) {
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
				Supplier<AttributedString> previousDynamicPost = interactive.dynamicPost;
				interactive.dynamicPost = null;
				try {
					super.doCleanup(newline);
				} finally {
					interactive.dynamicPost = previousDynamicPost;
				}
			}
		};
	}

	private static void ensureUsableTerminalSize(Terminal terminal) {
		int columns = terminal.getColumns();
		int rows = terminal.getRows();
		if (columns <= 0 || rows <= 0) {
			terminal.setSize(org.jline.terminal.Size.of(
					columns > 0 ? columns : InteractiveTerminal.DEFAULT_COLUMNS,
					rows > 0 ? rows : InteractiveTerminal.DEFAULT_ROWS));
		}
	}

	private static void installEditorBindings(InteractiveTerminal interactive) {
		LineReaderImpl reader = interactive.reader;
		String newlineWidgetName = "codingagent-insert-newline";
		reader.getWidgets().put(newlineWidgetName, () -> {
			reader.getBuffer().write('\n');
			return true;
		});
		Reference insertNewline = new Reference(newlineWidgetName);
		String[] newlineSequences = editorKeySequences("newline").toArray(String[]::new);

		String submitWidgetName = "codingagent-submit-or-insert-pasted-newline";
		reader.getWidgets().put(submitWidgetName, () -> submitOrInsertPastedNewline(interactive));
		Reference submit = new Reference(submitWidgetName);
		String[] submitSequences = editorKeySequences("submit").toArray(String[]::new);

		String suggestionUpWidgetName = "codingagent-previous-command-suggestion";
		reader.getWidgets().put(
				suggestionUpWidgetName,
				() -> moveSuggestionOrFallback(interactive, -1, LineReader.UP_LINE_OR_SEARCH));
		Reference suggestionUp = new Reference(suggestionUpWidgetName);
		String suggestionDownWidgetName = "codingagent-next-command-suggestion";
		reader.getWidgets().put(
				suggestionDownWidgetName,
				() -> moveSuggestionOrFallback(interactive, 1, LineReader.DOWN_LINE_OR_SEARCH));
		Reference suggestionDown = new Reference(suggestionDownWidgetName);
		String terminalUp = KeyMap.key(interactive.terminal, Capability.key_up);
		String terminalDown = KeyMap.key(interactive.terminal, Capability.key_down);

		reader.getWidgets().put(LineReader.BEGIN_PASTE, () -> insertBracketedPaste(interactive));
		for (var keyMap : reader.getKeyMaps().values()) {
			keyMap.bind(insertNewline, newlineSequences);
			keyMap.bind(submit, submitSequences);
			bindNavigationKey(keyMap, suggestionUp, terminalUp, "\u001b[A", "\u001bOA");
			bindNavigationKey(keyMap, suggestionDown, terminalDown, "\u001b[B", "\u001bOB");
		}
	}

	private static void bindNavigationKey(
			KeyMap<Binding> keyMap,
			Reference widget,
			String terminalSequence,
			String... fallbackSequences) {
		if (terminalSequence != null && !terminalSequence.isEmpty()) {
			keyMap.bind(widget, terminalSequence);
		}
		keyMap.bind(widget, fallbackSequences);
	}

	private static boolean moveSuggestionOrFallback(
			InteractiveTerminal interactive, int delta, String fallbackWidget) {
		if (interactive.activeCommandSuggestions != null
				&& moveCommandSuggestion(
						interactive.activeCommandSuggestions,
						interactive.reader.getBuffer().toString(),
						delta)) {
			return true;
		}
		interactive.reader.callWidget(fallbackWidget);
		return true;
	}

	/**
	 * Some consoles send pasted text without bracketed-paste markers. A pasted
	 * line ending is followed immediately by more input, unlike a submit key.
	 */
	private static boolean submitOrInsertPastedNewline(InteractiveTerminal interactive) {
		LineReaderImpl reader = interactive.reader;
		int next = reader.peekCharacter(InteractiveTerminal.PASTE_LOOKAHEAD_MILLIS);
		// Preserve multiline paste detection even when the pasted first line
		// happens to look like a slash command. A queued CR is instead treated
		// as the user's second Enter after choosing a command.
		if (next >= 0 && next != '\r') {
			if (next == '\n') reader.readCharacter();
			reader.getBuffer().write('\n');
			return true;
		}
		if (acceptActiveCommandSuggestion(interactive)) return true;
		if (next >= 0) {
			reader.getBuffer().write('\n');
			return true;
		}
		reader.callWidget(LineReader.ACCEPT_LINE);
		return true;
	}

	private static boolean acceptActiveCommandSuggestion(InteractiveTerminal interactive) {
		if (interactive.activeCommandSuggestions == null) return false;
		String command = acceptCommandSuggestion(
				interactive.activeCommandSuggestions, interactive.reader.getBuffer().toString());
		if (command == null) return false;
		interactive.reader.getBuffer().clear();
		interactive.reader.getBuffer().write(command);
		return true;
	}

	/** JLine maps every CR in a bracketed paste to LF, doubling CRLF input. */
	private static boolean insertBracketedPaste(InteractiveTerminal interactive) {
		StringBuilder content = new StringBuilder();
		while (true) {
			int value = interactive.reader.readCharacter();
			if (value < 0) break;
			content.append((char) value);
			if (builderEndsWith(content, InteractiveTerminal.BRACKETED_PASTE_END)) {
				content.setLength(content.length() - InteractiveTerminal.BRACKETED_PASTE_END.length());
				break;
			}
		}
		interactive.reader.getBuffer().write(normalizePastedLineEndings(content.toString()));
		return true;
	}

	private static boolean builderEndsWith(StringBuilder value, String suffix) {
		if (value.length() < suffix.length()) return false;
		int offset = value.length() - suffix.length();
		for (int index = 0; index < suffix.length(); index++) {
			if (value.charAt(offset + index) != suffix.charAt(index)) return false;
		}
		return true;
	}

	private static String normalizePastedLineEndings(String value) {
		return value.replace("\r\n", "\n").replace('\r', '\n');
	}

	/** Returns null on EOF and an empty string after Ctrl-C. */
	public static String readLine(InteractiveTerminal interactive, String prompt) {
		return readLineInternal(interactive, prompt, null, null, null);
	}

	/** Reads a line with an alphabetized slash-command panel below the prompt. */
	public static String readLine(
			InteractiveTerminal interactive, String prompt, List<String> slashCommands) {
		CommandSuggestions suggestions = slashCommands == null || slashCommands.isEmpty()
				? null
				: newCommandSuggestions(slashCommands);
		return readLineInternal(interactive, prompt, null, null, suggestions);
	}

	/** Reads a line whose editable buffer starts with {@code initialValue}. */
	public static String readLine(InteractiveTerminal interactive, String prompt, String initialValue) {
		return readLineInternal(interactive, prompt, initialValue, null, null);
	}

	/** Returns null on EOF and an empty string after Ctrl-C without echoing the entered value. */
	public static String readPassword(InteractiveTerminal interactive, String prompt) {
		return readLineInternal(interactive, prompt, null, '*', null);
	}

	private static String readLineInternal(
			InteractiveTerminal interactive,
			String prompt,
			String initialBuffer,
			Character mask,
			CommandSuggestions suggestions) {
		while (true) {
			Theme promptTheme = interactive.theme;
			try {
				String line =
						readEditorLine(interactive, prompt, mask, initialBuffer, promptTheme, suggestions);
				rememberCompletedLine(interactive, prompt, line, mask, promptTheme);
				return line;
			} catch (CancellationException signal) {
				if (signal != SUSPEND_REQUESTED) {
					throw signal;
				}
				initialBuffer = interactive.suspendedBuffer;
				interactive.restoreCursor = interactive.suspendedCursor;
				interactive.managedSuspend = true;
				try {
					callSuspendAction(interactive.suspendAction);
				} catch (IOException error) {
					println(interactive, "Could not suspend process: " + error.getMessage());
				} finally {
					repaintScreen(interactive);
					interactive.managedSuspend = false;
				}
			} catch (UserInterruptException ignored) {
				rememberCompletedLine(interactive, prompt, "", mask, promptTheme);
				return "";
			} catch (EndOfFileException ignored) {
				rememberPrompt(interactive, prompt, promptTheme);
				return null;
			}
		}
	}

	/** Keeps the background active so JLine's erase/edit operations preserve the full-width prompt bar. */
	private static String readEditorLine(
			InteractiveTerminal interactive,
			String prompt,
			Character mask,
			String initialBuffer,
			Theme promptTheme,
			CommandSuggestions suggestions) {
		String background = promptBackground(promptTheme);
		interactive.reader.setVariable(
				LineReader.SECONDARY_PROMPT_PATTERN,
				background.isEmpty()
						? InteractiveTerminal.SECONDARY_PROMPT
						: hiddenForJLine(background + Theme.CLEAR_TO_END_OF_LINE)
								+ InteractiveTerminal.SECONDARY_PROMPT);
		String editorPrompt =
				background.isEmpty() ? prompt : styleActivePromptLine(prompt, background);
		interactive.activeCommandSuggestions = suggestions;
		interactive.dynamicPost =
				suggestions == null ? null : () -> renderCommandPanel(interactive, suggestions, promptTheme);
		try {
			return interactive.reader.readLine(editorPrompt, null, mask, initialBuffer);
		} finally {
			interactive.dynamicPost = null;
			interactive.activeCommandSuggestions = null;
			resetPromptBackground(interactive, promptTheme);
		}
	}

	private static AttributedString renderCommandPanel(
			InteractiveTerminal interactive, CommandSuggestions suggestions, Theme promptTheme) {
		int columns = interactive.terminal.getColumns() > 0
				? interactive.terminal.getColumns()
				: InteractiveTerminal.DEFAULT_COLUMNS;
		List<String> lines = renderCommandSuggestions(
				suggestions, interactive.reader.getBuffer().toString(), columns, promptTheme);
		return lines.isEmpty()
				? new AttributedString("")
				: AttributedString.fromAnsi(String.join("\n", lines));
	}

	private static String styleActivePromptLine(String prompt, String background) {
		int activeLineOffset = activePromptLineOffset(prompt);
		return prompt.substring(0, activeLineOffset)
				+ hiddenForJLine(background + Theme.CLEAR_TO_END_OF_LINE)
				+ prompt.substring(activeLineOffset);
	}

	private static String hiddenForJLine(String value) {
		return "%{" + value + "%}";
	}

	private static int activePromptLineOffset(String prompt) {
		return Math.max(prompt.lastIndexOf('\n'), prompt.lastIndexOf('\r')) + 1;
	}

	/** Hosts a component on the alternate screen, restoring the line editor afterwards. */
	public static <T> T runComponent(InteractiveTerminal interactive, TuiComponent<T> component)
			throws IOException {
		if (interactive.reader.isReading()) resetPromptBackground(interactive, interactive.theme);
		suspendStatusBar(interactive);
		try {
			TuiRuntime runtime = new TuiRuntime(
					interactive.terminal,
					interactive.theme,
					interactive.supportsSuspend
							? () -> {
								suspendFullScreen(interactive);
								return null;
							}
							: null,
					() -> resumeMainScreen(interactive));
			return runTuiComponent(runtime, component);
		} finally {
			restoreStatusBar(interactive);
			if (interactive.reader.isReading()) interactive.reader.callWidget(LineReader.REDRAW_LINE);
		}
	}

	/**
	 * Runs an operation while listening for an interrupt key. The operation uses
	 * a virtual thread so Escape can be read even while it is blocked on a model
	 * response or tool. Ctrl-C remains an interrupt alias outside the line editor.
	 */
	public static <T> T runInterruptibly(
			InteractiveTerminal interactive, Callable<T> operation, Runnable interruptHandler)
			throws IOException, InterruptedException {
		Objects.requireNonNull(operation, "operation");
		Objects.requireNonNull(interruptHandler, "interruptHandler");
		Attributes originalAttributes = interactive.terminal.enterRawMode();
		FutureTask<T> task = new FutureTask<>(operation);
		Thread worker = Thread.ofVirtual().name("codingagent-interactive-operation").start(task);
		boolean interruptRequested = false;
		try {
			while (!task.isDone()) {
				TuiInput input = readTuiInput(interactive.terminal.reader(), 50);
				if (input instanceof TuiInput.Key key
						&& (key.type == TuiInput.KeyType.ESCAPE || key.type == TuiInput.KeyType.CANCEL)
						&& !interruptRequested
						&& !task.isDone()) {
					interruptRequested = true;
					interruptHandler.run();
				} else if (input instanceof TuiInput.Key key
						&& key.type == TuiInput.KeyType.SUSPEND
						&& interactive.supportsSuspend) {
					interactive.terminal.setAttributes(originalAttributes);
					interactive.managedSuspend = true;
					try {
						callSuspendAction(interactive.suspendAction);
					} catch (IOException error) {
						println(interactive, "Could not suspend process: " + error.getMessage());
					} finally {
						repaintScreen(interactive);
						interactive.managedSuspend = false;
						interactive.terminal.enterRawMode();
					}
				}
			}
			return awaitCompletedTask(task);
		} catch (IOException | RuntimeException | Error error) {
			if (!task.isDone()) {
				interruptHandler.run();
				worker.interrupt();
			}
			throw error;
		} finally {
			interactive.terminal.setAttributes(originalAttributes);
		}
	}

	private static <T> T awaitCompletedTask(FutureTask<T> task)
			throws IOException, InterruptedException {
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
	}

	/** Binds a configured application action while the line editor is active. */
	public static void bindAppAction(InteractiveTerminal interactive, String action, Runnable handler) {
		Objects.requireNonNull(handler, "handler");
		String widgetName = "codingagent-" + action;
		interactive.reader.getWidgets().put(widgetName, () -> {
			resetPromptBackground(interactive, interactive.theme);
			try {
				handler.run();
			} finally {
				interactive.reader.callWidget(LineReader.REDRAW_LINE);
			}
			return true;
		});
		Reference reference = new Reference(widgetName);
		for (var keyMap : interactive.reader.getKeyMaps().values()) {
			keyMap.bind(reference, appKeySequence(action));
		}
	}

	/** Prints a status line without losing the active line-editor buffer. */
	public static void printAbove(InteractiveTerminal interactive, String text) {
		synchronized (interactive) {
			if (interactive.reader.isReading()) resetPromptBackground(interactive, interactive.theme);
			interactive.reader.printAbove(text);
			remember(interactive, text + System.lineSeparator());
		}
	}

	public static void print(InteractiveTerminal interactive, String text) {
		synchronized (interactive) {
			String value = String.valueOf(text);
			interactive.terminal.writer().print(value);
			interactive.terminal.writer().flush();
			remember(interactive, value);
		}
	}

	public static void println(InteractiveTerminal interactive, String text) {
		synchronized (interactive) {
			String value = String.valueOf(text);
			interactive.terminal.writer().println(value);
			interactive.terminal.writer().flush();
			remember(interactive, value + System.lineSeparator());
		}
	}

	/** Replaces the main-screen document and redraws it from the top. */
	public static void replaceScreen(InteractiveTerminal interactive, String document) {
		synchronized (interactive) {
			interactive.screenDocument.setLength(0);
			interactive.screenDocument.append(document == null ? "" : document);
			repaintScreen(interactive);
		}
	}

	public static Theme terminalTheme(InteractiveTerminal interactive) {
		return interactive.theme;
	}

	public static void setTheme(InteractiveTerminal interactive, Theme theme) {
		interactive.theme = theme;
	}

	/** Shows or updates the status bar pinned to the bottom terminal row. */
	public static void setStatus(InteractiveTerminal interactive, String left, String right) {
		setStatus(interactive, "", InteractiveTerminal.StatusAccent.NONE, left, right);
	}

	/** Shows activity first so it remains visible when workspace/model details need truncation. */
	public static void setStatus(
			InteractiveTerminal interactive,
			String activity,
			InteractiveTerminal.StatusAccent accent,
			String left,
			String right) {
		synchronized (interactive) {
			interactive.statusActivity = activity == null ? "" : activity;
			interactive.statusAccent =
					accent == null ? InteractiveTerminal.StatusAccent.NONE : accent;
			interactive.statusLeft = left == null ? "" : left;
			interactive.statusRight = right == null ? "" : right;
			renderStatusBar(interactive);
		}
	}

	private static void handleTerminalResize(InteractiveTerminal interactive, Terminal.Signal signal) {
		if (interactive.previousResizeHandler != null
				&& interactive.previousResizeHandler != Terminal.SignalHandler.SIG_DFL
				&& interactive.previousResizeHandler != Terminal.SignalHandler.SIG_IGN) {
			interactive.previousResizeHandler.handle(signal);
		}
		synchronized (interactive) {
			if (interactive.statusBar != null) {
				interactive.statusBar.resize();
				renderStatusBar(interactive);
			}
		}
	}

	private static void renderStatusBar(InteractiveTerminal interactive) {
		if (interactive.statusLeft == null && interactive.statusRight == null) return;
		if (interactive.statusBar == null) {
			interactive.statusBar = Status.getStatus(interactive.terminal);
		}
		if (interactive.statusBar == null) return;
		int columns = interactive.terminal.getColumns();
		int width = columns > 0 ? columns : InteractiveTerminal.DEFAULT_COLUMNS;
		interactive.statusBar.update(List.of(AttributedString.fromAnsi(statusBarLine(
				interactive.statusActivity,
				interactive.statusAccent,
				interactive.statusLeft,
				interactive.statusRight,
				width,
				interactive.theme))));
	}

	/** Left- and right-aligns status content on one full-width row. */
	public static String statusBarLine(String left, String right, int width, Theme theme) {
		return statusBarLine("", InteractiveTerminal.StatusAccent.NONE, left, right, width, theme);
	}

	/**
	 * Keeps activity ahead of workspace/model metadata. When the terminal is
	 * narrow, metadata is discarded before the activity text is truncated.
	 */
	public static String statusBarLine(
			String activity,
			InteractiveTerminal.StatusAccent accent,
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
		String activityStyle = statusAccentStyle(theme, accent);
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

	private static String statusAccentStyle(Theme theme, InteractiveTerminal.StatusAccent accent) {
		return switch (accent == null ? InteractiveTerminal.StatusAccent.NONE : accent) {
			case NONE -> theme.muted;
			case READY -> readyStatus(theme);
			case ACTIVE -> activeStatus(theme);
			case TOOL -> toolStatus(theme);
			case WARNING -> warningStatus(theme);
		};
	}

	private static void suspendStatusBar(InteractiveTerminal interactive) {
		synchronized (interactive) {
			if (interactive.statusBar != null) interactive.statusBar.suspend();
		}
	}

	private static void restoreStatusBar(InteractiveTerminal interactive) {
		synchronized (interactive) {
			if (interactive.statusBar == null) return;
			interactive.statusBar.restore();
			if (interactive.statusBar.size() > 0) {
				// Alternate-screen switches can drop the scroll region on some terminals.
				int rows = interactive.terminal.getRows() > 0
						? interactive.terminal.getRows()
						: InteractiveTerminal.DEFAULT_ROWS;
				interactive.terminal.puts(Capability.save_cursor);
				interactive.terminal.puts(
						Capability.change_scroll_region, 0, rows - 1 - interactive.statusBar.size());
				interactive.terminal.puts(Capability.restore_cursor);
				renderStatusBar(interactive);
				interactive.terminal.flush();
			}
		}
	}

	private static boolean requestSuspend(InteractiveTerminal interactive) {
		interactive.suspendedBuffer = interactive.reader.getBuffer().toString();
		interactive.suspendedCursor = interactive.reader.getBuffer().cursor();
		throw SUSPEND_REQUESTED;
	}

	private static void handleContinue(InteractiveTerminal interactive, Terminal.Signal signal) {
		try {
			if (interactive.previousContinueHandler != null
					&& interactive.previousContinueHandler != Terminal.SignalHandler.SIG_DFL
					&& interactive.previousContinueHandler != Terminal.SignalHandler.SIG_IGN) {
				interactive.previousContinueHandler.handle(signal);
			}
		} finally {
			if (!interactive.managedSuspend) repaintScreen(interactive);
		}
	}

	private static void rememberCompletedLine(
			InteractiveTerminal interactive, String prompt, String line, Character mask, Theme promptTheme) {
		synchronized (interactive) {
			String displayedLine = line;
			if (mask != null) {
				displayedLine =
						mask.charValue() == 0 ? "" : String.valueOf(mask).repeat(line.length());
			}
			int activeLineOffset = activePromptLineOffset(prompt);
			remember(interactive, prompt.substring(0, activeLineOffset));
			remember(
					interactive,
					promptArea(promptTheme, prompt.substring(activeLineOffset) + displayedLine));
			remember(interactive, System.lineSeparator());
		}
	}

	private static void rememberPrompt(
			InteractiveTerminal interactive, String prompt, Theme promptTheme) {
		synchronized (interactive) {
			int activeLineOffset = activePromptLineOffset(prompt);
			remember(interactive, prompt.substring(0, activeLineOffset));
			remember(interactive, promptArea(promptTheme, prompt.substring(activeLineOffset)));
		}
	}

	private static void remember(InteractiveTerminal interactive, String text) {
		synchronized (interactive) {
			interactive.screenDocument.append(text);
		}
	}

	private static void suspendFullScreen(InteractiveTerminal interactive) throws IOException {
		interactive.fullScreenResumeAttributes =
				new Attributes(interactive.terminal.getAttributes());
		interactive.terminal.setAttributes(interactive.shellAttributes);
		interactive.managedSuspend = true;
		try {
			callSuspendAction(interactive.suspendAction);
		} catch (IOException | RuntimeException | Error error) {
			restoreFullScreenAttributes(interactive);
			interactive.managedSuspend = false;
			throw error;
		}
	}

	private static void resumeMainScreen(InteractiveTerminal interactive) {
		synchronized (interactive) {
			try {
				repaintScreen(interactive);
			} finally {
				restoreFullScreenAttributes(interactive);
				interactive.managedSuspend = false;
			}
		}
	}

	private static void restoreFullScreenAttributes(InteractiveTerminal interactive) {
		if (interactive.fullScreenResumeAttributes != null) {
			interactive.terminal.setAttributes(interactive.fullScreenResumeAttributes);
			interactive.fullScreenResumeAttributes = null;
		}
	}

	private static void resetPromptBackground(InteractiveTerminal interactive, Theme promptTheme) {
		if (promptBackground(promptTheme).isEmpty()) return;
		interactive.terminal.writer().print(promptTheme.reset);
		interactive.terminal.writer().flush();
	}

	private static void repaintScreen(InteractiveTerminal interactive) {
		synchronized (interactive) {
			interactive.terminal.writer().print(interactive.theme.reset);
			interactive.terminal.writer().print(InteractiveTerminal.BEGIN_SYNCHRONIZED_OUTPUT);
			boolean redrawStatusBar = interactive.statusBar != null && interactive.statusBar.size() > 0;
			// Release the status rows so the redrawn document starts on a clean screen.
			if (redrawStatusBar) interactive.statusBar.update(List.of());
			interactive.terminal.writer().print(InteractiveTerminal.CLEAR_SCREEN_AND_SCROLLBACK);
			// Re-reserve the bottom row before printing so the document scrolls above it.
			if (redrawStatusBar) renderStatusBar(interactive);
			interactive.terminal.writer().print(interactive.screenDocument);
			interactive.terminal.writer().print(InteractiveTerminal.END_SYNCHRONIZED_OUTPUT);
			interactive.terminal.writer().flush();
		}
	}

	/** Restores the signal handlers this terminal replaced and closes JLine. */
	public static void closeTerminal(InteractiveTerminal interactive) throws IOException {
		if (interactive.previousContinueHandler != null) {
			interactive.terminal.handle(Terminal.Signal.CONT, interactive.previousContinueHandler);
		}
		if (interactive.previousResizeHandler != null) {
			interactive.terminal.handle(Terminal.Signal.WINCH, interactive.previousResizeHandler);
		}
		interactive.terminal.close();
	}

	/**
	 * Builds the Windows console terminal that keeps Shift+Enter distinguishable,
	 * for the JLine provider SPI adapter in the tui package.
	 */
	public static Terminal shiftAwareWinSysTerminal(
			TerminalProvider provider,
			String name,
			String type,
			boolean ansiPassThrough,
			Charset encoding,
			Charset stdinEncoding,
			Charset stdoutEncoding,
			Charset stderrEncoding,
			boolean nativeSignals,
			Terminal.SignalHandler signalHandler,
			boolean paused,
			SystemStream systemStream)
			throws IOException {
		Charset outputEncoding = systemStream == SystemStream.Error ? stderrEncoding : stdoutEncoding;
		return ShiftAwareNativeWinSysTerminal.createTerminal(
				provider,
				systemStream,
				name,
				type,
				ansiPassThrough,
				encoding,
				stdinEncoding,
				outputEncoding,
				nativeSignals,
				signalHandler,
				paused);
	}

	// ------------------------------------------------------------------- cli

	/** Process entry point for the shaded jar and the native executable. */
	public static void main(String[] args) {
		System.exit(cliRun(args));
	}

	/** Parses the command line, runs the selected mode, and returns the exit code. */
	public static int cliRun(String[] args) {
		try {
			Cli parsed = parseCliArguments(args);
			if (parsed.version) {
				System.out.println(Cli.VERSION);
				return 0;
			}
			if (parsed.help) {
				printCliHelp();
				return 0;
			}
			CoreProviders providers = loadBundledCoreProviders();
			if (parsed.listModels) {
				printCliModels(providers, parsed.modelSearch);
				return 0;
			}
			if (parsed.mode.equals("rpc")) {
				return runRpcServer(newRpcServer(providers, parsed));
			}
			if (parsed.print) {
				return runCliPrint(providers, parsed);
			}
			return runInteractiveShell(providers, parsed);
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

	private static Cli parseCliArguments(String[] args) {
		Cli result = new Cli();
		List<String> messageParts = new ArrayList<>();
		for (int i = 0; i < args.length; i++) {
			String arg = args[i];
			switch (arg) {
				case "-h", "--help" -> result.help = true;
				case "-v", "--version" -> result.version = true;
				case "--list-models" -> {
					result.listModels = true;
					if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
						result.modelSearch = args[++i];
					}
				}
				case "--provider" -> result.provider = cliArgumentValue(args, ++i, arg);
				case "--model" -> result.model = cliArgumentValue(args, ++i, arg);
				case "--api-key" -> result.apiKey = cliArgumentValue(args, ++i, arg);
				case "--system-prompt" -> result.systemPrompt = cliArgumentValue(args, ++i, arg);
				case "--no-session" -> result.noSession = true;
				case "--mode" -> result.mode = cliArgumentValue(args, ++i, arg);
				case "-p", "--print" -> {
					result.print = true;
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
		result.message = String.join(" ", messageParts);
		if (!result.mode.equals("print") && !result.mode.equals("json") && !result.mode.equals("rpc")) {
			throw new IllegalArgumentException("--mode must be print, json, or rpc");
		}
		return result;
	}

	private static String cliArgumentValue(String[] args, int index, String flag) {
		if (index >= args.length || args[index].startsWith("-")) {
			throw new IllegalArgumentException(flag + " requires a value");
		}
		return args[index];
	}

	private static void printCliModels(CoreProviders providers, String search) {
		String needle = search == null ? "" : search.toLowerCase();
		for (Provider provider : allCoreProviders(providers)) {
			for (Model model : providerModels(provider)) {
				String id = model.provider + "/" + model.id;
				if (needle.isEmpty() || id.toLowerCase().contains(needle) || model.name.toLowerCase().contains(needle)) {
					System.out.printf("%-45s %s%n", id, model.name);
				}
			}
		}
	}

	private static int runCliPrint(CoreProviders providers, Cli arguments)
			throws InterruptedException, IOException {
		if (arguments.message.isBlank()) {
			throw new IllegalArgumentException("--print requires a prompt");
		}
		Model model = resolveCliModel(providers, arguments.provider, arguments.model);
		Provider provider = requireCoreProvider(providers, model.provider);
		Path cwd = Path.of(".").toAbsolutePath().normalize();
		McpManager mcp = mcpLoadDefaultManager(cwd);
		try {
			mcpAwaitReady(mcp);
			Agent agent = newAgent(arguments.systemPrompt, model, provider);
			agent.apiKey = arguments.apiKey;
			configureBuiltInTools(agent, cwd, arguments.systemPrompt);
			agent.state.tools.addAll(mcpTools(mcp));
			if (arguments.mode.equals("json")) {
				subscribe(agent, CodingAgentOperations::printCliJsonEvent);
			} else {
				subscribe(agent, CodingAgentOperations::printCliInstructionLoaded);
			}
			SessionRecorder recorder = arguments.noSession
					? null
					: createSessionRecorder(defaultSessionStore(), cwd, model.provider, model.id);
			List<Message> messages = prompt(agent, arguments.message);
			if (recorder != null) {
				appendSessionMessages(recorder, messages);
			}
			if (agent.state.messages.getLast() instanceof AssistantMessage response) {
				if (response.errorMessage != null) {
					System.err.println("Error: " + response.errorMessage);
					return 1;
				}
				if (!arguments.mode.equals("json")) {
					System.out.println(text(response));
				}
				return 0;
			}

			throw new IllegalStateException("Agent ended without an assistant response");
		} finally {
			mcpCloseManager(mcp);
		}
	}

	/**
	 * Adds local tools and composes repository {@code AGENTS.md} text into the
	 * agent's system prompt. Path-based tools refresh the prompt when they move
	 * into a deeper descendant scope.
	 */
	public static void configureBuiltInTools(Agent agent, Path cwd, String baseSystemPrompt) {
		AgentInstructions instructions = agentInstructionsForWorkingDirectory(cwd, baseSystemPrompt);
		Set<Path> announcedSources = new LinkedHashSet<>();
		agent.state.systemPrompt = instructions.systemPrompt;
		subscribe(agent, event -> {
			if (event instanceof AgentEvent.AgentStart) {
				refreshAgentInstructions(instructions);
				applyAgentInstructions(agent, instructions, announcedSources);
			}
		});
		agent.state.tools.addAll(builtInTools(cwd, path -> {
			if (observeAgentInstructions(instructions, path)) {
				applyAgentInstructions(agent, instructions, announcedSources);
			}
		}));
	}

	private static void applyAgentInstructions(
			Agent agent, AgentInstructions instructions, Set<Path> announcedSources) {
		agent.state.systemPrompt = instructions.systemPrompt;
		List<Path> sources = instructions.sources;
		announcedSources.retainAll(sources);
		for (Path source : sources) {
			if (announcedSources.add(source)) {
				instructionLoaded(agent, source);
			}
		}
	}

	public static String instructionLoadedMessage(Path path) {
		return "Found " + path;
	}

	private static void printCliInstructionLoaded(AgentEvent event) {
		if (event instanceof AgentEvent.InstructionLoaded loaded) {
			System.err.println(instructionLoadedMessage(loaded.path));
		}
	}

	private static void printCliJsonEvent(AgentEvent event) {
		ObjectNode node = jsonObject();
		switch (event) {
			case AgentEvent.AgentStart ignored -> node.put("type", "agent_start");
			case AgentEvent.AgentEnd end -> {
				node.put("type", "agent_end");
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
				if (update.providerEvent instanceof AssistantMessageEvent.TextDelta delta) {
					node.put("type", "text_delta");
					node.put("delta", delta.delta);
				} else {
					return;
				}
			}
			case AgentEvent.ToolExecutionStart start -> {
				node.put("type", "tool_start");
				node.put("toolCallId", start.toolCallId);
				node.put("tool", start.toolName);
				node.set("arguments", start.arguments);
			}
			case AgentEvent.ToolExecutionUpdate ignored -> {
				return;
			}
			case AgentEvent.ToolExecutionEnd end -> {
				node.put("type", "tool_end");
				node.put("toolCallId", end.toolCallId);
				node.put("tool", end.toolName);
				node.put("isError", end.result.isError);
			}
		}
		System.out.println(node);
	}

	/** Resolves {@code --provider}/{@code --model} into one bundled catalog model. */
	public static Model resolveCliModel(CoreProviders providers, String providerArg, String modelArg) {
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
		for (Model candidate : providerModels(requireCoreProvider(providers, provider))) {
			if (candidate.id.equals(model)) return candidate;
		}
		throw new IllegalArgumentException("Unknown model: " + provider + "/" + model);
	}

	private static void printCliHelp() {
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
				""".formatted(Cli.APP_NAME, Cli.APP_NAME));
	}

	// -------------------------------------------------------- activity status

	/** Creates a status; the phase is required and a missing detail becomes empty. */
	public static ActivityStatus activityStatus(
			ActivityStatus.Phase phase,
			String detail,
			int attempt,
			int maxAttempts,
			long startedNanos,
			long retryDelayNanos) {
		return new ActivityStatus(
				Objects.requireNonNull(phase, "phase"),
				detail == null ? "" : detail,
				attempt,
				maxAttempts,
				startedNanos,
				retryDelayNanos);
	}

	public static ActivityStatus noModelActivity(long nowNanos) {
		return activityStatus(ActivityStatus.Phase.NO_MODEL, "", 0, 0, nowNanos, 0);
	}

	public static ActivityStatus readyActivity(long nowNanos) {
		return activityStatus(ActivityStatus.Phase.READY, "", 0, 0, nowNanos, 0);
	}

	public static ActivityStatus activeActivity(ActivityStatus.Phase phase, long nowNanos) {
		return activeActivity(phase, "", nowNanos);
	}

	public static ActivityStatus activeActivity(ActivityStatus.Phase phase, String detail, long nowNanos) {
		return activityStatus(phase, detail, 0, 0, nowNanos, 0);
	}

	public static ActivityStatus retryingActivity(int attempt, int maxAttempts, long delayMs, long nowNanos) {
		long delayNanos;
		try {
			delayNanos = Math.multiplyExact(Math.max(0, delayMs), 1_000_000L);
		} catch (ArithmeticException ignored) {
			delayNanos = Long.MAX_VALUE;
		}
		return activityStatus(
				ActivityStatus.Phase.RETRYING, "", attempt, maxAttempts, nowNanos, delayNanos);
	}

	/** Whether a repeated event describes the same phase and should retain its elapsed timer. */
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
			case RETRYING -> activityRetryLabel(status, nowNanos);
			case STOPPING -> "◌ Stopping · " + formatActivityElapsed(status, nowNanos);
		};
	}

	public static InteractiveTerminal.StatusAccent activityAccent(ActivityStatus status) {
		return switch (status.phase) {
			case READY -> InteractiveTerminal.StatusAccent.READY;
			case NO_MODEL, RETRYING, STOPPING -> InteractiveTerminal.StatusAccent.WARNING;
			case RUNNING_TOOL -> InteractiveTerminal.StatusAccent.TOOL;
			default -> InteractiveTerminal.StatusAccent.ACTIVE;
		};
	}

	private static String activityBusyLabel(ActivityStatus status, String description, long nowNanos) {
		return activitySpinner(status, nowNanos) + " " + description + " · "
				+ formatActivityElapsed(status, nowNanos);
	}

	private static String activityRetryLabel(ActivityStatus status, long nowNanos) {
		String progress = status.attempt + "/" + status.maxAttempts;
		long remaining = Math.max(0, status.retryDelayNanos - activityElapsedNanos(status, nowNanos));
		if (remaining > 0) {
			long seconds = 1 + (remaining - 1) / 1_000_000_000L;
			return "↻ Retry " + progress + " in " + seconds + "s";
		}
		return "↻ Retry " + progress + " · waiting for model";
	}

	private static String activitySpinner(ActivityStatus status, long nowNanos) {
		long elapsedSeconds = activityElapsedNanos(status, nowNanos) / 1_000_000_000L;
		return ActivityStatus.SPINNER[(int) (elapsedSeconds % ActivityStatus.SPINNER.length)];
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

	/** Creates an instruction set; a missing base prompt becomes the empty string. */
	public static AgentInstructions agentInstructions(
			Path repositoryRoot, Path currentDirectory, String baseSystemPrompt) {
		return new AgentInstructions(
				repositoryRoot, currentDirectory, baseSystemPrompt == null ? "" : baseSystemPrompt);
	}

	/**
	 * Creates an instruction set rooted at the nearest enclosing Git worktree.
	 * If the working directory is not in a Git worktree, the working directory
	 * itself is used as the root.
	 */
	public static AgentInstructions agentInstructionsForWorkingDirectory(
			Path workingDirectory, String baseSystemPrompt) {
		Path directory = instructionDirectory(workingDirectory);
		if (directory == null) {
			throw new IllegalArgumentException("workingDirectory must have a parent directory");
		}
		AgentInstructions instructions = agentInstructions(
				findInstructionRepositoryRoot(directory), directory, baseSystemPrompt);
		refreshAgentInstructionsIn(instructions, directory);
		return instructions;
	}

	/** Creates an instruction set for an explicit repository root. */
	public static AgentInstructions agentInstructionsForRepository(
			Path repositoryRoot, Path workingDirectory, String baseSystemPrompt) {
		Path root = Objects.requireNonNull(repositoryRoot, "repositoryRoot").toAbsolutePath().normalize();
		Path directory = instructionDirectory(workingDirectory);
		if (directory == null || !directory.startsWith(root)) {
			throw new IllegalArgumentException("workingDirectory must be inside repositoryRoot");
		}
		AgentInstructions instructions = agentInstructions(root, directory, baseSystemPrompt);
		refreshAgentInstructionsIn(instructions, directory);
		return instructions;
	}

	/** Reloads the instruction files applicable to the current scope. */
	public static boolean refreshAgentInstructions(AgentInstructions instructions) {
		synchronized (instructions) {
			return refreshAgentInstructionsIn(instructions, instructions.currentDirectory);
		}
	}

	/**
	 * Observes a filesystem path used by the agent.
	 *
	 * <p>Only descendants of the current instruction scope are adopted. This
	 * makes a move from {@code repo/backend} to {@code repo/backend/database}
	 * pick up additional instructions without accidentally applying sibling
	 * directory instructions to the current task. The return value indicates
	 * whether the effective prompt changed.
	 */
	public static boolean observeAgentInstructions(AgentInstructions instructions, Path path) {
		synchronized (instructions) {
			Path directory = instructionDirectory(path);
			if (directory == null
					|| !directory.startsWith(instructions.repositoryRoot)
					|| !directory.startsWith(instructions.currentDirectory)) {
				return false;
			}
			return refreshAgentInstructionsIn(instructions, directory);
		}
	}

	private static boolean refreshAgentInstructionsIn(AgentInstructions instructions, Path directory) {
		List<Path> nextSources = new ArrayList<>();
		List<String> promptParts = new ArrayList<>();
		if (!instructions.baseSystemPrompt.isBlank()) {
			promptParts.add(instructions.baseSystemPrompt);
		}
		for (Path scope : instructionDirectoriesFromRoot(instructions, directory)) {
			Path instructionFile = instructionFile(scope);
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
		boolean changed = !directory.equals(instructions.currentDirectory)
				|| !nextPrompt.equals(instructions.systemPrompt)
				|| !immutableSources.equals(instructions.sources);
		instructions.currentDirectory = directory;
		instructions.systemPrompt = nextPrompt;
		instructions.sources = immutableSources;
		return changed;
	}

	private static List<Path> instructionDirectoriesFromRoot(
			AgentInstructions instructions, Path directory) {
		List<Path> directories = new ArrayList<>();
		for (Path current = directory; current != null; current = current.getParent()) {
			directories.add(current);
			if (current.equals(instructions.repositoryRoot)) {
				Collections.reverse(directories);
				return directories;
			}
		}
		return List.of();
	}

	private static Path findInstructionRepositoryRoot(Path workingDirectory) {
		for (Path current = workingDirectory; current != null; current = current.getParent()) {
			if (Files.exists(current.resolve(".git"))) return current;
		}
		return workingDirectory;
	}

	private static Path instructionFile(Path directory) {
		Path override = directory.resolve(AgentInstructions.OVERRIDE_FILE);
		if (isReadableRegularFile(override)) return override;
		Path standard = directory.resolve(AgentInstructions.AGENTS_FILE);
		return isReadableRegularFile(standard) ? standard : null;
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

	/** Runs the JLine-backed interactive shell until the user exits. */
	public static int runInteractiveShell(CoreProviders providers, Cli arguments)
			throws IOException, InterruptedException {
		SettingsStore settingsStore = defaultSettingsStore();
		SettingsStore.Settings settings = loadSettings(settingsStore);
		Path workspace = Path.of(".").toAbsolutePath().normalize();
		McpManager mcp = mcpLoadDefaultManager(workspace);
		try {
			InteractiveTerminal terminal = newInteractiveTerminal(Cli.APP_NAME);
			try {
				InteractiveShell shell =
						newInteractiveShell(providers, arguments, terminal, settingsStore, settings, mcp);
				try {
					applyShellSavedTheme(shell);
					if (arguments.model != null) {
						// An explicit CLI model overrides the saved default for this session only.
						configureShellModel(
								shell, resolveCliModel(providers, arguments.provider, arguments.model), false);
					} else if (arguments.provider != null) {
						Model model = selectShellStartupModel(providers, arguments.provider, terminal);
						if (model == null) return 0;
						configureShellModel(shell, model, true);
					} else if (!configureShellSavedModel(shell) && !configureShellSavedChatGpt(shell)) {
						configureShellSavedCopilot(shell);
					}
					return interactiveShellLoop(shell);
				} finally {
					closeInteractiveShell(shell);
				}
			} finally {
				closeTerminal(terminal);
			}
		} finally {
			mcpCloseManager(mcp);
		}
	}

	private static InteractiveShell newInteractiveShell(
			CoreProviders providers,
			Cli arguments,
			InteractiveTerminal terminal,
			SettingsStore settingsStore,
			SettingsStore.Settings settings,
			McpManager mcp) {
		InteractiveShell shell = new InteractiveShell();
		shell.providers = providers;
		shell.arguments = arguments;
		shell.terminal = terminal;
		shell.settingsStore = settingsStore;
		shell.mcp = mcp;
		shell.settings = settings;
		shell.hideThinkingBlock = settings.hideThinkingBlock;
		shell.activity = noModelActivity(System.nanoTime());
		shell.statusTicker = Executors.newSingleThreadScheduledExecutor(
				Thread.ofPlatform().daemon(true).name("codingagent-status").factory());
		bindAppAction(terminal, "expandTools", () -> showShellTurnDetails(shell, true));
		bindAppAction(
				terminal,
				"toggleThinking",
				() -> setShellHideThinkingBlock(shell, !shell.hideThinkingBlock, true));
		return shell;
	}

	/** Stops the status ticker started for this shell. */
	public static void closeInteractiveShell(InteractiveShell shell) {
		shell.statusTicker.shutdownNow();
		try {
			shell.statusTicker.awaitTermination(1, TimeUnit.SECONDS);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
	}

	private static Model selectShellStartupModel(
			CoreProviders providers, String providerId, InteractiveTerminal terminal) throws IOException {
		List<Model> models = providerId == null
				? allCatalogModels(providers.catalog)
				: providerModels(requireCoreProvider(providers, providerId));
		if (models.isEmpty()) {
			throw new IllegalArgumentException("No bundled models for provider: " + providerId);
		}
		List<SelectItem<Model>> items =
				models.stream().map(CodingAgentOperations::shellModelItem).toList();
		return select(terminal, "Select a model", items, -1, true);
	}

	private static int interactiveShellLoop(InteractiveShell shell) throws InterruptedException, IOException {
		print(shell.terminal, sessionScreenHeader(shell.agent == null ? null : shell.agent.state.model));
		refreshShellStatus(shell);
		shell.statusTicker.scheduleWithFixedDelay(() -> tickShellStatus(shell), 1, 1, TimeUnit.SECONDS);
		while (true) {
			String input = readLine(shell.terminal, "\n> ", InteractiveShell.SLASH_COMMANDS);
			if (input == null) {
				println(shell.terminal, "");
				return 0;
			}
			if (input.isBlank()) continue;
			if (input.startsWith("/")) {
				boolean exit;
				setShellActivity(shell, activeActivity(
						ActivityStatus.Phase.RUNNING_COMMAND, shellCommandName(input), System.nanoTime()));
				try {
					exit = runShellCommand(shell, input);
				} finally {
					setShellIdleActivity(shell);
					refreshShellStatus(shell);
				}
				if (exit) return 0;
				continue;
			}
			if (shell.agent == null) {
				println(shell.terminal, "No model configured. Run /login to choose a provider.");
				continue;
			}
			setShellActivity(shell, activeActivity(ActivityStatus.Phase.PREPARING_TOOLS, System.nanoTime()));
			mcpAwaitReady(shell.mcp);
			syncShellMcpTools(shell);
			shell.emittedText = false;
			shell.streamOutput = InteractiveShell.StreamOutput.NONE;
			shell.streamedThinkingCharacters = 0;
			AtomicBoolean interrupted = new AtomicBoolean();
			List<Message> messages = runInterruptibly(shell.terminal,
					() -> prompt(shell.agent, input),
					() -> {
						interrupted.set(true);
						setShellActivity(
								shell, activeActivity(ActivityStatus.Phase.STOPPING, System.nanoTime()));
						abort(shell.agent);
					});
			if (shell.recorder != null) appendSessionMessages(shell.recorder, messages);
			if (interrupted.get()) {
				finishShellStreamOutput(shell);
				println(shell.terminal, "Interrupted.");
			} else if (shell.agent.state.messages.getLast() instanceof AssistantMessage response) {
				String finalOutput = finalAssistantOutput(response, shell.emittedText);
				if (finalOutput != null) {
					finishShellStreamOutput(shell);
					println(shell.terminal, finalOutput);
				}
			}
			// AgentEnd normally performs this transition. Reassert it here to close
			// the small race where Escape arrives after AgentEnd but before the task returns.
			setShellActivity(shell, readyActivity(System.nanoTime()));
			refreshShellStatus(shell);
		}
	}

	private static boolean runShellCommand(InteractiveShell shell, String input)
			throws InterruptedException, IOException {
		switch (input.trim()) {
			case "/exit", "/quit" -> {
				return true;
			}
			case "/help" -> println(shell.terminal, "Commands: /help, /details, /fork, /resume, /login, /logout, /models, /mcp, /settings, /compact, /theme <dark|light|plain>, /exit\nShortcuts: Shift-Enter inserts a newline; Esc interrupts the active turn; Ctrl-O inspects reasoning/tool steps; Ctrl-T shows or hides streamed thinking.");
			case "/details" -> showShellTurnDetails(shell, false);
			case "/fork" -> forkShellSession(shell);
			case "/resume" -> resumeShellSession(shell);
			case "/login" -> shellLogin(shell);
			case "/logout" -> shellLogout(shell);
			case "/models" -> selectShellModel(shell);
			case "/mcp" -> selectShellMcpServers(shell);
			case "/settings" -> selectShellSettings(shell);
			case "/compact" -> {
				if (shell.agent == null) {
					println(shell.terminal, "No model is configured.");
					return false;
				}
				try {
					CompactionResult result = compact(shell.agent, null);
					println(shell.terminal, "Context compacted: " + result.tokensBefore + " -> " + result.estimatedTokensAfter + " tokens.");
					refreshShellStatus(shell);
				} catch (IllegalStateException error) {
					println(shell.terminal, "Error: " + error.getMessage());
				}
			}
			default -> {
				if (input.startsWith("/theme ")) {
					Theme theme = namedTheme(input.substring("/theme ".length()).trim());
					setTheme(shell.terminal, theme);
					refreshShellStatus(shell);
					shell.settings = withSettingsTheme(shell.settings, theme.name);
					try {
						setSettingsTheme(shell.settingsStore, theme.name);
						println(shell.terminal, "Theme: " + terminalTheme(shell.terminal).name);
					} catch (IOException error) {
						println(shell.terminal, "Theme changed for this session, but could not be saved: " + error.getMessage());
					}
				} else println(shell.terminal, "Unknown command: " + input);
			}
		}
		return false;
	}

	private static void applyShellSavedTheme(InteractiveShell shell) {
		if (shell.settings.theme != null) {
			setTheme(shell.terminal, namedTheme(shell.settings.theme));
		}
	}

	/** Returns true when a complete, valid saved model selection was restored. */
	private static boolean configureShellSavedModel(InteractiveShell shell) throws IOException {
		SettingsStore.Settings settings = shell.settings;
		if (settings.defaultProvider == null || settings.defaultModel == null) {
			return false;
		}
		Model model;
		try {
			model = findModelIn(
					providerModels(requireCoreProvider(shell.providers, settings.defaultProvider)),
					settings.defaultProvider,
					settings.defaultModel);
		} catch (IllegalArgumentException error) {
			model = null;
		}
		if (model == null) {
			println(shell.terminal, "Saved model " + settings.defaultProvider + "/" + settings.defaultModel
					+ " is unavailable; selecting a fallback.");
			return false;
		}
		if (model.provider.equals(GitHubCopilotAuth.PROVIDER_ID)) {
			GitHubCopilotProvider copilot = shellCopilotProvider(shell);
			try {
				if (!gitHubCopilotHasCredential(copilot.auth)) return false;
				model = findModelIn(gitHubCopilotAvailableModels(copilot), model.provider, model.id);
				if (model == null) {
					println(shell.terminal, "Saved GitHub Copilot model is not enabled for this account; selecting a fallback.");
					return false;
				}
			} catch (IOException error) {
				println(shell.terminal, "Could not restore the saved GitHub Copilot model: " + error.getMessage());
				return false;
			}
		}
		if (model.provider.equals(ChatGptAuth.PROVIDER_ID)) {
			try {
				if (!chatGptHasCredential(shellChatGptProvider(shell).auth)) return false;
			} catch (IOException error) {
				println(shell.terminal, "Could not restore the saved ChatGPT model: " + error.getMessage());
				return false;
			}
		}
		configureShellModel(shell, model, false);
		return true;
	}

	private static void configureShellSavedCopilot(InteractiveShell shell) {
		GitHubCopilotProvider copilot = shellCopilotProvider(shell);
		try {
			if (!gitHubCopilotHasCredential(copilot.auth)) {
				return;
			}
			List<Model> models = gitHubCopilotAvailableModels(copilot);
			Model model = preferredCopilotModel(models);
			if (model != null) {
				configureShellModel(shell, model, true);
			} else {
				println(shell.terminal, "GitHub Copilot has no enabled coding models. Run /login to refresh access.");
			}
		} catch (IOException error) {
			println(shell.terminal, "GitHub Copilot login needs attention: " + error.getMessage());
		}
	}

	/** Restores a valid ChatGPT login even when an earlier model selection was not persisted. */
	private static boolean configureShellSavedChatGpt(InteractiveShell shell) {
		ChatGptProvider chatGpt = shellChatGptProvider(shell);
		try {
			if (!chatGptHasCredential(chatGpt.auth) || chatGpt.models.isEmpty()) {
				return false;
			}
			configureShellModel(shell, preferredChatGptModel(chatGpt.models), true);
			return true;
		} catch (IOException error) {
			println(shell.terminal, "ChatGPT login needs attention: " + error.getMessage());
			return false;
		}
	}

	private static void shellLogin(InteractiveShell shell) throws IOException, InterruptedException {
		println(shell.terminal, "Log in to a provider:");
		println(shell.terminal, "  1. GitHub Copilot — sign in through GitHub's device authorization flow");
		println(shell.terminal, "  2. OpenAI API key — use separately billed Platform API credits");
		println(shell.terminal, "  3. ChatGPT Plus/Pro — use your ChatGPT subscription through Codex");
		String choice = readLine(shell.terminal, "Select provider [1-3]: ");
		if (choice == null || choice.isBlank()) {
			println(shell.terminal, "Login cancelled.");
			return;
		}
		switch (choice.trim().toLowerCase(Locale.ROOT)) {
			case "1", "github", "github copilot", "copilot" -> shellLoginCopilot(shell);
			case "2", "openai", "open ai", "openai api", "openai api key" -> shellLoginOpenAi(shell);
			case "3", "chatgpt", "chatgpt plus", "chatgpt pro", "chatgpt plus/pro" -> shellLoginChatGpt(shell);
			default -> println(shell.terminal, "Unknown provider. Enter 1 for GitHub Copilot, 2 for an OpenAI API key, or 3 for ChatGPT Plus/Pro.");
		}
	}

	private static void shellLoginCopilot(InteractiveShell shell) throws IOException, InterruptedException {
		GitHubCopilotProvider copilot = shellCopilotProvider(shell);
		GitHubCopilotAuth.DeviceCode device = gitHubCopilotBeginLogin(copilot.auth);
		println(shell.terminal, "Open " + device.verificationUri + " and enter code " + device.userCode + ".");
		println(shell.terminal, "Waiting for GitHub authorization...");
		gitHubCopilotCompleteLogin(copilot.auth, device);
		println(shell.terminal, "Enabling GitHub Copilot models...");
		GitHubCopilotProvider.ModelAccess access = gitHubCopilotEnableAndRefreshModels(copilot);
		if (access.policiesEnabled < copilot.models.size()) {
			println(shell.terminal, "Some GitHub Copilot models are unavailable for this account.");
		}
		List<Model> models = access.models;
		Model model = shellSavedModelIn(shell, models);
		if (model == null) model = preferredCopilotModel(models);
		if (model == null) {
			println(shell.terminal, "GitHub Copilot login succeeded, but no enabled coding model was returned.");
			return;
		}
		configureShellModel(shell, model, true);
		println(shell.terminal, "GitHub Copilot is ready with " + model + ".");
	}

	private static void shellLoginOpenAi(InteractiveShell shell) throws IOException {
		String apiKey = readPassword(shell.terminal, "OpenAI API key: ");
		if (apiKey == null || apiKey.isBlank()) {
			println(shell.terminal, "OpenAI login cancelled.");
			return;
		}
		modifyCredential(
				defaultCredentialStore(), "openai", ignored -> apiKeyCredential(apiKey.trim()));
		List<Model> models = providerModels(requireCoreProvider(shell.providers, "openai"));
		Model model = shellSavedModelIn(shell, models);
		if (model == null) {
			model = select(
					shell.terminal,
					"Select an OpenAI model",
					models.stream().map(CodingAgentOperations::shellModelItem).toList(),
					-1,
					true);
		}
		if (model == null) {
			println(shell.terminal, "OpenAI API key saved. Run /models when you are ready to select a model.");
			return;
		}
		configureShellModel(shell, model, true);
		println(shell.terminal, "OpenAI is ready with " + model + ".");
	}

	private static void shellLoginChatGpt(InteractiveShell shell) throws IOException, InterruptedException {
		ChatGptProvider chatGpt = shellChatGptProvider(shell);
		ChatGptAuth.DeviceCode device = chatGptBeginLogin(chatGpt.auth);
		println(shell.terminal, "Open " + device.verificationUri + " and enter code " + device.userCode + ".");
		println(shell.terminal, "Waiting for ChatGPT authorization...");
		chatGptCompleteLogin(chatGpt.auth, device);
		List<Model> models = chatGpt.models;
		Model model = shellSavedModelIn(shell, models);
		if (model == null) {
			model = select(
					shell.terminal,
					"Select a ChatGPT model",
					models.stream().map(CodingAgentOperations::shellModelItem).toList(),
					-1,
					true);
		}
		if (model == null) {
			println(shell.terminal, "ChatGPT login saved. Run /models when you are ready to select a model.");
			return;
		}
		configureShellModel(shell, model, true);
		println(shell.terminal, "ChatGPT Plus/Pro is ready with " + model + ".");
	}

	private static void shellLogout(InteractiveShell shell) throws IOException {
		try {
			if (shell.agent != null && shell.agent.state.model.provider.equals(ChatGptAuth.PROVIDER_ID)) {
				chatGptLogout(shellChatGptProvider(shell).auth);
				shell.agent = null;
				println(shell.terminal, "ChatGPT credentials removed. Run /login or /resume to continue.");
				return;
			}
			if (shell.agent != null && shell.agent.state.model.provider.equals("openai")) {
				deleteCredential(defaultCredentialStore(), "openai");
				shell.agent = null;
				println(shell.terminal, "OpenAI API key removed. Run /login or /resume to continue.");
				return;
			}
			gitHubCopilotLogout(shellCopilotProvider(shell).auth);
			if (shell.agent != null
					&& shell.agent.state.model.provider.equals(GitHubCopilotAuth.PROVIDER_ID)) {
				shell.agent = null;
				println(shell.terminal, "GitHub Copilot credentials removed. Run /login or /resume to continue.");
			} else {
				println(shell.terminal, "GitHub Copilot credentials removed.");
			}
		} finally {
			refreshShellStatus(shell);
		}
	}

	private static void resumeShellSession(InteractiveShell shell) throws IOException {
		if (shell.arguments.noSession) {
			println(shell.terminal, "Session persistence is disabled by --no-session.");
			return;
		}
		SessionStore store = defaultSessionStore();
		List<SessionSnapshot> sessions;
		try {
			sessions = listSessionSnapshots(store, shell.cwd);
		} catch (IOException error) {
			println(shell.terminal, "Failed to list saved sessions: " + error.getMessage());
			return;
		}
		if (sessions.isEmpty()) {
			println(shell.terminal, "No saved sessions in " + shell.cwd + ".");
			return;
		}
		String currentSessionId = shell.recorder == null ? null : shell.recorder.sessionId;
		List<SelectItem<SessionSnapshot>> items = sessions.stream()
				.filter(session -> session.messageCount > 0 && !session.id.equals(currentSessionId))
				.map(CodingAgentOperations::sessionSelectItem)
				.toList();
		if (items.isEmpty()) {
			println(shell.terminal, "No resumable sessions in " + shell.cwd + ".");
			return;
		}
		SessionSnapshot selected = select(shell.terminal, "Resume Session (Current Folder)", items, -1, true);
		if (selected == null) return;
		try {
			resumeShellSnapshot(shell, store, selected);
		} catch (IOException | IllegalArgumentException error) {
			println(shell.terminal, "Failed to resume session: " + error.getMessage());
		}
	}

	private static void forkShellSession(InteractiveShell shell) throws IOException {
		if (shell.agent == null) {
			println(shell.terminal, "No model is configured.");
			return;
		}
		String name = readLine(shell.terminal, "Fork session name: ", forkName(shell.sessionName));
		if (name == null || name.isBlank()) {
			println(shell.terminal, "Fork cancelled.");
			return;
		}
		name = name.strip();
		AgentState state = shell.agent.state;
		Model model = state.model;
		String systemPrompt = state.systemPrompt;
		ThinkingLevel thinkingLevel = state.thinkingLevel;
		boolean autoCompactionEnabled = state.autoCompactionEnabled;
		int compactionReserveTokens = state.compactionReserveTokens;
		List<Message> forkMessages = resumableMessages(state.messages);
		SessionRecorder forkRecorder = null;
		if (!shell.arguments.noSession) {
			try {
				forkRecorder = forkSessionRecorder(
						defaultSessionStore(), shell.cwd, model.provider, model.id, name, forkMessages);
			} catch (IOException error) {
				println(shell.terminal, "Failed to fork session: " + error.getMessage());
				return;
			}
		}
		configureShellAgent(shell, model, shell.cwd, forkRecorder, name);
		shell.agent.state.systemPrompt = systemPrompt;
		shell.agent.state.thinkingLevel = thinkingLevel;
		shell.agent.state.autoCompactionEnabled = autoCompactionEnabled;
		shell.agent.state.compactionReserveTokens = compactionReserveTokens;
		shell.agent.state.messages.addAll(forkMessages);
		refreshShellStatus(shell);
		println(shell.terminal, "Forked session " + name + " with " + forkMessages.size() + " message(s).");
	}

	public static String forkName(String currentSessionName) {
		return currentSessionName == null || currentSessionName.isBlank()
				? "fork"
				: currentSessionName.strip() + " fork";
	}

	private static void resumeShellSnapshot(
			InteractiveShell shell, SessionStore store, SessionSnapshot session) throws IOException {
		if (!Files.isDirectory(session.cwd)) {
			println(shell.terminal, "Cannot resume session because its working directory is unavailable: " + session.cwd);
			return;
		}
		Model model;
		try {
			model = findModelIn(
					providerModels(requireCoreProvider(shell.providers, session.provider)),
					session.provider,
					session.model);
		} catch (IllegalArgumentException error) {
			model = null;
		}
		if (model == null) {
			if (shell.agent == null) {
				println(shell.terminal, "Cannot restore model " + session.provider + "/" + session.model
						+ "; configure an available model before resuming this session.");
				return;
			}
			model = shell.agent.state.model;
			println(shell.terminal, "Could not restore model " + session.provider + "/" + session.model
					+ ". Using " + model + ".");
		}
		if (model.provider.equals(GitHubCopilotAuth.PROVIDER_ID)) {
			GitHubCopilotProvider copilot = shellCopilotProvider(shell);
			Model enabled = null;
			try {
				if (gitHubCopilotHasCredential(copilot.auth)) {
					enabled = findModelIn(gitHubCopilotAvailableModels(copilot), model.provider, model.id);
				}
			} catch (IOException error) {
				println(shell.terminal, "Could not refresh GitHub Copilot model access: " + error.getMessage());
			}
			if (enabled == null) {
				if (shell.agent == null
						|| shell.agent.state.model.provider.equals(GitHubCopilotAuth.PROVIDER_ID)) {
					println(shell.terminal, "Cannot restore GitHub Copilot model " + model.id + "; log in or configure another model first.");
					return;
				}
				Model fallback = shell.agent.state.model;
				println(shell.terminal, "Could not restore model " + model + ". Using " + fallback + ".");
				model = fallback;
			} else {
				model = enabled;
			}
		}
		SessionRecorder resumedRecorder = resumeSessionRecorder(store, session.id);
		// A session snapshot's messages are compaction-aware, so resuming cannot
		// resurrect summarized transcript entries into the next model request.
		List<Message> restored = resumableMessages(session.messages);
		configureShellAgent(shell, model, session.cwd, resumedRecorder, session.name);
		shell.settings = withSettingsDefaultModel(shell.settings, model.provider, model.id);
		shell.agent.state.messages.addAll(restored);
		refreshShellStatus(shell);
		replaceScreen(shell.terminal, renderSessionScreen(
				model, session.transcriptMessages, shell.hideThinkingBlock, terminalTheme(shell.terminal)));
		try {
			setSettingsDefaultModelAndProvider(shell.settingsStore, model.provider, model.id);
		} catch (IOException error) {
			println(shell.terminal, "Resumed model could not be saved as the default: " + error.getMessage());
		}
		println(shell.terminal, "Resumed session " + sessionDisplayName(session) + " with " + restored.size()
				+ " message(s) using " + model + ".");
	}

	/** Rebuilds the visible transcript for a resumed session. */
	public static String renderSessionScreen(
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
				case AssistantMessage assistant -> appendSessionAssistant(
						screen, assistant, hideThinking, theme, toolResults, renderedToolResults);
				case ToolResultMessage result -> {
					if (renderedToolResults.add(result.toolCallId)) appendSessionToolResult(screen, result);
				}
			}
		}
		return screen.toString();
	}

	private static String sessionScreenHeader(Model model) {
		StringBuilder header = new StringBuilder("codingagent ").append(Cli.VERSION);
		if (model != null) header.append("  ").append(model);
		header.append('\n');
		header.append(model == null
				? "Run /login to choose a provider. Commands: /help, /resume, /login, /mcp, /exit"
				: "Enter submits; Shift-Enter adds a newline; Esc interrupts. Ctrl-O inspects steps; Ctrl-T toggles thinking. Commands: /help, /fork, /resume, /models, /mcp, /settings, /compact, /logout, /theme <dark|light|plain>, /exit");
		header.append('\n');
		return header.toString();
	}

	private static void appendSessionAssistant(
			StringBuilder screen,
			AssistantMessage assistant,
			boolean hideThinking,
			Theme theme,
			Map<String, ToolResultMessage> toolResults,
			Set<String> renderedToolResults) {
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

	private static void appendSessionToolResult(StringBuilder screen, ToolResultMessage result) {
		screen.append("  ")
				.append(result.isError ? "Error" : "Done")
				.append(": ")
				.append(toolResultSummary(result.toolName, text(result), result.isError))
				.append('\n');
	}

	/** Drops failed turns and synthesizes results for tool calls that never completed. */
	public static List<Message> resumableMessages(List<Message> messages) {
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

	private static void appendMissingToolResults(
			List<Message> messages, Map<String, String> pendingToolCalls) {
		for (Map.Entry<String, String> toolCall : pendingToolCalls.entrySet()) {
			messages.add(toolResultMessage(
					toolCall.getKey(), toolCall.getValue(), "No result provided", true));
		}
		pendingToolCalls.clear();
	}

	private static SelectItem<SessionSnapshot> sessionSelectItem(SessionSnapshot session) {
		String message = abbreviateShellText(sessionDisplayName(session).replaceAll("[\\p{Cntrl}]", " "), 90);
		String description = session.messageCount + " messages  " + formatSessionAge(session.modified)
				+ "  [" + session.provider + "/" + session.model + "]";
		return selectItem(
				session,
				message,
				description,
				session.id + " " + sessionDisplayName(session) + " " + session.provider + " " + session.model
						+ " " + session.firstMessage + " " + session.allMessagesText);
	}

	private static String sessionDisplayName(SessionSnapshot session) {
		return session.name == null ? session.firstMessage : session.name;
	}

	/** The text to print after a turn, or null when it was already streamed. */
	public static String finalAssistantOutput(AssistantMessage response, boolean emittedText) {
		if (response.errorMessage != null) return "Error: " + response.errorMessage;
		return emittedText ? null : text(response);
	}

	private static String formatRetryDelay(long delayMs) {
		if (delayMs < 1_000) return delayMs + "ms";
		if (delayMs % 1_000 == 0) return delayMs / 1_000 + "s";
		return String.format(Locale.ROOT, "%.1fs", delayMs / 1_000.0);
	}

	private static String formatSessionAge(Instant instant) {
		long minutes = Math.max(0, Duration.between(instant, Instant.now()).toMinutes());
		if (minutes < 1) return "now";
		if (minutes < 60) return minutes + "m";
		long hours = minutes / 60;
		if (hours < 24) return hours + "h";
		long days = hours / 24;
		if (days < 7) return days + "d";
		if (days < 30) return days / 7 + "w";
		if (days < 365) return days / 30 + "mo";
		return days / 365 + "y";
	}

	private static void selectShellModel(InteractiveShell shell) throws IOException {
		List<Model> models = shellSelectableModels(shell);
		List<SelectItem<Model>> items =
				models.stream().map(CodingAgentOperations::shellModelItem).toList();
		int currentIndex = shell.agent == null ? -1 : shellModelIndex(models, shell.agent.state.model);
		Model model = select(shell.terminal, "Select a model", items, currentIndex, true);
		if (model == null) {
			return;
		}
		configureShellModel(shell, model, true);
		println(shell.terminal, "Using " + model + " in a new agent session.");
	}

	private static void selectShellMcpServers(InteractiveShell shell) throws IOException {
		if (mcpIsEmpty(shell.mcp)) {
			println(shell.terminal, "No MCP servers configured in ~/.codingagent/settings.json.");
			return;
		}
		McpSelector selector =
				newMcpSelector(shell.mcp, change -> persistShellMcpChange(shell, change));
		runComponent(shell.terminal, mcpSelectorComponent(selector));
		// An OAuth connection may finish asynchronously while the selector is open.
		syncShellMcpTools(shell);
	}

	private static void persistShellMcpChange(InteractiveShell shell, McpSelector.Change change) {
		try {
			if (change.toolName != null) {
				setSettingsMcpToolEnabled(
						shell.settingsStore, change.serverName, change.toolName, change.enabled);
			} else {
				setSettingsMcpServerEnabled(shell.settingsStore, change.serverName, change.enabled);
			}
		} catch (IOException error) {
			throw new UncheckedIOException(error);
		} finally {
			syncShellMcpTools(shell);
		}
	}

	private static void selectShellSettings(InteractiveShell shell) throws IOException {
		if (shell.agent == null) {
			println(shell.terminal, "No model is configured.");
			return;
		}
		String selected = select(
				shell.terminal,
				"Settings",
				List.of(selectItem("thinking", "Thinking level", shell.agent.state.thinkingLevel.wire)),
				0,
				false);
		if (selected != null) {
			selectShellThinkingLevel(shell);
		}
	}

	private static void selectShellThinkingLevel(InteractiveShell shell) throws IOException {
		List<ThinkingLevel> levels = getSupportedThinkingLevels(shell.agent.state.model);
		List<SelectItem<ThinkingLevel>> items = levels.stream()
				.map(level -> selectItem(level, level.wire, thinkingLevelDescription(level)))
				.toList();
		int currentIndex = Math.max(0, levels.indexOf(shell.agent.state.thinkingLevel));
		ThinkingLevel level = select(shell.terminal, "Thinking level", items, currentIndex, false);
		if (level == null) {
			return;
		}
		shell.agent.state.thinkingLevel = level;
		refreshShellStatus(shell);
		shell.settings = withSettingsDefaultThinkingLevel(shell.settings, level);
		try {
			setSettingsDefaultThinkingLevel(shell.settingsStore, level);
			println(shell.terminal, "Thinking level: " + level.wire);
		} catch (IOException error) {
			println(shell.terminal, "Thinking level changed for this session, but could not be saved: " + error.getMessage());
		}
	}

	private static String thinkingLevelDescription(ThinkingLevel level) {
		return switch (level) {
			case OFF -> "No reasoning";
			case MINIMAL -> "Very brief reasoning";
			case LOW -> "Light reasoning";
			case MEDIUM -> "Moderate reasoning";
			case HIGH -> "Deep reasoning";
			case XHIGH -> "Extra-high reasoning";
			case MAX -> "Maximum reasoning";
		};
	}

	private static List<Model> shellSelectableModels(InteractiveShell shell) {
		List<Model> models = new ArrayList<>();
		for (Model model : allCatalogModels(shell.providers.catalog)) {
			if (!model.provider.equals(GitHubCopilotAuth.PROVIDER_ID)) {
				models.add(model);
			}
		}
		GitHubCopilotProvider copilot = shellCopilotProvider(shell);
		try {
			if (gitHubCopilotHasCredential(copilot.auth)) {
				println(shell.terminal, "Refreshing GitHub Copilot models...");
				models.addAll(gitHubCopilotEnableAndRefreshModels(copilot).models);
			} else {
				models.addAll(copilot.models);
			}
		} catch (IOException error) {
			println(shell.terminal, "Could not refresh GitHub Copilot model access: " + error.getMessage());
			models.addAll(copilot.models);
		}
		models.addAll(shellChatGptProvider(shell).models);
		return List.copyOf(models);
	}

	private static void configureShellModel(InteractiveShell shell, Model model, boolean persistModel)
			throws IOException {
		Path configuredCwd = Path.of(".").toAbsolutePath().normalize();
		SessionRecorder nextRecorder = null;
		if (!shell.arguments.noSession) {
			try {
				nextRecorder = createSessionRecorder(
						defaultSessionStore(), configuredCwd, model.provider, model.id);
			} catch (IOException error) {
				println(shell.terminal, "Model configured, but session persistence is unavailable: " + error.getMessage());
			}
		}
		configureShellAgent(shell, model, configuredCwd, nextRecorder, null);
		if (persistModel) {
			shell.settings = withSettingsDefaultModel(shell.settings, model.provider, model.id);
			try {
				setSettingsDefaultModelAndProvider(shell.settingsStore, model.provider, model.id);
			} catch (IOException error) {
				println(shell.terminal, "Model changed for this session, but could not be saved: " + error.getMessage());
			}
		}
	}

	private static void configureShellAgent(
			InteractiveShell shell,
			Model model,
			Path configuredCwd,
			SessionRecorder nextRecorder,
			String nextSessionName) {
		Provider provider = requireCoreProvider(shell.providers, model.provider);
		Agent configured = newAgent(shell.arguments.systemPrompt, model, provider);
		configured.apiKey = shell.arguments.apiKey;
		configured.state.thinkingLevel =
				initialThinkingLevel(model, shell.settings.defaultThinkingLevel);
		configureBuiltInTools(configured, configuredCwd, shell.arguments.systemPrompt);
		configured.state.tools.addAll(mcpTools(shell.mcp));
		subscribe(configured, event -> onShellAgentEvent(shell, event));
		shell.cwd = configuredCwd.toAbsolutePath().normalize();
		shell.agent = configured;
		shell.recorder = nextRecorder;
		shell.sessionName = nextSessionName;
		if (shell.activity.phase != ActivityStatus.Phase.RUNNING_COMMAND) {
			setShellActivity(shell, readyActivity(System.nanoTime()));
		}
		refreshShellStatus(shell);
	}

	/** Updates cached workspace/model details, then redraws the live activity status. */
	private static void refreshShellStatus(InteractiveShell shell) {
		String branch = gitBranch(shell.cwd);
		shell.statusLocation = displayPath(Path.of(System.getProperty("user.home", "")), shell.cwd)
				+ (branch == null ? "" : " [" + branch + "]")
				+ (shell.sessionName == null ? "" : " \u2022 " + shell.sessionName);
		shell.statusModel = shell.agent == null
				? ""
				: modelStatus(
						shell.agent.state.model,
						shell.agent.state.thinkingLevel,
						contextTokens(shell.agent.state.messages));
		renderShellStatus(shell);
	}

	private static void tickShellStatus(InteractiveShell shell) {
		if (!isDynamicActivity(shell.activity)) return;
		try {
			renderShellStatus(shell);
		} catch (RuntimeException ignored) {
			// A best-effort repaint must not terminate the shell's status ticker.
		}
	}

	private static void renderShellStatus(InteractiveShell shell) {
		ActivityStatus current = shell.activity;
		setStatus(
				shell.terminal,
				activityLabel(current, System.nanoTime()),
				activityAccent(current),
				shell.statusLocation,
				shell.statusModel);
	}

	private static void setShellIdleActivity(InteractiveShell shell) {
		setShellActivity(shell, shell.agent == null
				? noModelActivity(System.nanoTime())
				: readyActivity(System.nanoTime()));
	}

	private static void setShellActivity(InteractiveShell shell, ActivityStatus next) {
		boolean changed;
		synchronized (shell.activityLock) {
			ActivityStatus current = shell.activity;
			if (current.phase == ActivityStatus.Phase.STOPPING
					&& next.phase != ActivityStatus.Phase.READY
					&& next.phase != ActivityStatus.Phase.NO_MODEL) {
				return;
			}
			changed = !sameActivity(current, next);
			if (changed) shell.activity = next;
		}
		if (changed) renderShellStatus(shell);
	}

	private static String shellCommandName(String input) {
		String trimmed = input.trim();
		for (int index = 0; index < trimmed.length(); index++) {
			if (Character.isWhitespace(trimmed.charAt(index))) return trimmed.substring(0, index);
		}
		return trimmed;
	}

	/** Formats the model segment, e.g. {@code GPT-5.6 Sol Max (0%)}. */
	public static String modelStatus(Model model, ThinkingLevel level, long contextTokens) {
		StringBuilder status = new StringBuilder(model.name);
		if (level != null && level != ThinkingLevel.OFF) {
			status.append(' ').append(shellThinkingLabel(level));
		}
		long percent = model.contextWindow > 0
				? Math.max(0, Math.round(100.0 * contextTokens / model.contextWindow))
				: 0;
		return status.append(" (").append(percent).append("%)").toString();
	}

	private static String shellThinkingLabel(ThinkingLevel level) {
		return switch (level) {
			case OFF -> "Off";
			case MINIMAL -> "Minimal";
			case LOW -> "Low";
			case MEDIUM -> "Medium";
			case HIGH -> "High";
			case XHIGH -> "XHigh";
			case MAX -> "Max";
		};
	}

	/** Context tokens consumed by the most recent successful assistant response. */
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

	/** Abbreviates the home directory to {@code ~}, e.g. {@code ~/xa/coding-agent}. */
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

	/** Reads the checked-out branch (or short detached commit) without spawning git. */
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

	private static void syncShellMcpTools(InteractiveShell shell) {
		if (shell.agent == null || shell.agent.state.isStreaming) return;
		shell.agent.state.tools.removeIf(McpAgentTool.class::isInstance);
		shell.agent.state.tools.addAll(mcpTools(shell.mcp));
	}

	private static GitHubCopilotProvider shellCopilotProvider(InteractiveShell shell) {
		return (GitHubCopilotProvider)
				requireCoreProvider(shell.providers, GitHubCopilotAuth.PROVIDER_ID);
	}

	private static ChatGptProvider shellChatGptProvider(InteractiveShell shell) {
		return (ChatGptProvider) requireCoreProvider(shell.providers, ChatGptAuth.PROVIDER_ID);
	}

	private static Model shellSavedModelIn(InteractiveShell shell, List<Model> models) {
		if (shell.settings.defaultProvider == null || shell.settings.defaultModel == null) return null;
		return findModelIn(models, shell.settings.defaultProvider, shell.settings.defaultModel);
	}

	/** Clamps the configured (or default) thinking level to what the model supports. */
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
		return selectItem(
				model, model.id, description, model.provider + " " + model.id + " " + model.name);
	}

	private static int shellModelIndex(List<Model> models, Model selected) {
		for (int index = 0; index < models.size(); index++) {
			Model model = models.get(index);
			if (model.provider.equals(selected.provider) && model.id.equals(selected.id)) {
				return index;
			}
		}
		return -1;
	}

	private static void showShellTurnDetails(InteractiveShell shell, boolean lineEditorActive) {
		if (shell.agent == null) {
			showShellShortcutStatus(shell, "No model is configured.", lineEditorActive);
			return;
		}
		TurnDetailsComponent details =
				turnDetailsForLatestTurn(shell.agent.state.messages, shell.hideThinkingBlock);
		if (details == null) {
			showShellShortcutStatus(
					shell, "The latest turn has no reasoning or tool details.", lineEditorActive);
			return;
		}
		try {
			boolean hiddenAfter = runComponent(shell.terminal, turnDetailsComponent(details));
			if (hiddenAfter != shell.hideThinkingBlock) {
				setShellHideThinkingBlock(shell, hiddenAfter, lineEditorActive);
			}
		} catch (IOException error) {
			showShellShortcutStatus(
					shell, "Could not open turn details: " + error.getMessage(), lineEditorActive);
		}
	}

	private static void setShellHideThinkingBlock(
			InteractiveShell shell, boolean hidden, boolean lineEditorActive) {
		shell.hideThinkingBlock = hidden;
		shell.settings = withSettingsHideThinkingBlock(shell.settings, hidden);
		String status = "Thinking blocks: " + (hidden ? "hidden" : "visible");
		try {
			setSettingsHideThinkingBlock(shell.settingsStore, hidden);
		} catch (IOException error) {
			status += " (could not save: " + error.getMessage() + ")";
		}
		showShellShortcutStatus(shell, status, lineEditorActive);
	}

	private static void showShellShortcutStatus(
			InteractiveShell shell, String status, boolean lineEditorActive) {
		if (lineEditorActive) printAbove(shell.terminal, status);
		else println(shell.terminal, status);
	}

	private static void onShellAgentEvent(InteractiveShell shell, AgentEvent event) {
		switch (event) {
			case AgentEvent.AgentStart ignored -> setShellActivity(
					shell, activeActivity(ActivityStatus.Phase.WAITING_FOR_MODEL, System.nanoTime()));
			case AgentEvent.AgentEnd ignored -> setShellActivity(shell, readyActivity(System.nanoTime()));
			case AgentEvent.InstructionLoaded loaded -> {
				finishShellStreamOutput(shell);
				println(shell.terminal, instructionLoadedMessage(loaded.path));
			}
			case AgentEvent.CompactionStart ignored -> setShellActivity(
					shell, activeActivity(ActivityStatus.Phase.COMPACTING, System.nanoTime()));
			case AgentEvent.CompactionEnd end -> {
				persistShellCompaction(shell, end.result);
				if (shell.agent != null && shell.agent.state.isStreaming) {
					setShellActivity(
							shell, activeActivity(ActivityStatus.Phase.WAITING_FOR_MODEL, System.nanoTime()));
				} else {
					setShellActivity(shell, readyActivity(System.nanoTime()));
				}
				refreshShellStatus(shell);
			}
			case AgentEvent.TurnStart ignored -> setShellActivity(
					shell, activeActivity(ActivityStatus.Phase.WAITING_FOR_MODEL, System.nanoTime()));
			case AgentEvent.MessageUpdate update -> onShellMessageUpdate(shell, update.providerEvent);
			case AgentEvent.MessageEnd end -> {
				if (end.message instanceof AssistantMessage) {
					finishShellStreamOutput(shell);
					refreshShellStatus(shell);
				}
			}
			case AgentEvent.AutoRetryStart retry -> {
				setShellActivity(shell, retryingActivity(
						retry.attempt, retry.maxAttempts, retry.delayMs, System.nanoTime()));
				finishShellStreamOutput(shell);
				println(shell.terminal, "\nTransient provider error; retrying in "
						+ formatRetryDelay(retry.delayMs)
						+ " (" + retry.attempt + "/" + retry.maxAttempts + "): "
						+ retry.errorMessage);
			}
			case AgentEvent.ToolExecutionStart start -> {
				setShellActivity(shell, activeActivity(
						ActivityStatus.Phase.RUNNING_TOOL, start.toolName, System.nanoTime()));
				finishShellStreamOutput(shell);
				println(shell.terminal, "\n[" + start.toolName + "] "
						+ toolCallDescription(start.toolName, start.arguments));
			}
			case AgentEvent.ToolExecutionEnd end -> {
				String label = end.result.isError ? "Error" : "Done";
				println(shell.terminal, "  " + label + ": " + toolResultSummary(end.toolName, end.result));
			}
			default -> {
				// Turn-end and low-level update events do not change the presentation phase.
			}
		}
	}

	/** Saves every successful manual or automatic compaction as a resume boundary. */
	private static void persistShellCompaction(InteractiveShell shell, CompactionResult result) {
		if (shell.recorder == null) return;
		try {
			appendSessionCompaction(shell.recorder, result);
		} catch (IOException error) {
			// The live agent state has already been compacted. Keep the turn usable,
			// but make the loss of the resume boundary visible to the user.
			println(shell.terminal, "Warning: compacted context could not be saved for resume: " + error.getMessage());
		}
	}

	private static void onShellMessageUpdate(InteractiveShell shell, AssistantMessageEvent event) {
		updateShellStreamingActivity(shell, event);
		Theme theme = terminalTheme(shell.terminal);
		switch (event) {
			case AssistantMessageEvent.ThinkingStart ignored -> {
				if (!shell.hideThinkingBlock) {
					finishShellStreamOutput(shell);
					print(shell.terminal, "\n" + theme.muted + "Thinking:" + theme.reset + "\n");
					shell.streamOutput = InteractiveShell.StreamOutput.THINKING;
					shell.streamedThinkingCharacters = 0;
				}
			}
			case AssistantMessageEvent.ThinkingDelta delta -> {
				if (!shell.hideThinkingBlock) {
					if (shell.streamOutput != InteractiveShell.StreamOutput.THINKING) {
						print(shell.terminal, "\n" + theme.muted + "Thinking:" + theme.reset + "\n");
						shell.streamOutput = InteractiveShell.StreamOutput.THINKING;
						shell.streamedThinkingCharacters = 0;
					}
					print(shell.terminal, theme.muted + delta.delta + theme.reset);
					shell.streamedThinkingCharacters += delta.delta.length();
				}
			}
			case AssistantMessageEvent.ThinkingEnd end -> {
				if (!shell.hideThinkingBlock
						&& shell.streamOutput == InteractiveShell.StreamOutput.THINKING) {
					if (shell.streamedThinkingCharacters == 0 && !end.content.isBlank()) {
						print(shell.terminal, theme.muted + end.content + theme.reset);
					}
					finishShellStreamOutput(shell);
				}
			}
			case AssistantMessageEvent.TextStart ignored -> {
				finishShellStreamOutput(shell);
				shell.streamOutput = InteractiveShell.StreamOutput.TEXT;
			}
			case AssistantMessageEvent.TextDelta delta -> {
				if (shell.streamOutput != InteractiveShell.StreamOutput.TEXT) {
					finishShellStreamOutput(shell);
					shell.streamOutput = InteractiveShell.StreamOutput.TEXT;
				}
				print(shell.terminal, delta.delta);
				shell.emittedText = true;
			}
			case AssistantMessageEvent.TextEnd ignored -> finishShellStreamOutput(shell);
			default -> {
				// Tool-call argument streaming is rendered once execution starts.
			}
		}
	}

	private static void updateShellStreamingActivity(
			InteractiveShell shell, AssistantMessageEvent event) {
		long now = System.nanoTime();
		switch (event) {
			case AssistantMessageEvent.ThinkingStart ignored -> setShellActivity(
					shell, activeActivity(ActivityStatus.Phase.REASONING, now));
			case AssistantMessageEvent.TextStart ignored -> setShellActivity(
					shell, activeActivity(ActivityStatus.Phase.RESPONDING, now));
			case AssistantMessageEvent.ToolCallStart start -> setShellActivity(shell, activeActivity(
					ActivityStatus.Phase.PREPARING_TOOL,
					streamedToolName(start.contentIndex, start.partial),
					now));
			case AssistantMessageEvent.ToolCallEnd end -> setShellActivity(shell, activeActivity(
					ActivityStatus.Phase.PREPARING_TOOL, end.toolCall.name, now));
			default -> {
				// End events retain the current phase until another block or AgentEnd.
			}
		}
	}

	private static String streamedToolName(int contentIndex, AssistantMessage message) {
		if (contentIndex >= 0
				&& contentIndex < message.content.size()
				&& message.content.get(contentIndex) instanceof ToolCall call) {
			return call.name;
		}
		return "";
	}

	private static void finishShellStreamOutput(InteractiveShell shell) {
		if (shell.streamOutput != InteractiveShell.StreamOutput.NONE) {
			println(shell.terminal, "");
			shell.streamOutput = InteractiveShell.StreamOutput.NONE;
		}
	}

	/** One-line description of the work a tool call is about to perform. */
	public static String toolCallDescription(String toolName, ObjectNode arguments) {
		return switch (toolName) {
			case "read" -> {
				int offset = arguments.path("offset").asInt(1);
				int limit = arguments.path("limit").asInt();
				yield "Reading " + toolTextArgument(arguments, "path", ".")
						+ (limit > 0 ? " (lines " + offset + "-" + (offset + limit - 1) + ")" : " (from line " + offset + ")");
			}
			case "write" -> "Writing " + toolTextArgument(arguments, "path", ".") + " (" + toolTextArgument(arguments, "content", "").length() + " characters)";
			case "edit" -> "Editing " + toolTextArgument(arguments, "path", ".") + " (" + arguments.path("edits").size() + " replacement(s))";
			case "shell" -> abbreviateShellText(toolTextArgument(arguments, "command", ""), 240);
			case "grep" -> "Searching for " + toolTextArgument(arguments, "pattern", "") + " in " + toolTextArgument(arguments, "path", ".");
			case "find" -> "Finding " + toolTextArgument(arguments, "pattern", "") + " in " + toolTextArgument(arguments, "path", ".");
			case "ls" -> "Listing " + toolTextArgument(arguments, "path", ".");
			default -> abbreviateShellText(arguments.toString(), 240);
		};
	}

	/** One-line summary of a completed tool result. */
	public static String toolResultSummary(String toolName, AgentTool.ToolResult result) {
		return toolResultSummary(toolName, toolResultContentText(result.content), result.isError);
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

	private static String toolResultContentText(List<UserContent> content) {
		StringBuilder text = new StringBuilder();
		for (UserContent block : content) {
			if (block instanceof TextContent value) {
				if (!text.isEmpty()) {
					text.append('\n');
				}
				text.append(value.text);
			}
		}
		return text.toString();
	}

	private static String toolTextArgument(ObjectNode arguments, String name, String fallback) {
		JsonNode value = arguments.get(name);
		return value != null && value.isTextual() ? value.asText() : fallback;
	}

	private static String abbreviateShellText(String value, int maximumLength) {
		String normalized = value.replaceAll("\\s+", " ").trim();
		return normalized.length() <= maximumLength ? normalized : normalized.substring(0, maximumLength) + "...";
	}

	// ------------------------------------------------------------- rpc server

	/**
	 * Creates the JSONL automation server: resolves the initial model, connects
	 * MCP, and prepares the first agent. The MCP manager is closed if startup
	 * fails.
	 */
	public static RpcServer newRpcServer(CoreProviders providers, Cli arguments) throws IOException {
		RpcServer server = new RpcServer();
		server.providers = providers;
		server.arguments = arguments;
		Model initialModel = resolveRpcModel(providers, arguments.provider, arguments.model);
		server.mcp = mcpLoadDefaultManager(Path.of(".").toAbsolutePath().normalize());
		try {
			resetRpcAgent(server, initialModel);
		} catch (IOException | RuntimeException error) {
			mcpCloseManager(server.mcp);
			throw error;
		}
		return server;
	}

	/** Reads JSONL commands from stdin until end of stream. */
	public static int runRpcServer(RpcServer server) throws IOException {
		try (BufferedReader input =
				new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = input.readLine()) != null) {
				handleRpcCommand(server, line);
			}
		} finally {
			mcpCloseManager(server.mcp);
		}
		return 0;
	}

	private static void handleRpcCommand(RpcServer server, String line) {
		JsonNode parsed;
		try {
			parsed = Json.MAPPER.readTree(line);
		} catch (IOException e) {
			respondRpc(null, "parse", false, null, "Invalid JSON: " + e.getMessage());
			return;
		}
		if (!(parsed instanceof ObjectNode command) || !command.path("type").isTextual()) {
			respondRpc(null, "parse", false, null, "Command must be a JSON object with a string type");
			return;
		}
		String id = command.path("id").isTextual() ? command.path("id").asText() : null;
		String type = command.path("type").asText();
		try {
			switch (type) {
				case "prompt" -> promptRpc(server, id, command);
				case "abort" -> {
					abort(server.agent);
					respondRpc(id, type, true, null, null);
				}
				case "get_state" -> respondRpc(id, type, true, rpcState(server), null);
				case "get_available_models" -> {
					ObjectNode data = jsonObject();
					data.set("models", Json.MAPPER.valueToTree(allCatalogModels(server.providers.catalog)));
					respondRpc(id, type, true, data, null);
				}
				case "set_model" -> setRpcModel(server, id, command);
				case "compact" -> {
					String instructions = command.path("customInstructions").isTextual()
							? command.path("customInstructions").asText()
							: null;
					CompactionResult result = compact(server.agent, instructions);
					respondRpc(id, type, true, Json.MAPPER.valueToTree(result), null);
				}
				case "set_auto_compaction" -> {
					if (!command.path("enabled").isBoolean()) {
						throw new IllegalArgumentException("enabled must be a boolean");
					}
					server.agent.state.autoCompactionEnabled = command.path("enabled").asBoolean();
					respondRpc(id, type, true, null, null);
				}
				case "get_messages" -> {
					ObjectNode data = jsonObject();
					data.set("messages", Json.MAPPER.valueToTree(server.agent.state.messages));
					respondRpc(id, type, true, data, null);
				}
				case "get_last_assistant_text" -> {
					String assistantText = server.agent.state.messages.reversed().stream()
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
					resetRpcAgent(server, server.agent.state.model);
					respondRpc(id, type, true, jsonObject().put("cancelled", false), null);
				}
				default -> respondRpc(id, type, false, null, "Unsupported command: " + type);
			}
		} catch (Exception e) {
			respondRpc(id, type, false, null, e.getMessage() == null ? e.toString() : e.getMessage());
		}
	}

	private static void promptRpc(RpcServer server, String id, ObjectNode command)
			throws InterruptedException, IOException {
		JsonNode message = command.get("message");
		if (message == null || !message.isTextual() || message.asText().isBlank()) {
			throw new IllegalArgumentException("prompt requires a non-empty string message");
		}
		mcpAwaitReady(server.mcp);
		syncRpcMcpTools(server);
		List<Message> messages = prompt(server.agent, message.asText());
		if (server.recorder != null) appendSessionMessages(server.recorder, messages);
		respondRpc(id, "prompt", true, null, null);
	}

	private static void setRpcModel(RpcServer server, String id, ObjectNode command) throws IOException {
		String provider = requiredRpcText(command, "provider");
		String modelId = requiredRpcText(command, "modelId");
		Model model = requireCatalogModel(server.providers.catalog, provider, modelId);
		resetRpcAgent(server, model);
		respondRpc(id, "set_model", true, Json.MAPPER.valueToTree(model), null);
	}

	private static void resetRpcAgent(RpcServer server, Model model) throws IOException {
		Provider provider = requireCoreProvider(server.providers, model.provider);
		server.agent = newAgent(server.arguments.systemPrompt, model, provider);
		server.agent.apiKey = server.arguments.apiKey;
		configureBuiltInTools(server.agent, Path.of("."), server.arguments.systemPrompt);
		server.agent.state.tools.addAll(mcpTools(server.mcp));
		subscribe(server.agent, event -> onRpcAgentEvent(server, event));
		server.recorder = server.arguments.noSession
				? null
				: createSessionRecorder(defaultSessionStore(), Path.of("."), model.provider, model.id);
	}

	private static void syncRpcMcpTools(RpcServer server) {
		server.agent.state.tools.removeIf(McpAgentTool.class::isInstance);
		server.agent.state.tools.addAll(mcpTools(server.mcp));
	}

	private static Model resolveRpcModel(CoreProviders providers, String providerArg, String modelArg) {
		if (modelArg == null) {
			throw new IllegalArgumentException("--mode rpc requires --model <provider/model>");
		}
		if (modelArg.contains("/")) {
			String[] parts = modelArg.split("/", 2);
			if (providerArg != null && !providerArg.equals(parts[0])) {
				throw new IllegalArgumentException("--provider conflicts with the provider in --model");
			}
			return requireCatalogModel(providers.catalog, parts[0], parts[1]);
		}
		if (providerArg == null) {
			throw new IllegalArgumentException("--mode rpc requires --model <provider/model>");
		}
		return requireCatalogModel(providers.catalog, providerArg, modelArg);
	}

	private static ObjectNode rpcState(RpcServer server) {
		ObjectNode data = jsonObject();
		data.set("model", Json.MAPPER.valueToTree(server.agent.state.model));
		data.put("isStreaming", server.agent.state.isStreaming);
		data.put("isCompacting", server.agent.state.isCompacting);
		data.put("autoCompactionEnabled", server.agent.state.autoCompactionEnabled);
		data.put("messageCount", server.agent.state.messages.size());
		data.put("sessionId", server.recorder == null ? "" : server.recorder.sessionId);
		return data;
	}

	private static void onRpcAgentEvent(RpcServer server, AgentEvent event) {
		ObjectNode node = jsonObject();
		switch (event) {
			case AgentEvent.AgentStart ignored -> node.put("type", "agent_start");
			case AgentEvent.AgentEnd end -> {
				node.put("type", "agent_settled");
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
				persistRpcCompaction(server, end.result);
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
				if (update.providerEvent instanceof AssistantMessageEvent.TextDelta delta) {
					node.put("type", "message_update");
					node.putObject("assistantMessageEvent").put("type", "text_delta").put("delta", delta.delta);
				} else return;
			}
			case AgentEvent.ToolExecutionStart start -> {
				node.put("type", "tool_execution_start");
				node.put("toolCallId", start.toolCallId);
				node.put("toolName", start.toolName);
				node.set("arguments", start.arguments);
			}
			case AgentEvent.ToolExecutionUpdate ignored -> {
				return;
			}
			case AgentEvent.ToolExecutionEnd end -> {
				node.put("type", "tool_execution_end");
				node.put("toolCallId", end.toolCallId);
				node.put("toolName", end.toolName);
				node.put("isError", end.result.isError);
			}
		}
		outputRpc(node);
	}

	/** Saves every successful manual or automatic compaction as a resume boundary. */
	private static void persistRpcCompaction(RpcServer server, CompactionResult result) {
		if (server.recorder == null) return;
		try {
			appendSessionCompaction(server.recorder, result);
		} catch (IOException error) {
			// Never contaminate the JSONL protocol on stdout. The live state remains
			// valid even if its append-only resume marker could not be written.
			System.err.println("Warning: compacted context could not be saved for resume: " + error.getMessage());
		}
	}

	private static void respondRpc(
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

	/**
	 * Creates the MCP server/tool selector. Every applied change is passed to
	 * {@code onChange}, which reports a failed save with UncheckedIOException.
	 */
	public static McpSelector newMcpSelector(McpManager manager, Consumer<McpSelector.Change> onChange) {
		McpSelector selector = new McpSelector();
		selector.manager = manager;
		selector.onChange = onChange;
		selector.names = mcpStatuses(manager).stream().map(status -> status.name).toList();
		selector.filtered = selector.names;
		return selector;
	}

	/** Binds a selector carrier to the full-screen host. */
	public static TuiComponent<Void> mcpSelectorComponent(McpSelector selector) {
		return new TuiComponent<>(
				frame -> renderMcpSelector(selector, frame.width, frame.height, frame.theme),
				input -> handleMcpSelectorInput(selector, input),
				() -> selector.complete,
				() -> null);
	}

	public static List<String> renderMcpSelector(McpSelector selector, int width, int height, Theme theme) {
		if (selector.view == McpSelector.View.TOOLS) refreshMcpSelectorTools(selector);

		List<String> lines = new ArrayList<>();
		String title = selector.view == McpSelector.View.SERVERS
				? "MCP Servers"
				: "MCP Tools: " + selector.toolServer;
		lines.add(theme.heading + truncatePlain(title, width) + theme.reset);
		lines.add("");
		String before = selector.query.substring(0, selector.queryCursor);
		String after = selector.query.substring(selector.queryCursor);
		lines.add(truncatePlain("Search: " + before + "|" + after, width));
		lines.add("");
		selector.optionStartRow = lines.size();
		selector.visibleCount = Math.max(1, Math.min(10, height - 9));
		int itemCount = mcpSelectorItemCount(selector);
		selector.visibleStart = Math.max(0, Math.min(
				selector.selectedIndex - selector.visibleCount / 2,
				Math.max(0, itemCount - selector.visibleCount)));
		int end = Math.min(itemCount, selector.visibleStart + selector.visibleCount);
		if (itemCount == 0) {
			String empty = selector.view == McpSelector.View.SERVERS
					? "  No matching servers"
					: "  No tools available";
			lines.add(theme.muted + empty + theme.reset);
		} else {
			for (int index = selector.visibleStart; index < end; index++) {
				String row = (index == selector.selectedIndex ? "> " : "  ")
						+ mcpSelectorStatusLine(selector, index);
				row = truncatePlain(row, width);
				lines.add(index == selector.selectedIndex ? theme.heading + row + theme.reset : row);
			}
			if (selector.visibleStart > 0 || end < itemCount) {
				lines.add(theme.muted + "  " + (selector.selectedIndex + 1) + "/" + itemCount + theme.reset);
			}
		}
		lines.add("");
		String detail = selector.changeError == null ? mcpSelectorDetail(selector) : selector.changeError;
		if (detail != null) {
			String style = selector.changeError == null ? theme.muted : warningStatus(theme);
			lines.add(style + truncatePlain("  " + detail, width) + theme.reset);
		}
		if (selector.view == McpSelector.View.SERVERS && !selector.filtered.isEmpty()) {
			McpManager.ServerStatus selected =
					mcpStatus(selector.manager, selector.filtered.get(selector.selectedIndex));
			if (selected.authorizationUrl != null) {
				String label = truncatePlain("Open: " + selected.authorizationUrl, Math.max(1, width - 2));
				String link = hyperlink(label, selected.authorizationUrl);
				lines.add(theme.muted + "  " + link + theme.reset);
			}
		}
		String hint = selector.view == McpSelector.View.SERVERS
				? "Type to filter  Up/Down move  Enter toggle/auth/retry  Tab tools  Esc close"
				: "Type to filter  Up/Down move  Enter toggle  Tab/Esc servers";
		lines.add(theme.muted + truncatePlain(hint, width) + theme.reset);
		return lines;
	}

	private static int mcpSelectorItemCount(McpSelector selector) {
		return selector.view == McpSelector.View.SERVERS
				? selector.filtered.size()
				: selector.filteredTools.size();
	}

	private static String mcpSelectorStatusLine(McpSelector selector, int index) {
		return selector.view == McpSelector.View.SERVERS
				? mcpServerStatusLine(mcpStatus(selector.manager, selector.filtered.get(index)))
				: mcpToolStatusLine(selector.filteredTools.get(index));
	}

	private static String mcpServerStatusLine(McpManager.ServerStatus status) {
		return switch (status.state) {
			case CONNECTING -> "⋯ " + status.name + "  Connecting";
			case AUTHENTICATING -> "⋯ " + status.name + "  Waiting for OAuth";
			case AUTH_REQUIRED -> "! " + status.name + "  Authentication required";
			case CONNECTED -> "✓ " + status.name + "  Enabled · " + mcpServerToolCount(status);
			case DISABLED -> "○ " + status.name + "  Disabled";
			case FAILED -> "✗ " + status.name + "  Failed";
		};
	}

	private static String mcpServerToolCount(McpManager.ServerStatus status) {
		if (status.enabledToolCount == status.toolCount) return status.toolCount + " tool(s)";
		return status.enabledToolCount + "/" + status.toolCount + " tool(s)";
	}

	private static String mcpToolStatusLine(McpManager.ToolStatus status) {
		return (status.enabled ? "✓ " : "○ ") + status.name + "  " + (status.enabled ? "Enabled" : "Disabled");
	}

	private static String mcpSelectorDetail(McpSelector selector) {
		if (selector.view == McpSelector.View.SERVERS) {
			if (selector.filtered.isEmpty()) return null;
			McpManager.ServerStatus selected =
					mcpStatus(selector.manager, selector.filtered.get(selector.selectedIndex));
			return selected.message == null ? selected.target : selected.message;
		}
		if (selector.filteredTools.isEmpty()) return null;
		String description = selector.filteredTools.get(selector.selectedIndex).description;
		return description.isBlank() ? "No description" : description;
	}

	public static void handleMcpSelectorInput(McpSelector selector, TuiInput input) {
		switch (input) {
			case TuiInput.Key key -> handleMcpSelectorKey(selector, key);
			case TuiInput.Mouse mouse -> handleMcpSelectorMouse(selector, mouse);
			case TuiInput.Resize ignored -> {}
		}
	}

	private static void handleMcpSelectorKey(McpSelector selector, TuiInput.Key key) {
		switch (key.type) {
			case UP -> moveMcpSelector(selector, -1);
			case DOWN -> moveMcpSelector(selector, 1);
			case PAGE_UP -> moveMcpSelector(selector, -Math.max(1, selector.visibleCount));
			case PAGE_DOWN -> moveMcpSelector(selector, Math.max(1, selector.visibleCount));
			case ENTER -> toggleMcpSelectorItem(selector);
			case TAB -> toggleMcpSelectorView(selector);
			case ESCAPE, CANCEL -> {
				if (selector.view == McpSelector.View.TOOLS) closeMcpSelectorTools(selector);
				else selector.complete = true;
			}
			case CHARACTER, PASTE -> insertMcpSelectorQuery(selector, key.text);
			case BACKSPACE -> backspaceMcpSelectorQuery(selector);
			case DELETE -> deleteMcpSelectorQuery(selector);
			case LEFT -> selector.queryCursor = Math.max(0, selector.queryCursor - 1);
			case RIGHT -> selector.queryCursor = Math.min(selector.query.length(), selector.queryCursor + 1);
			case HOME -> selector.queryCursor = 0;
			case END -> selector.queryCursor = selector.query.length();
			case CLEAR -> {
				selector.query.setLength(0);
				selector.queryCursor = 0;
				filterMcpSelector(selector);
			}
			default -> {}
		}
	}

	private static void handleMcpSelectorMouse(McpSelector selector, TuiInput.Mouse mouse) {
		switch (mouse.action) {
			case SCROLL_UP -> moveMcpSelector(selector, -1);
			case SCROLL_DOWN -> moveMcpSelector(selector, 1);
			case PRESS -> {
				int offset = mouse.y - 1 - selector.optionStartRow;
				int index = selector.visibleStart + offset;
				if (mouse.button == 0
						&& offset >= 0
						&& offset < selector.visibleCount
						&& index < mcpSelectorItemCount(selector)) {
					selector.selectedIndex = index;
				}
			}
			default -> {}
		}
	}

	private static void toggleMcpSelectorItem(McpSelector selector) {
		if (selector.view == McpSelector.View.SERVERS) {
			if (selector.filtered.isEmpty()) return;
			McpManager.ServerStatus status = mcpToggleServerAsync(
					selector.manager, selector.filtered.get(selector.selectedIndex));
			notifyMcpSelectorChange(selector, new McpSelector.Change(
					status.name, null, mcpIsEnabled(selector.manager, status.name)));
		} else {
			toggleMcpSelectorTool(selector);
		}
	}

	private static void toggleMcpSelectorTool(McpSelector selector) {
		refreshMcpSelectorTools(selector);
		if (selector.filteredTools.isEmpty()) return;
		try {
			McpManager.ToolStatus status = mcpToggleTool(
					selector.manager,
					selector.toolServer,
					selector.filteredTools.get(selector.selectedIndex).name);
			notifyMcpSelectorChange(
					selector, new McpSelector.Change(status.serverName, status.name, status.enabled));
			refreshMcpSelectorTools(selector);
		} catch (IllegalStateException | IllegalArgumentException ignored) {
			// The server or its catalog may have changed while this selector was open.
			refreshMcpSelectorTools(selector);
		}
	}

	private static void notifyMcpSelectorChange(McpSelector selector, McpSelector.Change change) {
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

	private static void toggleMcpSelectorView(McpSelector selector) {
		if (selector.view == McpSelector.View.SERVERS) openMcpSelectorTools(selector);
		else closeMcpSelectorTools(selector);
	}

	private static void openMcpSelectorTools(McpSelector selector) {
		if (selector.view == McpSelector.View.TOOLS || selector.filtered.isEmpty()) return;
		String server = selector.filtered.get(selector.selectedIndex);
		if (mcpStatus(selector.manager, server).state != McpManager.State.CONNECTED) return;
		selector.view = McpSelector.View.TOOLS;
		selector.toolServer = server;
		clearMcpSelectorQuery(selector);
		refreshMcpSelectorTools(selector);
	}

	private static void closeMcpSelectorTools(McpSelector selector) {
		if (selector.view != McpSelector.View.TOOLS) return;
		String server = selector.toolServer;
		selector.view = McpSelector.View.SERVERS;
		selector.toolServer = null;
		clearMcpSelectorQuery(selector);
		filterMcpSelector(selector);
		int index = selector.filtered.indexOf(server);
		if (index >= 0) selector.selectedIndex = index;
	}

	private static void moveMcpSelector(McpSelector selector, int delta) {
		int itemCount = mcpSelectorItemCount(selector);
		if (itemCount > 0) selector.selectedIndex = Math.floorMod(selector.selectedIndex + delta, itemCount);
	}

	private static void insertMcpSelectorQuery(McpSelector selector, String text) {
		if (text == null || text.isEmpty()) return;
		String normalized = text.replace('\r', ' ').replace('\n', ' ');
		selector.query.insert(selector.queryCursor, normalized);
		selector.queryCursor += normalized.length();
		filterMcpSelector(selector);
	}

	private static void backspaceMcpSelectorQuery(McpSelector selector) {
		if (selector.queryCursor == 0) return;
		int start = selector.query.offsetByCodePoints(selector.queryCursor, -1);
		selector.query.delete(start, selector.queryCursor);
		selector.queryCursor = start;
		filterMcpSelector(selector);
	}

	private static void deleteMcpSelectorQuery(McpSelector selector) {
		if (selector.queryCursor >= selector.query.length()) return;
		int end = selector.query.offsetByCodePoints(selector.queryCursor, 1);
		selector.query.delete(selector.queryCursor, end);
		filterMcpSelector(selector);
	}

	private static void clearMcpSelectorQuery(McpSelector selector) {
		selector.query.setLength(0);
		selector.queryCursor = 0;
	}

	private static void filterMcpSelector(McpSelector selector) {
		if (selector.view == McpSelector.View.SERVERS) {
			selector.filtered = fuzzyFilter(selector.names, selector.query.toString(), name -> {
				McpManager.ServerStatus status = mcpStatus(selector.manager, name);
				return name + " " + status.state + " " + status.target;
			});
		} else {
			refreshMcpSelectorTools(selector);
		}
		selector.selectedIndex = 0;
	}

	private static void refreshMcpSelectorTools(McpSelector selector) {
		if (selector.toolServer == null) {
			selector.filteredTools = List.of();
			return;
		}
		List<McpManager.ToolStatus> tools = mcpToolStatuses(selector.manager, selector.toolServer);
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
					sections.add(new TurnDetailsComponent.Section(
							TurnDetailsComponent.Kind.THINKING,
							"Thinking " + thinkingNumber,
							body,
							turnDetailsFirstLine(body),
							!thinkingHidden));
				} else if (content instanceof ToolCall call) {
					ToolResultMessage result = results.get(call.id);
					String description =
							turnDetailsSingleLine(toolCallDescription(call.name, call.arguments));
					String title = call.name + (description.isBlank() ? "" : "  " + description);
					StringBuilder body = new StringBuilder("Arguments\n").append(call.arguments.toPrettyString());
					String summary = result == null
							? "pending"
							: result.isError ? "error" : turnDetailsResultSummary(text(result));
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

	/** Binds a turn-details carrier to the full-screen host. */
	public static TuiComponent<Boolean> turnDetailsComponent(TurnDetailsComponent details) {
		return new TuiComponent<>(
				frame -> renderTurnDetails(details, frame.width, frame.height, frame.theme),
				input -> handleTurnDetailsInput(details, input),
				() -> details.complete,
				() -> details.thinkingHidden);
	}

	public static List<String> renderTurnDetails(
			TurnDetailsComponent details, int width, int height, Theme theme) {
		int safeWidth = Math.max(20, width);
		details.viewportHeight = Math.max(
				1, height - TurnDetailsComponent.HEADER_LINES - TurnDetailsComponent.FOOTER_LINES);
		List<TurnDetailsComponent.RenderedLine> content = renderTurnDetailsContent(details, safeWidth, theme);
		int selectedRow = turnDetailsHeadingRow(content, details.selectedIndex);
		if (details.keepSelectionVisible) {
			if (selectedRow < details.scrollTop) {
				details.scrollTop = selectedRow;
			} else if (selectedRow >= details.scrollTop + details.viewportHeight) {
				details.scrollTop = selectedRow - details.viewportHeight + 1;
			}
		}
		details.scrollTop = Math.max(
				0, Math.min(details.scrollTop, Math.max(0, content.size() - details.viewportHeight)));

		List<String> lines = new ArrayList<>();
		lines.add(theme.heading + "Turn details" + theme.reset);
		lines.add("");
		details.visibleSectionsByRow.clear();
		int visibleEnd = Math.min(content.size(), details.scrollTop + details.viewportHeight);
		for (int index = details.scrollTop; index < visibleEnd; index++) {
			TurnDetailsComponent.RenderedLine line = content.get(index);
			if (line.heading) details.visibleSectionsByRow.put(lines.size(), line.sectionIndex);
			lines.add(line.text);
		}
		String hint = "Up/Down select  Enter expand/collapse  PgUp/PgDn scroll  Ctrl-T thinking  Ctrl-O tools  Esc close";
		lines.add(theme.muted + truncatePlain(hint, safeWidth) + theme.reset);
		return lines;
	}

	public static void handleTurnDetailsInput(TurnDetailsComponent details, TuiInput input) {
		switch (input) {
			case TuiInput.Key key -> handleTurnDetailsKey(details, key);
			case TuiInput.Mouse mouse -> handleTurnDetailsMouse(details, mouse);
			case TuiInput.Resize ignored -> {
				// Rendering uses the current dimensions directly.
			}
		}
	}

	private static List<TurnDetailsComponent.RenderedLine> renderTurnDetailsContent(
			TurnDetailsComponent details, int width, Theme theme) {
		List<TurnDetailsComponent.RenderedLine> lines = new ArrayList<>();
		for (int index = 0; index < details.sections.size(); index++) {
			TurnDetailsComponent.Section section = details.sections.get(index);
			String marker = section.expanded ? "▼ " : "▶ ";
			String suffix = section.expanded || section.summary.isBlank() ? "" : " — " + section.summary;
			String heading = truncatePlain(marker + section.title + suffix, width);
			if (index == details.selectedIndex) heading = theme.heading + heading + theme.reset;
			else heading = theme.strong + heading + theme.reset;
			lines.add(new TurnDetailsComponent.RenderedLine(heading, index, true));
			if (section.expanded) {
				for (String bodyLine : wrapPlain(section.body, Math.max(1, width - 3))) {
					String rendered = "   " + bodyLine;
					if (section.kind == TurnDetailsComponent.Kind.THINKING) {
						rendered = theme.muted + rendered + theme.reset;
					}
					lines.add(new TurnDetailsComponent.RenderedLine(rendered, index, false));
				}
			}
			if (index + 1 < details.sections.size()) {
				lines.add(new TurnDetailsComponent.RenderedLine("", index, false));
			}
		}
		return lines;
	}

	private static void handleTurnDetailsKey(TurnDetailsComponent details, TuiInput.Key key) {
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

	private static void handleTurnDetailsMouse(TurnDetailsComponent details, TuiInput.Mouse mouse) {
		switch (mouse.action) {
			case SCROLL_UP -> scrollTurnDetails(details, -3);
			case SCROLL_DOWN -> scrollTurnDetails(details, 3);
			case PRESS -> {
				if (mouse.button != 0) return;
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

	private static void moveTurnDetails(TurnDetailsComponent details, int delta) {
		details.selectedIndex = Math.floorMod(details.selectedIndex + delta, details.sections.size());
		details.keepSelectionVisible = true;
	}

	private static void scrollTurnDetails(TurnDetailsComponent details, int delta) {
		details.scrollTop = Math.max(0, details.scrollTop + delta);
		details.keepSelectionVisible = false;
	}

	private static void toggleSelectedTurnDetail(TurnDetailsComponent details) {
		TurnDetailsComponent.Section section = details.sections.get(details.selectedIndex);
		section.expanded = !section.expanded;
		details.keepSelectionVisible = true;
	}

	private static void toggleTurnDetailsKind(
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

	private static int turnDetailsHeadingRow(
			List<TurnDetailsComponent.RenderedLine> lines, int sectionIndex) {
		for (int index = 0; index < lines.size(); index++) {
			TurnDetailsComponent.RenderedLine line = lines.get(index);
			if (line.heading && line.sectionIndex == sectionIndex) return index;
		}
		return 0;
	}

	private static String turnDetailsResultSummary(String text) {
		if (text == null || text.isBlank()) return "done";
		long lines = text.lines().count();
		return lines == 1 ? "done" : lines + " lines";
	}

	private static String turnDetailsFirstLine(String text) {
		if (text == null || text.isBlank()) return "";
		String line = text.lines().filter(value -> !value.isBlank()).findFirst().orElse("").strip();
		return line.length() <= 100 ? line : line.substring(0, 100) + "...";
	}

	private static String turnDetailsSingleLine(String value) {
		return turnDetailsSafePlain(value).replaceAll("\\s+", " ").strip();
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

	/** JSON representation shared by session recording and restoration. */
	public static ObjectNode encodeSessionMessage(Message message) {
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
				encodeSessionUsage(node.putObject("usage"), assistant);
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
		return node;
	}

	public static Message decodeSessionMessage(JsonNode node) throws IOException {
		if (node == null || !node.isObject() || !node.path("role").isTextual()) {
			throw new IOException("Message payload must be an object with a string role");
		}
		long timestamp = node.path("timestamp").isIntegralNumber()
				? node.path("timestamp").asLong()
				: System.currentTimeMillis();
		return switch (node.path("role").asText()) {
			case "user" -> userMessage(decodeSessionUserContent(node.get("content")), timestamp);
			case "assistant" -> decodeSessionAssistant(node, timestamp);
			case "toolResult" -> toolResultMessage(
					requiredSessionText(node, "toolCallId"),
					requiredSessionText(node, "toolName"),
					decodeSessionUserContent(node.get("content")),
					node.has("details") ? Json.MAPPER.treeToValue(node.get("details"), Object.class) : null,
					node.path("isError").asBoolean(false),
					timestamp);
			default -> throw new IOException("Unknown session message role: " + node.path("role").asText());
		};
	}

	private static AssistantMessage decodeSessionAssistant(JsonNode node, long timestamp) throws IOException {
		AssistantMessage assistant = new AssistantMessage(
				optionalSessionText(node, "api"),
				optionalSessionText(node, "provider"),
				optionalSessionText(node, "model"));
		assistant.timestamp = timestamp;
		assistant.responseModel = optionalSessionText(node, "responseModel");
		assistant.responseId = optionalSessionText(node, "responseId");
		assistant.errorMessage = optionalSessionText(node, "error");
		assistant.rawStopReason = optionalSessionText(node, "rawStopReason");
		String stopReason = optionalSessionText(node, "stopReason");
		if (stopReason != null) {
			try {
				assistant.stopReason = stopReasonFromWire(stopReason);
			} catch (IllegalArgumentException error) {
				try {
					assistant.stopReason = StopReason.valueOf(stopReason.toUpperCase(Locale.ROOT));
				} catch (IllegalArgumentException ignored) {
					throw new IOException("Unknown assistant stop reason: " + stopReason, error);
				}
			}
		}
		decodeSessionUsage(node.get("usage"), assistant);
		JsonNode content = node.get("content");
		if (content != null && !content.isArray()) {
			throw new IOException("Assistant message content must be an array");
		}
		if (content != null) {
			for (JsonNode block : content) {
				String type = requiredSessionText(block, "type");
				switch (type) {
					case "text" -> assistant.content.add(textContent(
							requiredSessionText(block, "text"), optionalSessionText(block, "textSignature")));
					case "thinking" -> assistant.content.add(thinkingContent(
							requiredSessionText(block, "text"),
							optionalSessionText(block, "thinkingSignature"),
							block.path("redacted").asBoolean(false)));
					case "toolCall" -> {
						JsonNode arguments = block.get("arguments");
						if (!(arguments instanceof ObjectNode object)) {
							throw new IOException("Tool call arguments must be an object");
						}
						assistant.content.add(toolCall(
								requiredSessionText(block, "id"),
								requiredSessionText(block, "name"),
								object.deepCopy(),
								optionalSessionText(block, "thoughtSignature")));
					}
					default -> throw new IOException("Unknown assistant content type: " + type);
				}
			}
		}
		return assistant;
	}

	private static void encodeSessionUserContent(ArrayNode target, List<UserContent> source) {
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
				case "text" -> decoded.add(textContent(
						requiredSessionText(block, "text"), optionalSessionText(block, "textSignature")));
				case "image" -> decoded.add(imageContent(
						requiredSessionText(block, "data"), requiredSessionText(block, "mimeType")));
				default -> throw new IOException("Unknown user content type: " + type);
			}
		}
		return List.copyOf(decoded);
	}

	private static void encodeSessionUsage(ObjectNode node, AssistantMessage assistant) {
		node.put("input", assistant.usage.input);
		node.put("output", assistant.usage.output);
		node.put("cacheRead", assistant.usage.cacheRead);
		node.put("cacheWrite", assistant.usage.cacheWrite);
		if (assistant.usage.cacheWrite1h != null) node.put("cacheWrite1h", assistant.usage.cacheWrite1h);
		if (assistant.usage.reasoning != null) node.put("reasoning", assistant.usage.reasoning);
		node.put("totalTokens", assistant.usage.totalTokens);
		ObjectNode cost = node.putObject("cost");
		cost.put("input", assistant.usage.cost.input);
		cost.put("output", assistant.usage.cost.output);
		cost.put("cacheRead", assistant.usage.cost.cacheRead);
		cost.put("cacheWrite", assistant.usage.cost.cacheWrite);
		cost.put("total", assistant.usage.cost.total);
	}

	private static void decodeSessionUsage(JsonNode node, AssistantMessage assistant) {
		if (node == null || !node.isObject()) return;
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

	private static void putNullableSessionText(ObjectNode node, String field, String value) {
		if (value != null) node.put(field, value);
	}

	// --------------------------------------------------------- session recorder

	public static SessionRecorder createSessionRecorder(
			SessionStore store, Path cwd, String provider, String model) throws IOException {
		return createSessionRecorder(store, cwd, provider, model, null);
	}

	/** Creates a session with an optional user-visible name. */
	public static SessionRecorder createSessionRecorder(
			SessionStore store, Path cwd, String provider, String model, String sessionName) throws IOException {
		String id = createSession(store);
		ObjectNode start = jsonObject();
		start.put("cwd", cwd.toAbsolutePath().normalize().toString());
		start.put("provider", provider);
		start.put("model", model);
		String normalizedName = sessionName == null ? null : sessionName.strip();
		if (normalizedName != null && !normalizedName.isEmpty()) start.put("name", normalizedName);
		appendSessionEntry(store, id, "session_start", start);
		return new SessionRecorder(store, id);
	}

	/** Creates a named child session containing a copy of the supplied conversation. */
	public static SessionRecorder forkSessionRecorder(
			SessionStore store,
			Path cwd,
			String provider,
			String model,
			String sessionName,
			List<Message> messages)
			throws IOException {
		SessionRecorder fork = createSessionRecorder(store, cwd, provider, model, sessionName);
		appendSessionMessages(fork, messages);
		return fork;
	}

	/** Opens an existing session so future messages continue in the same JSONL file. */
	public static SessionRecorder resumeSessionRecorder(SessionStore store, String sessionId) throws IOException {
		sessionSnapshot(store, sessionId);
		return new SessionRecorder(store, sessionId);
	}

	/** Appends finished agent messages in chronological order. */
	public static void appendSessionMessages(SessionRecorder recorder, List<Message> messages) throws IOException {
		for (Message message : messages) {
			appendSessionEntry(recorder.store, recorder.sessionId, "message", encodeSessionMessage(message));
		}
	}

	/**
	 * Appends a compaction boundary without rewriting the prior transcript. On
	 * resume, the latest boundary rebuilds the active model context from that
	 * checkpoint and later messages only.
	 */
	public static void appendSessionCompaction(SessionRecorder recorder, CompactionResult result) throws IOException {
		Objects.requireNonNull(result, "result");
		if (result.summary == null || result.summary.isBlank()) {
			throw new IllegalArgumentException("Compaction summary must not be blank");
		}
		ObjectNode checkpoint = jsonObject();
		checkpoint.put("summary", result.summary);
		checkpoint.put("tokensBefore", result.tokensBefore);
		checkpoint.put("estimatedTokensAfter", result.estimatedTokensAfter);
		appendSessionEntry(recorder.store, recorder.sessionId, "compaction", checkpoint);
	}

	// ------------------------------------------------------------ session store

	/** Resolves the session directory, dropping legacy directories that duplicate it. */
	public static SessionStore sessionStore(Path directory) {
		return sessionStore(directory, List.of());
	}

	public static SessionStore sessionStore(Path directory, List<Path> legacyDirectories) {
		Path resolved = directory.toAbsolutePath().normalize();
		return new SessionStore(
				resolved,
				legacyDirectories.stream()
						.map(path -> path.toAbsolutePath().normalize())
						.filter(path -> !path.equals(resolved))
						.toList());
	}

	public static SessionStore defaultSessionStore() {
		return defaultSessionStore(Path.of(System.getProperty("user.home")));
	}

	/** Keeps sessions written before the application-data directory was renamed resumable. */
	public static SessionStore defaultSessionStore(Path home) {
		return sessionStore(
				home.resolve(".codingagent").resolve("sessions"),
				List.of(home.resolve(".pi-java").resolve("sessions")));
	}

	/** Creates an empty JSONL session and returns its time-sortable UUIDv7 id. */
	public static String createSession(SessionStore store) throws IOException {
		Files.createDirectories(store.directory);
		setPosixPermissions(store.directory, SessionStore.DIRECTORY_PERMISSIONS);
		String id = uuidv7();
		Path file = store.directory.resolve(id + ".jsonl");
		Files.createFile(file);
		setPosixPermissions(file, SessionStore.FILE_PERMISSIONS);
		return id;
	}

	/** Appends a typed payload to an existing session. */
	public static void appendSessionEntry(SessionStore store, String sessionId, String type, JsonNode payload)
			throws IOException {
		validateSessionId(sessionId);
		if (type == null || type.isBlank()) {
			throw new IllegalArgumentException("Session entry type must not be blank");
		}
		if (payload == null) {
			throw new IllegalArgumentException("Session entry payload must not be null");
		}
		Path file = existingSessionPath(store, sessionId);
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

	/** Reads and validates all complete entries in file order. */
	public static List<SessionStore.Entry> readSession(SessionStore store, String sessionId) throws IOException {
		validateSessionId(sessionId);
		Path file = existingSessionPath(store, sessionId);
		if (file == null) {
			throw new IOException("Unknown session: " + sessionId);
		}
		List<SessionStore.Entry> entries = new ArrayList<>();
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
				entries.add(new SessionStore.Entry(
						node.path("timestamp").asLong(), node.path("type").asText(), node.path("payload")));
			}
		}
		return List.copyOf(entries);
	}

	/** Lists session ids newest first. */
	public static List<String> listSessions(SessionStore store) throws IOException {
		return sessionFiles(store).stream()
				.map(path -> sessionIdFor(path.getFileName()))
				.sorted(Comparator.reverseOrder())
				.toList();
	}

	/** Lists resumable sessions newest first, optionally limited to one working directory. */
	public static List<SessionSnapshot> listSessionSnapshots(SessionStore store, Path cwd) throws IOException {
		Path normalizedCwd = cwd == null ? null : cwd.toAbsolutePath().normalize();
		List<SessionSnapshot> snapshots = new ArrayList<>();
		for (Path file : sessionFiles(store)) {
			try {
				SessionSnapshot snapshot = sessionSnapshot(store, sessionIdFor(file.getFileName()));
				if (normalizedCwd == null || sameSessionCwd(snapshot.cwd, normalizedCwd)) {
					snapshots.add(snapshot);
				}
			} catch (IOException | IllegalArgumentException ignored) {
				// Discovery is best effort: one corrupt session must not hide the rest.
			}
		}
		snapshots.sort(Comparator.comparing((SessionSnapshot snapshot) -> snapshot.modified).reversed());
		return List.copyOf(snapshots);
	}

	/** Loads metadata, the complete transcript, and compaction-aware continuation context. */
	public static SessionSnapshot sessionSnapshot(SessionStore store, String sessionId) throws IOException {
		List<SessionStore.Entry> entries = readSession(store, sessionId);
		if (entries.isEmpty() || !entries.getFirst().type.equals("session_start")) {
			throw new IOException("Session has no session_start entry: " + sessionId);
		}
		SessionStore.Entry start = entries.getFirst();
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
		for (SessionStore.Entry entry : entries) {
			modified = Math.max(modified, entry.timestamp);
			if (entry.type.equals("compaction")) {
				messages.clear();
				messages.add(sessionCompactionCheckpoint(entry.payload, entry.timestamp, sessionId));
				continue;
			}
			if (!entry.type.equals("message")) continue;
			Message message;
			try {
				message = decodeSessionMessage(entry.payload);
			} catch (IOException | RuntimeException error) {
				throw new IOException("Invalid message in session " + sessionId, error);
			}
			transcriptMessages.add(message);
			messages.add(message);
			String text = sessionMessageText(message);
			if (!text.isBlank()) {
				if (!allMessages.isEmpty()) allMessages.append(' ');
				allMessages.append(text);
				if (firstMessage.isEmpty() && message instanceof UserMessage) firstMessage = text;
			}
		}
		Path file = existingSessionPath(store, sessionId);
		if (file == null) throw new IOException("Unknown session: " + sessionId);
		Path sessionCwd;
		try {
			sessionCwd = Path.of(cwdText).toAbsolutePath().normalize();
		} catch (RuntimeException error) {
			throw new IOException("Session " + sessionId + " has an invalid cwd", error);
		}
		return newSessionSnapshot(
				sessionId,
				name,
				file,
				sessionCwd,
				provider,
				model,
				Instant.ofEpochMilli(start.timestamp),
				Instant.ofEpochMilli(modified),
				transcriptMessages.size(),
				firstMessage.isEmpty() ? "(no messages)" : firstMessage,
				allMessages.toString(),
				messages,
				transcriptMessages);
	}

	/**
	 * Creates a session snapshot. Every field except the display name is
	 * required; a blank name normalizes to null and both message lists are
	 * copied.
	 */
	public static SessionSnapshot newSessionSnapshot(
			String id,
			String name,
			Path path,
			Path cwd,
			String provider,
			String model,
			Instant created,
			Instant modified,
			int messageCount,
			String firstMessage,
			String allMessagesText,
			List<Message> messages,
			List<Message> transcriptMessages) {
		return new SessionSnapshot(
				Objects.requireNonNull(id, "id"),
				normalizeSessionName(name),
				Objects.requireNonNull(path, "path"),
				Objects.requireNonNull(cwd, "cwd"),
				Objects.requireNonNull(provider, "provider"),
				Objects.requireNonNull(model, "model"),
				Objects.requireNonNull(created, "created"),
				Objects.requireNonNull(modified, "modified"),
				messageCount,
				Objects.requireNonNull(firstMessage, "firstMessage"),
				Objects.requireNonNull(allMessagesText, "allMessagesText"),
				List.copyOf(messages),
				List.copyOf(transcriptMessages));
	}

	/** Strips a session display name, treating blank names as absent. */
	public static String normalizeSessionName(String name) {
		String normalized = name == null ? null : name.strip();
		return normalized == null || normalized.isEmpty() ? null : normalized;
	}

	private static UserMessage sessionCompactionCheckpoint(JsonNode payload, long timestamp, String sessionId)
			throws IOException {
		String summary = requiredSessionPayloadText(payload, "summary", sessionId);
		return userMessage(List.of(textContent("[Conversation checkpoint]\n" + summary)), timestamp);
	}

	private static List<Path> sessionFiles(SessionStore store) throws IOException {
		Map<String, Path> filesById = new LinkedHashMap<>();
		List<Path> directories = new ArrayList<>(store.legacyDirectories.size() + 1);
		directories.add(store.directory);
		directories.addAll(store.legacyDirectories);
		for (Path candidateDirectory : directories) {
			if (!Files.isDirectory(candidateDirectory)) continue;
			try (Stream<Path> files = Files.list(candidateDirectory)) {
				files.filter(Files::isRegularFile)
						.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
						.forEach(path -> filesById.putIfAbsent(sessionIdFor(path.getFileName()), path));
			}
		}
		return List.copyOf(filesById.values());
	}

	private static Path existingSessionPath(SessionStore store, String sessionId) {
		Path current = store.directory.resolve(sessionId + ".jsonl");
		if (Files.isRegularFile(current)) return current;
		for (Path legacyDirectory : store.legacyDirectories) {
			Path legacy = legacyDirectory.resolve(sessionId + ".jsonl");
			if (Files.isRegularFile(legacy)) return legacy;
		}
		return null;
	}

	private static String sessionIdFor(Path fileName) {
		return fileName.toString().replaceFirst("\\.jsonl$", "");
	}

	private static boolean sameSessionCwd(Path left, Path right) {
		try {
			return left.toRealPath().equals(right.toRealPath());
		} catch (IOException ignored) {
			return left.equals(right);
		}
	}

	private static String requiredSessionPayloadText(JsonNode payload, String field, String sessionId)
			throws IOException {
		JsonNode value = payload.get(field);
		if (value == null || !value.isTextual() || value.asText().isBlank()) {
			throw new IOException("Session " + sessionId + " has no valid " + field);
		}
		return value.asText();
	}

	private static String sessionMessageText(Message message) {
		return switch (message) {
			case UserMessage user -> text(user);
			case AssistantMessage assistant -> text(assistant);
			case ToolResultMessage result -> text(result);
		};
	}

	private static void validateSessionId(String sessionId) {
		if (sessionId == null
				|| !sessionId.matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) {
			throw new IllegalArgumentException("Invalid session id: " + sessionId);
		}
	}

	// ----------------------------------------------------------- settings store

	/** Resolves the settings file and its sibling lock file. */
	public static SettingsStore settingsStore(Path settingsPath) {
		Path resolved = settingsPath.toAbsolutePath().normalize();
		return new SettingsStore(resolved, resolved.resolveSibling(resolved.getFileName() + ".lock"));
	}

	public static SettingsStore defaultSettingsStore() {
		return settingsStore(Path.of(System.getProperty("user.home"), ".codingagent", "settings.json"));
	}

	public static SettingsStore.Settings emptySettings() {
		return new SettingsStore.Settings(null, null, null, null, false);
	}

	public static SettingsStore.Settings withSettingsDefaultModel(
			SettingsStore.Settings settings, String provider, String model) {
		return new SettingsStore.Settings(
				provider, model, settings.defaultThinkingLevel, settings.theme, settings.hideThinkingBlock);
	}

	public static SettingsStore.Settings withSettingsDefaultThinkingLevel(
			SettingsStore.Settings settings, ThinkingLevel level) {
		return new SettingsStore.Settings(
				settings.defaultProvider, settings.defaultModel, level, settings.theme, settings.hideThinkingBlock);
	}

	public static SettingsStore.Settings withSettingsTheme(SettingsStore.Settings settings, String value) {
		return new SettingsStore.Settings(
				settings.defaultProvider,
				settings.defaultModel,
				settings.defaultThinkingLevel,
				value,
				settings.hideThinkingBlock);
	}

	public static SettingsStore.Settings withSettingsHideThinkingBlock(
			SettingsStore.Settings settings, boolean hide) {
		return new SettingsStore.Settings(
				settings.defaultProvider,
				settings.defaultModel,
				settings.defaultThinkingLevel,
				settings.theme,
				hide);
	}

	/** Loads the current settings, or an empty settings object when no file exists. */
	public static SettingsStore.Settings loadSettings(SettingsStore store) throws IOException {
		ObjectNode root = readSettingsObject(store);
		String provider = optionalSettingsText(root, "defaultProvider");
		String model = optionalSettingsText(root, "defaultModel");
		String theme = optionalSettingsText(root, "theme");
		String thinking = optionalSettingsText(root, "defaultThinkingLevel");
		ThinkingLevel thinkingLevel = null;
		if (thinking != null) {
			try {
				thinkingLevel = thinkingLevelFromWire(thinking);
			} catch (IllegalArgumentException error) {
				throw new IOException(
						"Invalid defaultThinkingLevel in " + store.settingsPath + ": " + thinking, error);
			}
		}
		return new SettingsStore.Settings(
				provider, model, thinkingLevel, theme, optionalSettingsBoolean(root, "hideThinkingBlock", false));
	}

	public static void setSettingsDefaultModelAndProvider(SettingsStore store, String provider, String model)
			throws IOException {
		requireSettingsValue(provider, "provider");
		requireSettingsValue(model, "model");
		modifySettings(store, root -> {
			root.put("defaultProvider", provider);
			root.put("defaultModel", model);
		});
	}

	public static void setSettingsDefaultThinkingLevel(SettingsStore store, ThinkingLevel level) throws IOException {
		if (level == null) throw new IllegalArgumentException("level must not be null");
		modifySettings(store, root -> root.put("defaultThinkingLevel", level.wire));
	}

	public static void setSettingsTheme(SettingsStore store, String theme) throws IOException {
		requireSettingsValue(theme, "theme");
		modifySettings(store, root -> root.put("theme", theme));
	}

	public static void setSettingsHideThinkingBlock(SettingsStore store, boolean hide) throws IOException {
		modifySettings(store, root -> root.put("hideThinkingBlock", hide));
	}

	/** Persists whether a configured MCP server should connect on future starts. */
	public static void setSettingsMcpServerEnabled(SettingsStore store, String serverName, boolean enabled)
			throws IOException {
		requireSettingsValue(serverName, "serverName");
		modifySettings(store, root -> settingsMcpServer(root, serverName).put("enabled", enabled));
	}

	/**
	 * Persists a per-server tool override. Enabled tools are omitted from
	 * {@code disabledTools}, because enabled is the default.
	 */
	public static void setSettingsMcpToolEnabled(
			SettingsStore store, String serverName, String toolName, boolean enabled) throws IOException {
		requireSettingsValue(serverName, "serverName");
		requireSettingsValue(toolName, "toolName");
		modifySettings(store, root -> {
			ObjectNode server = settingsMcpServer(root, serverName);
			ArrayNode disabledTools = settingsDisabledTools(server, serverName);
			if (enabled) {
				if (disabledTools == null) return;
				ArrayNode retained = Json.MAPPER.createArrayNode();
				for (JsonNode tool : disabledTools) {
					String name = settingsDisabledToolName(serverName, tool);
					if (!name.equals(toolName)) retained.add(name);
				}
				if (retained.isEmpty()) server.remove("disabledTools");
				else server.set("disabledTools", retained);
				return;
			}
			if (disabledTools == null) disabledTools = server.putArray("disabledTools");
			for (JsonNode tool : disabledTools) {
				if (settingsDisabledToolName(serverName, tool).equals(toolName)) return;
			}
			disabledTools.add(toolName);
		});
	}

	/**
	 * Serializes an update across processes, preserves unknown settings, and
	 * replaces the file atomically. The update reports invalid stored JSON with
	 * UncheckedIOException, which is unwrapped into the declared IOException.
	 */
	private static void modifySettings(SettingsStore store, Consumer<ObjectNode> operation) throws IOException {
		ensureSettingsParentDirectory(store);
		try (FileChannel channel =
						FileChannel.open(store.lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
				FileLock ignored = channel.lock()) {
			setPosixPermissions(store.lockPath, SettingsStore.FILE_PERMISSIONS);
			ObjectNode root = readSettingsObject(store);
			operation.accept(root);
			writeSettingsObject(store, root);
		} catch (UncheckedIOException error) {
			throw error.getCause();
		}
	}

	private static ObjectNode readSettingsObject(SettingsStore store) throws IOException {
		if (!Files.exists(store.settingsPath)) {
			return jsonObject();
		}
		JsonNode root;
		try {
			root = Json.MAPPER.readTree(Files.readString(store.settingsPath, StandardCharsets.UTF_8));
		} catch (IOException error) {
			throw new IOException(
					"Failed to read settings file " + store.settingsPath + ": " + error.getMessage(), error);
		}
		if (!(root instanceof ObjectNode object)) {
			throw new IOException("Invalid settings file " + store.settingsPath + ": expected a JSON object");
		}
		return object;
	}

	private static void writeSettingsObject(SettingsStore store, ObjectNode root) throws IOException {
		Path temp = Files.createTempFile(store.settingsPath.getParent(), "settings-", ".json");
		try {
			Files.writeString(
					temp,
					Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n",
					StandardCharsets.UTF_8,
					StandardOpenOption.WRITE,
					StandardOpenOption.TRUNCATE_EXISTING);
			setPosixPermissions(temp, SettingsStore.FILE_PERMISSIONS);
			try {
				Files.move(temp, store.settingsPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException error) {
				Files.move(temp, store.settingsPath, StandardCopyOption.REPLACE_EXISTING);
			}
			setPosixPermissions(store.settingsPath, SettingsStore.FILE_PERMISSIONS);
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	private static void ensureSettingsParentDirectory(SettingsStore store) throws IOException {
		Path parent = store.settingsPath.getParent();
		if (parent == null) {
			throw new IOException("Settings path has no parent directory: " + store.settingsPath);
		}
		Files.createDirectories(parent);
		setPosixPermissions(parent, SettingsStore.DIRECTORY_PERMISSIONS);
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

	private static ArrayNode settingsDisabledTools(ObjectNode server, String serverName) {
		JsonNode value = server.get("disabledTools");
		if (value == null || value.isNull()) return null;
		if (!(value instanceof ArrayNode tools)) {
			throw new UncheckedIOException(new IOException(
					"Invalid MCP server \"" + serverName + "\": disabledTools must be an array"));
		}
		for (JsonNode tool : tools) {
			settingsDisabledToolName(serverName, tool);
		}
		return tools;
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

	private static boolean optionalSettingsBoolean(ObjectNode root, String field, boolean defaultValue)
			throws IOException {
		JsonNode value = root.get(field);
		if (value == null || value.isNull()) return defaultValue;
		if (!value.isBoolean()) {
			throw new IOException("Invalid setting " + field + ": expected a boolean");
		}
		return value.asBoolean();
	}

	private static void requireSettingsValue(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
	}
}
