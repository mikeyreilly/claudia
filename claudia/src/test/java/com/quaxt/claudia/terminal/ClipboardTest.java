package com.quaxt.claudia.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClipboardTest {
    private static final Terminal TERMINAL =
            Terminal.streams("xterm-256color", new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), 80, 24);

    private static List<String> commands(Map<String, String> environment, String osName) {
        return new Clipboard.Platform(TERMINAL, environment, osName).commands().stream()
                .map(command -> String.join(" ", command))
                .toList();
    }

    @Test
    void choosesThePlatformsCopyCommandLocallyAndTheTerminalOverSsh() {
        assertEquals(List.of("pbcopy"), commands(Map.of(), "Mac OS X"));
        assertEquals(List.of("clip.exe"), commands(Map.of(), "Windows 11"));
        assertEquals(List.of("clip.exe"), commands(Map.of("WSL_DISTRO_NAME", "Ubuntu"), "Linux"));
        assertEquals(List.of("wl-copy", "xclip -selection clipboard -in", "xsel --clipboard --input"),
                commands(Map.of("WAYLAND_DISPLAY", "wayland-0", "DISPLAY", ":0"), "Linux"));
        assertEquals(List.of(), commands(Map.of(), "Linux"));
        assertEquals(List.of(), commands(Map.of("SSH_CONNECTION", "1.2.3.4 22 5.6.7.8 22"), "Mac OS X"));
    }

    @Test
    void withoutACopyCommandTheTerminalSetsTheClipboard() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Terminal terminal = Terminal.streams("xterm-256color", new ByteArrayInputStream(new byte[0]), output, 80, 24);
        new Clipboard.Platform(terminal, Map.of("SSH_TTY", "/dev/pts/1"), "Linux").copy("héllo\n");
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("\u001b]52;c;aMOpbGxvCg==\u0007"));
    }
}
