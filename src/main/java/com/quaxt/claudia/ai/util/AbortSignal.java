package com.quaxt.claudia.ai.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Cooperative cancellation token state, standing in for the DOM AbortSignal
 * used by the TS implementation. Pure data carrier: the abort/listen behavior
 * lives in ClaudiaOperations, which uses the signal instance as its monitor.
 */
public final class AbortSignal {
	public boolean aborted;
	public List<Runnable> listeners = new ArrayList<>();

	public AbortSignal() {}
}
