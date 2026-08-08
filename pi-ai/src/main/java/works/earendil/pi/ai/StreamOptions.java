package works.earendil.pi.ai;

import java.util.LinkedHashMap;
import java.util.Map;
import works.earendil.pi.ai.types.CacheRetention;
import works.earendil.pi.ai.types.ThinkingLevel;
import works.earendil.pi.ai.util.AbortSignal;

/**
 * Options for streaming requests. Folds the TS StreamOptions and
 * SimpleStreamOptions (reasoning) into one mutable options object.
 */
public final class StreamOptions {
	public AbortSignal signal;
	public String apiKey;
	/** Overrides the model catalog endpoint for credential-routed providers. */
	public String baseUrl;
	public Double temperature;
	public Integer maxTokens;
	/** Custom headers merged over provider defaults; a null value suppresses a default header. */
	public Map<String, String> headers = new LinkedHashMap<>();
	/** HTTP request/idle timeout in ms. Default 600_000 (10 min) applied by the HTTP layer. */
	public Integer timeoutMs;
	public Integer maxRetries;
	/** Cap on server-requested retry delays; default 60_000, 0 disables the cap. */
	public Integer maxRetryDelayMs;
	public CacheRetention cacheRetention = CacheRetention.SHORT;
	/** Session id for providers with session-based caching/routing. */
	public String sessionId;
	/** Reasoning effort; null or OFF disables thinking. */
	public ThinkingLevel reasoning;
	/** Provider-understood request metadata (e.g. Anthropic user_id). */
	public Map<String, Object> metadata = new LinkedHashMap<>();

	public StreamOptions() {}

	public StreamOptions signal(AbortSignal v) { this.signal = v; return this; }
	public StreamOptions apiKey(String v) { this.apiKey = v; return this; }
	public StreamOptions baseUrl(String v) { this.baseUrl = v; return this; }
	public StreamOptions temperature(Double v) { this.temperature = v; return this; }
	public StreamOptions maxTokens(Integer v) { this.maxTokens = v; return this; }
	public StreamOptions timeoutMs(Integer v) { this.timeoutMs = v; return this; }
	public StreamOptions maxRetries(Integer v) { this.maxRetries = v; return this; }
	public StreamOptions sessionId(String v) { this.sessionId = v; return this; }
	public StreamOptions reasoning(ThinkingLevel v) { this.reasoning = v; return this; }
	public StreamOptions cacheRetention(CacheRetention v) { this.cacheRetention = v; return this; }

	public boolean isAborted() {
		return signal != null && signal.isAborted();
	}

	public StreamOptions copy() {
		StreamOptions copy = new StreamOptions();
		copy.signal = signal;
		copy.apiKey = apiKey;
		copy.baseUrl = baseUrl;
		copy.temperature = temperature;
		copy.maxTokens = maxTokens;
		copy.headers = new LinkedHashMap<>(headers);
		copy.timeoutMs = timeoutMs;
		copy.maxRetries = maxRetries;
		copy.maxRetryDelayMs = maxRetryDelayMs;
		copy.cacheRetention = cacheRetention;
		copy.sessionId = sessionId;
		copy.reasoning = reasoning;
		copy.metadata = new LinkedHashMap<>(metadata);
		return copy;
	}
}
