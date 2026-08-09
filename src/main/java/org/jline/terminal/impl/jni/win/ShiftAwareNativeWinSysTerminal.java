/* Windows terminal setup adapted from JLine (BSD-3-Clause): https://github.com/jline/jline3. */
package org.jline.terminal.impl.jni.win;

import static org.jline.nativ.Kernel32.GetStdHandle;
import static org.jline.nativ.Kernel32.STD_INPUT_HANDLE;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.Charset;
import org.jline.nativ.Kernel32;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.AbstractWindowsTerminal;
import org.jline.terminal.spi.SystemStream;
import org.jline.terminal.spi.TerminalProvider;
import org.jline.utils.OSUtils;

/**
 * JLine's Windows input pump normally discards Shift on Enter because both
 * Shift+Enter and Enter have the same Unicode character. Preserve the modifier
 * as LF, which codingagent binds to insert-newline while CR remains submit.
 */
public final class ShiftAwareNativeWinSysTerminal extends NativeWinSysTerminal {
	private static final short VK_RETURN = 0x0d;
	private static final int SHIFT_ONLY = 0x01;

	private ShiftAwareNativeWinSysTerminal(
			TerminalProvider provider,
			SystemStream systemStream,
			Writer writer,
			String name,
			String type,
			Charset encoding,
			Charset inputEncoding,
			Charset outputEncoding,
			boolean nativeSignals,
			Terminal.SignalHandler signalHandler,
			long inConsole,
			int inMode,
			long outConsole,
			int outMode)
			throws IOException {
		super(
				provider,
				systemStream,
				writer,
				name,
				type,
				encoding,
				inputEncoding,
				outputEncoding,
				nativeSignals,
				signalHandler,
				inConsole,
				inMode,
				outConsole,
				outMode);
	}

	public static ShiftAwareNativeWinSysTerminal createTerminal(
			TerminalProvider provider,
			SystemStream systemStream,
			String name,
			String type,
			boolean ansiPassThrough,
			Charset encoding,
			Charset inputEncoding,
			Charset outputEncoding,
			boolean nativeSignals,
			Terminal.SignalHandler signalHandler,
			boolean paused)
			throws IOException {
		long consoleIn = GetStdHandle(STD_INPUT_HANDLE);
		int[] inMode = new int[1];
		if (Kernel32.GetConsoleMode(consoleIn, inMode) == 0) {
			throw new IOException("Failed to get console mode: " + NativeWinSysTerminal.getLastErrorMessage());
		}

		long consoleOut = NativeWinSysTerminal.getConsole(systemStream);
		int[] outMode = new int[1];
		if (Kernel32.GetConsoleMode(consoleOut, outMode) == 0) {
			throw new IOException("Failed to get console mode: " + NativeWinSysTerminal.getLastErrorMessage());
		}

		Writer writer;
		if (ansiPassThrough) {
			type = type != null ? type : (OSUtils.IS_CONEMU ? TYPE_WINDOWS_CONEMU : TYPE_WINDOWS);
			writer = new NativeWinConsoleWriter(consoleOut);
		} else if (enableVirtualTerminalProcessing(consoleOut, outMode[0])) {
			type = type != null ? type : TYPE_WINDOWS_VTP;
			writer = new NativeWinConsoleWriter(consoleOut);
		} else if (OSUtils.IS_CONEMU) {
			type = type != null ? type : TYPE_WINDOWS_CONEMU;
			writer = new NativeWinConsoleWriter(consoleOut);
		} else {
			type = type != null ? type : TYPE_WINDOWS;
			writer = new WindowsAnsiWriter(new BufferedWriter(new NativeWinConsoleWriter(consoleOut)));
		}

		ShiftAwareNativeWinSysTerminal terminal = new ShiftAwareNativeWinSysTerminal(
				provider,
				systemStream,
				writer,
				name,
				type,
				encoding,
				inputEncoding,
				outputEncoding,
				nativeSignals,
				signalHandler,
				consoleIn,
				inMode[0],
				consoleOut,
				outMode[0]);
		if (!paused) terminal.resume();
		return terminal;
	}

	@Override
	protected String getEscapeSequence(short keyCode, int keyState) {
		String shiftedEnter = shiftedEnterSequence(keyCode, keyState);
		return shiftedEnter != null ? shiftedEnter : super.getEscapeSequence(keyCode, keyState);
	}

	static String shiftedEnterSequence(short keyCode, int keyState) {
		return keyCode == VK_RETURN && keyState == SHIFT_ONLY ? "\n" : null;
	}

	private static boolean enableVirtualTerminalProcessing(long console, int mode) {
		return Kernel32.SetConsoleMode(
				console, mode | AbstractWindowsTerminal.ENABLE_VIRTUAL_TERMINAL_PROCESSING) != 0;
	}
}
