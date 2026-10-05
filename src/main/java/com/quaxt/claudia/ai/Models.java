package com.quaxt.claudia.ai;

import com.quaxt.claudia.ai.types.ThinkingLevel;

/**
 * Model helper constants. The cost calculation and thinking-level clamping
 * behavior ported from packages/ai/src/models.ts lives in
 * ClaudiaOperations.
 */
public final class Models {
	public static final ThinkingLevel[] EXTENDED_THINKING_LEVELS = ThinkingLevel.values();

	public Models() {}
}
