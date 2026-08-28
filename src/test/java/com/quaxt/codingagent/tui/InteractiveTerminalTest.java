package com.quaxt.codingagent.tui;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jline.terminal.Attributes.LocalFlag;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.DumbTerminal;
import com.quaxt.codingagent.CodingAgentOperations;
import org.junit.jupiter.api.Test;

class InteractiveTerminalTest {
	@Test
	void escapeInterruptsARunningOperationAndRestoresTerminalMode() throws Exception {
		TerminalFixture fixture = terminal();
		Terminal terminal = fixture.terminal();
		setCanonicalAttributes(terminal);
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch interrupted = new CountDownLatch(1);
		CountDownLatch inputMayFinish = new CountDownLatch(1);

		InteractiveTerminal interactive = CodingAgentOperations.newInteractiveTerminal(
				terminal, () -> null, false);
		try {
			Thread input = Thread.ofVirtual().start(() -> {
				try {
					started.await();
					fixture.input().write(0x1b);
					fixture.input().flush();
					inputMayFinish.await();
				} catch (Exception error) {
					throw new AssertionError(error);
				}
			});

			String result;
			try {
				result = assertTimeoutPreemptively(
						Duration.ofSeconds(5),
						() -> CodingAgentOperations.runInterruptibly(interactive,
								() -> {
									assertFalse(terminal.getAttributes().getLocalFlag(LocalFlag.ICANON));
									started.countDown();
									assertTrue(interrupted.await(5, TimeUnit.SECONDS));
									return "stopped";
								},
								interrupted::countDown));
			} finally {
				inputMayFinish.countDown();
			}
			input.join();

			assertEquals("stopped", result);
			assertTrue(terminal.getAttributes().getLocalFlag(LocalFlag.ICANON));
			assertTrue(terminal.getAttributes().getLocalFlag(LocalFlag.ECHO));
		} finally {
			CodingAgentOperations.closeTerminal(interactive);
		}
	}

	@Test
	void suppliesFallbackDimensionsWhenTheTerminalReportsZeroSize() throws Exception {
		TerminalFixture fixture = terminal();
		fixture.terminal().setSize(org.jline.terminal.Size.of(0, 0));

		InteractiveTerminal ignored = CodingAgentOperations.newInteractiveTerminal(
				fixture.terminal(), () -> null, false);
		try {
			assertEquals(80, fixture.terminal().getColumns());
			assertEquals(24, fixture.terminal().getRows());
		} finally {
			CodingAgentOperations.closeTerminal(ignored);
		}
	}

