package com.quaxt.codingagent.cli.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.quaxt.codingagent.agent.AgentTool;
import com.quaxt.codingagent.agent.ToolDefinition;
import com.quaxt.codingagent.agent.ToolParameters;
import com.quaxt.codingagent.agent.ToolRegistry;
import com.quaxt.codingagent.agent.QuestionBroker;
import com.quaxt.codingagent.debug.DebugManager;
import com.quaxt.codingagent.debug.DebugTools;
import com.quaxt.codingagent.ai.types.TextContent;
import com.quaxt.codingagent.ai.util.AbortSignal;
import com.quaxt.codingagent.shell.ShellCommandLine;
import com.quaxt.codingagent.shell.ShellSessionManager;
import com.quaxt.codingagent.shell.PosixPreflight;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.ProviderNotFoundException;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static com.quaxt.codingagent.agent.ToolParameters.*;

/** Built-in tool definitions. Each factory keeps arguments, behavior, and presentation together. */
public final class LocalTools {
    private LocalTools() {}

    private static final Duration GIT_IGNORE_TIMEOUT = Duration.ofSeconds(30);
    private static final BuiltInTools.Shell SHELL = isWindowsHost() ? BuiltInTools.Shell.POWERSHELL : BuiltInTools.Shell.BASH;
    private static final ToolRegistry<Environment> REGISTRY = new ToolRegistry<>(
            List.of(read(), write(), edit(), shell(), runProcess(), shellInput(), preflightPosix(), grep(), find(), ls(), question(), taskState()));

    private record Environment(Path cwd, Consumer<Path> onPathAccess, String executable, ShellSessionManager shellSessions,
                               QuestionBroker questions, String agentId, TaskState taskState) {}
    private record Edit(String oldText, String newText) {}

    public static List<AgentTool> bind(Path cwd, Consumer<Path> onPathAccess, String gitExecutable, ShellSessionManager shellSessions) {
        return bind(cwd, onPathAccess, gitExecutable, shellSessions, new QuestionBroker(), "main");
    }

    public static List<AgentTool> bind(Path cwd, Consumer<Path> onPathAccess, String gitExecutable,
            ShellSessionManager shellSessions, QuestionBroker questions, String agentId) {
        return bind(cwd, onPathAccess, gitExecutable, shellSessions, questions, agentId, new TaskState(ignored -> {}));
    }

    public static List<AgentTool> bind(Path cwd, Consumer<Path> onPathAccess, String gitExecutable,
            ShellSessionManager shellSessions, QuestionBroker questions, String agentId, TaskState taskState) {
        return REGISTRY.bind(new Environment(cwd.toAbsolutePath().normalize(), Objects.requireNonNull(onPathAccess),
                Objects.requireNonNull(gitExecutable), Objects.requireNonNull(shellSessions),
                Objects.requireNonNull(questions), Objects.requireNonNull(agentId), Objects.requireNonNull(taskState)));
    }

    public static List<AgentTool> bind(Path cwd, Consumer<Path> onPathAccess, String gitExecutable,
            ShellSessionManager shellSessions, QuestionBroker questions, String agentId, TaskState taskState,
            DebugManager debugger) {
        List<AgentTool> tools = new ArrayList<>(bind(cwd, onPathAccess, gitExecutable, shellSessions, questions, agentId, taskState));
        tools.addAll(DebugTools.bind(debugger, cwd));
        return List.copyOf(tools);
    }

    public static Optional<String> describeCall(String name, ObjectNode arguments) {
        return REGISTRY.describeCall(name, arguments).or(() -> DebugTools.describeCall(name, arguments));
    }

