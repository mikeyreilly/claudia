package com.quaxt.codingagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.regex.Matcher;
import java.nio.file.InvalidPathException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import com.quaxt.codingagent.agent.AgentEvent;
import com.quaxt.codingagent.agent.AgentMode;
import com.quaxt.codingagent.agent.QuestionBroker;
import com.quaxt.codingagent.cli.QuestionComponent;
import com.quaxt.codingagent.agent.AgentTool;
import com.quaxt.codingagent.agent.CompactionResult;
import com.quaxt.codingagent.ai.Provider;
import com.quaxt.codingagent.ai.auth.Credential;
import com.quaxt.codingagent.ai.json.Json;
import com.quaxt.codingagent.ai.providers.ProviderState;
import com.quaxt.codingagent.ai.types.AssistantContent;
import com.quaxt.codingagent.ai.types.AssistantMessage;
import com.quaxt.codingagent.ai.types.AssistantMessageEvent;
import com.quaxt.codingagent.ai.types.Message;
import com.quaxt.codingagent.ai.types.Model;
import com.quaxt.codingagent.ai.types.StopReason;
import com.quaxt.codingagent.ai.types.TextContent;
import com.quaxt.codingagent.ai.types.ThinkingContent;
import com.quaxt.codingagent.ai.types.ThinkingLevel;
import com.quaxt.codingagent.ai.types.ToolCall;
import com.quaxt.codingagent.ai.types.ToolResultMessage;
import com.quaxt.codingagent.ai.types.Usage;
import com.quaxt.codingagent.ai.types.UserContent;
import com.quaxt.codingagent.ai.types.UserMessage;
import com.quaxt.codingagent.cli.ActivityStatus;
import com.quaxt.codingagent.cli.McpSelector;
import com.quaxt.codingagent.cli.TurnDetailsComponent;
import com.quaxt.codingagent.cli.session.SessionSnapshot;
import com.quaxt.codingagent.tui.AnsiRenderer;
import com.quaxt.codingagent.tui.FuzzyMatcher;
import com.quaxt.codingagent.tui.FuzzySelector;
import com.quaxt.codingagent.tui.Keybindings;
import com.quaxt.codingagent.tui.SelectItem;
import com.quaxt.codingagent.tui.TerminalStyle;
import com.quaxt.codingagent.tui.TuiComponent;
import com.quaxt.codingagent.tui.TuiFrame;
import com.quaxt.codingagent.tui.TuiInput;
import com.quaxt.codingagent.tui.TuiRuntime;
import com.quaxt.codingagent.terminal.Ansi;
import com.quaxt.codingagent.terminal.Cells;
import com.quaxt.codingagent.terminal.KeyParser;
import com.quaxt.codingagent.terminal.LineEditor;
import com.quaxt.codingagent.terminal.StatusLine;
import com.quaxt.codingagent.terminal.Terminal;
import com.quaxt.codingagent.terminal.TerminalEvent;

import static com.quaxt.codingagent.CodingAgentOperations.AgentSnapshot;
import static com.quaxt.codingagent.CodingAgentOperations.CHATGPT_PROVIDER_ID;
import static com.quaxt.codingagent.CodingAgentOperations.ChatGptDeviceCode;
import static com.quaxt.codingagent.CodingAgentOperations.CopilotModelAccess;
import static com.quaxt.codingagent.CodingAgentOperations.GITHUB_COPILOT_PROVIDER_ID;
import static com.quaxt.codingagent.CodingAgentOperations.GitHubCopilotDeviceCode;
import static com.quaxt.codingagent.CodingAgentOperations.McpServerStatus;
import static com.quaxt.codingagent.CodingAgentOperations.McpState;
import static com.quaxt.codingagent.CodingAgentOperations.McpToolStatus;
import static com.quaxt.codingagent.CodingAgentOperations.Settings;
import static com.quaxt.codingagent.CodingAgentOperations.clampThinkingLevel;
import static com.quaxt.codingagent.CodingAgentOperations.getSupportedThinkingLevels;
import static com.quaxt.codingagent.CodingAgentOperations.isAnthropicProxyConfigured;
import static com.quaxt.codingagent.CodingAgentOperations.jsonObject;
import static com.quaxt.codingagent.CodingAgentOperations.providerModels;
import static com.quaxt.codingagent.CodingAgentOperations.result;
import static com.quaxt.codingagent.CodingAgentOperations.role;
import static com.quaxt.codingagent.CodingAgentOperations.text;
import static com.quaxt.codingagent.CodingAgentOperations.thinking;
import static com.quaxt.codingagent.CodingAgentOperations.toolName;
import static com.quaxt.codingagent.CodingAgentOperations.withSettingsDefaultModel;

/** Command-line modes, terminal interaction, and presentation of runtime events. */
public final class CodingAgentCli {
    private final CodingAgentOperations runtime;

    public CodingAgentCli() {
        this(new CodingAgentOperations());
    }

