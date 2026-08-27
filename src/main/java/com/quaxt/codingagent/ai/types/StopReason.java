package com.quaxt.codingagent.ai.types;

/**
 * Why an assistant message stopped. Mirrors StopReason in packages/ai/src/types.ts.
 */
public enum StopReason {
	PENDING("pending"),
	STOP("stop"),
	LENGTH("length"),
	TOOL_USE("toolUse"),
	ERROR("error"),
	ABORTED("aborted"),
	DEFERRED("deferred");

	public String wire;

	StopReason(String wire) {
		this.wire = wire;
	}
}
