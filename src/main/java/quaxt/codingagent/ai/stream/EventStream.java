package works.earendil.pi.ai.stream;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Producer/consumer event stream. Java port of EventStream in
 * packages/ai/src/utils/event-stream.ts: producers push() events (possibly
 * before a consumer attaches), the consumer iterates blocking until a terminal
 * event or end() arrives, and result() exposes the extracted final value.
 * Consumers are expected to run on virtual threads, so blocking is cheap.
 */
public class EventStream<T, R> implements Iterable<T> {
	private final Deque<T> queue = new ArrayDeque<>();
	private final Object lock = new Object();
	private boolean done;
	private final CompletableFuture<R> finalResult = new CompletableFuture<>();
	private final Predicate<T> isComplete;
	private final Function<T, R> extractResult;

	public EventStream(Predicate<T> isComplete, Function<T, R> extractResult) {
		this.isComplete = isComplete;
		this.extractResult = extractResult;
	}

	/** Push an event. Ignored after the stream is done. */
	public void push(T event) {
		synchronized (lock) {
			if (done) {
				return;
			}
			if (isComplete.test(event)) {
				done = true;
				finalResult.complete(extractResult.apply(event));
			}
			queue.addLast(event);
			lock.notifyAll();
		}
	}

	/** Terminate the stream without a terminal event, optionally supplying the result. */
	public void end(R result) {
		synchronized (lock) {
			done = true;
			if (result != null) {
				finalResult.complete(result);
			}
			lock.notifyAll();
		}
	}

	public void end() {
		end(null);
	}

	/** Blocks until the terminal event arrives and returns the extracted result. */
	public R result() throws InterruptedException {
		try {
			return finalResult.get();
		} catch (java.util.concurrent.ExecutionException e) {
			throw new IllegalStateException(e.getCause());
		}
	}

	/**
	 * Single-consumer blocking iterator. hasNext() blocks until an event is
	 * available or the stream is exhausted (done and queue drained).
	 */
	@Override
	public Iterator<T> iterator() {
		return new Iterator<>() {
			@Override
			public boolean hasNext() {
				synchronized (lock) {
					while (queue.isEmpty() && !done) {
						try {
							lock.wait();
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
							return false;
						}
					}
					return !queue.isEmpty();
				}
			}

			@Override
			public T next() {
				synchronized (lock) {
					if (!hasNext()) {
						throw new NoSuchElementException();
					}
					return queue.removeFirst();
				}
			}
		};
	}
}
