package works.earendil.pi.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jline.terminal.Attributes.LocalFlag;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.Test;

class InteractiveTerminalTest {
	@Test
	void restoresLineEditorScreenAndInputBufferAfterSuspend() throws Exception {
		TerminalFixture fixture = terminal();
		Terminal terminal = fixture.terminal();
		setCanonicalAttributes(terminal);
		AtomicBoolean suspended = new AtomicBoolean();

		try (InteractiveTerminal interactive = new InteractiveTerminal(
				terminal,
				() -> {
					assertTrue(terminal.getAttributes().getLocalFlag(LocalFlag.ICANON));
					assertTrue(terminal.getAttributes().getLocalFlag(LocalFlag.ECHO));
					suspended.set(true);
					fixture.output().writeBytes("shell activity\n".getBytes(StandardCharsets.UTF_8));
					fixture.input().write("d\n".getBytes(StandardCharsets.UTF_8));
					fixture.input().flush();
				},
				true)) {
			interactive.println("conversation before suspend");
			fixture.input().write("abc\u001a".getBytes(StandardCharsets.UTF_8));
			fixture.input().flush();
			String line = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> interactive.readLine("> "));

			assertEquals("abcd", line);
			assertTrue(suspended.get());
			String written = fixture.output().toString(StandardCharsets.UTF_8);
			int shellActivity = written.indexOf("shell activity");
			int redraw = written.indexOf("\u001b[2J\u001b[H\u001b[3J", shellActivity);
			assertTrue(redraw > shellActivity);
			assertTrue(written.indexOf("conversation before suspend", redraw) > redraw);
			assertTrue(written.indexOf("abc", redraw) > redraw);
		}
	}

	@Test
	void redrawsTheTrackedScreenAfterAnExternalContinueSignal() throws Exception {
		TerminalFixture fixture = terminal();

		try (InteractiveTerminal interactive = new InteractiveTerminal(fixture.terminal(), () -> {}, true)) {
			interactive.println("conversation before external suspend");
			fixture.output().writeBytes("shell output after external suspend\n".getBytes(StandardCharsets.UTF_8));

			fixture.terminal().raise(Terminal.Signal.CONT);

			String written = fixture.output().toString(StandardCharsets.UTF_8);
			int shellOutput = written.indexOf("shell output after external suspend");
			int redraw = written.indexOf("\u001b[2J\u001b[H\u001b[3J", shellOutput);
			assertTrue(redraw > shellOutput);
			assertTrue(written.indexOf("conversation before external suspend", redraw) > redraw);
		}
	}

	@Test
	void replacesTheTrackedMainScreenDocument() throws Exception {
		TerminalFixture fixture = terminal();

		try (InteractiveTerminal interactive = new InteractiveTerminal(
				fixture.terminal(),
				() -> {
					fixture.input().write('\n');
					fixture.input().flush();
				},
				true)) {
			interactive.println("discarded session");
			interactive.replaceScreen("restored session\n");
			fixture.input().write(0x1a);
			fixture.input().flush();

			assertTimeoutPreemptively(Duration.ofSeconds(5), () -> interactive.readLine("> "));

			String written = fixture.output().toString(StandardCharsets.UTF_8);
			String finalFrame = written.substring(written.lastIndexOf("\u001b[2J\u001b[H\u001b[3J"));
			assertTrue(finalFrame.contains("restored session"));
			assertFalse(finalFrame.contains("discarded session"));
		}
	}

	@Test
	void invokesConfiguredApplicationActionsWithoutLosingInput() throws Exception {
		TerminalFixture fixture = terminal();
		AtomicBoolean invoked = new AtomicBoolean();

		try (InteractiveTerminal interactive = new InteractiveTerminal(fixture.terminal(), () -> {}, false)) {
			interactive.bindAppAction("expandTools", () -> {
				invoked.set(true);
				try {
					interactive.run(new ImmediateComponent());
				} catch (java.io.IOException error) {
					throw new AssertionError(error);
				}
				interactive.printAbove("details opened");
			});
			fixture.input().write("ab\u000fcd\n".getBytes(StandardCharsets.UTF_8));
			fixture.input().flush();

			String line = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> interactive.readLine("> "));

			assertEquals("abcd", line);
			assertTrue(invoked.get());
			assertTrue(fixture.output().toString(StandardCharsets.UTF_8).contains("details opened"));
		}
	}

	@Test
	void restoresShellModeWhenNestedFullScreenIsSuspendedFromTheLineEditor() throws Exception {
		TerminalFixture fixture = terminal();
		Terminal terminal = fixture.terminal();
		setCanonicalAttributes(terminal);
		AtomicBoolean suspended = new AtomicBoolean();

		try (InteractiveTerminal interactive = new InteractiveTerminal(
				terminal,
				() -> {
					assertTrue(terminal.getAttributes().getLocalFlag(LocalFlag.ICANON));
					assertTrue(terminal.getAttributes().getLocalFlag(LocalFlag.ECHO));
					suspended.set(true);
					fixture.output().writeBytes("nested shell activity\n".getBytes(StandardCharsets.UTF_8));
					fixture.input().write("\rcd\n".getBytes(StandardCharsets.UTF_8));
					fixture.input().flush();
				},
				true)) {
			interactive.println("conversation behind nested selector");
			interactive.bindAppAction("expandTools", () -> {
				try {
					interactive.run(new FuzzySelector<>(
							"Details", List.of(new SelectItem<>("done", "Done")), 0, false));
				} catch (java.io.IOException error) {
					throw new AssertionError(error);
				}
			});
			fixture.input().write("ab\u000f\u001a".getBytes(StandardCharsets.UTF_8));
			fixture.input().flush();

			String line = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> interactive.readLine("> "));

			assertEquals("abcd", line);
			assertTrue(suspended.get());
			String written = fixture.output().toString(StandardCharsets.UTF_8);
			int shellActivity = written.indexOf("nested shell activity");
			int redraw = written.indexOf("\u001b[2J\u001b[H\u001b[3J", shellActivity);
			assertTrue(redraw > shellActivity);
			assertTrue(written.indexOf("conversation behind nested selector", redraw) > redraw);
		}
	}

	@Test
	void restoresFullScreenStateAroundSuspendAndContinuesTheSelector() throws Exception {
		TerminalFixture fixture = terminal();
		Terminal terminal = fixture.terminal();
		setCanonicalAttributes(terminal);
		AtomicBoolean suspended = new AtomicBoolean();

		try (InteractiveTerminal interactive = new InteractiveTerminal(
				terminal,
				() -> {
					assertTrue(terminal.getAttributes().getLocalFlag(LocalFlag.ICANON));
					assertTrue(terminal.getAttributes().getLocalFlag(LocalFlag.ECHO));
					suspended.set(true);
					fixture.output().writeBytes("shell activity in full screen\n".getBytes(StandardCharsets.UTF_8));
					fixture.input().write('\r');
					fixture.input().flush();
				},
				true)) {
			interactive.println("conversation behind selector");
			fixture.input().write(0x1a);
			fixture.input().flush();
			String selected = assertTimeoutPreemptively(
					Duration.ofSeconds(5),
					() -> interactive.run(new FuzzySelector<>(
							"Models",
							List.of(new SelectItem<>("gpt", "gpt-5.6-terra")),
							0,
							true)));

			assertEquals("gpt", selected);
			assertTrue(suspended.get());
			String written = fixture.output().toString(StandardCharsets.UTF_8);
			assertTrue(count(written, "\u001b[?1049h") >= 2);
			assertTrue(count(written, "\u001b[?1049l") >= 2);
			int shellActivity = written.indexOf("shell activity in full screen");
			int mainScreenRedraw = written.indexOf("\u001b[2J\u001b[H\u001b[3J", shellActivity);
			assertTrue(mainScreenRedraw > shellActivity);
			assertTrue(written.indexOf("conversation behind selector", mainScreenRedraw) > mainScreenRedraw);
		}
	}

	private static final class ImmediateComponent implements TuiComponent<Void> {
		@Override
		public List<String> render(int width, int height, Theme theme) {
			return List.of("details");
		}

		@Override
		public void handle(TuiInput input) {}

		@Override
		public boolean isComplete() {
			return true;
		}

		@Override
		public Void result() {
			return null;
		}
	}

	private static TerminalFixture terminal() throws Exception {
		PipedInputStream input = new PipedInputStream();
		PipedOutputStream inputWriter = new PipedOutputStream(input);
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		Terminal terminal = new DumbTerminal("test", "xterm-256color", input, output, StandardCharsets.UTF_8);
		terminal.setSize(org.jline.terminal.Size.of(80, 24));
		return new TerminalFixture(terminal, inputWriter, output);
	}

	private static void setCanonicalAttributes(Terminal terminal) {
		var attributes = terminal.getAttributes();
		attributes.setLocalFlag(LocalFlag.ICANON, true);
		attributes.setLocalFlag(LocalFlag.ECHO, true);
		terminal.setAttributes(attributes);
	}

	private static int count(String value, String target) {
		int count = 0;
		int offset = 0;
		while ((offset = value.indexOf(target, offset)) >= 0) {
			count++;
			offset += target.length();
		}
		return count;
	}

	private record TerminalFixture(Terminal terminal, PipedOutputStream input, ByteArrayOutputStream output) {}
}
