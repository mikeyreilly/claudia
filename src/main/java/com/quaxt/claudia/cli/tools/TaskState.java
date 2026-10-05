package com.quaxt.claudia.cli.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.quaxt.claudia.ai.json.Json;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Per-agent working state, kept outside the conversation and checkpointed as a whole. */
public final class TaskState {
    @FunctionalInterface public interface Checkpoint { void write(ObjectNode snapshot) throws IOException; }

    private record Task(String description, String status, String note, List<String> dependsOn) {}
    private static final Set<String> STATUSES = Set.of("todo", "in_progress", "done", "cancelled");
    private final Checkpoint checkpoint;
    private State state = new State();

    private static final class State {
        int nextTask = 1, nextFinding = 1, nextConstraint = 1;
        final Map<String, Task> tasks = new LinkedHashMap<>();
        final Map<String, String> findings = new LinkedHashMap<>();
        final Map<String, String> constraints = new LinkedHashMap<>();

        State copy() {
            State result = new State();
            result.nextTask = nextTask;
            result.nextFinding = nextFinding;
            result.nextConstraint = nextConstraint;
            result.tasks.putAll(tasks);
            result.findings.putAll(findings);
            result.constraints.putAll(constraints);
            return result;
        }
    }

    public TaskState(Checkpoint checkpoint) { this.checkpoint = Objects.requireNonNull(checkpoint); }

    public synchronized void reset() { state = new State(); }

    public synchronized ObjectNode snapshot() { return encode(state); }

    public synchronized void restore(JsonNode snapshot) throws IOException { state = decode(snapshot); }

    public synchronized String apply(String action, String id, String description, String status, String note,
            List<String> dependsOn, boolean hasDependencies, String text) throws IOException {
        if (id != null && id.isBlank()) id = null;
        if ("list".equals(action)) {
            requireAbsent(id, "id", action); requireAbsent(description, "description", action);
            requireAbsent(status, "status", action); requireAbsent(note, "note", action);
            if (hasDependencies) throw new IllegalArgumentException("Omit depends_on when action=" + action);
            requireAbsent(text, "text", action);
            return list();
        }
        State next = state.copy();
        String result;
        switch (action) {
            case "add_task" -> {
                if (id != null) throw new IllegalArgumentException(
                        "Omit id when action=add_task; task IDs are assigned automatically");
                requireAbsent(text, "text", action);
                String value = required(description, "description");
                String selectedStatus = status == null ? "todo" : validStatus(status);
                List<String> dependencies = hasDependencies ? validateDependencies(next, dependsOn) : List.of();
                String newId = "#" + next.nextTask++;
                next.tasks.put(newId, new Task(value, selectedStatus, note == null ? "" : note, dependencies));
                ensureAcyclic(next);
                result = "Added " + newId;
            }
            case "update_task" -> {
                requireAbsent(text, "text", action);
                String taskId = existingTask(next, required(id, "id"));
                Task before = next.tasks.get(taskId);
                if (description == null && status == null && note == null && !hasDependencies)
                    throw new IllegalArgumentException("update_task requires a field to change");
                List<String> dependencies = hasDependencies ? validateDependencies(next, dependsOn) : before.dependsOn;
                next.tasks.put(taskId, new Task(description == null ? before.description : required(description, "description"),
                        status == null ? before.status : validStatus(status), note == null ? before.note : note, dependencies));
                ensureAcyclic(next);
                result = "Updated " + taskId;
            }
            case "remove_task" -> {
                String taskId = existingTask(next, required(id, "id"));
                requireAbsent(description, "description", action); requireAbsent(status, "status", action);
                requireAbsent(note, "note", action); requireAbsent(text, "text", action);
                if (hasDependencies) throw new IllegalArgumentException("Omit depends_on when action=" + action);
                next.tasks.remove(taskId);
                next.tasks.replaceAll((key, task) -> new Task(task.description, task.status, task.note,
                        task.dependsOn.stream().filter(dependency -> !dependency.equals(taskId)).toList()));
                result = "Removed " + taskId;
            }
            case "add_finding", "add_constraint" -> {
                if (id != null) throw new IllegalArgumentException(
                        "Omit id when action=" + action + "; IDs are assigned automatically");
                requireAbsent(description, "description", action);
                requireAbsent(status, "status", action); requireAbsent(note, "note", action);
                if (hasDependencies) throw new IllegalArgumentException("Omit depends_on when action=" + action);
                String value = required(text, "text");
                boolean finding = action.equals("add_finding");
                String newId = finding ? "F" + next.nextFinding++ : "C" + next.nextConstraint++;
                (finding ? next.findings : next.constraints).put(newId, value);
                result = "Added " + newId;
            }
            case "remove_finding", "remove_constraint" -> {
                requireAbsent(description, "description", action); requireAbsent(status, "status", action);
                requireAbsent(note, "note", action); requireAbsent(text, "text", action);
                if (hasDependencies) throw new IllegalArgumentException("Omit depends_on when action=" + action);
                Map<String, String> items = action.equals("remove_finding") ? next.findings : next.constraints;
                String prefix = action.equals("remove_finding") ? "F" : "C";
                id = required(id, "id");
                validId(id, prefix);
                if (items.remove(id) == null) throw new IllegalArgumentException("Unknown " + prefix + " ID: " + id);
                result = "Removed " + id;
            }
            default -> throw new IllegalArgumentException("Unknown task_state action: " + action);
        }
        checkpoint.write(encode(next));
        state = next;
        return result + "\n" + list();
    }

