package com.quaxt.claudia.tui;

/** Dimensions handed to a component for one rendered frame. */
public final class TuiFrame {
	public int width;
	public int height;

	public TuiFrame(int width, int height) {
		this.width = width;
		this.height = height;
	}
}
