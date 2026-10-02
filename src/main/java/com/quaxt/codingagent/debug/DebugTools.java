package com.quaxt.codingagent.debug;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.quaxt.codingagent.agent.AgentTool;
import com.quaxt.codingagent.agent.ToolDefinition;
import com.quaxt.codingagent.agent.ToolParameters;
import com.quaxt.codingagent.agent.ToolRegistry;
import com.quaxt.codingagent.ai.json.Json;
import com.quaxt.codingagent.ai.types.TextContent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.quaxt.codingagent.agent.ToolParameters.*;

/** Standalone model-facing bindings for the structured debugger operations. */
public final class DebugTools {
    private DebugTools() {}

    private record Context(DebugManager manager, Path cwd) {}

    private static final ToolRegistry<Context> REGISTRY = new ToolRegistry<>(List.of(
            tool("debug_launch", "Launch one Java main class or selected test under the debugger; requires main_class or test_selector.",
                    "main_class module test_selector working_directory arguments args jvm_options environment classpath module_path source_roots stop_on_entry capture_output launch_timeout_ms idempotency_key"),
            tool("debug_attach", "Attach to an explicitly authorized JVM by PID or unambiguous target selector.",
                    "pid target_selector consent source_roots path_mappings idempotency_key"),
            tool("debug_sessions", "List owned debug sessions with bounded paging.", "limit cursor"),
            tool("debug_status", "Inspect a debug session, including its current stop and target state.", "session_id"),
            tool("debug_detach", "Detach from a session; use leave_running: true or terminate: true with leave_running: false for an owned launched target. close_session releases its retained status/output.",
                    "session_id leave_running terminate close_session idempotency_key"),
            tool("debug_breakpoints", "Manage breakpoints: omit action to list (limit/cursor); add with breakpoints specs; update with breakpoint_id and only changed fields in each spec; enable/disable/remove with breakpoint_ids. Reports resolved and pending locations.",
                    "session_id action breakpoint_id breakpoint_ids breakpoints source_path line class_name method_name signature type exception_class caught uncaught field_name access modification condition hit_count hit_policy thread_id one_shot suspension_policy log_expression enabled limit cursor idempotency_key"),
            tool("debug_continue", "Resume from the current stop and wait for stop/exit up to wait_ms (0-30000, default 1000). Wait expiry leaves the JVM running.",
                    "session_id stop_id thread_id wait_ms idempotency_key"),
            tool("debug_step", "Step into, over, or out on a selected thread and wait for stop/exit up to wait_ms (0-30000, default 1000). Wait expiry leaves the JVM running.",
                    "session_id stop_id direction thread_id skip_filters wait_ms idempotency_key"),
            tool("debug_run_to", "Run to a precise one-shot loaded source path/line location and wait for stop/exit up to wait_ms (0-30000, default 1000). Wait expiry leaves the JVM running.",
                    "session_id stop_id source_path line class_name method_name signature thread_id wait_ms idempotency_key"),
            tool("debug_pause", "Request a stop in a running target without implicitly resuming it.",
                    "session_id thread_id wait_ms idempotency_key"),
            tool("debug_wait", "Wait a bounded time for a stop or other event without changing execution.",
                    "session_id wait_ms cursor thread_id event_types"),
            tool("debug_events", "Read ordered debugger events since a cursor.",
                    "session_id cursor limit thread_id event_types"),
            tool("debug_threads", "Inspect platform and virtual threads at a stop with bounded filters.",
                    "session_id stop_id thread_id filter state limit cursor"),
            tool("debug_stack", "Inspect a stopped thread's frames with paging and library-frame filtering.",
                    "session_id stop_id thread_id filter include_library_frames include_arguments limit cursor"),
            tool("debug_variables", "Inspect arguments, locals, this, and captured values in a stopped frame.",
                    "session_id stop_id thread_id frame_id filter include_sensitive_fields limit cursor"),
            tool("debug_object", "Expand a stop-scoped object or value reference with bounded depth and paging.",
                    "session_id stop_id thread_id frame_id reference object_id include_inherited include_static include_sensitive_fields start length depth limit cursor"),
            tool("debug_source", "Read a bounded source window for a stopped frame or precise location.",
                    "session_id stop_id thread_id frame_id source_path line before after"),
            tool("debug_exception", "Inspect the exception at a stop and its bounded cause and suppressed chain.",
                    "session_id stop_id thread_id depth limit cursor suppressed_cursor"),
            tool("debug_evaluate", "Evaluate exactly one of expression or expressions (1-20 strings, each at most 256 characters) in a stopped frame; batch results retain input order. Read-only literals, field paths, array indexing/length, and comparisons only; no method calls or mutation. timeout_ms is an optional cooperative scheduling budget, not an interrupt for in-flight JDI reads.",
                    "session_id stop_id thread_id frame_id expression expressions allow_side_effects timeout_ms idempotency_key"),
            tool("debug_output", "Retrieve bounded target stdout and stderr since a cursor, including after exit.",
                    "session_id cursor stream suppress_output limit byte_limit line_limit")));

