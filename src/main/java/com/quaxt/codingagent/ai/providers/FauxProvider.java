package com.quaxt.codingagent.ai.providers;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;
import com.quaxt.codingagent.ai.Provider;
import com.quaxt.codingagent.ai.StreamOptions;
import com.quaxt.codingagent.ai.types.AssistantMessage;
import com.quaxt.codingagent.ai.types.Context;
import com.quaxt.codingagent.ai.types.Model;

/**
 * Deterministic in-process provider carrier for tests. This is the Java
 * equivalent of {@code packages/ai/src/providers/faux.ts}; it needs neither
 * credentials nor a network connection and produces the same incremental event
 * protocol as real provider adapters. All behavior lives in
 * CodingAgentOperations, which uses the provider instance as its monitor.
 */
public final class FauxProvider implements Provider {
	public static final String DEFAULT_API = "faux";
	public static final String DEFAULT_PROVIDER = "faux";
	public static final String DEFAULT_MODEL_ID = "faux-1";
	public static final String NAME = "Faux";

	public String api;
	public String id;
	public List<Model> models;
	public Deque<ResponseStep> pendingResponses = new ArrayDeque<>();
	public State state = new State();

	public FauxProvider(String api, String id, List<Model> models) {
		this.api = api;
		this.id = id;
		this.models = models;
	}

	/** One scripted reply, literal or derived from the request context. */
	public sealed interface ResponseStep permits ResponseStep.Message, ResponseStep.Factory {

		final class Message implements ResponseStep {
			public AssistantMessage response;

			public Message(AssistantMessage response) {
				this.response = response;
			}
		}

		final class Factory implements ResponseStep {
			public Function<Request, AssistantMessage> factory;

			public Factory(Function<Request, AssistantMessage> factory) {
				this.factory = factory;
			}
		}
	}

	/** Inputs visible to response factories. */
	public static final class Request {
		public Context context;
		public StreamOptions options;
		public State state;
		public Model model;

		public Request(Context context, StreamOptions options, State state, Model model) {
			this.context = context;
			this.options = options;
			this.state = state;
			this.model = model;
		}
	}

	/** Observable test state; guarded by the owning provider's monitor. */
	public static final class State {
		public int callCount;

		public State() {}
	}
}
