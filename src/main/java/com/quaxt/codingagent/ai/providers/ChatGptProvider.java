package com.quaxt.codingagent.ai.providers;

import com.quaxt.codingagent.ai.Provider;
import com.quaxt.codingagent.ai.StreamOptions;
import com.quaxt.codingagent.ai.auth.ChatGptAuth;
import com.quaxt.codingagent.ai.stream.AssistantMessageEventStream;
import com.quaxt.codingagent.ai.types.AssistantMessage;
import com.quaxt.codingagent.ai.types.AssistantMessageEvent;
import com.quaxt.codingagent.ai.types.Context;
import com.quaxt.codingagent.ai.types.Model;
import com.quaxt.codingagent.ai.types.ModelCost;
import com.quaxt.codingagent.ai.types.StopReason;
import java.io.IOException;
import java.util.List;
import java.util.Set;

/** OpenAI Responses provider authenticated with a ChatGPT Plus/Pro subscription. */
public final class ChatGptProvider implements Provider {
	private static final Set<String> CODEX_MODEL_IDS = Set.of(
			"gpt-5.3-codex",
			"gpt-5.3-codex-spark",
			"gpt-5.4",
			"gpt-5.5",
			"gpt-5.6-luna",
			"gpt-5.6-sol",
			"gpt-5.6-terra");

	private final List<Model> models;
	private final ChatGptAuth auth;
	private final OpenAiResponsesProvider responses;

	public ChatGptProvider(List<Model> openAiModels, ChatGptAuth auth) {
		this.models = openAiModels.stream()
				.filter(model -> CODEX_MODEL_IDS.contains(model.id))
				.map(ChatGptProvider::subscriptionModel)
				.toList();
		this.auth = auth;
		this.responses = OpenAiResponsesProvider.codex(id(), name(), models);
	}

	@Override
	public String id() {
		return ChatGptAuth.PROVIDER_ID;
	}

	@Override
	public String name() {
		return "ChatGPT Plus/Pro";
	}

	@Override
	public String api() {
		return "openai-responses";
	}

	@Override
	public List<Model> models() {
		return models;
	}

	public ChatGptAuth auth() {
		return auth;
	}

	public boolean hasCredential() throws IOException {
		return auth.hasCredential();
	}

	public void logout() throws IOException {
		auth.logout();
	}

	@Override
	public AssistantMessageEventStream stream(Model model, Context context, StreamOptions options) {
		if (!model.provider.equals(id())) {
			throw new IllegalArgumentException("Model " + model + " is not a ChatGPT subscription model");
		}
		StreamOptions requestOptions = options == null ? new StreamOptions() : options.copy();
		try {
			configureCodexRequest(requestOptions, auth.resolveToken());
			return responses.stream(model, context, requestOptions);
		} catch (IOException error) {
			return errorStream(model, error);
		}
	}

	static void configureCodexRequest(StreamOptions options, ChatGptAuth.ChatGptToken token) {
		options.apiKey = token.accessToken();
		options.baseUrl = ChatGptAuth.CODEX_API_BASE_URL.toString();
		options.headers.put("ChatGPT-Account-Id", token.accountId());
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

	private static Model subscriptionModel(Model source) {
		return source.toBuilder()
				.provider(ChatGptAuth.PROVIDER_ID)
				.baseUrl(ChatGptAuth.CODEX_API_BASE_URL.toString())
				.cost(ModelCost.FREE)
				.build();
	}

	private static AssistantMessageEventStream errorStream(Model model, IOException error) {
		AssistantMessageEventStream stream = new AssistantMessageEventStream();
		Thread.startVirtualThread(() -> {
			AssistantMessage message = new AssistantMessage(model.api, model.provider, model.id);
			message.stopReason = StopReason.ERROR;
			message.errorMessage = error.getMessage() == null ? error.toString() : error.getMessage();
			stream.push(new AssistantMessageEvent.Error(StopReason.ERROR, message));
		});
		return stream;
	}
}