    public synchronized String list() {
        StringBuilder result = new StringBuilder("Tasks:");
        if (state.tasks.isEmpty()) result.append(" (none)");
        for (var entry : state.tasks.entrySet()) {
            Task task = entry.getValue();
            result.append("\n").append(entry.getKey()).append(" [").append(task.status).append("] ").append(task.description);
            if (!task.dependsOn.isEmpty()) result.append(" | depends_on: ").append(String.join(", ", task.dependsOn));
            if (!task.note.isEmpty()) result.append(" | note: ").append(task.note);
        }
        result.append("\nFindings:");
        if (state.findings.isEmpty()) result.append(" (none)");
        state.findings.forEach((id, value) -> result.append("\n").append(id).append(" ").append(value));
        result.append("\nConstraints:");
        if (state.constraints.isEmpty()) result.append(" (none)");
        state.constraints.forEach((id, value) -> result.append("\n").append(id).append(" ").append(value));
        return result.toString();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must be non-empty");
        return value;
    }

    private static void requireAbsent(String value, String field, String action) {
        if (value != null) throw new IllegalArgumentException("Omit " + field + " when action=" + action);
    }

    private static String validStatus(String value) {
        if (!STATUSES.contains(value)) throw new IllegalArgumentException("Invalid task status: " + value);
        return value;
    }

    private static void validId(String id, String prefix) {
        if (id == null || !id.matches(java.util.regex.Pattern.quote(prefix) + "[1-9][0-9]*"))
            throw new IllegalArgumentException("Invalid " + prefix + " ID: " + id);
    }

    private static String existingTask(State state, String id) {
        validId(id, "#");
        if (!state.tasks.containsKey(id)) throw new IllegalArgumentException("Unknown task ID: " + id);
        return id;
    }

    private static List<String> validateDependencies(State state, List<String> dependencies) {
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String dependency : dependencies) {
            existingTask(state, dependency);
            if (!seen.add(dependency)) throw new IllegalArgumentException("Duplicate dependency: " + dependency);
            result.add(dependency);
        }
        return List.copyOf(result);
    }

    private static void ensureAcyclic(State state) {
        Set<String> visited = new HashSet<>(), visiting = new HashSet<>();
        for (String id : state.tasks.keySet()) visit(state, id, visiting, visited);
    }

    private static void visit(State state, String id, Set<String> visiting, Set<String> visited) {
        if (visited.contains(id)) return;
        if (!visiting.add(id)) throw new IllegalArgumentException("Task dependency cycle at " + id);
        for (String dependency : state.tasks.get(id).dependsOn) visit(state, dependency, visiting, visited);
        visiting.remove(id);
        visited.add(id);
    }

    private static ObjectNode encode(State state) {
        ObjectNode node = Json.MAPPER.createObjectNode();
        node.put("nextTask", state.nextTask).put("nextFinding", state.nextFinding).put("nextConstraint", state.nextConstraint);
        ArrayNode tasks = node.putArray("tasks");
        state.tasks.forEach((id, task) -> {
            ObjectNode item = tasks.addObject().put("id", id).put("description", task.description)
                    .put("status", task.status).put("note", task.note);
            ArrayNode dependencies = item.putArray("dependsOn");
            task.dependsOn.forEach(dependencies::add);
        });
        ArrayNode findings = node.putArray("findings");
        state.findings.forEach((id, value) -> findings.addObject().put("id", id).put("text", value));
        ArrayNode constraints = node.putArray("constraints");
        state.constraints.forEach((id, value) -> constraints.addObject().put("id", id).put("text", value));
        return node;
    }

    private static State decode(JsonNode node) throws IOException {
        try {
            if (node == null || !node.isObject()) throw new IllegalArgumentException("snapshot must be an object");
            State result = new State();
            result.nextTask = positiveInt(node, "nextTask");
            result.nextFinding = positiveInt(node, "nextFinding");
            result.nextConstraint = positiveInt(node, "nextConstraint");
            for (JsonNode item : array(node, "tasks")) {
                String id = string(item, "id"); validId(id, "#");
                String description = required(string(item, "description"), "description");
                String status = validStatus(string(item, "status"));
                String note = string(item, "note");
                List<String> dependencies = new ArrayList<>();
                for (JsonNode dependency : array(item, "dependsOn")) {
                    if (!dependency.isTextual()) throw new IllegalArgumentException("dependency must be a string");
                    dependencies.add(dependency.asText());
                }
                if (result.tasks.putIfAbsent(id, new Task(description, status, note, List.copyOf(dependencies))) != null)
                    throw new IllegalArgumentException("Duplicate task ID: " + id);
                if (Integer.parseInt(id.substring(1)) >= result.nextTask) throw new IllegalArgumentException("nextTask reuses " + id);
            }
            for (Task task : result.tasks.values()) validateDependencies(result, task.dependsOn);
            ensureAcyclic(result);
            decodeItems(result.findings, array(node, "findings"), "F", result.nextFinding);
            decodeItems(result.constraints, array(node, "constraints"), "C", result.nextConstraint);
            return result;
        } catch (RuntimeException error) { throw new IOException("Invalid task_state snapshot", error); }
    }

    private static void decodeItems(Map<String, String> items, JsonNode array, String prefix, int next) {
        for (JsonNode item : array) {
            String id = string(item, "id"); validId(id, prefix);
            if (Integer.parseInt(id.substring(1)) >= next) throw new IllegalArgumentException("counter reuses " + id);
            if (items.putIfAbsent(id, required(string(item, "text"), "text")) != null)
                throw new IllegalArgumentException("Duplicate ID: " + id);
        }
    }

    private static int positiveInt(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isInt() || value.asInt() < 1) throw new IllegalArgumentException(field + " must be positive");
        return value.asInt();
    }

    private static JsonNode array(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isArray()) throw new IllegalArgumentException(field + " must be an array");
        return value;
    }

    private static String string(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual()) throw new IllegalArgumentException(field + " must be a string");
        return value.asText();
    }
}
