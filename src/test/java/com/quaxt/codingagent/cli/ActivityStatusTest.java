package com.quaxt.codingagent.cli;

import com.quaxt.codingagent.CodingAgentCli;


import static com.quaxt.codingagent.CodingAgentCli.activeActivity;
import static com.quaxt.codingagent.CodingAgentCli.activityAccent;
import static com.quaxt.codingagent.CodingAgentCli.activityLabel;
import static com.quaxt.codingagent.CodingAgentCli.isDynamicActivity;
import static com.quaxt.codingagent.CodingAgentCli.readyActivity;
import static com.quaxt.codingagent.CodingAgentCli.retryingActivity;
import static com.quaxt.codingagent.CodingAgentCli.sameActivity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ActivityStatusTest {
	private static final long SECOND = 1_000_000_000L;

	@Test
	void readyIsTheOnlyReadyAccentedPhase() {
		ActivityStatus ready = readyActivity(0);

		assertEquals("● Ready", activityLabel(ready, 20 * SECOND));
		assertEquals(CodingAgentCli.StatusAccent.READY, activityAccent(ready));
		assertFalse(isDynamicActivity(ready));

		for (ActivityStatus.Phase phase : ActivityStatus.Phase.values()) {
			if (phase == ActivityStatus.Phase.READY) continue;
			ActivityStatus status = phase == ActivityStatus.Phase.RETRYING
					? retryingActivity(1, 3, 2_000, 0)
					: activeActivity(phase, 0);
			assertFalse(activityAccent(status) == CodingAgentCli.StatusAccent.READY, phase.toString());
		}
	}

	@Test
	void describesQuietAndStreamingModelPhasesWithElapsedTime() {
		assertEquals(
				"◑ Command: /login · 2s",
				activityLabel(activeActivity(ActivityStatus.Phase.RUNNING_COMMAND, "/login", 0), 2 * SECOND));
		assertEquals(
				"◐ Waiting for model · 12s",
				activityLabel(activeActivity(ActivityStatus.Phase.WAITING_FOR_MODEL, 0), 12 * SECOND));
		assertEquals(
				"◓ Reasoning · 1m05s",
				activityLabel(activeActivity(ActivityStatus.Phase.REASONING, 0), 65 * SECOND));
		assertEquals(
				"◑ Responding · 2s",
				activityLabel(activeActivity(ActivityStatus.Phase.RESPONDING, 0), 2 * SECOND));
		assertEquals(
				"⚙ Tool: shell · 1h01m",
				activityLabel(activeActivity(ActivityStatus.Phase.RUNNING_TOOL, "shell", 0), 3_660 * SECOND));
	}

	@Test
	void retryUsesACountdownThenSaysItIsWaitingForTheModel() {
		ActivityStatus retry = retryingActivity(2, 3, 2_500, 10 * SECOND);

		assertEquals("↻ Retry 2/3 in 3s", activityLabel(retry, 10 * SECOND));
		assertEquals("↻ Retry 2/3 in 1s", activityLabel(retry, 12 * SECOND));
		assertEquals("↻ Retry 2/3 · waiting for model", activityLabel(retry, 13 * SECOND));
		assertEquals(CodingAgentCli.StatusAccent.WARNING, activityAccent(retry));
	}

	@Test
	void repeatedStreamEventsCanRetainTheOriginalPhaseTimer() {
		ActivityStatus first = activeActivity(ActivityStatus.Phase.RESPONDING, 1);
		ActivityStatus repeated = activeActivity(ActivityStatus.Phase.RESPONDING, 2);
		ActivityStatus next = activeActivity(ActivityStatus.Phase.RUNNING_TOOL, "read", 2);

		assertTrue(sameActivity(first, repeated));
		assertFalse(sameActivity(first, next));
	}
}