    private static ToolDefinition<Environment> taskState() {
        var action = text("action", "add_task, update_task, remove_task, list, add_finding, remove_finding, add_constraint, or remove_constraint");
        var id = optionalText("id", "Existing ID for update/remove actions: task (#1), finding (F1), or constraint (C1). Omit for add/list actions; new IDs are assigned automatically.", null);
        var description = optionalText("description", "Task description; required for add_task and optional for update_task", null);
        var status = optionalText("status", "Task status for add_task/update_task: todo, in_progress, done, or cancelled", null);
        var note = optionalText("note", "Task note for add_task/update_task; an empty string clears it", null);
        var dependencies = optionalStringList("depends_on", "Task IDs for add_task/update_task; an empty list clears them");
        var entryText = optionalText("text", "Finding or constraint text; required for add_finding/add_constraint", null);
        var parameters = new ToolParameters(action, id, description, status, note, dependencies, entryText);
        parameters = parameters.withSchema(taskStateSchema(parameters.schema()));
        return new ToolDefinition<>("task_state",
                "Keep coarse per-agent tasks, findings, and constraints across turns, compaction, and saved sessions. "
                        + "Use meaningful tasks for difficult work; record discoveries and requirements; revise or cancel tasks as evidence changes. "
                        + "For add_task, supply description and omit id; the tool assigns the ID. For update/remove actions, use an existing ID. "
                        + "Consult list after compaction or resume and before declaring completion. A call after every action is unnecessary.",
                parameters,
                (local, args, invocation) -> toolResultText(local.taskState.apply(args.get(action), args.get(id),
                        args.get(description), args.get(status), args.get(note), args.get(dependencies),
                        invocation.arguments != null && invocation.arguments.hasNonNull("depends_on"), args.get(entryText))),
                raw -> "Task state: " + textArgument(raw, "action", "list"));
    }

    private static ObjectNode taskStateSchema(ObjectNode schema) {
        ObjectNode properties = (ObjectNode) schema.path("properties");
        enumValues((ObjectNode) properties.path("action"),
                "add_task", "update_task", "remove_task", "list",
                "add_finding", "remove_finding", "add_constraint", "remove_constraint");
        enumValues((ObjectNode) properties.path("status"), "todo", "in_progress", "done", "cancelled");
        ((ObjectNode) properties.path("id")).put("pattern", "^(?:#[1-9][0-9]*|F[1-9][0-9]*|C[1-9][0-9]*)$");
        ((ObjectNode) properties.path("description")).put("minLength", 1).put("pattern", "\\S");
        ((ObjectNode) properties.path("text")).put("minLength", 1).put("pattern", "\\S");
        ((ObjectNode) properties.path("depends_on")).put("uniqueItems", true);
        ((ObjectNode) properties.path("depends_on").path("items")).put("pattern", "^#[1-9][0-9]*$");

        ArrayNode alternatives = schema.putArray("oneOf");
        addTaskStateSchema(alternatives, properties, "add_task",
                List.of("action", "description", "status", "note", "depends_on"),
                List.of("action", "description"), null, List.of());
        addTaskStateSchema(alternatives, properties, "update_task",
                List.of("action", "id", "description", "status", "note", "depends_on"),
                List.of("action", "id"), "^#[1-9][0-9]*$",
                List.of("description", "status", "note", "depends_on"));
        addTaskStateSchema(alternatives, properties, "remove_task",
                List.of("action", "id"), List.of("action", "id"), "^#[1-9][0-9]*$", List.of());
        addTaskStateSchema(alternatives, properties, "list",
                List.of("action"), List.of("action"), null, List.of());
        addTaskStateSchema(alternatives, properties, "add_finding",
                List.of("action", "text"), List.of("action", "text"), null, List.of());
        addTaskStateSchema(alternatives, properties, "remove_finding",
                List.of("action", "id"), List.of("action", "id"), "^F[1-9][0-9]*$", List.of());
        addTaskStateSchema(alternatives, properties, "add_constraint",
                List.of("action", "text"), List.of("action", "text"), null, List.of());
        addTaskStateSchema(alternatives, properties, "remove_constraint",
                List.of("action", "id"), List.of("action", "id"), "^C[1-9][0-9]*$", List.of());
        return schema;
    }

