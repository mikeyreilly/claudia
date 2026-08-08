package works.earendil.pi.cli;

import java.util.ArrayList;
import java.util.List;
import works.earendil.pi.agent.Agent;
import works.earendil.pi.agent.AgentEvent;
import works.earendil.pi.ai.json.Json;
import works.earendil.pi.ai.CoreProviders;
import works.earendil.pi.ai.Provider;
import works.earendil.pi.ai.types.AssistantMessage;
import works.earendil.pi.ai.types.Model;
import works.earendil.pi.ai.types.Message;
import works.earendil.pi.cli.session.SessionRecorder;
import works.earendil.pi.cli.session.SessionStore;
import works.earendil.pi.cli.tools.BuiltInTools;

/** Headless command dispatcher. Interactive, JSON, and RPC modes are ported separately. */
final class Cli {
	static final String APP_NAME = "pi";
	static final String VERSION = "0.1.0-java";

	private Cli() {}

	static int run(String[] args) {
		try {
			Arguments parsed = Arguments.parse(args);
			if (parsed.version) {
				System.out.println(VERSION);
				return 0;
			}
			if (parsed.help) {
				printHelp();
				return 0;
			}
			CoreProviders providers = CoreProviders.loadBundled();
			if (parsed.listModels) {
				listModels(providers, parsed.modelSearch);
				return 0;
			}
			if (parsed.mode.equals("rpc")) {
				return new RpcServer(providers, parsed).run();
			}
			if (parsed.print) {
				return runPrint(providers, parsed);
			}
			return InteractiveShell.run(providers, parsed);
		} catch (IllegalArgumentException e) {
			System.err.println("Error: " + e.getMessage());
			return 2;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			System.err.println("Error: interrupted");
			return 130;
		} catch (java.io.IOException e) {
			System.err.println("Error: " + e.getMessage());
			return 1;
		}
	}

	private static void listModels(CoreProviders providers, String search) {
		String needle = search == null ? "" : search.toLowerCase();
		for (Model model : providers.catalog().all()) {
			String id = model.provider + "/" + model.id;
			if (needle.isEmpty() || id.toLowerCase().contains(needle) || model.name.toLowerCase().contains(needle)) {
				System.out.printf("%-45s %s%n", id, model.name);
			}
		}
	}

	private static int runPrint(CoreProviders providers, Arguments arguments) throws InterruptedException, java.io.IOException {
		if (arguments.message.isBlank()) {
			throw new IllegalArgumentException("--print requires a prompt");
		}
		Model model = resolveModel(providers, arguments.provider, arguments.model);
		Provider provider = providers.require(model.provider);
		Agent agent = new Agent(arguments.systemPrompt, model, provider::stream);
		agent.setApiKey(arguments.apiKey);
		agent.state().tools.addAll(BuiltInTools.create(java.nio.file.Path.of(".")));
		if (arguments.mode.equals("json")) {
			agent.subscribe(Cli::printJsonEvent);
		}
		SessionRecorder recorder = arguments.noSession
				? null
				: SessionRecorder.create(SessionStore.defaultStore(), java.nio.file.Path.of("."), model.provider, model.id);
		List<Message> messages = agent.prompt(arguments.message);
		if (recorder != null) {
			recorder.appendMessages(messages);
		}
		if (agent.state().messages.getLast() instanceof AssistantMessage response) {
			if (response.errorMessage != null) {
				System.err.println("Error: " + response.errorMessage);
				return 1;
			}
			if (!arguments.mode.equals("json")) {
				System.out.println(response.text());
			}
			return 0;
		}

		throw new IllegalStateException("Agent ended without an assistant response");
	}

	private static void printJsonEvent(AgentEvent event) {
		var node = Json.object();
		switch (event) {
			case AgentEvent.AgentStart ignored -> node.put("type", "agent_start");
			case AgentEvent.AgentEnd end -> {
				node.put("type", "agent_end");
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
				if (update.providerEvent() instanceof works.earendil.pi.ai.types.AssistantMessageEvent.TextDelta delta) {
					node.put("type", "text_delta");
					node.put("delta", delta.delta());
				} else {
					return;
				}
			}
			case AgentEvent.ToolExecutionStart start -> {
				node.put("type", "tool_start");
				node.put("toolCallId", start.toolCallId());
				node.put("tool", start.toolName());
				node.set("arguments", start.arguments());
			}
			case AgentEvent.ToolExecutionUpdate ignored -> {
				return;
			}
			case AgentEvent.ToolExecutionEnd end -> {
				node.put("type", "tool_end");
				node.put("toolCallId", end.toolCallId());
				node.put("tool", end.toolName());
				node.put("isError", end.result().isError());
			}
		}
		System.out.println(node);
	}

	static Model resolveModel(CoreProviders providers, String providerArg, String modelArg) {
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
		providers.require(provider);
		return providers.catalog().require(provider, model);
	}

	private static void printHelp() {
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
				Run /login to authenticate with GitHub Copilot for the first time.
				""".formatted(APP_NAME, APP_NAME));
	}

	static final class Arguments {
		boolean help;
		boolean version;
		boolean listModels;
		boolean print;
		String modelSearch;
		String provider;
		String model;
		String apiKey;
		String systemPrompt = "";
		String message = "";
		boolean noSession;
		String mode = "print";

		private static Arguments parse(String[] args) {
			Arguments result = new Arguments();
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
					case "--provider" -> result.provider = value(args, ++i, arg);
					case "--model" -> result.model = value(args, ++i, arg);
					case "--api-key" -> result.apiKey = value(args, ++i, arg);
					case "--system-prompt" -> result.systemPrompt = value(args, ++i, arg);
					case "--no-session" -> result.noSession = true;
					case "--mode" -> result.mode = value(args, ++i, arg);
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

		private static String value(String[] args, int index, String flag) {
			if (index >= args.length || args[index].startsWith("-")) {
				throw new IllegalArgumentException(flag + " requires a value");
			}
			return args[index];
		}
	}
}