    public static List<AgentTool> bind(DebugManager manager, Path cwd) {
        return REGISTRY.bind(new Context(Objects.requireNonNull(manager), Objects.requireNonNull(cwd).toAbsolutePath().normalize()));
    }

    public static Optional<String> describeCall(String name, ObjectNode raw) {
        return REGISTRY.describeCall(name, raw);
    }

    private static ToolDefinition<Context> tool(String name, String description, String fields) {
        var parameters = new ArrayList<ToolParameters.Parameter<?>>();
        for (String field : fields.split(" ")) parameters.add(parameter(field));
        ToolParameters typed = new ToolParameters(parameters.toArray(ToolParameters.Parameter<?>[]::new));
        return new ToolDefinition<>(name, description, typed.withSchema(advertisedSchema(name, typed.schema())),
                (context, ignored, invocation) -> {
                    ObjectNode raw = invocation.arguments;
                    if (raw == null) raw = Json.MAPPER.createObjectNode();
                    ObjectNode json = context.manager().call(name, raw, context.cwd(), () -> {
                        synchronized (invocation.signal) { return invocation.signal.aborted; }
                    });
                    return new AgentTool.ToolResult(List.of(new TextContent(json.toString(), null)), json,
                            "error".equals(json.path("status").asText()));
                }, raw -> {
                    String session = raw == null ? "" : raw.path("session_id").asText("");
                    String action = raw == null ? "" : raw.path("action").asText("");
                    return name + (session.isBlank() ? "" : " (session " + session + ")")
                            + (action.isBlank() ? "" : ": " + action);
                });
    }

    private static ToolParameters.Parameter<?> parameter(String name) {
        return switch (name) {
            case "session_id" -> text(name, "Existing debug session ID.");
            case "stop_id" -> text(name, "Current stop ID; stale stops are rejected.");
            case "action" -> optionalText(name, "Breakpoint action; omitted means list.", "list");
            case "expression" -> optionalText(name, "Single read-only expression; provide this OR expressions. Strings compare by content; other objects by identity. No calls, assignment, or arithmetic.", null, 256);
            case "expressions" -> optionalStringList(name, "Batch of 1-20 read-only expressions, each at most 256 characters; provide this OR expression. Results retain input order.");
            case "main_class", "test_selector" -> optionalText(name, "Java entry point or single selected test (choose one).", null);
            case "cursor" -> integer(name, "Nonnegative paging offset or event/output cursor; omitted or 0 starts at the beginning.", 0, Integer.MAX_VALUE, 0);
            case "limit" -> integer(name, "Maximum items (1-100).", 1, 100, 20);
            case "wait_ms" -> integer(name, "Wait for stop/exit (or a matching event for debug_wait) up to 0-30000 milliseconds (default 1000). Expiry leaves the JVM running; 0 returns without waiting.", 0, 30000, 1000);
            case "timeout_ms" -> integer(name, "Optional cooperative scheduling budget in milliseconds (0-30000; default 0 means no extra wall-clock budget). Does not interrupt in-flight JDI reads.", 0, 30000, 0);
            case "before", "after" -> integer(name, "Context lines (0-30).", 0, 30, 3);
            case "depth" -> integer(name, "Maximum requested depth (1 is supported for object expansion).", 1, 8, 1);
            case "byte_limit" -> integer(name, "Maximum UTF-8 output bytes (1-51200).", 1, 51200, 51200);
            case "line_limit" -> integer(name, "Maximum output lines (1-2000).", 1, 2000, 2000);
            case "line" -> integer(name, "1-based source line; debug_source uses the stopped line when omitted or 0. Source breakpoints require a positive line.", 0, Integer.MAX_VALUE, 0);
            case "start", "length", "suppressed_cursor", "launch_timeout_ms", "pid", "hit_count" ->
                    integer(name, "Nonnegative count, 1-based line, PID, or timeout in milliseconds.", 0, Integer.MAX_VALUE, 0);
            case "capture_output", "leave_running", "caught", "uncaught", "enabled", "modification",
                    "include_library_frames", "include_inherited" -> flagWithDefault(name, "Boolean option (default true).", true);
            case "stop_on_entry", "consent", "terminate", "close_session", "one_shot", "access", "include_arguments",
                    "include_static", "include_sensitive_fields", "suppress_output", "allow_side_effects" -> flag(name, "Explicit boolean option.");
            case "arguments", "args", "jvm_options", "classpath", "module_path", "source_roots", "skip_filters", "event_types", "breakpoint_ids" ->
                    optionalStringList(name, "Optional list of values.");
            case "environment", "path_mappings" -> optionalStringMap(name, "String-to-string overrides or path mappings.");
            case "breakpoints" -> optionalList(name, "Breakpoint specifications for an atomic action.", breakpointParameters(), ignored -> ignored);
            default -> optionalText(name, "Optional debugger selector or setting.", null);
        };
    }

