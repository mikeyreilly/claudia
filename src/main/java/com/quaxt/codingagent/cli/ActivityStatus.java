package com.quaxt.codingagent.cli;

import java.util.Objects;
import com.quaxt.codingagent.tui.InteractiveTerminal;

/** A concise presentation state for the interactive shell's status bar. */
record ActivityStatus(
		Phase phase,
		String detail,
		int attempt,
		int maxAttempts,
		long startedNanos,
		long retryDelayNanos) {
	private static final String[] SPINNER = {"◐", "◓", "◑", "◒"};

	enum Phase {
		NO_MODEL,
		READY,
		RUNNING_COMMAND,
		PREPARING_TOOLS,
		COMPACTING,
		WAITING_FOR_MODEL,
		REASONING,
		RESPONDING,
		PREPARING_TOOL,
		RUNNING_TOOL,
		RETRYING,
		STOPPING
	}

	ActivityStatus {
		Objects.requireNonNull(phase, "phase");
		detail = detail == null ? "" : detail;
	}

	static ActivityStatus noModel(long nowNanos) {
		return new ActivityStatus(Phase.NO_MODEL, "", 0, 0, nowNanos, 0);
	}

	static ActivityStatus ready(long nowNanos) {
		return new ActivityStatus(Phase.READY, "", 0, 0, nowNanos, 0);
	}

	static ActivityStatus active(Phase phase, long nowNanos) {
		return active(phase, "", nowNanos);
	}

	static ActivityStatus active(Phase phase, String detail, long nowNanos) {
		return new ActivityStatus(phase, detail, 0, 0, nowNanos, 0);
	}

	static ActivityStatus retrying(int attempt, int maxAttempts, long delayMs, long nowNanos) {
		long delayNanos;
		try {
			delayNanos = Math.multiplyExact(Math.max(0, delayMs), 1_000_000L);
		} catch (ArithmeticException ignored) {
			delayNanos = Long.MAX_VALUE;
		}
		return new ActivityStatus(Phase.RETRYING, "", attempt, maxAttempts, nowNanos, delayNanos);
	}

	/** Whether a repeated event describes the same phase and should retain its elapsed timer. */
	boolean sameActivity(ActivityStatus other) {
		return other != null
				&& phase == other.phase
				&& detail.equals(other.detail)
				&& attempt == other.attempt
				&& maxAttempts == other.maxAttempts
				&& retryDelayNanos == other.retryDelayNanos;
	}

	boolean isDynamic() {
		return phase != Phase.NO_MODEL && phase != Phase.READY;
	}

	String label(long nowNanos) {
		return switch (phase) {
			case NO_MODEL -> "○ No model";
			case READY -> "● Ready";
			case RUNNING_COMMAND -> busyLabel(
					detail.isBlank() ? "Running command" : "Command: " + detail,
					nowNanos);
			case PREPARING_TOOLS -> busyLabel("Preparing tools", nowNanos);
			case COMPACTING -> busyLabel("Compacting context", nowNanos);
			case WAITING_FOR_MODEL -> busyLabel("Waiting for model", nowNanos);
			case REASONING -> busyLabel("Reasoning", nowNanos);
			case RESPONDING -> busyLabel("Responding", nowNanos);
			case PREPARING_TOOL -> busyLabel(
					detail.isBlank() ? "Preparing tool call" : "Preparing tool: " + detail,
					nowNanos);
			case RUNNING_TOOL -> "⚙ Tool: " + (detail.isBlank() ? "unknown" : detail)
					+ " · " + formatElapsed(nowNanos);
			case RETRYING -> retryLabel(nowNanos);
			case STOPPING -> "◌ Stopping · " + formatElapsed(nowNanos);
		};
	}

	InteractiveTerminal.StatusAccent accent() {
		return switch (phase) {
			case READY -> InteractiveTerminal.StatusAccent.READY;
			case NO_MODEL, RETRYING, STOPPING -> InteractiveTerminal.StatusAccent.WARNING;
			case RUNNING_TOOL -> InteractiveTerminal.StatusAccent.TOOL;
			default -> InteractiveTerminal.StatusAccent.ACTIVE;
		};
	}

	private String busyLabel(String description, long nowNanos) {
		return spinner(nowNanos) + " " + description + " · " + formatElapsed(nowNanos);
	}

	private String retryLabel(long nowNanos) {
		String progress = attempt + "/" + maxAttempts;
		long remaining = Math.max(0, retryDelayNanos - elapsedNanos(nowNanos));
		if (remaining > 0) {
			long seconds = 1 + (remaining - 1) / 1_000_000_000L;
			return "↻ Retry " + progress + " in " + seconds + "s";
		}
		return "↻ Retry " + progress + " · waiting for model";
	}

	private String spinner(long nowNanos) {
		long elapsedSeconds = elapsedNanos(nowNanos) / 1_000_000_000L;
		return SPINNER[(int) (elapsedSeconds % SPINNER.length)];
	}

	private String formatElapsed(long nowNanos) {
		long seconds = elapsedNanos(nowNanos) / 1_000_000_000L;
		if (seconds < 60) return seconds + "s";
		long minutes = seconds / 60;
		long remainingSeconds = seconds % 60;
		if (minutes < 60) return minutes + "m" + String.format(java.util.Locale.ROOT, "%02ds", remainingSeconds);
		long hours = minutes / 60;
		return hours + "h" + String.format(java.util.Locale.ROOT, "%02dm", minutes % 60);
	}

	private long elapsedNanos(long nowNanos) {
		return Math.max(0, nowNanos - startedNanos);
	}
}