    private static void addTaskStateSchema(ArrayNode alternatives, ObjectNode allProperties, String action,
            List<String> allowed, List<String> required, String idPattern, List<String> oneRequired) {
        ObjectNode alternative = alternatives.addObject().put("type", "object").put("additionalProperties", false);
        ObjectNode properties = alternative.putObject("properties");
        for (String field : allowed) properties.set(field, allProperties.path(field).deepCopy());
        enumValues((ObjectNode) properties.path("action"), action);
        if (idPattern != null) ((ObjectNode) properties.path("id")).put("pattern", idPattern);
        ArrayNode requiredFields = alternative.putArray("required");
        required.forEach(requiredFields::add);
        if (!oneRequired.isEmpty()) {
            ArrayNode anyOf = alternative.putArray("anyOf");
            for (String field : oneRequired) anyOf.addObject().putArray("required").add(field);
        }
    }

    private static void enumValues(ObjectNode schema, String... values) {
        ArrayNode choices = schema.putArray("enum");
        for (String value : values) choices.add(value);
    }

    private static ToolDefinition<Environment> question() {
        var question = text("question", "A concise question resolving material ambiguity or a user preference.");
        var label = text("label", "Suggested answer");
        var description = optionalText("description", "Tradeoff or explanation of this answer", "");
        var options = optionalList("options", "Optional suggested answers; the user can always enter a custom answer",
                new ToolParameters(label, description), args -> new QuestionBroker.Option(args.get(label), args.get(description)));
        return new ToolDefinition<>("question",
                "Ask the user for clarification and wait for their explicit answer. Use after exploration for ambiguity the workspace cannot resolve. "
                + "Supply meaningful choices when helpful. A declined or unavailable result is not an answer: retain the unresolved issue, "
                + "do not repeatedly call this tool, and present the question in your response. Never infer approval or change modes from an answer.",
                new ToolParameters(question, options), (local, args, invocation) -> {
                    var answer = local.questions.ask(local.agentId, args.get(question), args.get(options), invocation.signal);
                    var data = com.quaxt.codingagent.ai.json.Json.MAPPER.createObjectNode().put("status", answer.status());
                    if (answer.answer() != null) data.put("answer", answer.answer());
                    else data.put("question", args.get(question));
                    return new AgentTool.ToolResult(List.of(new TextContent(data.toString(), null)), data, false);
                }, raw -> "Asking: " + textArgument(raw, "question", ""));
    }