    /** Advertising is deliberately separate from decoding: legacy flattened breakpoint calls remain valid. */
    private static ObjectNode advertisedSchema(String tool, ObjectNode schema) {
        ObjectNode properties = (ObjectNode) schema.path("properties");
        addEnum(properties, "action", "list", "add", "update", "enable", "disable", "remove");
        addEnum(properties, "type", "source", "method_entry", "method_exit", "exception", "field");
        addEnum(properties, "direction", "into", "over", "out");
        addEnum(properties, "hit_policy", "after", "exact", "every");
        addEnum(properties, "suspension_policy", "all_threads", "event_thread");
        if (properties.has("action")) ((ObjectNode) properties.path("action")).put("default", "list");
        if (properties.has("direction")) ((ObjectNode) properties.path("direction")).put("default", "over");
        if (properties.has("event_types")) {
            enumChoices((ObjectNode) properties.path("event_types").path("items"), "launch", "attach", "detach",
                    "exit", "stop", "resume", "breakpoint_resolved", "breakpoint_pending", "breakpoint_error",
                    "breakpoint_change", "logpoint");
        }
        if (tool.equals("debug_breakpoints")) return breakpointSchema(schema);
        if (tool.equals("debug_evaluate")) {
            ObjectNode expressions = (ObjectNode) properties.path("expressions");
            expressions.put("minItems", 1).put("maxItems", 20);
            ((ObjectNode) expressions.path("items")).put("maxLength", 256);
            // No root-level combinators: Anthropic removes root oneOf/anyOf/allOf.
            schema.putObject("if").putArray("required").add("expression");
            schema.putObject("then").putObject("not").putArray("required").add("expressions");
            schema.putObject("else").putArray("required").add("expressions");
        }
        return schema;
    }

