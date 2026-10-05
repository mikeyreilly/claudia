package com.quaxt.claudia.ai;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Transient-error classification data and retry policy/callback carriers.
 * Port of packages/ai/src/utils/retry.ts; the classification and bounded
 * exponential-backoff behavior lives in ClaudiaOperations.
 */
public final class Retry {
	public static final Pattern NON_RETRYABLE_PROVIDER_LIMIT_ERROR_PATTERN = Pattern.compile(
			String.join(
					"|",
					"GoUsageLimitError",
					"FreeUsageLimitError",
					"Monthly usage limit reached",
					"available balance",
					"insufficient_quota",
					"out of budget",
					"quota exceeded",
					"billing"),
			Pattern.CASE_INSENSITIVE);

	public static final Pattern RETRYABLE_PROVIDER_ERROR_PATTERN = Pattern.compile(
			String.join(
					"|",
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
					"connect.?exception",
					"connection.?refused",
					"connection.?lost",
					"connection.?reset",
					"connection.?termination",
					"disconnect",
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
					"ResourceExhausted"),
			Pattern.CASE_INSENSITIVE);

	public Retry() {}

	/** Retry policy: bounded attempts with exponential backoff (baseDelayMs * 2^(attempt-1)). */
	public static final class Policy {
		public static final Policy DISABLED = new Policy(false, 0, 0);
		public static final Policy DEFAULT = new Policy(true, 3, 2000);

		public boolean enabled;
		public int maxRetries;
		public long baseDelayMs;

		public Policy(boolean enabled, int maxRetries, long baseDelayMs) {
			this.enabled = enabled;
			this.maxRetries = maxRetries;
			this.baseDelayMs = baseDelayMs;
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof Policy that
					&& enabled == that.enabled
					&& maxRetries == that.maxRetries
					&& baseDelayMs == that.baseDelayMs;
		}

		@Override
		public int hashCode() {
			return Objects.hash(enabled, maxRetries, baseDelayMs);
		}

		@Override
		public String toString() {
			return "Policy[enabled=" + enabled + ", maxRetries=" + maxRetries
					+ ", baseDelayMs=" + baseDelayMs + "]";
		}
	}

	/** Details of a scheduled backoff before the next attempt. */
	public static final class Scheduled {
		public int attempt;
		public int maxAttempts;
		public long delayMs;
		public String errorMessage;

		public Scheduled(int attempt, int maxAttempts, long delayMs, String errorMessage) {
			this.attempt = attempt;
			this.maxAttempts = maxAttempts;
			this.delayMs = delayMs;
			this.errorMessage = errorMessage;
		}
	}

	/** Outcome of a retry sequence that made at least one extra attempt. */
	public static final class Finished {
		public boolean success;
		public int attempt;
		public String finalError;

		public Finished(boolean success, int attempt, String finalError) {
			this.success = success;
			this.attempt = attempt;
			this.finalError = finalError;
		}
	}

	/**
	 * Retry observers. Each field holds a JDK functional value; a null field is
	 * simply not invoked.
	 */
	public static final class Callbacks {
		public Consumer<Scheduled> onRetryScheduled;
		public Runnable onRetryAttemptStart;
		public Consumer<Finished> onRetryFinished;

		public Callbacks() {}

		public Callbacks(
				Consumer<Scheduled> onRetryScheduled, Runnable onRetryAttemptStart, Consumer<Finished> onRetryFinished) {
			this.onRetryScheduled = onRetryScheduled;
			this.onRetryAttemptStart = onRetryAttemptStart;
			this.onRetryFinished = onRetryFinished;
		}
	}
}
