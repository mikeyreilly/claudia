package com.quaxt.codingagent.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import com.quaxt.codingagent.tui.InteractiveTerminal;

class ActivityStatusTest {
	private static final long SECOND = 1_000_000_000L;

	@Test
	void readyIsTheOnlyReadyAccentedPhase() {
		ActivityStatus ready = ActivityStatus.ready(0);

		assertEquals("● Ready", ready.label(20 * SECOND));
		assertEquals(InteractiveTerminal.StatusAccent.READY, ready.accent());
		assertFalse(ready.isDynamic());

		for (ActivityStatus.Phase phase : ActivityStatus.Phase.values()) {
			if (phase == ActivityStatus.Phase.READY) continue;
			ActivityStatus status = phase == ActivityStatus.Phase.RETRYING
					? ActivityStatus.retrying(1, 3, 2_000, 0)
					: ActivityStatus.active(phase, 0);
			assertFalse(status.accent() == InteractiveTerminal.StatusAccent.READY, phase.toString());
		}
	}

	@Test
	void describesQuietAndStreamingModelPhasesWithElapsedTime() {
		assertEquals(
				"◑ Command: /login · 2s",
				ActivityStatus.active(ActivityStatus.Phase.RUNNING_COMMAND, "/login", 0).label(2 * SECOND));
		assertEquals(
				"◐ Waiting for model · 12s",
				ActivityStatus.active(ActivityStatus.Phase.WAITING_FOR_MODEL, 0).label(12 * SECOND));
		assertEquals(
				"◓ Reasoning · 1m05s",
				ActivityStatus.active(ActivityStatus.Phase.REASONING, 0).label(65 * SECOND));
		assertEquals(
				"◑ Responding · 2s",
				ActivityStatus.active(ActivityStatus.Phase.RESPONDING, 0).label(2 * SECOND));
		assertEquals(
				"⚙ Tool: shell · 1h01m",
				ActivityStatus.active(ActivityStatus.Phase.RUNNING_TOOL, "shell", 0).label(3_660 * SECOND));
	}

	@Test
	void retryUsesACountdownThenSaysItIsWaitingForTheModel() {
		ActivityStatus retry = ActivityStatus.retrying(2, 3, 2_500, 10 * SECOND);

		assertEquals("↻ Retry 2/3 in 3s", retry.label(10 * SECOND));
		assertEquals("↻ Retry 2/3 in 1s", retry.label(12 * SECOND));
		assertEquals("↻ Retry 2/3 · waiting for model", retry.label(13 * SECOND));
		assertEquals(InteractiveTerminal.StatusAccent.WARNING, retry.accent());
	}

	@Test
	void repeatedStreamEventsCanRetainTheOriginalPhaseTimer() {
		ActivityStatus first = ActivityStatus.active(ActivityStatus.Phase.RESPONDING, 1);
		ActivityStatus repeated = ActivityStatus.active(ActivityStatus.Phase.RESPONDING, 2);
		ActivityStatus next = ActivityStatus.active(ActivityStatus.Phase.RUNNING_TOOL, "read", 2);

		assertTrue(first.sameActivity(repeated));
		assertFalse(first.sameActivity(next));
	}
}
