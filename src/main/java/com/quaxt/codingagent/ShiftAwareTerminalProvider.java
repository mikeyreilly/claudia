package com.quaxt.codingagent;

import org.jline.terminal.Terminal;
import org.jline.terminal.impl.jni.JniTerminalProvider;
import org.jline.terminal.impl.jni.win.ShiftAwareNativeWinSysTerminal;
import org.jline.terminal.spi.SystemStream;

import java.io.IOException;
import java.nio.charset.Charset;

public class ShiftAwareTerminalProvider extends JniTerminalProvider {
    /**
     * JLine's named provider SPI requires an instance override. The terminal
     * construction remains in the static operation below.
     */
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
