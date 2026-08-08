package works.earendil.pi.ai.types;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A model served by a provider. Mirrors Model in packages/ai/src/types.ts.
 * `api` and `provider` are open string ids (e.g. "anthropic-messages", "openai").
 * thinkingLevelMap maps a level to a provider-specific value; a mapping to null
 * marks the level unsupported. Missing keys use provider defaults.
 */
public final class Model {
	public final String id;
	public final String name;
	public final String api;
	public final String provider;
	public final String baseUrl;
	public final boolean reasoning;
	public final Map<ThinkingLevel, String> thinkingLevelMap;
	/** Accepted input modalities: "text", "image". */
	public final List<String> input;
	public final ModelCost cost;
	public final long contextWindow;
	public final long maxTokens;
	public final Map<String, String> headers;
	/** API-specific compatibility overrides; null means auto-detect/defaults. */
	public final Compat compat;

	private Model(Builder b) {
		if (b.id == null || b.api == null || b.provider == null || b.baseUrl == null) {
			throw new IllegalArgumentException("id, api, provider, and baseUrl are required");
		}
		this.id = b.id;
		this.name = b.name != null ? b.name : b.id;
		this.api = b.api;
		this.provider = b.provider;
		this.baseUrl = b.baseUrl;
		this.reasoning = b.reasoning;
		this.thinkingLevelMap = b.thinkingLevelMap == null
				? null
				: Collections.unmodifiableMap(new EnumMap<>(b.thinkingLevelMap));
		this.input = List.copyOf(b.input);
		this.cost = b.cost != null ? b.cost : ModelCost.FREE;
		this.contextWindow = b.contextWindow;
		this.maxTokens = b.maxTokens;
		this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(b.headers));
		this.compat = b.compat;
	}

	public static Builder builder() {
		return new Builder();
	}

	public Builder toBuilder() {
		Builder b = new Builder();
		b.id = id;
		b.name = name;
		b.api = api;
		b.provider = provider;
		b.baseUrl = baseUrl;
		b.reasoning = reasoning;
		b.thinkingLevelMap = thinkingLevelMap == null ? null : new EnumMap<>(thinkingLevelMap);
		b.input = new java.util.ArrayList<>(input);
		b.cost = cost;
		b.contextWindow = contextWindow;
		b.maxTokens = maxTokens;
		b.headers = new LinkedHashMap<>(headers);
		b.compat = compat;
		return b;
	}

	@Override
	public String toString() {
		return provider + "/" + id;
	}

	public static final class Builder {
		private String id;
		private String name;
		private String api;
		private String provider;
		private String baseUrl;
		private boolean reasoning;
		private Map<ThinkingLevel, String> thinkingLevelMap;
		private List<String> input = List.of("text");
		private ModelCost cost;
		private long contextWindow;
		private long maxTokens;
		private Map<String, String> headers = new LinkedHashMap<>();
		private Compat compat;

		public Builder id(String v) { this.id = v; return this; }
		public Builder name(String v) { this.name = v; return this; }
		public Builder api(String v) { this.api = v; return this; }
		public Builder provider(String v) { this.provider = v; return this; }
		public Builder baseUrl(String v) { this.baseUrl = v; return this; }
		public Builder reasoning(boolean v) { this.reasoning = v; return this; }
		public Builder thinkingLevelMap(Map<ThinkingLevel, String> v) { this.thinkingLevelMap = v; return this; }
		public Builder input(List<String> v) { this.input = v; return this; }
		public Builder cost(ModelCost v) { this.cost = v; return this; }
		public Builder contextWindow(long v) { this.contextWindow = v; return this; }
		public Builder maxTokens(long v) { this.maxTokens = v; return this; }
		public Builder headers(Map<String, String> v) { this.headers = new LinkedHashMap<>(v); return this; }
		public Builder compat(Compat v) { this.compat = v; return this; }

		public Model build() {
			return new Model(this);
		}
	}
}
