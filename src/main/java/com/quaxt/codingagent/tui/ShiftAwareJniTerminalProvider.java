package com.quaxt.codingagent.tui;

import java.io.IOException;
import java.nio.charset.Charset;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.jni.JniTerminalProvider;
import org.jline.terminal.impl.jni.win.ShiftAwareNativeWinSysTerminal;
import org.jline.terminal.spi.SystemStream;

/** JNI terminal provider that retains Shift+Enter on the Windows console. */
public final class ShiftAwareJniTerminalProvider extends JniTerminalProvider {
	@Override
	public Terminal winSysTerminal(
			String name,
			String type,
			boolean ansiPassThrough,
			Charset encoding,
			Charset stdinEncoding,
			Charset stdoutEncoding,
			Charset stderrEncoding,
			boolean nativeSignals,
			Terminal.SignalHandler signalHandler,
			boolean paused,
			SystemStream systemStream)
			throws IOException {
		Charset outputEncoding = systemStream == SystemStream.Error ? stderrEncoding : stdoutEncoding;
		return ShiftAwareNativeWinSysTerminal.createTerminal(
				this,
				systemStream,
				name,
				type,
				ansiPassThrough,
				encoding,
				stdinEncoding,
				outputEncoding,
				nativeSignals,
				signalHandler,
				paused);
	}
}