    public CodingAgentCli(CodingAgentOperations runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    private static final String APP_NAME = "codingagent";
    private static final String VERSION = "0.1.0-java";

    // Interactive shell and terminal
    private enum SlashCommand {
        BUILD("/build"),
        CD("/cd"),
        CLEAR("/clear"),
        COMPACT("/compact"),
        DETAILS("/details"),
        EXIT("/exit"),
        FORK("/fork"),
        HELP("/help"),
        LOGIN("/login"),
        LOGOUT("/logout"),
        MCP("/mcp"),
        MODELS("/models"),
        PLAN("/plan"),
        QUIT("/quit", false),
        RESUME("/resume"),
        SETTINGS("/settings"),
        SUBAGENTS("/subagents");

        final String input;
        final boolean includeInHelp;

        SlashCommand(String input) {
            this(input, true);
        }

        SlashCommand(String input, boolean includeInHelp) {
            this.input = input;
            this.includeInHelp = includeInHelp;
        }

        static SlashCommand from(String input) {
            for (SlashCommand command : values()) {
                if (command.input.equals(input)) return command;
            }
            return null;
        }

        static List<String> inputs() {
            return List.of(values()).stream().map(command -> command.input).toList();
        }

        static List<String> helpInputs() {
            return List.of(values()).stream()
                    .filter(command -> command.includeInHelp)
                    .map(command -> command.input)
                    .toList();
        }
    }

    private static final List<String> SLASH_COMMANDS = SlashCommand.inputs();

    static List<String> slashCommands() {
        return SLASH_COMMANDS;
    }

    static String slashCommandHelp() {
        return "Commands: " + String.join(", ", SlashCommand.helpInputs())
                + "\nShortcuts: Shift-Enter inserts a newline; Esc interrupts the active turn; "
                + "Ctrl-O inspects reasoning/tool steps; Ctrl-T shows or hides streamed thinking; Shift-Tab toggles Plan/Build.";
    }

    private static final int VISIBLE_COMMANDS = 4;
    /** SI (invoke G0 into GL), then designate ASCII into G0, undoing stray character-set shifts. */
    static final String RESET_CHARACTER_SET = "\u000f\u001b(B";
    private static final String CLEAR_SCREEN_AND_SCROLLBACK = "\u001b[2J\u001b[H\u001b[3J";
    private static final String SECONDARY_PROMPT = "> ";
    /** Kitty keyboard-protocol flag 1 disambiguates control keys but leaves text in legacy form. */
    private static final int KITTY_DISAMBIGUATE = 1;

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
    // Also remember a successful login when its initial model selector was cancelled.
    private String shellModelProvider;
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
    private AutoCloseable shellSubscription;
    private volatile String selectedAgent = SubagentManager.MAIN;
    private volatile boolean componentOpen;
    private volatile boolean componentDirty;
    private java.util.concurrent.locks.ReentrantLock editorLock;
    private final java.util.concurrent.ConcurrentLinkedQueue<SubagentManager.Event> shellEvents = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> shellNotifications = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private boolean restoredLiveTurn;
    private volatile boolean lineEditorReading;

    // Never wait for the editor from a provider thread. All rendering takes the editor
    // lock before the screen lock, matching the editor's order when it handles a key.
    private void drainShellEvents() {
        if (editorLock == null || !editorLock.tryLock()) return;
        try {
            synchronized (this) {
                if (componentOpen || managedSuspend) return;
                SubagentManager.Event event;
                boolean redraw = false;
                while ((event = shellEvents.poll()) != null) {
                    if (!event.agentId().equals(selectedAgent)) continue;
                    if (restoredLiveTurn) redraw = true;
                    else handleShellAgentEvent(event.event());
                }
                if (redraw) {
                    redrawSelectedConversation();
                    restoredLiveTurn = selectedState().streaming();
                }
                Runnable notice;
                while ((notice = shellNotifications.poll()) != null) notice.run();
                if (agentConfigured) activity = agentActivities.getOrDefault(selectedAgent, activity);
            }
        } finally { editorLock.unlock(); }
    }

    private final Map<String, ActivityStatus> agentActivities = new java.util.concurrent.ConcurrentHashMap<>();

    private AgentSnapshot selectedState() {
        return agentConfigured ? runtime.subagents().snapshot(selectedAgent).state() : runtime.state();
    }

    static List<SelectItem<String>> subagentItems(List<SubagentManager.Snapshot> agents, String selected) {
        return agents.stream().map(agent -> new SelectItem<>(agent.id(),
                (agent.id().equals(selected) ? "* " : "") + agent.name(),
                agent.status().name().toLowerCase(Locale.ROOT) + "  " + agent.queued() + " queued  " + agent.task(),
                agent.name() + " " + agent.task() + " " + agent.id() + " " + agent.status())).toList();
    }

    private void showSubagents() throws IOException {
        if (!agentConfigured) { println("No model is configured."); return; }
        List<SubagentManager.Snapshot> agents = runtime.subagents().list();
        int initial = 0;
        for (int i = 0; i < agents.size(); i++) if (agents.get(i).id().equals(selectedAgent)) initial = i;
        String selected = select("Subagents", subagentItems(agents, selectedAgent), initial, true);
        if (selected != null) {
            synchronized (this) {
                selectedAgent = selected;
                emittedText = false;
                streamOutput = StreamOutput.NONE;
                streamedThinkingCharacters = 0;
                activity = agentActivities.getOrDefault(selected, readyActivity(System.nanoTime()));
                redrawSelectedConversation();
            }
        }
    }

    private void redrawSelectedConversation() {
        if (!agentConfigured) return;
        shellEvents.clear();
        var agent = runtime.subagents().snapshot(selectedAgent);
        restoredLiveTurn = agent.state().streaming();
        List<Message> visible = new ArrayList<>(agent.transcript());
        List<Message> active = agent.state().messages();
        if (agent.state().streaming() && !active.isEmpty() && active.getLast() instanceof AssistantMessage partial
                && partial.stopReason == StopReason.PENDING) visible.add(partial);
        if (!active.isEmpty() && active.getLast() instanceof AssistantMessage last) emittedText = !text(last).isEmpty();
        replaceScreen(renderSessionScreen(agent.state().model(), visible, hideThinkingBlock));
        refreshShellStatus();
    }


    // InteractiveTerminal fields
    private Terminal terminal;
    private LineEditor editor;
    private Callable<Void> suspendAction;
    private boolean supportsSuspend;
    /** The line discipline the shell uses; restored whenever the process is suspended. */
    private Terminal.Mode shellMode;
    private final List<AutoCloseable> signalRegistrations = new ArrayList<>();
    private StringBuilder screenDocument = new StringBuilder();
    private StatusLine statusLine;
    private String statusActivity;
    private StatusAccent statusAccent = StatusAccent.NONE;
    private String statusLeft;
    private String statusRight;
    private Terminal.Mode fullScreenResumeMode;
    private volatile boolean managedSuspend;
    private boolean commandSuggestionsActive;
    private boolean enhancedKeyboardReporting;
    /** Application shortcuts active in every prompt, keyed by keybinding action. */
    private final Map<String, Runnable> appActions = new LinkedHashMap<>();

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

    private String systemPrompt = "";
    private String apiKey;
    private String message = "";
    private boolean noSession;
    private String mode = "print";

    // ------------------------------------------------------------ tui text

    private static final Pattern TERMINAL_ANSI = Pattern.compile(
            "\u001b(?:\\[[0-?]*[ -/]*[@-~]|\\][^\u0007\u001b]*(?:\u0007|\u001b\\\\))");

    /**
     * Terminal cell width of a string, ignoring ANSI escapes.
     */
    public static int visibleWidth(String value) {
        return Cells.width(stripAnsi(value));
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
            int codePointWidth = Math.max(0, Cells.width(codePoint));
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

    /**
     * Makes untrusted text (model output, tool arguments and results) safe to write to the terminal.
     * ANSI escape sequences are removed and other control characters, except newline and tab, are
     * dropped: the terminal would otherwise act on them. A stray SO (0x0E) from binary tool output,
     * for example, shifts the terminal to its G1 character set so that line drawing appears as
     * letters until the terminal is reset.
     */
    static String terminalSafeText(String value) {
        String stripped = stripAnsi(value == null ? "" : value);
        StringBuilder safe = new StringBuilder(stripped.length());
        for (int index = 0; index < stripped.length(); ) {
            int codePoint = stripped.codePointAt(index);
            if (codePoint == '\n' || codePoint == '\t' || !Character.isISOControl(codePoint)) {
                safe.appendCodePoint(codePoint);
            }
            index += Character.charCount(codePoint);
        }
        return safe.toString();
    }

    // ------------------------------------------------------------ styling

    /** Bold green is reserved for the Ready activity so idle is recognizable at a glance. */
    public static String readyStatus() {
        return TerminalStyle.READY;
    }

    /** Active model and shell work; deliberately never green. */
    public static String activeStatus() {
        return TerminalStyle.ACTIVE;
    }

    /** Retry, cancellation, and configuration attention; deliberately never green. */
    private static String warningStatus() {
        return TerminalStyle.WARNING;
    }

    /** Styles every line as a full-width prompt area. */
    private static String promptArea(String value) {
        StringBuilder styled = new StringBuilder(value.length() + 32);
        styled.append(TerminalStyle.PROMPT_BACKGROUND).append(TerminalStyle.CLEAR_TO_END_OF_LINE);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\n') {
                styled.append(TerminalStyle.RESET).append(character);
                if (index + 1 < value.length()) {
                    styled.append(TerminalStyle.PROMPT_BACKGROUND).append(TerminalStyle.CLEAR_TO_END_OF_LINE);
                }
            } else {
                styled.append(character);
            }
        }
        if (value.isEmpty() || value.charAt(value.length() - 1) != '\n') styled.append(TerminalStyle.RESET);
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

    private void pushEnhancedKeyboardReporting() {
        String type = terminal.type();
        if (type == null || Terminal.TYPE_DUMB.equals(type) || Terminal.TYPE_DUMB_COLOR.equals(type)) return;
        terminal.pushKeyboardFlags(KITTY_DISAMBIGUATE);
        enhancedKeyboardReporting = true;
    }

    private void popEnhancedKeyboardReporting() {
        if (!enhancedKeyboardReporting) return;
        enhancedKeyboardReporting = false;
        terminal.popKeyboardFlags();
    }

    /** Whether a decoded key is the configured shortcut for an application action. */
    static boolean appKeyMatches(TerminalEvent.Key key, String action) {
        String binding = Keybindings.DEFAULT_APP_KEYBINDINGS.get(action);
        if (binding != null && binding.startsWith("ctrl-") && binding.length() == 6) {
            return key.isCtrl(binding.charAt(5));
        }
        if ("escape".equals(binding)) return key.is(TerminalEvent.KeyType.ESCAPE);
        if ("shift-tab".equals(binding)) {
            return key.type() == TerminalEvent.KeyType.TAB && key.modifiers() == TerminalEvent.SHIFT;
        }
        throw new IllegalArgumentException("Unsupported application keybinding: " + action + "=" + binding);
    }

    // ----------------------------------------------------------- input parsing

    /**
     * Creates a key event without associated text.
     */
    public static TuiInput.Key key(TuiInput.KeyType type) {
        return new TuiInput.Key(type, "");
    }

    /**
     * Reads one normalized event from the shared input path, or null when the read timed out.
     */
    private TuiInput readTuiInput(long timeoutMs) throws IOException {
        TerminalEvent event = terminal.input().readEvent(timeoutMs);
        return event == null ? null : tuiInput(event);
    }

    /**
     * Normalizes an already-buffered terminal sequence.
     */
    public static TuiInput parseInputSequence(String sequence) {
        if (sequence == null || sequence.isEmpty()) {
            return key(TuiInput.KeyType.UNKNOWN);
        }
        return tuiInput(KeyParser.parse(sequence));
    }

    /**
     * Maps a decoded terminal event to component input. Legacy control bytes and
     * their kitty-protocol encodings decode to the same key, so a selector
     * handles keys queued while the editor had enhanced reporting enabled.
     */
    static TuiInput tuiInput(TerminalEvent event) {
        return switch (event) {
            case TerminalEvent.EndOfInput ignored -> key(TuiInput.KeyType.CANCEL);
            case TerminalEvent.Paste paste -> new TuiInput.Key(TuiInput.KeyType.PASTE, paste.text());
            case TerminalEvent.Mouse mouse -> new TuiInput.Mouse(
                    TuiInput.MouseAction.valueOf(mouse.action().name()), mouse.button(), mouse.x(), mouse.y());
            case TerminalEvent.Key key -> tuiKey(key);
        };
    }

    private static TuiInput tuiKey(TerminalEvent.Key key) {
        if (appKeyMatches(key, "exit")) return key(TuiInput.KeyType.EXIT);
        if (appKeyMatches(key, "suspend")) return key(TuiInput.KeyType.SUSPEND);
        if (appKeyMatches(key, "expandTools")) return key(TuiInput.KeyType.EXPAND_TOOLS);
        if (appKeyMatches(key, "toggleThinking")) return key(TuiInput.KeyType.TOGGLE_THINKING);
        int modifiers = key.modifiers();
        return switch (key.type()) {
            case CHARACTER -> {
                if (modifiers == TerminalEvent.CTRL) {
                    yield switch (key.codePoint()) {
                        case 'c' -> key(TuiInput.KeyType.CANCEL);
                        case 'h' -> key(TuiInput.KeyType.BACKSPACE);
                        case 'i' -> key(TuiInput.KeyType.TAB);
                        case 'j', 'm' -> key(TuiInput.KeyType.ENTER);
                        case 'n' -> key(TuiInput.KeyType.DOWN);
                        case 'p' -> key(TuiInput.KeyType.UP);
                        case 'u' -> key(TuiInput.KeyType.CLEAR);
                        default -> key(TuiInput.KeyType.UNKNOWN);
                    };
                }
                yield (modifiers & (TerminalEvent.CTRL | TerminalEvent.SUPER)) == 0
                        ? new TuiInput.Key(TuiInput.KeyType.CHARACTER, Character.toString(key.codePoint()))
                        : key(TuiInput.KeyType.UNKNOWN);
            }
            case ENTER -> key(TuiInput.KeyType.ENTER);
            case TAB -> modifiers == 0 ? key(TuiInput.KeyType.TAB) : key(TuiInput.KeyType.UNKNOWN);
            case BACKSPACE -> key(TuiInput.KeyType.BACKSPACE);
            case ESCAPE -> key(TuiInput.KeyType.ESCAPE);
            case UP -> key(TuiInput.KeyType.UP);
            case DOWN -> key(TuiInput.KeyType.DOWN);
            case LEFT -> key(TuiInput.KeyType.LEFT);
            case RIGHT -> key(TuiInput.KeyType.RIGHT);
            case HOME -> key(TuiInput.KeyType.HOME);
            case END -> key(TuiInput.KeyType.END);
            case PAGE_UP -> key(TuiInput.KeyType.PAGE_UP);
            case PAGE_DOWN -> key(TuiInput.KeyType.PAGE_DOWN);
            case DELETE -> key(TuiInput.KeyType.DELETE);
            default -> key(TuiInput.KeyType.UNKNOWN);
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

    private static String styleMuted(String value) {
        return TerminalStyle.MUTED + value + TerminalStyle.RESET;
    }

    // --------------------------------------------------------- tui components

    /**
     * Applies one normalized input event to a component.
     */
    private void handleComponentInput(TuiComponent<?> component, TuiInput input) {
        component.handle.accept(input);
    }

    static int mcpSelectorVisibleStart(int itemCount, int visibleCount, int selectedIndex) {
        if (itemCount <= visibleCount) return 0;
        return Math.clamp(selectedIndex - visibleCount / 2, 0, itemCount - visibleCount);
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
                frame -> renderFuzzySelector(selector, frame.width, frame.height),
                input -> handleFuzzySelectorInput(selector, input),
                () -> selector.complete,
                () -> selector.result);
    }

    public static <T> List<String> renderFuzzySelector(
            FuzzySelector<T> selector, int width, int height) {
        List<String> lines = new ArrayList<>();
        lines.add(TerminalStyle.HEADING + truncatePlain(selector.title, width) + TerminalStyle.RESET);
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
            lines.add(TerminalStyle.MUTED + "  No matching options" + TerminalStyle.RESET);
        } else {
            for (int index = selector.visibleStart; index < visibleEnd; index++) {
                SelectItem<T> item = selector.filteredItems.get(index);
                boolean selected = index == selector.selectedIndex;
                boolean current = item == selector.currentItem;
                String suffix = current ? " *" : "";
                String description = item.description.isBlank() ? "" : "  " + item.description;
                String row = (selected ? "> " : "  ") + item.label + suffix + description;
                row = truncatePlain(row, width);
                lines.add(selected ? TerminalStyle.HEADING + row + TerminalStyle.RESET : row);
            }
            if (selector.visibleStart > 0 || visibleEnd < selector.filteredItems.size()) {
                lines.add(TerminalStyle.MUTED
                        + "  "
                        + (selector.selectedIndex + 1)
                        + "/"
                        + selector.filteredItems.size()
                        + TerminalStyle.RESET);
            }
        }
        lines.add("");
        String currentHint = selector.currentItem == null ? "" : "  * current";
        String hint = (selector.searchable
                ? "Type to filter  Up/Down move  Enter select  Esc cancel"
                : "Up/Down move  Enter select  Esc cancel")
                + currentHint;
        lines.add(TerminalStyle.MUTED + truncatePlain(hint, width) + TerminalStyle.RESET);
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
                                    int currentIndex = selector.currentItem == null
                                            ? -1
                                            : selector.filteredItems.indexOf(selector.currentItem);
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

    private static int tuiWidth(TuiRuntime runtime) {
        return runtime.terminal.columns();
    }

    private static int tuiHeight(TuiRuntime runtime) {
        return runtime.terminal.rows();
    }

    /** Switches to the alternate screen in raw mode with mouse reporting and bracketed paste. */
    private void startTuiRuntime(TuiRuntime runtime) {
        runtime.originalMode = runtime.terminal.enterRawMode();
        runtime.pasteWasEnabled = runtime.terminal.bracketedPaste();
        runtime.terminal.alternateScreen(true);
        runtime.terminal.mouseTracking(true);
        runtime.terminal.bracketedPaste(true);
        runtime.terminal.cursorVisible(false);
        runtime.active = true;
        resetAnsiRenderer(runtime.renderer);
        clearTuiScreen(runtime);
    }

    private void stopTuiRuntime(TuiRuntime runtime) {
        if (!runtime.active) {
            return;
        }
        runtime.active = false;
        runtime.terminal.mouseTracking(false);
        runtime.terminal.bracketedPaste(runtime.pasteWasEnabled);
        runtime.terminal.cursorVisible(true);
        runtime.terminal.alternateScreen(false);
        runtime.terminal.setMode(runtime.originalMode);
    }

    private void renderTuiFrame(
            TuiRuntime runtime, TuiComponent<?> component, int width, int height) {
        int safeWidth = Math.max(20, width);
        int safeHeight = Math.max(5, height);
        List<String> lines = component.render.apply(new TuiFrame(safeWidth, safeHeight));
        if (lines.size() > safeHeight) {
            lines = lines.subList(0, safeHeight);
        }
        List<String> next = List.copyOf(lines);
        StringBuilder output = new StringBuilder();
        int common = Math.min(runtime.renderer.previousLines.size(), next.size());
        for (int index = 0; index < common; index++) {
            if (!runtime.renderer.previousLines.get(index).equals(next.get(index))) {
                ansiMoveTo(output, index);
                output.append(Ansi.ERASE_LINE).append(next.get(index));
            }
        }
        for (int index = common; index < next.size(); index++) {
            ansiMoveTo(output, index);
            output.append(Ansi.ERASE_LINE).append(next.get(index));
        }
        for (int index = next.size(); index < runtime.renderer.previousLines.size(); index++) {
            ansiMoveTo(output, index);
            output.append(Ansi.ERASE_LINE);
        }
        runtime.renderer.previousLines = next;
        runtime.terminal.write(output);
    }

    private void clearTuiScreen(TuiRuntime runtime) {
        runtime.terminal.write("\u001b[2J\u001b[H");
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
     * Wires the line editor, status line, and signal handlers onto a terminal.
     */
    public void newInteractiveTerminal(
            Terminal terminal, Callable<Void> suspendAction, boolean supportsSuspend) {
        screenDocument = new StringBuilder();
        statusActivity = null;
        statusAccent = StatusAccent.NONE;
        statusLeft = null;
        statusRight = null;
        fullScreenResumeMode = null;
        managedSuspend = false;
        commandSuggestionsActive = false;
        enhancedKeyboardReporting = false;
        this.terminal = terminal;
        this.editor = new LineEditor(terminal);
        this.editorLock = editor.lock();
        this.statusLine = new StatusLine(terminal);
        this.suspendAction = suspendAction;
        this.supportsSuspend = supportsSuspend;
        this.shellMode = terminal.mode();
        resetTerminalCharacterSet();
        signalRegistrations.add(terminal.handle(Terminal.Signal.WINCH, () -> {
            synchronized (this) {
                statusLine.resize();
                renderStatusBar();
            }
        }));
        if (supportsSuspend) {
            signalRegistrations.add(terminal.handle(Terminal.Signal.CONT, () -> {
                // Resumed by something other than our own suspend: the shell drew over the screen.
                if (managedSuspend) return;
                editorLock.lock();
                try {
                    synchronized (this) {
                        if (!managedSuspend) repaintScreen();
                    }
                } finally {
                    editorLock.unlock();
                }
            }));
        }
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
        int activeLineOffset = activePromptLineOffset(prompt);
        LineEditor.Options options = new LineEditor.Options();
        options.header = prompt.substring(0, activeLineOffset);
        options.prompt = prompt.substring(activeLineOffset);
        options.continuationPrompt = SECONDARY_PROMPT;
        options.rowStyle = TerminalStyle.PROMPT_BACKGROUND + TerminalStyle.CLEAR_TO_END_OF_LINE;
        options.initialBuffer = initialBuffer;
        options.mask = mask;
        options.keys = key -> handleEditorKey(key, suggestionsEnabled);
        options.repaint = () -> {
            synchronized (this) {
                repaintScreen();
            }
        };
        if (suggestionsEnabled) {
            options.below = this::commandSuggestionLines;
            options.beforeAccept = this::insertSelectedCommand;
            // Questions open only from the main prompt, between key sequences.
            options.idle = this::showPendingQuestions;
        }
        LineEditor.Result result;
        Terminal.Mode previousMode = terminal.mode();
        try {
            commandSuggestionsActive = suggestionsEnabled;
            terminal.enterRawMode();
            resetTerminalCharacterSet();
            lineEditorReading = true;
            pushEnhancedKeyboardReporting();
            result = editor.readLine(options);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        } finally {
            popEnhancedKeyboardReporting();
            lineEditorReading = false;
            commandSuggestionsActive = false;
            terminal.setMode(previousMode);
            resetPromptBackground();
        }
        return switch (result.outcome()) {
            case ACCEPTED -> {
                rememberCompletedLine(prompt, result.line(), mask);
                yield result.line();
            }
            case INTERRUPTED -> {
                rememberCompletedLine(prompt, "", mask);
                yield "";
            }
            case END_OF_INPUT -> {
                synchronized (this) {
                    remember(prompt.substring(0, activeLineOffset));
                    remember(promptArea(prompt.substring(activeLineOffset)));
                }
                yield null;
            }
        };
    }

    /** Application shortcuts and command-panel navigation, consulted before the editor's own keys. */
    private boolean handleEditorKey(TerminalEvent.Key key, boolean suggestionsEnabled) {
        if (supportsSuspend && appKeyMatches(key, "suspend")) {
            suspendInteractive();
            return true;
        }
        for (Map.Entry<String, Runnable> action : appActions.entrySet()) {
            if (appKeyMatches(key, action.getKey())) {
                resetPromptBackground();
                try {
                    action.getValue().run();
                } finally {
                    editor.requestRedraw();
                }
                return true;
            }
        }
        if (suggestionsEnabled) {
            if (appKeyMatches(key, "toggleAgentMode")) {
                changeAgentMode(runtime.agentMode() == AgentMode.PLAN ? AgentMode.BUILD : AgentMode.PLAN, true);
                return true;
            }
            if (key.is(TerminalEvent.KeyType.UP)) return moveSuggestion(-1);
            if (key.is(TerminalEvent.KeyType.DOWN)) return moveSuggestion(1);
        }
        return false;
    }

    private boolean moveSuggestion(int delta) {
        synchronizeCommandSuggestions(editor.buffer());
        if (matches.isEmpty()) return false;
        selectedIndex = Math.floorMod(selectedIndex + delta, matches.size());
        if (selectedIndex < visibleStart) {
            visibleStart = selectedIndex;
        } else if (selectedIndex >= visibleStart + VISIBLE_COMMANDS) {
            visibleStart = selectedIndex - VISIBLE_COMMANDS + 1;
        }
        visibleStart = Math.clamp(matches.size() - VISIBLE_COMMANDS, 0, visibleStart);
        return true;
    }

    /** On Enter, replaces a partial slash command with the highlighted suggestion instead of submitting. */
    private boolean insertSelectedCommand() {
        synchronizeCommandSuggestions(editor.buffer());
        if (matches.isEmpty()) return false;
        String selected = matches.get(selectedIndex);
        dismissedBuffer = selected;
        editor.setBuffer(selected, selected.length());
        return true;
    }

    private List<String> commandSuggestionLines() {
        int columns = terminal.columns();
        synchronizeCommandSuggestions(editor.buffer());
        if (matches.isEmpty()) return List.of();
        int visibleEnd = Math.min(matches.size(), visibleStart + VISIBLE_COMMANDS);
        List<String> visible = matches.subList(visibleStart, visibleEnd);
        int longestCommand = visible.stream()
                .mapToInt(CodingAgentCli::visibleWidth)
                .max()
                .orElse(1);
        int innerWidth = Math.max(1, Math.clamp(columns - 2, 1, longestCommand + 2));
        String border = "─".repeat(innerWidth);
        List<String> lines = new ArrayList<>(visible.size() + 2);
        lines.add(styleMuted("╭" + border + "╮"));
        for (int index = visibleStart; index < visibleEnd; index++) {
            boolean selected = index == selectedIndex;
            String content = (selected ? "› " : "  ") + matches.get(index);
            content = truncatePlain(content, innerWidth);
            content += " ".repeat(Math.max(0, innerWidth - visibleWidth(content)));
            String styledContent = selected
                    ? TerminalStyle.HEADING + content + TerminalStyle.RESET
                    : content;
            lines.add(styleMuted("│") + styledContent + styleMuted("│"));
        }
        lines.add(styleMuted("╰" + border + "╯"));
        return lines;
    }

    private static int activePromptLineOffset(String prompt) {
        return Math.max(prompt.lastIndexOf('\n'), prompt.lastIndexOf('\r')) + 1;
    }

    /**
     * Hosts a component on the alternate screen, restoring the line editor afterwards.
     */
    public <T> T runComponent(TuiComponent<T> component)
            throws IOException {
        // Components may open from an editor shortcut and can suspend the process.
        // Leave their alternate screen (and the shell) in legacy key mode; restore
        // enhanced reporting only after returning to the editor.
        boolean resumeReporting = enhancedKeyboardReporting;
        popEnhancedKeyboardReporting();
        try {
            return runComponentInternal(component);
        } finally {
            if (resumeReporting) pushEnhancedKeyboardReporting();
        }
    }

    private <T> T runComponentInternal(TuiComponent<T> component) throws IOException {
        if (editor.isReading()) resetPromptBackground();
        synchronized (this) {
            componentOpen = true;
            statusLine.suspend();
        }
        try {
            TuiRuntime runtime = new TuiRuntime(
                    terminal,
                    supportsSuspend
                            ? () -> {
                        fullScreenResumeMode = terminal.mode();
                        terminal.setMode(shellMode);
                        managedSuspend = true;
                        try {
                            callSuspendAction(suspendAction);
                        } catch (IOException | RuntimeException | Error error) {
                            restoreFullScreenMode();
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
                                restoreFullScreenMode();
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
                    TuiInput input = readTuiInput(100);
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
                // Reserve the status row again: the alternate screen shared the scroll region.
                statusLine.restore();
            }
            synchronized (this) {
                componentOpen = false;
                if (componentDirty) { componentDirty = false; redrawSelectedConversation(); }
            }
            if (editor.isReading()) editor.requestRedraw();
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
        Terminal.Mode originalMode = terminal.enterRawMode();
        FutureTask<T> task = new FutureTask<>(operation);
        Thread worker = Thread.ofVirtual().name("codingagent-interactive-operation").start(task);
        boolean interruptRequested = false;
        try {
            while (!task.isDone()) {
                TuiInput input = readTuiInput(50);
                if (input instanceof TuiInput.Key key
                        && (key.type == TuiInput.KeyType.ESCAPE || key.type == TuiInput.KeyType.CANCEL)
                        && !interruptRequested
                        && !task.isDone()) {
                    interruptRequested = true;
                    interruptHandler.run();
                } else if (input instanceof TuiInput.Key key
                        && key.type == TuiInput.KeyType.SUSPEND
                        && supportsSuspend) {
                    suspendInteractive();
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
            terminal.setMode(originalMode);
        }
    }

    /**
     * Hands the terminal to the shell in its own mode, stops the process group,
     * and on resume restores the mode, key reporting, status row, screen, and
     * any draft in the line editor.
     */
    private void suspendInteractive() {
        boolean resumeReporting = enhancedKeyboardReporting;
        if (editor.isReading()) {
            resetPromptBackground();
            synchronized (this) {
                editor.detach();
            }
        }
        popEnhancedKeyboardReporting();
        boolean pasteWasEnabled = terminal.bracketedPaste();
        terminal.bracketedPaste(false);
        Terminal.Mode resumeMode = terminal.mode();
        synchronized (this) {
            statusLine.suspend();
        }
        terminal.setMode(shellMode);
        managedSuspend = true;
        IOException failure = null;
        try {
            callSuspendAction(suspendAction);
        } catch (IOException error) {
            failure = error;
        } finally {
            terminal.setMode(resumeMode);
            terminal.bracketedPaste(pasteWasEnabled);
            if (resumeReporting) pushEnhancedKeyboardReporting();
            synchronized (this) {
                managedSuspend = false;
                statusLine.restore();
                if (componentDirty) { componentDirty = false; redrawSelectedConversation(); }
                else repaintScreen();
            }
        }
        if (failure != null) println("Could not suspend process: " + failure.getMessage());
    }

    /**
     * Binds a configured application action while the line editor is active.
     */
    public void bindAppAction(String action, Runnable handler) {
        Objects.requireNonNull(handler, "handler");
        // Resolve the binding now so an unsupported one fails at startup.
        appKeyMatches(TerminalEvent.Key.of(TerminalEvent.KeyType.UNKNOWN), action);
        appActions.put(action, handler);
    }

    /**
     * Prints a status line without losing the active line-editor buffer.
     */
    public void printAbove(String text) {
        if (editorLock != null) editorLock.lock();
        try {
            synchronized (this) {
                remember(text + System.lineSeparator());
                if (editor.isReading()) {
                    resetPromptBackground();
                    repaintScreen();
                } else {
                    terminal.write(text + System.lineSeparator());
                }
            }
        } finally { if (editorLock != null) editorLock.unlock(); }
    }

    private void print(String text) {
        if (editorLock != null) editorLock.lock();
        try {
            synchronized (this) {
                String value = String.valueOf(text);
                remember(value);
                if (editor != null && editor.isReading()) {
                    repaintScreen();
                } else {
                    terminal.write(value);
                }
            }
        } finally { if (editorLock != null) editorLock.unlock(); }
    }

    public void println(String text) {
        print(String.valueOf(text) + System.lineSeparator());
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

    /** Shows activity first so it remains visible when metadata must be truncated. */
    public void setStatus(String activity, StatusAccent accent, String left, String right) {
        synchronized (this) {
            statusActivity = activity == null ? "" : activity;
            statusAccent = accent == null ? StatusAccent.NONE : accent;
            statusLeft = left == null ? "" : left;
            statusRight = right == null ? "" : right;
            renderStatusBar();
        }
    }

    private void renderStatusBar() {
        if (statusLeft == null && statusRight == null) return;
        if (statusLine == null) return;
        statusLine.update(statusBarLine(
                statusActivity, statusAccent, statusLeft, statusRight, terminal.columns()));
    }

    /**
     * Keeps activity ahead of workspace/model metadata. When the terminal is
     * narrow, metadata is discarded before the activity text is truncated.
     */
    public static String statusBarLine(
            String activity, StatusAccent accent, String left, String right, int width) {
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
        if (activityText.isEmpty()) return mutedStatus(alignedStatusDetails(left, right, safeWidth));
        String activityStyle = switch (accent == null ? StatusAccent.NONE : accent) {
            case NONE -> TerminalStyle.MUTED;
            case READY -> readyStatus();
            case ACTIVE -> activeStatus();
            case TOOL -> TerminalStyle.TOOL;
            case WARNING -> warningStatus();
        };
        return activityStyle + activityText + TerminalStyle.RESET + mutedStatus(separatorAndDetails);
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

    private static String mutedStatus(String text) {
        return text.isEmpty() ? text : TerminalStyle.MUTED + text + TerminalStyle.RESET;
    }

    private void rememberCompletedLine(String prompt, String line, Character mask) {
        synchronized (this) {
            String displayedLine = line;
            if (mask != null) {
                displayedLine =
                        mask == 0 ? "" : String.valueOf(mask).repeat(line.length());
            }
            int activeLineOffset = activePromptLineOffset(prompt);
            remember(prompt.substring(0, activeLineOffset));
            remember(promptArea(prompt.substring(activeLineOffset) + displayedLine));
            remember(System.lineSeparator());
        }
    }

    private void remember(String text) {
        synchronized (this) {
            screenDocument.append(text);
        }
    }

    private void restoreFullScreenMode() {
        if (fullScreenResumeMode != null) {
            terminal.setMode(fullScreenResumeMode);
            fullScreenResumeMode = null;
        }
    }

    private void resetPromptBackground() {
        terminal.write(TerminalStyle.RESET);
    }

    /**
     * Returns the terminal to G0/ASCII. Earlier output in this terminal (another
     * program, or a stray SO before tool output was sanitized) can leave G1 or DEC
     * line drawing selected, which would garble the prompt and status until the
     * user ran {@code reset}.
     */
    private void resetTerminalCharacterSet() {
        terminal.write(RESET_CHARACTER_SET);
    }

    /** Clears the screen and scrollback, then redraws the status row, the document, and any active prompt. */
    private void repaintScreen() {
        synchronized (this) {
            terminal.beginUpdate();
            try {
                terminal.write(TerminalStyle.RESET);
                boolean redrawStatus = statusLine.isShown();
                // Release the status row so the redrawn document starts on a clean screen.
                if (redrawStatus) statusLine.suspend();
                terminal.write(CLEAR_SCREEN_AND_SCROLLBACK);
                // Re-reserve the bottom row before printing so the document scrolls above it.
                if (redrawStatus) statusLine.restore();
                terminal.write(screenDocument);
                if (editor.isReading()) {
                    editor.screenReset();
                    editor.redisplay();
                }
            } finally {
                terminal.endUpdate();
            }
        }
    }

    /**
     * Removes this shell's signal handlers, releases the status row, and restores the terminal.
     */
    public void closeTerminal() throws IOException {
        for (AutoCloseable registration : signalRegistrations) {
            try {
                registration.close();
            } catch (Exception ignored) {
                // Unregistering a handler cannot fail meaningfully.
            }
        }
        signalRegistrations.clear();
        synchronized (this) {
            if (statusLine != null) statusLine.close();
        }
        terminal.close();
    }

    // ------------------------------------------------------------------- cli

    /**
     * Process entry point for the shaded jar and the native executable.
     */
    public static void main(String[] args) {
        System.exit(new CodingAgentCli().cliRun(args));
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
                    case "--aliases" -> runtime.setCodeLensAliases(cliAliasesValue(args, ++i));
                    case "--no-session" -> noSession = true;
                    case "--mode" -> mode = cliArgumentValue(args, ++i, arg);
                    case "--agent-mode" -> runtime.setAgentMode(AgentMode.parse(cliArgumentValue(args, ++i, arg)));
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
                          \t\t\t\t Set a per-run system prompt
                          --aliases <:a[:b...]>
                                         Force these Clojure tools.deps aliases on all code-lens MCP reads
                          --no-session   Do not persist the print-mode transcript
                          --mode <print|json|rpc>
                        				 Select plain text, JSONL events, or stdin/stdout RPC
                          --agent-mode <build|plan>
                                         Select the workflow (default: build)
                          -p, --print <prompt>
                        				 Run a headless coding-agent prompt and print the final answer

                        Interactive mode restores the model and settings selected previously.
                        Run /login to authenticate with GitHub Copilot, an OpenAI API key, or ChatGPT Plus/Pro.
                        """.formatted(APP_NAME, APP_NAME));
                return 0;
            }
            runtime.initializeCoreProviders();
            if (listModels) {
                String needle = modelSearch == null ? "" : modelSearch.toLowerCase();
                for (Provider provider : runtime.coreProviders()) {
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
                    initialModel = runtime.requireCatalogModel(parts[0], parts[1]);
                } else {
                    if (this.provider == null) {
                        throw new IllegalArgumentException("--mode rpc requires --model <provider/model>");
                    }
                    initialModel = runtime.requireCatalogModel(this.provider, this.model);
                }
                runtime.mcpLoadDefaultManager(Path.of(".").toAbsolutePath().normalize());
                try {
                    resetRpcAgent(initialModel);
                } catch (IOException | RuntimeException error) {
                    runtime.mcpCloseManager();
                    throw error;
                }
                runtime.questions().setInteractive(true);
                List<java.util.concurrent.CompletableFuture<?>> rpcTasks = new ArrayList<>();
                AutoCloseable questionEvents = runtime.questions().subscribe(event -> outputRpc(questionEventJson(event)));
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
                            rpcTasks.removeIf(java.util.concurrent.CompletableFuture::isDone);
                            if (Set.of("set_model", "new_session", "set_agent_mode", "set_auto_compaction").contains(type))
                                runtime.requireIdleGroup();
                            switch (type) {
                                case "prompt" -> {
                                    JsonNode message = command.get("message");
                                    if (message == null || !message.isTextual() || message.asText().isBlank()) {
                                        throw new IllegalArgumentException("prompt requires a non-empty string message");
                                    }
                                    rpcTasks.add(runtime.subagents().submit(SubagentManager.MAIN, message.asText())
                                            .handle((answer, failure) -> {
                                                respondRpc(id, "prompt", failure == null, null, rpcFailure(failure));
                                                return null;
                                            }));
                                }
                                case "abort" -> {
                                    runtime.abort();
                                    respondRpc(id, type, true, null, null);
                                }
                                case "get_state" -> {
                                    AgentSnapshot state = runtime.state();
                                    ObjectNode data = jsonObject();
                                    data.set("model", Json.MAPPER.valueToTree(state.model()));
                                    data.put("isStreaming", state.streaming());
                                    data.put("isCompacting", state.compacting());
                                    data.put("autoCompactionEnabled", state.autoCompactionEnabled());
                                    data.put("messageCount", state.messages().size());
                                    data.put("sessionId", noSession ? "" : state.sessionId());
                                    data.put("agentMode", state.agentMode().wire);
                                    var pending = data.putArray("pendingQuestions");
                                    runtime.questions().pending().forEach(question -> pending.add(questionRequestJson(question)));
                                    respondRpc(id, type, true, data, null);
                                }
                                case "set_agent_mode" -> {
                                    runtime.setAgentMode(AgentMode.parse(requiredRpcText(command, "agentMode")));
                                    respondRpc(id, type, true, jsonObject().put("agentMode", runtime.agentMode().wire), null);
                                }
                                case "answer_question" -> {
                                    String questionId = requiredRpcText(command, "questionId");
                                    if (command.has("decline") && !command.path("decline").isBoolean())
                                        throw new IllegalArgumentException("decline must be a boolean");
                                    if (command.path("decline").asBoolean()) {
                                        if (command.has("answer")) throw new IllegalArgumentException("Supply an answer or decline, not both");
                                        runtime.questions().decline(questionId);
                                    } else runtime.questions().answer(questionId, requiredRpcText(command, "answer"));
                                    respondRpc(id, type, true, null, null);
                                }
                                case "get_available_models" -> {
                                    ObjectNode data = jsonObject();
                                    data.set("models", Json.MAPPER.valueToTree(runtime.allCatalogModels()));
                                    respondRpc(id, type, true, data, null);
                                }
                                case "set_model" -> {
                                    String provider = requiredRpcText(command, "provider");
                                    String modelId = requiredRpcText(command, "modelId");
                                    Model model = runtime.requireCatalogModel(provider, modelId);
                                    resetRpcAgent(model);
                                    respondRpc(id, "set_model", true, Json.MAPPER.valueToTree(model), null);
                                }
                                case "compact" -> {
                                    String instructions = command.path("customInstructions").isTextual()
                                            ? command.path("customInstructions").asText()
                                            : null;
                                    rpcTasks.add(submitRpcCompaction(id, instructions));
                                }
                                case "set_auto_compaction" -> {
                                    if (!command.path("enabled").isBoolean()) {
                                        throw new IllegalArgumentException("enabled must be a boolean");
                                    }
                                    runtime.setAutoCompaction(command.path("enabled").asBoolean());
                                    respondRpc(id, type, true, null, null);
                                }
                                case "get_messages" -> {
                                    ObjectNode data = jsonObject();
                                    data.set("messages", Json.MAPPER.valueToTree(runtime.state().messages()));
                                    respondRpc(id, type, true, data, null);
                                }
                                case "get_last_assistant_text" -> {
                                    String assistantText = runtime.state().messages().reversed().stream()
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
                                    resetRpcAgent(runtime.state().model());
                                    respondRpc(id, type, true, jsonObject().put("cancelled", false), null);
                                }
                                default -> respondRpc(id, type, false, null, "Unsupported command: " + type);
                            }
                        } catch (Exception e) {
                            respondRpc(id, type, false, null, e.getMessage() == null ? e.toString() : e.getMessage());
                        }
                    }
                } finally {
                    runtime.questions().setInteractive(false);
                    java.util.concurrent.CompletableFuture.allOf(rpcTasks.toArray(java.util.concurrent.CompletableFuture[]::new)).join();
                    try { questionEvents.close(); } catch (Exception ignored) { }
                    runtime.mcpCloseManager();
                }
                return 0;
            }
            if (print) {
                if (this.message.isBlank()) {
                    throw new IllegalArgumentException("--print requires a prompt");
                }
                Model model = resolveCliModel(this.provider, this.model);

                Path cwd = Path.of(".").toAbsolutePath().normalize();
                runtime.mcpLoadDefaultManager(cwd);
                try {
                    runtime.mcpAwaitReady();
                    runtime.configureAgent(model, cwd, systemPrompt, apiKey, ThinkingLevel.OFF);
                    if (mode.equals("json")) {
                        runtime.subscribe(event -> {
                            ObjectNode node = encodeAgentEvent(event, false);
                            if (node != null) System.out.println(node);
                        });
                    } else {
                        runtime.subscribe(event -> {
                            if (event instanceof AgentEvent.InstructionLoaded loaded) {
                                System.err.println(instructionLoadedMessage(loaded.path));
                            }
                        });
                    }
                    if (!noSession) {
                        runtime.defaultSessionStore();
                        runtime.createSessionRecorder(cwd, model.provider, model.id);
                    }
                    runtime.setSessionRecording(!noSession, this::reportCheckpointFailure);
                    runtime.prompt(this.message);

                    if (runtime.state().messages().getLast() instanceof AssistantMessage response) {
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
                    runtime.mcpCloseManager();
                }
            }
            Settings settings = runtime.loadSettings();
            // Check the console before starting MCP servers.
            Terminal systemTerminal = Terminal.system();
            Path workspace = Path.of(".").toAbsolutePath().normalize();
            try {
                runtime.mcpLoadDefaultManager(workspace);
            } catch (IOException | RuntimeException error) {
                systemTerminal.close();
                throw error;
            }
            try {
                boolean canSuspend = !System.getProperty("os.name", "")
                        .toLowerCase(java.util.Locale.ROOT).startsWith("windows");
                newInteractiveTerminal(
                        systemTerminal,
                        canSuspend ? () -> {
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
                        } : null,
                        canSuspend);
                try {
                    this.settings = settings;
                    runtime.questions().setInteractive(true);
                    agentConfigured = false;
                    recordingSession = false;
                    hideThinkingBlock = settings.hideThinkingBlock;
                    activity = noModelActivity(System.nanoTime());
                    statusTicker = Executors.newSingleThreadScheduledExecutor(
                            Thread.ofPlatform().daemon(true).name("codingagent-status").factory());
                    bindAppAction("interrupt", () -> {
                        if (agentConfigured) {
                            runtime.subagents().cancel(selectedAgent);
                            printAbove("Interrupted " + runtime.subagents().snapshot(selectedAgent).name() + ". Queued prompts cleared.");
                        }
                    });
                    bindAppAction("expandTools", () -> showShellTurnDetails(true));
                    bindAppAction("toggleThinking", () -> setShellHideThinkingBlock(!hideThinkingBlock, true));
                    try {
                        if (this.model != null) {
                            // An explicit CLI model overrides the saved default for this session only.
                            configureShellModel(resolveCliModel(this.provider, this.model), false);
                        } else if (this.provider != null) {
                            List<Model> models1 = providerModels(runtime.requireCoreProvider(this.provider));
                            if (models1.isEmpty()) {
                                throw new IllegalArgumentException("No bundled models for provider: " + this.provider);
                            }
                            List<SelectItem<Model>> items =
                                    models1.stream().map(CodingAgentCli::shellModelItem).toList();
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
                                                providerModels(runtime.requireCoreProvider(savedSettings.defaultProvider)),
                                                savedSettings.defaultProvider,
                                                savedSettings.defaultModel);
                                    } catch (IllegalArgumentException ignored) {
                                    }
                                    if (model == null) {
                                        println("Saved model " + savedSettings.defaultProvider + "/" + savedSettings.defaultModel
                                                + " is unavailable; selecting a fallback.");
                                    } else if (model.provider.equals(GITHUB_COPILOT_PROVIDER_ID)) {
                                        ProviderState copilot = (ProviderState)
                                                runtime.requireCoreProvider(GITHUB_COPILOT_PROVIDER_ID);
                                        try {
                                            if (runtime.gitHubCopilotHasCredential(copilot)) {
                                                shellModelProvider = GITHUB_COPILOT_PROVIDER_ID;
                                                model = findModelIn(
                                                        runtime.gitHubCopilotAvailableModels(copilot), model.provider, model.id);
                                            } else {
                                                model = null;
                                            }
                                            if (model == null) {
                                                println("Saved GitHub Copilot model is not enabled for this account; selecting a fallback.");
                                            }
                                        } catch (IOException error) {
                                            println("Could not restore the saved GitHub Copilot model: " + error.getMessage());
                                            model = null;
                                        }
                                    } else if (model.provider.equals(CHATGPT_PROVIDER_ID)) {
                                        try {
                                            ProviderState chatGpt =
                                                    (ProviderState) runtime.requireCoreProvider(CHATGPT_PROVIDER_ID);
                                            if (!runtime.chatGptHasCredential(chatGpt)) model = null;
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

                                ProviderState chatGpt = (ProviderState)
                                        runtime.requireCoreProvider(CHATGPT_PROVIDER_ID);
                                try {
                                    if (runtime.chatGptHasCredential(chatGpt)) {
                                        shellModelProvider = CHATGPT_PROVIDER_ID;
                                        if (!chatGpt.models.isEmpty()) {
                                            configureShellModel(preferredChatGptModel(chatGpt.models), true);
                                            break restore;
                                        }
                                    }
                                } catch (IOException error) {
                                    println("ChatGPT login needs attention: " + error.getMessage());
                                }
                                ProviderState copilot = (ProviderState)
                                        runtime.requireCoreProvider(GITHUB_COPILOT_PROVIDER_ID);
                                try {
                                    if (!runtime.gitHubCopilotHasCredential(copilot)) break restore;
                                    shellModelProvider = GITHUB_COPILOT_PROVIDER_ID;
                                    Model fallback = preferredCopilotModel(runtime.gitHubCopilotAvailableModels(copilot));
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
                        print(sessionScreenHeader(!agentConfigured ? null : runtime.state().model()));
                        refreshShellStatus();
                        statusTicker.scheduleWithFixedDelay(() -> {
                            try {
                                drainShellEvents();
                                if (!isDynamicActivity(activity)) return;
                                renderShellStatus();
                            } catch (RuntimeException ignored) {
                                // A best-effort repaint must not terminate the shell's status ticker.
                            }
                        }, 50, 50, TimeUnit.MILLISECONDS);
                        while (true) {
                            String input = readLine("\n> ", slashCommands());
                            drainShellEvents();
                            if (input == null) {
                                println("");
                                return 0;
                            }
                            if (input.isBlank()) continue;
                            if (input.startsWith("/")) {
                                if (dispatchSlashCommand(input)) return 0;
                                continue;
                            }
                            if (!agentConfigured) {
                                println("No model configured. Run /login to choose a provider.");
                                continue;
                            }
                            String target = selectedAgent;
                            boolean queued = !runtime.subagents().idle(target);
                            runtime.subagents().submit(target, input).whenComplete((answer, error) -> {
                                shellNotifications.add(() -> {
                                    if (!target.equals(selectedAgent)) return;
                                    if (error != null) println("Error: " + error.getMessage());
                                    refreshShellStatus();
                                });
                            });
                            if (queued) println("Prompt queued for " + runtime.subagents().snapshot(target).name() + ".");
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
                    closeShellSubscription();
                    runtime.close();
                    closeTerminal();
                }
            } finally {
                runtime.mcpCloseManager();
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
        } finally {
            runtime.close();
        }
    }

    /** Dispatches one interactive slash command and returns whether the shell should exit. */
    private boolean dispatchSlashCommand(String input) throws IOException, InterruptedException {
        editorLock.lock();
        try { return handleSlashCommand(input); }
        finally { editorLock.unlock(); }
    }

    private boolean handleSlashCommand(String input) throws IOException, InterruptedException {
        String trimmed = input.trim();
        String[] parts = trimmed.split("\\s+", 2);
        String commandInput = parts[0];
        String arguments = parts.length == 1 ? "" : parts[1].strip();
        SlashCommand command = SlashCommand.from(commandInput);
        String commandLabel = command == null ? commandInput : command.input;
        if (agentConfigured && command != null) {
            boolean local = switch (command) {
                case SUBAGENTS, DETAILS, HELP, EXIT, QUIT, COMPACT -> true;
                default -> false;
            };
            if (!local && (!SubagentManager.MAIN.equals(selectedAgent) || !runtime.subagents().idle())) {
                println("Select Main and wait for all agents to be idle before " + command.input + ".");
                return false;
            }
            if (command == SlashCommand.COMPACT && !runtime.subagents().idle(selectedAgent)) {
                println("Wait for the selected agent to be idle before compacting.");
                return false;
            }
        }
        setShellActivity(activeActivity(
                ActivityStatus.Phase.RUNNING_COMMAND, commandLabel, System.nanoTime()));
        try {
            if (command == null) {
                println("Unknown command: " + input);
                return false;
            }
            if (command != SlashCommand.CD && !arguments.isEmpty()) {
                println("Command " + command.input + " does not accept arguments.");
                return false;
            }
            switch (command) {
                case PLAN -> changeAgentMode(AgentMode.PLAN, false);
                case BUILD -> changeAgentMode(AgentMode.BUILD, false);
                case SUBAGENTS -> showSubagents();
                case CD -> {
                    if (arguments.isEmpty()) {
                        println("Usage: /cd <directory>");
                        break;
                    }
                    try {
                        changeShellWorkingDirectory(resolveShellWorkingDirectory(cwd, arguments));
                    } catch (IllegalArgumentException | IOException error) {
                        println("Could not change working directory: " + error.getMessage());
                    }
                }
                case EXIT, QUIT -> {
                    return true;
                }
                case HELP -> println(slashCommandHelp());
                case CLEAR -> {
                    if (!agentConfigured) {
                        println("No model is configured.");
                        break;
                    }
                    Model model = runtime.state().model();
                    IOException persistenceFailure = startFreshShellSession(model, cwd);
                    replaceScreen(sessionScreenHeader(model));
                    println("Started a new session.");
                    if (persistenceFailure != null) {
                        println("New session will not be saved: " + persistenceFailure.getMessage());
                    }
                }
                case DETAILS -> showShellTurnDetails(false);
                case FORK -> {
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
                    Model model = runtime.state().model();
                    List<Message> forkMessages = runtime.resumableMessages(runtime.state().messages());
                    ObjectNode forkTaskState = runtime.taskStateSnapshot();
                    boolean recordingEnabled = false;
                    if (!noSession) {
                        try {
                            runtime.defaultSessionStore();
                            runtime.forkSessionRecorder(
                                    this.cwd, model.provider, model.id, name, forkMessages);
                            recordingEnabled = true;
                        } catch (IOException error) {
                            println("Failed to fork session: " + error.getMessage());
                            break;
                        }
                    }
                    configureShellAgent(model, this.cwd, recordingEnabled, name);
                    runtime.restoreMessages(forkMessages);
                    runtime.restoreTaskState(forkTaskState);
                    refreshShellStatus();
                    println("Forked session " + name + " with " + forkMessages.size() + " message(s).");
                }
                case RESUME -> {
                    resume: {
                        if (noSession) {
                            println("Session persistence is disabled by --no-session.");
                            break resume;
                        }
                        runtime.defaultSessionStore();
                        List<SessionSnapshot> sessions;
                        try {
                            sessions = runtime.listSessions(cwd);
                        } catch (IOException error) {
                            println("Failed to list saved sessions: " + error.getMessage());
                            break resume;
                        }
                        if (sessions.isEmpty()) {
                            println("No saved sessions in " + cwd + ".");
                            break resume;
                        }
                        String currentId = !recordingSession ? null : runtime.state().sessionId();
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
                                        providerModels(runtime.requireCoreProvider(selected.provider)),
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
                                model = runtime.state().model();
                                println("Could not restore model " + selected.provider + "/" + selected.model
                                        + ". Using " + model + ".");
                            }
                            if (model.provider.equals(GITHUB_COPILOT_PROVIDER_ID)) {
                                ProviderState copilot = (ProviderState)
                                        runtime.requireCoreProvider(GITHUB_COPILOT_PROVIDER_ID);
                                Model enabled = null;
                                try {
                                    if (runtime.gitHubCopilotHasCredential(copilot)) {
                                        enabled = findModelIn(
                                                runtime.gitHubCopilotAvailableModels(copilot),
                                                model.provider,
                                                model.id);
                                    }
                                } catch (IOException error) {
                                    println("Could not refresh GitHub Copilot model access: "
                                            + error.getMessage());
                                }
                                if (enabled != null) model = enabled;
                                else if (!agentConfigured
                                        || runtime.state().model().provider.equals(GITHUB_COPILOT_PROVIDER_ID)) {
                                    println("Cannot restore GitHub Copilot model " + model.id
                                            + "; log in or configure another model first.");
                                    break resume;
                                } else {
                                    Model fallback = runtime.state().model();
                                    println("Could not restore model " + model + ". Using " + fallback + ".");
                                    model = fallback;
                                }
                            }
                            // A session snapshot's messages are compaction-aware, so resuming cannot
                            // resurrect summarized transcript entries into the next model request.
                            List<Message> restored = runtime.resumableMessages(selected.messages);
                            configureShellAgent(model, selected.cwd, false, selected.name);
                            settings = withSettingsDefaultModel(settings, model.provider, model.id);
                            runtime.restoreMessages(restored);
                            runtime.resumeSessionRecorder(selected.id);
                            runtime.setSessionRecording(true, this::reportCheckpointFailure);
                            recordingSession = true;
                            refreshShellStatus();
                            replaceScreen(renderSessionScreen(
                                    model,
                                    selected.transcriptMessages,
                                    hideThinkingBlock));
                            try {
                                runtime.setSettingsDefaultModelAndProvider(model.provider, model.id);
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
                case LOGIN -> {
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
                                ProviderState copilot = (ProviderState)
                                        runtime.requireCoreProvider(GITHUB_COPILOT_PROVIDER_ID);
                                GitHubCopilotDeviceCode device = runtime.gitHubCopilotBeginLogin(copilot);
                                println("Open " + device.verificationUri + " and enter code " + device.userCode + ".");
                                println("Waiting for GitHub authorization...");
                                runtime.gitHubCopilotCompleteLogin(copilot, device);
                                shellModelProvider = GITHUB_COPILOT_PROVIDER_ID;
                                println("Enabling GitHub Copilot models...");
                                CopilotModelAccess access =
                                        runtime.gitHubCopilotEnableAndRefreshModels(copilot);
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
                                    runtime.modifyCredential(
                                            runtime.defaultCredentialStore(),
                                            "openai",
                                            ignored -> new Credential.ApiKeyCredential(apiKey.trim(), Map.of()));
                                    shellModelProvider = "openai";
                                    List<Model> models1 = providerModels(runtime.requireCoreProvider("openai"));
                                    Model model = shellSavedModelIn(models1);
                                    if (model == null) {
                                        model = this.select("Select an OpenAI model", models1.stream().map(CodingAgentCli::shellModelItem).toList(), -1, true);
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
                                ProviderState chatGpt = (ProviderState)
                                        runtime.requireCoreProvider(CHATGPT_PROVIDER_ID);
                                ChatGptDeviceCode device = runtime.chatGptBeginLogin(chatGpt);
                                println("Open " + device.verificationUri + " and enter code " + device.userCode + ".");
                                println("Waiting for ChatGPT authorization...");
                                runtime.chatGptCompleteLogin(chatGpt, device);
                                shellModelProvider = CHATGPT_PROVIDER_ID;
                                List<Model> models1 = chatGpt.models;
                                Model model = shellSavedModelIn(models1);
                                if (model == null) {
                                    model = this.select("Select a ChatGPT model", models1.stream().map(CodingAgentCli::shellModelItem).toList(), -1, true);
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
                case LOGOUT -> {
                    boolean hadAgent = agentConfigured;
                    try {
                        if (CHATGPT_PROVIDER_ID.equals(shellModelProvider)) {
                            runtime.chatGptLogout(
                                    (ProviderState) runtime.requireCoreProvider(CHATGPT_PROVIDER_ID));
                            if (agentConfigured && runtime.state().model().provider.equals(CHATGPT_PROVIDER_ID)) {
                                agentConfigured = false;
                            }
                            println("ChatGPT credentials removed. Run /login or /resume to continue.");
                        } else if ("openai".equals(shellModelProvider)) {
                            runtime.deleteCredential(runtime.defaultCredentialStore(), "openai");
                            if (agentConfigured && runtime.state().model().provider.equals("openai")) {
                                agentConfigured = false;
                            }
                            println("OpenAI API key removed. Run /login or /resume to continue.");
                        } else {
                            runtime.gitHubCopilotLogout((ProviderState)
                                    runtime.requireCoreProvider(GITHUB_COPILOT_PROVIDER_ID));
                            if (agentConfigured
                                    && runtime.state().model().provider.equals(GITHUB_COPILOT_PROVIDER_ID)) {
                                agentConfigured = false;
                                println("GitHub Copilot credentials removed. Run /login or /resume to continue.");
                            } else {
                                println("GitHub Copilot credentials removed.");
                            }
                        }
                        shellModelProvider = agentConfigured ? runtime.state().model().provider : null;
                    } finally {
                        if (hadAgent && !agentConfigured) {
                            closeShellSubscription();
                            runtime.subagents().close();
                            shellEvents.clear();
                            shellNotifications.clear();
                        }
                        refreshShellStatus();
                    }
                }
                case MODELS -> {
                    if (shellModelProvider == null) {
                        println("No provider is selected. Run /login first or start with --model or --provider.");
                        break;
                    }
                    List<Model> models1 = shellSelectableModels();
                    if (models1.isEmpty()) {
                        println("No selectable models for " + shellModelProvider + ". Run /login to refresh access.");
                        break;
                    }
                    List<SelectItem<Model>> items =
                            models1.stream().map(CodingAgentCli::shellModelItem).toList();
                    int currentIndex;
                    if (!agentConfigured) {
                        currentIndex = -1;
                    } else {
                        int result1 = -1;
                        for (int index = 0; index < models1.size(); index++) {
                            Model model = models1.get(index);
                            if (model.provider.equals(runtime.state().model().provider) && model.id.equals(runtime.state().model().id)) {
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
                case MCP -> {
                    if (runtime.mcpStatuses().isEmpty()) {
                        println("No MCP servers configured in ~/.codingagent/settings.json.");
                    } else {
                        McpSelector selector1 = new McpSelector();
                        selector1.manager = runtime;
                        selector1.onChange = change -> {
                            try {
                                runtime.saveMcpPreference(change.serverName, change.toolName, change.enabled);
                            } catch (IOException error) {
                                throw new UncheckedIOException(error);
                            } finally {
                                syncShellMcpTools();
                            }
                        };
                        selector1.names = runtime.mcpStatuses().stream().map(status -> status.name).toList();
                        selector1.filtered = selector1.names;
                        runComponent(new TuiComponent<>(
                                frame -> {
                                    if (selector1.view == McpSelector.View.TOOLS)
                                        refreshMcpSelectorTools(selector1);

                                    List<String> lines = new ArrayList<>();
                                    String title = selector1.view == McpSelector.View.SERVERS
                                            ? "MCP Servers"
                                            : "MCP Tools: " + selector1.toolServer;
                                    lines.add(TerminalStyle.HEADING + truncatePlain(title, frame.width) + TerminalStyle.RESET);
                                    lines.add("");
                                    String before = selector1.query.substring(0, selector1.queryCursor);
                                    String after = selector1.query.substring(selector1.queryCursor);
                                    lines.add(truncatePlain("Search: " + before + "|" + after, frame.width));
                                    lines.add("");
                                    selector1.optionStartRow = lines.size();
                                    selector1.visibleCount = Math.clamp(frame.height - 9, 1, 10);
                                    int itemCount = mcpSelectorItemCount(selector1);
                                    selector1.visibleStart = Math.max(
                                            0,
                                            Math.min(
                                                    selector1.selectedIndex - selector1.visibleCount / 2,
                                                    Math.max(0, itemCount - selector1.visibleCount)));
                                    int end = Math.min(itemCount, selector1.visibleStart + selector1.visibleCount);
                                    if (itemCount == 0) {
                                        String empty = selector1.view == McpSelector.View.SERVERS
                                                ? "  No matching servers"
                                                : "  No tools available";
                                        lines.add(TerminalStyle.MUTED + empty + TerminalStyle.RESET);
                                    } else {
                                        for (int index = selector1.visibleStart; index < end; index++) {
                                            String detail;
                                            if (selector1.view == McpSelector.View.SERVERS) {
                                                McpServerStatus status = selector1.manager.mcpStatus(selector1.filtered.get(index));
                                                detail = switch (status.state) {
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
                                                };
                                            } else {
                                                McpToolStatus status1 = selector1.filteredTools.get(index);
                                                detail = (status1.enabled ? "✓ " : "○ ") + status1.name + "  " + (status1.enabled ? "Enabled" : "Disabled");
                                            }
                                            String row = (index == selector1.selectedIndex ? "> " : "  ") + detail;
                                            row = truncatePlain(row, frame.width);
                                            lines.add(index == selector1.selectedIndex ? TerminalStyle.HEADING + row + TerminalStyle.RESET : row);
                                        }
                                        if (selector1.visibleStart > 0 || end < itemCount) {
                                            lines.add(TerminalStyle.MUTED + "  " + (selector1.selectedIndex + 1) + "/" + itemCount + TerminalStyle.RESET);
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
                                        String style = selector1.changeError == null ? TerminalStyle.MUTED : warningStatus();
                                        lines.add(style + truncatePlain("  " + detail, frame.width) + TerminalStyle.RESET);
                                    }
                                    if (selector1.view == McpSelector.View.SERVERS && !selector1.filtered.isEmpty()) {
                                        McpServerStatus selected =
                                                selector1.manager.mcpStatus(selector1.filtered.get(selector1.selectedIndex));
                                        if (selected.authorizationUrl != null) {
                                            String label = truncatePlain("Open: " + selected.authorizationUrl, Math.max(1, frame.width - 2));
                                            String safeUrl = selected.authorizationUrl.replace("\u001b", "").replace("\u0007", "");
                                            String link = "\u001b]8;;" + safeUrl + "\u001b\\" + label + "\u001b]8;;\u001b\\";
                                            lines.add(TerminalStyle.MUTED + "  " + link + TerminalStyle.RESET);
                                        }
                                    }
                                    String hint = selector1.view == McpSelector.View.SERVERS
                                            ? "Type to filter  Up/Down move  Enter toggle/auth/retry  Tab tools  Esc close"
                                            : "Type to filter  Up/Down move  Enter toggle  Tab/Esc servers";
                                    lines.add(TerminalStyle.MUTED + truncatePlain(hint, frame.width) + TerminalStyle.RESET);
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
                                                            String name = selector1.filtered.get(selector1.selectedIndex);
                                                            boolean enabled = selector1.manager.toggleMcpServer(name);
                                                            notifyMcpSelectorChange(selector1, new McpSelector.Change(name, null, enabled));
                                                        }
                                                    } else {
                                                        refreshMcpSelectorTools(selector1);
                                                        if (!selector1.filteredTools.isEmpty()) {
                                                            try {
                                                                McpToolStatus status = selector1.manager.toggleMcpTool(
                                                                        selector1.toolServer, selector1.filteredTools.get(selector1.selectedIndex).name);
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
                case SETTINGS -> {
                    if (!agentConfigured) {
                        println("No model is configured.");
                    } else {
                        this.select("Settings", List.of(new SelectItem<>(
                                        "thinking",
                                        "Thinking level",
                                        runtime.state().thinkingLevel().wire,
                                        "Thinking level " + runtime.state().thinkingLevel().wire)), 0, false);
                        List<ThinkingLevel> levels = getSupportedThinkingLevels(runtime.state().model());
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
                        int currentIndex = Math.max(0, levels.indexOf(runtime.state().thinkingLevel()));
                        ThinkingLevel level = select("Thinking level", items, currentIndex, false);
                        if (level != null) {
                            runtime.setThinkingLevel(level);
                            refreshShellStatus();
                            this.settings = new Settings(
                                    this.settings.defaultProvider, this.settings.defaultModel, level, this.settings.hideThinkingBlock);
                            try {
                                runtime.saveThinkingLevel(level);
                                println("Thinking level: " + level.wire);
                            } catch (IOException error) {
                                println("Thinking level changed for this session, but could not be saved: " + error.getMessage());
                            }
                        }
                    }
                }
                case COMPACT -> {
                    if (!agentConfigured) {
                        println("No model is configured.");
                        break;
                    }
                    try {
                        String target = selectedAgent;
                        runtime.subagents().compact(target).whenComplete((answer, error) -> shellNotifications.add(() -> {
                            if (!target.equals(selectedAgent)) return;
                            println(error == null ? answer.finalAnswer() : "Error: " + error.getMessage());
                            refreshShellStatus();
                        }));
                    } catch (IllegalStateException error) {
                        println("Error: " + error.getMessage());
                    }
                }
                }
        } finally {
            setShellActivity(!agentConfigured
                    ? noModelActivity(System.nanoTime())
                    : agentActivities.getOrDefault(selectedAgent, readyActivity(System.nanoTime())));
            refreshShellStatus();
        }
        return false;
    }

    private static String cliArgumentValue(String[] args, int index, String flag) {
        if (index >= args.length || args[index].startsWith("-")) {
            throw new IllegalArgumentException(flag + " requires a value");
        }
        return args[index];
    }

    private static String cliAliasesValue(String[] args, int index) {
        String aliases = cliArgumentValue(args, index, "--aliases");
        if (aliases.length() < 2 || aliases.charAt(0) != ':') {
            throw new IllegalArgumentException("--aliases requires a non-empty colon-prefixed alias string such as :dev:reporting");
        }
        return aliases;
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
        for (Model candidate : providerModels(runtime.requireCoreProvider(provider))) {
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
            Model model, List<Message> messages, boolean hideThinking) {
        StringBuilder screen = new StringBuilder(sessionScreenHeader(model));
        Map<String, ToolResultMessage> toolResults = new LinkedHashMap<>();
        for (Message message : messages) {
            if (message instanceof ToolResultMessage result) toolResults.put(result.toolCallId, result);
        }
        Set<String> renderedToolResults = new HashSet<>();
        for (Message message : messages) {
            switch (message) {
                case UserMessage user -> screen.append('\n')
                        .append(promptArea("> " + terminalSafeText(text(user))))
                        .append('\n');
                case AssistantMessage assistant -> {
                    for (AssistantContent content : assistant.content) {
                        if (content instanceof ThinkingContent thinking) {
                            if (!hideThinking && !thinking.thinking.isBlank()) {
                                screen.append("\n").append(TerminalStyle.MUTED).append("Thinking:").append(TerminalStyle.RESET).append('\n');
                                screen.append(TerminalStyle.MUTED).append(terminalSafeText(thinking.thinking)).append(TerminalStyle.RESET).append('\n');
                            }
                        } else if (content instanceof TextContent text) {
                            screen.append(terminalSafeText(text.text)).append('\n');
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
                        screen.append("Error: ").append(terminalSafeText(assistant.errorMessage)).append('\n');
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
                ? "Run /login to choose a provider. Shift-Tab toggles Plan/Build. Commands: /help, /plan, /build, /resume, /login, /mcp, /exit"
                : "Enter submits; Shift-Enter adds a newline; Esc interrupts. Shift-Tab toggles Plan/Build. Ctrl-O inspects steps; Ctrl-T toggles thinking. Commands: /help, /plan, /build, /subagents, /clear, /fork, /resume, /models, /mcp, /settings, /compact, /logout, /exit");
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

    private void configureShellModel(Model model, boolean persistModel) {
        IOException persistenceFailure = startFreshShellSession(model, cwd);
        if (persistenceFailure != null) {
            println("Model configured, but session persistence is unavailable: " + persistenceFailure.getMessage());
        }
        if (persistModel) {
            settings = withSettingsDefaultModel(settings, model.provider, model.id);
            try {
                runtime.setSettingsDefaultModelAndProvider(model.provider, model.id);
            } catch (IOException error) {
                println("Model changed for this session, but could not be saved: " + error.getMessage());
            }
        }
    }

    /**
     * Reconnects workspace-bound tools while preserving the active Main conversation in {@code directory}.
     *
     * <p>This deliberately changes codingagent's virtual workspace only; it does not change the parent
     * shell's working directory. Rebuilding the agent releases workspace-bound child runtimes, but the
     * Main conversation, task state, and recorder continue in the destination workspace.
     */
    private void changeShellWorkingDirectory(Path directory) throws IOException {
        if (directory.equals(cwd)) {
            println("Already using " + cwd + ".");
            return;
        }

        // MCP initialization carries the workspace to local server processes and remote servers, so a
        // workspace switch must replace the manager before the agent receives its new tool set.
        runtime.mcpLoadDefaultManager(directory);
        if (!agentConfigured) {
            cwd = directory;
            println("Changed working directory to " + cwd + ".");
            return;
        }

        Model model = runtime.state().model();
        List<Message> messages = runtime.state().messages();
        ObjectNode taskState = runtime.taskStateSnapshot();
        String existingSessionId = recordingSession ? runtime.state().sessionId() : null;
        String existingSessionName = sessionName;

        // configureAgent intentionally creates a fresh tool/runtime graph. Restore the conversational
        // state immediately afterwards instead of using startFreshShellSession, which creates a new JSONL file.
        configureShellAgent(model, directory, false, existingSessionName);
        runtime.restoreMessages(messages);
        runtime.restoreTaskState(taskState);

        if (existingSessionId != null) {
            try {
                runtime.moveSessionRecorderToWorkspace(existingSessionId, directory);
                runtime.setSessionRecording(true, this::reportCheckpointFailure);
                recordingSession = true;
            } catch (IOException | IllegalArgumentException error) {
                recordingSession = false;
                println("Session continues in memory, but its new workspace could not be saved: "
                        + error.getMessage());
            }
        }
        refreshShellStatus();
        println("Changed working directory to " + cwd + ". Continuing current session.");
    }

    /**
     * Resolves a {@code /cd} argument against codingagent's current virtual workspace.
     */
    static Path resolveShellWorkingDirectory(Path currentDirectory, String argument) {
        Path base = Objects.requireNonNull(currentDirectory, "currentDirectory").toAbsolutePath().normalize();
        if (argument == null || argument.isBlank()) {
            throw new IllegalArgumentException("directory must not be empty");
        }
        String text = argument.strip();
        if (text.equals("~") || text.startsWith("~/")
                || (System.getProperty("os.name", "").startsWith("Windows") && text.startsWith("~\\"))) {
            String home = System.getProperty("user.home");
            if (home == null || home.isBlank()) {
                throw new IllegalArgumentException("home directory is unavailable");
            }
            text = text.equals("~") ? home : Path.of(home).resolve(text.substring(2)).toString();
        } else if (text.startsWith("~")) {
            throw new IllegalArgumentException("~user paths are not supported; use an absolute path");
        }

        final Path candidate;
        try {
            candidate = Path.of(text);
        } catch (InvalidPathException error) {
            throw new IllegalArgumentException("invalid directory: " + argument, error);
        }
        Path resolved = (candidate.isAbsolute() ? candidate : base.resolve(candidate)).normalize();
        try {
            if (!Files.isDirectory(resolved)) {
                throw new IllegalArgumentException("not a directory: " + resolved);
            }
        } catch (SecurityException error) {
            throw new IllegalArgumentException("cannot access directory: " + resolved, error);
        }
        return resolved;
    }

    /**
     * Resets the active conversation and, unless disabled, records it in a new session file.
     * The returned failure is deliberately non-fatal: callers continue with an in-memory session.
     */
    private IOException startFreshShellSession(Model model, Path configuredCwd) {
        boolean recordingEnabled = false;
        IOException persistenceFailure = null;
        if (!noSession) {
            try {
                runtime.defaultSessionStore();
                runtime.createSessionRecorder(configuredCwd, model.provider, model.id);
                recordingEnabled = true;
            } catch (IOException error) {
                persistenceFailure = error;
            }
        }
        configureShellAgent(model, configuredCwd, recordingEnabled, null);
        return persistenceFailure;
    }

    private void configureShellAgent(Model model, Path configuredCwd, boolean recordingEnabled, String nextSessionName) {

        closeShellSubscription();
        runtime.configureAgent(model, configuredCwd, systemPrompt, apiKey, initialThinkingLevel(model, settings.defaultThinkingLevel));
        runtime.setSessionRecording(recordingEnabled, this::reportCheckpointFailure);
        selectedAgent = SubagentManager.MAIN;
        agentActivities.clear();
        restoredLiveTurn = false;
        shellEvents.clear();
        shellNotifications.clear();
        shellSubscription = runtime.subagents().subscribe(identified -> {
            agentActivities.compute(identified.agentId(), (id, current) -> activityAfterEvent(current, identified.event()));
            if (componentOpen || managedSuspend) { componentDirty = true; return; }
            if (identified.agentId().equals(selectedAgent)) shellEvents.add(identified);
        });
        cwd = configuredCwd.toAbsolutePath().normalize();
        agentConfigured = true;
        shellModelProvider = model.provider;
        recordingSession = recordingEnabled;
        sessionName = nextSessionName;
        if (activity.phase != ActivityStatus.Phase.RUNNING_COMMAND) {
            setShellActivity(readyActivity(System.nanoTime()));
        }
        refreshShellStatus();
    }

    private static ActivityStatus activityAfterEvent(ActivityStatus current, AgentEvent event) {
        long now = System.nanoTime();
        return switch (event) {
            case AgentEvent.AgentStart ignored -> activeActivity(ActivityStatus.Phase.WAITING_FOR_MODEL, now);
            case AgentEvent.TurnStart ignored -> activeActivity(ActivityStatus.Phase.WAITING_FOR_MODEL, now);
            case AgentEvent.AgentEnd ignored -> readyActivity(now);
            case AgentEvent.CompactionStart ignored -> activeActivity(ActivityStatus.Phase.COMPACTING, now);
            case AgentEvent.CompactionEnd ignored -> readyActivity(now);
            case AgentEvent.ToolExecutionStart tool -> activeActivity(ActivityStatus.Phase.RUNNING_TOOL, tool.toolName, now);
            case AgentEvent.AutoRetryStart retry -> retryingActivity(retry.attempt, retry.maxAttempts, retry.delayMs, now);
            case AgentEvent.MessageUpdate update -> switch (update.providerEvent) {
                case AssistantMessageEvent.ThinkingStart ignored -> activeActivity(ActivityStatus.Phase.REASONING, now);
                case AssistantMessageEvent.TextStart ignored -> activeActivity(ActivityStatus.Phase.RESPONDING, now);
                case AssistantMessageEvent.ToolCallStart ignored -> activeActivity(ActivityStatus.Phase.PREPARING_TOOL, now);
                default -> current == null ? readyActivity(now) : current;
            };
            default -> current == null ? readyActivity(now) : current;
        };
    }

    private void handleShellAgentEvent(AgentEvent event) {

            switch ((AgentEvent) event) {
                case AgentEvent.AgentStart ignored -> {
                    emittedText = false; streamOutput = StreamOutput.NONE; streamedThinkingCharacters = 0;
                    setShellActivity(activeActivity(ActivityStatus.Phase.WAITING_FOR_MODEL, System.nanoTime()));
                }
                case AgentEvent.AgentEnd ignored -> setShellActivity(readyActivity(System.nanoTime()));
                case AgentEvent.InstructionLoaded loaded -> {
                    finishShellStreamOutput();
                    println(instructionLoadedMessage(loaded.path));
                }
                case AgentEvent.CompactionStart ignored -> setShellActivity(activeActivity(ActivityStatus.Phase.COMPACTING, System.nanoTime()));
                case AgentEvent.CompactionEnd end -> {
                    if (agentConfigured && selectedState().streaming()) {
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
                    switch (update.providerEvent) {
                        case AssistantMessageEvent.ThinkingStart ignored -> {
                            if (!this.hideThinkingBlock) {
                                finishShellStreamOutput();
                                print("\n" + TerminalStyle.MUTED + "Thinking:" + TerminalStyle.RESET + "\n");
                                this.streamOutput = StreamOutput.THINKING;
                                this.streamedThinkingCharacters = 0;
                            }
                        }
                        case AssistantMessageEvent.ThinkingDelta delta -> {
                            if (!this.hideThinkingBlock) {
                                if (this.streamOutput != StreamOutput.THINKING) {
                                    print("\n" + TerminalStyle.MUTED + "Thinking:" + TerminalStyle.RESET + "\n");
                                    this.streamOutput = StreamOutput.THINKING;
                                    this.streamedThinkingCharacters = 0;
                                }
                                print(TerminalStyle.MUTED + terminalSafeText(delta.delta) + TerminalStyle.RESET);
                                this.streamedThinkingCharacters += delta.delta.length();
                            }
                        }
                        case AssistantMessageEvent.ThinkingEnd end -> {
                            if (!this.hideThinkingBlock
                                    && this.streamOutput == StreamOutput.THINKING) {
                                if (this.streamedThinkingCharacters == 0 && !end.content.isBlank()) {
                                    print(TerminalStyle.MUTED + terminalSafeText(end.content) + TerminalStyle.RESET);
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
                            print(terminalSafeText(delta.delta));
                            this.emittedText = true;
                        }
                        case AssistantMessageEvent.TextEnd ignored -> finishShellStreamOutput();
                        default -> {
                            // Tool-call argument streaming is rendered once execution starts.
                        }
                    }
                }
                case AgentEvent.MessageEnd end -> {
                    if (end.message instanceof AssistantMessage assistant) {
                        String finalOutput = finalAssistantOutput(assistant, emittedText);
                        if (finalOutput != null) println(terminalSafeText(finalOutput));
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
    }

    private void closeShellSubscription() {
        AutoCloseable subscription = shellSubscription;
        shellSubscription = null;
        if (subscription == null) return;
        try {
            subscription.close();
        } catch (Exception error) {
            throw new IllegalStateException("Could not close shell event subscription", error);
        }
    }

    /**
     * Updates cached workspace/model details, then redraws the live activity status.
     */
    private void refreshShellStatus() {
        AgentSnapshot state = selectedState();
        String branch = gitBranch(cwd);
        statusLocation = displayPath(Path.of(System.getProperty("user.home", "")), cwd)
                + (branch == null ? "" : " [" + branch + "]")
                + (sessionName == null ? "" : " \u2022 " + sessionName)
                + (!agentConfigured ? "" : " \u2022 " + runtime.subagents().snapshot(selectedAgent).name()
                    + " (" + runtime.subagents().snapshot(selectedAgent).queued() + " queued)");
        statusModel = !agentConfigured
                ? ""
                : modelStatus(
                state.model(),
                state.thinkingLevel(),
                contextTokens(state.messages()));
        renderShellStatus();
    }

    private void renderShellStatus() {
        if (componentOpen || managedSuspend) return;
        ActivityStatus current = activity;
        boolean waiting = runtime.questions().pending().stream()
                .anyMatch(question -> question.agentId().equals(selectedAgent) || SubagentManager.MAIN.equals(selectedAgent));
        String label = waiting ? "Waiting for answer" : activityLabel(current, System.nanoTime());
        setStatus(label + " [" + runtime.agentMode().label + "]", waiting ? StatusAccent.ACTIVE : activityAccent(current), statusLocation, statusModel);
    }

    private void changeAgentMode(AgentMode mode, boolean editorActive) {
        String notice;
        try {
            if (agentConfigured && !SubagentManager.MAIN.equals(selectedAgent))
                throw new IllegalStateException("Select Main before changing mode.");
            runtime.setAgentMode(mode);
            notice = mode.label + " mode selected."
                    + (mode == AgentMode.BUILD ? " Send a prompt to request implementation." : " Explore and refine a plan before implementation.");
        } catch (IllegalStateException error) { notice = error.getMessage(); }
        if (editorActive) printAbove(notice); else println(notice);
        refreshShellStatus();
    }

    private void showPendingQuestions() {
        if (componentOpen || managedSuspend || !commandSuggestionsActive) return;
        if (runtime.questions().pending().isEmpty()) return;
        for (var question : runtime.questions().pending()) {
            if (!runtime.questions().contains(question.questionId())) continue;
            String name = question.agentId();
            if (agentConfigured) name = runtime.subagents().snapshot(question.agentId()).name();
            try {
                var component = new QuestionComponent(question, name, () -> runtime.questions().contains(question.questionId()));
                var answer = runComponent(component.component());
                if (!runtime.questions().contains(question.questionId())) continue;
                if ("answered".equals(answer.status())) runtime.questions().answer(question.questionId(), answer.answer());
                else runtime.questions().decline(question.questionId());
            } catch (IOException | IllegalArgumentException error) {
                if (runtime.questions().contains(question.questionId())) runtime.questions().decline(question.questionId());
                printAbove("Question could not be completed: " + error.getMessage());
            }
        }
        refreshShellStatus();
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
        if (!agentConfigured || runtime.state().streaming()) return;
        runtime.syncMcpTools();
    }

    private List<Model> shellSelectableModels() throws IOException {
        if (shellModelProvider == null) return List.of();
        if (!shellModelProvider.equals(GITHUB_COPILOT_PROVIDER_ID)) {
            return providerModels(runtime.requireCoreProvider(shellModelProvider));
        }
        ProviderState copilot = (ProviderState) runtime.requireCoreProvider(GITHUB_COPILOT_PROVIDER_ID);
        if (!runtime.gitHubCopilotHasCredential(copilot)) return List.of();
        if (!isAnthropicProxyConfigured()) {
            try {
                println("Refreshing GitHub Copilot models...");
                return runtime.gitHubCopilotEnableAndRefreshModels(copilot).models;
            } catch (IOException error) {
                println("Could not refresh GitHub Copilot model access: " + error.getMessage());
            }
        }
        // Preserve known entitlements without HTTP traffic or exposing the entire catalog.
        return runtime.gitHubCopilotCachedAvailableModels(copilot);
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
                turnDetailsForLatestTurn(selectedState().messages(), hideThinkingBlock);
        if (details == null) {
            showShellShortcutStatus("The latest turn has no reasoning or tool details.", lineEditorActive);
            return;
        }
        try {
            boolean hiddenAfter = runComponent(new TuiComponent<>(
                    frame -> renderTurnDetails(details, frame.width, frame.height),
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
                hidden);
        String status = "Thinking blocks: " + (hidden ? "hidden" : "visible");
        try {
            runtime.saveHideThinkingBlock(hidden);
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
        return com.quaxt.codingagent.cli.tools.LocalTools.describeCall(toolName, arguments)
                .map(CodingAgentCli::terminalSafeText)
                .orElseGet(() -> abbreviateShellText(arguments.toString(), 240));
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

    private static String abbreviateShellText(String value, int maximumLength) {
        String normalized = terminalSafeText(value.replaceAll("\\s+", " ")).replaceAll("\\s+", " ").trim();
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

        runtime.configureAgent(model, Path.of("."), systemPrompt, apiKey, ThinkingLevel.OFF);
        runtime.subscribe(event -> {
            ObjectNode node = encodeAgentEvent(event, true);
            if (node != null) outputRpc(node);
        });
        if (!noSession) {
            runtime.defaultSessionStore();
            runtime.createSessionRecorder(Path.of("."), model.provider, model.id);
        }
        runtime.setSessionRecording(!noSession, this::reportCheckpointFailure);
    }

    private java.util.concurrent.CompletableFuture<Void> submitRpcCompaction(String id, String instructions) throws Exception {
        var result = new java.util.concurrent.atomic.AtomicReference<CompactionResult>();
        AutoCloseable listener = runtime.subscribe(event -> {
            if (event instanceof AgentEvent.CompactionEnd end) result.set(end.result);
        });
        try {
            return runtime.subagents().compact(SubagentManager.MAIN, instructions).handle((answer, failure) -> {
                try { listener.close(); } catch (Exception ignored) { }
                respondRpc(id, "compact", failure == null, failure == null ? Json.MAPPER.valueToTree(result.get()) : null,
                        rpcFailure(failure));
                return null;
            });
        } catch (RuntimeException error) { listener.close(); throw error; }
    }

    private static String rpcFailure(Throwable failure) {
        return failure == null ? null : failure.getMessage() == null ? failure.toString() : failure.getMessage();
    }

    private static ObjectNode questionRequestJson(QuestionBroker.Request request) {
        ObjectNode node = jsonObject().put("questionId", request.questionId()).put("agentId", request.agentId())
                .put("question", request.question());
        var options = node.putArray("options");
        for (var option : request.options()) options.addObject().put("label", option.label()).put("description", option.description());
        return node;
    }

    private static ObjectNode questionEventJson(QuestionBroker.Event event) {
        ObjectNode node = questionRequestJson(event.request()).put("type", event.type());
        if (event.answer() != null) {
            node.put("status", event.answer().status());
            if (event.answer().answer() != null) node.put("answer", event.answer().answer());
        }
        return node;
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
        List<McpToolStatus> tools = selector.manager.mcpToolStatuses(selector.toolServer);
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
            TurnDetailsComponent details, int width, int height) {
        int safeWidth = Math.max(20, width);
        details.viewportHeight = Math.max(
                1, height - TurnDetailsComponent.HEADER_LINES - TurnDetailsComponent.FOOTER_LINES);
        List<TurnDetailsComponent.RenderedLine> lines1 = new ArrayList<>();
        for (int index1 = 0; index1 < details.sections.size(); index1++) {
            TurnDetailsComponent.Section section = details.sections.get(index1);
            String marker = section.expanded ? "▼ " : "▶ ";
            String suffix = section.expanded || section.summary.isBlank() ? "" : " — " + section.summary;
            String heading = truncatePlain(marker + section.title + suffix, safeWidth);
            if (index1 == details.selectedIndex) heading = TerminalStyle.HEADING + heading + TerminalStyle.RESET;
            else heading = TerminalStyle.STRONG + heading + TerminalStyle.RESET;
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
                        int codePointWidth = Math.max(0, Cells.width(codePoint));
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
                        rendered = TerminalStyle.MUTED + rendered + TerminalStyle.RESET;
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
        lines.add(TerminalStyle.HEADING + "Turn details" + TerminalStyle.RESET);
        lines.add("");
        details.visibleSectionsByRow.clear();
        int visibleEnd = Math.min(lines1.size(), details.scrollTop + details.viewportHeight);
        for (int index = details.scrollTop; index < visibleEnd; index++) {
            TurnDetailsComponent.RenderedLine line = lines1.get(index);
            if (line.heading) details.visibleSectionsByRow.put(lines.size(), line.sectionIndex);
            lines.add(line.text);
        }
        String hint = "Up/Down select  Enter expand/collapse  PgUp/PgDn scroll  Ctrl-T thinking  Ctrl-O tools  Esc close";
        lines.add(TerminalStyle.MUTED + truncatePlain(hint, safeWidth) + TerminalStyle.RESET);
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
        return terminalSafeText(value);
    }

    private void reportCheckpointFailure(IOException error) {
        String message = "Warning: session progress could not be saved for resume: " + error.getMessage();
        if (terminal == null) System.err.println(message);
        else shellNotifications.add(() -> println(message));
    }
}
