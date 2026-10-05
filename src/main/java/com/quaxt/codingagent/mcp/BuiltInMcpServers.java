package com.quaxt.codingagent.mcp;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * MCP servers that codingagent supplies without any settings-file configuration.
 *
 * <p>The only built-in server is code-lens. It is located inside the checkout that
 * built codingagent: an in-place build (see {@code install.sh}/{@code install.ps1})
 * leaves codingagent in {@code <repo>/target/} and code-lens in
 * {@code <repo>/code-lens/build/}. When that binary is absent the server is simply
 * not offered. Enable/disable choices are stored under {@link #SETTINGS_KEY} in
 * the settings file, never in its OpenCode-compatible {@code mcp} object.
 */
public final class BuiltInMcpServers {
	public static final String CODE_LENS = "code-lens";
	/** Top-level settings object holding a {@link Preference} per built-in server name. */
	public static final String SETTINGS_KEY = "builtInMcp";

	/** Stored enable/disable choices for one built-in server. */
	public record Preference(boolean enabled, List<String> disabledTools) {
		public static final Preference DEFAULT = new Preference(true, List.of());

		public Preference {
			disabledTools = List.copyOf(disabledTools);
		}
	}

	private BuiltInMcpServers() {}

	/** Finds the code-lens binary that belongs to the running codingagent build, if one was built. */
	public static Optional<Path> locateCodeLens() {
		Path artifact = runningArtifact();
		return artifact == null ? Optional.empty() : codeLensFor(artifact, isWindows());
	}

	/**
	 * Returns {@code <repo>/code-lens/build/code-lens[.exe]} for a codingagent jar or
	 * native executable at {@code <repo>/target/<artifact>}, when it is an executable file.
	 */
	public static Optional<Path> codeLensFor(Path artifact, boolean windows) {
		Path directory = artifact.toAbsolutePath().normalize().getParent();
		Path checkout = directory == null ? null : directory.getParent();
		if (checkout == null) return Optional.empty();
		Path binary = checkout.resolve("code-lens").resolve("build").resolve(windows ? "code-lens.exe" : "code-lens");
		return Files.isRegularFile(binary) && Files.isExecutable(binary) ? Optional.of(binary) : Optional.empty();
	}

	/** The local server configuration that runs {@code binary} as code-lens's MCP stdio server. */
	public static McpServerConfig.Local codeLens(Path binary, Preference preference) {
		return new McpServerConfig.Local(
				List.of(binary.toString(), "mcp"),
				null,
				Map.of(),
				preference.enabled(),
				null,
				List.of(),
				preference.disabledTools(),
				true);
	}

	/**
	 * The jar or native executable codingagent is running from. Classes directories
	 * (tests and IDE runs) are not a build, so they report none and stay hermetic.
	 */
	private static Path runningArtifact() {
		try {
			Path location;
			if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
				Optional<String> command = ProcessHandle.current().info().command();
				if (command.isEmpty()) return null;
				location = Path.of(command.get());
			} else {
				CodeSource source = BuiltInMcpServers.class.getProtectionDomain().getCodeSource();
				if (source == null || source.getLocation() == null) return null;
				location = Path.of(source.getLocation().toURI());
			}
			if (!Files.isRegularFile(location)) return null;
			try {
				// A launcher may reach the build through a symbolic link.
				return location.toRealPath();
			} catch (IOException error) {
				return location;
			}
		} catch (URISyntaxException | RuntimeException error) {
			return null;
		}
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
	}
}
