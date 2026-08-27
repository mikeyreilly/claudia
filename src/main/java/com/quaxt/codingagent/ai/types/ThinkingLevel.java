package com.quaxt.codingagent.ai.types;

/**
 * Thinking/reasoning effort levels. OFF corresponds to ModelThinkingLevel "off";
 * the rest mirror ThinkingLevel in packages/ai/src/types.ts.
 */
public enum ThinkingLevel {
	OFF("off"),
	MINIMAL("minimal"),
	LOW("low"),
	MEDIUM("medium"),
	HIGH("high"),
	XHIGH("xhigh"),
	MAX("max");

	public String wire;

	ThinkingLevel(String wire) {
		this.wire = wire;
	}
}