    private static ObjectNode breakpointSchema(ObjectNode legacy) {
        ObjectNode schema = Json.MAPPER.createObjectNode().put("type", "object").put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        for (String field : List.of("session_id", "action", "breakpoints", "breakpoint_ids", "limit", "cursor", "idempotency_key"))
            properties.set(field, legacy.path("properties").path(field).deepCopy());
        schema.putArray("required").add("session_id");
        ObjectNode specs = (ObjectNode) properties.path("breakpoints");
        specs.put("minItems", 1).put("maxItems", 64)
                .put("description", "Add: full location specs (type defaults to source). Update: breakpoint_id plus only changed fields; omitted fields stay unchanged.");
        ObjectNode items = (ObjectNode) specs.path("items");
        ObjectNode itemProperties = (ObjectNode) items.path("properties");
        addEnum(itemProperties, "type", "source", "method_entry", "method_exit", "exception", "field");
        addEnum(itemProperties, "hit_policy", "after", "exact", "every");
        addEnum(itemProperties, "suspension_policy", "all_threads", "event_thread");
        // Defaults belong to adds, not patches: models must not fill in omitted update fields.
        itemProperties.elements().forEachRemaining(property -> ((ObjectNode) property).remove("default"));
        ((ObjectNode) properties.path("breakpoint_ids")).put("minItems", 1).put("maxItems", 64)
                .put("description", "Existing IDs for enable, disable, or remove.");

        // One action decision followed by location variants, rather than action x type alternatives.
        schema.set("if", actionIs("add"));
        ObjectNode add = schema.putObject("then");
        add.putArray("required").add("breakpoints");
        var variants = add.putObject("properties").putObject("breakpoints").putObject("items").putArray("anyOf");
        ObjectNode source = breakpointVariant(new String[]{"source"}, "source_path", "line");
        ((ObjectNode) source.path("properties")).putObject("line").put("minimum", 1);
        variants.add(source); // type may be omitted only for source adds.
        variants.add(breakpointVariant(new String[]{"method_entry", "method_exit"}, "type", "class_name", "method_name"));
        variants.add(breakpointVariant(new String[]{"exception"}, "type")); // No class filter means all exceptions.
        variants.add(breakpointVariant(new String[]{"field"}, "type", "class_name", "field_name"));

        ObjectNode other = schema.putObject("else");
        other.set("if", actionIs("update"));
        ObjectNode update = other.putObject("then");
        update.putArray("required").add("breakpoints");
        update.putObject("properties").putObject("breakpoints").putObject("items")
                .putArray("required").add("breakpoint_id");
        ObjectNode ids = other.putObject("else");
        ids.set("if", actionIs("enable", "disable", "remove"));
        ids.putObject("then").putArray("required").add("breakpoint_ids");
        return schema;
    }

    private static ObjectNode actionIs(String... actions) {
        ObjectNode condition = Json.MAPPER.createObjectNode();
        condition.putArray("required").add("action");
        enumChoices(condition.putObject("properties").putObject("action"), actions);
        return condition;
    }

    private static ObjectNode breakpointVariant(String[] types, String... required) {
        ObjectNode variant = Json.MAPPER.createObjectNode();
        enumChoices(variant.putObject("properties").putObject("type"), types);
        var names = variant.putArray("required");
        for (String name : required) names.add(name);
        return variant;
    }

    private static void addEnum(ObjectNode properties, String name, String... choices) {
        if (properties.has(name)) enumChoices((ObjectNode) properties.path(name), choices);
    }

    private static void enumChoices(ObjectNode schema, String... choices) {
        var values = schema.putArray("enum");
        for (String choice : choices) values.add(choice);
    }

    private static ToolParameters breakpointParameters() {
        return new ToolParameters(
                optionalText("breakpoint_id", "Existing breakpoint ID.", null),
                optionalText("type", "Breakpoint type; omitted on add means source. On update, omitted fields stay unchanged.", null),
                optionalText("source_path", "Source path.", null),
                integer("line", "1-based source line.", 0, Integer.MAX_VALUE, 0),
                optionalText("class_name", "Declaring class.", null),
                optionalText("method_name", "Method name.", null),
                optionalText("signature", "Method signature for overloads.", null),
                optionalText("exception_class", "Exception class filter.", null),
                flagWithDefault("caught", "Stop on caught exceptions.", true),
                flagWithDefault("uncaught", "Stop on uncaught exceptions.", true),
                optionalText("field_name", "Field to watch.", null),
                flag("access", "Stop on field access."),
                flagWithDefault("modification", "Stop on field modification.", true),
                optionalText("condition", "Read-only condition unless explicitly authorized.", null),
                integer("hit_count", "Hit count.", 0, Integer.MAX_VALUE, 0),
                optionalText("hit_policy", "Hit policy.", null),
                optionalText("thread_id", "Thread filter.", null),
                flag("one_shot", "Remove after first hit."),
                optionalText("suspension_policy", "all_threads or event_thread.", null),
                optionalText("log_expression", "Expression to log instead of suspending.", null),
                flagWithDefault("enabled", "Whether enabled.", true));
    }
}
