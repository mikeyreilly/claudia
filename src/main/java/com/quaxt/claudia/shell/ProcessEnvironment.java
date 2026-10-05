package com.quaxt.claudia.shell;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Environment and executable resolution shared by direct processes and POSIX preflight. */
public final class ProcessEnvironment {
    private ProcessEnvironment() {}

    public static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public static void apply(Map<String, String> target, Map<String, String> values,
            List<String> unset, boolean inherit) {
        if (!inherit) {
            Map<String, String> original = Map.copyOf(target);
            target.clear();
            if (windows()) {
                for (String key : List.of("SystemRoot", "WINDIR", "ComSpec", "PATHEXT")) {
                    String value = get(original, key);
                    if (value != null) target.put(key, value);
                }
            }
        }
        for (var entry : values.entrySet()) {
            validateKey(entry.getKey());
            validate(entry.getValue(), "environment value");
            target.put(entry.getKey(), entry.getValue());
        }
        for (String key : unset) {
            validateKey(key);
            remove(target, key);
        }
    }

    public static String get(Map<String, String> environment, String key) {
        if (!windows()) return environment.get(key);
        for (var entry : environment.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) return entry.getValue();
        }
        return null;
    }

    private static void remove(Map<String, String> environment, String key) {
        if (windows()) environment.keySet().removeIf(candidate -> candidate.equalsIgnoreCase(key));
        else environment.remove(key);
    }

    private static void validateKey(String key) {
        validate(key, "environment name");
        if (key.isEmpty() || key.indexOf('=') >= 0) throw new IllegalArgumentException("Invalid environment name: " + key);
    }

    public static void validate(String value, String label) {
        if (value == null || value.indexOf('\0') >= 0) throw new IllegalArgumentException(label + " contains NUL or is null");
    }

    /** ProcessBuilder's Windows legacy quoting drops literal quotes unless they are escaped. */
    public static String processBuilderArgument(String argument) {
        if (!windows() || argument.indexOf('"') < 0) return argument;
        StringBuilder escaped = new StringBuilder(argument.length() + 8);
        int backslashes = 0;
        for (int index = 0; index < argument.length(); index++) {
            char character = argument.charAt(index);
            if (character == '\\') {
                backslashes++;
            } else if (character == '"') {
                escaped.append("\\".repeat(backslashes * 2 + 1)).append('"');
                backslashes = 0;
            } else {
                escaped.append("\\".repeat(backslashes)).append(character);
                backslashes = 0;
            }
        }
        escaped.append("\\".repeat(backslashes));
        return escaped.toString();
    }

    /** Resolve before launching so Windows batch files cannot silently reparse arguments. */
    public static Path resolveExecutable(String executable, Path cwd, Map<String, String> environment) throws IOException {
        validate(executable, "executable");
        if (executable.isBlank()) throw new IllegalArgumentException("executable must not be blank");
        if (batch(executable)) throw new IllegalArgumentException("run_process does not support Windows batch files; use shell for .bat/.cmd");
        Path requested = Path.of(executable);
        if (requested.isAbsolute() || executable.contains("/") || executable.contains("\\")) {
            Path path = requested.isAbsolute() ? requested : cwd.resolve(requested);
            path = path.toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) throw new IOException("Executable not found: " + path);
            return path;
        }
        String pathValue = get(environment, "PATH");
        if (pathValue != null) {
            for (String part : pathValue.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator), -1)) {
                Path directory = part.isBlank() ? cwd : Path.of(part);
                if (!directory.isAbsolute()) directory = cwd.resolve(directory);
                List<String> names = new ArrayList<>();
                names.add(executable);
                if (windows() && !executable.toLowerCase(Locale.ROOT).endsWith(".exe")) names.add(executable + ".exe");
                for (String name : names) {
                    Path candidate = directory.resolve(name).toAbsolutePath().normalize();
                    if (!batch(candidate.toString()) && Files.isRegularFile(candidate)) return candidate;
                }
            }
        }
        throw new IOException("Executable not found on child PATH: " + executable);
    }

    private static boolean batch(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return windows() && (lower.endsWith(".bat") || lower.endsWith(".cmd"));
    }
}
