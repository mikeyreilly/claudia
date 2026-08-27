package com.quaxt.codingagent.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import com.quaxt.codingagent.CodingAgentOperations;
import com.quaxt.codingagent.ai.types.AssistantMessage;
import com.quaxt.codingagent.ai.types.StopReason;
import com.quaxt.codingagent.ai.util.AbortSignal;

class RetryTest {
	private static AssistantMessage message(StopReason reason, String error) {
		AssistantMessage m = new AssistantMessage("a", "p", "m");
		m.stopReason = reason;
		m.errorMessage = error;
		return m;
	}

	@Test
	void classifiesRetryableErrors() {
		assertTrue(CodingAgentOperations.isRetryableAssistantError(message(StopReason.ERROR, "429 Too Many Requests")));
		assertTrue(CodingAgentOperations.isRetryableAssistantError(message(StopReason.ERROR, "socket hang up")));
		assertTrue(CodingAgentOperations.isRetryableAssistantError(message(StopReason.ERROR, "java.net.ConnectException")));
		assertTrue(CodingAgentOperations.isRetryableAssistantError(message(StopReason.ERROR, "Overloaded")));
		assertTrue(CodingAgentOperations.isRetryableAssistantError(message(
				StopReason.ERROR,
				"503: upstream connect error or disconnect/reset before headers. reset reason: connection termination")));
	}

	@Test
	void classifiesNonRetryableErrors() {
		assertFalse(CodingAgentOperations.isRetryableAssistantError(message(StopReason.ERROR, "insufficient_quota: add credits")));
		assertFalse(CodingAgentOperations.isRetryableAssistantError(message(StopReason.ERROR, "invalid_request_error")));
		assertFalse(CodingAgentOperations.isRetryableAssistantError(message(StopReason.STOP, null)));
		// quota patterns take precedence even when a retryable token (429) is present
		assertFalse(CodingAgentOperations.isRetryableAssistantError(message(StopReason.ERROR, "429 quota exceeded")));
	}

	@Test
	void retriesUntilSuccess() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		AssistantMessage result = CodingAgentOperations.retryAssistantCall(
				() -> calls.incrementAndGet() < 3
						? message(StopReason.ERROR, "503 service unavailable")
						: message(StopReason.STOP, null),
				new Retry.Policy(true, 5, 1),
				null,
				null);
		assertEquals(3, calls.get());
		assertEquals(StopReason.STOP, result.stopReason);
	}

	@Test
	void returnsErrorAfterExhaustingRetries() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		AssistantMessage result = CodingAgentOperations.retryAssistantCall(
				() -> {
					calls.incrementAndGet();
					return message(StopReason.ERROR, "500 internal error");
				},
				new Retry.Policy(true, 2, 1),
				null,
				null);
		assertEquals(3, calls.get()); // initial + 2 retries
		assertEquals(StopReason.ERROR, result.stopReason);
	}

	@Test
	void doesNotRetryNonRetryable() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		AssistantMessage result = CodingAgentOperations.retryAssistantCall(
				() -> {
					calls.incrementAndGet();
					return message(StopReason.ERROR, "billing problem");
				},
				new Retry.Policy(true, 5, 1),
				null,
				null);
		assertEquals(1, calls.get());
		assertEquals(StopReason.ERROR, result.stopReason);
	}

	@Test
	void abortDuringBackoffNormalizesToAborted() throws Exception {
		AbortSignal signal = new AbortSignal();
		Thread aborter = Thread.ofVirtual().start(() -> {
			try {
				Thread.sleep(30);
			} catch (InterruptedException ignored) {
			}
			CodingAgentOperations.abort(signal);
		});
		AssistantMessage result = CodingAgentOperations.retryAssistantCall(
				() -> message(StopReason.ERROR, "503 service unavailable"),
				new Retry.Policy(true, 3, 10_000),
				signal,
				null);
		aborter.join();
		assertEquals(StopReason.ABORTED, result.stopReason);
		assertNull(result.errorMessage);
	}

	@Test
	void neverRetriesAbortedResponses() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		AssistantMessage result = CodingAgentOperations.retryAssistantCall(
				() -> {
					calls.incrementAndGet();
					return message(StopReason.ABORTED, null);
				},
				new Retry.Policy(true, 5, 1),
				null,
				null);
		assertEquals(1, calls.get());
		assertEquals(StopReason.ABORTED, result.stopReason);
	}
}
