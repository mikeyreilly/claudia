package com.quaxt.codingagent.ai;

import com.quaxt.codingagent.ai.types.ThinkingLevel;

/**
 * Model helper constants. The cost calculation and thinking-level clamping
 * behavior ported from packages/ai/src/models.ts lives in
 * CodingAgentOperations.
 */
public final class Models {
	public static final ThinkingLevel[] EXTENDED_THINKING_LEVELS = ThinkingLevel.values();

	public Models() {}
}
