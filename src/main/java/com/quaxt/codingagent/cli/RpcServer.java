package com.quaxt.codingagent.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import com.quaxt.codingagent.agent.Agent;
import com.quaxt.codingagent.agent.AgentEvent;
import com.quaxt.codingagent.ai.CoreProviders;
import com.quaxt.codingagent.ai.Provider;
import com.quaxt.codingagent.ai.json.Json;
import com.quaxt.codingagent.ai.types.AssistantMessage;
import com.quaxt.codingagent.ai.types.Message;
import com.quaxt.codingagent.ai.types.Model;
import com.quaxt.codingagent.cli.session.SessionRecorder;
import com.quaxt.codingagent.cli.session.SessionStore;
import com.quaxt.codingagent.cli.tools.BuiltInTools;
import com.quaxt.codingagent.mcp.McpAgentTool;
import com.quaxt.codingagent.mcp.McpManager;

/**
 * JSONL stdin/stdout automation protocol. This intentionally implements the
 * core stable commands; interactive-only queues, extensions, and branching
 * are not part of the Java port's initial RPC surface.
 */
final class RpcServer {
	private final CoreProviders providers;
	private final Cli.Arguments arguments;
	private final McpManager mcp;
	private Agent agent;
	private SessionRecorder recorder;

	RpcServer(CoreProviders providers, Cli.Arguments arguments) throws IOException {
		this.providers = providers;
		this.arguments = arguments;
		Model initialModel = resolveModel(arguments.provider, arguments.model);
		this.mcp = McpManager.loadDefault(Path.of(".").toAbsolutePath().normalize());
		try {
			resetAgent(initialModel);
		} catch (IOException | RuntimeException error) {
			mcp.close();
			throw error;
		}
	}

