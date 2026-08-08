package com.quaxt.codingagent.tui;

import java.io.IOException;
import java.util.List;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.InfoCmp.Capability;

/** Alternate-screen host with resize, mouse, and suspend/resume lifecycle management. */
final class TuiRuntime {
	private final Terminal terminal;
	private final Theme theme;
	private final SuspendAction suspendAction;
	private final Runnable resumeMainScreen;
	private final AnsiRenderer renderer = new AnsiRenderer();
	private Attributes originalAttributes;
	private boolean active;

	TuiRuntime(Terminal terminal, Theme theme, SuspendAction suspendAction, Runnable resumeMainScreen) {
		this.terminal = terminal;
		this.theme = theme;
		this.suspendAction = suspendAction;
		this.resumeMainScreen = resumeMainScreen;
	}

	<T> T run(TuiComponent<T> component) throws IOException {
		int width = terminal.getColumns();
		int height = terminal.getRows();
		try {
			start();
			component.handle(new TuiInput.Resize(width, height));
			render(component, width, height);
			while (!component.isComplete()) {
				TuiInput input = TuiInputReader.read(terminal.reader(), 100);
				int nextWidth = terminal.getColumns();
				int nextHeight = terminal.getRows();
				if (nextWidth != width || nextHeight != height) {
					width = nextWidth;
					height = nextHeight;
					component.handle(new TuiInput.Resize(width, height));
					renderer.reset();
					clearScreen();
					render(component, width, height);
				}
				if (input == null) {
					continue;
				}
				if (suspendAction != null
						&& input instanceof TuiInput.Key key
						&& key.type() == TuiInput.KeyType.SUSPEND) {
					stop();
					suspendAction.suspend();
					resumeMainScreen.run();
					start();
					width = terminal.getColumns();
					height = terminal.getRows();
					component.handle(new TuiInput.Resize(width, height));
					renderer.reset();
					render(component, width, height);
					continue;
				}
				component.handle(input);
				render(component, width, height);
			}
			return component.result();
		} finally {
			stop();
		}
	}

	private void start() {
		originalAttributes = terminal.enterRawMode();
		if (!terminal.puts(Capability.enter_ca_mode)) {
			terminal.writer().write("\u001b[?1049h");
		}
		terminal.puts(Capability.keypad_xmit);
		terminal.trackMouse(Terminal.MouseTracking.Button);
		if (!terminal.puts(Capability.cursor_invisible)) {
			terminal.writer().write("\u001b[?25l");
		}
		active = true;
		renderer.reset();
		clearScreen();
		terminal.flush();
	}

	private void stop() {
		if (!active) {
			return;
		}
		terminal.trackMouse(Terminal.MouseTracking.Off);
		if (!terminal.puts(Capability.cursor_normal)) {
			terminal.writer().write("\u001b[?25h");
		}
		terminal.puts(Capability.keypad_local);
		if (!terminal.puts(Capability.exit_ca_mode)) {
			terminal.writer().write("\u001b[?1049l");
		}
		terminal.flush();
		if (originalAttributes != null) {
			terminal.setAttributes(originalAttributes);
		}
		active = false;
	}

	private void render(TuiComponent<?> component, int width, int height) {
		int safeWidth = Math.max(20, width);
		int safeHeight = Math.max(5, height);
		List<String> lines = component.render(safeWidth, safeHeight, theme);
		if (lines.size() > safeHeight) {
			lines = lines.subList(0, safeHeight);
		}
		terminal.writer().write(renderer.render(lines));
		terminal.flush();
	}

	private void clearScreen() {
		terminal.writer().write("\u001b[2J\u001b[H");
	}
}
