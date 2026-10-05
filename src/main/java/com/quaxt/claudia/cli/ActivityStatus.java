package com.quaxt.claudia.cli;

/**
 * A concise presentation state for the interactive shell's status bar.
 * Labels, accents, and the phase transitions live in ClaudiaCli.
 */
public final class ActivityStatus {
	public static final String[] SPINNER = {"◐", "◓", "◑", "◒"};

	public enum Phase {
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

	public Phase phase;
	public String detail;
	public int attempt;
	public int maxAttempts;
	public long startedNanos;
	public long retryDelayNanos;

	public ActivityStatus(
			Phase phase,
			String detail,
			int attempt,
			int maxAttempts,
			long startedNanos,
			long retryDelayNanos) {
		this.phase = phase;
		this.detail = detail;
		this.attempt = attempt;
		this.maxAttempts = maxAttempts;
		this.startedNanos = startedNanos;
		this.retryDelayNanos = retryDelayNanos;
	}
}
