package com.quaxt.claudia.ai.stream;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;

/**
 * Producer/consumer event stream state. Java port of EventStream in
 * packages/ai/src/utils/event-stream.ts: producers push events (possibly before
 * a consumer attaches), the consumer iterates blocking until a terminal event
 * or end arrives, and the final result is exposed through finalResult.
 * Consumers are expected to run on virtual threads, so blocking is cheap.
 *
 * <p>Pure data carrier: all stream behavior lives in ClaudiaOperations.
 * The stream instance itself is the monitor guarding queue/done.
 */
public class EventStream<T, R> {
	public Deque<T> queue = new ArrayDeque<>();
	public boolean done;
	public CompletableFuture<R> finalResult = new CompletableFuture<>();

	public EventStream() {}
}
