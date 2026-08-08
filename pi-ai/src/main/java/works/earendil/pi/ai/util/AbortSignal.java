package works.earendil.pi.ai.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Cooperative cancellation token, standing in for the DOM AbortSignal used by
 * the TS implementation. Thread-safe.
 */
public final class AbortSignal {
	private final Object lock = new Object();
	private boolean aborted;
	private final List<Runnable> listeners = new ArrayList<>();

	public void abort() {
		List<Runnable> toRun;
		synchronized (lock) {
			if (aborted) {
				return;
			}
			aborted = true;
			toRun = new ArrayList<>(listeners);
			listeners.clear();
		}
		for (Runnable listener : toRun) {
			listener.run();
		}
	}

	public boolean isAborted() {
		synchronized (lock) {
			return aborted;
		}
	}

	/** Registers a listener, invoking it immediately if already aborted. */
	public void onAbort(Runnable listener) {
		boolean runNow;
		synchronized (lock) {
			runNow = aborted;
			if (!aborted) {
				listeners.add(listener);
			}
		}
		if (runNow) {
			listener.run();
		}
	}
}
