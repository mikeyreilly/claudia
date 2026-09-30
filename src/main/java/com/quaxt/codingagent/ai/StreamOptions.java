package com.quaxt.codingagent.ai;

import java.util.LinkedHashMap;
import java.util.Map;
import com.quaxt.codingagent.ai.types.CacheRetention;
import com.quaxt.codingagent.ai.types.ThinkingLevel;
import com.quaxt.codingagent.ai.util.AbortSignal;

/**
 * Options for streaming requests. Folds the TS StreamOptions and
 * SimpleStreamOptions (reasoning) into one mutable options carrier; the copy
 * and abort-state helpers live in CodingAgentOperations.
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
	/**
	 * HTTP timeout in ms for the wait for response headers and for each wait for
	 * more response data; it never caps the total length of a stream. Default
	 * 600_000 (10 min) applied by the HTTP layer.
	 */
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
}