    private static ToolDefinition<Environment> read() {
        var pathArg = text("path", "Path to the file to read. A leading ~/ expands to the user home directory.");
        var offsetArg = integer("offset", "1-indexed starting line", 1, Integer.MAX_VALUE, 1);
        var limitArg = integer("limit", "Maximum lines to read", 1, Integer.MAX_VALUE, Integer.MAX_VALUE);
        return new ToolDefinition<>("read",
                "Read a text file. Use offset and limit for large files; output is bounded to 2,000 lines or 50KB. To read inside a jar/zip, append '!entry/path' to the archive path; 'archive.jar!' lists entries.",
                new ToolParameters(pathArg, offsetArg, limitArg),
                (local, args, invocation) -> {
                    AbortSignal signal = invocation.signal;
                    AgentTool.ToolResult result;
                    String pathText = args.get(pathArg);
                    BuiltInTools.ArchiveLocation location = localToolArchiveLocation(local, pathText);
                    if (location == null) {
                        result = readToolFile(localToolPath(local, pathText), args.get(offsetArg), args.get(limitArg), signal);
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
                                result = readToolFile(entry, args.get(offsetArg), args.get(limitArg), signal);
                            }
                        }
                    }
                    return result;
                }, raw -> {
                    int offset = raw.path("offset").asInt(1);
                    int limit = raw.path("limit").asInt();
                    return "Reading " + textArgument(raw, "path", ".")
                            + (limit > 0 ? " (lines " + offset + "-" + ((long) offset + limit - 1) + ")" : " (from line " + offset + ")");
                });
    }

    private static ToolDefinition<Environment> write() {
        var pathArg = text("path", "Path to write. A leading ~/ expands to the user home directory.");
        var contentArg = text("content", "File content");
        return new ToolDefinition<>("write",
                "Create or overwrite a text file, creating parent directories as needed.",
                new ToolParameters(pathArg, contentArg),
                (local, args, invocation) -> {
                    AbortSignal signal = invocation.signal;
                    String pathText = args.get(pathArg);
                    rejectArchivePath(local, pathText);
                    Path file = localToolPath(local, pathText);
                    String content = args.get(contentArg);
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
                }, raw -> "Writing " + textArgument(raw, "path", ".") + " (" + textArgument(raw, "content", "").length() + " characters)");
    }

    private static ToolDefinition<Environment> edit() {
        var pathArg = text("path", "Path to edit. A leading ~/ expands to the user home directory.");
        var oldTextArg = text("oldText", "Exact text to replace");
        var newTextArg = text("newText", "Replacement text");
        var editsArg = nonEmptyList("edits", "Exact replacements with oldText and newText",
                new ToolParameters(oldTextArg, newTextArg), item -> new Edit(item.get(oldTextArg), item.get(newTextArg)));
        return new ToolDefinition<>("edit",
                "Replace one or more unique, non-overlapping exact text blocks in a file.",
                new ToolParameters(pathArg, editsArg),
                (local, args, invocation) -> {
                    AbortSignal signal = invocation.signal;
                    String pathText = args.get(pathArg);
                    rejectArchivePath(local, pathText);
                    Path file = localToolPath(local, pathText);
                    List<Edit> edits = args.get(editsArg);
                    requireNotAborted(signal);
                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    List<BuiltInTools.Replacement> replacements = new ArrayList<>();
                    for (Edit edit : edits) {
                        String oldText = edit.oldText();
                        String newText = edit.newText();
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
                }, raw -> "Editing " + textArgument(raw, "path", ".") + " (" + raw.path("edits").size() + " replacement(s))");
    }

    private static ToolDefinition<Environment> shell() {
        var commandArg = text("command", "Shell command");
        var timeoutArg = optionalPositiveNumber("timeout", "Optional total process lifetime in seconds, including time waiting for the user; omitted means no deadline");
        var yieldMsArg = integer("yield_ms", "Wait before returning output in milliseconds; does not terminate the process", 0, 30000, 1000);
        return new ToolDefinition<>("shell",
                "Execute a " + ShellCommandLine.displayName(SHELL) + " command in the current working directory. Returns output with an explicit running or completion notice and the exit code when available. Includes a session ID if still running after yield_ms (default 1000). Use shell_input to poll or send stdin. When a script requests information you need from the user, ask them and end your turn, then send their reply to the same session; do not restart the script or repeatedly poll while awaiting the user. Sessions survive chat turns, but not conversation resets or application exit. Uses pipes, not a PTY; programs must flush prompts. Output is bounded to 2,000 lines or 50KB.",
                new ToolParameters(commandArg, timeoutArg, yieldMsArg),
                (local, args, invocation) -> {
                    AbortSignal signal = invocation.signal;
                    return local.shellSessions.execute(local.cwd, SHELL, args.get(commandArg), args.get(timeoutArg), args.get(yieldMsArg), () -> isAborted(signal));
                }, raw -> singleLine(textArgument(raw, "command", "")));
    }

    private static ToolDefinition<Environment> shellInput() {
        var sessionIdArg = text("session_id", "Session ID returned by shell");
        var inputArg = optionalText("input", "Exact text to write to stdin; include a trailing newline to submit a line (maximum 51200 characters)", null, ShellSessionManager.MAX_INPUT_CHARACTERS);
        var closeStdinArg = flag("close_stdin", "Close stdin after writing input, signalling EOF");
        var terminateArg = flag("terminate", "Terminate the process and its children; cannot be combined with input or close_stdin");
        var processTreeArg = flag("process_tree", "Include the process and its descendants in the status result");
        var yieldMsArg = integer("yield_ms", "Wait for new output in milliseconds", 0, 30000, 1000);
        return new ToolDefinition<>("shell_input",
                "Continue a shell session. Omit input to poll for new output. To answer a script prompt, send the user's reply as input with a trailing newline. Input is written literally to stdin, never evaluated as a new shell command. Ask the user for missing information and wait for their next chat message before answering on their behalf. Completed sessions return final output with an explicit completion notice and the exit code when available, then are removed.",
                new ToolParameters(sessionIdArg, inputArg, closeStdinArg, terminateArg, processTreeArg, yieldMsArg),
                (local, args, invocation) -> {
                    AbortSignal signal = invocation.signal;
                    return local.shellSessions.interact(args.get(sessionIdArg), args.get(inputArg), args.get(closeStdinArg), args.get(terminateArg), args.get(processTreeArg), args.get(yieldMsArg), () -> isAborted(signal));
                }, raw -> (raw.path("terminate").asBoolean() ? "Stopping command "
                        : raw.hasNonNull("input") ? "Sending input to command "
                        : raw.path("close_stdin").asBoolean() ? "Closing command input " : "Checking command ")
                        + textArgument(raw, "session_id", ""));
    }

    private static ToolDefinition<Environment> runProcess() {
        var executable = text("executable", "Native executable path or name found on the child PATH; Windows .bat/.cmd files are rejected");
        var arguments = stringList("arguments", "Exact argument strings passed directly to the executable, without shell parsing");
        var workingDirectory = optionalText("working_directory", "Child working directory (default: current workspace)", null);
        var environment = optionalStringMap("environment", "Environment values scoped to this child");
        var unsetEnvironment = optionalStringList("unset_environment", "Environment variable names removed from this child");
        var inheritEnvironment = flagWithDefault("inherit_environment", "Inherit parent environment before applying changes; false retains only Windows startup variables", true);
        var stdoutLog = optionalText("stdout_log", "File receiving complete stdout", null);
        var stderrLog = optionalText("stderr_log", "File receiving complete stderr", null);
        var timeout = optionalPositiveNumber("timeout", "Maximum process lifetime in seconds");
        var yieldMs = integer("yield_ms", "Wait before returning output in milliseconds", 0, 30000, 1000);
        return new ToolDefinition<>("run_process",
                "Run an executable directly with exact argument boundaries. Stdout and stderr are separate; exit_code is the child process's actual status. Sessions can be polled, sent stdin, or terminated with shell_input. Output is bounded; optional log files retain complete text.",
                new ToolParameters(executable, arguments, workingDirectory, environment, unsetEnvironment,
                        inheritEnvironment, stdoutLog, stderrLog, timeout, yieldMs),
                (local, args, invocation) -> local.shellSessions.executeProcess(
                        args.get(workingDirectory) == null ? local.cwd : localToolPath(local, args.get(workingDirectory)),
                        args.get(executable), args.get(arguments), args.get(environment), args.get(unsetEnvironment),
                        args.get(inheritEnvironment), args.get(stdoutLog) == null ? null : localToolPath(local, args.get(stdoutLog)),
                        args.get(stderrLog) == null ? null : localToolPath(local, args.get(stderrLog)),
                        args.get(timeout), args.get(yieldMs), () -> isAborted(invocation.signal)),
                raw -> "Running " + singleLine(textArgument(raw, "executable", "")));
    }

    private static ToolDefinition<Environment> preflightPosix() {
        var layer = text("layer", "Requested POSIX layer: msys2 or git-bash");
        var root = optionalText("installation_root", "Windows path to the requested installation root", null);
        var environment = optionalStringMap("environment", "Environment values for tool discovery and checks");
        var unsetEnvironment = optionalStringList("unset_environment", "Environment names removed for this check");
        var inheritEnvironment = flagWithDefault("inherit_environment", "Inherit parent environment before applying changes", true);
        return new ToolDefinition<>("preflight_posix",
                "Check a Windows MSYS2 or Git Bash build environment. Reports tool origins, Windows/POSIX paths, compiler, temp write access, missing tools and MSYS2 package hints, and Visual Studio toolsets found with vswhere. Makes no installations.",
                new ToolParameters(layer, root, environment, unsetEnvironment, inheritEnvironment),
                (local, args, invocation) -> PosixPreflight.inspect(local.cwd, args.get(layer), args.get(root),
                        args.get(environment), args.get(unsetEnvironment), args.get(inheritEnvironment)),
                raw -> "Checking " + textArgument(raw, "layer", "") + " POSIX environment");
    }

    private static ToolDefinition<Environment> grep() {
        var patternArg = text("pattern", "Regular expression to search for, or literal text when literal is true");
        var pathArg = optionalText("path", "Literal file or directory to search (default: current directory); wildcards are not expanded. A leading ~/ expands to the user home directory.", ".");
        var globArg = optionalText("glob", "Glob file filter relative to path, for example '*.java' or 'src/**/*.java'; patterns without a slash match file names at any depth", null);
        var ignoreCaseArg = flag("ignoreCase", "Case insensitive");
        var literalArg = flag("literal", "Treat pattern literally");
        var includeIgnoredArg = flag("includeIgnored", "Search files ignored by git");
        var contextArg = integer("context", "Lines before and after matches", 0, Integer.MAX_VALUE, 0);
        var limitArg = integer("limit", "Maximum matches", 1, Integer.MAX_VALUE, BuiltInTools.DEFAULT_GREP_LIMIT);
        return new ToolDefinition<>("grep",
                "Search text files beneath a literal file or directory. Use glob, not path, to filter file names. Files ignored by git are skipped; set includeIgnored to search them. Returns paths and line numbers, respecting the result limit. For symbol definitions and call sites in indexed repositories, a code-lens context/query tool (when connected) is usually faster and resolves aliases.",
                new ToolParameters(patternArg, pathArg, globArg, ignoreCaseArg, literalArg, includeIgnoredArg, contextArg, limitArg),
                (local, args, invocation) -> {
                    AbortSignal signal = invocation.signal;
                    String patternText = args.get(patternArg);
                    boolean literal = args.get(literalArg);
                    int flags = args.get(ignoreCaseArg) ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
                    Pattern pattern = Pattern.compile(literal ? Pattern.quote(patternText) : patternText, flags);
                    String pathText = args.get(pathArg);
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
                    int limit = args.get(limitArg);
                    String glob = args.get(globArg);
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
                    List<Path> files = filesUnder(local, root, args.get(includeIgnoredArg), signal);
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
                        return toolResultText(boundToolOutput(
                                output.toString(), matches >= limit ? "[" + limit + " matches limit reached]" : null));
                    } else if (glob != null && filesSearched == 0 && filesConsidered > 0) {
                        return toolResultText("No files matched glob '" + glob + "' (" + filesConsidered + " files under "
                                + root + " were considered). The glob is matched against paths relative to path; check the directory prefix.");
                    } else if (glob != null) {
                        return toolResultText("No matches found in " + filesSearched + " files matching glob '" + glob + "'");
                    } else {
                        return toolResultText("No matches found in " + filesSearched + " files");
                    }
                }, raw -> "Searching for " + textArgument(raw, "pattern", "") + " in " + textArgument(raw, "path", "."));
    }

    private static ToolDefinition<Environment> find() {
        var patternArg = text("pattern", "Glob pattern");
        var pathArg = optionalText("path", "Directory to search. A leading ~/ expands to the user home directory.", ".");
        var includeIgnoredArg = flag("includeIgnored", "Search files ignored by git");
        var limitArg = integer("limit", "Maximum results", 1, Integer.MAX_VALUE, BuiltInTools.DEFAULT_FIND_LIMIT);
        return new ToolDefinition<>("find",
                "Find files by glob pattern. Hidden files are included; .git and node_modules are skipped. Files ignored by git are skipped; set includeIgnored to search them.",
                new ToolParameters(patternArg, pathArg, includeIgnoredArg, limitArg),
                (local, args, invocation) -> {
                    AbortSignal signal = invocation.signal;
                    AgentTool.ToolResult result;
                    String pattern = args.get(patternArg);
                    Path root = localToolPath(local, args.get(pathArg));
                    if (!Files.isDirectory(root)) {
                        throw new IllegalArgumentException("Not a directory: " + root);
                    }
                    int limit = args.get(limitArg);
                    var matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
                    List<Path> candidates =
                            filesUnder(local, root, args.get(includeIgnoredArg), signal);
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
                    return result;
                }, raw -> "Finding " + textArgument(raw, "pattern", "") + " in " + textArgument(raw, "path", "."));
    }

    private static ToolDefinition<Environment> ls() {
        var pathArg = optionalText("path", "Directory to list. A leading ~/ expands to the user home directory.", ".");
        var limitArg = integer("limit", "Maximum entries", 1, Integer.MAX_VALUE, BuiltInTools.DEFAULT_LS_LIMIT);
        return new ToolDefinition<>("ls",
                "List a directory's contents, with a slash suffix on directories.",
                new ToolParameters(pathArg, limitArg),
                (local, args, invocation) -> {
                    AbortSignal signal = invocation.signal;
                    AgentTool.ToolResult result;
                    Path directory = localToolPath(local, args.get(pathArg));
                    if (!Files.isDirectory(directory)) {
                        throw new IllegalArgumentException("Not a directory: " + directory);
                    }
                    int limit = args.get(limitArg);
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
                    return result;
                }, raw -> "Listing " + textArgument(raw, "path", "."));
    }
    private static AgentTool.ToolResult readToolFile(Path file, int offset, int limit, AbortSignal signal)
            throws IOException {
        requireNotAborted(signal);
        if (!Files.isRegularFile(file)) {
            if (Files.exists(file)) {
                throw new IOException("Not a regular file: " + file);
            }
            try {
                file.getParent().toFile().canRead();
            } catch (SecurityException ignored) {
                throw new IOException("Access denied: " + file + " (the file may be outside the sandbox)");
            }
            throw new IOException("File not found: " + file);
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (java.nio.file.AccessDeniedException denied) {
            throw new IOException("Access denied: " + file + " (the file may be outside the sandbox)");
        }
        int start = offset - 1;
        if (start >= lines.size()) {
            throw new IllegalArgumentException(
                    "offset " + offset + " is beyond end of file (" + lines.size() + " lines)");
        }
        int end = start + Math.min(lines.size() - start, limit);
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
    private static Path localToolPath(Environment tool, String value) {
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

    private static BuiltInTools.ArchiveLocation localToolArchiveLocation(Environment tool, String value) {
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

    private static void rejectArchivePath(Environment tool, String value) {
        if (localToolArchiveLocation(tool, value) != null) {
            throw new IllegalArgumentException("archives are read-only through this tool");
        }
    }

    private static void requireNotAborted(AbortSignal signal) {
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

    private static List<Path> filesUnder(Environment local, Path root, boolean includeIgnored, AbortSignal signal)
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
                        local.executable, "-C", workingDirectory.toString(), "check-ignore", "--stdin", "-z")
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

    private static void joinThreads(Thread... threads) throws InterruptedException {
        for (Thread thread : threads) thread.join();
    }

    private static AgentTool.ToolResult toolResultText(String text) {
        return new AgentTool.ToolResult(List.of(new TextContent(text, null)), null, false);
    }

    private static boolean isAborted(AbortSignal signal) {
        synchronized (signal) { return signal.aborted; }
    }

    private static String textArgument(ObjectNode arguments, String name, String fallback) {
        JsonNode value = arguments.get(name);
        return value != null && value.isTextual() ? value.asText() : fallback;
    }

    /** Joins whitespace runs, including line breaks, so a description reads as one line. */
    private static String singleLine(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }
}