	@Test
	void darkThemeFillsThePromptLineWithADarkGreyBackground() throws Exception {
		TerminalFixture fixture = terminal();

		InteractiveTerminal interactive = CodingAgentOperations.newInteractiveTerminal(
				fixture.terminal(), () -> null, false);
		try {
			fixture.input().write("hello\r".getBytes(StandardCharsets.UTF_8));
			fixture.input().flush();

			String line = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> CodingAgentOperations.readLine(interactive, "\n> "));

			assertEquals("hello", line);
			assertTrue(fixture.output().toString(StandardCharsets.UTF_8)
					.contains("\u001b[48;5;236m\u001b[K> hello"));
		} finally {
			CodingAgentOperations.closeTerminal(interactive);
		}
	}

	@Test
	void submitsAPrepopulatedInputBuffer() throws Exception {
		TerminalFixture fixture = terminal();

		InteractiveTerminal interactive = CodingAgentOperations.newInteractiveTerminal(
				fixture.terminal(), () -> null, false);
		try {
			fixture.input().write("\r".getBytes(StandardCharsets.UTF_8));
			fixture.input().flush();

			String line = assertTimeoutPreemptively(
					Duration.ofSeconds(5), () -> CodingAgentOperations.readLine(
							interactive, "> ", "current session fork"));

			assertEquals("current session fork", line);
			assertTrue(fixture.output().toString(StandardCharsets.UTF_8).contains("> current session fork"));
		} finally {
			CodingAgentOperations.closeTerminal(interactive);
		}
	}

	@Test
	void slashCommandPanelFiltersNavigatesAndInsertsWithoutSubmitting() throws Exception {
		TerminalFixture fixture = terminal();

		InteractiveTerminal interactive = CodingAgentOperations.newInteractiveTerminal(
				fixture.terminal(), () -> null, false);
		try {
			fixture.input().write(
					"/\u001b[B\r\r".getBytes(StandardCharsets.UTF_8));
			fixture.input().flush();

			String line = assertTimeoutPreemptively(
					Duration.ofSeconds(5),
					() -> CodingAgentOperations.readLine(interactive,
							"> ",
							List.of("/models", "/help", "/compact", "/details", "/exit")));

			assertEquals("/details", line);
			String written = fixture.output().toString(StandardCharsets.UTF_8);
			assertTrue(written.contains("╭"));
			assertTrue(written.contains("/compact"));
			assertTrue(written.contains("/details"));
		} finally {
			CodingAgentOperations.closeTerminal(interactive);
		}
	}

	@Test
	void shiftEnterVariantsInsertNewlinesAndEnterSubmitsThePrompt() throws Exception {
		TerminalFixture fixture = terminal();

		InteractiveTerminal interactive = CodingAgentOperations.newInteractiveTerminal(
				fixture.terminal(), () -> null, false);
		try {
			fixture.input().write(
					"first\u001b[13;2usecond\u001b[27;2;13~third\nfourth\r".getBytes(StandardCharsets.UTF_8));
			fixture.input().flush();

			String line = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> CodingAgentOperations.readLine(interactive, "> "));

			assertEquals("first\nsecond\nthird\nfourth", line);
		} finally {
			CodingAgentOperations.closeTerminal(interactive);
		}
	}

	@Test
	void bracketedPasteKeepsMultilineTextUntilEnter() throws Exception {
		TerminalFixture fixture = terminal();

		InteractiveTerminal interactive = CodingAgentOperations.newInteractiveTerminal(
				fixture.terminal(), () -> null, false);
		try {
			fixture.input().write(
					"\u001b[200~first\r\nsecond\u001b[201~\r".getBytes(StandardCharsets.UTF_8));
			fixture.input().flush();

			String line = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> CodingAgentOperations.readLine(interactive, "> "));

			assertEquals("first\nsecond", line);
		} finally {
			CodingAgentOperations.closeTerminal(interactive);
		}
	}

	@Test
	void unbracketedCrLfPasteDoesNotSubmitAtTheLineBreak() throws Exception {
		TerminalFixture fixture = terminal();

		InteractiveTerminal interactive = CodingAgentOperations.newInteractiveTerminal(
				fixture.terminal(), () -> null, false);
		try {
			fixture.input().write("first\r\nsecond\r".getBytes(StandardCharsets.UTF_8));
			fixture.input().flush();

			String line = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> CodingAgentOperations.readLine(interactive, "> "));

			assertEquals("first\nsecond", line);
		} finally {
			CodingAgentOperations.closeTerminal(interactive);
		}
	}

	@Test
	void unbracketedMultilinePasteIsNotMistakenForACommandSelection() throws Exception {
		TerminalFixture fixture = terminal();

		InteractiveTerminal interactive = CodingAgentOperations.newInteractiveTerminal(
				fixture.terminal(), () -> null, false);
		try {
			fixture.input().write("/help\rsecond\r".getBytes(StandardCharsets.UTF_8));
			fixture.input().flush();

			String line = assertTimeoutPreemptively(
					Duration.ofSeconds(5),
					() -> CodingAgentOperations.readLine(interactive, "> ", List.of("/help", "/models")));

			assertEquals("/help\nsecond", line);
		} finally {
			CodingAgentOperations.closeTerminal(interactive);
		}
	}

	@Test
	void restoresLineEditorScreenAndInputBufferAfterSuspend() throws Exception {
		TerminalFixture fixture = terminal();
		Terminal terminal = fixture.terminal();
		setCanonicalAttributes(terminal);
		AtomicBoolean suspended = new AtomicBoolean();

		InteractiveTerminal interactive = CodingAgentOperations.newInteractiveTerminal(
				terminal,
				() -> {
					assertTrue(terminal.getAttributes().getLocalFlag(LocalFlag.ICANON));
					assertTrue(terminal.getAttributes().getLocalFlag(LocalFlag.ECHO));
					suspended.set(true);
					fixture.output().writeBytes("shell activity\n".getBytes(StandardCharsets.UTF_8));
					fixture.input().write("d\r".getBytes(StandardCharsets.UTF_8));
					fixture.input().flush();
					return null;
				},
				true);
		try {
			CodingAgentOperations.println(interactive, "conversation before suspend");
			fixture.input().write("abc\u001a".getBytes(StandardCharsets.UTF_8));
			fixture.input().flush();
			String line = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> CodingAgentOperations.readLine(interactive, "> "));

			assertEquals("abcd", line);
			assertTrue(suspended.get());
			String written = fixture.output().toString(StandardCharsets.UTF_8);
			int shellActivity = written.indexOf("shell activity");
			int redraw = written.indexOf("\u001b[2J\u001b[H\u001b[3J", shellActivity);
			assertTrue(redraw > shellActivity);
			assertTrue(written.indexOf("conversation before suspend", redraw) > redraw);
			assertTrue(written.indexOf("abc", redraw) > redraw);
		} finally {
			CodingAgentOperations.closeTerminal(interactive);
		}
	}

	@Test
	void redrawsTheTrackedScreenAfterAnExternalContinueSignal() throws Exception {
		TerminalFixture fixture = terminal();

		InteractiveTerminal interactive = CodingAgentOperations.newInteractiveTerminal(
				fixture.terminal(), () -> null, true);
		try {
			CodingAgentOperations.println(interactive, "conversation before external suspend");
			fixture.output().writeBytes("shell output after external suspend\n".getBytes(StandardCharsets.UTF_8));

			fixture.terminal().raise(Terminal.Signal.CONT);

			String written = fixture.output().toString(StandardCharsets.UTF_8);
			int shellOutput = written.indexOf("shell output after external suspend");
			int redraw = written.indexOf("\u001b[2J\u001b[H\u001b[3J", shellOutput);
			assertTrue(redraw > shellOutput);
			assertTrue(written.indexOf("conversation before external suspend", redraw) > redraw);
		} finally {
			CodingAgentOperations.closeTerminal(interactive);
		}
	}

	@Test
	void replacesTheTrackedMainScreenDocument() throws Exception {
		TerminalFixture fixture = terminal();

		InteractiveTerminal interactive = CodingAgentOperations.newInteractiveTerminal(
				fixture.terminal(),
				() -> {
					fixture.input().write('\r');
					fixture.input().flush();
					return null;
				},
				true);
		try {
			CodingAgentOperations.println(interactive, "discarded session");
			CodingAgentOperations.replaceScreen(interactive, "restored session\n");
			fixture.input().write(0x1a);
			fixture.input().flush();

			assertTimeoutPreemptively(Duration.ofSeconds(5), () -> CodingAgentOperations.readLine(
					interactive, "> "));

			String written = fixture.output().toString(StandardCharsets.UTF_8);
			String finalFrame = written.substring(written.lastIndexOf("\u001b[2J\u001b[H\u001b[3J"));
			assertTrue(finalFrame.contains("restored session"));
			assertFalse(finalFrame.contains("discarded session"));
		} finally {
			CodingAgentOperations.closeTerminal(interactive);
		}
	}

	@Test
	void invokesConfiguredApplicationActionsWithoutLosingInput() throws Exception {
		TerminalFixture fixture = terminal();
		AtomicBoolean invoked = new AtomicBoolean();

		InteractiveTerminal interactive = CodingAgentOperations.newInteractiveTerminal(
				fixture.terminal(), () -> null, false);
		try {
			CodingAgentOperations.bindAppAction(interactive, "expandTools", () -> {
				invoked.set(true);
				try {
					CodingAgentOperations.runComponent(interactive, immediateComponent());
				} catch (java.io.IOException error) {
					throw new AssertionError(error);
				}
				CodingAgentOperations.printAbove(interactive, "details opened");
			});
			fixture.input().write("ab\u000fcd\r".getBytes(StandardCharsets.UTF_8));
			fixture.input().flush();

			String line = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> CodingAgentOperations.readLine(interactive, "> "));

			assertEquals("abcd", line);
			assertTrue(invoked.get());
			assertTrue(fixture.output().toString(StandardCharsets.UTF_8).contains("details opened"));
		} finally {
			CodingAgentOperations.closeTerminal(interactive);
		}
	}

	@Test
	void restoresShellModeWhenNestedFullScreenIsSuspendedFromTheLineEditor() throws Exception {
		TerminalFixture fixture = terminal();
		Terminal terminal = fixture.terminal();
		setCanonicalAttributes(terminal);
		AtomicBoolean suspended = new AtomicBoolean();

		InteractiveTerminal interactive = CodingAgentOperations.newInteractiveTerminal(
				terminal,
				() -> {
					assertTrue(terminal.getAttributes().getLocalFlag(LocalFlag.ICANON));
					assertTrue(terminal.getAttributes().getLocalFlag(LocalFlag.ECHO));
					suspended.set(true);
					fixture.output().writeBytes("nested shell activity\n".getBytes(StandardCharsets.UTF_8));
					fixture.input().write("\rcd\r".getBytes(StandardCharsets.UTF_8));
					fixture.input().flush();
					return null;
				},
				true);
		try {
			CodingAgentOperations.println(interactive, "conversation behind nested selector");
			CodingAgentOperations.bindAppAction(interactive, "expandTools", () -> {
				try {
					CodingAgentOperations.runComponent(
							interactive,
							CodingAgentOperations.fuzzySelectorComponent(CodingAgentOperations.fuzzySelector(
									"Details", List.of(CodingAgentOperations.selectItem("done", "Done")), 0, false)));
				} catch (java.io.IOException error) {
					throw new AssertionError(error);
				}
			});
			fixture.input().write("ab\u000f\u001a".getBytes(StandardCharsets.UTF_8));
			fixture.input().flush();

			String line = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> CodingAgentOperations.readLine(interactive, "> "));

			assertEquals("abcd", line);
			assertTrue(suspended.get());
			String written = fixture.output().toString(StandardCharsets.UTF_8);
			int shellActivity = written.indexOf("nested shell activity");
			int redraw = written.indexOf("\u001b[2J\u001b[H\u001b[3J", shellActivity);
			assertTrue(redraw > shellActivity);
			assertTrue(written.indexOf("conversation behind nested selector", redraw) > redraw);
		} finally {
			CodingAgentOperations.closeTerminal(interactive);
		}
	}

	@Test
	void restoresFullScreenStateAroundSuspendAndContinuesTheSelector() throws Exception {
		TerminalFixture fixture = terminal();
		Terminal terminal = fixture.terminal();
		setCanonicalAttributes(terminal);
		AtomicBoolean suspended = new AtomicBoolean();

		InteractiveTerminal interactive = CodingAgentOperations.newInteractiveTerminal(
				terminal,
				() -> {
					assertTrue(terminal.getAttributes().getLocalFlag(LocalFlag.ICANON));
					assertTrue(terminal.getAttributes().getLocalFlag(LocalFlag.ECHO));
					suspended.set(true);
					fixture.output().writeBytes("shell activity in full screen\n".getBytes(StandardCharsets.UTF_8));
					fixture.input().write('\r');
					fixture.input().flush();
					return null;
				},
				true);
		try {
			CodingAgentOperations.println(interactive, "conversation behind selector");
			fixture.input().write(0x1a);
			fixture.input().flush();
			String selected = assertTimeoutPreemptively(
					Duration.ofSeconds(5),
					() -> CodingAgentOperations.runComponent(
							interactive,
							CodingAgentOperations.fuzzySelectorComponent(CodingAgentOperations.fuzzySelector(
									"Models",
									List.of(CodingAgentOperations.selectItem("gpt", "gpt-5.6-terra")),
									0,
									true))));

			assertEquals("gpt", selected);
			assertTrue(suspended.get());
			String written = fixture.output().toString(StandardCharsets.UTF_8);
			assertTrue(count(written, "\u001b[?1049h") >= 2);
			assertTrue(count(written, "\u001b[?1049l") >= 2);
			int shellActivity = written.indexOf("shell activity in full screen");
			int mainScreenRedraw = written.indexOf("\u001b[2J\u001b[H\u001b[3J", shellActivity);
			assertTrue(mainScreenRedraw > shellActivity);
			assertTrue(written.indexOf("conversation behind selector", mainScreenRedraw) > mainScreenRedraw);
		} finally {
			CodingAgentOperations.closeTerminal(interactive);
		}
	}

	@Test
	void alignsStatusBarSegmentsToTheFullTerminalWidth() {
		String line = CodingAgentOperations.statusBarLine(
				"~/xa/coding-agent [main]", "GPT-5.6 Sol Max (0%)", 60, Theme.PLAIN);

		assertEquals(60, line.length());
		assertTrue(line.startsWith("~/xa/coding-agent [main]"));
		assertTrue(line.endsWith("GPT-5.6 Sol Max (0%)"));

		String styled = CodingAgentOperations.statusBarLine("left", "right", 20, Theme.DARK);
		assertTrue(styled.startsWith(Theme.DARK.muted));
		assertTrue(styled.endsWith(Theme.DARK.reset));

		String narrow = CodingAgentOperations.statusBarLine(
				"~/a/very/long/working/directory", "GPT-5.6 Sol Max (0%)", 30, Theme.PLAIN);
		assertTrue(CodingAgentOperations.visibleWidth(narrow) <= 30);
		assertTrue(narrow.endsWith("GPT-5.6 Sol Max (0%)"));

		assertEquals("left only", CodingAgentOperations.statusBarLine("left only", "", 20, Theme.PLAIN));
		assertEquals("", CodingAgentOperations.statusBarLine("", "", 20, Theme.PLAIN));
	}

	@Test
	void activityHasPriorityAndOnlyReadyUsesTheGreenAccent() {
		String ready = CodingAgentOperations.statusBarLine(
				"● Ready",
				InteractiveTerminal.StatusAccent.READY,
				"~/xa/coding-agent [main]",
				"GPT-5.6 Sol Max (24%)",
				80,
				Theme.DARK);
		String waiting = CodingAgentOperations.statusBarLine(
				"◐ Waiting for model · 12s",
				InteractiveTerminal.StatusAccent.ACTIVE,
				"~/xa/coding-agent [main]",
				"GPT-5.6 Sol Max (24%)",
				80,
				Theme.DARK);

		assertTrue(ready.startsWith(CodingAgentOperations.readyStatus(Theme.DARK) + "● Ready"));
		assertTrue(ready.contains(Theme.DARK.reset + Theme.DARK.muted + " │ "));
		assertTrue(waiting.startsWith(CodingAgentOperations.activeStatus(
				Theme.DARK) + "◐ Waiting for model"));
		assertFalse(waiting.contains(CodingAgentOperations.readyStatus(Theme.DARK)));
		String lightReady = CodingAgentOperations.statusBarLine(
				"● Ready", InteractiveTerminal.StatusAccent.READY, "path", "model", 30, Theme.LIGHT);
		String lightWaiting = CodingAgentOperations.statusBarLine(
				"Waiting", InteractiveTerminal.StatusAccent.ACTIVE, "path", "model", 30, Theme.LIGHT);
		assertTrue(lightReady.startsWith(CodingAgentOperations.readyStatus(Theme.LIGHT) + "● Ready"));
		assertFalse(lightWaiting.contains(CodingAgentOperations.readyStatus(Theme.LIGHT)));
		String plainReady = CodingAgentOperations.statusBarLine(
				"● Ready", InteractiveTerminal.StatusAccent.READY, "path", "model", 30, Theme.PLAIN);
		assertFalse(plainReady.contains("\u001b"));
		assertEquals(80, CodingAgentOperations.visibleWidth(ready));
		assertEquals(80, CodingAgentOperations.visibleWidth(waiting));

		for (InteractiveTerminal.StatusAccent accent : List.of(
				InteractiveTerminal.StatusAccent.NONE,
				InteractiveTerminal.StatusAccent.ACTIVE,
				InteractiveTerminal.StatusAccent.TOOL,
				InteractiveTerminal.StatusAccent.WARNING)) {
			String line = CodingAgentOperations.statusBarLine(
					"Busy", accent, "path", "model", 30, Theme.DARK);
			assertFalse(line.contains(CodingAgentOperations.readyStatus(Theme.DARK)), accent.toString());
		}
	}

	@Test
	void narrowStatusBarsKeepActivityBeforeMetadata() {
		String line = CodingAgentOperations.statusBarLine(
				"◐ Waiting for model · 12s",
				InteractiveTerminal.StatusAccent.ACTIVE,
				"~/a/very/long/working/directory",
				"GPT-5.6 Sol Max (24%)",
				20,
				Theme.PLAIN);

		assertEquals("◐ Waiting for mod...", line);
		assertFalse(line.contains("GPT"));
		assertFalse(line.contains("~/"));
		assertEquals(20, CodingAgentOperations.visibleWidth(line));
	}

	@Test
	void statusBarUpdatesAreIgnoredOnTerminalsWithoutCursorAddressing() throws Exception {
		TerminalFixture fixture = terminal();

		InteractiveTerminal interactive = CodingAgentOperations.newInteractiveTerminal(
				fixture.terminal(), () -> null, false);
		try {
			CodingAgentOperations.setStatus(interactive, "", InteractiveTerminal.StatusAccent.NONE, "~/xa/coding-agent [main]", "GPT-5.6 Sol Max (0%)");
			CodingAgentOperations.println(interactive, "conversation output");

			assertTrue(fixture.output().toString(StandardCharsets.UTF_8).contains("conversation output"));
		} finally {
			CodingAgentOperations.closeTerminal(interactive);
		}
	}

	private static TuiComponent<Void> immediateComponent() {
		return new TuiComponent<>(frame -> List.of("details"), input -> {}, () -> true, () -> null);
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