	int run() throws IOException {
		try (mcp;
				BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = input.readLine()) != null) {
				handle(line);
			}
		}
		return 0;
	}

	private void handle(String line) {
		JsonNode parsed;
		try {
			parsed = Json.MAPPER.readTree(line);
		} catch (IOException e) {
			respond(null, "parse", false, null, "Invalid JSON: " + e.getMessage());
			return;
		}
		if (!(parsed instanceof ObjectNode command) || !command.path("type").isTextual()) {
			respond(null, "parse", false, null, "Command must be a JSON object with a string type");
			return;
		}
		String id = command.path("id").isTextual() ? command.path("id").asText() : null;
		String type = command.path("type").asText();
		try {
			switch (type) {
				case "prompt" -> prompt(id, command);
				case "abort" -> {
					agent.abort();
					respond(id, type, true, null, null);
				}
				case "get_state" -> respond(id, type, true, state(), null);
				case "get_available_models" -> {
					ObjectNode data = Json.object();
					data.set("models", Json.MAPPER.valueToTree(providers.catalog().all()));
					respond(id, type, true, data, null);
				}
				case "set_model" -> setModel(id, command);
				case "compact" -> {
					String instructions = command.path("customInstructions").isTextual()
							? command.path("customInstructions").asText()
							: null;
					var result = agent.compact(instructions);
					respond(id, type, true, Json.MAPPER.valueToTree(result), null);
				}
				case "set_auto_compaction" -> {
					if (!command.path("enabled").isBoolean()) {
						throw new IllegalArgumentException("enabled must be a boolean");
					}
					agent.state().autoCompactionEnabled = command.path("enabled").asBoolean();
					respond(id, type, true, null, null);
				}
				case "get_messages" -> {
					ObjectNode data = Json.object();
					data.set("messages", Json.MAPPER.valueToTree(agent.state().messages));
					respond(id, type, true, data, null);
				}
				case "get_last_assistant_text" -> {
					String text = agent.state().messages.reversed().stream()
							.filter(AssistantMessage.class::isInstance)
							.map(AssistantMessage.class::cast)
							.map(AssistantMessage::text)
							.findFirst()
							.orElse(null);
					ObjectNode data = Json.object();
					if (text == null) data.putNull("text");
					else data.put("text", text);
					respond(id, type, true, data, null);
				}
				case "new_session" -> {
					resetAgent(agent.state().model);
					respond(id, type, true, Json.object().put("cancelled", false), null);
				}
				default -> respond(id, type, false, null, "Unsupported command: " + type);
			}
		} catch (Exception e) {
			respond(id, type, false, null, e.getMessage() == null ? e.toString() : e.getMessage());
		}
	}

	private void prompt(String id, ObjectNode command) throws InterruptedException, IOException {
		JsonNode message = command.get("message");
		if (message == null || !message.isTextual() || message.asText().isBlank()) {
			throw new IllegalArgumentException("prompt requires a non-empty string message");
		}
		mcp.awaitReady();
		syncMcpTools();
		List<Message> messages = agent.prompt(message.asText());
		if (recorder != null) recorder.appendMessages(messages);
		respond(id, "prompt", true, null, null);
	}

	private void setModel(String id, ObjectNode command) throws IOException {
		String provider = text(command, "provider");
		String modelId = text(command, "modelId");
		Model model = providers.catalog().require(provider, modelId);
		resetAgent(model);
		respond(id, "set_model", true, Json.MAPPER.valueToTree(model), null);
	}

	private void resetAgent(Model model) throws IOException {
		Provider provider = providers.require(model.provider);
		agent = new Agent(arguments.systemPrompt, model, provider::stream);
		agent.setApiKey(arguments.apiKey);
		agent.state().tools.addAll(BuiltInTools.create(Path.of(".")));
		agent.state().tools.addAll(mcp.tools());
		agent.subscribe(this::event);
		recorder = arguments.noSession
				? null
				: SessionRecorder.create(SessionStore.defaultStore(), Path.of("."), model.provider, model.id);
	}

	private void syncMcpTools() {
		agent.state().tools.removeIf(McpAgentTool.class::isInstance);
		agent.state().tools.addAll(mcp.tools());
	}

	private Model resolveModel(String providerArg, String modelArg) {
		if (modelArg == null) {
			throw new IllegalArgumentException("--mode rpc requires --model <provider/model>");
		}
		if (modelArg.contains("/")) {
			String[] parts = modelArg.split("/", 2);
			if (providerArg != null && !providerArg.equals(parts[0])) {
				throw new IllegalArgumentException("--provider conflicts with the provider in --model");
			}
			return providers.catalog().require(parts[0], parts[1]);
		}
		if (providerArg == null) {
			throw new IllegalArgumentException("--mode rpc requires --model <provider/model>");
		}
		return providers.catalog().require(providerArg, modelArg);
	}

	private ObjectNode state() {
		ObjectNode data = Json.object();
		data.set("model", Json.MAPPER.valueToTree(agent.state().model));
		data.put("isStreaming", agent.state().isStreaming);
		data.put("isCompacting", agent.state().isCompacting);
		data.put("autoCompactionEnabled", agent.state().autoCompactionEnabled);
		data.put("messageCount", agent.state().messages.size());
		data.put("sessionId", recorder == null ? "" : recorder.sessionId());
		return data;
	}

	private void event(AgentEvent event) {
		ObjectNode node = Json.object();
		switch (event) {
			case AgentEvent.AgentStart ignored -> node.put("type", "agent_start");
			case AgentEvent.AgentEnd end -> {
				node.put("type", "agent_settled");
				node.put("messageCount", end.newMessages().size());
			}
			case AgentEvent.CompactionStart start -> {
				node.put("type", "compaction_start");
				node.put("tokensBefore", start.tokensBefore());
			}
			case AgentEvent.CompactionEnd end -> {
				node.put("type", "compaction_end");
				node.put("tokensBefore", end.result().tokensBefore());
				node.put("estimatedTokensAfter", end.result().estimatedTokensAfter());
			}
			case AgentEvent.TurnStart ignored -> node.put("type", "turn_start");
			case AgentEvent.TurnEnd end -> {
				node.put("type", "turn_end");
				node.put("toolResultCount", end.toolResults().size());
			}
			case AgentEvent.AutoRetryStart retry -> {
				node.put("type", "auto_retry_start");
				node.put("attempt", retry.attempt());
				node.put("maxAttempts", retry.maxAttempts());
				node.put("delayMs", retry.delayMs());
				node.put("error", retry.errorMessage());
			}
			case AgentEvent.AutoRetryEnd retry -> {
				node.put("type", "auto_retry_end");
				node.put("success", retry.success());
				node.put("attempt", retry.attempt());
				if (retry.finalError() != null) node.put("error", retry.finalError());
			}
			case AgentEvent.MessageStart start -> {
				node.put("type", "message_start");
				node.put("role", start.message().role());
			}
			case AgentEvent.MessageEnd end -> {
				node.put("type", "message_end");
				node.put("role", end.message().role());
				if (end.message() instanceof AssistantMessage assistant) node.put("text", assistant.text());
			}
			case AgentEvent.MessageUpdate update -> {
				if (update.providerEvent() instanceof com.quaxt.codingagent.ai.types.AssistantMessageEvent.TextDelta delta) {
					node.put("type", "message_update");
					node.putObject("assistantMessageEvent").put("type", "text_delta").put("delta", delta.delta());
				} else return;
			}
			case AgentEvent.ToolExecutionStart start -> {
				node.put("type", "tool_execution_start");
				node.put("toolCallId", start.toolCallId());
				node.put("toolName", start.toolName());
				node.set("arguments", start.arguments());
			}
			case AgentEvent.ToolExecutionUpdate ignored -> {
				return;
			}
			case AgentEvent.ToolExecutionEnd end -> {
				node.put("type", "tool_execution_end");
				node.put("toolCallId", end.toolCallId());
				node.put("toolName", end.toolName());
				node.put("isError", end.result().isError());
			}
		}
		output(node);
	}

	private void respond(String id, String command, boolean success, JsonNode data, String error) {
		ObjectNode response = Json.object();
		response.put("type", "response");
		if (id != null) response.put("id", id);
		response.put("command", command);
		response.put("success", success);
		if (success && data != null) response.set("data", data);
		if (!success) response.put("error", error);
		output(response);
	}

	private static String text(ObjectNode command, String field) {
		JsonNode value = command.get(field);
		if (value == null || !value.isTextual() || value.asText().isBlank()) {
			throw new IllegalArgumentException(field + " must be a non-empty string");
		}
		return value.asText();
	}

	private static synchronized void output(ObjectNode node) {
		System.out.println(node);
		System.out.flush();
	}
}
