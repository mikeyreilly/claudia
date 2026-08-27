package com.quaxt.codingagent.tui;

/** Dimensions and palette handed to a component for one rendered frame. */
public final class TuiFrame {
	public int width;
	public int height;
	public Theme theme;

	public TuiFrame(int width, int height, Theme theme) {
		this.width = width;
		this.height = height;
		this.theme = theme;
	}
}
