package com.quaxt.agentic;

import java.io.*;
import java.util.*;

/** Shared Responses request envelope extracted from Claudia's OpenAI adapter. */
public final class Responses {
    private Responses() {}
    public static Map<String, Object> envelope(String model, String instructions, boolean codex) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model); request.put("stream", true); request.put("store", false);
        if (instructions != null && !instructions.isBlank()) request.put("instructions", instructions);
        else if (codex) request.put("instructions", "You are a helpful assistant.");
        if (codex) {
            request.put("text", Map.of("verbosity", "low")); request.put("tool_choice", "auto"); request.put("parallel_tool_calls", true);
        }
        return request;
    }
    /** Read a terminal Responses SSE event; partial/failed streams never count as success. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> completed(Reader reader, ChatGptAuth.Codec codec) throws IOException {
        BufferedReader buffered = reader instanceof BufferedReader b ? b : new BufferedReader(reader);
        StringBuilder data = new StringBuilder();
        long total = 0;
        String line;
        while ((line = buffered.readLine()) != null) {
            total += line.length();
            if (total > 4 * 1048576L) throw new IOException("Provider stream exceeds the response limit");
            if (line.isEmpty()) {
                if (!data.isEmpty()) {
                    String json = data.toString().trim(); data.setLength(0);
                    if (json.equals("[DONE]")) continue;
                    Map<String, Object> event = codec.decode(json);
                    if ("response.completed".equals(event.get("type"))) {
                        Object value = event.get("response");
                        if (!(value instanceof Map<?, ?> response) || !"completed".equals(response.get("status"))) throw new IOException("Provider response was incomplete");
                        return (Map<String, Object>) response;
                    }
                    if (Set.of("response.failed", "response.incomplete", "error").contains(event.get("type"))) throw new IOException("Provider response failed");
                }
            } else if (line.startsWith("data:")) data.append(line.substring(5).stripLeading()).append('\n');
        }
        throw new IOException("Provider stream ended before completion");
    }
}
