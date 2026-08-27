package com.quaxt.codingagent.tui;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Carrier binding one stateful component to the full-screen host. Each field
 * holds a JDK functional value over the component's own state; the host
 * dispatches through the static operations in CodingAgentOperations.
 */
public final class TuiComponent<T> {
	public Function<TuiFrame, List<String>> render;
	public Consumer<TuiInput> handle;
	public BooleanSupplier complete;
	public Supplier<T> result;

	public TuiComponent(
			Function<TuiFrame, List<String>> render,
			Consumer<TuiInput> handle,
			BooleanSupplier complete,
			Supplier<T> result) {
		this.render = render;
		this.handle = handle;
		this.complete = complete;
		this.result = result;
	}
}
