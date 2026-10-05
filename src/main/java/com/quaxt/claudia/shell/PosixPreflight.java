package com.quaxt.claudia.shell;

import com.fasterxml.jackson.databind.JsonNode;
import com.quaxt.claudia.agent.AgentTool;
import com.quaxt.claudia.ai.json.Json;
import com.quaxt.claudia.ai.types.TextContent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Read-only diagnostics for a Windows POSIX build environment. */
public final class PosixPreflight {
    private static final Map<String, String> MSYS_PACKAGES = Map.of(
            "bash", "bash", "sh", "bash", "make", "make", "find", "findutils",
            "zip", "zip", "unzip", "unzip", "mktemp", "coreutils", "gcc", "gcc");

    private PosixPreflight() {}

    public static AgentTool.ToolResult inspect(Path cwd, String layer, String installationRoot,
            Map<String, String> values, List<String> unset, boolean inherit) throws IOException, InterruptedException {
        if (!ProcessEnvironment.windows()) throw new IllegalStateException("preflight_posix is available on Windows only");
        String selected = layer.toLowerCase(Locale.ROOT);
        if (!selected.equals("msys2") && !selected.equals("git-bash")) {
            throw new IllegalArgumentException("layer must be msys2 or git-bash");
        }
        Map<String, String> environment = new LinkedHashMap<>(System.getenv());
        ProcessEnvironment.apply(environment, values, unset, inherit);
        Path root = installationRoot == null ? defaultRoot(selected, cwd, environment)
                : absolute(cwd, installationRoot);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("layer", selected);
        details.put("installation_root", root.toString());
        details.put("posix_root", "/");
        String path = ProcessEnvironment.get(environment, "PATH");
        details.put("windows_path", path);
        details.put("posix_path", path == null ? null : String.join(":", java.util.Arrays.stream(
                path.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator), -1))
                .map(part -> toPosix(part, root)).toList()));

        Map<String, Object> tools = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        List<String> mixed = new ArrayList<>();
        for (String name : List.of("bash", "make", "sh", "find", "zip", "unzip", "mktemp", "cmd.exe")) {
            Path found = locate(name, cwd, environment);
            Map<String, Object> info = toolInfo(found, root);
            tools.put(name, info);
            if (found == null) missing.add(name);
            else if (!name.equals("cmd.exe") && !inside(found, root)) mixed.add(name + " resolves to " + found);
        }
        details.put("tools", tools);
        details.put("missing_tools", missing);
        details.put("mixed_tools", mixed);

        String compilerRequest = ProcessEnvironment.get(environment, "CC");
        Path compiler = compilerRequest == null || compilerRequest.isBlank() ? null
                : locate(compilerRequest, cwd, environment);
        if (compiler == null && (compilerRequest == null || compilerRequest.isBlank())) {
            for (String candidate : List.of("gcc", "clang", "cl.exe")) {
                compiler = locate(candidate, cwd, environment);
                if (compiler != null) break;
            }
        }
        details.put("compiler", compiler == null ? null : toolInfo(compiler, root));
        details.put("compiler_version", compiler == null ? null : version(compiler));
        if (compiler == null) missing.add("compiler");
        if (selected.equals("msys2")) {
            Map<String, String> packages = new LinkedHashMap<>();
            for (String name : missing) if (MSYS_PACKAGES.containsKey(name)) packages.put(name, MSYS_PACKAGES.get(name));
            if (compiler == null) packages.put("compiler", MSYS_PACKAGES.get("gcc"));
            details.put("missing_packages", packages);
        }

        String tempValue = ProcessEnvironment.get(environment, "TMPDIR");
        if (tempValue == null || tempValue.isBlank()) tempValue = ProcessEnvironment.get(environment, "TEMP");
        if (tempValue == null || tempValue.isBlank()) tempValue = ProcessEnvironment.get(environment, "TMP");
        if (tempValue == null || tempValue.isBlank()) tempValue = cwd.toString();
        Path temp = absolute(cwd, fromPosix(tempValue, root));
        details.put("windows_tmpdir", temp.toString());
        details.put("posix_tmpdir", toPosix(temp.toString(), root));
        String tempError = checkTemp(temp);
        details.put("tmpdir_writable", tempError == null);
        if (tempError != null) details.put("tmpdir_error", tempError);
        details.put("visual_studio_toolsets", vsToolsets(cwd, environment));

        List<String> remediation = new ArrayList<>();
        if (!mixed.isEmpty()) remediation.add("Prepend " + root.resolve("usr/bin") + " to PATH and remove earlier Git for Windows/POSIX tool directories.");
        if (!missing.isEmpty()) remediation.add("Install or add to PATH: " + String.join(", ", missing)
                + (selected.equals("msys2") ? " (MSYS2 package hints are in missing_packages)." : "."));
        if (compiler == null) remediation.add("Set CC to a compiler executable or add the selected compiler's bin directory to PATH.");
        if (tempError != null) remediation.add("Set TMPDIR to a writable workspace directory.");
        details.put("remediation", remediation);
        boolean ok = remediation.isEmpty();
        details.put("ok", ok);
        String content = (ok ? "POSIX preflight passed" : "POSIX preflight found problems")
                + " for " + selected + " at " + root + ".\n"
                + "Tools: " + tools + "\nCompiler: " + compiler + " (" + details.get("compiler_version") + ")"
                + "\nTMPDIR: " + temp + " (" + (tempError == null ? "writable" : tempError) + ")"
                + (remediation.isEmpty() ? "" : "\nRemediation:\n- " + String.join("\n- ", remediation));
        return new AgentTool.ToolResult(List.of(new TextContent(content, null)), details, !ok);
    }

    private static Path defaultRoot(String layer, Path cwd, Map<String, String> environment) {
        if (layer.equals("msys2")) {
            String supplied = ProcessEnvironment.get(environment, "MSYS2_ROOT");
            return supplied == null || supplied.isBlank() ? Path.of("C:\\msys64") : absolute(cwd, supplied);
        }
        Path bash = locate("bash", cwd, environment);
        if (bash != null && bash.toString().toLowerCase(Locale.ROOT).contains("\\git\\")) {
            Path parent = bash.getParent();
            if (parent != null && parent.getFileName() != null && parent.getFileName().toString().equalsIgnoreCase("bin")) {
                Path previous = parent.getParent();
                if (previous != null && previous.getFileName() != null && previous.getFileName().toString().equalsIgnoreCase("usr")) return previous.getParent();
                return previous;
            }
        }
        return Path.of("C:\\Program Files\\Git");
    }

    private static Path locate(String name, Path cwd, Map<String, String> environment) {
        try { return ProcessEnvironment.resolveExecutable(name, cwd, environment); }
        catch (IOException | IllegalArgumentException ignored) { return null; }
    }

    private static Map<String, Object> toolInfo(Path found, Path root) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("windows_path", found == null ? null : found.toString());
        info.put("posix_path", found == null ? null : toPosix(found.toString(), root));
        info.put("origin", found == null ? "missing" : inside(found, root) ? "selected installation" : "outside selected installation");
        return info;
    }

    private static boolean inside(Path path, Path root) {
        String candidate = path.toAbsolutePath().normalize().toString();
        String prefix = root.toAbsolutePath().normalize().toString();
        if (ProcessEnvironment.windows()) {
            candidate = candidate.toLowerCase(Locale.ROOT);
            prefix = prefix.toLowerCase(Locale.ROOT);
        }
        return candidate.equals(prefix) || candidate.startsWith(prefix + java.io.File.separator);
    }

    private static Path absolute(Path cwd, String path) {
        Path candidate = Path.of(path);
        return (candidate.isAbsolute() ? candidate : cwd.resolve(candidate)).toAbsolutePath().normalize();
    }

    private static String fromPosix(String path, Path root) {
        if (ProcessEnvironment.windows() && path.matches("^/[a-zA-Z]/.*")) {
            return Character.toUpperCase(path.charAt(1)) + ":\\" + path.substring(3).replace('/', '\\');
        }
        if (path.startsWith("/") && ProcessEnvironment.windows()) return root.resolve(path.substring(1)).toString();
        return path;
    }

    private static String toPosix(String path, Path root) {
        try {
            Path candidate = Path.of(path).toAbsolutePath().normalize();
            if (inside(candidate, root)) return "/" + root.toAbsolutePath().normalize().relativize(candidate).toString().replace('\\', '/');
            if (ProcessEnvironment.windows() && path.matches("^[a-zA-Z]:.*")) {
                return "/" + Character.toLowerCase(path.charAt(0)) + path.substring(2).replace('\\', '/');
            }
            return candidate.toString().replace('\\', '/');
        } catch (RuntimeException ignored) { return path.replace('\\', '/'); }
    }

    private static String checkTemp(Path directory) {
        Path file = null;
        String problem = null;
        try {
            file = Files.createTempFile(directory, "claudia-preflight-", ".tmp");
            Files.writeString(file, "preflight", StandardCharsets.UTF_8);
            if (!Files.readString(file, StandardCharsets.UTF_8).equals("preflight")) problem = "temporary file readback failed";
        } catch (IOException | SecurityException error) {
            problem = error.getMessage();
        } finally {
            if (file != null) try { Files.deleteIfExists(file); }
            catch (IOException error) { problem = "temporary file could not be deleted: " + error.getMessage(); }
        }
        return problem;
    }

    private static String version(Path executable) throws InterruptedException {
        try {
            boolean msvc = executable.getFileName().toString().equalsIgnoreCase("cl.exe");
            Process process = new ProcessBuilder(msvc ? List.of(executable.toString())
                    : List.of(executable.toString(), "--version")).redirectErrorStream(true).start();
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    return "version query timed out";
                }
                String output = new String(process.getInputStream().readNBytes(4096), StandardCharsets.UTF_8);
                return output.lines().findFirst().orElse("").strip();
            } finally { if (process.isAlive()) process.destroyForcibly(); }
        } catch (IOException error) { return "version query failed: " + error.getMessage(); }
    }

    private static List<Map<String, Object>> vsToolsets(Path cwd, Map<String, String> environment) throws InterruptedException {
        Path vswhere = locate("vswhere.exe", cwd, environment);
        if (vswhere == null) {
            String programFiles = ProcessEnvironment.get(environment, "ProgramFiles(x86)");
            if (programFiles != null) {
                Path candidate = Path.of(programFiles, "Microsoft Visual Studio", "Installer", "vswhere.exe");
                if (Files.isRegularFile(candidate)) vswhere = candidate;
            }
        }
        if (vswhere == null) return List.of();
        Process process;
        try {
            process = new ProcessBuilder(vswhere.toString(), "-all", "-products", "*", "-format", "json")
                    .redirectErrorStream(true).start();
        } catch (IOException error) { return List.of(); }
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return List.of();
            }
            JsonNode instances = Json.MAPPER.readTree(process.getInputStream().readNBytes(1_000_000));
            List<Map<String, Object>> found = new ArrayList<>();
            if (instances != null && instances.isArray()) for (JsonNode instance : instances) {
                String installation = instance.path("installationPath").asText("");
                Path toolsets = Path.of(installation, "VC", "Tools", "MSVC");
                if (!Files.isDirectory(toolsets)) continue;
                try (var versions = Files.list(toolsets)) {
                    for (Path version : versions.filter(Files::isDirectory).toList()) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("installation_path", installation);
                        item.put("product_version", instance.path("installationVersion").asText(""));
                        item.put("msvc_version", version.getFileName().toString());
                        item.put("toolset_path", version.toString());
                        found.add(item);
                    }
                }
            }
            return found;
        } catch (IOException | RuntimeException error) { return List.of(); }
        finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}
