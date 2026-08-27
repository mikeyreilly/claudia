package com.quaxt.codingagent.ai.util;

import java.security.SecureRandom;

/**
 * Generator state for time-ordered UUIDv7 values. Port of the state held by
 * packages/ai/src/utils/uuid.ts; the generator itself lives in
 * CodingAgentOperations, which uses this class as its monitor.
 */
public final class Uuid {
	public static final SecureRandom RANDOM = new SecureRandom();
	public static long lastTimestamp = Long.MIN_VALUE;
	public static long sequence;

	public Uuid() {}
}
