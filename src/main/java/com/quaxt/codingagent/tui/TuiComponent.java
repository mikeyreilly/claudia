package com.quaxt.codingagent.tui;

import java.util.List;

/** Stateful component hosted by {@link TuiRuntime}. */
public interface TuiComponent<T> {
	List<String> render(int width, int height, Theme theme);

	void handle(TuiInput input);

	boolean isComplete();

	T result();
}
