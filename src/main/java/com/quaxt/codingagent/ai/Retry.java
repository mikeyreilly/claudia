package com.quaxt.codingagent.ai;

import java.util.regex.Pattern;
import com.quaxt.codingagent.ai.types.AssistantMessage;
import com.quaxt.codingagent.ai.types.StopReason;
import com.quaxt.codingagent.ai.util.AbortSignal;

/**
 * Transient-error classification and bounded retry with exponential backoff.
 * Port of packages/ai/src/utils/retry.ts.
 */
public final class Retry {
	private static final Pattern NON_RETRYABLE_PROVIDER_LIMIT_ERROR_PATTERN = buildPattern(new String[] {
		"GoUsageLimitError",
		"FreeUsageLimitError",
		"Monthly usage limit reached",
		"available balance",
		"insufficient_quota",
		"out of budget",
		"quota exceeded",
		"billing",
	});

	private static final Pattern RETRYABLE_PROVIDER_ERROR_PATTERN = buildPattern(new String[] {
		"overloaded",
		"rate.?limit",
		"too many requests",
		"429",
		"500",
		"502",
		"503",
		"504",
		"524",
		"service.?unavailable",
		"server.?error",
		"internal.?error",
		"provider.?returned.?error",
		"network.?error",
		"connection.?error",
		"connection.?refused",
		"connection.?lost",
		"other side closed",
		"fetch failed",
		"getaddrinfo",
		"ENOTFOUND",
		"EAI_AGAIN",
		"upstream.?connect",
		"reset before headers",
		"socket hang up",
		"socket connection was closed",
		"timed? out",
		"timeout",
		"terminated",
		"websocket.?closed",
		"websocket.?error",
		"ended without",
		"stream ended before message_stop",
		"stream ended before a terminal response event",
		"http2 request did not get a response",
		"retry delay",
		"you can retry your request",
		"try your request again",
		"please retry your request",
		"ResourceExhausted",
	});

	private Retry() {}

	private static Pattern buildPattern(String[] patterns) {
		return Pattern.compile(String.join("|", patterns), Pattern.CASE_INSENSITIVE);
	}

	/** Retry policy: bounded attempts with exponential backoff (baseDelayMs * 2^(attempt-1)). */
	public record Policy(boolean enabled, int maxRetries, long baseDelayMs) {
		public static final Policy DISABLED = new Policy(false, 0, 0);
		public static final Policy DEFAULT = new Policy(true, 3, 2000);
	}

	/** Callbacks around retry attempts. */
	public interface Callbacks {
		default void onRetryScheduled(int attempt, int maxAttempts, long delayMs, String errorMessage) {}

		default void onRetryAttemptStart() {}

		default void onRetryFinished(boolean success, int attempt, String finalError) {}
	}

	@FunctionalInterface
	public interface Producer {
		AssistantMessage produce() throws InterruptedException;
	}

	/**
	 * Classifies whether a failed assistant message looks like a transient
	 * provider/transport error.
	 */
	public static boolean isRetryableAssistantError(AssistantMessage message) {
		if (message.stopReason != StopReason.ERROR || message.errorMessage == null) {
			return false;
		}
		if (NON_RETRYABLE_PROVIDER_LIMIT_ERROR_PATTERN.matcher(message.errorMessage).find()) {
			return false;
		}
		return RETRYABLE_PROVIDER_ERROR_PATTERN.matcher(message.errorMessage).find();
	}

	/**
	 * Run a single assistant-producing call with bounded retry on transient
	 * errors. Aborts are terminal and never retried; aborts during backoff are
	 * normalized to an aborted AssistantMessage.
	 */
	public static AssistantMessage retryAssistantCall(
			Producer produce, Policy policy, AbortSignal signal, Callbacks callbacks) throws InterruptedException {
		int maxAttempts = policy != null && policy.enabled() ? policy.maxRetries() : 0;
		Callbacks cb = callbacks != null ? callbacks : new Callbacks() {};

		int attempt = 0;
		Integer lastRetryAttempt = null;
		while (true) {
			AssistantMessage response = produce.produce();

			if (response.stopReason == StopReason.ABORTED) {
				if (lastRetryAttempt != null) {
					cb.onRetryFinished(false, lastRetryAttempt, null);
				}
				return response;
			}
			if (response.stopReason != StopReason.ERROR) {
				if (lastRetryAttempt != null) {
					cb.onRetryFinished(true, lastRetryAttempt, null);
				}
				return response;
			}
			if (attempt >= maxAttempts || !isRetryableAssistantError(response)) {
				if (lastRetryAttempt != null) {
					cb.onRetryFinished(false, lastRetryAttempt, response.errorMessage);
				}
				return response;
			}

			attempt++;
			lastRetryAttempt = attempt;
			String errorMessage = response.errorMessage != null ? response.errorMessage : "Unknown error";
			long delayMs = policy.baseDelayMs() * (1L << (attempt - 1));
			cb.onRetryScheduled(attempt, maxAttempts, delayMs, errorMessage);

			if (!sleepAbortable(delayMs, signal)) {
				cb.onRetryFinished(false, attempt, errorMessage);
				response.stopReason = StopReason.ABORTED;
				response.errorMessage = null;
				return response;
			}
			cb.onRetryAttemptStart();
		}
	}

	/** Returns false if aborted during sleep. */
	private static boolean sleepAbortable(long ms, AbortSignal signal) throws InterruptedException {
		if (signal == null) {
			Thread.sleep(ms);
			return true;
		}
		if (signal.isAborted()) {
			return false;
		}
		Object monitor = new Object();
		signal.onAbort(() -> {
			synchronized (monitor) {
				monitor.notifyAll();
			}
		});
		long deadline = System.currentTimeMillis() + ms;
		synchronized (monitor) {
			while (!signal.isAborted()) {
				long remaining = deadline - System.currentTimeMillis();
				if (remaining <= 0) {
					return true;
				}
				monitor.wait(remaining);
			}
		}
		return false;
	}
}
