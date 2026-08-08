package works.earendil.pi.ai.providers;

import java.io.IOException;
import java.util.List;
import works.earendil.pi.ai.Provider;
import works.earendil.pi.ai.StreamOptions;
import works.earendil.pi.ai.auth.GitHubCopilotAuth;
import works.earendil.pi.ai.stream.AssistantMessageEventStream;
import works.earendil.pi.ai.types.AssistantMessage;
import works.earendil.pi.ai.types.AssistantMessageEvent;
import works.earendil.pi.ai.types.Context;
import works.earendil.pi.ai.types.Model;
import works.earendil.pi.ai.types.StopReason;

/** Routes GitHub Copilot models through their catalog-declared streaming API. */
public final class GitHubCopilotProvider implements Provider {
	private final List<Model> models;
	private final GitHubCopilotAuth auth;
	private final AnthropicProvider anthropic;
	private final OpenAiCompatibleProvider completions;
	private final OpenAiResponsesProvider responses;

	public GitHubCopilotProvider(List<Model> models, GitHubCopilotAuth auth) {
		this.models = List.copyOf(models);
		this.auth = auth;
		this.anthropic = new AnthropicProvider(
				id(), name(), modelsFor("anthropic-messages"), List.of(), true);
		this.completions = new OpenAiCompatibleProvider(
				id(), name(), "https://api.individual.githubcopilot.com", modelsFor("openai-completions"));
		this.responses = new OpenAiResponsesProvider(id(), name(), modelsFor("openai-responses"), List.of());
	}

	@Override
	public String id() {
		return GitHubCopilotAuth.PROVIDER_ID;
	}

	@Override
	public String name() {
		return "GitHub Copilot";
	}

	@Override
	public String api() {
		return "github-copilot";
	}

	@Override
	public List<Model> models() {
		return models;
	}

	/** Filters the catalog to models GitHub reports as enabled for the signed-in account. */
	public List<Model> availableModels() throws IOException {
		List<String> enabled = auth.resolveToken().availableModelIds();
		return filterEnabledModels(enabled);
	}

	/** Enables catalog model policies, then refreshes the account's enabled-model list. */
	public ModelAccess enableAndRefreshModels() throws IOException {
		int policiesEnabled = auth.enableModels(models.stream().map(model -> model.id).toList());
		List<Model> available = filterEnabledModels(auth.refreshAvailableModels().availableModelIds());
		return new ModelAccess(policiesEnabled, available);
	}

	public boolean hasCredential() throws IOException {
		return auth.hasCredential();
	}

	public void logout() throws IOException {
		auth.logout();
	}

	public GitHubCopilotAuth auth() {
		return auth;
	}

	@Override
	public AssistantMessageEventStream stream(Model model, Context context, StreamOptions options) {
		if (!model.provider.equals(id())) {
			throw new IllegalArgumentException("Model " + model + " is not a GitHub Copilot model");
		}
		StreamOptions requestOptions = options == null ? new StreamOptions() : options.copy();
		if (requestOptions.apiKey == null || requestOptions.apiKey.isBlank()) {
			try {
				GitHubCopilotAuth.CopilotToken token = auth.resolveToken();
				requestOptions.apiKey = token.accessToken();
				requestOptions.baseUrl = token.baseUrl().toString();
			} catch (IOException error) {
				return errorStream(model, error);
			}
		}
		return switch (model.api) {
			case "anthropic-messages" -> anthropic.stream(model, context, requestOptions);
			case "openai-completions" -> completions.stream(model, context, requestOptions);
			case "openai-responses" -> responses.stream(model, context, requestOptions);
			default -> throw new IllegalArgumentException("Unsupported GitHub Copilot model API: " + model.api);
		};
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

	private List<Model> modelsFor(String api) {
		return models.stream().filter(model -> model.api.equals(api)).toList();
	}

	private List<Model> filterEnabledModels(List<String> enabled) {
		if (enabled == null) {
			return models;
		}
		return models.stream().filter(model -> enabled.contains(model.id)).toList();
	}

	public record ModelAccess(int policiesEnabled, List<Model> models) {
		public ModelAccess {
			models = List.copyOf(models);
		}
	}
}
