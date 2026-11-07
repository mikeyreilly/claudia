package com.quaxt.claudia.terminal;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The system clipboard. Locally it runs the platform's copy command
 * ({@code pbcopy}, {@code clip.exe}, {@code wl-copy}, {@code xclip} or
 * {@code xsel}); over SSH, or when none of those works, it asks the terminal
 * to set its clipboard with an OSC 52 sequence.
 */
public interface Clipboard {
    void copy(String text);

    /** The clipboard of the machine running the terminal. */
    static Clipboard system(Terminal terminal) {
        return new Platform(terminal, System.getenv(), System.getProperty("os.name", ""));
    }

    record Platform(Terminal terminal, Map<String, String> environment, String osName) implements Clipboard {
        @Override
        public void copy(String text) {
            for (List<String> command : commands()) {
                if (run(command, text)) return;
            }
            terminal.write("\u001b]52;c;" + Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)) + "\u0007");
        }

        /** The local copy commands to try in order; none over SSH, where they would reach the wrong machine. */
        public List<List<String>> commands() {
            List<List<String>> commands = new ArrayList<>();
            String os = osName.toLowerCase(Locale.ROOT);
            if (set("SSH_CONNECTION") || set("SSH_TTY") || set("SSH_CLIENT")) return commands;
            if (os.startsWith("mac")) {
                commands.add(List.of("pbcopy"));
            } else if (os.startsWith("windows") || set("WSL_DISTRO_NAME") || set("WSL_INTEROP")) {
                commands.add(List.of("clip.exe"));
            } else {
                if (set("WAYLAND_DISPLAY")) commands.add(List.of("wl-copy"));
                if (set("DISPLAY")) {
                    commands.add(List.of("xclip", "-selection", "clipboard", "-in"));
                    commands.add(List.of("xsel", "--clipboard", "--input"));
                }
            }
            return commands;
        }

        private boolean set(String name) {
            String value = environment.get(name);
            return value != null && !value.isEmpty();
        }

        private boolean run(List<String> command, String text) {
            boolean clip = command.getFirst().equals("clip.exe");
            ProcessBuilder builder = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            // pbcopy decodes its input by the locale.
            if (!clip && !utf8Locale()) builder.environment().put("LC_CTYPE", "UTF-8");
            Process process = null;
            try {
                process = builder.start();
                try (OutputStream input = process.getOutputStream()) {
                    // clip.exe reads the console code page unless the text starts with a UTF-16 byte order mark.
                    input.write(clip ? ("\ufeff" + text).getBytes(StandardCharsets.UTF_16LE) : text.getBytes(StandardCharsets.UTF_8));
                }
                return process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0;
            } catch (IOException error) {
                return false;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            } finally {
                if (process != null) process.destroyForcibly();
            }
        }

        private boolean utf8Locale() {
            for (String name : List.of("LC_ALL", "LC_CTYPE", "LANG")) {
                if (set(name)) return environment.get(name).toUpperCase(Locale.ROOT).replace("-", "").contains("UTF8");
            }
            return false;
        }
    }
}
